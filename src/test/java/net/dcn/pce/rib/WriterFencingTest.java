package net.dcn.pce.rib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A superseded writer must find out on its next write, not at the next restart.
 *
 * <p>The file lock guards against accidental co-tenancy, and it is least trustworthy exactly where
 * shared state would live: {@code FileLock} is advisory, and over NFS, EFS and similar its
 * behaviour ranges from unreliable to silently absent. Two controllers on a shared volume can each
 * believe they hold it, interleave transactions, and produce a sequence gap that only surfaces at
 * the next restart — long after the writes that caused it.
 *
 * <p>An epoch in the lock file turns that from undetected corruption into a refusal on the very
 * next append. It does not make two controllers safe and it is not leader election; it makes the
 * unsafe configuration stop instead of continuing quietly.
 */
class WriterFencingTest {

    private static Path lockFile(Path dir) {
        return dir.resolve("state.json.wal.lock");
    }

    private static Path epochFile(Path dir) {
        return dir.resolve("state.json.wal.lock.generation");
    }

    private static ReservationStore.ReservationDelta oneReservation(String id) {
        LRIB lrib = new LRIB();
        lrib.restoreLinkReservation(id, "T-" + id, "L1", "A", "B", 1_000_000, 0, 5);
        return new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), java.util.List.of(), java.util.Set.of(),
                java.util.List.of());
    }

    @Test
    void aWriterRecordsAnEpochWhenItTakesOwnership(@TempDir Path dir) {
        FileWalReservationStore store =
                new FileWalReservationStore(dir.resolve("state.json").toString());
        try {
            store.append(oneReservation("R1"));
            assertTrue(Files.exists(epochFile(dir)), "taking ownership should stamp an epoch");
        } finally {
            store.close();
        }
    }

    @Test
    void asupersededWriterRefusesItsNextWrite(@TempDir Path dir) throws Exception {
        FileWalReservationStore first =
                new FileWalReservationStore(dir.resolve("state.json").toString());
        try {
            first.append(oneReservation("R1"));

            // Another writer takes ownership. On a network filesystem this is what a lock that
            // silently failed to exclude looks like from the first controller's point of view:
            // nothing it can observe changed, except the epoch.
            long stolen = Long.parseLong(
                    Files.readString(epochFile(dir), StandardCharsets.UTF_8).trim()) + 1;
            Files.writeString(epochFile(dir), Long.toString(stolen), StandardCharsets.UTF_8);

            IllegalStateException fenced = assertThrows(IllegalStateException.class,
                    () -> first.append(oneReservation("R2")),
                    "a superseded writer must not keep appending");
            assertTrue(fenced.getMessage().contains("superseded"), fenced.getMessage());
            assertTrue(fenced.getMessage().contains("Stop this instance"), fenced.getMessage());
        } finally {
            first.close();
        }
    }

    @Test
    void asuccessorTakesTheNextEpoch(@TempDir Path dir) throws Exception {
        FileWalReservationStore first =
                new FileWalReservationStore(dir.resolve("state.json").toString());
        first.append(oneReservation("R1"));
        String firstEpoch = Files.readString(epochFile(dir), StandardCharsets.UTF_8).trim();
        first.close();

        // A legitimate restart: the lock was released, so the successor is the only writer.
        FileWalReservationStore second =
                new FileWalReservationStore(dir.resolve("state.json").toString());
        try {
            second.append(oneReservation("R2"));
            String secondEpoch = Files.readString(epochFile(dir), StandardCharsets.UTF_8).trim();
            assertTrue(Long.parseLong(secondEpoch) > Long.parseLong(firstEpoch),
                    "each owner takes a higher epoch: " + firstEpoch + " -> " + secondEpoch);
        } finally {
            second.close();
        }
    }

    @Test
    void adamagedEpochFileDoesNotStopTheLegitimateOwner(@TempDir Path dir) throws Exception {
        // The lock still says this controller is the owner. Refusing here would make a damaged
        // sidecar an outage for the instance that is, as far as exclusion goes, correct.
        FileWalReservationStore store =
                new FileWalReservationStore(dir.resolve("state.json").toString());
        try {
            store.append(oneReservation("R1"));
            Files.writeString(epochFile(dir), "not a number", StandardCharsets.UTF_8);
            store.append(oneReservation("R2"));
        } finally {
            store.close();
        }
    }
}
