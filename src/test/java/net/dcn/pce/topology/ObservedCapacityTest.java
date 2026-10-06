package net.dcn.pce.topology;

import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An observation reaches the model without disturbing anything else about the link.
 *
 * <p>The variance study measured this engine planning against a declared mean while the link
 * drifted below it. This is the first half of closing that loop: what the network turned out to
 * have becomes what the next solve believes.
 */
class ObservedCapacityTest {

    private static BaseTopology topology(ContactPlan plan) {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(plan == null ? ContactRegime.R_STATIC : ContactRegime.R_DET);
        topology.addNode(new Node("A", "A", 1e9, 1e6, "10.0.0.1"));
        topology.addNode(new Node("B", "B", 1e9, 1e6, "10.0.0.2"));
        LinkIntermittencyFunction lif =
                new LinkIntermittencyFunction(1e8, 0.005, 20.0, 0.0, 20.0);
        topology.addLink(plan == null
                ? new Link("A-B", "A", "B", lif)
                : new Link("A-B", "A", "B", LinkIntermittencyFunction.persistentLink(1e8, 0.005), plan));
        return topology;
    }

    @Test
    void theObservedCapacityReplacesTheDeclaredOne() {
        BaseTopology updated = ObservedCapacity.withLinkCapacity(topology(null), "A-B", 4.2e7);

        assertEquals(4.2e7, updated.getLink("A-B").getLif().getBaseBandwidthBps());
    }

    @Test
    void everythingElseAboutTheLinkSurvives() {
        // Observing that a link is slower says nothing about when it is available. Dropping the
        // duty cycle here would quietly turn an intermittent link into a persistent one.
        BaseTopology updated = ObservedCapacity.withLinkCapacity(topology(null), "A-B", 4.2e7);
        LinkIntermittencyFunction lif = updated.getLink("A-B").getLif();

        assertEquals(0.005, lif.getPropagationDelaySec());
        assertEquals(20.0, lif.getActiveContactSec());
        assertEquals(20.0, lif.getInactivePostSec());
    }

    @Test
    void aContactPlanIsCarriedAcross() {
        ContactPlan plan = new ContactPlan(List.of(
                new ContactWindow(0, 20), new ContactWindow(60, 80)));
        BaseTopology updated = ObservedCapacity.withLinkCapacity(topology(plan), "A-B", 4.2e7);

        assertTrue(updated.getLink("A-B").getContactPlan().isPresent(),
                "a calendared link must not silently become a persistent one");
        assertEquals(2, updated.getLink("A-B").getContactPlan().orElseThrow().getWindows().size());
    }

    @Test
    void theOriginalTopologyIsUntouched() {
        // A solve in flight keeps reasoning about the topology it started with.
        BaseTopology original = topology(null);
        ObservedCapacity.withLinkCapacity(original, "A-B", 4.2e7);

        assertEquals(1e8, original.getLink("A-B").getLif().getBaseBandwidthBps());
    }

    @Test
    void unusableObservationsAreRefused() {
        BaseTopology original = topology(null);
        assertThrows(IllegalArgumentException.class,
                () -> ObservedCapacity.withLinkCapacity(original, "nope", 4.2e7));
        for (double bad : new double[]{0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ObservedCapacity.withLinkCapacity(original, "A-B", bad));
        }
    }
}
