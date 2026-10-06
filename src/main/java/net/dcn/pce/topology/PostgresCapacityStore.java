package net.dcn.pce.topology;

import net.dcn.pce.rib.PostgresLeadership;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * A {@link CapacityStore} backed by the shared Postgres database (ADR-0001).
 *
 * <p>Observations live in {@code capacity_observation} in the same database as the reservations and
 * are fenced by the same leadership term, so a promoted standby restores the corrected capacities
 * with everything else and two replicas can never plan against divergent topologies. Writes take the
 * same advisory lock as the reservation store and refuse if the leadership term has moved on, and the
 * upsert carries a {@code WHERE term <= EXCLUDED.term} guard so a stale write cannot overwrite a
 * newer observation.
 */
public final class PostgresCapacityStore implements CapacityStore {

    private static final long ADVISORY_LOCK_KEY = 4711L;

    private final String url;
    private final Properties connectionProperties;
    private final PostgresLeadership leadership;

    public PostgresCapacityStore(String url, String user, String password,
                                 PostgresLeadership leadership) {
        this.url = url;
        this.leadership = leadership;
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
            // schema creation under the same advisory lock the writes already use.
            try (Statement lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS capacity_observation ("
                        + "link_id text PRIMARY KEY, observed_bps double precision, "
                        + "previous_bps double precision, recorded_at bigint, term bigint)");
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not initialise the capacity observation table", e);
        }
    }

    @Override
    public void record(String linkId, double observedBps, double previousBps,
                       long recordedAtEpochMillis) {
        long term = leadership.term();
        try (Connection connection = open()) {
            try (Statement lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
            }
            requireCurrentLeader(connection, term);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO capacity_observation (link_id, observed_bps, previous_bps, "
                            + "recorded_at, term) VALUES (?,?,?,?,?) ON CONFLICT (link_id) DO UPDATE "
                            + "SET observed_bps=EXCLUDED.observed_bps, "
                            + "previous_bps=EXCLUDED.previous_bps, recorded_at=EXCLUDED.recorded_at, "
                            + "term=EXCLUDED.term WHERE capacity_observation.term <= EXCLUDED.term")) {
                ps.setString(1, linkId);
                ps.setDouble(2, observedBps);
                ps.setDouble(3, previousBps);
                ps.setLong(4, recordedAtEpochMillis);
                ps.setLong(5, term);
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not record observed capacity", e);
        }
    }

    @Override
    public void forget(String linkId) {
        long term = leadership.term();
        try (Connection connection = open()) {
            try (Statement lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
            }
            requireCurrentLeader(connection, term);
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM capacity_observation WHERE link_id = ?")) {
                ps.setString(1, linkId);
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not forget observed capacity", e);
        }
    }

    @Override
    public Map<String, ObservedCapacityStore.Observation> all() {
        Map<String, ObservedCapacityStore.Observation> out = new LinkedHashMap<>();
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT link_id, observed_bps, previous_bps, recorded_at "
                             + "FROM capacity_observation ORDER BY link_id")) {
            while (rs.next()) {
                out.put(rs.getString(1), new ObservedCapacityStore.Observation(
                        rs.getDouble(2), rs.getDouble(3), rs.getLong(4)));
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not read observed capacities", e);
        }
        return out;
    }

    private void requireCurrentLeader(Connection connection, long term) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT term, leader_id FROM leadership WHERE id = 1")) {
            boolean ok = rs.next() && rs.getLong(1) == term
                    && leadership.instanceId().equals(rs.getString(2));
            if (!ok) {
                connection.rollback();
                throw new IllegalStateException("leadership term " + term
                        + " is no longer current; refusing capacity write");
            }
        }
    }
}
