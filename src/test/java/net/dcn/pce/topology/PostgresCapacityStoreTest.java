package net.dcn.pce.topology;

import net.dcn.pce.rib.PostgresLeadership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Observed capacities in the shared database. Gated on POSTGRES_URL. Proves a promoted
 * replica can restore the corrected capacities another wrote, and that a stale term cannot.
 */
class PostgresCapacityStoreTest {

    private static final String URL = System.getenv("POSTGRES_URL");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_PASSWORD", "postgres");

    @BeforeEach
    void requirePostgresAndCleanSlate() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(), "POSTGRES_URL not set; skipping");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS capacity_observation, leadership");
        }
    }

    private PostgresLeadership leader(String id) {
        PostgresLeadership l = new PostgresLeadership(URL, USER, PASSWORD, 30_000);
        assertTrue(l.tryBecomeLeader());
        return l;
    }

    @Test
    void aRecordedObservationIsReadableByAnotherReplica() {
        PostgresCapacityStore writer = new PostgresCapacityStore(URL, USER, PASSWORD, leader("A"));
        writer.record("L1", 2_500_000, 10_000_000, 1234L);

        PostgresCapacityStore reader = new PostgresCapacityStore(URL, USER, PASSWORD,
                new PostgresLeadership(URL, USER, PASSWORD, 30_000));  // reader: never leads, only reads
        var all = reader.all();
        assertEquals(1, all.size());
        assertEquals(2_500_000, all.get("L1").observedBps, 1.0);
        assertEquals(10_000_000, all.get("L1").previousBps, 1.0);
    }

    @Test
    void aSupersededTermCannotWriteCapacity() {
        PostgresLeadership first = leader("first");
        PostgresCapacityStore firstStore = new PostgresCapacityStore(URL, USER, PASSWORD, first);
        firstStore.record("L1", 5_000_000, 10_000_000, 1L);

        // A newer leader takes the term.
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("UPDATE leadership SET term = 99, leader_id = 'second' WHERE id = 1");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThrows(IllegalStateException.class,
                () -> firstStore.record("L1", 1_000_000, 10_000_000, 2L),
                "a superseded term must not write capacity");
    }
}
