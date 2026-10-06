package net.dcn.pce.install;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import net.dcn.pce.northbound.PCERestController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Capacity must not be held for work that can never be installed.
 *
 * <p>A solve that commits while no PCC is connected keeps its route and rate so the install can be
 * attempted when a router arrives. The reservation is already durable by then, so a dispatch
 * record that cannot be written leaves capacity reserved for work the controller has permanently
 * lost the means to encode: accepted, paid for, and impossible to install. The failure used to be
 * logged and swallowed.
 */
class UnrecordableDispatchIsReleasedTest {

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

    @Test
    void aTaskWhoseDispatchRecordCannotBePersistedIsReleased(@TempDir Path dir) throws Exception {
        // A file where the state directory must be, so every sidecar write fails.
        Path blocked = dir.resolve("blocked");
        Files.createFile(blocked);

        CRPEngine engine = new CRPEngine();
        PCERestController controller = new PCERestController(engine, topology());
        controller.withDurablePendingDispatch(
                PendingDispatchStore.besideState(blocked.resolve("state.json").toString()));
        // No PCC, so the schedule is held for later rather than sent.
        controller.setInstallDispatch(Optional::empty,
                (intent, srpId, route, rateBps) -> CRPEngine.DispatchOutcome.NOT_ATTEMPTED);

        assertThrows(java.io.UncheckedIOException.class,
                () -> controller.handleScheduleWorkloadsRequest(TASK),
                "the solve must not acknowledge a task whose install request was lost");

        assertFalse(controller.pendingDispatchTaskIds().contains("T1"),
                "a request that could not be recorded must not appear to be waiting");
        assertEquals(0, engine.getLRIB().getAllReservations().stream()
                        .filter(r -> "T1".equals(r.getTaskId())).count(),
                "its capacity must be released rather than held for work that cannot be installed");
    }

    @Test
    void aWorkingStoreStillHoldsTheRequest(@TempDir Path dir) throws Exception {
        CRPEngine engine = new CRPEngine();
        PCERestController controller = new PCERestController(engine, topology());
        controller.withDurablePendingDispatch(
                PendingDispatchStore.besideState(dir.resolve("state.json").toString()));
        controller.setInstallDispatch(Optional::empty,
                (intent, srpId, route, rateBps) -> CRPEngine.DispatchOutcome.NOT_ATTEMPTED);

        controller.handleScheduleWorkloadsRequest(TASK);

        assertTrue(controller.pendingDispatchTaskIds().contains("T1"));
        assertTrue(engine.getLRIB().getAllReservations().stream()
                        .anyMatch(r -> "T1".equals(r.getTaskId())),
                "the reservation is kept when the request was recorded");
    }
}
