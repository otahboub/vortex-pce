package net.dcn.pce.northbound;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A capacity observation must not land in the middle of a solve.
 *
 * <p>Both operations were individually safe. A solve reads the active topology and admits against
 * it; recording an observation independently checked the reservation ledger and then replaced the
 * topology. Nothing held both, so an observation could pass its committed-capacity check while a
 * solve still holding the old topology had not yet committed — and that solve would then reserve
 * bandwidth the link no longer has. The result is the over-subscribed ledger the 409 refusal
 * exists to prevent, arrived at by a route the refusal never saw.
 *
 * <p>The race is driven deterministically by holding the solve-side lock, rather than starting a
 * real solve and hoping the window opens. A timing-dependent test for a timing bug tends to pass
 * for the wrong reason.
 */
class CapacityUpdateRacesSolveTest {

    private static BaseTopology topology(double linkBps) {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e10, 1e9));
        topology.addNode(new Node("B", "B", 1e10, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(linkBps, 0.0001)));
        return topology;
    }

    /** Holds the solve-side lock on another thread until released, like a solve in progress. */
    private static final class HeldByAnotherThread implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread thread;

        HeldByAnotherThread(PCERestController controller) throws Exception {
            CountDownLatch held = new CountDownLatch(1);
            this.thread = new Thread(() -> {
                controller.topologyLock.readLock().lock();
                try {
                    held.countDown();
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    controller.topologyLock.readLock().unlock();
                }
            }, "fake-solve");
            thread.setDaemon(true);
            thread.start();
            assertTrue(held.await(5, TimeUnit.SECONDS), "the fake solve never took the lock");
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void anObservationIsRefusedWhileASolveHoldsTheTopology(@TempDir Path dir) throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology(1e8));
        controller.capacityLockWaitMillis = 150;

        // Another thread, deliberately. An earlier version of this test took the read lock on the
        // test thread itself and passed -- but for the wrong reason: a ReentrantReadWriteLock
        // refuses to upgrade read to write on one thread, so it failed instantly through
        // deadlock avoidance and never exercised the exclusion it claimed to.
        try (HeldByAnotherThread ignored = new HeldByAnotherThread(controller)) {
            PCERestController.CapacityUpdateBusyException busy = assertThrows(
                    PCERestController.CapacityUpdateBusyException.class,
                    () -> controller.recordObservedCapacity("A-B", 1e6));
            assertTrue(busy.getMessage().contains("solve is in flight"), busy.getMessage());

            // Refused, not partially applied.
            assertEquals(1e8, controller.getActiveTopology().getLink("A-B")
                    .getLif().getBaseBandwidthBps());
        }

        // And it applies once the solve lets go, so this is exclusion rather than a wall.
        assertEquals(1e8, controller.recordObservedCapacity("A-B", 1e6));
        assertEquals(1e6, controller.getActiveTopology().getLink("A-B")
                .getLif().getBaseBandwidthBps());
    }

    @Test
    void theLedgerNeverHoldsMoreThanTheLinkAfterAnAcceptedObservation(@TempDir Path dir)
            throws Exception {
        // The invariant the lock protects, stated directly: whatever the ordering, an accepted
        // observation leaves every reservation within the observed capacity.
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology(1e8));

        controller.handleScheduleWorkloadsRequest(
                "[{\"taskId\": \"T1\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\","
                        + " \"originationTimeSec\": 0, \"deadlineSec\": 100,"
                        + " \"taskSizeBytes\": 20000000, \"priority\": 1}]");

        double committed = engine.getLRIB().getAllReservations().stream()
                .filter(r -> "A-B".equals(r.getLinkId()))
                .mapToDouble(net.dcn.pce.rib.LRIB.LinkReservation::getReservedBwBps)
                .max().orElse(0.0);
        assertTrue(committed > 0, "the solve should have reserved something to make this a test");

        assertThrows(IllegalStateException.class,
                () -> controller.recordObservedCapacity("A-B", committed / 2));
        assertFalse(controller.getActiveTopology().getLink("A-B").getLif().getBaseBandwidthBps()
                        < committed,
                "a refused observation must not have lowered the link below its commitments");
    }

    /**
     * An engine whose solve can be held open, so a *real* solve is in flight during the test.
     *
     * <p>The other tests here hold the lock directly, which proves the lock excludes but not that
     * the solve path takes it — they passed with the solve-side lock deleted. This one drives
     * {@code handleScheduleWorkloadsRequest} and fails if that path stops holding the topology.
     */
    private static final class HeldSolveEngine extends CRPEngine {
        private final CountDownLatch inSolve = new CountDownLatch(1);
        private final CountDownLatch mayFinish = new CountDownLatch(1);

        // The four-argument solve is the real entry point; the three-argument one delegates to
        // it. Overriding the delegating signature no longer intercepts anything.
        @Override
        public synchronized PCEComputationResult solve(
                BaseTopology topology, java.util.List<net.dcn.pce.model.WorkloadTask> tasks,
                net.dcn.pce.crp.SolveCancellation cancellation, String owner) {
            inSolve.countDown();
            try {
                mayFinish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.solve(topology, tasks, cancellation, owner);
        }
    }

    @Test
    void arealSolveInFlightBlocksTheObservation(@TempDir Path dir) throws Exception {
        HeldSolveEngine engine = new HeldSolveEngine();
        engine.withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology(1e8));
        controller.capacityLockWaitMillis = 200;

        Thread solving = new Thread(() -> {
            try {
                controller.handleScheduleWorkloadsRequest(
                        "[{\"taskId\": \"T1\", \"sourceNodeId\": \"A\","
                                + " \"destinationNodeId\": \"B\", \"originationTimeSec\": 0,"
                                + " \"deadlineSec\": 100, \"taskSizeBytes\": 20000000,"
                                + " \"priority\": 1}]");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "real-solve");
        solving.setDaemon(true);
        solving.start();

        assertTrue(engine.inSolve.await(5, TimeUnit.SECONDS), "solve never started");
        assertThrows(PCERestController.CapacityUpdateBusyException.class,
                () -> controller.recordObservedCapacity("A-B", 1e6),
                "an observation must not apply while a solve holds the topology");
        assertEquals(1e8, controller.getActiveTopology().getLink("A-B")
                .getLif().getBaseBandwidthBps());

        engine.mayFinish.countDown();
        solving.join(TimeUnit.SECONDS.toMillis(10));

        // Once the solve lets go the observation applies -- above what that solve committed,
        // since dropping below it is refused by the over-subscription rule and would be testing
        // that instead.
        assertEquals(1e8, controller.recordObservedCapacity("A-B", 8e7));
        assertEquals(8e7, controller.getActiveTopology().getLink("A-B")
                .getLif().getBaseBandwidthBps());
    }
}
