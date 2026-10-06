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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corruption before the physical tail must fail closed.
 *
 * <p>Truncation and corruption are different faults. A torn tail is the expected result of a
 * crash and the records after it do not exist, so discarding them loses nothing. A damaged record
 * in the middle of a file that still has valid transactions after it is media or operator damage:
 * silently stopping there drops reservations that were committed and acknowledged, and the
 * controller then re-admits capacity the network has already promised.
 */
class WalCorruptionFailClosedTest {

    private static Path seedThreeTransactions(Path dir) {
        Path statePath = dir.resolve("state.json");
        FileWalReservationStore store = new FileWalReservationStore(statePath.toString());
        try {
            for (int txn = 0; txn < 3; txn++) {
                LRIB lrib = new LRIB();
                lrib.restoreLinkReservation("R" + txn, "T" + txn, "L" + txn, "A", "B", 1_000_000, txn, txn + 1);
                store.append(new ReservationStore.ReservationDelta(
                        lrib.getAllReservations(), List.of(), Set.of()));
            }
        } finally {
            // The following mutations simulate an offline/crashed writer, which no longer owns
            // the process-local WAL lock.
            store.close();
        }
        return statePath;
    }

    private static List<String> walLines(Path dir) throws IOException {
        return new ArrayList<>(Files.readAllLines(dir.resolve("state.json.wal"), StandardCharsets.UTF_8));
    }

    private static void writeWal(Path dir, List<String> lines) throws IOException {
        Files.writeString(dir.resolve("state.json.wal"), String.join("\n", lines) + "\n");
    }

    private static void assertFailsClosed(Path statePath, String fault) {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> new FileWalReservationStore(statePath.toString()).restore(new LRIB(), new NRIB()),
                fault + " must stop startup rather than silently dropping committed reservations");
        assertTrue(error.getMessage().toLowerCase().contains("corrupt")
                        || error.getMessage().toLowerCase().contains("sequence"),
                "the operator needs to know this is corruption: " + error.getMessage());
    }

    @Test
    void aBitFlipInsideAnEarlierTransactionFailsClosed(@TempDir Path dir) throws IOException {
        Path statePath = seedThreeTransactions(dir);
        List<String> lines = walLines(dir);

        // Flip a byte inside the first transaction's data record; two valid transactions follow.
        String damaged = lines.get(1).replace("\"reservedBwBps\":1000000.0", "\"reservedBwBps\":9000000.0");
        assertTrue(!damaged.equals(lines.get(1)), "the test fixture should have modified the record");
        lines.set(1, damaged);
        writeWal(dir, lines);

        assertFailsClosed(statePath, "a bit flip in a committed record");
    }

    @Test
    void aMissingRecordLineFailsClosed(@TempDir Path dir) throws IOException {
        Path statePath = seedThreeTransactions(dir);
        List<String> lines = walLines(dir);

        lines.remove(1); // drop the first transaction's only data record, keeping its commit marker
        writeWal(dir, lines);

        assertFailsClosed(statePath, "a missing record line");
    }

    @Test
    void aMissingCommitMarkerMidFileFailsClosed(@TempDir Path dir) throws IOException {
        Path statePath = seedThreeTransactions(dir);
        List<String> lines = walLines(dir);

        // Remove the first commit marker. Its records would otherwise be absorbed into the next
        // transaction, whose checksum then covers records it never committed.
        int commitIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("\"op\":\"commit\"")) {
                commitIndex = i;
                break;
            }
        }
        assertTrue(commitIndex > 0, "fixture should contain a commit marker");
        lines.remove(commitIndex);
        writeWal(dir, lines);

        assertFailsClosed(statePath, "a missing commit marker");
    }

    @Test
    void anUnparseableLineFollowedByValidTransactionsFailsClosed(@TempDir Path dir) throws IOException {
        Path statePath = seedThreeTransactions(dir);
        List<String> lines = walLines(dir);

        lines.set(1, "{\"op\":\"put_link\",\"reservationId\":\"R0\",TRUNCATED");
        writeWal(dir, lines);

        // Identical in shape to a torn tail, but valid committed transactions follow it, so it
        // cannot be a crash artifact.
        assertFailsClosed(statePath, "an unparseable line with valid data after it");
    }

    @Test
    void aReorderedTransactionFailsClosed(@TempDir Path dir) throws IOException {
        Path statePath = seedThreeTransactions(dir);
        List<String> lines = walLines(dir);

        // Swap the first two transactions (record + commit pairs) so sequence numbers regress.
        List<String> reordered = new ArrayList<>();
        reordered.add(lines.get(0));
        reordered.addAll(lines.subList(3, 5));
        reordered.addAll(lines.subList(1, 3));
        reordered.addAll(lines.subList(5, lines.size()));
        writeWal(dir, reordered);

        assertFailsClosed(statePath, "reordered transactions");
    }

    @Test
    void aGenuineTornTailIsStillToleratedWithoutOperatorIntervention(@TempDir Path dir) throws IOException {
        Path statePath = seedThreeTransactions(dir);
        Path walPath = dir.resolve("state.json.wal");
        byte[] complete = Files.readAllBytes(walPath);

        // Cut inside the final transaction: nothing valid follows, so this is a crash, not damage.
        Files.write(walPath, java.util.Arrays.copyOf(complete, complete.length - 12));

        LRIB lrib = new LRIB();
        FileWalReservationStore recovered = new FileWalReservationStore(statePath.toString());
        try {
            recovered.restore(lrib, new NRIB());
        } finally {
            recovered.close();
        }

        assertTrue(lrib.getAllReservations().size() >= 2,
                "the two fully committed transactions must survive a torn tail");
    }
}
