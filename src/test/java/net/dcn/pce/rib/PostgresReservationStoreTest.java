package net.dcn.pce.rib;

import net.dcn.pce.install.InstallationIntent;
import net.dcn.pce.install.InstallationState;
import net.dcn.pce.install.IntentLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for the shared Postgres backend (ADR-0001). Gated on POSTGRES_URL. Proves the
 * properties the file WAL could not offer across hosts: a committed delta reloads exactly; a
 * read-only replica never fences the writer; and a superseded term cannot commit over newer state
 * (tested deterministically).
 */
class PostgresReservationStoreTest {

    private static final String URL = System.getenv("POSTGRES_URL");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_PASSWORD", "postgres");

    @BeforeEach
    void requirePostgresAndCleanSlate() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(), "POSTGRES_URL not set; skipping Postgres integration");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS link_reservation, node_reservation, intent, leadership");
        }
    }

    private PostgresLeadership leader(String id) {
        PostgresLeadership l = new PostgresLeadership(URL, USER, PASSWORD, 30_000, id);
        assertTrue(l.tryBecomeLeader(), "instance " + id + " should take vacant leadership");
        return l;
    }

    private static ReservationStore.ReservationDelta delta() {
        LRIB lrib = new LRIB();
        lrib.restoreLinkReservation("R1", "T1", "L1", "A", "B", 4_000_000, 0, 10);
        NRIB nrib = new NRIB();
        nrib.restoreNodeReservation("N1", "T1", "B", 2_000_000, 0, 10);
        InstallationIntent intent = InstallationIntent.restore(
                "T1", "lsp-T1", InstallationState.INSTALLING, 7L, 42L, "speaker:pcc-1", "tenant-a", null);
        return new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), nrib.getAllReservations(), Set.of(), List.of(intent));
    }

    @Test
    void anOverbookedReservationKeepsItsWeight() {
        PostgresReservationStore store = new PostgresReservationStore(URL, USER, PASSWORD, leader("A"));
        LRIB lrib = new LRIB();
        lrib.restoreLinkReservation("FULL", "T1", "L", "A", "B", 4_000_000, 0, 10);
        lrib.restoreLinkReservation("HALF", "T2", "L", "A", "B", 4_000_000, 0, 10, 0.5);
        store.append(new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), List.of(), Set.of(), List.of()));

        LRIB restored = new LRIB();
        assertTrue(PostgresReservationStore.readOnly(URL, USER, PASSWORD)
                .restore(restored, new NRIB(), new IntentLedger()));
        java.util.Map<String, Double> weights = new java.util.HashMap<>();
        restored.getAllReservations().forEach(r -> weights.put(r.getReservationId(), r.getWeight()));
        assertEquals(java.util.Map.of("FULL", 1.0, "HALF", 0.5), weights);
    }

    /**
     * A database written before reservations had weights: a replica starting against it upgrades the
     * table in place, and the old rows load at full weight.
     */
    @Test
    void aTableFromBeforeWeightsUpgradesAndItsRowsLoadAtFullWeight() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE link_reservation (reservation_id text PRIMARY KEY, task_id text, "
                    + "link_id text, src_node text, dst_node text, bw_bps double precision, "
                    + "start_sec double precision, end_sec double precision, term bigint)");
            s.execute("INSERT INTO link_reservation VALUES ('OLD', 'T1', 'L', 'A', 'B', 4000000, 0, 10, 1)");
        }

        LRIB restored = new LRIB();
        assertTrue(PostgresReservationStore.readOnly(URL, USER, PASSWORD)
                .restore(restored, new NRIB(), new IntentLedger()));
        assertEquals(1, restored.getAllReservations().size());
        assertEquals(1.0, restored.getAllReservations().get(0).getWeight());
        assertEquals(4_000_000, restored.getAllReservations().get(0).getReservedBwBps(), 1.0);
    }

    @Test
    void aCommittedDeltaReloadsExactly() {
        PostgresReservationStore store = new PostgresReservationStore(URL, USER, PASSWORD, leader("A"));
        store.append(delta());

        // A read-only replica restores without acquiring leadership (readers must not
        // fence the writer).
        LRIB lrib = new LRIB();
        NRIB nrib = new NRIB();
        IntentLedger intents = new IntentLedger();
        PostgresReservationStore reader = PostgresReservationStore.readOnly(URL, USER, PASSWORD);
        assertTrue(reader.restore(lrib, nrib, intents), "durable state should be reported present");

        assertEquals(1, lrib.getAllReservations().size());
        assertEquals(4_000_000, lrib.getAllReservations().get(0).getReservedBwBps(), 1.0);
        assertEquals(2_000_000, nrib.getAllReservations().get(0).getReservedBufferBytes(), 1.0);
        InstallationIntent restored = intents.find("T1").orElseThrow();
        assertEquals(InstallationState.INSTALLING, restored.getState());
        assertEquals(42L, restored.getPlspId().orElseThrow());
        assertEquals("tenant-a", restored.getOwner().orElseThrow());
    }

    @Test
    void aReadOnlyReplicaCannotAppend() {
        PostgresReservationStore reader = PostgresReservationStore.readOnly(URL, USER, PASSWORD);
        assertThrows(IllegalStateException.class, () -> reader.append(delta()),
                "a store with no leadership must refuse to write");
    }

    @Test
    void aSupersededTermCannotCommitOverNewerState() {
        PostgresLeadership first = leader("first");
        PostgresReservationStore firstStore =
                new PostgresReservationStore(URL, USER, PASSWORD, first);
        firstStore.append(delta());                  // works: first is the current leader

        // A new leader takes over deterministically -- lease still live, so it must wait for the
        // gap, but here we simulate the takeover directly by acquiring after expiry is irrelevant:
        // we bump the term via a fresh leadership that we force by clearing the lease.
        forceTakeover("second", 99L);

        // The first, now on a stale term, must be fenced on its next append -- the append re-reads
        // the leadership row under the advisory lock and refuses. No stale write reaches the tables.
        IllegalStateException fenced = assertThrows(IllegalStateException.class,
                () -> firstStore.append(delta()),
                "a superseded term must not commit over newer state");
        assertTrue(fenced.getMessage().contains("no longer current"), fenced.getMessage());
    }

    /** Deterministically install a higher-term leader by writing the leadership row directly. */
    private void forceTakeover(String leaderId, long term) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("UPDATE leadership SET term = " + term + ", leader_id = '" + leaderId
                    + "', lease_expiry = now() + interval '1 hour' WHERE id = 1");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
