package net.dcn.pce.northbound;

import net.dcn.pce.config.OperatorConfiguration;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.install.InstallationState;
import net.dcn.pce.install.InstallationIntent;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.LinkIntermittencyFunction;
import net.dcn.pce.model.Node;
import net.dcn.pce.pcep.PcepEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The southbound listener as a running controller starts it.
 *
 * <p>The peer here is written in this repository, so this establishes that the wiring holds
 * together, not that a real PCC behaves this way. See {@code tests/interop/README.md}.
 */
class PcepListenerWiringTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e9, 1e9));
        topology.addNode(new Node("B", "B", 1e9, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
        return topology;
    }

    private static PCERestServer serverWith(Path dir, Map<String, String> env) throws Exception {
        return serverWith(new CRPEngine().withDurableState(dir.resolve("state.json").toString()), env);
    }

    private static PCERestServer serverWith(CRPEngine engine, Map<String, String> env)
            throws Exception {
        OperatorConfiguration configuration = OperatorConfiguration.parse(env);
        PCERestServer server = new PCERestServer(
                0, new PCERestController(engine, topology()), "secret", 4, 8, configuration);
        server.start();
        return server;
    }

    @Test
    void theListenerIsOffUnlessTheOperatorAsksForIt(@TempDir Path dir) throws Exception {
        PCERestServer server = serverWith(dir, Map.of());
        try {
            assertTrue(server.pcepListener().isEmpty(),
                    "an unconfirmed southbound loop must not start by default");

            HttpResponse<String> metrics = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + server.getBoundPort() + "/metrics"))
                            .header("X-API-Key", "secret").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertFalse(metrics.body().contains("vortex_pcc_sessions_established"),
                    "no listener means no session gauge, which reads differently from zero peers");
        } finally {
            server.stop();
        }
    }

    @Test
    void anEnabledListenerAcceptsAPeerAndItsReportUpdatesIntent(@TempDir Path dir) throws Exception {
        PCERestServer server = serverWith(dir, Map.of(
                OperatorConfiguration.ENV_PCEP_LISTENER, "JAVA",
                OperatorConfiguration.ENV_PCEP_PORT, "0",
                OperatorConfiguration.ENV_PCEP_BIND, "127.0.0.1"));
        try {
            var listener = server.pcepListener().orElseThrow();
            var coordinator = server.installationCoordinator().orElseThrow();

            // A task the controller has planned, and an operation it believes outstanding.
            server.getClass();
            var ledger = coordinator.outstandingAcknowledgements();
            assertTrue(ledger.isEmpty());

            try (Socket pcc = new Socket("127.0.0.1", listener.getBoundPort())) {
                pcc.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(pcc.getInputStream());
                readFrame(in);                                   // our OPEN
                pcc.getOutputStream().write(PcepEncoder.open(30, 120, 1, "pcc-alpha"));
                readFrame(in);                                   // our KEEPALIVE
                pcc.getOutputStream().write(PcepEncoder.keepalive());
                pcc.getOutputStream().flush();

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline
                        && !listener.establishedSessions().contains("speaker:pcc-alpha")) {
                    Thread.sleep(20);
                }
                assertTrue(listener.establishedSessions().contains("speaker:pcc-alpha"));

                HttpResponse<String> metrics = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://127.0.0.1:" + server.getBoundPort() + "/metrics"))
                                .header("X-API-Key", "secret").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(metrics.body().contains("vortex_pcc_sessions_established 1"),
                        "an established peer should be visible to an operator");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void theEffectiveConfigurationReportsTheListener(@TempDir Path dir) throws Exception {
        PCERestServer server = serverWith(dir, Map.of(
                OperatorConfiguration.ENV_PCEP_LISTENER, "JAVA",
                OperatorConfiguration.ENV_PCEP_PORT, "0"));
        try {
            HttpResponse<String> config = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + server.getBoundPort() + "/api/v1/config"))
                            .header("X-API-Key", "secret").GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, config.statusCode());
            assertTrue(config.body().contains("\"pcepListener\": \"JAVA\""), config.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void anUnknownListenerValueFailsStartup() {
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                        () -> OperatorConfiguration.parse(Map.of(
                                OperatorConfiguration.ENV_PCEP_LISTENER, "SOMETHING_ELSE")))
                .getMessage().contains("VORTEX_PCEP_LISTENER"));
    }

    @Test
    void stoppingTheServerClosesTheListener(@TempDir Path dir) throws Exception {
        PCERestServer server = serverWith(dir, Map.of(
                OperatorConfiguration.ENV_PCEP_LISTENER, "JAVA",
                OperatorConfiguration.ENV_PCEP_PORT, "0"));
        int pcepPort = server.pcepListener().orElseThrow().getBoundPort();
        server.stop();

        // The southbound port must not outlive the controller that owns it.
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> {
            try (Socket ignored = new Socket("127.0.0.1", pcepPort)) {
                Thread.sleep(200);
                throw new java.io.IOException("port still accepting");
            }
        });
    }

    @Test
    void aPcepBindFailureRollsBackTheAlreadyStartedHttpServer(@TempDir Path dir)
            throws Exception {
        try (ServerSocket occupied = new ServerSocket(
                0, 1, InetAddress.getLoopbackAddress())) {
            OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                    OperatorConfiguration.ENV_PCEP_LISTENER, "JAVA",
                    OperatorConfiguration.ENV_PCEP_PORT,
                    String.valueOf(occupied.getLocalPort()),
                    OperatorConfiguration.ENV_PCEP_BIND, "127.0.0.1"));
            PCERestServer server = new PCERestServer(
                    0,
                    new PCERestController(
                            new CRPEngine().withDurableState(
                                    dir.resolve("state.json").toString()),
                            topology()),
                    "secret", 4, 8, configuration);

            assertThrows(IllegalStateException.class, server::start);
            assertEquals(-1, server.getBoundPort(),
                    "a required PCEP failure must not leave an HTTP listener behind");
            assertTrue(server.pcepListener().isEmpty(),
                    "the partially constructed PCEP listener must be released");

            // Cleanup must remain idempotent after the failed transactional start.
            server.stop();
        }
    }

    /**
     * The southbound half of cancellation, end to end.
     *
     * <p>Cancelling an installed LSP used to move the intent to DELETING and answer 202 without
     * sending anything: the reservations stayed held, correctly, but no router had been asked to
     * remove anything. The assertion that matters here is not the status code -- it is that the
     * peer receives a frame.
     */
    @Test
    void cancellingAnInstalledTaskSendsARemovalToThePccThatOwnsIt(@TempDir Path dir)
            throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestServer server = serverWith(engine, Map.of(
                OperatorConfiguration.ENV_PCEP_LISTENER, "JAVA",
                OperatorConfiguration.ENV_PCEP_PORT, "0",
                OperatorConfiguration.ENV_PCEP_BIND, "127.0.0.1"));
        try {
            var listener = server.pcepListener().orElseThrow();
            var coordinator = server.installationCoordinator().orElseThrow();
            engine.solve(topology(),
                    java.util.List.of(new net.dcn.pce.model.WorkloadTask("T1", "A", "B", 0, 10_000, 1_000)));

            try (Socket pcc = new Socket("127.0.0.1", listener.getBoundPort())) {
                pcc.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(pcc.getInputStream());
                readFrame(in);                                   // our OPEN
                pcc.getOutputStream().write(PcepEncoder.open(30, 120, 1, "pcc-alpha"));
                readFrame(in);                                   // our KEEPALIVE
                pcc.getOutputStream().write(PcepEncoder.keepalive());
                pcc.getOutputStream().flush();

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline
                        && !listener.establishedSessions().contains("speaker:pcc-alpha")) {
                    Thread.sleep(20);
                }

                // The controller sent an install and this peer reported the LSP up, which is what
                // gives the intent both an owner and the PLSP-ID a removal has to name.
                coordinator.recordSent("T1", 7L, "speaker:pcc-alpha");
                pcc.getOutputStream().write(TSHARK_VALIDATED_PCRPT);
                pcc.getOutputStream().flush();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline && engine.getIntents().find("T1")
                        .map(i -> i.getState() != InstallationState.INSTALLED).orElse(true)) {
                    Thread.sleep(20);
                }
                assertEquals(InstallationState.INSTALLED,
                        engine.getIntents().find("T1").orElseThrow().getState());

                HttpResponse<String> cancel = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                        + server.getBoundPort() + "/api/v1/tasks/T1"))
                                .header("X-API-Key", "secret").DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(202, cancel.statusCode(), cancel.body());

                byte[] removal = nextPcInitiate(in);
                long srpId = ((long) (removal[12] & 0xFF) << 24) | ((removal[13] & 0xFF) << 16)
                        | ((removal[14] & 0xFF) << 8) | (removal[15] & 0xFF);
                assertArrayEquals(PcepEncoder.pcInitiateRemoval(srpId, 42L, "vortex-T1"), removal,
                        "the peer must receive a removal naming the PLSP-ID it reported");
                assertEquals(srpId, engine.getIntents().find("T1").orElseThrow()
                                .getSrpId().orElseThrow(),
                        "the ledger records the SRP the PCC will answer with");
                assertFalse(engine.getLRIB().getAllReservations().isEmpty(),
                        "capacity stays held until the PCC confirms the LSP is gone");
            }
        } finally {
            server.stop();
        }
    }

    /**
     * Reconciliation, driven by a PCC saying it has finished synchronising.
     *
     * <p>This is the wiring the end-of-synchronisation marker exists for, and it was unreachable:
     * the decoder rejected the marker as a nameless LSP object, so the running controller never
     * learned that a report was complete. Reconciliation is the only path that acts on absence,
     * and absence only means anything once synchronisation is done.
     */
    @Test
    void aCompletedSynchronisationReconcilesIntentsThePccNoLongerReports(@TempDir Path dir)
            throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestServer server = serverWith(engine, Map.of(
                OperatorConfiguration.ENV_PCEP_LISTENER, "JAVA",
                OperatorConfiguration.ENV_PCEP_PORT, "0",
                OperatorConfiguration.ENV_PCEP_BIND, "127.0.0.1"));
        try {
            var listener = server.pcepListener().orElseThrow();
            var coordinator = server.installationCoordinator().orElseThrow();
            engine.solve(topology(),
                    java.util.List.of(new net.dcn.pce.model.WorkloadTask("T1", "A", "B", 0, 10_000, 1_000)));

            try (Socket pcc = new Socket("127.0.0.1", listener.getBoundPort())) {
                pcc.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(pcc.getInputStream());
                readFrame(in);                                   // our OPEN
                pcc.getOutputStream().write(PcepEncoder.open(30, 120, 1, "pcc-alpha"));
                readFrame(in);                                   // our KEEPALIVE
                pcc.getOutputStream().write(PcepEncoder.keepalive());
                pcc.getOutputStream().flush();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline
                        && !listener.establishedSessions().contains("speaker:pcc-alpha")) {
                    Thread.sleep(20);
                }

                // An install this controller sent to this very peer and never heard back about.
                coordinator.recordSent("T1", 7L, "speaker:pcc-alpha");
                assertEquals(InstallationState.INSTALLING,
                        engine.getIntents().find("T1").orElseThrow().getState());

                // The peer now finishes synchronising without ever naming it. After a complete
                // report, absence is evidence: the PCC never created the LSP, so the bandwidth
                // reserved for it is backing nothing. (An intent already INSTALLED that goes
                // missing is deliberately left alone -- that is Reconciliation.Action.LOST, an
                // operator's call rather than this controller's.)
                pcc.getOutputStream().write(FRR_END_OF_SYNC);
                pcc.getOutputStream().flush();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline && engine.getIntents().find("T1")
                        .map(i -> i.getState() != InstallationState.FAILED).orElse(true)) {
                    Thread.sleep(20);
                }

                assertEquals(InstallationState.FAILED,
                        engine.getIntents().find("T1").orElseThrow().getState(),
                        "a completed sync that never names an outstanding install resolves it");
                assertTrue(engine.getLRIB().getAllReservations().isEmpty(),
                        "and the capacity it was holding must be released");
            }
        } finally {
            server.stop();
        }
    }

    /**
     * The acknowledgement clock has to be driven by something.
     *
     * <p>{@code expireOverdueAcknowledgements()} existed and was unit-tested, and nothing outside
     * those tests ever called it. An operation that was never answered therefore stayed INSTALLING
     * forever while holding its capacity, and the configured timeout described a behaviour that
     * did not happen. This asserts the running controller, not the coordinator.
     */
    @Test
    void anUnacknowledgedOperationBecomesUncertainWithoutAnyoneAskingIt(@TempDir Path dir)
            throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestServer server = serverWith(engine, Map.of(
                OperatorConfiguration.ENV_PCEP_LISTENER, "JAVA",
                OperatorConfiguration.ENV_PCEP_PORT, "0",
                OperatorConfiguration.ENV_PCEP_BIND, "127.0.0.1",
                OperatorConfiguration.ENV_PCEP_ACK_TIMEOUT_SEC, "1"));
        try {
            engine.solve(topology(),
                    java.util.List.of(new net.dcn.pce.model.WorkloadTask("T1", "A", "B", 0, 10_000, 1_000)));
            server.installationCoordinator().orElseThrow()
                    .recordSent("T1", 7L, "speaker:pcc-alpha");

            // No report ever arrives. Nothing here calls the sweep; the server has to.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline && engine.getIntents().find("T1")
                    .map(i -> i.getState() != InstallationState.UNCERTAIN).orElse(true)) {
                Thread.sleep(50);
            }

            assertEquals(InstallationState.UNCERTAIN,
                    engine.getIntents().find("T1").orElseThrow().getState(),
                    "an operation nobody answered must reach UNCERTAIN on its own");
            assertFalse(engine.getLRIB().getAllReservations().isEmpty(),
                    "and must keep its capacity: unacknowledged is not evidence of absent");
        } finally {
            server.stop();
        }
    }

    /** FRRouting pathd's end-of-synchronisation marker, captured from a live interop run. */
    private static final byte[] FRR_END_OF_SYNC = java.util.HexFormat.of().parseHex(
            "200a00242012001c00000000001200100000000000000000000000000000000007120004");

    /** The same topology, with addresses, so a computed schedule can be encoded for the wire. */
    private static BaseTopology addressedTopology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e9, 1e9, "10.0.0.1"));
        topology.addNode(new Node("B", "B", 1e9, 1e9, "10.0.0.2"));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
        return topology;
    }

    private static PCERestServer autoInstallServer(CRPEngine engine, String mode) throws Exception {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_PCEP_LISTENER, "JAVA",
                OperatorConfiguration.ENV_PCEP_PORT, "0",
                OperatorConfiguration.ENV_PCEP_BIND, "127.0.0.1",
                OperatorConfiguration.ENV_PCEP_AUTO_INSTALL, mode));
        PCERestServer server = new PCERestServer(
                0, new PCERestController(engine, addressedTopology()), "secret", 4, 8, configuration);
        server.start();
        return server;
    }

    private static int solveOne(PCERestServer server) throws Exception {
        String body = "[{\"taskId\": \"T1\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\","
                + " \"originationTimeSec\": 0, \"deadlineSec\": 60, \"taskSizeBytes\": 1000000}]";
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + server.getBoundPort() + "/api/v1/solve"))
                        .header("X-API-Key", "secret").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /**
     * The join this controller was missing: a solve reaches a router by itself.
     *
     * <p>Until the topology model carried addresses, a committed schedule could not be encoded into
     * a PCInitiate at all, so every schedule had to be applied to a network by hand. This asserts
     * the frame on the wire, not merely that an intent changed state.
     */
    @Test
    void aSolveDispatchesAPcInitiateToTheOnlyEstablishedPcc(@TempDir Path dir) throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestServer server = autoInstallServer(engine, "SINGLE_PCC");
        try {
            var listener = server.pcepListener().orElseThrow();
            try (Socket pcc = new Socket("127.0.0.1", listener.getBoundPort())) {
                pcc.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(pcc.getInputStream());
                readFrame(in);
                pcc.getOutputStream().write(PcepEncoder.open(30, 120, 1, "pcc-alpha"));
                readFrame(in);
                pcc.getOutputStream().write(PcepEncoder.keepalive());
                pcc.getOutputStream().flush();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline
                        && !listener.establishedSessions().contains("speaker:pcc-alpha")) {
                    Thread.sleep(20);
                }

                assertEquals(200, solveOne(server));

                byte[] initiate = nextPcInitiate(in);
                assertEquals(12, initiate[1] & 0xFF, "the PCC must receive a PCInitiate");

                InstallationIntent intent = engine.getIntents().find("T1").orElseThrow();
                assertEquals(InstallationState.INSTALLING, intent.getState(),
                        "the intent must be recorded before the frame is sent, not after");
                assertEquals("speaker:pcc-alpha", intent.getPccSessionKey().orElseThrow());
                assertTrue(intent.getSrpId().isPresent(), "the PCC will answer by SRP");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void withAutoInstallOffASolveSendsNothing(@TempDir Path dir) throws Exception {
        // The default. Enabling a southbound listener is not the same as asking this controller to
        // start programming routers.
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestServer server = autoInstallServer(engine, "DISABLED");
        try {
            var listener = server.pcepListener().orElseThrow();
            try (Socket pcc = new Socket("127.0.0.1", listener.getBoundPort())) {
                pcc.setSoTimeout(4000);
                DataInputStream in = new DataInputStream(pcc.getInputStream());
                readFrame(in);
                pcc.getOutputStream().write(PcepEncoder.open(30, 120, 1, "pcc-alpha"));
                readFrame(in);
                pcc.getOutputStream().write(PcepEncoder.keepalive());
                pcc.getOutputStream().flush();
                Thread.sleep(500);

                assertEquals(200, solveOne(server));
                Thread.sleep(1000);

                assertEquals(InstallationState.PLANNED,
                        engine.getIntents().find("T1").orElseThrow().getState(),
                        "nothing may be dispatched while automatic installation is off");
            }
        } finally {
            server.stop();
        }
    }

        /** Reads frames until a PCInitiate arrives, skipping the keepalives that share the socket. */
    private static byte[] nextPcInitiate(DataInputStream in) throws Exception {
        for (int frame = 0; frame < 20; frame++) {
            byte[] header = new byte[4];
            in.readFully(header);
            int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            byte[] rest = new byte[length - 4];
            in.readFully(rest);
            if ((header[1] & 0xFF) == 12) {
                byte[] whole = new byte[length];
                System.arraycopy(header, 0, whole, 0, 4);
                System.arraycopy(rest, 0, whole, 4, rest.length);
                return whole;
            }
        }
        throw new AssertionError("no PCInitiate arrived; the removal was never sent");
    }

    /** The report fixture from {@code PcepReportDecoderTest}: SRP 7, PLSP-ID 42, vortex-T1, up. */
    private static final byte[] TSHARK_VALIDATED_PCRPT = java.util.HexFormat.of().parseHex(
            "200a00282110000c0000000000000007201000180002a01900110009766f727465782d5431000000");

    private static void readFrame(DataInputStream in) throws Exception {
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        in.readFully(new byte[length - 4]);
    }
}
