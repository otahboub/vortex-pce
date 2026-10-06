package net.dcn.pce.rib;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The peak concurrent aggregate reservation on a link, which capacity feedback must not accept an
 * observation below. The bug this guards against summed nothing -- it took the largest single
 * reservation -- so two overlapping 400 Mbps reservations reported 400, and a 500 Mbps observation
 * was wrongly accepted while 800 Mbps was committed at the overlap.
 */
class PeakReservedBandwidthTest {

    private final AtomicInteger ids = new AtomicInteger(1);

    private void reserve(LRIB lrib, double bwBps, double startSec, double endSec) {
        int id = ids.getAndIncrement();
        lrib.restoreLinkReservation("R" + id, "T" + id, "L1", "A", "B", bwBps, startSec, endSec);
    }

    @Test
    void overlappingReservationsSumAtTheOverlap() {
        LRIB lrib = new LRIB();
        reserve(lrib, 400e6, 0, 10);
        reserve(lrib, 400e6, 5, 15);      // overlaps [5,10)
        assertEquals(800e6, lrib.getPeakReservedBps("L1"), 1.0,
                "the overlap commits both reservations at once");
    }

    @Test
    void adjacentReservationsDoNotSum() {
        LRIB lrib = new LRIB();
        reserve(lrib, 400e6, 0, 10);
        reserve(lrib, 400e6, 10, 20);     // [0,10) and [10,20) touch but do not overlap
        assertEquals(400e6, lrib.getPeakReservedBps("L1"), 1.0,
                "half-open intervals meeting at an instant never coincide");
    }

    @Test
    void disjointReservationsTakeTheLarger() {
        LRIB lrib = new LRIB();
        reserve(lrib, 300e6, 0, 5);
        reserve(lrib, 700e6, 20, 25);
        assertEquals(700e6, lrib.getPeakReservedBps("L1"), 1.0,
                "no overlap, so the peak is the largest single reservation");
    }

    @Test
    void threeWayOverlapSumsAll() {
        LRIB lrib = new LRIB();
        reserve(lrib, 100e6, 0, 30);
        reserve(lrib, 200e6, 5, 25);
        reserve(lrib, 400e6, 10, 20);     // all three coincide on [10,20)
        assertEquals(700e6, lrib.getPeakReservedBps("L1"), 1.0);
    }

    @Test
    void noReservationsIsZero() {
        assertEquals(0.0, new LRIB().getPeakReservedBps("L1"), 1.0);
    }
}
