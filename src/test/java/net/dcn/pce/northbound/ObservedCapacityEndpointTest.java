package net.dcn.pce.northbound;

import net.dcn.pce.config.OperatorConfiguration;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An observation must reach the solver, not merely be recorded.
 *
 * <p>The variance study measured this engine planning against a capacity declared at startup while
 * the link drifted below it, dropping packets because the plan committed to a rate the network no
 * longer sustained. The test that matters is therefore not that the endpoint returns 200 — it is
 * that the *next* solve admits differently.
 */
class ObservedCapacityEndpointTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e10, 1e9));
        topology.addNode(new Node("B", "B", 1e10, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e8, 0.0001)));
        return topology;
    }

    private static PCERestServer server(Path dir) throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestServer server = new PCERestServer(0, new PCERestController(engine, topology()),
                "secret", 4, 8, OperatorConfiguration.parse(Map.of()));
        server.start();
        return server;
    }

    private static HttpResponse<String> post(PCERestServer server, String path, String body)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getBoundPort() + path))
                        .header("X-API-Key", "secret").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int admitted(PCERestServer server, String tag) throws Exception {
        StringBuilder body = new StringBuilder("[");
        for (int i = 1; i <= 6; i++) {
            body.append(i > 1 ? "," : "").append(String.format(
                    "{\"taskId\": \"%s%d\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\","
                            + " \"originationTimeSec\": 0, \"deadlineSec\": 10,"
                            + " \"taskSizeBytes\": 15000000}", tag, i));
        }
        HttpResponse<String> response = post(server, "/api/v1/solve", body.append("]").toString());
        assertEquals(200, response.statusCode(), response.body());
        String marker = "\"committedFlowCount\" : ";
        int start = response.body().indexOf(marker) + marker.length();
        return Integer.parseInt(response.body().substring(start,
                response.body().indexOf(',', start)).trim());
    }

    @Test
    void anObservationChangesWhatTheNextSolveAdmits(@TempDir Path dir) throws Exception {
        // On a clean engine, with nothing committed, the observation is the only thing that
        // changes between the two solves.
        PCERestServer server = server(dir);
        try {
            HttpResponse<String> observed = post(server,
                    "/api/v1/links/A-B/observed-capacity", "{\"observedBps\": 20000000}");
            assertEquals(200, observed.statusCode(), observed.body());
            assertTrue(observed.body().contains("\"previousBps\""), observed.body());

            int after = admitted(server, "AFTER");
            assertTrue(after >= 1 && after <= 2,
                    "a 20 Mbit link should admit far fewer than the 6 a 100 Mbit link takes; "
                            + "admitted=" + after);
        } finally {
            server.stop();
        }
    }

    @Test
    void anObservationThatWouldOverSubscribeCommitmentsIsRefused(@TempDir Path dir)
            throws Exception {
        // Found by the endpoint test itself: accepting this left the ledger holding more
        // bandwidth than the link had, and the next solve failed its own replay validation with
        // an LRIB capacity violation -- a 500 caused by a state the controller had created.
        PCERestServer server = server(dir);
        try {
            int before = admitted(server, "BEFORE");
            assertTrue(before > 1, "the declared 100 Mbit link should admit several flows");

            HttpResponse<String> refused = post(server,
                    "/api/v1/links/A-B/observed-capacity", "{\"observedBps\": 1000000}");
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("already committed"), refused.body());

            // And the controller is still usable afterwards, which is the point of refusing.
            assertEquals(200, post(server, "/api/v1/solve",
                    "[{\"taskId\": \"AFTER1\", \"sourceNodeId\": \"A\","
                            + " \"destinationNodeId\": \"B\", \"originationTimeSec\": 0,"
                            + " \"deadlineSec\": 10, \"taskSizeBytes\": 1000000}]").statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void anUnknownLinkIsRefused(@TempDir Path dir) throws Exception {
        PCERestServer server = server(dir);
        try {
            assertEquals(404, post(server,
                    "/api/v1/links/nope/observed-capacity", "{\"observedBps\": 1000}").statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void unusableBodiesAreRefusedRatherThanDefaulted(@TempDir Path dir) throws Exception {
        // A silently-defaulted observation would leave the controller planning against a capacity
        // the operator believes they corrected.
        PCERestServer server = server(dir);
        try {
            for (String body : new String[]{"", "{}", "{\"observedBps\": 0}",
                    "{\"observedBps\": -5}", "{\"observed_bps\": 10}", "{\"observedBps\": \"fast\"}"}) {
                assertEquals(400,
                        post(server, "/api/v1/links/A-B/observed-capacity", body).statusCode(),
                        "should refuse: " + body);
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void theEndpointRequiresTheApiKey(@TempDir Path dir) throws Exception {
        PCERestServer server = server(dir);
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getBoundPort()
                                    + "/api/v1/links/A-B/observed-capacity"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"observedBps\": 1000}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
        } finally {
            server.stop();
        }
    }
}
