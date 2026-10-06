package net.dcn.pce.pcep;

import net.dcn.pce.install.InstallationCoordinator;
import net.dcn.pce.install.InstallationState;
import net.dcn.pce.install.IntentLedger;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the session against a peer written in this repository.
 *
 * <p><strong>This is not interoperability evidence.</strong> The peer below is ours: it speaks the
 * bytes our own encoder produces and answers the way our own decoder expects. It proves the
 * session state machine, the framing, and the wiring into the intent ledger hold together. It
 * proves nothing about how a real PCC behaves, and a green run here must not be read as
 * "installation works". Stage 3, an independent PCC, remains blocked — see
 * {@code tests/interop/README.md}.
 *
 * <p>What is independently checked is the wire format itself: every frame the encoder produces is
 * dissected by Wireshark in CI.
 */
class PcepSessionServerTest {

    /** A minimal PCC written in this repository, for exercising our own session logic. */
    private static final class OurOwnPccFixture implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream in;

        OurOwnPccFixture(int port, String speakerEntityId) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            in = new DataInputStream(socket.getInputStream());

            expect(MSG_OPEN);
            socket.getOutputStream().write(PcepEncoder.open(30, 120, 1, speakerEntityId));
            expect(MSG_KEEPALIVE);
            socket.getOutputStream().write(PcepEncoder.keepalive());
            socket.getOutputStream().flush();
        }

        private static final int MSG_OPEN = 1;
        private static final int MSG_KEEPALIVE = 2;

        byte[] expect(int messageType) throws IOException {
            byte[] header = new byte[4];
            in.readFully(header);
            assertEquals(messageType, header[1] & 0xFF, "unexpected PCEP message type");
            int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            byte[] rest = new byte[length - 4];
            in.readFully(rest);
            byte[] whole = new byte[length];
            System.arraycopy(header, 0, whole, 0, 4);
            System.arraycopy(rest, 0, whole, 4, rest.length);
            return whole;
        }

        void report(long srpId, long plspId, String lspName, boolean up) throws IOException {
            socket.getOutputStream().write(pcRpt(srpId, plspId, lspName, up));
            socket.getOutputStream().flush();
        }

        @Override public void close() throws IOException { socket.close(); }
    }

    /** Builds a PCRpt by hand; the layout is the one Wireshark validates in CI. */
    private static byte[] pcRpt(long srpId, long plspId, String lspName, boolean up) {
        byte[] name = lspName.getBytes(StandardCharsets.US_ASCII);
        int padded = (name.length + 3) & ~3;
        byte[] tlv = new byte[4 + padded];
        tlv[0] = 0; tlv[1] = 17;
        tlv[2] = (byte) (name.length >> 8); tlv[3] = (byte) name.length;
        System.arraycopy(name, 0, tlv, 4, name.length);

        int flags = (up ? 1 : 0) << 4 | 0x008 | 0x001;
        byte[] lspBody = new byte[4 + tlv.length];
        long plspAndFlags = (plspId << 12) | flags;
        lspBody[0] = (byte) (plspAndFlags >>> 24); lspBody[1] = (byte) (plspAndFlags >>> 16);
        lspBody[2] = (byte) (plspAndFlags >>> 8);  lspBody[3] = (byte) plspAndFlags;
        System.arraycopy(tlv, 0, lspBody, 4, tlv.length);

        byte[] srp = new byte[]{33, 1 << 4, 0, 12, 0, 0, 0, 0,
                (byte) (srpId >>> 24), (byte) (srpId >>> 16), (byte) (srpId >>> 8), (byte) srpId};
        byte[] lsp = new byte[4 + lspBody.length];
        lsp[0] = 32; lsp[1] = 1 << 4;
        lsp[2] = (byte) ((4 + lspBody.length) >> 8); lsp[3] = (byte) (4 + lspBody.length);
        System.arraycopy(lspBody, 0, lsp, 4, lspBody.length);

        int total = 4 + srp.length + lsp.length;
        byte[] frame = new byte[total];
        frame[0] = (1 << 5); frame[1] = 10;
        frame[2] = (byte) (total >> 8); frame[3] = (byte) total;
        System.arraycopy(srp, 0, frame, 4, srp.length);
        System.arraycopy(lsp, 0, frame, 4 + srp.length, lsp.length);
        return frame;
    }

    private static void awaitSession(PcepSessionServer server, String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (server.establishedSessions().contains(key)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("session " + key + " never established: "
                + server.establishedSessions());
    }

    @Test
    void aPeerCompletesTheHandshakeAndIsKeyedByItsSpeakerIdentity() throws Exception {
        List<ReportedLsp> seen = new CopyOnWriteArrayList<>();
        try (PcepSessionServer server = new PcepSessionServer(0, "vortex-pce", (sessionKey, report) -> seen.add(report))) {
            server.start("127.0.0.1");
            try (OurOwnPccFixture pcc = new OurOwnPccFixture(server.getBoundPort(), "pcc-alpha")) {
                awaitSession(server, "speaker:pcc-alpha");
                assertEquals(1, server.establishedSessions().size());
            }
        }
    }

    @Test
    void aReconnectingPeerSupersedesItsOwnSession() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(0, "vortex-pce", (sessionKey, report) -> { })) {
            server.start("127.0.0.1");
            OurOwnPccFixture first = new OurOwnPccFixture(server.getBoundPort(), "pcc-alpha");
            awaitSession(server, "speaker:pcc-alpha");

            // A new source port, exactly as a restarted PCC would present.
            try (OurOwnPccFixture second = new OurOwnPccFixture(server.getBoundPort(), "pcc-alpha")) {
                awaitSession(server, "speaker:pcc-alpha");
                assertEquals(1, server.establishedSessions().size(),
                        "a reconnecting speaker must replace itself, not accumulate");
            }
            first.close();
        }
    }

    @Test
    void aPcInitiateReachesThePeerAndItsReportConfirmsTheInstall() throws Exception {
        IntentLedger ledger = new IntentLedger();
        InstallationCoordinator coordinator =
                new InstallationCoordinator(ledger, Clock.systemUTC(), Duration.ofSeconds(30));
        ledger.plan("T1");

        try (PcepSessionServer server = new PcepSessionServer(0, "vortex-pce", coordinator::apply)) {
            server.start("127.0.0.1");
            try (OurOwnPccFixture pcc = new OurOwnPccFixture(server.getBoundPort(), "pcc-alpha")) {
                awaitSession(server, "speaker:pcc-alpha");

                coordinator.recordSent("T1", 7L, "speaker:pcc-alpha");
                assertEquals(PcepSessionServer.SendOutcome.SENT,
                        server.send("speaker:pcc-alpha", PcepEncoder.pcInitiate(
                                7L, "vortex-T1", "10.0.0.1", "10.0.0.2", 1.0e8, null)),
                        "the initiate should reach an established peer");

                pcc.expect(12); // the PCInitiate arrived intact
                pcc.report(7L, 42L, "vortex-T1", true);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline
                        && ledger.find("T1").orElseThrow().getState() != InstallationState.INSTALLED) {
                    Thread.sleep(20);
                }
                assertEquals(InstallationState.INSTALLED,
                        ledger.find("T1").orElseThrow().getState(),
                        "a report from our own peer should drive the intent to INSTALLED");
                assertEquals(42L, ledger.find("T1").orElseThrow().getPlspId().orElseThrow(),
                        "the PCC-assigned identifier should be adopted from its report");
            }
        }
    }

    @Test
    void sendingToAnUnknownPeerReportsFailureRatherThanSilentlyDropping() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(0, "vortex-pce", (sessionKey, report) -> { })) {
            server.start("127.0.0.1");
            // NO_SESSION rather than a generic failure: it is the only outcome that lets a caller
            // undo a record it wrote before sending, because nothing can have left the process.
            assertEquals(PcepSessionServer.SendOutcome.NO_SESSION,
                    server.send("speaker:absent", PcepEncoder.keepalive()),
                    "a caller must be able to tell that nothing was delivered");
        }
    }

    @Test
    void aMalformedReportDoesNotKillTheSession() throws Exception {
        List<ReportedLsp> seen = new CopyOnWriteArrayList<>();
        try (PcepSessionServer server = new PcepSessionServer(0, "vortex-pce", (sessionKey, report) -> seen.add(report))) {
            server.start("127.0.0.1");
            try (OurOwnPccFixture pcc = new OurOwnPccFixture(server.getBoundPort(), "pcc-alpha")) {
                awaitSession(server, "speaker:pcc-alpha");

                // A PCRpt with no LSP object: well framed, undecodable.
                byte[] junk = new byte[]{(1 << 5), 10, 0, 8, 33, 1 << 4, 0, 4};
                pcc.socket.getOutputStream().write(junk);
                pcc.socket.getOutputStream().flush();

                // The session must survive it and still carry a good report afterwards.
                pcc.report(1L, 7L, "vortex-OK", true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline && seen.isEmpty()) {
                    Thread.sleep(20);
                }
                assertEquals(1, seen.size(), "one bad frame must not deafen the session");
                assertEquals("vortex-OK", seen.get(0).lspName());
            }
        }
    }

    @Test
    void theSessionCapRefusesExcessPeers() throws Exception {
        try (PcepSessionServer server =
                     new PcepSessionServer(0, "vortex-pce", (sessionKey, report) -> { }, 30, 120, 1)) {
            server.start("127.0.0.1");
            try (OurOwnPccFixture first = new OurOwnPccFixture(server.getBoundPort(), "pcc-alpha")) {
                awaitSession(server, "speaker:pcc-alpha");

                // An unauthenticated port that accepted unbounded sessions would be a cheap
                // denial of service; the second peer is refused rather than queued.
                Socket extra = new Socket("127.0.0.1", server.getBoundPort());
                extra.setSoTimeout(3000);
                Thread.sleep(300);
                assertEquals(1, server.establishedSessions().size());
                extra.close();
            }
        }
    }
}
