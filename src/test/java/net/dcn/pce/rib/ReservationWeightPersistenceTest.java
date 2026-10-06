package net.dcn.pce.rib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An overbooked reservation's capacity weight survives every durable path, and full-weight
 * reservations are written exactly as before weights existed, so older files load unchanged.
 */
class ReservationWeightPersistenceTest {

    private static LRIB ledgerWithOneFullAndOneOverbooked() {
        LRIB lrib = new LRIB();
        lrib.restoreLinkReservation("FULL", "T1", "L", "A", "B", 1e6, 0, 10);
        lrib.restoreLinkReservation("HALF", "T2", "L", "A", "B", 1e6, 0, 10, 0.5);
        return lrib;
    }

    private static List<LRIB.LinkReservation> sorted(LRIB lrib) {
        return lrib.getAllReservations().stream()
                .sorted(Comparator.comparing(LRIB.LinkReservation::getReservationId)).toList();
    }

    private static void assertWeightsRestored(LRIB restored) {
        List<LRIB.LinkReservation> reservations = sorted(restored);
        assertEquals(2, reservations.size());
        assertEquals("FULL", reservations.get(0).getReservationId());
        assertEquals(1.0, reservations.get(0).getWeight());
        assertEquals("HALF", reservations.get(1).getReservationId());
        assertEquals(0.5, reservations.get(1).getWeight());
        assertEquals(1e6, reservations.get(1).getReservedBwBps(), "the rate itself is unscaled");
        assertEquals(2e6 - 1.5e6, restored.getAvailableCap("L", 2e6, 0, 10), 1e-6,
                "capacity counts the full reservation and half the overbooked one");
    }

    @Test
    void theWriteAheadLogKeepsTheWeight(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();
        new FileWalReservationStore(statePath).append(new ReservationStore.ReservationDelta(
                ledgerWithOneFullAndOneOverbooked().getAllReservations(), List.of(), Set.of()));

        LRIB restored = new LRIB();
        assertTrue(new FileWalReservationStore(statePath).restore(restored, new NRIB()));
        assertWeightsRestored(restored);
    }

    @Test
    void aFullWeightWalRecordIsWrittenExactlyAsBefore(@TempDir Path dir) throws Exception {
        String statePath = dir.resolve("state.json").toString();
        LRIB lrib = new LRIB();
        lrib.restoreLinkReservation("FULL", "T1", "L", "A", "B", 1e6, 0, 10);
        new FileWalReservationStore(statePath).append(new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), List.of(), Set.of()));

        String wal = Files.readString(Path.of(statePath + ".wal"));
        assertTrue(wal.contains("\"reservationId\":\"FULL\""));
        assertFalse(wal.contains("weight"), "full-weight records carry no weight field: " + wal);
    }

    @Test
    void theSnapshotStoreKeepsTheWeightAndOmitsItAtFullWeight(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal.json");
        LRIBStateStore store = new LRIBStateStore(journal.toString());
        store.persistJournal(ledgerWithOneFullAndOneOverbooked(), new NRIB());

        LRIB restored = new LRIB();
        assertTrue(new LRIBStateStore(journal.toString()).restoreJournal(restored, new NRIB()));
        assertWeightsRestored(restored);

        String snapshot = Files.readString(journal);
        assertEquals(1, snapshot.split("\"weight\"", -1).length - 1,
                "only the overbooked reservation records a weight: " + snapshot);
    }
}
