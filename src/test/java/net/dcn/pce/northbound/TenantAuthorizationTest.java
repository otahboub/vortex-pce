package net.dcn.pce.northbound;

import net.dcn.pce.config.ApiPrincipals;
import net.dcn.pce.config.OperatorConfiguration;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One credential authorised everything for everyone.
 *
 * <p>A read-only monitoring scraper and the system owning production traffic held the same secret,
 * so either could cancel the other's LSPs and the two requests were indistinguishable. Rotating it
 * meant coordinating every client at once.
 */
class TenantAuthorizationTest {

    private static final String PRINCIPALS =
            "watcher:VIEWER::look,"
            + "alpha:OPERATOR:tenant-a:key-a,"
            + "beta:OPERATOR:tenant-b:key-b,"
            + "root:ADMIN::key-root";

    private PCERestServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e10, 1e9));
        topology.addNode(new Node("B", "B", 1e10, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e8, 0.0001)));
        return topology;
    }

    private void start(Path dir) throws Exception {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_API_PRINCIPALS, PRINCIPALS,
                OperatorConfiguration.ENV_STATE_PATH, dir.resolve("state.json").toString()));
        CRPEngine engine = new CRPEngine()
                .withDurableState(dir.resolve("state.json").toString());
        server = new PCERestServer(0, new PCERestController(engine, topology()),
                "legacy-key", 4, 8, configuration);
        server.start();
    }

    private HttpResponse<String> call(String method, String path, String key, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.getBoundPort() + path))
                .header("X-API-Key", key).header("Content-Type", "application/json");
        request = switch (method) {
            case "POST" -> request.POST(HttpRequest.BodyPublishers.ofString(body));
            case "DELETE" -> request.DELETE();
            default -> request.GET();
        };
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String task(String id) {
        return "[{\"taskId\": \"" + id + "\", \"sourceNodeId\": \"A\","
                + " \"destinationNodeId\": \"B\", \"originationTimeSec\": 0,"
                + " \"deadlineSec\": 100, \"taskSizeBytes\": 1000000, \"priority\": 1}]";
    }

    @Test
    void aViewerMayReadButNotSolveOrCancel(@TempDir Path dir) throws Exception {
        start(dir);
        assertEquals(200, call("GET", "/metrics", "look", null).statusCode());
        assertEquals(403, call("POST", "/api/v1/solve", "look", task("V1")).statusCode(),
                "a read-only credential must not be able to commit capacity");

        assertEquals(200, call("POST", "/api/v1/solve", "key-a", task("V1")).statusCode());
        assertEquals(403, call("DELETE", "/api/v1/tasks/V1", "look", null).statusCode());
    }

    @Test
    void oneTenantCannotCancelAnothersTask(@TempDir Path dir) throws Exception {
        start(dir);
        assertEquals(200, call("POST", "/api/v1/solve", "key-a", task("T-A")).statusCode());

        // 404 rather than 403: telling tenant B that T-A exists leaks tenant A's id space.
        assertEquals(404, call("DELETE", "/api/v1/tasks/T-A", "key-b", null).statusCode(),
                "another tenant's task must not be cancellable");
        assertEquals(404, call("GET", "/api/v1/tasks/T-A", "key-b", null).statusCode());

        // Its owner still can.
        assertEquals(200, call("DELETE", "/api/v1/tasks/T-A", "key-a", null).statusCode());
    }

    @Test
    void oneTenantCannotResolveAnothersTask(@TempDir Path dir) throws Exception {
        start(dir);
        assertEquals(200, call("POST", "/api/v1/solve", "key-a", task("T-A")).statusCode());

        assertEquals(404, call("POST", "/api/v1/tasks/T-A/resolve", "key-b",
                "{\"resolution\":\"NOT_INSTALLED\"}").statusCode(),
                "resolution must enforce the same tenant boundary as read and cancellation");
        assertEquals(409, call("POST", "/api/v1/tasks/T-A/resolve", "key-a",
                "{\"resolution\":\"NOT_INSTALLED\"}").statusCode(),
                "the owner reaches state validation rather than being hidden");
    }

    @Test
    void anAdministratorReachesEveryTenant(@TempDir Path dir) throws Exception {
        // Someone has to be able to clear up after a tenant that has gone away, or its capacity is
        // stranded for ever.
        start(dir);
        assertEquals(200, call("POST", "/api/v1/solve", "key-a", task("T-A")).statusCode());
        assertEquals(200, call("DELETE", "/api/v1/tasks/T-A", "key-root", null).statusCode());
    }

    @Test
    void onlyAnAdministratorMayChangeLinkCapacity(@TempDir Path dir) throws Exception {
        // Lowering a link changes what every tenant can be admitted for.
        start(dir);
        assertEquals(403, call("POST", "/api/v1/links/A-B/observed-capacity", "key-a",
                "{\"observedBps\": 4000000}").statusCode());
        assertEquals(200, call("POST", "/api/v1/links/A-B/observed-capacity", "key-root",
                "{\"observedBps\": 4000000}").statusCode());
    }

    @Test
    void theLegacySingleKeyStillWorksAsAnAdministrator(@TempDir Path dir) throws Exception {
        // Every deployment that predates principals keeps working; a security improvement nobody
        // can adopt without an outage is not one.
        start(dir);
        assertEquals(200, call("POST", "/api/v1/solve", "legacy-key", task("L1")).statusCode());
        assertEquals(200, call("DELETE", "/api/v1/tasks/L1", "legacy-key", null).statusCode());
    }

    @Test
    void namedPrincipalsMayBeTheOnlyConfiguredCredentials(@TempDir Path dir) throws Exception {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_API_PRINCIPALS,
                "root:ADMIN::principal-only-secret",
                OperatorConfiguration.ENV_STATE_PATH, dir.resolve("state.json").toString()));
        server = new PCERestServer(0,
                new PCERestController(new CRPEngine()
                        .withDurableState(dir.resolve("state.json").toString()), topology()),
                null, 4, 8, configuration);
        server.start();

        assertEquals(200, call("GET", "/api/v1/config", "principal-only-secret", null).statusCode());
    }

    @Test
    void startupRefusesADeploymentWithNoCredentials() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new PCERestServer(0,
                        new PCERestController(new CRPEngine(), topology()),
                        null, 4, 8, configuration));
        assertTrue(refused.getMessage().contains("VORTEX_API_KEY"));
        assertTrue(refused.getMessage().contains("VORTEX_API_PRINCIPALS"));
    }

    @Test
    void twoPrincipalsMayNotShareASecret() {
        // They could not be told apart, so the tenant check and the audit trail would be fiction.
        assertThrows(IllegalArgumentException.class,
                () -> ApiPrincipals.parse("a:OPERATOR:t1:same,b:OPERATOR:t2:same", null));
    }

    @Test
    void aNamedPrincipalMayNotShareTheLegacyKey() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiPrincipals.parse("named:ADMIN::same", "same"));
    }

    @Test
    void anUnknownRoleIsRefusedAtStartup() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ApiPrincipals.parse("a:SUPERUSER:t1:secret", null));
        assertTrue(refused.getMessage().contains("VIEWER"), refused.getMessage());
    }
}
