package net.dcn.pce.rib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Crash-boundary recovery.
 *
 * <p>A write can be interrupted at any byte: the process can die mid-write, and a host can lose
 * power after only part of the page cache reached the device. Recovery must therefore never
 * observe a fraction of a transaction. Applying half a solve would hold capacity for hops that
 * were never committed; applying half a cancellation would release some hops and keep others.
 *
 * <p>This truncates a real log at every offset and asserts the restored ledger is always the
 * result of a whole number of committed transactions.
 */
class WalTornWriteRecoveryTest {

    /** A transaction big enough to span several records, so a partial apply is observable. */
    private static ReservationStore.ReservationDelta multiRecordTransaction() {
        LRIB lrib = new LRIB();
        NRIB nrib = new NRIB();
        for (int i = 0; i < 6; i++) {
            lrib.restoreLinkReservation("R" + i, "T1", "L" + i, "A", "B", 1_000_000, i, i + 1);
        }
        for (int i = 0; i < 4; i++) {
            nrib.restoreNodeReservation("N" + i, "T1", "A" + i, 4096, i, i + 1);
        }
        return new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), nrib.getAllReservations(), Set.of());
    }

    @Test
    void truncationAtAnyByteNeverRestoresAPartialTransaction(@TempDir Path dir) throws IOException {
        Path statePath = dir.resolve("state.json");
        Path walPath = dir.resolve("state.json.wal");

        FileWalReservationStore writer = new FileWalReservationStore(statePath.toString());
        ReservationStore.ReservationDelta transaction = multiRecordTransaction();
        writer.append(transaction);

        byte[] complete = Files.readAllBytes(walPath);
        writer.close(); // the truncation below represents a process crash, which releases its lock
        int expectedLinks = transaction.addedLinkReservations().size();
        int expectedNodes = transaction.addedNodeReservations().size();

        List<String> partials = new ArrayList<>();
        for (int length = 0; length <= complete.length; length++) {
            Files.write(walPath, java.util.Arrays.copyOf(complete, length));

            LRIB lrib = new LRIB();
            NRIB nrib = new NRIB();
            int links;
            int nodes;
            FileWalReservationStore reader = new FileWalReservationStore(statePath.toString());
            try {
                reader.restore(lrib, nrib);
                links = lrib.getAllReservations().size();
                nodes = nrib.getAllReservations().size();
            } catch (RuntimeException e) {
                // Refusing to start is a defensible response to a corrupt log, but a torn tail is
                // an expected crash outcome and must not require operator intervention.
                partials.add(String.format(
                        "offset %d: startup failed with %s", length, e.getClass().getSimpleName()));
                continue;
            } finally {
                reader.close();
            }

            boolean empty = links == 0 && nodes == 0;
            boolean whole = links == expectedLinks && nodes == expectedNodes;
            if (!empty && !whole) {
                partials.add(String.format(
                        "offset %d: restored %d/%d links and %d/%d nodes",
                        length, links, expectedLinks, nodes, expectedNodes));
            }
        }

        if (!partials.isEmpty()) {
            fail(String.format(
                    "%d of %d truncation offsets restored a partial transaction or refused to start.%n%s",
                    partials.size(), complete.length + 1,
                    String.join("\n", partials.subList(0, Math.min(12, partials.size())))));
        }
    }

    @Test
    void anUnframedVersion3LogStillRestoresAndIsRewrittenFramed(@TempDir Path dir) throws IOException {
        Path statePath = dir.resolve("state.json");
        Path walPath = dir.resolve("state.json.wal");

        // A log written by the previous release: no commit markers. Refusing it, or silently
        // discarding every record as uncommitted, would drop capacity already promised to callers.
        Files.writeString(walPath, String.join("\n",
                "{\"op\":\"header\",\"formatVersion\":3,\"createdAtEpochMs\":1}",
                "{\"op\":\"put_link\",\"reservationId\":\"L1\",\"taskId\":\"T1\",\"linkId\":\"A-B\","
                        + "\"sourceNodeId\":\"A\",\"destNodeId\":\"B\",\"reservedBwBps\":1000.0,"
                        + "\"startSec\":0.0,\"endSec\":5.0}") + "\n");

        LRIB lrib = new LRIB();
        NRIB nrib = new NRIB();
        FileWalReservationStore store = new FileWalReservationStore(statePath.toString());
        assertTrue(store.restore(lrib, nrib), "a version 3 log must still restore");
        assertTrue(lrib.getAllReservations().stream()
                .anyMatch(r -> "L1".equals(r.getReservationId())));

        // Compaction rewrites it in the framed format, so the next crash is protected.
        store.compact(lrib, nrib);
        String rewritten = Files.readString(walPath);
        assertTrue(
                rewritten.contains("\"formatVersion\":" + FileWalReservationStore.FORMAT_VERSION),
                "compaction should rewrite the header at the current format version");
        assertTrue(rewritten.contains("\"op\":\"commit\""), "compaction should frame the transaction");

        LRIB reread = new LRIB();
        assertTrue(new FileWalReservationStore(statePath.toString()).restore(reread, new NRIB()));
        assertTrue(reread.getAllReservations().stream()
                .anyMatch(r -> "L1".equals(r.getReservationId())));
    }

    @Test
    void aTornTailDoesNotDiscardEarlierCommittedTransactions(@TempDir Path dir) throws IOException {
        Path statePath = dir.resolve("state.json");
        Path walPath = dir.resolve("state.json.wal");

        FileWalReservationStore writer = new FileWalReservationStore(statePath.toString());
        LRIB first = new LRIB();
        first.restoreLinkReservation("KEEP", "T0", "L0", "A", "B", 1_000_000, 0, 1);
        writer.append(new ReservationStore.ReservationDelta(
                first.getAllReservations(), List.of(), Set.of()));

        int committedLength = Files.readAllBytes(walPath).length;
        writer.append(multiRecordTransaction());
        byte[] complete = Files.readAllBytes(walPath);
        writer.close(); // the truncation below represents a process crash, which releases its lock

        // Tear anywhere inside the second transaction; the first must always survive intact.
        for (int length = committedLength; length < complete.length; length++) {
            Files.write(walPath, java.util.Arrays.copyOf(complete, length));

            LRIB lrib = new LRIB();
            NRIB nrib = new NRIB();
            FileWalReservationStore reader = new FileWalReservationStore(statePath.toString());
            try {
                reader.restore(lrib, nrib);
            } finally {
                reader.close();
            }

            assertTrue(lrib.getAllReservations().stream()
                            .anyMatch(r -> "KEEP".equals(r.getReservationId())),
                    "the earlier committed transaction was lost at offset " + length);
        }
    }

    @Test
    void aRecoveredTornTailIsHealedBeforeTheNextAppend(@TempDir Path dir) throws IOException {
        Path statePath = dir.resolve("state.json");
        Path walPath = dir.resolve("state.json.wal");

        FileWalReservationStore writer = new FileWalReservationStore(statePath.toString());
        try {
            LRIB first = new LRIB();
            first.restoreLinkReservation("KEEP", "T0", "L0", "A", "B", 1_000_000, 0, 1);
            writer.append(new ReservationStore.ReservationDelta(
                    first.getAllReservations(), List.of(), Set.of()));
            writer.append(multiRecordTransaction());
        } finally {
            writer.close();
        }

        byte[] complete = Files.readAllBytes(walPath);
        Files.write(walPath, java.util.Arrays.copyOf(complete, complete.length - 12));

        LRIB recovered = new LRIB();
        NRIB recoveredNodes = new NRIB();
        FileWalReservationStore restarted = new FileWalReservationStore(statePath.toString());
        try {
            restarted.restore(recovered, recoveredNodes);
            assertTrue(recovered.getAllReservations().stream()
                    .anyMatch(r -> "KEEP".equals(r.getReservationId())));

            LRIB next = new LRIB();
            next.restoreLinkReservation("AFTER", "T2", "L2", "A", "B", 2_000_000, 2, 3);
            restarted.append(new ReservationStore.ReservationDelta(
                    next.getAllReservations(), List.of(), Set.of()));
        } finally {
            restarted.close();
        }

        LRIB reread = new LRIB();
        new FileWalReservationStore(statePath.toString()).restore(reread, new NRIB());
        assertTrue(reread.getAllReservations().stream()
                .anyMatch(r -> "KEEP".equals(r.getReservationId())),
                "the earlier complete transaction must survive healing");
        assertTrue(reread.getAllReservations().stream()
                .anyMatch(r -> "AFTER".equals(r.getReservationId())),
                "the first post-recovery transaction must survive another restart");
        assertTrue(reread.getAllReservations().stream()
                .noneMatch(r -> "R0".equals(r.getReservationId())),
                "no record from the torn transaction may become visible");
    }
}
