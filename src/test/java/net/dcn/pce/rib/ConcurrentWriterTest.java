package net.dcn.pce.rib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two controllers must not share one state file.
 *
 * <p>The reservation log is a single-writer structure: appends carry a monotonic transaction
 * sequence, and compaction rewrites the file wholesale. Two processes pointed at the same volume
 * would interleave both, and neither would be aware of the other. The README and the reviews both
 * say deployments must stay single-replica; nothing enforced it, so the failure mode was silent
 * corruption discovered at the next restart rather than a refusal at startup.
 */
class ConcurrentWriterTest {

    private static ReservationStore.ReservationDelta delta(String reservationId, String taskId) {
        LRIB lrib = new LRIB();
        lrib.restoreLinkReservation(reservationId, taskId, "L1", "A", "B", 1_000_000, 0, 5);
        return new ReservationStore.ReservationDelta(lrib.getAllReservations(), List.of(), Set.of());
    }

    @Test
    void aSecondWriterOnTheSameLogIsRefused(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();

        FileWalReservationStore first = new FileWalReservationStore(statePath);
        first.restore(new LRIB(), new NRIB());
        first.append(delta("R1", "T1"));

        // A second controller pointed at the same volume. Refusing here is the whole point:
        // letting it proceed means two monotonic sequences interleaved in one file.
        FileWalReservationStore second = new FileWalReservationStore(statePath);
        // Reading is allowed: recovery tooling and successive restarts must be able to inspect a
        // log freely. It is the write that would interleave, so that is what is refused.
        second.restore(new LRIB(), new NRIB());
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> second.append(delta("R2", "T2")),
                "a second writer must be refused rather than allowed to interleave");

        assertTrue(refused.getMessage().toLowerCase().contains("another")
                        || refused.getMessage().toLowerCase().contains("lock"),
                "the operator needs to be told why: " + refused.getMessage());
    }

    @Test
    void theLogIsUsableAgainOnceTheFirstWriterReleasesIt(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();

        FileWalReservationStore first = new FileWalReservationStore(statePath);
        first.restore(new LRIB(), new NRIB());
        first.append(delta("R1", "T1"));
        first.close();

        // A restarted controller must be able to take over cleanly; the guard is against
        // concurrent writers, not against succession.
        FileWalReservationStore second = new FileWalReservationStore(statePath);
        LRIB recovered = new LRIB();
        second.restore(recovered, new NRIB());
        second.append(delta("R2", "T2"));

        org.junit.jupiter.api.Assertions.assertEquals(1, recovered.getAllReservations().size());
        second.close();
    }

    @Test
    void distinctLogsAreIndependent(@TempDir Path dir) {
        FileWalReservationStore a = new FileWalReservationStore(dir.resolve("a.json").toString());
        FileWalReservationStore b = new FileWalReservationStore(dir.resolve("b.json").toString());

        a.restore(new LRIB(), new NRIB());
        b.restore(new LRIB(), new NRIB());
        a.append(delta("R1", "T1"));
        b.append(delta("R2", "T2"));

        a.close();
        b.close();
    }
}
