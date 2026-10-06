package net.dcn.pce.pcep;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Liveness and admission bounds on an unauthenticated listener.
 *
 * <p>Two defects the review found: the PCE never sent periodic KEEPALIVEs after establishment, so
 * a conformant peer could tear down an idle session because the far end appeared dead; and the
 * connection cap counted only established sessions, so peers that never completed a handshake each
 * held a thread while remaining invisible to the check.
 */
class SessionLivenessAndPermitsTest {

    private static final int MSG_OPEN = 1;
    private static final int MSG_KEEPALIVE = 2;

    /** A peer that completes the handshake and then says nothing. */
    private static final class QuietPeer implements AutoCloseable {
        final Socket socket;
        private final DataInputStream in;

        QuietPeer(int port, String speaker, int readTimeoutMs) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(readTimeoutMs);
            in = new DataInputStream(socket.getInputStream());
            expect(MSG_OPEN);
            socket.getOutputStream().write(PcepEncoder.open(1, 3, 1, speaker));
            expect(MSG_KEEPALIVE);
            socket.getOutputStream().write(PcepEncoder.keepalive());
            socket.getOutputStream().flush();
        }

        int expect(int messageType) throws IOException {
            byte[] header = new byte[4];
            in.readFully(header);
            int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            in.readFully(new byte[length - 4]);
            int actual = header[1] & 0xFF;
            if (messageType >= 0) {
                assertEquals(messageType, actual, "unexpected PCEP message type");
            }
            return actual;
        }

        @Override public void close() throws IOException { socket.close(); }
    }

    private static void awaitSession(PcepSessionServer server, String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (server.establishedSessions().contains(key)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("session never established: " + server.establishedSessions());
    }

    @Test
    void theListenerSendsKeepalivesToAnIdlePeer() throws Exception {
        // A one-second keepalive with a three-second dead timer: a quiet peer should be sent
        // unsolicited KEEPALIVEs rather than left to conclude the PCE is gone.
        try (PcepSessionServer server = new PcepSessionServer(
                0, "vortex-pce", (session, report) -> { }, 1, 3, 8)) {
            server.start("127.0.0.1");
            try (QuietPeer peer = new QuietPeer(server.getBoundPort(), "pcc-alpha", 4000)) {
                awaitSession(server, "speaker:pcc-alpha");

                assertEquals(MSG_KEEPALIVE, peer.expect(-1),
                        "the PCE must demonstrate liveness on an otherwise silent session");
            }
        }
    }

    @Test
    void aPeerThatGoesSilentPastTheDeadTimerIsDroppedButKeepsBeingProbedFirst() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(
                0, "vortex-pce", (session, report) -> { }, 1, 3, 8)) {
            server.start("127.0.0.1");
            try (QuietPeer peer = new QuietPeer(server.getBoundPort(), "pcc-alpha", 6000)) {
                awaitSession(server, "speaker:pcc-alpha");

                // At least two probes arrive before the dead timer expires.
                assertEquals(MSG_KEEPALIVE, peer.expect(-1));
                assertEquals(MSG_KEEPALIVE, peer.expect(-1));

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline
                        && server.establishedSessions().contains("speaker:pcc-alpha")) {
                    Thread.sleep(50);
                }
                assertTrue(server.establishedSessions().isEmpty(),
                        "a peer silent past the dead timer should be dropped");
            }
        }
    }

    /**
     * The case the previous keepalive test could not catch.
     *
     * <p>Sending only on a read timeout meant a peer that talks continuously stopped the PCE from
     * ever transmitting: the timeout branch never ran. Such a peer is entitled to conclude the PCE
     * is dead while it is being actively talked to.
     */
    @Test
    void aChattyPeerStillReceivesKeepalivesFromThePce() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(
                0, "vortex-pce", (session, report) -> { }, 1, 30, 8)) {
            server.start("127.0.0.1");
            try (QuietPeer peer = new QuietPeer(server.getBoundPort(), "pcc-alpha", 8000)) {
                awaitSession(server, "speaker:pcc-alpha");

                // Keep the socket busy well inside the one-second read timeout, so the timeout
                // branch never fires, then require the PCE to have transmitted anyway.
                Thread chatter = new Thread(() -> {
                    try {
                        for (int i = 0; i < 40; i++) {
                            synchronized (peer.socket) {
                                peer.socket.getOutputStream().write(PcepEncoder.keepalive());
                                peer.socket.getOutputStream().flush();
                            }
                            Thread.sleep(100);
                        }
                    } catch (Exception ignored) {
                        // The test ends when the assertion below completes.
                    }
                });
                chatter.setDaemon(true);
                chatter.start();

                assertEquals(MSG_KEEPALIVE, peer.expect(-1),
                        "the PCE must keep its own outbound schedule regardless of inbound traffic");
                chatter.interrupt();
            }
        }
    }

    /**
     * The permit race: peers that never finish the handshake used to be invisible to the cap.
     * Each held a thread, so an unauthenticated port could be turned into a thread sink by simply
     * connecting and saying nothing.
     */
    @Test
    void handshakingPeersCountTowardTheConnectionCap() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(
                0, "vortex-pce", (session, report) -> { }, 5, 30, 2)) {
            server.start("127.0.0.1");
            List<Socket> silent = new ArrayList<>();
            try {
                // Two connections that accept the OPEN and then never reply.
                for (int i = 0; i < 2; i++) {
                    Socket socket = new Socket("127.0.0.1", server.getBoundPort());
                    socket.setSoTimeout(3000);
                    silent.add(socket);
                }
                Thread.sleep(400);
                assertTrue(server.establishedSessions().isEmpty(),
                        "neither peer completed a handshake");

                // A third must be refused even though nothing is established.
                Socket third = new Socket("127.0.0.1", server.getBoundPort());
                third.setSoTimeout(2000);
                silent.add(third);
                DataInputStream in = new DataInputStream(third.getInputStream());
                assertThrows(IOException.class, () -> {
                    byte[] header = new byte[4];
                    in.readFully(header);
                }, "the cap must count handshaking peers, not only established ones");
            } finally {
                for (Socket socket : silent) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // closing a refused socket is fine
                    }
                }
            }
        }
    }

    @Test
    void aPermitIsReleasedWhenAPeerDisconnects() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(
                0, "vortex-pce", (session, report) -> { }, 5, 30, 1)) {
            server.start("127.0.0.1");

            QuietPeer first = new QuietPeer(server.getBoundPort(), "pcc-alpha", 3000);
            awaitSession(server, "speaker:pcc-alpha");
            first.close();

            // Once the slot frees, a new peer must be able to take it; a permit leaked on
            // disconnect would wedge the listener at its cap forever.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && !server.establishedSessions().isEmpty()) {
                Thread.sleep(20);
            }
            try (QuietPeer second = new QuietPeer(server.getBoundPort(), "pcc-beta", 3000)) {
                awaitSession(server, "speaker:pcc-beta");
            }
        }
    }
}
