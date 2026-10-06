package net.dcn.pce.rib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LRIBStateStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndRestoresCompleteLedgers() throws IOException {
        Path statePath = temporaryDirectory.resolve("state.json");
        LRIB sourceLrib = new LRIB();
        NRIB sourceNrib = new NRIB();
        LRIB.LinkReservation link = sourceLrib.reserveLinkCap(
                "task-1", "link-1", "A", "B", 8_000_000, 10, 20);
        NRIB.NodeReservation node = sourceNrib.reserveNodeBuff(
                "task-1", "B", 4096, 20, 30);

        LRIBStateStore store = new LRIBStateStore(statePath.toString());
        store.persistJournal(sourceLrib, sourceNrib);

        String json = Files.readString(statePath);
        assertTrue(json.contains("\"linkReservations\""));
        assertTrue(json.contains("\"nodeReservations\""));
        assertTrue(json.contains("task-1"));

        LRIB restoredLrib = new LRIB();
        NRIB restoredNrib = new NRIB();
        assertTrue(store.restoreJournal(restoredLrib, restoredNrib));
        assertEquals(1, restoredLrib.getAllReservations().size());
        assertEquals(1, restoredNrib.getAllReservations().size());
        assertEquals(link.getReservationId(), restoredLrib.getAllReservations().get(0).getReservationId());
        assertEquals(node.getReservationId(), restoredNrib.getAllReservations().get(0).getReservationId());
        assertEquals(8_000_000, restoredLrib.getAllReservations().get(0).getReservedBwBps());
        assertEquals(4096, restoredNrib.getAllReservations().get(0).getReservedBufferBytes());
    }

    @Test
    void corruptionDoesNotMutateLiveLedgers() throws IOException {
        Path statePath = temporaryDirectory.resolve("corrupt.json");
        Files.writeString(statePath, "{not-json");
        LRIB liveLrib = new LRIB();
        NRIB liveNrib = new NRIB();
        liveLrib.reserveLinkCap("existing", "link-existing", "A", "B", 1000, 0, 1);

        LRIBStateStore store = new LRIBStateStore(statePath.toString());
        assertThrows(IllegalStateException.class, () -> store.restoreJournal(liveLrib, liveNrib));
        assertEquals(1, liveLrib.getAllReservations().size());
        assertEquals("existing", liveLrib.getAllReservations().get(0).getTaskId());
    }

    @Test
    void rejectsAverageRateVersionOneSnapshots() throws IOException {
        Path statePath = temporaryDirectory.resolve("version-one.json");
        Files.writeString(statePath, """
                {"formatVersion":1,"timestampEpochMs":0,
                 "linkReservations":[],"nodeReservations":[]}
                """);

        LRIBStateStore store = new LRIBStateStore(statePath.toString());

        assertThrows(IllegalStateException.class,
                () -> store.restoreJournal(new LRIB(), new NRIB()));
    }
}
