package net.dcn.pce.northbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dcn.pce.config.OperatorConfiguration;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.Node;
import net.dcn.pce.topology.FileTopologyParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PCERestServerTest {

    @Test
    void livenessRemainsAvailableAndConcurrentSolveWaitsFairlyWhilePlannerIsBusy() throws Exception {
        BaseTopology topology = new BaseTopology();
        topology.addNode(new Node("A", "A", 1_000, 1_000));
        BlockingController controller = new BlockingController(topology);
        PCERestServer server = new PCERestServer(0, controller, "test-secret", 4, 8);
        server.start();

        try {
            int port = server.getBoundPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest solve = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/solve"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", "test-secret")
                    .POST(HttpRequest.BodyPublishers.ofString("[{}]"))
                    .build();

            CompletableFuture<HttpResponse<String>> first = client.sendAsync(
                    solve, HttpResponse.BodyHandlers.ofString());
            assertTrue(controller.entered.await(2, TimeUnit.SECONDS));

            assertEquals(200, get(client, port, "/livez").statusCode());
            // A busy planner is backpressure, not unavailability: the instance is still able to
            // accept traffic, and reporting NotReady here would pull it out of rotation during
            // ordinary work. An admitted concurrent solve waits on the bounded server-side queue.
            assertEquals(200, get(client, port, "/readyz").statusCode());
            assertTrue(get(client, port, "/readyz").body().contains("\"plannerBusy\": true"),
                    "readiness should still report planner occupancy for observability");
            CompletableFuture<HttpResponse<String>> second = client.sendAsync(
                    solve, HttpResponse.BodyHandlers.ofString());
            Thread.sleep(50);
            assertTrue(!second.isDone(), "the second solve should wait instead of retrying");

            controller.release.countDown();
            assertEquals(200, first.get(2, TimeUnit.SECONDS).statusCode());
            assertEquals(200, second.get(2, TimeUnit.SECONDS).statusCode());
            assertEquals(200, get(client, port, "/readyz").statusCode());
        } finally {
            controller.release.countDown();
            server.stop();
        }
    }

    @Test
    void plannerQueueWaitIsBoundedAndReturnsRetryGuidance() throws Exception {
        BaseTopology topology = new BaseTopology();
        topology.addNode(new Node("A", "A", 1_000, 1_000));
        BlockingController controller = new BlockingController(topology);
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_SOLVE_TIMEOUT_SEC, "0"));
        PCERestServer server = new PCERestServer(
                0, controller, "test-secret", 4, 8, configuration);
        server.start();

        try {
            int port = server.getBoundPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest solve = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/solve"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", "test-secret")
                    .POST(HttpRequest.BodyPublishers.ofString("[{}]"))
                    .build();

            CompletableFuture<HttpResponse<String>> first = client.sendAsync(
                    solve, HttpResponse.BodyHandlers.ofString());
            assertTrue(controller.entered.await(2, TimeUnit.SECONDS));

            HttpResponse<String> rejected = client.send(
                    solve, HttpResponse.BodyHandlers.ofString());
            assertEquals(503, rejected.statusCode());
            assertEquals("1", rejected.headers().firstValue("Retry-After").orElseThrow());
            assertTrue(rejected.body().contains("queue wait exceeded"));

            controller.release.countDown();
            assertEquals(200, first.get(2, TimeUnit.SECONDS).statusCode());
        } finally {
            controller.release.countDown();
            server.stop();
        }
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static final class BlockingController extends PCERestController {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingController(BaseTopology topology) {
            super(new CRPEngine(), topology);
        }

        // The owner-carrying overload, which is the one the HTTP handler calls. Overriding the
        // shorter signature stopped intercepting anything once ownership began travelling with
        // the solve, and the test failed by timing out rather than by saying so.
        @Override
        public String handleScheduleWorkloadsRequest(
                String workloadJsonBlob, net.dcn.pce.crp.SolveCancellation cancellation,
                String owner) throws IOException {
            entered.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("test controller timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("test controller interrupted", e);
            }
            return "{}";
        }
    }

    private static final String R_DET_TOPOLOGY_JSON = """
            {
              "regime": "R_DET",
              "nodes": [
                {"nodeId":"N1","serviceRateBps":1.0e9,"reservoirCapacityBytes":1.0e9},
                {"nodeId":"N2","serviceRateBps":1.0e9,"reservoirCapacityBytes":1.0e9}
              ],
              "links": [
                {"linkId":"L1","sourceNodeId":"N1","destinationNodeId":"N2",
                 "baseBandwidthBps":1.0e8,"propagationDelaySec":0.001,
                 "activeContactSec":30,"inactivePreSec":0,"inactivePostSec":0}
              ]
            }
            """;

    /**
     * Exercises the configured route policy end to end and asserts the flow was actually
     * committed, rather than only that the response envelope mentions committed schedules.
     */
    @Test
    void boundedCgrConfigurationCommitsAFlowOverTheConfiguredTopology() throws Exception {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(R_DET_TOPOLOGY_JSON);
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "BOUNDED_CGR",
                OperatorConfiguration.ENV_RATE_ASSIGN_POLICY, "LINE_RATE"));
        configuration.validateForRegime(topology.getRegime());

        CRPEngine engine = configuration.applyTo(new CRPEngine());
        PCERestServer server = new PCERestServer(
                0, new PCERestController(engine, topology), "secret-key", 4, 8, configuration);
        server.start();

        try {
            int port = server.getBoundPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String payload = "[{\"taskId\": \"T1\", \"sourceNodeId\": \"N1\", \"destinationNodeId\": \"N2\","
                    + " \"originationTimeSec\": 0.0, \"deadlineSec\": 10.0, \"taskSizeBytes\": 100000}]";

            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/solve"))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", "secret-key")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            JsonNode body = new ObjectMapper().readTree(response.body());
            assertEquals(1, body.get("offeredFlowCount").asInt());
            assertEquals(1, body.get("committedFlowCount").asInt(),
                    "bounded CGR should admit the flow, not merely return an envelope");
            assertEquals(0, body.get("unadmittedTaskCount").asInt());

            JsonNode schedule = body.get("committedSchedules").get(0);
            assertEquals("T1", schedule.get("taskId").asText());
            assertTrue(schedule.get("metDeadline").asBoolean(), "committed flow should meet its deadline");
            assertEquals("L1", schedule.get("hops").get(0).get("linkId").asText(),
                    "the committed route should traverse the configured contact-bearing link");
        } finally {
            server.stop();
        }
    }

    @Test
    void configEndpointReportsEffectiveConfigurationAndRequiresAuthentication() throws Exception {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(R_DET_TOPOLOGY_JSON);
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "BOUNDED_CGR",
                OperatorConfiguration.ENV_RATE_ASSIGN_POLICY, "LINE_RATE",
                OperatorConfiguration.ENV_TOPOLOGY_FILE, "/etc/vortex/topology.json"));

        PCERestServer server = new PCERestServer(
                0,
                new PCERestController(configuration.applyTo(new CRPEngine()), topology),
                "secret-key", 4, 8, configuration);
        server.start();

        try {
            int port = server.getBoundPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            assertEquals(401, get(client, port, "/api/v1/config").statusCode(),
                    "effective configuration must not be readable without a credential");

            HttpResponse<String> authorized = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/config"))
                    .header("X-API-Key", "secret-key")
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, authorized.statusCode());
            JsonNode body = new ObjectMapper().readTree(authorized.body());
            JsonNode effective = body.get("effectiveConfiguration");
            assertEquals("BOUNDED_CGR", effective.get("routeGenerationPolicy").asText());
            assertEquals("LINE_RATE", effective.get("rateAssignmentPolicy").asText());
            assertEquals("LWEEF", effective.get("taskSelectionPolicy").asText());
            assertEquals("/etc/vortex/topology.json", effective.get("topologySource").asText());
            assertEquals("R_DET", body.get("activeTopology").get("contactRegime").asText());
            assertEquals(2, body.get("activeTopology").get("nodeCount").asInt());
            assertTrue(body.get("supportedValues").get(OperatorConfiguration.ENV_ROUTE_GEN_POLICY)
                    .asText().contains("BOUNDED_CGR"));
        } finally {
            server.stop();
        }
    }

    @Test
    void metricsRequireAuthenticationAndReflectCompletedSolves() throws Exception {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(R_DET_TOPOLOGY_JSON);
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "BOUNDED_CGR",
                OperatorConfiguration.ENV_RATE_ASSIGN_POLICY, "LINE_RATE"));

        PCERestServer server = new PCERestServer(
                0,
                new PCERestController(configuration.applyTo(new CRPEngine()), topology),
                "secret-key", 4, 8, configuration);
        server.start();

        try {
            int port = server.getBoundPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            assertEquals(401, get(client, port, "/metrics").statusCode(),
                    "ledger depth and admission state must not be readable without a credential");

            String payload = "[{\"taskId\": \"M1\", \"sourceNodeId\": \"N1\", \"destinationNodeId\": \"N2\","
                    + " \"originationTimeSec\": 0.0, \"deadlineSec\": 10.0, \"taskSizeBytes\": 100000}]";
            assertEquals(200, client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/solve"))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", "secret-key")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(), HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpResponse<String> metrics = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/metrics"))
                    .header("X-API-Key", "secret-key")
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.headers().firstValue("Content-Type").orElse("")
                            .startsWith("text/plain; version=0.0.4"),
                    "scrapers require the Prometheus text exposition content type");

            String body = metrics.body();
            assertTrue(body.contains("vortex_solve_requests_total{outcome=\"success\"} 1"), body);
            assertTrue(body.contains("vortex_workloads_offered_total 1"), body);
            assertTrue(body.contains("vortex_workloads_committed_total 1"), body);
            assertTrue(body.contains("vortex_solve_duration_seconds_count 1"), body);
            assertTrue(body.contains("# TYPE vortex_solve_duration_seconds histogram"), body);
        } finally {
            server.stop();
        }
    }

    @Test
    void aSolveExceedingItsBudgetReturns504AndIsCountedAsATimeout() throws Exception {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(R_DET_TOPOLOGY_JSON);
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_SOLVE_TIMEOUT_SEC, "1"));
        // An already-spent budget makes the first cancellation point fire deterministically.
        CRPEngine engine = configuration.applyTo(new CRPEngine())
                .withSolveTimeout(Duration.ofNanos(1));

        PCERestServer server = new PCERestServer(
                0, new PCERestController(engine, topology), "secret-key", 4, 8, configuration);
        server.start();

        try {
            int port = server.getBoundPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String payload = "[{\"taskId\": \"SLOW\", \"sourceNodeId\": \"N1\", \"destinationNodeId\": \"N2\","
                    + " \"originationTimeSec\": 0.0, \"deadlineSec\": 10.0, \"taskSizeBytes\": 100000}]";

            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/solve"))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", "secret-key")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(504, response.statusCode());
            assertTrue(response.body().contains("No reservations were committed"), response.body());

            // The planner must be released, or one timeout would wedge the controller.
            assertEquals(200, get(client, port, "/readyz").statusCode());

            HttpResponse<String> metrics = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/metrics"))
                    .header("X-API-Key", "secret-key")
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(metrics.body().contains("vortex_solve_requests_total{outcome=\"timeout\"} 1"),
                    metrics.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void shutdownStopsAnInFlightSolveInsteadOfWaitingOutTheGracePeriod() throws Exception {
        // A dense mesh under exhaustive enumeration runs far longer than the shutdown grace
        // period, so without cancellation stop() would wait the full 5 seconds and then
        // interrupt a planner that never checked for interruption.
        BaseTopology topology = new BaseTopology();
        topology.setRegime(net.dcn.pce.model.ContactRegime.R_STATIC);
        for (int i = 0; i < 11; i++) {
            topology.addNode(new Node("N" + i, "N" + i, 1e9, 1e9));
        }
        for (int i = 0; i < 11; i++) {
            for (int j = 0; j < 11; j++) {
                if (i != j) {
                    topology.addLink(new net.dcn.pce.model.Link("L" + i + "-" + j, "N" + i, "N" + j,
                            net.dcn.pce.model.LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
                }
            }
        }

        CRPEngine engine = new CRPEngine().withFGenerate(
                net.dcn.pce.crp.policy.RouteGenerationPolicy.K_MAX_EXHAUSTIVE);
        PCERestServer server = new PCERestServer(
                0, new PCERestController(engine, topology), "secret-key", 4, 8);
        server.start();

        int port = server.getBoundPort();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        String payload = "[{\"taskId\": \"BIG\", \"sourceNodeId\": \"N0\", \"destinationNodeId\": \"N10\","
                + " \"originationTimeSec\": 0.0, \"deadlineSec\": 1000000.0, \"taskSizeBytes\": 1000}]";

        CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/solve"))
                        .header("Content-Type", "application/json")
                        .header("X-API-Key", "secret-key")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(), HttpResponse.BodyHandlers.ofString());

        Thread.sleep(300); // let planning get underway

        long startedAt = System.nanoTime();
        server.stop();
        long shutdownMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertTrue(shutdownMillis < 4_000,
                "shutdown should not wait out the grace period, took " + shutdownMillis + " ms");

        // The abandoned request either fails or reports the solve was stopped; either way it must
        // not report a committed schedule.
        try {
            HttpResponse<String> response = inFlight.get(5, TimeUnit.SECONDS);
            assertTrue(response.statusCode() >= 500,
                    "a shut-down solve must not report success: " + response.statusCode());
        } catch (java.util.concurrent.ExecutionException expected) {
            // Connection torn down with the server; equally acceptable.
        }
        assertEquals(0, engine.getLRIB().getAllReservations().size(),
                "a solve stopped by shutdown must leave no reservations behind");
    }

    /**
     * S reaches D directly, but the S-D contact opens after the deadline; S-A-D delivers in time.
     * The time-blind minimum-hop generator proposes only S-D.
     */
    private static final String LATE_DIRECT_CONTACT_JSON = """
            {"regime": "R_DET",
             "nodes": [
              {"nodeId": "S", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
              {"nodeId": "A", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
              {"nodeId": "D", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9}],
             "links": [
              {"linkId": "S-D", "sourceNodeId": "S", "destinationNodeId": "D", "baseBandwidthBps": 1e6,
               "propagationDelaySec": 0, "contacts": [{"startSec": 100, "endSec": 110}]},
              {"linkId": "S-A", "sourceNodeId": "S", "destinationNodeId": "A", "baseBandwidthBps": 1e6,
               "propagationDelaySec": 0, "contacts": [{"startSec": 0, "endSec": 10}]},
              {"linkId": "A-D", "sourceNodeId": "A", "destinationNodeId": "D", "baseBandwidthBps": 1e6,
               "propagationDelaySec": 0, "contacts": [{"startSec": 20, "endSec": 30}]}]}
            """;

    /** Solves one S-to-D task on a server built the way {@code --server} builds it. */
    private static JsonNode solveOnOperatorServer(Map<String, String> environment, String path)
            throws Exception {
        Map<String, String> env = new java.util.HashMap<>(environment);
        env.put(OperatorConfiguration.ENV_API_PRINCIPALS, "ops:ADMIN::op-key");
        PCERestServer server = new PCERestServer(0,
                FileTopologyParser.parseTopologyJson(LATE_DIRECT_CONTACT_JSON),
                OperatorConfiguration.parse(env));
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getBoundPort() + path))
                    .header("X-API-Key", "op-key")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10));
            request = path.endsWith("/solve")
                    ? request.POST(HttpRequest.BodyPublishers.ofString(
                            "[{\"taskId\": \"T\", \"sourceNodeId\": \"S\", \"destinationNodeId\": \"D\","
                                    + " \"originationTimeSec\": 0.0, \"deadlineSec\": 50.0,"
                                    + " \"taskSizeBytes\": 1000}]"))
                    : request.GET();
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return new ObjectMapper().readTree(response.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void operatorServerDefaultsToContactAwareRoutingUnderAContactPlan() throws Exception {
        JsonNode config = solveOnOperatorServer(Map.of(), "/api/v1/config");
        assertEquals("BOUNDED_CGR",
                config.get("effectiveConfiguration").get("routeGenerationPolicy").asText(),
                "an unset policy must resolve to the contact-aware default and be reported as such");

        JsonNode solved = solveOnOperatorServer(Map.of(), "/api/v1/solve");
        assertEquals(1, solved.get("committedFlowCount").asInt(), solved.toString());
        JsonNode hops = solved.get("committedSchedules").get(0).get("hops");
        assertEquals("S-A", hops.get(0).get("linkId").asText());
        assertEquals("A-D", hops.get(1).get("linkId").asText());
    }

    @Test
    void operatorServerKeepsAnExplicitRoutePolicy() throws Exception {
        Map<String, String> minHop = Map.of(OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "BFS_MIN_HOP");
        assertEquals("BFS_MIN_HOP", solveOnOperatorServer(minHop, "/api/v1/config")
                .get("effectiveConfiguration").get("routeGenerationPolicy").asText());
        // The failure the default exists to prevent: the only proposed route's contact is too late.
        assertEquals(0, solveOnOperatorServer(minHop, "/api/v1/solve")
                .get("committedFlowCount").asInt());
    }

    @Test
    void readinessReportsTheEffectivePolicyAndRegime() throws Exception {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(R_DET_TOPOLOGY_JSON);
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "BOUNDED_CGR"));

        PCERestServer server = new PCERestServer(
                0,
                new PCERestController(configuration.applyTo(new CRPEngine()), topology),
                "secret-key", 4, 8, configuration);
        server.start();

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> readiness = get(client, server.getBoundPort(), "/readyz");

            assertEquals(200, readiness.statusCode());
            JsonNode body = new ObjectMapper().readTree(readiness.body());
            assertEquals("READY", body.get("status").asText());
            assertEquals("BOUNDED_CGR", body.get("routeGenerationPolicy").asText());
            assertEquals("R_DET", body.get("contactRegime").asText());
        } finally {
            server.stop();
        }
    }
}
