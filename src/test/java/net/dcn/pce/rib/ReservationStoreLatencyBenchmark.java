package net.dcn.pce.rib;

import net.dcn.pce.install.InstallationIntent;
import net.dcn.pce.install.InstallationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Solve-latency characterisation for the shared store (ADR-0001 action 7).
 *
 * <p>A solve reads the in-memory LRIB/NRIB and, on commit, calls {@link ReservationStore#append}
 * once. The read cost is unchanged by the backend; the added cost of the Postgres backend is that
 * one append, which now crosses the network and commits with {@code synchronous_commit = on} under
 * the leadership advisory lock, where the file WAL only fsynced locally. This benchmark measures
 * exactly that append across a range of delta sizes on both backends, plus the full-state
 * {@link ReservationStore#restore} a failover pays once, so the ADR's "measure it" is a number, not
 * a guess.
 *
 * <p>Not a normal test: it is gated on {@code RUN_LATENCY_BENCH=1} as well as {@code POSTGRES_URL},
 * so the ordinary build neither runs it nor depends on a machine-specific latency threshold. It
 * asserts nothing about absolute milliseconds; it prints a table that {@code
 * docs/vortex_pce_shared_store_latency.md} records and interprets.
 */
class ReservationStoreLatencyBenchmark {

    private static final String URL = System.getenv("POSTGRES_URL");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_USER", "postgres");
    private static final String PASSWORD =
            System.getenv().getOrDefault("POSTGRES_PASSWORD", "postgres");
    private static final boolean ENABLED = "1".equals(System.getenv("RUN_LATENCY_BENCH"));

    private static final int WARMUP = 30;
    private static final int ITERATIONS = 200;
    private static final int[] DELTA_SIZES = {1, 10, 50, 100};

    @Test
    void measureAppendAndRestoreLatency(@TempDir Path dir) throws Exception {
        assumeTrue(ENABLED, "RUN_LATENCY_BENCH != 1; skipping the latency benchmark");
        assumeTrue(URL != null && !URL.isBlank(), "POSTGRES_URL not set; skipping");
        cleanSlate();

        System.out.println();
        System.out.println("## ReservationStore.append latency (ms) — " + ITERATIONS
                + " commits after " + WARMUP + " warmup, per delta size");
        System.out.printf("%-14s %10s %8s %8s %8s %8s%n",
                "backend", "reservations", "p50", "p95", "p99", "max");

        for (int size : DELTA_SIZES) {
            FileWalReservationStore wal =
                    new FileWalReservationStore(dir.resolve("wal-" + size + ".log").toString());
            reportAppend("file-wal", size, wal);

            cleanSlate();
            PostgresLeadership leadership = leader("bench");
            PostgresReservationStore pg =
                    new PostgresReservationStore(URL, USER, PASSWORD, leadership);
            reportAppend("postgres", size, pg);
        }

        // Restore cost the survivor pays once on failover, at a realistic committed-state size.
        System.out.println();
        System.out.println("## ReservationStore.restore latency (ms) — full state read");
        System.out.printf("%-14s %10s %8s%n", "backend", "rows", "ms");
        int restoreRows = 2000;
        cleanSlate();
        PostgresLeadership leadership = leader("bench");
        PostgresReservationStore pg = new PostgresReservationStore(URL, USER, PASSWORD, leadership);
        for (int i = 0; i < restoreRows; i++) {
            pg.append(delta(1, "restore-" + i));
        }
        long start = System.nanoTime();
        LRIB lrib = new LRIB();
        NRIB nrib = new NRIB();
        pg.restore(lrib, nrib, new net.dcn.pce.install.IntentLedger());
        double ms = (System.nanoTime() - start) / 1e6;
        System.out.printf("%-14s %10d %8.1f%n", "postgres", lrib.getAllReservations().size(), ms);
        System.out.println();
    }

    private void reportAppend(String backend, int size, ReservationStore store) {
        for (int i = 0; i < WARMUP; i++) {
            store.append(delta(size, backend + "-w" + i));
        }
        double[] samples = new double[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            store.append(delta(size, backend + "-" + size + "-" + i));
            samples[i] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(samples);
        System.out.printf("%-14s %10d %8.2f %8.2f %8.2f %8.2f%n", backend, size,
                pct(samples, 50), pct(samples, 95), pct(samples, 99), samples[samples.length - 1]);
    }

    private static double pct(double[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    /** A delta of {@code size} link + node reservations and one intent, with unique ids. */
    private static ReservationStore.ReservationDelta delta(int size, String tag) {
        LRIB lrib = new LRIB();
        NRIB nrib = new NRIB();
        List<InstallationIntent> intents = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String id = tag + "-" + i;
            lrib.restoreLinkReservation("L" + id, "T" + id, "link", "A", "B", 4_000_000, 0, 10);
            nrib.restoreNodeReservation("N" + id, "T" + id, "B", 2_000_000, 0, 10);
        }
        intents.add(InstallationIntent.restore("T" + tag, "lsp-" + tag,
                InstallationState.INSTALLING, 7L, 42L, "speaker:pcc-1", "tenant-a", null));
        return new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), nrib.getAllReservations(), Set.of(), intents);
    }

    private PostgresLeadership leader(String id) {
        PostgresLeadership l = new PostgresLeadership(URL, USER, PASSWORD, 300_000, id);
        l.tryBecomeLeader();
        return l;
    }

    private void cleanSlate() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS link_reservation, node_reservation, intent, leadership");
        }
    }
}
