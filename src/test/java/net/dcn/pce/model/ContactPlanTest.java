package net.dcn.pce.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContactPlanTest {

    @Test
    void findsFiniteNonPeriodicWindowsWithoutRepeatingThem() {
        ContactPlan plan = new ContactPlan(List.of(
                new ContactWindow(2, 4),
                new ContactWindow(10, 13)));

        assertEquals(new ContactWindow(3, 4),
                plan.findWindowAtOrAfter(3, 20).orElseThrow());
        assertEquals(new ContactWindow(10, 12),
                plan.findWindowAtOrAfter(5, 12).orElseThrow());
        assertTrue(plan.findWindowAtOrAfter(13, 20).isEmpty());
        assertEquals(3, plan.activeDurationBetween(3, 12), 1e-9);
    }

    @Test
    void validatesOrderingOverlapAndCardinality() {
        assertThrows(IllegalArgumentException.class, () -> new ContactPlan(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ContactPlan(List.of(
                new ContactWindow(5, 7), new ContactWindow(2, 3))));
        assertThrows(IllegalArgumentException.class, () -> new ContactPlan(List.of(
                new ContactWindow(2, 5), new ContactWindow(4, 6))));
        assertThrows(IllegalArgumentException.class, () -> new Link("L", "A", "B",
                new LinkIntermittencyFunction(100, 0, 2, 1, 1),
                new ContactPlan(List.of(new ContactWindow(2, 5)))));
    }
}
