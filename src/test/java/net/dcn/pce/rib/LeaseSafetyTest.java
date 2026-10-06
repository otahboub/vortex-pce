package net.dcn.pce.rib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The lease-safety properties the 2026-08-26 re-evaluation named as the P0 gap keeping production
 * readiness below 9.0 (findings H1, H2, H3). Gated on {@code POSTGRES_URL}.
 *
 * <ul>
 *   <li><b>H1</b> — lease timing is evaluated by the database clock, not a JVM clock, so nodes with
 *       skewed application clocks cannot disagree about expiry. Verified through its observable
 *       consequences: the stored expiry is sourced from the database ({@code clock_timestamp()}), and
 *       vacancy is decided by that same clock.</li>
 *   <li><b>H2</b> — a leader that pauses past its lease fails closed locally, by a monotonic-clock
 *       deadline, before the next heartbeat notices and independent of any clock comparison.</li>
 *   <li><b>H3</b> — a holder cannot renew a lease that has already lapsed by the database clock; it
 *       must reacquire under a fresh, strictly higher term.</li>
 * </ul>
 */
class LeaseSafetyTest {

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

    // --- H3 -----------------------------------------------------------------------------------

    @Test
    void renewAfterExpiryIsRefusedAndReacquiresUnderAHigherTerm() {
        PostgresLeadership a = leadership("A", 30_000);
        assertTrue(a.tryBecomeLeader(), "A takes vacant leadership");
        long firstTerm = a.term();

        // The lease lapses by the database's own clock (a stall/partition past the TTL).
        expireLeaseByDbClock();

        assertFalse(a.renew(), "a holder must not renew a lease that has already expired");
        assertFalse(a.isLeader(), "and it must consider itself no longer the leader");

        assertTrue(a.tryBecomeLeader(), "on expiry it reacquires");
        assertTrue(a.term() > firstTerm,
                "reacquisition is under a strictly higher term (" + a.term() + " > " + firstTerm + ")");
    }

    // --- H1 -----------------------------------------------------------------------------------

    @Test
    void leaseExpiryIsSourcedFromTheDatabaseClock() {
        long ttl = 30_000;
        PostgresLeadership a = leadership("A", ttl);
        assertTrue(a.tryBecomeLeader());

        Instant dbNow = dbClock();
        Instant expiry = a.leaseExpiry();
        long aheadMs = Duration.between(dbNow, expiry).toMillis();
        // Expiry is clock_timestamp() + TTL as the database sees it, so measured against the database
        // clock the gap is ~TTL. A JVM-sourced expiry would only match this by accident of zero skew.
        assertTrue(Math.abs(aheadMs - ttl) < 3_000,
                "expiry should be database-now + TTL (~" + ttl + "ms ahead), was " + aheadMs + "ms");

        Instant firstExpiry = a.leaseExpiry();
        assertTrue(a.renew(), "renew extends the lease");
        assertTrue(a.leaseExpiry().isAfter(firstExpiry), "and moves the database expiry forward");
    }

    @Test
    void vacancyIsDecidedByTheDatabaseClock() {
        PostgresLeadership a = leadership("A", 30_000);
        assertTrue(a.tryBecomeLeader());
        PostgresLeadership b = leadership("B", 30_000);
        assertFalse(b.tryBecomeLeader(), "B cannot take a lease the database clock still sees as live");

        expireLeaseByDbClock();
        assertTrue(b.tryBecomeLeader(),
                "once expired by the database clock, B takes over at a higher term");
        assertTrue(b.term() > a.term());
    }

    // --- H2 -----------------------------------------------------------------------------------

    @Test
    void aPausedLeaderFailsClosedLocallyBeforeItsHeartbeatNotices() throws Exception {
        // The local monotonic deadline is TTL - TTL/4 = 0.75*TTL, deliberately shorter than the DB
        // lease. A sleep past that deadline but before DB expiry stands in for a stop-the-world pause:
        // the instance must fail closed locally even while the database lease is technically still
        // live, with no renew and no heartbeat having run. TTL=2000 leaves ~300ms slack on each side.
        long ttl = 2_000;                  // local deadline ~1500ms, DB expiry ~2000ms
        PostgresLeadership a = leadership("A", ttl);
        assertTrue(a.tryBecomeLeader());
        assertTrue(a.leaseLocallyLive(), "immediately after acquiring, the lease is locally live");

        Thread.sleep(1_700);               // past the local deadline (~1500ms), before DB expiry (~2000ms)

        assertFalse(a.leaseLocallyLive(),
                "past the conservative local deadline the instance fails closed -- stricter than the "
                        + "database lease, and without any renew or heartbeat");
        assertTrue(a.isLeader(),
                "the guard is stricter than isLeader(): the object still nominally holds the term, "
                        + "but admission/dispatch gate on leaseLocallyLive(), not isLeader()");

        // The database lease has not lapsed or been taken, so a live instance renews and re-arms.
        assertTrue(a.renew(), "the DB lease is still live, so a healthy instance can renew");
        assertTrue(a.leaseLocallyLive(), "renewal re-arms the local liveness window");
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Push the single lease's expiry into the past by the database's own clock. */
    private void expireLeaseByDbClock() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("UPDATE leadership SET lease_expiry = clock_timestamp() - interval '1 second' "
                    + "WHERE id = 1");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Instant dbClock() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT clock_timestamp()")) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
