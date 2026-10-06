package net.dcn.pce.rib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReservationIdIndexTest {
    @Test
    void linkIdsRemainUniqueAndAreReleasedByEveryRemovalPath() {
        LRIB lrib = new LRIB();
        restoreLink(lrib, "same", "T1", 0, 1);
        assertThrows(IllegalArgumentException.class, () -> restoreLink(lrib, "same", "T2", 1, 2));
        lrib.removeReservationsForTask("T1");
        assertDoesNotThrow(() -> restoreLink(lrib, "same", "T2", 1, 2));
        lrib.pruneReservationsEndingAtOrBefore(2);
        assertDoesNotThrow(() -> restoreLink(lrib, "same", "T3", 2, 3));
        lrib.clear();
        assertDoesNotThrow(() -> restoreLink(lrib, "same", "T4", 3, 4));
    }

    @Test
    void nodeIdsRemainUniqueAndAreReleasedByEveryRemovalPath() {
        NRIB nrib = new NRIB();
        restoreNode(nrib, "same", "T1", 0, 1);
        assertThrows(IllegalArgumentException.class, () -> restoreNode(nrib, "same", "T2", 1, 2));
        nrib.removeReservationsForTask("T1");
        assertDoesNotThrow(() -> restoreNode(nrib, "same", "T2", 1, 2));
        nrib.pruneReservationsEndingAtOrBefore(2);
        assertDoesNotThrow(() -> restoreNode(nrib, "same", "T3", 2, 3));
        nrib.clear();
        assertDoesNotThrow(() -> restoreNode(nrib, "same", "T4", 3, 4));
    }

    private static void restoreLink(LRIB lrib, String id, String task, double start, double end) {
        lrib.restoreLinkReservation(id, task, "L1", "A", "B", 1_000, start, end);
    }

    private static void restoreNode(NRIB nrib, String id, String task, double start, double end) {
        nrib.restoreNodeReservation(id, task, "B", 100, start, end);
    }
}
