package net.dcn.pce.northbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.LinkIntermittencyFunction;
import net.dcn.pce.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a task's capacity is merely planned or actually installed is not answerable from the
 * schedule, and it is what an operator asks when a flow misbehaves. These tests cover the two
 * places that answer it.
 */
class IntentObservabilityTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e9, 1e9));
        topology.addNode(new Node("B", "B", 1e9, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
        return topology;
    }

    private static final String SOLVE = "[{\"taskId\": \"T1\", \"sourceNodeId\": \"A\","
            + " \"destinationNodeId\": \"B\", \"originationTimeSec\": 0.0,"
            + " \"deadlineSec\": 10000.0, \"taskSizeBytes\": 1000}]";

    private record Harness(PCERestServer server, HttpClient client, int port) {}

    private static Harness start(Path dir) throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestServer server = new PCERestServer(
                0, new PCERestController(engine, topology()), "secret", 4, 8);
        server.start();
        return new Harness(server, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build(), server.getBoundPort());
    }

    private static HttpResponse<String> get(Harness h, String path) throws Exception {
        return h.client().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + h.port() + path))
                .header("X-API-Key", "secret").timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void solve(Harness h) throws Exception {
        assertEquals(200, h.client().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + h.port() + "/api/v1/solve"))
                .header("Content-Type", "application/json").header("X-API-Key", "secret")
                .POST(HttpRequest.BodyPublishers.ofString(SOLVE)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void everyIntentStateHasASeriesBeforeAnyTaskReachesIt(@TempDir Path dir) throws Exception {
        Harness h = start(dir);
        try {
            String metrics = get(h, "/metrics").body();

            // A gauge that appears only once a task reaches a state leaves an alert on that state
            // silently unevaluated until the condition it is meant to catch has already happened.
            for (String state : new String[]{
                    "PLANNED", "INSTALLING", "INSTALLED", "UNCERTAIN", "DELETING", "FAILED", "DELETED"}) {
                assertTrue(metrics.contains("vortex_installation_intents{state=\"" + state + "\"}"),
                        "missing pre-registered series for " + state);
            }
        } finally {
            h.server().stop();
        }
    }

    @Test
    void aPlannedTaskIsCountedAndReadable(@TempDir Path dir) throws Exception {
        Harness h = start(dir);
        try {
            solve(h);

            assertTrue(get(h, "/metrics").body()
                            .contains("vortex_installation_intents{state=\"PLANNED\"} 1"),
                    "a committed solve should be visible as a planned intent");

            HttpResponse<String> task = get(h, "/api/v1/tasks/T1");
            assertEquals(200, task.statusCode());
            JsonNode body = new ObjectMapper().readTree(task.body());
            assertEquals("T1", body.get("taskId").asText());
            assertEquals("vortex-T1", body.get("lspName").asText());
            assertEquals("PLANNED", body.get("installationState").asText());
            assertTrue(body.get("holdsCapacity").asBoolean(),
                    "a planned task holds capacity even though nothing is installed");
        } finally {
            h.server().stop();
        }
    }

    @Test
    void anUnknownTaskIsNotFoundRatherThanInvented(@TempDir Path dir) throws Exception {
        Harness h = start(dir);
        try {
            assertEquals(404, get(h, "/api/v1/tasks/NEVER-EXISTED").statusCode());
        } finally {
            h.server().stop();
        }
    }

    @Test
    void readingATaskRequiresACredential(@TempDir Path dir) throws Exception {
        Harness h = start(dir);
        try {
            solve(h);
            HttpResponse<String> unauthorised = h.client().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + h.port() + "/api/v1/tasks/T1"))
                    .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(401, unauthorised.statusCode(),
                    "installation state describes committed capacity and must not be public");
        } finally {
            h.server().stop();
        }
    }

    @Test
    void cancellingATaskStopsItBeingCountedAsHoldingCapacity(@TempDir Path dir) throws Exception {
        Harness h = start(dir);
        try {
            solve(h);
            assertEquals(200, h.client().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + h.port() + "/api/v1/tasks/T1"))
                    .header("X-API-Key", "secret").timeout(Duration.ofSeconds(2))
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());

            String metrics = get(h, "/metrics").body();
            assertTrue(metrics.contains("vortex_installation_intents{state=\"PLANNED\"} 0"),
                    "a cancelled task should no longer be counted as planned:\n" + metrics);
        } finally {
            h.server().stop();
        }
    }

    @Test
    void deleteStillWorksAndAnUnsupportedMethodIsRejected(@TempDir Path dir) throws Exception {
        Harness h = start(dir);
        try {
            solve(h);
            HttpResponse<String> put = h.client().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + h.port() + "/api/v1/tasks/T1"))
                    .header("X-API-Key", "secret").timeout(Duration.ofSeconds(2))
                    .method("PUT", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(405, put.statusCode());
        } finally {
            h.server().stop();
        }
    }
}
