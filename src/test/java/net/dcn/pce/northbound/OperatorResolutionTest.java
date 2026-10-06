package net.dcn.pce.northbound;

import net.dcn.pce.config.OperatorConfiguration;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.install.InstallationState;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An operator must be able to end an uncertain operation.
 *
 * <p>{@code UNCERTAIN} is the state the controller cannot leave on its own: retrying could
 * duplicate an LSP that already exists, and releasing could hand its bandwidth to another flow
 * while a router is still forwarding over it. Holding capacity and waiting is the right default
 * and it had no exit — nothing moved an uncertain intent to a terminal state, so the reservations
 * were held indefinitely for work nobody could see.
 */
class OperatorResolutionTest {

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

    /** Drives a task to UNCERTAIN the way the runtime does: sent, then never acknowledged. */
    private static PCERestController uncertainController(Path dir) throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology());
        controller.setInstallDispatch(() -> Optional.of("speaker:pcc-alpha"),
                (intent, srpId, route, rateBps) -> CRPEngine.DispatchOutcome.UNCERTAIN);
        controller.handleScheduleWorkloadsRequest(TASK);
        engine.applyInstallationChange(() -> engine.getIntents().markUncertain("T1"));
        assertEquals(InstallationState.UNCERTAIN,
                engine.getIntents().find("T1").orElseThrow().getState());
        return controller;
    }

    @Test
    void anUncertainIntentIsVisibleAsNeedingAnOperator(@TempDir Path dir) throws Exception {
        PCERestController controller = uncertainController(dir);

        var stuck = controller.intentsNeedingOperator();
        assertEquals(1, stuck.size(), stuck.toString());
        assertEquals("T1", stuck.get(0).taskId());
        assertTrue(stuck.get(0).holdsCapacity(),
                "an uncertain intent still holds its reservations, which is the point of listing it");
    }

    @Test
    void anOperatorCanReleaseCapacityForAnLspTheyConfirmedIsAbsent(@TempDir Path dir)
            throws Exception {
        PCERestController controller = uncertainController(dir);

        var resolved = controller.resolveUncertain("T1", PCERestController.Resolution.NOT_INSTALLED);

        assertEquals(InstallationState.FAILED, resolved.getState());
        assertFalse(resolved.getState().holdsCapacity(), "FAILED must release the reservations");
        assertTrue(controller.intentsNeedingOperator().isEmpty(),
                "a resolved intent no longer needs an operator");
    }

    @Test
    void aTaskThatIsNotUncertainIsRefused(@TempDir Path dir) throws Exception {
        // Resolution asserts something about a router. Allowing it against an INSTALLED intent
        // would let an operator release capacity for an LSP the controller has positive evidence
        // exists, which is exactly the double-booking this endpoint must not enable.
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology());
        controller.setInstallDispatch(() -> Optional.of("speaker:pcc-alpha"),
                (intent, srpId, route, rateBps) -> CRPEngine.DispatchOutcome.SENT);
        controller.handleScheduleWorkloadsRequest(TASK);

        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> controller.resolveUncertain("T1", PCERestController.Resolution.NOT_INSTALLED));
        assertTrue(refused.getMessage().contains("not UNCERTAIN"), refused.getMessage());
    }

    @Test
    void theEndpointReportsTheOutcomeOverHttp(@TempDir Path dir) throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology());
        controller.setInstallDispatch(() -> Optional.of("speaker:pcc-alpha"),
                (intent, srpId, route, rateBps) -> CRPEngine.DispatchOutcome.UNCERTAIN);
        PCERestServer server = new PCERestServer(0, controller, "secret", 4, 8,
                OperatorConfiguration.parse(Map.of()));
        server.start();
        try {
            controller.handleScheduleWorkloadsRequest(TASK);
            engine.applyInstallationChange(() -> engine.getIntents().markUncertain("T1"));

            HttpResponse<String> ok = post(server, "/api/v1/tasks/T1/resolve",
                    "{\"resolution\": \"NOT_INSTALLED\"}");
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(ok.body().contains("FAILED"), ok.body());

            // Already terminal: a second attempt is a conflict, not another release.
            HttpResponse<String> again = post(server, "/api/v1/tasks/T1/resolve",
                    "{\"resolution\": \"NOT_INSTALLED\"}");
            assertEquals(409, again.statusCode(), again.body());

            HttpResponse<String> missing = post(server, "/api/v1/tasks/NOPE/resolve",
                    "{\"resolution\": \"NOT_INSTALLED\"}");
            assertEquals(404, missing.statusCode(), missing.body());

            HttpResponse<String> garbage = post(server, "/api/v1/tasks/T1/resolve",
                    "{\"resolution\": \"MAYBE\"}");
            assertEquals(400, garbage.statusCode(), garbage.body());
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> post(PCERestServer server, String path, String body)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + server.getBoundPort() + path))
                        .header("X-API-Key", "secret").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
