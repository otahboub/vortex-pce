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
 * Graceful lease release. Gated on POSTGRES_URL. A planned shutdown
 * should hand the lease back so a successor promotes at once rather than waiting out the TTL — and it
 * must do so conditionally, never disturbing a lease a successor has already taken over.
 */
class GracefulLeaseReleaseTest {

    private static final String URL = System.getenv("POSTGRES_URL");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_USER", "postgres");
    private static final String PASSWORD =
            System.getenv().getOrDefault("POSTGRES_PASSWORD", "postgres");

    @BeforeEach
    void requirePostgresAndCleanSlate() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(), "POSTGRES_URL not set; skipping");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS leadership");
        }
    }

    private PostgresLeadership leadership(String id, long ttl) {
        return new PostgresLeadership(URL, USER, PASSWORD, ttl, id);
    }

    @Test
    void aReleasedLeaseLetsASuccessorPromoteWithoutWaitingTheTtl() {
        // A long TTL: without a release, B could not take over for a full minute.
        PostgresLeadership a = leadership("A", 60_000);
        assertTrue(a.tryBecomeLeader());
        long aTerm = a.term();

        PostgresLeadership b = leadership("B", 60_000);
        assertFalse(b.tryBecomeLeader(), "B cannot take A's live lease");

        assertTrue(a.releaseIfHeld(), "A releases the lease it holds");
        assertFalse(a.isLeader(), "A stands down locally on release");

        assertTrue(b.tryBecomeLeader(), "B promotes immediately after the release, not after the TTL");
        assertTrue(b.term() > aTerm, "the successor's term is strictly higher");
    }

    @Test
    void releaseDoesNothingWhenAnotherInstanceAlreadyOwnsTheLease() {
        PostgresLeadership a = leadership("A", 500);
        assertTrue(a.tryBecomeLeader());

        // A's lease lapses and B takes over under a higher term.
        expireLease();
        PostgresLeadership b = leadership("B", 60_000);
        assertTrue(b.tryBecomeLeader());
        long bTerm = b.term();

        // A now belatedly runs its graceful release. It must not disturb B's live lease.
        assertFalse(a.releaseIfHeld(), "a stale instance releases nothing");
        assertTrue(b.renew(), "the current leader keeps its lease");
        assertEquals(bTerm, b.term(), "the successor's term is untouched");
    }

    private void expireLease() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("UPDATE leadership SET lease_expiry = now() - interval '1 minute' WHERE id = 1");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
