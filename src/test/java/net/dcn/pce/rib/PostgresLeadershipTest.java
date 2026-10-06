package net.dcn.pce.rib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for Postgres-backed leadership (ADR-0001, action 4). Gated on POSTGRES_URL so
 * the ordinary build skips it. Proves the properties failover depends on: exactly one leader while
 * a lease is live, takeover only after expiry, a strictly higher term on takeover, and a superseded
 * leader that discovers the loss on its next renewal (the fencing guarantee).
 */
class PostgresLeadershipTest {

    private static final String URL = System.getenv("POSTGRES_URL");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_PASSWORD", "postgres");

    @BeforeEach
    void requirePostgresAndCleanSlate() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(), "POSTGRES_URL not set; skipping");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS leadership");
        }
    }

    private PostgresLeadership instance(String id, long ttlMillis) {
        return new PostgresLeadership(URL, USER, PASSWORD, ttlMillis, id);
    }

    @Test
    void oneInstanceBecomesLeaderAndAnotherCannotWhileTheLeaseIsLive() {
        PostgresLeadership a = instance("A", 10_000);
        PostgresLeadership b = instance("B", 10_000);

        assertTrue(a.tryBecomeLeader(), "first mover takes leadership");
        assertTrue(a.isLeader());
        assertTrue(a.term() > ReservationStore.NO_FENCING_TOKEN);

        assertFalse(b.tryBecomeLeader(), "a live lease excludes a second leader");
        assertFalse(b.isLeader());
    }

    @Test
    void aLapsedLeaseIsTakenOverAtAStrictlyHigherTerm() throws Exception {
        PostgresLeadership a = instance("A", 400);      // short TTL so it can lapse in-test
        PostgresLeadership b = instance("B", 10_000);

        assertTrue(a.tryBecomeLeader());
        long firstTerm = a.term();

        // A stops heartbeating (simulated: we just wait past its lease).
        Thread.sleep(600);

        assertTrue(b.tryBecomeLeader(), "an expired lease is available");
        assertTrue(b.term() > firstTerm, "the successor fences the predecessor with a higher term");

        // A tries to renew after being superseded and must discover it is no longer the leader.
        assertFalse(a.renew(), "a superseded leader loses its next renewal");
        assertFalse(a.isLeader());
    }

    @Test
    void aLeaderKeepsTheLeaseByRenewing() {
        PostgresLeadership a = instance("A", 5_000);
        assertTrue(a.tryBecomeLeader());
        long term = a.term();
        assertTrue(a.renew(), "the current leader renews");
        assertEquals(term, a.term(), "renewal keeps the same term");
        assertTrue(a.isLeader());
    }
}
