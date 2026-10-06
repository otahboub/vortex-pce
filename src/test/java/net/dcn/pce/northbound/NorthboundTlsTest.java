package net.dcn.pce.northbound;

import net.dcn.pce.config.OperatorConfiguration;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The API credential must not cross the network in cleartext.
 *
 * <p>A single {@code X-API-Key} authorises solving, cancellation and capacity mutation, and it was
 * sent as a plain header over plain HTTP on every request. Comparing it in constant time — which
 * this controller does — protects nothing if anyone able to observe the traffic can read it.
 */
class NorthboundTlsTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    private static void keytool(Path dir, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "keytool timed out");
        assertEquals(0, process.exitValue(), "keytool failed: " + output);
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

    private static PCERestServer server(Map<String, String> environment) throws Exception {
        OperatorConfiguration configuration = OperatorConfiguration.parse(environment);
        PCERestServer server = new PCERestServer(0,
                new PCERestController(new CRPEngine(), topology()), "secret", 4, 8, configuration);
        server.start();
        return server;
    }

    private static HttpClient trusting(Path dir) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(dir.resolve("api.p12"))) {
            trust.load(in, PASSWORD);
        }
        TrustManagerFactory trusts =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusts.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trusts.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(context).build();
    }

    @Test
    void theApiServesHttpsWhenAKeystoreIsConfigured(@TempDir Path dir) throws Exception {
        keytool(dir, "-genkeypair", "-alias", "api", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", "api.p12",
                "-storepass", "changeit", "-keypass", "changeit");

        PCERestServer server = server(Map.of(
                OperatorConfiguration.ENV_API_TLS_KEYSTORE, dir.resolve("api.p12").toString(),
                OperatorConfiguration.ENV_API_TLS_KEYSTORE_PASSWORD, "changeit"));
        try {
            HttpResponse<String> response = trusting(dir).send(
                    HttpRequest.newBuilder(
                            URI.create("https://localhost:" + server.getBoundPort() + "/livez"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("https", response.uri().getScheme());
        } finally {
            server.stop();
        }
    }

    @Test
    void plainHttpIsRefusedOnceTlsIsOn(@TempDir Path dir) throws Exception {
        // Otherwise a client that forgot the scheme would send the credential in the clear to a
        // server the operator believes is protected.
        keytool(dir, "-genkeypair", "-alias", "api", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", "api.p12",
                "-storepass", "changeit", "-keypass", "changeit");

        PCERestServer server = server(Map.of(
                OperatorConfiguration.ENV_API_TLS_KEYSTORE, dir.resolve("api.p12").toString(),
                OperatorConfiguration.ENV_API_TLS_KEYSTORE_PASSWORD, "changeit"));
        try {
            assertThrows(IOException.class, () -> HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.getBoundPort() + "/livez"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()));
        } finally {
            server.stop();
        }
    }

    @Test
    void anUnusableKeystoreAbortsStartupRatherThanServingCleartext(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("broken.p12"), "not a keystore");
        IOException refused = assertThrows(IOException.class, () -> server(Map.of(
                OperatorConfiguration.ENV_API_TLS_KEYSTORE, dir.resolve("broken.p12").toString(),
                OperatorConfiguration.ENV_API_TLS_KEYSTORE_PASSWORD, "changeit")));
        assertTrue(refused.getMessage().contains("refusing to serve the API in cleartext"),
                refused.getMessage());
    }

    @Test
    void theApiStaysOnPlainHttpWhenNoKeystoreIsConfigured(@TempDir Path dir) throws Exception {
        // Every deployment that predates this keeps working.
        PCERestServer server = server(Map.of());
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getBoundPort() + "/livez"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
        } finally {
            server.stop();
        }
    }
}
