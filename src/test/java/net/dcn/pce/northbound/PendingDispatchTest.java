package net.dcn.pce.northbound;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.install.InstallationState;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A schedule planned before any router connected must still be installed once one does.
 *
 * <p>Automatic dispatch chose a PCC at solve time and gave up if there was none: the intent stayed
 * {@code PLANNED}, its reservations stayed committed, and nothing ever revisited it. The capacity
 * was therefore spent on a deadline that could not be met, and connecting the router afterwards
 * did not help — the route and rate needed to encode a PCInitiate live on the solve result, and
 * an {@link net.dcn.pce.install.InstallationIntent} carries an LSP name, not a path.
 *
 * <p>Retrying this case is safe precisely because the transport established that nothing left the
 * process. An {@code UNCERTAIN} operation means the opposite and is not retried.
 */
class PendingDispatchTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e10, 1e9, "10.0.0.1"));
        topology.addNode(new Node("B", "B", 1e10, 1e9, "10.0.0.2"));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e8, 0.0001)));
        return topology;
    }

    private static String oneTask() {
        return "[{\"taskId\": \"T1\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\","
                + " \"originationTimeSec\": 0, \"deadlineSec\": 100,"
                + " \"taskSizeBytes\": 4000000, \"priority\": 1}]";
    }

    @Test
    void aScheduleCommittedWithNoPccIsDispatchedWhenOneConnects(@TempDir Path dir)
            throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology());

        AtomicReference<Optional<String>> pcc = new AtomicReference<>(Optional.empty());
        List<String> sent = new ArrayList<>();
        controller.setInstallDispatch(pcc::get, (intent, srpId, route, rateBps) -> {
            sent.add(intent.getTaskId());
            return CRPEngine.DispatchOutcome.SENT;
        });

        // No router yet.
        controller.handleScheduleWorkloadsRequest(oneTask());
        assertTrue(sent.isEmpty(), "nothing can be sent with no PCC");
        assertEquals(InstallationState.PLANNED,
                engine.getIntents().find("T1").orElseThrow().getState());
        assertTrue(controller.pendingDispatchTaskIds().contains("T1"),
                "the schedule must be held, or it can never be encoded again");

        // A router connects and finishes synchronising.
        pcc.set(Optional.of("speaker:pcc-alpha"));
        List<String> outcomes = controller.dispatchPending();

        assertEquals(List.of("T1"), sent, "the held schedule should have been installed");
        assertEquals(InstallationState.INSTALLING,
                engine.getIntents().find("T1").orElseThrow().getState());
        assertTrue(outcomes.stream().anyMatch(line -> line.contains("T1")), outcomes.toString());
    }

    @Test
    void aTaskCancelledBeforeThePccConnectsIsNotInstalled(@TempDir Path dir) throws Exception {
        // The held schedule must not resurrect work the operator withdrew in the meantime.
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology());

        AtomicReference<Optional<String>> pcc = new AtomicReference<>(Optional.empty());
        List<String> sent = new ArrayList<>();
        controller.setInstallDispatch(pcc::get, (intent, srpId, route, rateBps) -> {
            sent.add(intent.getTaskId());
            return CRPEngine.DispatchOutcome.SENT;
        });

        controller.handleScheduleWorkloadsRequest(oneTask());
        assertTrue(controller.pendingDispatchTaskIds().contains("T1"));

        controller.cancelTask("T1");
        pcc.set(Optional.of("speaker:pcc-alpha"));
        controller.dispatchPending();

        assertTrue(sent.isEmpty(), "a cancelled task must not be installed by the retry path");
    }

    @Test
    void dispatchingTwiceDoesNotSendTwice(@TempDir Path dir) throws Exception {
        // Synchronisation completes on every reconnect, so this path runs repeatedly. Only an
        // intent still PLANNED is eligible, which is what keeps a reconnect from duplicating.
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology());

        List<String> sent = new ArrayList<>();
        controller.setInstallDispatch(() -> Optional.of("speaker:pcc-alpha"),
                (intent, srpId, route, rateBps) -> {
                    sent.add(intent.getTaskId());
                    return CRPEngine.DispatchOutcome.SENT;
                });

        controller.handleScheduleWorkloadsRequest(oneTask());
        assertEquals(List.of("T1"), sent, "dispatched at solve time when a PCC is present");

        controller.dispatchPending();
        controller.dispatchPending();
        assertEquals(List.of("T1"), sent, "a reconnect must not re-send an installing LSP");
    }
}
