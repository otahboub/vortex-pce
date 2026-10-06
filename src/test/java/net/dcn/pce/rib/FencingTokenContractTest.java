package net.dcn.pce.rib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fencing token is now part of the {@link ReservationStore} contract (ADR-0001, action 2), so
 * both the file WAL and a future shared backend expose the same concept. For the WAL the token is
 * its writer epoch: zero before ownership, and strictly higher for a writer that re-took a lock a
 * predecessor had held.
 */
class FencingTokenContractTest {

    private static ReservationStore.ReservationDelta oneReservation(String id) {
        LRIB lrib = new LRIB();
        lrib.restoreLinkReservation(id, "T-" + id, "L1", "A", "B", 1_000_000, 0, 5);
        return new ReservationStore.ReservationDelta(
                lrib.getAllReservations(), java.util.List.of(), java.util.Set.of(),
                java.util.List.of());
    }

    @Test
    void aFreshStoreHasNoFencingTokenUntilItTakesOwnership(@TempDir Path dir) {
        FileWalReservationStore store =
                new FileWalReservationStore(dir.resolve("state.json").toString());
        try {
            assertEquals(ReservationStore.NO_FENCING_TOKEN, store.fencingToken(),
                    "no ownership taken yet, so no token");
            store.append(oneReservation("R1"));
            assertTrue(store.fencingToken() > ReservationStore.NO_FENCING_TOKEN,
                    "taking ownership stamps a token");
        } finally {
            store.close();
        }
    }

    @Test
    void aReacquiringWriterCarriesAStrictlyHigherToken(@TempDir Path dir) {
        String path = dir.resolve("state.json").toString();
        long first;
        FileWalReservationStore a = new FileWalReservationStore(path);
        try {
            a.append(oneReservation("R1"));
            first = a.fencingToken();
        } finally {
            a.close();
        }
        FileWalReservationStore b = new FileWalReservationStore(path);
        try {
            b.append(oneReservation("R2"));
            assertTrue(b.fencingToken() > first,
                    "a writer that re-took the lock fences the one it displaced");
        } finally {
            b.close();
        }
    }

    @Test
    void theDefaultContractReturnsNoToken() {
        // A store with no notion of ownership -- the contract default -- must report exactly that.
        ReservationStore unfenced = new ReservationStore() {
            public boolean restore(LRIB lrib, NRIB nrib) { return false; }
            public void append(ReservationDelta delta) { }
            public void compact(LRIB lrib, NRIB nrib) { }
        };
        assertEquals(ReservationStore.NO_FENCING_TOKEN, unfenced.fencingToken());
    }
}
