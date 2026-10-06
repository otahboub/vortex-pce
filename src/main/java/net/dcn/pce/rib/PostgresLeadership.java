package net.dcn.pce.rib;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * PostgreSQL-backed leader election with a time-bounded lease and a monotonic term (ADR-0001,
 * action 4). This is what makes "only one controller is active" a fact the database enforces rather
 * than an assumption, and it is the source of the fencing token the reservation store stamps on
 * every write.
 *
 * <p>The mechanism is deliberately small. A single {@code leadership} row holds the current
 * {@code term}, the {@code leader_id} that owns it, and a {@code lease_expiry}. Under a
 * transaction-scoped advisory lock — so two instances cannot race — an instance takes leadership
 * only when no lease is live: no row, or {@code lease_expiry} is in the past. Taking it bumps the
 * term (which is why a new leader always fences its predecessor) and stamps this instance's id and
 * a fresh expiry. A leader keeps the lease by {@link #renew()}, which extends the expiry only while
 * this instance still owns the current term; the moment the term has moved on, renew reports the
 * loss and the caller must stop acting.
 *
 * <p>Correctness over availability: a partitioned leader whose lease lapses stops being the leader
 * after {@code leaseTtlMillis}, and a successor cannot appear before then, so the two windows do
 * not overlap. The lease TTL must exceed the heartbeat interval by enough to survive a missed beat;
 * the caller owns that scheduling.
 *
 * <h2>The database is the only wall clock</h2>
 *
 * <p>Every lease comparison and extension is evaluated with PostgreSQL's {@code clock_timestamp()},
 * never a JVM {@link Instant#now()}. Two nodes with skewed application clocks would otherwise
 * disagree about when a lease expires — the exact failure a distributed lease exists to prevent — so
 * the single authoritative clock is the one shared row's database. {@link #renew()} additionally
 * refuses once the lease has already lapsed by that clock: an instance paused past its expiry must
 * reacquire under a new term, not silently extend the old one.
 *
 * <h2>A paused leader fails closed</h2>
 *
 * <p>Owning the term is necessary but not sufficient to <em>act</em>. A stop-the-world pause longer
 * than the TTL can let a successor take over while this JVM is frozen; on resume it would still
 * believe it leads until its next heartbeat. So each successful acquire/renew also arms a
 * conservative <em>local monotonic</em> deadline ({@link #leaseLocallyLive()}): the lease is known
 * live only until {@code nanoTime} captured just before the request, plus the TTL, less a safety
 * margin. This bound needs no clock comparison — it holds under arbitrary skew — and a pause makes
 * {@code System.nanoTime()} jump past it, so admission and dispatch that gate on it stop the instant
 * the local guarantee lapses, well before the database would hand the lease to anyone else.
 */
public final class PostgresLeadership {

    private static final Logger log = Logger.getLogger(PostgresLeadership.class.getName());
    private static final long ADVISORY_LOCK_KEY = 4711L;

    private final String url;
    private final Properties connectionProperties;
    private final String instanceId;
    private final long leaseTtlMillis;
    /**
     * How far before the nominal local deadline this instance stops trusting the lease, absorbing
     * scheduling jitter and the gap between capturing {@code nanoTime} and the database evaluating
     * {@code clock_timestamp()}. A quarter of the TTL is comfortably larger than either in practice
     * while still leaving {@code TTL - margin} well above any sane heartbeat interval.
     */
    private final long safetyMarginMillis;

    private volatile long term = ReservationStore.NO_FENCING_TOKEN;
    private volatile boolean leader;
    /**
     * Monotonic-clock deadline past which the lease is no longer known to be locally live. Read on
     * every admission/dispatch via {@link #leaseLocallyLive()}; set only on a successful lease write.
     */
    private volatile long leaseDeadlineNanos = Long.MIN_VALUE;
    /** The database-authoritative expiry of the current lease, for diagnostics and cluster status. */
    private volatile Instant leaseExpiry;

    public PostgresLeadership(String url, String user, String password, long leaseTtlMillis) {
        this(url, user, password, leaseTtlMillis, UUID.randomUUID().toString());
    }

    PostgresLeadership(String url, String user, String password, long leaseTtlMillis,
                       String instanceId) {
        if (leaseTtlMillis <= 0) {
            throw new IllegalArgumentException("lease TTL must be positive");
        }
        this.url = url;
        this.instanceId = instanceId;
        this.leaseTtlMillis = leaseTtlMillis;
        this.safetyMarginMillis = Math.max(1, leaseTtlMillis / 4);
        this.connectionProperties = new Properties();
        if (user != null) {
            connectionProperties.setProperty("user", user);
        }
        if (password != null) {
            connectionProperties.setProperty("password", password);
        }
        initialiseSchema();
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url, connectionProperties);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET synchronous_commit = on");
        }
        return connection;
    }

    private void initialiseSchema() {
        try (Connection connection = open()) {
            // CREATE TABLE IF NOT EXISTS is not race-safe: two controllers starting together can both
            // pass the existence check and one then fails with a duplicate-relation error. Serialise
            // schema creation under the same advisory lock the rest of the class already uses.
            try (Statement lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS leadership ("
                        + "id int PRIMARY KEY, term bigint NOT NULL, "
                        + "leader_id text, lease_expiry timestamptz)");
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not initialise the leadership table", e);
        }
    }

    /** This instance's current term, or {@link ReservationStore#NO_FENCING_TOKEN} if not leader. */
    public long term() {
        return term;
    }

    public boolean isLeader() {
        return leader;
    }

    /**
     * Whether the lease is still known to be live by the conservative local monotonic deadline —
     * the guard every admission and PCEP dispatch must pass. Returns {@code false} the moment this
     * instance can no longer prove the database lease is its own, including after a stop-the-world
     * pause that outran the TTL, even before the next heartbeat observes the loss.
     */
    public boolean leaseLocallyLive() {
        return leader && System.nanoTime() <= leaseDeadlineNanos;
    }

    /** The database-authoritative expiry of the current lease, or {@code null} if never acquired. */
    public Instant leaseExpiry() {
        return leaseExpiry;
    }

    public String instanceId() {
        return instanceId;
    }

    /**
     * Takes leadership if no live lease exists, or does nothing if another instance holds one.
     *
     * @return {@code true} if this instance is the leader after the call
     */
    public boolean tryBecomeLeader() {
        // Capture the monotonic clock before the request: the database evaluates clock_timestamp()
        // no earlier than this, so this instant plus the TTL is a safe lower bound on the real expiry.
        long sentNanos = System.nanoTime();
        try (Connection connection = open()) {
            try (Statement lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
            }
            LeaseRow row = readLease(connection);
            // Vacancy is decided by the database clock, never a local one.
            boolean vacant = row == null || row.expired;
            if (!vacant && !instanceId.equals(row.leaderId)) {
                connection.commit();
                this.leader = false;
                return false;                       // someone else holds a live lease
            }
            long nextTerm = (row == null ? 0L : row.term) + (vacant ? 1L : 0L);
            Instant expiry = writeLease(connection, nextTerm);
            connection.commit();
            adoptLease(nextTerm, expiry, sentNanos);
            log.info("Instance " + instanceId + " holds leadership at term " + nextTerm);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("leadership acquisition failed", e);
        }
    }

    /**
     * Extends the lease while this instance still owns its term <em>and the lease has not already
     * lapsed by the database clock</em>. An instance paused past its expiry cannot renew: it reports
     * the loss, and the caller must demote and reacquire under a fresh, higher term.
     *
     * @return {@code true} if the lease was extended; {@code false} if leadership was lost, in which
     *         case the caller must stop accepting sessions and dispatching
     */
    public boolean renew() {
        long sentNanos = System.nanoTime();
        try (Connection connection = open()) {
            try (Statement lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
            }
            LeaseRow row = readLease(connection);
            if (row == null || row.term != term || !instanceId.equals(row.leaderId) || row.expired) {
                connection.commit();
                if (leader) {
                    log.warning("Instance " + instanceId + " lost leadership; "
                            + (row != null && row.expired && row.term == term
                                    ? "its lease had already expired"
                                    : "term is now " + (row == null ? "none" : row.term)));
                }
                this.leader = false;
                this.leaseDeadlineNanos = Long.MIN_VALUE;
                return false;
            }
            Instant expiry = writeLease(connection, term);
            connection.commit();
            adoptLease(term, expiry, sentNanos);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("leadership renewal failed", e);
        }
    }

    /**
     * Releases the lease on a <em>graceful</em> shutdown so a successor can take over at once instead
     * of waiting out the TTL. It expires the lease only if this instance still owns it and it is still
     * live — never disturbing a lease a successor may already hold — and expires it in place rather
     * than deleting the row, so the term history survives and the successor's next term is still
     * strictly higher. A crash skips this path entirely; the TTL then provides the same safety more
     * slowly. Failure to release is not fatal: the TTL still bounds takeover.
     *
     * @return {@code true} if a lease this instance owned was released
     */
    public boolean releaseIfHeld() {
        try (Connection connection = open()) {
            try (Statement lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
            }
            int released;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE leadership SET lease_expiry = clock_timestamp() - make_interval(secs => 1) "
                            + "WHERE id = 1 AND term = ? AND leader_id = ? "
                            + "AND lease_expiry >= clock_timestamp()")) {
                ps.setLong(1, term);
                ps.setString(2, instanceId);
                released = ps.executeUpdate();
            }
            connection.commit();
            this.leader = false;
            this.leaseDeadlineNanos = Long.MIN_VALUE;
            if (released > 0) {
                log.info("Instance " + instanceId + " released leadership (term " + term + ")");
            }
            return released > 0;
        } catch (SQLException e) {
            // A failed release is not fatal: the lease still lapses on its own at the TTL.
            log.warning("graceful lease release failed (" + e.getMessage()
                    + "); takeover falls back to the TTL");
            return false;
        }
    }

    /** Records a freshly written lease: its term, its database expiry, and the local monotonic bound. */
    private void adoptLease(long newTerm, Instant expiry, long sentNanos) {
        this.term = newTerm;
        this.leaseExpiry = expiry;
        // Conservative local deadline: valid until (pre-request monotonic instant + TTL - margin).
        // Independent of any clock comparison, so it holds under arbitrary skew and trips on a pause.
        this.leaseDeadlineNanos =
                sentNanos + (leaseTtlMillis - safetyMarginMillis) * 1_000_000L;
        this.leader = true;
    }

    private LeaseRow readLease(Connection connection) throws SQLException {
        // The expiry test runs in the database so it uses the database clock, not the JVM's.
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT term, leader_id, "
                        + "(lease_expiry IS NULL OR lease_expiry < clock_timestamp()) AS expired "
                        + "FROM leadership WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return new LeaseRow(rs.getLong(1), rs.getString(2), rs.getBoolean(3));
        }
    }

    /**
     * Writes the lease with an expiry of {@code clock_timestamp() + TTL} — the database's own clock —
     * and returns that authoritative expiry.
     */
    private Instant writeLease(Connection connection, long newTerm) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO leadership (id, term, leader_id, lease_expiry) VALUES "
                        + "(1, ?, ?, clock_timestamp() + make_interval(secs => ? / 1000.0)) "
                        + "ON CONFLICT (id) DO UPDATE SET term = EXCLUDED.term, "
                        + "leader_id = EXCLUDED.leader_id, lease_expiry = EXCLUDED.lease_expiry "
                        + "RETURNING lease_expiry")) {
            ps.setLong(1, newTerm);
            ps.setString(2, instanceId);
            ps.setDouble(3, (double) leaseTtlMillis);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    private record LeaseRow(long term, String leaderId, boolean expired) {
    }
}
