package net.dcn.pce.rib;

import net.dcn.pce.topology.PostgresCapacityStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Concurrent schema bootstrap must be race-free (found by the M2 fault harness). Two controllers
 * starting together — the ordinary HA case — each construct the Postgres stores, and each store
 * runs {@code CREATE TABLE IF NOT EXISTS} on first use. That statement is <em>not</em> concurrency
 * safe in PostgreSQL: two sessions can both pass the existence check and one then fails with a
 * duplicate-relation error ("Key (typname, ...)=(leadership, ...) already exists"), crashing that
 * controller on startup. Schema creation is now serialised under the shared advisory lock; this
 * starts many stores at once against a fresh database and asserts none throws. Gated on POSTGRES_URL.
 */
class ConcurrentSchemaInitTest {

    private static final String URL = System.getenv("POSTGRES_URL");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_USER", "postgres");
    private static final String PASSWORD =
            System.getenv().getOrDefault("POSTGRES_PASSWORD", "postgres");

    @BeforeEach
    void requirePostgresAndCleanSlate() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(), "POSTGRES_URL not set; skipping");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS link_reservation, node_reservation, intent, "
                    + "capacity_observation, leadership");
        }
    }

    @Test
    void manyStoresBootstrappingAtOnceAllSucceed() throws Exception {
        int racers = 12;
        CyclicBarrier start = new CyclicBarrier(racers);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < racers; i++) {
            // Mix all three schema-creating stores, since they share the fresh database and the
            // duplicate-relation race is across any of them creating the same tables at once.
            int which = i % 3;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    switch (which) {
                        case 0 -> new PostgresLeadership(URL, USER, PASSWORD, 30_000);
                        case 1 -> new PostgresReservationStore(URL, USER, PASSWORD, null);
                        default -> new PostgresCapacityStore(URL, USER, PASSWORD, null);
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(failures.isEmpty(),
                "concurrent schema bootstrap must not crash any controller, but got: " + failures);
    }
}
