package net.dcn.pce.rib;

import net.dcn.pce.install.InstallationIntent;
import net.dcn.pce.install.InstallationState;
import net.dcn.pce.install.IntentLedger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A shared, transactional {@link ReservationStore} backed by PostgreSQL (ADR-0001).
 *
 * <p>This is the backend that lets more than one controller share durable state safely, which the
 * file WAL cannot. It preserves the properties the WAL already guarantees and adds the distributed
 * ones behind a single dependency: the WAL's atomic framed transaction becomes one database
 * transaction, fsync-before-commit becomes {@code synchronous_commit = on}, and the single-writer
 * FileLock plus writer epoch become a {@link PostgresLeadership} lease whose term is the fencing
 * token stamped on every write.
 *
 * <h2>Fencing is atomic, not check-then-commit</h2>
 *
 * <p>An earlier cut validated the term, then wrote and committed in separate steps with no lock
 * held between them — a time-of-check-to-time-of-use hole. A takeover could interleave: the old
 * leader validated term T, a new leader committed T+1, and the old transaction then committed rows
 * stamped T over the newer state. {@link #append} now holds {@code pg_advisory_xact_lock} — the
 * same lock {@link PostgresLeadership} takes — across the whole transaction, so a takeover cannot
 * commit in the middle of an append, and it re-reads the leadership row under that lock and refuses
 * if the term or leader has moved on. Every upsert additionally carries a {@code WHERE term <=
 * EXCLUDED.term} guard, so even a stray lower-term write cannot overwrite a newer row.
 *
 * <h2>Readers do not acquire leadership</h2>
 *
 * <p>Constructing a store no longer takes a term. Writing requires a {@link PostgresLeadership} the
 * caller has made leader; a read-only replica is built with {@link #readOnly} and can
 * {@link #restore} without ever contending for the lease. That separation matters: a restore-only process must not fence the active writer.
 */
public final class PostgresReservationStore implements ReservationStore {

    private static final Logger log = Logger.getLogger(PostgresReservationStore.class.getName());
    private static final long ADVISORY_LOCK_KEY = 4711L;

    private final String url;
    private final Properties connectionProperties;
    private final PostgresLeadership leadership;   // null for a read-only store

    /** A writer bound to a leadership lease; only its leader may {@link #append}. */
    public PostgresReservationStore(String url, String user, String password,
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

    /** A read-only store: it can {@link #restore} but never acquires leadership or writes. */
    public static PostgresReservationStore readOnly(String url, String user, String password) {
        return new PostgresReservationStore(url, user, password, null);
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url, connectionProperties);
        connection.setAutoCommit(false);
        // fsync-before-commit: a returned commit must be durable, matching the WAL's promise.
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET synchronous_commit = on");
        }
        return connection;
    }

    private void initialiseSchema() {
        try (Connection connection = open()) {
            // CREATE TABLE IF NOT EXISTS is not race-safe: two controllers starting together can both
            // pass the existence check and one then fails with a duplicate-relation error. Serialise
            // schema creation under the same advisory lock append() already uses.
            try (Statement lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS leadership ("
                        + "id int PRIMARY KEY, term bigint NOT NULL, "
                        + "leader_id text, lease_expiry timestamptz)");
                statement.execute("CREATE TABLE IF NOT EXISTS link_reservation ("
                        + "reservation_id text PRIMARY KEY, task_id text, link_id text, "
                        + "src_node text, dst_node text, bw_bps double precision, "
                        + "start_sec double precision, end_sec double precision, term bigint)");
                // Added with overbooking. NULL, as in every row written before, means full weight.
                statement.execute("ALTER TABLE link_reservation ADD COLUMN IF NOT EXISTS "
                        + "weight double precision");
                statement.execute("CREATE TABLE IF NOT EXISTS node_reservation ("
                        + "reservation_id text PRIMARY KEY, task_id text, node_id text, "
                        + "buffer_bytes double precision, start_sec double precision, "
                        + "end_sec double precision, term bigint)");
                statement.execute("CREATE TABLE IF NOT EXISTS intent ("
                        + "task_id text PRIMARY KEY, lsp_name text, state text, srp_id bigint, "
                        + "plsp_id bigint, pcc_session_key text, owner text, "
                        + "pending_rate_bps double precision, term bigint)");
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not initialise the Postgres reservation store", e);
        }
    }

    @Override
    public long fencingToken() {
        return leadership == null ? NO_FENCING_TOKEN : leadership.term();
    }

    @Override
    public boolean restore(LRIB lrib, NRIB nrib) {
        return restore(lrib, nrib, null);
    }

    @Override
    public boolean restore(LRIB lrib, NRIB nrib, IntentLedger intents) {
        boolean any = false;
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT reservation_id, task_id, link_id, src_node, dst_node, bw_bps, "
                                 + "start_sec, end_sec, COALESCE(weight, 1.0) FROM link_reservation")) {
                while (rs.next()) {
                    lrib.restoreLinkReservation(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getDouble(6), rs.getDouble(7),
                            rs.getDouble(8), rs.getDouble(9));
                    any = true;
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT reservation_id, task_id, node_id, buffer_bytes, start_sec, end_sec "
                                 + "FROM node_reservation")) {
                while (rs.next()) {
                    nrib.restoreNodeReservation(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getDouble(4), rs.getDouble(5), rs.getDouble(6));
                    any = true;
                }
            }
            if (intents != null) {
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery(
                             "SELECT task_id, lsp_name, state, srp_id, plsp_id, pcc_session_key, "
                                     + "owner, pending_rate_bps FROM intent")) {
                    while (rs.next()) {
                        intents.restore(InstallationIntent.restore(
                                rs.getString(1), rs.getString(2),
                                InstallationState.valueOf(rs.getString(3)),
                                nullableLong(rs, 4), nullableLong(rs, 5), rs.getString(6),
                                rs.getString(7), nullableDouble(rs, 8)));
                        any = true;
                    }
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not restore from the Postgres reservation store", e);
        }
        return any;
    }

    @Override
    public void append(ReservationDelta delta) {
        if (leadership == null) {
            throw new IllegalStateException("a read-only Postgres store cannot append");
        }
        long term = leadership.term();
        try (Connection connection = open()) {
            // Hold the leadership advisory lock for the whole transaction. A takeover takes the same
            // lock, so it cannot commit a new term in the middle of this append -- the check below,
            // the writes, and the commit are one indivisible critical section.
            try (Statement lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
            }
            LeaderRow row = readLeadership(connection);
            if (row == null || row.term != term
                    || !leadership.instanceId().equals(row.leaderId)) {
                connection.rollback();
                throw new IllegalStateException("leadership term " + term
                        + " is no longer current (now " + (row == null ? "none" : row.term)
                        + "); stop this instance");
            }
            writeLinkReservations(connection, delta, term);
            writeNodeReservations(connection, delta, term);
            if (!delta.removedReservationIds().isEmpty()) {
                deleteByIds(connection, "link_reservation", delta.removedReservationIds());
                deleteByIds(connection, "node_reservation", delta.removedReservationIds());
            }
            writeIntents(connection, delta, term);
            connection.commit();               // releases the advisory lock
        } catch (SQLException e) {
            throw new IllegalStateException("could not append to the Postgres reservation store", e);
        }
    }

    private void writeLinkReservations(Connection connection, ReservationDelta delta, long term)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO link_reservation (reservation_id, task_id, link_id, src_node, "
                        + "dst_node, bw_bps, start_sec, end_sec, term, weight) VALUES (?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (reservation_id) DO UPDATE SET task_id=EXCLUDED.task_id, "
                        + "link_id=EXCLUDED.link_id, src_node=EXCLUDED.src_node, "
                        + "dst_node=EXCLUDED.dst_node, bw_bps=EXCLUDED.bw_bps, "
                        + "start_sec=EXCLUDED.start_sec, end_sec=EXCLUDED.end_sec, "
                        + "term=EXCLUDED.term, weight=EXCLUDED.weight "
                        + "WHERE link_reservation.term <= EXCLUDED.term")) {
            for (LRIB.LinkReservation r : delta.addedLinkReservations()) {
                ps.setString(1, r.getReservationId());
                ps.setString(2, r.getTaskId());
                ps.setString(3, r.getLinkId());
                ps.setString(4, r.getSourceNodeId());
                ps.setString(5, r.getDestNodeId());
                ps.setDouble(6, r.getReservedBwBps());
                ps.setDouble(7, r.getStartSec());
                ps.setDouble(8, r.getEndSec());
                ps.setLong(9, term);
                // NULL for full weight, as every row written before overbooking existed.
                if (r.getWeight() == 1.0) {
                    ps.setNull(10, java.sql.Types.DOUBLE);
                } else {
                    ps.setDouble(10, r.getWeight());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void writeNodeReservations(Connection connection, ReservationDelta delta, long term)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO node_reservation (reservation_id, task_id, node_id, buffer_bytes, "
                        + "start_sec, end_sec, term) VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT (reservation_id) DO UPDATE SET task_id=EXCLUDED.task_id, "
                        + "node_id=EXCLUDED.node_id, buffer_bytes=EXCLUDED.buffer_bytes, "
                        + "start_sec=EXCLUDED.start_sec, end_sec=EXCLUDED.end_sec, "
                        + "term=EXCLUDED.term WHERE node_reservation.term <= EXCLUDED.term")) {
            for (NRIB.NodeReservation r : delta.addedNodeReservations()) {
                ps.setString(1, r.getReservationId());
                ps.setString(2, r.getTaskId());
                ps.setString(3, r.getNodeId());
                ps.setDouble(4, r.getReservedBufferBytes());
                ps.setDouble(5, r.getStartSec());
                ps.setDouble(6, r.getEndSec());
                ps.setLong(7, term);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void writeIntents(Connection connection, ReservationDelta delta, long term)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO intent (task_id, lsp_name, state, srp_id, plsp_id, pcc_session_key, "
                        + "owner, pending_rate_bps, term) VALUES (?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (task_id) DO UPDATE SET lsp_name=EXCLUDED.lsp_name, "
                        + "state=EXCLUDED.state, srp_id=EXCLUDED.srp_id, plsp_id=EXCLUDED.plsp_id, "
                        + "pcc_session_key=EXCLUDED.pcc_session_key, owner=EXCLUDED.owner, "
                        + "pending_rate_bps=EXCLUDED.pending_rate_bps, term=EXCLUDED.term "
                        + "WHERE intent.term <= EXCLUDED.term")) {
            for (InstallationIntent intent : delta.intentTransitions()) {
                ps.setString(1, intent.getTaskId());
                ps.setString(2, intent.getLspName());
                ps.setString(3, intent.getState().name());
                setNullableLong(ps, 4, intent.getSrpId().orElse(null));
                setNullableLong(ps, 5, intent.getPlspId().orElse(null));
                ps.setString(6, intent.getPccSessionKey().orElse(null));
                ps.setString(7, intent.getOwner().orElse(null));
                setNullableDouble(ps, 8, intent.getPendingRateBps().orElse(null));
                ps.setLong(9, term);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** A shared store has no size-proportional-to-history growth, so compaction is a no-op. */
    @Override
    public void compact(LRIB lrib, NRIB nrib) {
    }

    private LeaderRow readLeadership(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT term, leader_id FROM leadership WHERE id = 1")) {
            return rs.next() ? new LeaderRow(rs.getLong(1), rs.getString(2)) : null;
        }
    }

    private static void deleteByIds(Connection connection, String table,
                                    java.util.Set<String> ids) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE reservation_id = ?")) {
            for (String id : ids) {
                ps.setString(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, int column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private record LeaderRow(long term, String leaderId) {
    }
}
