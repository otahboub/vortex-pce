package net.dcn.pce.pcep;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the two conformance fixes an independent probe found against the shipped server: it
 * answered malformed and out-of-version input with silence and a bare TCP close, and it ignored
 * the DeadTimer a peer advertised in favour of its own.
 *
 * <p>These vectors are the impolite half of an external cross-PCE conformance probe, brought
 * in-tree so a regression fails the build rather than waiting for the next cross-PCE run. The
 * expectations are RFC 5440 section 7.15 Error-Type/Error-value pairs, quoted at each assertion.
 */
class PcepConformanceResponsesTest {

    private static final int MSG_KEEPALIVE = 2;
    private static final int MSG_PCERR = 6;

    /** A raw client that can send exactly the bytes a well-behaved fixture never would. */
    private static final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream in;

        RawClient(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(8000);
            in = new DataInputStream(socket.getInputStream());
        }

        void send(byte[] bytes) throws IOException {
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        }

        /** Drain frames until a PCErr appears (returning its type/value), the peer closes, or timeout. */
        int[] awaitPcerrOrClose() throws IOException {
            for (int guard = 0; guard < 16; guard++) {
                byte[] header = new byte[4];
                try {
                    in.readFully(header);
                } catch (EOFException closed) {
                    return null;                       // TCP close with no PCErr
                } catch (SocketTimeoutException quiet) {
                    return new int[] {-1, -1};         // silence
                }
                int type = header[1] & 0xFF;
                int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                byte[] body = new byte[Math.max(0, length - 4)];
                in.readFully(body);
                if (type == MSG_PCERR) {
                    // PCEP-ERROR object body starts after the 4-byte object header:
                    // Reserved, Flags, Error-Type, Error-value.
                    return new int[] {body[4 + 2] & 0xFF, body[4 + 3] & 0xFF};
                }
            }
            return new int[] {-2, -2};
        }

        void openExchange() throws IOException {
            expect(1);                                 // server OPEN
            send(PcepEncoder.open(30, 120, 1, "pcc-conf"));
            expect(MSG_KEEPALIVE);                      // server KEEPALIVE
            send(PcepEncoder.keepalive());
        }

        private void expect(int messageType) throws IOException {
            byte[] header = new byte[4];
            in.readFully(header);
            assertEquals(messageType, header[1] & 0xFF, "unexpected PCEP message type");
            int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            in.readFully(new byte[Math.max(0, length - 4)]);
        }

        /** True if the peer closes the session socket within the window; false if it stays open. */
        boolean closedWithin(int millis) throws IOException {
            socket.setSoTimeout(millis);
            try {
                return in.read() < 0;          // EOF -> closed
            } catch (SocketTimeoutException stillOpen) {
                return false;                  // quiet but alive
            }
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }

    private static byte[] commonHeader(int version, int messageType, int totalLength) {
        return new byte[] {(byte) ((version & 0x07) << 5), (byte) messageType,
                (byte) ((totalLength >> 8) & 0xFF), (byte) (totalLength & 0xFF)};
    }

    @Test
    void aNonOpenFirstMessageDrawsSessionFailureOneOne() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(0, "vortex-pce", (k, r) -> { })) {
            server.start("127.0.0.1");
            try (RawClient client = new RawClient(server.getBoundPort())) {
                client.expect(1);                       // server opens
                client.send(commonHeader(1, MSG_KEEPALIVE, 4));   // ... we answer with a KEEPALIVE
                int[] error = client.awaitPcerrOrClose();
                // RFC 5440 s7.15: "reception of an invalid Open message or a non Open message" ->
                // Error-Type 1, Error-value 1.
                assertEquals(1, error[0], "Error-Type");
                assertEquals(1, error[1], "Error-value");
            }
        }
    }

    @Test
    void anUnsupportedVersionDrawsSessionFailureOneEight() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(0, "vortex-pce", (k, r) -> { })) {
            server.start("127.0.0.1");
            try (RawClient client = new RawClient(server.getBoundPort())) {
                client.expect(1);
                // A syntactically fine OPEN whose common-header version is 2.
                byte[] open = PcepEncoder.open(30, 120, 1, "pcc-conf");
                open[0] = (byte) ((2 & 0x07) << 5);
                client.send(open);
                int[] error = client.awaitPcerrOrClose();
                // IANA PCEP-ERROR registry: Error-Type 1, Error-value 8, "PCEP version not supported".
                assertEquals(1, error[0], "Error-Type");
                assertEquals(8, error[1], "Error-value");
            }
        }
    }

    @Test
    void aMalformedFrameOnAnEstablishedSessionDrawsOneOne() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(0, "vortex-pce", (k, r) -> { })) {
            server.start("127.0.0.1");
            try (RawClient client = new RawClient(server.getBoundPort())) {
                client.openExchange();
                client.send(commonHeader(1, MSG_KEEPALIVE, 0));   // length 0 is below the header
                int[] error = client.awaitPcerrOrClose();
                assertEquals(1, error[0], "Error-Type");
                assertEquals(1, error[1], "Error-value");
            }
        }
    }

    @Test
    void aFrameThatAnnouncesALengthThenStallsDrawsOneOne() throws Exception {
        // A short keepalive gives a short read slice, so the stall is detected in about a second
        // rather than the ten a default 30s keepalive would take. The detection is the same; only
        // the granularity at which the server wakes to notice it differs.
        try (PcepSessionServer server =
                     new PcepSessionServer(0, "vortex-pce", (k, r) -> { }, 2, 8, 8)) {
            server.start("127.0.0.1");
            try (RawClient client = new RawClient(server.getBoundPort())) {
                client.openExchange();
                // Header announces a 64-byte frame; only four body bytes follow, then silence.
                client.send(commonHeader(1, MSG_KEEPALIVE, 64));
                client.send(new byte[] {0, 0, 0, 0});
                int[] error = client.awaitPcerrOrClose();
                // A stalled frame is a malformed message: RFC 5440 s7.15 Error-Type 1, value 1.
                assertEquals(1, error[0], "Error-Type");
                assertEquals(1, error[1], "Error-value");
            }
        }
    }

    @Test
    void aSecondOpenOnAnEstablishedSessionDrawsNineOneAndKeepsTheSession() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(0, "vortex-pce", (k, r) -> { })) {
            server.start("127.0.0.1");
            try (RawClient client = new RawClient(server.getBoundPort())) {
                client.openExchange();
                client.send(PcepEncoder.open(30, 120, 1, "pcc-conf"));   // a second OPEN
                int[] error = client.awaitPcerrOrClose();
                // RFC 5440 s7.15: attempt to establish a second session -> Error-Type 9, value 1,
                // and the existing session MUST be preserved.
                assertEquals(9, error[0], "Error-Type");
                assertEquals(1, error[1], "Error-value");
                // The same session socket must still be usable: a keepalive goes out and the
                // server does not close it. A torn-down session would EOF here instead.
                client.send(keepaliveMessage());
                assertFalse(client.closedWithin(2000), "the existing session must be preserved");
            }
        }
    }

    private static byte[] keepaliveMessage() {
        return commonHeader(1, MSG_KEEPALIVE, 4);
    }

    @Test
    void anUnrecognisedMandatoryObjectDrawsThreeOne() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(0, "vortex-pce", (k, r) -> { })) {
            server.start("127.0.0.1");
            try (RawClient client = new RawClient(server.getBoundPort())) {
                client.openExchange();
                // A message carrying object class 200 (unassigned) with the P flag set.
                client.send(new byte[] {
                        0x20, MSG_KEEPALIVE, 0x00, 0x0C,           // v1, len 12
                        (byte) 200, 0x12, 0x00, 0x08,              // class 200, P set, len 8
                        0x00, 0x00, 0x00, 0x00});                  // object body
                int[] error = client.awaitPcerrOrClose();
                // RFC 5440 s7.15: unrecognised object class, P flag set -> Error-Type 3, value 1.
                assertEquals(3, error[0], "Error-Type");
                assertEquals(1, error[1], "Error-value");
            }
        }
    }

    @Test
    void thePeersAdvertisedDeadTimerIsHonoured() throws Exception {
        // The server's own dead timer is a long 120s; the peer advertises a short 3s and then goes
        // silent. RFC 5440 s7.3: the peer's value is the one the server must apply to detect it.
        try (PcepSessionServer server =
                     new PcepSessionServer(0, "vortex-pce", (k, r) -> { }, 1, 3, 8)) {
            server.start("127.0.0.1");
            try (RawClient client = new RawClient(server.getBoundPort())) {
                client.expect(1);
                client.send(PcepEncoder.open(1, 3, 1, "pcc-conf"));   // keepalive 1, deadtimer 3
                client.expect(MSG_KEEPALIVE);
                client.send(PcepEncoder.keepalive());
                // Now go silent and time how long until the server tears the session down.
                client.socket.setSoTimeout(20000);
                long start = System.nanoTime();
                Integer closedAfterMs = null;
                try {
                    while (System.nanoTime() - start < 15_000_000_000L) {
                        byte[] header = new byte[4];
                        client.in.readFully(header);      // consume the server's keepalives
                    }
                } catch (EOFException closed) {
                    closedAfterMs = (int) ((System.nanoTime() - start) / 1_000_000L);
                } catch (SocketTimeoutException ignored) {
                    // fell through without a close
                }
                assertTrue(closedAfterMs != null && closedAfterMs < 10_000,
                        "server should close near the peer's 3s dead timer, not its own 120s; "
                                + "closedAfterMs=" + closedAfterMs);
            }
        }
    }
}
