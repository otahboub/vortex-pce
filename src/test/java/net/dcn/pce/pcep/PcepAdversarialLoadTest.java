package net.dcn.pce.pcep;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deterministic mixed valid/hostile black-box load at the real TCP listener.
 *
 * <p>This is intentionally not a coverage-guided fuzzer. It repeatedly exercises the bounded
 * connection and parser error paths that an unauthenticated management port exposes while valid
 * PCCs continue to negotiate sessions. The decisive assertion is recovery: after every hostile
 * stream stops, a fresh conformant PCC must establish normally rather than finding leaked permits
 * or a dead acceptor.
 */
class PcepAdversarialLoadTest {

    private static final int HOSTILE_WORKERS = 4;
    private static final int STORM_SECONDS = 10;
    private static final int REQUIRED_HOSTILE_OUTCOMES = 1_000;
    private static final int REQUIRED_VALID_SUCCESSES = 100;

    @Test
    @Timeout(30)
    void hostileConnectionChurnCannotStarveOrWedgeConformantPeers() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(
                0, "vortex-pce", (session, report) -> { }, 1, 10, 8)) {
            server.start("127.0.0.1");
            int port = server.getBoundPort();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger hostileCompleted = new AtomicInteger();
            AtomicInteger validCompleted = new AtomicInteger();
            ExecutorService workers = Executors.newFixedThreadPool(HOSTILE_WORKERS + 1);
            long stopAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(STORM_SECONDS);
            try {
                for (int worker = 0; worker < HOSTILE_WORKERS; worker++) {
                    int stream = worker;
                    workers.submit(() -> {
                        await(start);
                        int attempt = 0;
                        while (System.nanoTime() < stopAt) {
                            try {
                                sendHostileVector(port, (stream + attempt) % 4);
                            } catch (IOException ignored) {
                                // Rejection at the session cap is a safe overload response.
                            } finally {
                                hostileCompleted.incrementAndGet();
                            }
                            attempt++;
                            pauseBetweenConnections();
                        }
                    });
                }
                workers.submit(() -> {
                    await(start);
                    int attempt = 0;
                    while (System.nanoTime() < stopAt) {
                        try {
                            validHandshake(port, "control-" + attempt);
                            validCompleted.incrementAndGet();
                        } catch (IOException ignored) {
                            // A control may race the bounded cap, but the aggregate threshold and
                            // post-storm probe prevent overload from becoming indefinite starvation.
                        }
                        attempt++;
                        pauseBetweenConnections();
                    }
                });

                start.countDown();
                workers.shutdown();
                assertTrue(workers.awaitTermination(25, TimeUnit.SECONDS),
                        "the bounded adversarial workload must terminate");
            } finally {
                workers.shutdownNow();
            }

            assertTrue(hostileCompleted.get() >= REQUIRED_HOSTILE_OUTCOMES,
                    "hostile streams made insufficient bounded progress: " + hostileCompleted.get());
            assertTrue(validCompleted.get() >= REQUIRED_VALID_SUCCESSES,
                    "valid peers made insufficient progress during hostile churn: "
                            + validCompleted.get());
            System.out.printf("A6B hostile_outcomes=%d valid_successes=%d duration_seconds=%d%n",
                    hostileCompleted.get(), validCompleted.get(), STORM_SECONDS);

            awaitNoSessions(server);
            validHandshake(port, "post-storm");
            awaitNoSessions(server);
        }
    }

    private static void sendHostileVector(int port, int vector) throws IOException {
        try (Socket peer = connect(port)) {
            readFrame(peer); // The server always sends its OPEN first.
            switch (vector) {
                case 0 -> peer.getOutputStream().write(header(1, 2, 0));
                case 1 -> peer.getOutputStream().write(header(1, 2, 4)); // non-OPEN first
                case 2 -> {
                    byte[] wrongVersion = PcepEncoder.open(1, 10, 1, "hostile");
                    wrongVersion[0] = 0x40; // PCEP version 2
                    peer.getOutputStream().write(wrongVersion);
                }
                case 3 -> {
                    peer.getOutputStream().write(header(1, 2, 64));
                    peer.getOutputStream().write(new byte[] {0, 0, 0, 0});
                    peer.shutdownOutput(); // announced body is truncated
                }
                default -> throw new AssertionError("unknown vector");
            }
            peer.getOutputStream().flush();
            drainUntilClose(peer);
        }
    }

    private static void validHandshake(int port, String speaker) throws IOException {
        try (Socket peer = connect(port)) {
            assertEquals(1, readFrame(peer), "server OPEN");
            peer.getOutputStream().write(PcepEncoder.open(1, 10, 1, speaker));
            peer.getOutputStream().flush();
            assertEquals(2, readFrame(peer), "server KEEPALIVE");
            peer.getOutputStream().write(PcepEncoder.keepalive());
            peer.getOutputStream().flush();
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket peer = new Socket("127.0.0.1", port);
        peer.setSoTimeout(2000);
        return peer;
    }

    private static int readFrame(Socket peer) throws IOException {
        DataInputStream in = new DataInputStream(peer.getInputStream());
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (length < 4) {
            throw new IOException("invalid peer frame length " + length);
        }
        in.readFully(new byte[length - 4]);
        return header[1] & 0xFF;
    }

    private static byte[] header(int version, int type, int length) {
        return new byte[] {(byte) (version << 5), (byte) type,
                (byte) (length >>> 8), (byte) length};
    }

    private static void drainUntilClose(Socket peer) throws IOException {
        try {
            while (peer.getInputStream().read() >= 0) {
                // Consume any best-effort PCErr before close.
            }
        } catch (SocketTimeoutException ignored) {
            // A preserved session is also bounded by this client's close at method exit.
        } catch (EOFException ignored) {
            // Expected terminal response.
        }
    }

    private static void awaitNoSessions(PcepSessionServer server) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !server.establishedSessions().isEmpty()) {
            Thread.sleep(20);
        }
        assertTrue(server.establishedSessions().isEmpty(),
                "session registry must drain after clients close: " + server.establishedSessions());
    }

    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void pauseBetweenConnections() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
