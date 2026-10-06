package net.dcn.pce.install;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import net.dcn.pce.northbound.PCERestController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An installation waiting for a router must survive the controller restarting first.
 *
 * <p>A solve that commits while no PCC is connected holds its capacity and waits. Those held
 * requests lived only in memory, so a controller that restarted before its router arrived kept the
 * reservations for ever and could never install them: the route and rate a PCInitiate is built
 * from were gone, and an {@link InstallationIntent} carries an LSP name, not a path.
 */
class DurablePendingDispatchTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e10, 1e9, "10.0.0.1"));
        topology.addNode(new Node("B", "B", 1e10, 1e9, "10.0.0.2"));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e8, 0.0001)));
        return topology;
    }

    private static final String TASK =
            "[{\"taskId\": \"T1\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\","
                    + " \"originationTimeSec\": 0, \"deadlineSec\": 100,"
                    + " \"taskSizeBytes\": 4000000, \"priority\": 1}]";

    /**
     * One controller's lifetime over a state directory.
     *
     * <p>Closing it releases the single-writer lock on the reservation log, which is what makes
     * the next one a restart rather than a second controller. The log refuses the latter, and
     * rightly — an earlier version of this test hit that refusal because it never let go.
     */
    private record Running(PCERestController controller,
                           net.dcn.pce.rib.FileWalReservationStore store) implements AutoCloseable {
        @Override
        public void close() {
            store.close();
        }
    }

    private static Running start(Path dir, BaseTopology topology,
                                 AtomicReference<Optional<String>> pcc, List<String> sent) {
        String statePath = dir.resolve("state.json").toString();
        net.dcn.pce.rib.FileWalReservationStore store =
                new net.dcn.pce.rib.FileWalReservationStore(statePath);
        CRPEngine engine = new CRPEngine().withReservationStore(store);
        PCERestController controller = new PCERestController(engine, topology);
        controller.withDurablePendingDispatch(PendingDispatchStore.besideState(statePath));
        controller.setInstallDispatch(pcc::get, (intent, srpId, route, rateBps) -> {
            sent.add(intent.getTaskId() + "@" + route.size() + "hops@" + (long) rateBps);
            return CRPEngine.DispatchOutcome.SENT;
        });
        return new Running(controller, store);
    }

    @Test
    void anInstallationWaitingForAPccSurvivesARestart(@TempDir Path dir) throws Exception {
        AtomicReference<Optional<String>> pcc = new AtomicReference<>(Optional.empty());
        List<String> sent = new ArrayList<>();

        try (Running before = start(dir, topology(), pcc, sent)) {
            before.controller().handleScheduleWorkloadsRequest(TASK);
            assertTrue(sent.isEmpty(), "nothing can be sent with no PCC");
            assertTrue(Files.exists(dir.resolve("pending-dispatch.json")),
                    "the request must be on disk, or a restart cannot encode it");
        }

        // Restart, still with no router.
        try (Running after = start(dir, topology(), pcc, sent)) {
            assertTrue(after.controller().pendingDispatchTaskIds().contains("T1"),
                    "the restarted controller must still know what it owes");

            // The router finally connects.
            pcc.set(Optional.of("speaker:pcc-alpha"));
            after.controller().dispatchPending();

            assertEquals(1, sent.size(), "the surviving request should have been installed: " + sent);
            assertTrue(sent.get(0).startsWith("T1@1hops@"), sent.get(0));
            assertEquals(InstallationState.INSTALLING,
                    after.controller().findIntent("T1").orElseThrow().getState());
        }
    }

    @Test
    void aRequestWhoseRouteNoLongerExistsIsNotSent(@TempDir Path dir) throws Exception {
        // The topology file can change across a restart. Sending a path over a link that is gone
        // is worse than not sending: the reservation stays visible and cancellable rather than
        // becoming an LSP nobody can account for.
        AtomicReference<Optional<String>> pcc = new AtomicReference<>(Optional.empty());
        List<String> sent = new ArrayList<>();
        try (Running before = start(dir, topology(), pcc, sent)) {
            before.controller().handleScheduleWorkloadsRequest(TASK);
        }

        BaseTopology rerouted = new BaseTopology();
        rerouted.setRegime(ContactRegime.R_STATIC);
        rerouted.addNode(new Node("A", "A", 1e10, 1e9, "10.0.0.1"));
        rerouted.addNode(new Node("B", "B", 1e10, 1e9, "10.0.0.2"));
        rerouted.addLink(new Link("A-B-NEW", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e8, 0.0001)));

        try (Running after = start(dir, rerouted, pcc, sent)) {
            pcc.set(Optional.of("speaker:pcc-alpha"));
            List<String> outcomes = after.controller().dispatchPending();

            assertTrue(sent.isEmpty(), "a route that no longer exists must not be installed");
            assertTrue(outcomes.stream().anyMatch(line -> line.contains("no longer exists")),
                    outcomes.toString());
            assertFalse(after.controller().pendingDispatchTaskIds().contains("T1"),
                    "and it must not be retried for ever");
        }
    }

    @Test
    void anUnreadableFileDoesNotPreventStartup(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pending-dispatch.json"), "{ not json");
        AtomicReference<Optional<String>> pcc = new AtomicReference<>(Optional.empty());
        try (Running running = start(dir, topology(), pcc, new ArrayList<>())) {
            assertTrue(running.controller().pendingDispatchTaskIds().isEmpty());
        }
    }

    @Test
    void aCrashBetweenTheWalCommitAndTheSidecarStillRecovers(@TempDir Path dir) throws Exception {
        // The reservation and intent commit to the WAL; the dispatch sidecar is written after. A
        // crash in that gap leaves a PLANNED intent holding capacity with no sidecar entry. The
        // route and rate are still in the WAL as the reservation, so recovery must not depend on
        // the sidecar -- deleting it stands in for the crash.
        AtomicReference<Optional<String>> pcc = new AtomicReference<>(Optional.empty());
        List<String> sent = new ArrayList<>();

        try (Running before = start(dir, topology(), pcc, sent)) {
            before.controller().handleScheduleWorkloadsRequest(TASK);
        }
        Files.delete(dir.resolve("pending-dispatch.json"));   // the sidecar never made it to disk

        try (Running after = start(dir, topology(), pcc, sent)) {
            assertTrue(after.controller().pendingDispatchTaskIds().contains("T1"),
                    "the dispatch must be reconstructed from the reservation, not lost with the sidecar");
            pcc.set(Optional.of("speaker:pcc-alpha"));
            after.controller().dispatchPending();
            assertEquals(1, sent.size(), "the reconstructed request installs: " + sent);
            assertTrue(sent.get(0).startsWith("T1@1hops@"), sent.get(0));
        }
    }

    @Test
    void workIsRefusedRatherThanHeldUndispatchableWhenTheBufferIsFull(@TempDir Path dir)
            throws Exception {
        AtomicReference<Optional<String>> pcc = new AtomicReference<>(Optional.empty());
        try (Running running = start(dir, topology(), pcc, new ArrayList<>())) {
            // A bound low enough to reach in one request (the per-request cap is 1000, below the
            // production 1024 bound). More tasks than the bound, with no PCC to drain them:
            // admitting any would commit capacity that can never be installed, so the whole batch
            // is refused before the solve rather than committed and then silently dropped.
            running.controller().setMaxUndispatchedForTest(2);
            StringBuilder batch = new StringBuilder("[");
            for (int i = 1; i <= 3; i++) {
                if (i > 1) {
                    batch.append(',');
                }
                batch.append("{\"taskId\": \"B").append(i)
                        .append("\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\","
                                + " \"originationTimeSec\": 0, \"deadlineSec\": 100,"
                                + " \"taskSizeBytes\": 1000, \"priority\": 1}");
            }
            batch.append(']');

            assertThrows(PCERestController.UndispatchableBacklogException.class,
                    () -> running.controller().handleScheduleWorkloadsRequest(batch.toString()),
                    "an over-capacity batch with no PCC must be refused up front");
            assertTrue(running.controller().pendingDispatchTaskIds().isEmpty(),
                    "and nothing may have been committed");
            assertEquals(0, running.controller().getLinkReservationCount(),
                    "no capacity may be held for the refused batch");
        }
    }
}
