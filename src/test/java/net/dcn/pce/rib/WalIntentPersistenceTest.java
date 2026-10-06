package net.dcn.pce.rib;

import net.dcn.pce.install.InstallationIntent;
import net.dcn.pce.install.InstallationState;
import net.dcn.pce.install.IntentLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalIntentPersistenceTest {

    private static InstallationIntent intent(String taskId, InstallationState state) {
        return InstallationIntent.restore(
                taskId, InstallationIntent.lspNameFor(taskId), state, 7L, 42L, "speaker:pcc-alpha");
    }

    private static LRIB.LinkReservation link(LRIB lrib, String id, String taskId) {
        return lrib.restoreLinkReservation(id, taskId, "L1", "A", "B", 1_000_000, 0, 5);
    }

    @Test
    void anIntentSurvivesARestart(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();
        LRIB lrib = new LRIB();
        link(lrib, "R1", "T1");

        FileWalReservationStore writer = new FileWalReservationStore(statePath);
        writer.append(new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), List.of(), Set.of(),
                List.of(intent("T1", InstallationState.UNCERTAIN))));
        // Simulate a terminated process: the next instance may exclusively repair the WAL.
        writer.close();

        IntentLedger restored = new IntentLedger();
        assertTrue(new FileWalReservationStore(statePath)
                .restore(new LRIB(), new NRIB(), restored));

        InstallationIntent recovered = restored.find("T1").orElseThrow();
        assertEquals(InstallationState.UNCERTAIN, recovered.getState());
        assertEquals("vortex-T1", recovered.getLspName());
        assertEquals(7L, recovered.getSrpId().orElseThrow());
        assertEquals(42L, recovered.getPlspId().orElseThrow());
        assertEquals("speaker:pcc-alpha", recovered.getPccSessionKey().orElseThrow());
    }

    /**
     * The reason intents live in this log rather than beside it. An UNCERTAIN task holds capacity;
     * if a restart recovered the reservation without the intent, the controller would hold
     * bandwidth it could no longer explain, reconcile, or ever release.
     */
    @Test
    void areservationAndItsIntentAreRecoveredTogetherOrNotAtAll(@TempDir Path dir) throws IOException {
        String statePath = dir.resolve("state.json").toString();
        Path walPath = dir.resolve("state.json.wal");
        LRIB lrib = new LRIB();
        link(lrib, "R1", "T1");

        FileWalReservationStore writer = new FileWalReservationStore(statePath);
        writer.append(new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), List.of(), Set.of(),
                List.of(intent("T1", InstallationState.UNCERTAIN))));
        // Truncation below represents a terminated process, not a concurrent live writer.
        writer.close();

        byte[] complete = Files.readAllBytes(walPath);
        for (int length = 0; length <= complete.length; length++) {
            Files.write(walPath, java.util.Arrays.copyOf(complete, length));

            LRIB recoveredLrib = new LRIB();
            IntentLedger recoveredIntents = new IntentLedger();
            FileWalReservationStore recovered = new FileWalReservationStore(statePath);
            try {
                recovered.restore(recoveredLrib, new NRIB(), recoveredIntents);
            } finally {
                recovered.close();
            }

            boolean hasReservation = !recoveredLrib.getAllReservations().isEmpty();
            boolean hasIntent = recoveredIntents.find("T1").isPresent();
            assertEquals(hasReservation, hasIntent, String.format(
                    "at offset %d the reservation and its intent disagreed (reservation=%s intent=%s)",
                    length, hasReservation, hasIntent));
        }
    }

    @Test
    void theLatestTransitionWins(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();
        FileWalReservationStore store = new FileWalReservationStore(statePath);

        store.append(new ReservationStore.ReservationDelta(
                List.of(), List.of(), Set.of(), List.of(intent("T1", InstallationState.INSTALLING))));
        store.append(new ReservationStore.ReservationDelta(
                List.of(), List.of(), Set.of(), List.of(intent("T1", InstallationState.INSTALLED))));

        IntentLedger restored = new IntentLedger();
        new FileWalReservationStore(statePath).restore(new LRIB(), new NRIB(), restored);

        assertEquals(InstallationState.INSTALLED, restored.find("T1").orElseThrow().getState());
    }

    @Test
    void compactionCarriesLiveIntentsForward(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();
        FileWalReservationStore store = new FileWalReservationStore(statePath);
        LRIB lrib = new LRIB();
        link(lrib, "R1", "T1");

        store.append(new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), List.of(), Set.of(),
                List.of(intent("T1", InstallationState.INSTALLED))));
        store.compact(lrib, new NRIB());

        IntentLedger restored = new IntentLedger();
        new FileWalReservationStore(statePath).restore(new LRIB(), new NRIB(), restored);

        assertEquals(InstallationState.INSTALLED, restored.find("T1").orElseThrow().getState());
    }

    @Test
    void compactionDropsTerminalIntents(@TempDir Path dir) {
        // Their capacity is already released, and a PCC still reporting such an LSP is an orphan
        // whether or not the record survives, so retaining them would only grow the log.
        String statePath = dir.resolve("state.json").toString();
        FileWalReservationStore store = new FileWalReservationStore(statePath);

        store.append(new ReservationStore.ReservationDelta(
                List.of(), List.of(), Set.of(), List.of(intent("T1", InstallationState.INSTALLED))));
        store.append(new ReservationStore.ReservationDelta(
                List.of(), List.of(), Set.of(), List.of(intent("T1", InstallationState.DELETED))));
        store.compact(new LRIB(), new NRIB());

        IntentLedger restored = new IntentLedger();
        new FileWalReservationStore(statePath).restore(new LRIB(), new NRIB(), restored);

        assertTrue(restored.find("T1").isEmpty());
    }

    @Test
    void aFormat4LogWithoutIntentsStillRestores(@TempDir Path dir) throws IOException {
        // Written by the previous release: framed transactions, no intent records. Refusing it
        // would discard reservations that are still holding real capacity.
        Path statePath = dir.resolve("state.json");
        Path walPath = dir.resolve("state.json.wal");
        String record = "{\"op\":\"put_link\",\"reservationId\":\"R1\",\"taskId\":\"T1\","
                + "\"linkId\":\"A-B\",\"sourceNodeId\":\"A\",\"destNodeId\":\"B\","
                + "\"reservedBwBps\":1000.0,\"startSec\":0.0,\"endSec\":5.0}";
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update((record + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(walPath, String.join("\n",
                "{\"op\":\"header\",\"formatVersion\":4,\"createdAtEpochMs\":1}",
                record,
                "{\"op\":\"commit\",\"txnSeq\":1,\"recordCount\":1,\"checksum\":" + crc.getValue() + "}") + "\n");

        LRIB lrib = new LRIB();
        IntentLedger intents = new IntentLedger();
        assertTrue(new FileWalReservationStore(statePath.toString()).restore(lrib, new NRIB(), intents));

        assertEquals(1, lrib.getAllReservations().size());
        assertTrue(intents.all().isEmpty(), "a format 4 log carries no intents");
    }

    @Test
    void restoringWithoutALedgerStillRecoversReservations(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();
        LRIB lrib = new LRIB();
        link(lrib, "R1", "T1");
        new FileWalReservationStore(statePath).append(new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), List.of(), Set.of(),
                List.of(intent("T1", InstallationState.INSTALLED))));

        LRIB recovered = new LRIB();
        assertTrue(new FileWalReservationStore(statePath).restore(recovered, new NRIB()));
        assertEquals(1, recovered.getAllReservations().size());
    }

    @Test
    void anEmptyDeltaWithNoIntentsStillWritesNothing(@TempDir Path dir) {
        FileWalReservationStore store = new FileWalReservationStore(dir.resolve("state.json").toString());
        store.append(new ReservationStore.ReservationDelta(List.of(), List.of(), Set.of(), List.of()));

        assertFalse(Files.exists(store.getWalPath()));
    }
}
