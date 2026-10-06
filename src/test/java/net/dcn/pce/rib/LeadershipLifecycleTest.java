package net.dcn.pce.rib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The failover lifecycle (ADR-0001 action 5). Gated on POSTGRES_URL. Proves that exactly one
 * instance promotes, and that when a leader stops renewing, a standby takes over and the old leader
 * demotes -- the automatic-failover property the review requires for a real HA claim.
 */
class LeadershipLifecycleTest {

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

    private PostgresLeadership leadership(String id, long ttl) {
        return new PostgresLeadership(URL, USER, PASSWORD, ttl, id);
    }

    @Test
    void exactlyOneOfTwoStandbysPromotes() throws Exception {
        AtomicInteger aPromotes = new AtomicInteger();
        AtomicInteger bPromotes = new AtomicInteger();
        try (LeadershipLifecycle a = new LeadershipLifecycle(
                leadership("A", 10_000), 1_000, aPromotes::incrementAndGet, () -> { });
             LeadershipLifecycle b = new LeadershipLifecycle(
                     leadership("B", 10_000), 1_000, bPromotes::incrementAndGet, () -> { })) {
            a.start();
            b.start();
            Thread.sleep(500);
            assertTrue(a.isLeader() ^ b.isLeader(), "exactly one leader");
            assertEquals(1, aPromotes.get() + bPromotes.get(), "exactly one promotion");
        }
    }

    @Test
    void aStandbyTakesOverWhenTheLeaderStopsRenewing() throws Exception {
        AtomicInteger aDemotes = new AtomicInteger();
        AtomicInteger bPromotes = new AtomicInteger();
        // A leads on a short lease and then stops heartbeating (we close its scheduler but hold the
        // object). B, polling, should acquire the lapsed lease and promote; A should have demoted.
        LeadershipLifecycle a = new LeadershipLifecycle(
                leadership("A", 800), 300, () -> { }, aDemotes::incrementAndGet);
        a.start();
        Thread.sleep(300);
        assertTrue(a.isLeader(), "A leads first");

        try (LeadershipLifecycle b = new LeadershipLifecycle(
                leadership("B", 5_000), 300, bPromotes::incrementAndGet, () -> { })) {
            b.start();
            assertFalse(b.isLeader(), "B cannot take a live lease");

            a.close();                       // A stands down (stops renewing, demotes)
            assertTrue(aDemotes.get() >= 1, "A demoted on close");

            // Within a couple of B's poll intervals past A's lapsed lease, B promotes.
            for (int i = 0; i < 40 && !b.isLeader(); i++) {
                Thread.sleep(100);
            }
            assertTrue(b.isLeader(), "B took over the lapsed lease");
            assertEquals(1, bPromotes.get(), "B promoted exactly once");
        }
    }
}
