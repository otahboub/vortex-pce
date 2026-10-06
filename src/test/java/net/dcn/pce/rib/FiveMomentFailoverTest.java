package net.dcn.pce.rib;

import net.dcn.pce.install.InstallationIntent;
import net.dcn.pce.install.InstallationState;
import net.dcn.pce.install.IntentLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The five failover moments ADR-0001 (action 6) requires the shared backend to survive, each driven
 * against a real Postgres and gated on {@code POSTGRES_URL}. The review named these moments because
 * each is a place a naive failover loses or duplicates committed state, or lets a superseded leader
 * write over a newer one. The invariant across all five is the same: <em>exactly one leader's writes
 * ever reach the tables, and a committed reservation is neither lost nor double-counted across a
 * takeover.</em>
 *
 * <p>The takeovers here are deterministic, not sleep-timed: a leader's lease is expired directly and
 * the standby acquires it through the real {@link PostgresLeadership#tryBecomeLeader()} path, so the
 * survivor runs the production acquisition code and the test does not race a heartbeat. The old
 * leader keeps its cached term (that is precisely what a partitioned-but-alive leader believes), so
 * its late write exercises the real fencing check rather than a simulated one.
 *
 * <p>Two moments are structural rather than timing races, and the tests say so explicitly:
 * <ul>
 *   <li><b>mid partial-write</b> — {@link PostgresReservationStore#append} writes link, node, and
 *       intent rows in one transaction opened with {@code autoCommit=false} and
 *       {@code synchronous_commit=on}; a crash before {@code commit()} must surface nothing. The
 *       store's own API cannot even construct a partial delta (the domain rejects null/blank ids),
 *       so the crash is injected at the transaction layer the store depends on, using the store's
 *       exact SQL and connection settings.</li>
 *   <li><b>mid-compaction</b> — {@link PostgresReservationStore#compact} is a deliberate no-op: the
 *       shared backend keeps current-state tables (upserts), not a growing log, so there is no
 *       compaction window in which to lose a committed row. The test pins that invariant so a future
 *       change that makes compaction do real work is forced to bring its own fault injection.</li>
 * </ul>
 */
class FiveMomentFailoverTest {

    private static final String URL = System.getenv("POSTGRES_URL");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_USER", "postgres");
    private static final String PASSWORD =
            System.getenv().getOrDefault("POSTGRES_PASSWORD", "postgres");

    @BeforeEach
    void requirePostgresAndCleanSlate() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(), "POSTGRES_URL not set; skipping Postgres integration");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS link_reservation, node_reservation, intent, leadership");
        }
    }

    // --- Moment 1: mid-solve ------------------------------------------------------------------

    /**
     * A solve outlives the leadership it began under. The leader computes a schedule, loses the lease
     * to a standby before it can commit, and then tries to commit anyway (its solve thread has no
     * idea it was demoted). The stale-term append must be fenced with nothing written, and the new
     * leader must be free to proceed.
     */
    @Test
    void midSolve_aDemotedLeadersCommitIsFencedAndTheSurvivorProceeds() {
        PostgresLeadership a = leader("A");                       // term 1
        PostgresReservationStore aStore =
                new PostgresReservationStore(URL, USER, PASSWORD, a);

        // A begins solving here (delta computed in memory, not yet committed).
        ReservationStore.ReservationDelta solved = reservationDelta();

        // A is partitioned long enough for its lease to lapse; a standby takes over for real.
        expireLease();
        PostgresLeadership b = standby("B");
        assertTrue(b.tryBecomeLeader(), "B takes the lapsed lease");      // term 2

        // A, still believing it is term 1, tries to commit the solve it started as leader.
        IllegalStateException fenced = assertThrows(IllegalStateException.class,
                () -> aStore.append(solved),
                "a solve begun under a lost term must not commit");
        assertTrue(fenced.getMessage().contains("no longer current"), fenced.getMessage());
        assertEquals(0, rowCount("link_reservation"), "the fenced solve wrote nothing");
        assertEquals(0, rowCount("intent"), "the fenced solve wrote nothing");

        // The survivor commits its own solve normally.
        PostgresReservationStore bStore =
                new PostgresReservationStore(URL, USER, PASSWORD, b);
        bStore.append(solved);
        assertEquals(1, rowCount("link_reservation"), "the new leader's solve committed");
    }

    // --- Moment 2: mid-dispatch ---------------------------------------------------------------

    /**
     * The leader commits a reservation and marks the intent INSTALLING (the dispatch has persisted
     * and a PCInitiate is on the wire), then dies before the install is confirmed. The standby must
     * inherit the committed reservation and the INSTALLING intent so it neither loses the capacity
     * nor re-admits it to someone else.
     */
    @Test
    void midDispatch_theStandbyInheritsCommittedReservationsAndInstallingState() {
        PostgresLeadership a = leader("A");
        new PostgresReservationStore(URL, USER, PASSWORD, a).append(reservationDelta());

        // A dies with the PCInitiate in flight; B takes over.
        expireLease();
        PostgresLeadership b = standby("B");
        assertTrue(b.tryBecomeLeader());

        LRIB lrib = new LRIB();
        NRIB nrib = new NRIB();
        IntentLedger intents = new IntentLedger();
        new PostgresReservationStore(URL, USER, PASSWORD, b).restore(lrib, nrib, intents);

        assertEquals(1, lrib.getAllReservations().size(), "the committed reservation is inherited");
        InstallationIntent t1 = intents.find("T1").orElseThrow();
        assertEquals(InstallationState.INSTALLING, t1.getState(),
                "the standby sees the LSP as still installing, not free");
        assertEquals(42L, t1.getPlspId().orElseThrow(), "the delegated LSP identity carries over");
        assertTrue(intents.holdingCapacity().stream().anyMatch(i -> i.getTaskId().equals("T1")),
                "the standby knows T1 still holds capacity, so it will not re-admit it");
    }

    // --- Moment 3: mid partial-write ----------------------------------------------------------

    /**
     * A crash between the first row of an append and its commit must leave no trace. The store writes
     * link, node, and intent in one transaction and relies on connection-close rollback; this injects
     * the crash at that layer (the store's API cannot build a partial delta) using the store's exact
     * SQL and durability settings, then confirms the store reads back an empty database.
     */
    @Test
    void midPartialWrite_anUncommittedTransactionSurfacesNothing() throws Exception {
        // Constructing the store initialises the schema, as a live deployment would have.
        new PostgresReservationStore(URL, USER, PASSWORD, leader("A"));

        // Reproduce the store's own transaction: autoCommit off, synchronous_commit on, write the
        // first (link) row of a multi-row append -- then crash before commit by dropping the
        // connection. JDBC rolls the transaction back on close.
        try (Connection crashing = DriverManager.getConnection(URL, USER, PASSWORD)) {
            crashing.setAutoCommit(false);
            try (Statement s = crashing.createStatement()) {
                s.execute("SET synchronous_commit = on");
                s.execute("INSERT INTO link_reservation (reservation_id, task_id, link_id, src_node, "
                        + "dst_node, bw_bps, start_sec, end_sec, term) VALUES "
                        + "('R1','T1','L1','A','B',4000000,0,10,1)");
            }
            // No commit. Leaving the block closes the connection -> rollback.
        }

        assertEquals(0, rowCount("link_reservation"),
                "a partial append that never committed must be invisible");
        LRIB lrib = new LRIB();
        NRIB nrib = new NRIB();
        assertFalse(PostgresReservationStore.readOnly(URL, USER, PASSWORD).restore(lrib, nrib),
                "the store reads back an empty database after the crash");
    }

    // --- Moment 4: mid-acknowledgement --------------------------------------------------------

    /**
     * The PCC confirms an install, but the leader crashes before it records the INSTALLING to
     * INSTALLED transition. On resync the PCC re-sends its report to the new leader, which applies
     * the transition. Applying it must be idempotent -- a re-sent acknowledgement across the failover
     * boundary must not duplicate the intent or its reservation.
     */
    @Test
    void midAcknowledgement_theTransitionReplaysIdempotentlyAcrossFailover() {
        PostgresLeadership a = leader("A");
        new PostgresReservationStore(URL, USER, PASSWORD, a).append(reservationDelta());

        // A crashes after the PCC acked but before recording INSTALLED. B takes over.
        expireLease();
        PostgresLeadership b = standby("B");
        assertTrue(b.tryBecomeLeader());
        PostgresReservationStore bStore = new PostgresReservationStore(URL, USER, PASSWORD, b);

        // The PCC re-sends its report on resync; B records the confirmed install.
        bStore.append(intentOnlyDelta(InstallationState.INSTALLED));
        // A duplicate report (the PCC retried) is applied again -- it must be a no-op.
        bStore.append(intentOnlyDelta(InstallationState.INSTALLED));

        assertEquals(1, rowCount("intent"), "the re-sent acknowledgement did not duplicate the intent");
        assertEquals(1, rowCount("link_reservation"), "nor its reservation");
        IntentLedger intents = new IntentLedger();
        PostgresReservationStore.readOnly(URL, USER, PASSWORD).restore(new LRIB(), new NRIB(), intents);
        assertEquals(InstallationState.INSTALLED, intents.find("T1").orElseThrow().getState(),
                "the confirmed state is durable after failover");
    }

    // --- Moment 5: mid-compaction -------------------------------------------------------------

    /**
     * Compaction on the shared backend is a no-op -- current-state tables do not accumulate history
     * to compact -- so there is no window in which a crash could lose a committed reservation. This
     * pins that: compaction preserves state exactly, and a change that gives it real work to do will
     * fail here until it is proven crash-safe on its own.
     */
    @Test
    void midCompaction_compactionIsANoOpThatPreservesCommittedState() {
        PostgresLeadership a = leader("A");
        PostgresReservationStore store = new PostgresReservationStore(URL, USER, PASSWORD, a);
        store.append(reservationDelta());

        LRIB before = new LRIB();
        NRIB beforeN = new NRIB();
        store.restore(before, beforeN);

        // Compact repeatedly, as a crash-and-restart-mid-compaction would re-enter it.
        store.compact(before, beforeN);
        store.compact(before, beforeN);

        assertEquals(1, rowCount("link_reservation"), "compaction preserved the committed reservation");
        assertEquals(1, rowCount("node_reservation"), "and the node reservation");
        LRIB after = new LRIB();
        NRIB afterN = new NRIB();
        assertTrue(store.restore(after, afterN), "state is still present after compaction");
        assertEquals(before.getAllReservations().size(), after.getAllReservations().size(),
                "compaction changed nothing");
    }

    // --- helpers ------------------------------------------------------------------------------

    private PostgresLeadership leader(String id) {
        PostgresLeadership l = standby(id);
        assertTrue(l.tryBecomeLeader(), "instance " + id + " should take vacant leadership");
        return l;
    }

    private PostgresLeadership standby(String id) {
        return new PostgresLeadership(URL, USER, PASSWORD, 30_000, id);
    }

    /** Expire the current lease so a standby can acquire it through the real code path. */
    private void expireLease() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("UPDATE leadership SET lease_expiry = now() - interval '1 minute' WHERE id = 1");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int rowCount(String table) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ReservationStore.ReservationDelta reservationDelta() {
        LRIB lrib = new LRIB();
        lrib.restoreLinkReservation("R1", "T1", "L1", "A", "B", 4_000_000, 0, 10);
        NRIB nrib = new NRIB();
        nrib.restoreNodeReservation("N1", "T1", "B", 2_000_000, 0, 10);
        InstallationIntent intent = InstallationIntent.restore(
                "T1", "lsp-T1", InstallationState.INSTALLING, 7L, 42L, "speaker:pcc-1", "tenant-a", null);
        return new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), nrib.getAllReservations(), Set.of(), List.of(intent));
    }

    private static ReservationStore.ReservationDelta intentOnlyDelta(InstallationState state) {
        InstallationIntent intent = InstallationIntent.restore(
                "T1", "lsp-T1", state, 7L, 42L, "speaker:pcc-1", "tenant-a", null);
        return new ReservationStore.ReservationDelta(
                List.of(), List.of(), Set.of(), List.of(intent));
    }
}
