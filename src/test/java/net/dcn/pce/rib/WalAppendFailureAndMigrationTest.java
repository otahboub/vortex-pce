package net.dcn.pce.rib;

import net.dcn.pce.install.IntentLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalAppendFailureAndMigrationTest {

    private static ReservationStore.ReservationDelta delta(String reservationId, String taskId) {
        LRIB lrib = new LRIB();
        lrib.restoreLinkReservation(reservationId, taskId, "L1", "A", "B", 1_000_000, 0, 5);
        return new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), List.of(), Set.of());
    }

    /**
     * A transient write failure must not corrupt the sequence series.
     *
     * <p>The append path advanced {@code lastTxnSeq} before the durable write. If the write then
     * failed, the number was already spent, so the next successful append left a gap — and replay
     * treats a gap as corruption and refuses to start. A storage blip that the caller correctly
     * saw as a failed transaction would become a controller that will not boot.
     */
    @Test
    void aFailedAppendDoesNotPoisonTheSequenceSeries(@TempDir Path dir) throws IOException {
        String statePath = dir.resolve("state.json").toString();
        Path walPath = dir.resolve("state.json.wal");
        FileWalReservationStore store = new FileWalReservationStore(statePath);

        store.append(delta("R1", "T1"));
        byte[] afterFirst = Files.readAllBytes(walPath);

        // Injecting the failure by replacing the log with a directory rather than by permissions:
        // these tests run as root in CI, where a read-only bit is not enforced.
        Files.delete(walPath);
        Files.createDirectory(walPath);
        assertThrows(RuntimeException.class, () -> store.append(delta("R2", "T2")),
                "a write to an unusable log must fail");

        // The storage problem clears and the log is intact, as after a transient volume error.
        Files.delete(walPath);
        Files.write(walPath, afterFirst);

        // The storage problem has cleared; the controller must be able to continue.
        assertDoesNotThrow(() -> store.append(delta("R3", "T3")));

        LRIB restored = new LRIB();
        assertDoesNotThrow(
                () -> new FileWalReservationStore(statePath).restore(restored, new NRIB()),
                "a recovered write failure must not leave an unreadable log");
        assertEquals(2, restored.getAllReservations().size(),
                "the two committed transactions should both survive");
    }

    /**
     * A version-3 log must be migrated before it accepts framed appends.
     *
     * <p>Restore accepts the legacy unframed format and disables commit enforcement for it. If the
     * file keeps its version-3 header while new framed transactions are appended to it, the next
     * restore reads those records under unframed rules, which is the torn-write exposure the
     * framing work closed.
     */
    @Test
    void aLegacyLogIsMigratedBeforeItAcceptsNewTransactions(@TempDir Path dir) throws IOException {
        Path statePath = dir.resolve("state.json");
        Path walPath = dir.resolve("state.json.wal");

        String record = "{\"op\":\"put_link\",\"reservationId\":\"OLD\",\"taskId\":\"T0\","
                + "\"linkId\":\"A-B\",\"sourceNodeId\":\"A\",\"destNodeId\":\"B\","
                + "\"reservedBwBps\":1000.0,\"startSec\":0.0,\"endSec\":5.0}";
        Files.writeString(walPath, String.join("\n",
                "{\"op\":\"header\",\"formatVersion\":3,\"createdAtEpochMs\":1}",
                record) + "\n");

        FileWalReservationStore store = new FileWalReservationStore(statePath.toString());
        LRIB lrib = new LRIB();
        assertTrue(store.restore(lrib, new NRIB(), new IntentLedger()));
        assertEquals(1, lrib.getAllReservations().size());

        // Startup must leave the log in the current format, not merely be able to read the old one.
        String afterRestore = Files.readString(walPath);
        assertTrue(afterRestore.contains("\"formatVersion\":" + FileWalReservationStore.FORMAT_VERSION),
                "restore should migrate a legacy log before accepting mutations, header was:\n"
                        + afterRestore.lines().findFirst().orElse("(empty)"));

        store.append(delta("NEW", "T1"));

        LRIB reread = new LRIB();
        assertDoesNotThrow(
                () -> new FileWalReservationStore(statePath.toString()).restore(reread, new NRIB()),
                "a migrated log must still replay cleanly after new appends");
        assertEquals(2, reread.getAllReservations().size());
    }

    /** A migrated legacy log must enforce framing, so a torn append cannot apply partially. */
    @Test
    void afterMigrationATornAppendIsNotPartiallyApplied(@TempDir Path dir) throws IOException {
        Path statePath = dir.resolve("state.json");
        Path walPath = dir.resolve("state.json.wal");
        String record = "{\"op\":\"put_link\",\"reservationId\":\"OLD\",\"taskId\":\"T0\","
                + "\"linkId\":\"A-B\",\"sourceNodeId\":\"A\",\"destNodeId\":\"B\","
                + "\"reservedBwBps\":1000.0,\"startSec\":0.0,\"endSec\":5.0}";
        Files.writeString(walPath, String.join("\n",
                "{\"op\":\"header\",\"formatVersion\":3,\"createdAtEpochMs\":1}", record) + "\n");

        FileWalReservationStore store = new FileWalReservationStore(statePath.toString());
        store.restore(new LRIB(), new NRIB(), new IntentLedger());

        LRIB multi = new LRIB();
        for (int i = 0; i < 5; i++) {
            multi.restoreLinkReservation("M" + i, "T9", "L" + i, "A", "B", 1_000_000, i, i + 1);
        }
        store.append(new ReservationStore.ReservationDelta(
                multi.getAllReservations(), List.of(), Set.of()));

        byte[] complete = Files.readAllBytes(walPath);
        for (int length = 0; length <= complete.length; length++) {
            Files.write(walPath, java.util.Arrays.copyOf(complete, length));
            LRIB recovered = new LRIB();
            try {
                new FileWalReservationStore(statePath.toString()).restore(recovered, new NRIB());
            } catch (RuntimeException expected) {
                continue; // failing closed is acceptable; a partial apply is not
            }
            long committed = recovered.getAllReservations().stream()
                    .filter(r -> "T9".equals(r.getTaskId())).count();
            assertTrue(committed == 0 || committed == 5,
                    "offset " + length + " applied " + committed + " of 5 records from one transaction");
        }
    }

    private static long crcOf(String... lines) {
        CRC32 crc = new CRC32();
        for (String line : lines) {
            crc.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return crc.getValue();
    }
}
