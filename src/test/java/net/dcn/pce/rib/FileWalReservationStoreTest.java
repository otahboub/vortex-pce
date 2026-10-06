package net.dcn.pce.rib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileWalReservationStoreTest {

    private static LRIB.LinkReservation seedLink(LRIB lrib, String id, String taskId, double start, double end) {
        return lrib.restoreLinkReservation(id, taskId, "L1", "A", "B", 1_000_000, start, end);
    }

    private static NRIB.NodeReservation seedNode(NRIB nrib, String id, String taskId) {
        return nrib.restoreNodeReservation(id, taskId, "A", 4096, 0, 10);
    }

    @Test
    void restoreReportsNoStateWhenNothingWasEverWritten(@TempDir Path dir) {
        FileWalReservationStore store = new FileWalReservationStore(dir.resolve("state.json").toString());

        assertFalse(store.restore(new LRIB(), new NRIB()));
    }

    @Test
    void appendedReservationsSurviveAcrossAFreshStore(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();
        LRIB lrib = new LRIB();
        NRIB nrib = new NRIB();
        seedLink(lrib, "R1", "T1", 0, 5);
        seedNode(nrib, "N1", "T1");

        FileWalReservationStore writer = new FileWalReservationStore(statePath);
        writer.append(new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), nrib.getAllReservations(), Set.of()));

        LRIB restoredLrib = new LRIB();
        NRIB restoredNrib = new NRIB();
        assertTrue(new FileWalReservationStore(statePath).restore(restoredLrib, restoredNrib));

        assertEquals(1, restoredLrib.getAllReservations().size());
        assertEquals(1, restoredNrib.getAllReservations().size());
        LRIB.LinkReservation restored = restoredLrib.getAllReservations().get(0);
        assertEquals("R1", restored.getReservationId());
        assertEquals("T1", restored.getTaskId());
        assertEquals(1_000_000, restored.getReservedBwBps(), 0.0);
        assertEquals(5, restored.getEndSec(), 0.0);
    }

    @Test
    void aRemovalRecordedAfterAPutSuppressesThatReservation(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();
        LRIB lrib = new LRIB();
        NRIB nrib = new NRIB();
        seedLink(lrib, "R1", "KEEP", 0, 5);
        seedLink(lrib, "R2", "DROP", 0, 5);

        FileWalReservationStore store = new FileWalReservationStore(statePath);
        store.append(new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), List.of(), Set.of()));
        store.append(new ReservationStore.ReservationDelta(List.of(), List.of(), Set.of("R2")));

        LRIB restored = new LRIB();
        assertTrue(new FileWalReservationStore(statePath).restore(restored, new NRIB()));

        assertEquals(1, restored.getAllReservations().size());
        assertEquals("R1", restored.getAllReservations().get(0).getReservationId());
    }

    @Test
    void theLogGrowsWithTheChangeNotWithLedgerDepth(@TempDir Path dir) throws Exception {
        String statePath = dir.resolve("state.json").toString();
        FileWalReservationStore store = new FileWalReservationStore(statePath);
        LRIB lrib = new LRIB();

        for (int i = 0; i < 40; i++) {
            LRIB.LinkReservation added = seedLink(lrib, "R" + i, "T" + i, i, i + 1);
            store.append(new ReservationStore.ReservationDelta(List.of(added), List.of(), Set.of()));
        }

        // One data line plus one commit marker per appended reservation, plus the header. A
        // snapshot-per-write store would have rewritten all 40 reservations on the fortieth call,
        // so growth still tracks the change rather than ledger depth.
        long lines = Files.readAllLines(store.getWalPath()).stream().filter(l -> !l.isBlank()).count();
        assertEquals(81, lines);
    }

    @Test
    void compactionShrinksTheLogToLiveStateAndPreservesIt(@TempDir Path dir) throws Exception {
        String statePath = dir.resolve("state.json").toString();
        FileWalReservationStore store = new FileWalReservationStore(statePath);
        LRIB lrib = new LRIB();

        for (int i = 0; i < 30; i++) {
            LRIB.LinkReservation added = seedLink(lrib, "R" + i, "T" + i, i, i + 1);
            store.append(new ReservationStore.ReservationDelta(List.of(added), List.of(), Set.of()));
        }
        for (int i = 0; i < 25; i++) {
            lrib.removeReservationsForTask("T" + i);
            store.append(new ReservationStore.ReservationDelta(List.of(), List.of(), Set.of("R" + i)));
        }
        long linesBefore = Files.readAllLines(store.getWalPath()).size();

        store.compact(lrib, new NRIB());
        long linesAfter = Files.readAllLines(store.getWalPath()).size();

        assertTrue(linesAfter < linesBefore,
                "compaction should reduce the log: " + linesAfter + " vs " + linesBefore);
        assertEquals(7, linesAfter,
                "header, the five surviving reservations, and the commit marker framing them");

        LRIB restored = new LRIB();
        assertTrue(new FileWalReservationStore(statePath).restore(restored, new NRIB()));
        assertEquals(5, restored.getAllReservations().size());
    }

    @Test
    void compactionIsTriggeredOnlyOnceTheLogOutgrowsLiveState(@TempDir Path dir) {
        FileWalReservationStore store = new FileWalReservationStore(dir.resolve("state.json").toString());
        LRIB lrib = new LRIB();

        for (int i = 0; i < 600; i++) {
            LRIB.LinkReservation added = seedLink(lrib, "R" + i, "T" + i, i, i + 1);
            store.append(new ReservationStore.ReservationDelta(List.of(added), List.of(), Set.of()));
        }

        // 600 live reservations sit well under the 4x growth factor, so no compaction is due.
        assertFalse(store.shouldCompact(600));
        // The same log against a nearly empty ledger is pure overhead and should compact.
        assertTrue(store.shouldCompact(1));
    }

    @Test
    void aLegacySnapshotIsImportedAndConvertedOnFirstStart(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();
        LRIB lrib = new LRIB();
        NRIB nrib = new NRIB();
        seedLink(lrib, "LEGACY-1", "OLD", 0, 9);
        seedNode(nrib, "LEGACY-N1", "OLD");
        new LRIBStateStore(statePath).persistJournal(lrib, nrib);

        FileWalReservationStore store = new FileWalReservationStore(statePath);
        LRIB restoredLrib = new LRIB();
        NRIB restoredNrib = new NRIB();

        // An upgrade must not start with an empty ledger and re-admit already-committed capacity.
        assertTrue(store.restore(restoredLrib, restoredNrib));
        assertEquals(1, restoredLrib.getAllReservations().size());
        assertEquals("LEGACY-1", restoredLrib.getAllReservations().get(0).getReservationId());
        assertEquals(1, restoredNrib.getAllReservations().size());
        assertTrue(Files.exists(store.getWalPath()), "the snapshot should be converted to a log");

        LRIB reopened = new LRIB();
        assertTrue(new FileWalReservationStore(statePath).restore(reopened, new NRIB()));
        assertEquals(1, reopened.getAllReservations().size());
    }

    @Test
    void anUnknownFormatVersionFailsLoudlyRatherThanStartingEmpty(@TempDir Path dir) throws Exception {
        Path statePath = dir.resolve("state.json");
        Path walPath = dir.resolve("state.json.wal");
        Files.writeString(walPath, "{\"op\":\"header\",\"formatVersion\":99}\n");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new FileWalReservationStore(statePath.toString()).restore(new LRIB(), new NRIB()));

        assertTrue(error.getMessage().contains("99"));
        assertTrue(error.getMessage().contains("Migrate or reset"));
    }

    @Test
    void aLogWithoutAFormatHeaderIsRejected(@TempDir Path dir) throws Exception {
        Path statePath = dir.resolve("state.json");
        Files.writeString(dir.resolve("state.json.wal"),
                "{\"op\":\"remove\",\"reservationId\":\"R1\"}\n");

        assertThrows(IllegalStateException.class,
                () -> new FileWalReservationStore(statePath.toString()).restore(new LRIB(), new NRIB()));
    }

    @Test
    void anEmptyDeltaWritesNothing(@TempDir Path dir) {
        FileWalReservationStore store = new FileWalReservationStore(dir.resolve("state.json").toString());
        store.append(new ReservationStore.ReservationDelta(List.of(), List.of(), Set.of()));

        assertFalse(Files.exists(store.getWalPath()),
                "a no-op transaction should not create or touch the log");
    }
}
