package net.dcn.pce.northbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A successful solve must say what happened to the installation, not just to the plan.
 *
 * <p>The response was identical whether the LSP reached a router, was never sent because no PCC
 * was connected, or was sent with an outcome the transport could not confirm. Those are three
 * materially different situations — in one the network is carrying the flow, in another nothing
 * was asked of it, in the third nobody knows — and a client could only tell them apart by polling
 * every task afterwards.
 */
class SolveReportsInstallationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private static JsonNode solveWith(Path dir, Optional<String> pcc,
                                      CRPEngine.DispatchOutcome outcome) throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology());
        AtomicReference<Optional<String>> target = new AtomicReference<>(pcc);
        controller.setInstallDispatch(target::get, (intent, srpId, route, rateBps) -> outcome);
        return MAPPER.readTree(controller.handleScheduleWorkloadsRequest(TASK));
    }

    @Test
    void aSentInstallReportsInstallingAndSent(@TempDir Path dir) throws Exception {
        JsonNode body = solveWith(dir, Optional.of("speaker:pcc-alpha"),
                CRPEngine.DispatchOutcome.SENT);
        assertEquals("INSTALLING/SENT", body.get("installation").get("T1").asText(),
                body.toString());
        assertEquals("INSTALLING/SENT",
                body.get("committedSchedules").get(0).get("installationState").asText());
    }

    @Test
    void anUnconfirmedWriteIsDistinguishableFromASuccessfulOne(@TempDir Path dir)
            throws Exception {
        // The case that most needs saying, and the one the intent state alone cannot express:
        // a write that may have half-arrived leaves the intent INSTALLING exactly as a clean
        // send does. Reporting only the state would make "we do not know" look like "sent".
        JsonNode body = solveWith(dir, Optional.of("speaker:pcc-alpha"),
                CRPEngine.DispatchOutcome.UNCERTAIN);
        assertEquals("INSTALLING/UNCERTAIN", body.get("installation").get("T1").asText(),
                "an unconfirmed write must not be reported the same as a confirmed one");
        assertTrue(body.get("committedFlowCount").asInt() > 0,
                "the plan itself did succeed; that is why the distinction matters");
    }

    @Test
    void aScheduleWithNoPccReportsPlanned(@TempDir Path dir) throws Exception {
        JsonNode body = solveWith(dir, Optional.empty(), CRPEngine.DispatchOutcome.SENT);
        assertEquals("PLANNED", body.get("installation").get("T1").asText(),
                "with no PCC nothing was sent, and the response must not imply otherwise");
    }

    @Test
    void aControllerWithNoDispatchPathOmitsTheFieldEntirely(@TempDir Path dir) throws Exception {
        // Reporting PLANNED for everything would suggest installation was attempted and declined,
        // when this controller was never asked to install anything at all.
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology());
        JsonNode body = MAPPER.readTree(controller.handleScheduleWorkloadsRequest(TASK));
        assertFalse(body.has("installation"),
                "no dispatch wired means no installation claim, not a claim of PLANNED");
    }
}
