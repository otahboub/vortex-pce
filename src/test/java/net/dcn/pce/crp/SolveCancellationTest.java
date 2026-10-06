package net.dcn.pce.crp;

import net.dcn.pce.crp.policy.RouteGenerationPolicy;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.LinkIntermittencyFunction;
import net.dcn.pce.model.Node;
import net.dcn.pce.model.WorkloadTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolveCancellationTest {

    /** Dense enough that exhaustive enumeration runs for a long time. */
    private static BaseTopology denseMesh(int nodes) {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        for (int i = 0; i < nodes; i++) {
            topology.addNode(new Node("N" + i, "N" + i, 1e9, 1e9));
        }
        for (int i = 0; i < nodes; i++) {
            for (int j = 0; j < nodes; j++) {
                if (i != j) {
                    topology.addLink(new Link("L" + i + "-" + j, "N" + i, "N" + j,
                            LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
                }
            }
        }
        return topology;
    }

    @Test
    void anUncancelledTokenReportsNoReason() {
        assertNull(SolveCancellation.unbounded().cancellationReason());
        assertNull(SolveCancellation.withBudgetNanos(TimeUnit.MINUTES.toNanos(5)).cancellationReason());
    }

    @Test
    void anExpiredBudgetReportsDeadline() {
        SolveCancellation token = SolveCancellation.withBudgetNanos(1);
        assertEquals(SolveCancellation.Reason.DEADLINE, token.cancellationReason());
    }

    @Test
    void theFirstReasonWinsSoTheReportedCauseIsTheOneThatStopped() {
        SolveCancellation token = SolveCancellation.unbounded();
        token.cancel(SolveCancellation.Reason.SHUTDOWN);
        token.cancel(SolveCancellation.Reason.CLIENT_ABORT);

        assertEquals(SolveCancellation.Reason.SHUTDOWN, token.cancellationReason());
    }

    @Test
    void anUnboundedTokenIsStillCancellable() {
        SolveCancellation token = SolveCancellation.unbounded();
        assertNull(token.cancellationReason());

        token.cancel(SolveCancellation.Reason.EXPLICIT);

        assertEquals(SolveCancellation.Reason.EXPLICIT, token.cancellationReason());
    }

    /**
     * The point of the token: a solve stops for a reason it cannot observe itself. A deadline is
     * self-evident to the planner; a shutdown is not.
     */
    @Test
    void aSolveStopsWhenAnotherThreadCancelsIt() throws Exception {
        BaseTopology topology = denseMesh(11);
        List<WorkloadTask> tasks = new ArrayList<>();
        tasks.add(new WorkloadTask("BIG", "N0", "N10", 0, 1_000_000, 1_000));

        CRPEngine engine = new CRPEngine().withFGenerate(RouteGenerationPolicy.K_MAX_EXHAUSTIVE);
        SolveCancellation token = SolveCancellation.unbounded();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread solver = new Thread(() -> {
            started.countDown();
            try {
                engine.solve(topology, tasks, token);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        solver.setDaemon(true);
        solver.start();

        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread.sleep(150); // let the enumeration get underway
        token.cancel(SolveCancellation.Reason.SHUTDOWN);

        solver.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue(!solver.isAlive(), "the solve should have stopped promptly after cancellation");

        Throwable error = thrown.get();
        assertNotNull(error, "cancellation should surface as an exception, not a silent result");
        assertTrue(error instanceof SolveTimeoutException, "unexpected: " + error);
        assertEquals(SolveCancellation.Reason.SHUTDOWN, ((SolveTimeoutException) error).getReason());
        assertTrue(error.getMessage().contains("shutting down"),
                "the message should name the cause: " + error.getMessage());
    }

    @Test
    void aCancelledSolveCommitsNothing() throws Exception {
        BaseTopology topology = denseMesh(11);
        CRPEngine engine = new CRPEngine()
                .withPersistentState(true)
                .withFGenerate(RouteGenerationPolicy.K_MAX_EXHAUSTIVE);
        SolveCancellation token = SolveCancellation.unbounded();
        token.cancel(SolveCancellation.Reason.CLIENT_ABORT);

        assertThrows(SolveTimeoutException.class, () -> engine.solve(topology,
                List.of(new WorkloadTask("GONE", "N0", "N10", 0, 1_000_000, 1_000)), token));

        assertEquals(0, engine.getLRIB().getAllReservations().size(),
                "a cancelled solve must roll back like any other aborted transaction");
        assertEquals(0, engine.getNRIB().getAllReservations().size());
    }

    @Test
    void interruptionStopsASolveBecauseExecutorShutdownUsesIt() throws Exception {
        BaseTopology topology = denseMesh(11);
        CRPEngine engine = new CRPEngine().withFGenerate(RouteGenerationPolicy.K_MAX_EXHAUSTIVE);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread solver = new Thread(() -> {
            started.countDown();
            try {
                engine.solve(topology,
                        List.of(new WorkloadTask("BIG", "N0", "N10", 0, 1_000_000, 1_000)),
                        SolveCancellation.unbounded());
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        solver.setDaemon(true);
        solver.start();

        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread.sleep(150);
        solver.interrupt();

        solver.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue(!solver.isAlive(), "shutdownNow() interrupts; the planner must honour that");
        assertTrue(thrown.get() instanceof SolveTimeoutException,
                "interruption should abort the solve, got: " + thrown.get());
    }
}
