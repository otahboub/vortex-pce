package net.dcn.pce.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinkIntermittencyFunctionTest {

    private final LinkIntermittencyFunction lif =
            new LinkIntermittencyFunction(1_000, 0, 2, 3, 5);

    @Test
    void locatesTheCurrentOrNextPeriodicContact() {
        assertEquals(3, lif.findActiveWindowAtOrAfter(0, 20).orElseThrow().startSec(), 1e-9);
        assertEquals(4, lif.findActiveWindowAtOrAfter(4, 20).orElseThrow().startSec(), 1e-9);
        assertEquals(13, lif.findActiveWindowAtOrAfter(5, 20).orElseThrow().startSec(), 1e-9);
    }

    @Test
    void completeIntervalMustRemainInsideOneContact() {
        assertTrue(lif.containsActiveInterval(3, 5));
        assertFalse(lif.containsActiveInterval(4, 6));
        assertFalse(lif.containsActiveInterval(2, 4));
    }

    @Test
    void contiguousSearchMovesToTheNextContactWhenNeeded() {
        assertEquals(13, lif.findEarliestActiveIntervalStart(4, 20, 2)
                .orElseThrow(), 1e-9);
        assertTrue(lif.findEarliestActiveIntervalStart(4, 14, 2).isEmpty());
    }

    @Test
    void persistentLinksDoNotCreateArtificialCycleBoundaries() {
        LinkIntermittencyFunction persistent =
                LinkIntermittencyFunction.persistentLink(1_000, 0);
        assertTrue(persistent.containsActiveInterval(0.5, 10.5));
        assertEquals(0.5, persistent.findEarliestActiveIntervalStart(0.5, 20, 10)
                .orElseThrow(), 1e-9);
    }
}
