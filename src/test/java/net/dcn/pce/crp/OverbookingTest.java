package net.dcn.pce.crp;

import net.dcn.pce.crp.policy.PathSelectionPolicy;
import net.dcn.pce.crp.policy.RateAssignmentPolicy;
import net.dcn.pce.crp.policy.RouteGenerationPolicy;
import net.dcn.pce.crp.policy.TaskSelectionPolicy;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.topology.FileTopologyParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R_STOCH reach-probability overbooking: each hop is reserved at the probability that the flow's
 * data reaches it. Off by default, and then every reservation is full weight as before.
 */
class OverbookingTest {

    /**
     * S1 -> A -> D and S2 -> A -> D. Each source's link carries 10 Mbit/s in [0,10) and occurs with
     * probability 0.5; the shared A-D carries 1 Mbit/s in [20,30), so one 1.25 MB flow fills it at full
     * weight. Separate source links keep the shared hop the only contended one.
     */
    private static String topology(String regime, String successProb) {
        return """
                {"regime": "%s",
                 "nodes": [
                  {"nodeId": "S1", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
                  {"nodeId": "S2", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
                  {"nodeId": "A", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
                  {"nodeId": "D", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9}],
                 "links": [
                  {"linkId": "S1-A", "sourceNodeId": "S1", "destinationNodeId": "A", "baseBandwidthBps": 1e7,
                   "propagationDelaySec": 0, "contacts": [{"startSec": 0, "endSec": 10%s}]},
                  {"linkId": "S2-A", "sourceNodeId": "S2", "destinationNodeId": "A", "baseBandwidthBps": 1e7,
                   "propagationDelaySec": 0, "contacts": [{"startSec": 0, "endSec": 10%s}]},
                  {"linkId": "A-D", "sourceNodeId": "A", "destinationNodeId": "D", "baseBandwidthBps": 1e6,
                   "propagationDelaySec": 0, "contacts": [{"startSec": 20, "endSec": 30%s}]}]}
                """.formatted(regime, successProb, successProb, successProb);
    }

    private static final String STOCHASTIC = topology("R_STOCH", ", \"successProb\": 0.5");

    private static final List<WorkloadTask> TWO_FLOWS = List.of(
            new WorkloadTask("F1", "S1", "D", 0.0, 100.0, 1.25e6),
            new WorkloadTask("F2", "S2", "D", 0.0, 100.0, 1.25e6));

    private static CRPEngine engine() {
        return new CRPEngine()
                .withFSelect(TaskSelectionPolicy.LWEEF)
                .withFGenerate(RouteGenerationPolicy.BOUNDED_CGR)
                .withFRoute(PathSelectionPolicy.EAP)
                .withFProp(RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM)
                .withUtilizationHeadroom(1.0)
                // One route per flow: this isolates the capacity accounting from redundancy.
                .withStochasticConfidenceAlpha(0.01);
    }

    private static double weightOn(CRPEngine engine, String linkId) {
        return engine.getLRIB().getAllReservations().stream()
                .filter(r -> r.getLinkId().equals(linkId)).mapToDouble(LRIB.LinkReservation::getWeight)
                .max().orElseThrow();
    }

    @Test
    void offByDefaultAndEveryReservationIsFullWeight() {
        CRPEngine engine = engine();
        assertFalse(engine.isStochasticOverbooking());

        CRPEngine.PCEComputationResult result =
                engine.solve(FileTopologyParser.parseTopologyJson(STOCHASTIC), TWO_FLOWS);

        assertEquals(1, result.getCommittedSchedules().size(), "A-D fits one flow at full weight");
        assertTrue(engine.getLRIB().getAllReservations().stream().allMatch(r -> r.getWeight() == 1.0));
    }

    @Test
    void eachHopIsReservedAtTheProbabilityItsDataArrives() {
        CRPEngine engine = engine().withStochasticOverbooking(true, 1.0);

        CRPEngine.PCEComputationResult result =
                engine.solve(FileTopologyParser.parseTopologyJson(STOCHASTIC), TWO_FLOWS);

        // S-A is always reached; A-D only if S-A occurs (0.5), so each flow counts half of it and
        // both fit. The solve also passes replay validation, which sums the same weighted load.
        assertEquals(2, result.getCommittedSchedules().size(), String.valueOf(result.getRefusalCauses()));
        assertEquals(1.0, weightOn(engine, "S1-A"));
        assertEquals(1.0, weightOn(engine, "S2-A"));
        assertEquals(0.5, weightOn(engine, "A-D"));
        assertEquals(1e6, engine.getLRIB().getAllReservations().stream()
                .filter(r -> r.getLinkId().equals("A-D")).mapToDouble(LRIB.LinkReservation::getReservedBwBps)
                .max().orElseThrow(), "the rate itself is not scaled, only its capacity weight");
    }

    @Test
    void theSafetyFactorScalesTheWeightUpToFull() {
        CRPEngine engine = engine().withStochasticOverbooking(true, 2.0);

        CRPEngine.PCEComputationResult result =
                engine.solve(FileTopologyParser.parseTopologyJson(STOCHASTIC), TWO_FLOWS);

        assertEquals(1.0, weightOn(engine, "A-D"), "min(1, 2 x 0.5)");
        assertEquals(1, result.getCommittedSchedules().size());
    }

    @Test
    void onlyStochasticContactsAreOverbooked() {
        BaseTopology deterministic = FileTopologyParser.parseTopologyJson(topology("R_DET", ""));
        CRPEngine on = engine().withStochasticOverbooking(true, 1.0);
        CRPEngine off = engine();

        CRPEngine.PCEComputationResult withFlag = on.solve(deterministic, TWO_FLOWS);
        CRPEngine.PCEComputationResult without = off.solve(deterministic, TWO_FLOWS);

        assertEquals(without.getCommittedSchedules().size(), withFlag.getCommittedSchedules().size());
        assertEquals(without.getRefusalCauses(), withFlag.getRefusalCauses());
        assertTrue(on.getLRIB().getAllReservations().stream().allMatch(r -> r.getWeight() == 1.0));
    }

    @Test
    void theSafetyFactorMustBeAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> engine().withStochasticOverbooking(true, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> engine().withStochasticOverbooking(true, Double.NaN));
    }

    @Test
    void aReservationCountsItsWeightAgainstCapacity() {
        LRIB lrib = new LRIB();
        lrib.reserveLinkCap("T", "L", "A", "B", 8e5, 0, 10, 0.25);
        assertEquals(1e6 - 2e5, lrib.getAvailableCap("L", 1e6, 0, 10), 1e-6);
        assertThrows(IllegalArgumentException.class,
                () -> lrib.reserveLinkCap("T", "L", "A", "B", 8e5, 0, 10, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> lrib.reserveLinkCap("T", "L", "A", "B", 8e5, 0, 10, 1.5));
    }
}
