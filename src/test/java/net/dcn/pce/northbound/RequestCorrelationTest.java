package net.dcn.pce.northbound;

import net.dcn.pce.config.OperatorConfiguration;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A log line must say which request produced it.
 *
 * <p>The solve path logged receipt, then the computed result, then a line per dispatch, with
 * nothing connecting them. Every line was true and the sequence was unreconstructable: no way to
 * tell which dispatch belonged to which solve, or which of several interleaved requests a warning
 * came from.
 */
class RequestCorrelationTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e10, 1e9));
        topology.addNode(new Node("B", "B", 1e10, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e8, 0.0001)));
        return topology;
    }

    private static final String TASK =
            "[{\"taskId\": \"T1\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\","
                    + " \"originationTimeSec\": 0, \"deadlineSec\": 100,"
                    + " \"taskSizeBytes\": 4000000, \"priority\": 1}]";

    private static PCERestServer server() throws Exception {
        PCERestServer server = new PCERestServer(0,
                new PCERestController(new CRPEngine(), topology()), "secret", 4, 8,
                OperatorConfiguration.parse(Map.of()));
        server.start();
        return server;
    }

    private static HttpResponse<String> solve(PCERestServer server, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.getBoundPort() + "/api/v1/solve"))
                        .header("X-API-Key", "secret").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        PCERestServer server = server();
        try {
            HttpResponse<String> response = solve(server, TASK);
            assertEquals(200, response.statusCode());
            String id = response.headers().firstValue("X-Request-Id").orElse("");
            assertFalse(id.isBlank(), "a caller reporting a problem needs something to quote");
        } finally {
            server.stop();
        }
    }

    @Test
    void afailedRequestCarriesOneToo() throws Exception {
        // The request a caller asks about is usually the one that failed, so the id must be on
        // error responses rather than only on success.
        PCERestServer server = server();
        try {
            HttpResponse<String> response = solve(server, "not json");
            assertTrue(response.statusCode() >= 400, "expected a rejection");
            assertFalse(response.headers().firstValue("X-Request-Id").orElse("").isBlank(),
                    "an error response must be identifiable");
        } finally {
            server.stop();
        }
    }

    @Test
    void twoRequestsGetDifferentIds() throws Exception {
        PCERestServer server = server();
        try {
            String first = solve(server, TASK).headers()
                    .firstValue("X-Request-Id").orElse("");
            String second = solve(server, TASK).headers()
                    .firstValue("X-Request-Id").orElse("");
            assertNotEquals(first, second, "ids that repeat correlate nothing");
        } finally {
            server.stop();
        }
    }

    @Test
    void anIdDoesNotLeakOntoTheNextRequestOnAPooledThread() throws Exception {
        // HTTP threads are reused. An id left behind would attach itself to whatever that thread
        // served next, which is worse than no correlation: confidently wrong.
        assertEquals("", RequestContext.current(), "no scope should be active on a fresh thread");

        AtomicReference<String> insideScope = new AtomicReference<>();
        AtomicReference<String> afterScope = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                RequestContext.begin();
                insideScope.set(RequestContext.current());
            } finally {
                RequestContext.end();
            }
            afterScope.set(RequestContext.current());
            done.countDown();
        });
        worker.start();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertFalse(insideScope.get().isBlank());
        assertEquals("", afterScope.get(),
                "the scope must be cleared, or the thread's next request inherits this id");
    }

    @Test
    void taggingOutsideARequestChangesNothing() {
        // PCEP reports arrive on session threads with no originating request. Inventing an id for
        // them would suggest a link that does not exist.
        assertEquals("a report arrived", RequestContext.tag("a report arrived"));
    }
}
