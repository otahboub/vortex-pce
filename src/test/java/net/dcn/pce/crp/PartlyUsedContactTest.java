package net.dcn.pce.crp;

import net.dcn.pce.crp.policy.PathSelectionPolicy;
import net.dcn.pce.crp.policy.RateAssignmentPolicy;
import net.dcn.pce.crp.policy.RouteGenerationPolicy;
import net.dcn.pce.crp.policy.TaskSelectionPolicy;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.rib.NRIB;
import net.dcn.pce.topology.FileTopologyParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A contact that is fully free only briefly but half free for long enough still carries a flow.
 *
 * <p>S-D carries 1 Mbit/s in [0,100). A first flow holds half of it over [2,100), so the contact is
 * fully free only in [0,2). A second 10 Mbit flow due at 100 needs 10 s at the full rate, which the
 * contact never has, but fits at half the rate in [0,20). Route generation and path selection used to
 * judge a route only at the best instantaneous rate and dropped it.
 */
class PartlyUsedContactTest {

    private static BaseTopology topology(String regime, String successProb) {
        return FileTopologyParser.parseTopologyJson("""
                {"regime": "%s",
                 "nodes": [
                  {"nodeId": "S", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
                  {"nodeId": "D", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9}],
                 "links": [
                  {"linkId": "S-D", "sourceNodeId": "S", "destinationNodeId": "D", "baseBandwidthBps": 1e6,
                   "propagationDelaySec": 0, "contacts": [{"startSec": 0, "endSec": 100%s}]}]}
                """.formatted(regime, successProb));
    }

    /** Needs exactly half the contact over [2,100): 0.5 Mbit/s x 98 s. */
    private static final WorkloadTask HALF_FROM_2 = new WorkloadTask("F0", "S", "D", 2.0, 100.0, 0.5e6 * 98 / 8);
    /** 10 Mbit: 10 s at 1 Mbit/s, 20 s at 0.5 Mbit/s. */
    private static final WorkloadTask LATER = new WorkloadTask("F1", "S", "D", 0.0, 100.0, 1e7 / 8);

    private static CRPEngine engine() {
        return new CRPEngine()
                .withFSelect(TaskSelectionPolicy.LWEEF)
                .withFGenerate(RouteGenerationPolicy.BOUNDED_CGR)
                .withFRoute(PathSelectionPolicy.EAP)
                .withFProp(RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM)
                .withUtilizationHeadroom(1.0)
                .withStochasticConfidenceAlpha(0.01)
                .withPersistentState(true);
    }

    private static LRIB halfUsedFrom2() {
        LRIB lrib = new LRIB();
        lrib.reserveLinkCap("F0", "S-D", "S", "D", 0.5e6, 2, 100);
        return lrib;
    }

    @Test
    void theGeneratorKeepsARouteThatFitsOnlyBelowTheBestInstantRate() {
        BaseTopology deterministic = topology("R_DET", "");
        List<List<Link>> routes = RouteGenerationPolicy.BOUNDED_CGR.generateCandidateRoutes(
                LATER, deterministic, halfUsedFrom2(), new NRIB());

        assertEquals(1, routes.size());
        assertEquals("S-D", routes.get(0).get(0).getLinkId());
    }

    @Test
    void pathSelectionKeepsIt() {
        BaseTopology deterministic = topology("R_DET", "");
        List<Link> route = deterministic.getOutgoingLinks("S").stream().toList();

        assertFalse(PathSelectionPolicy.EAP.selectPath(
                LATER, List.of(route), deterministic, halfUsedFrom2(), new NRIB()).isEmpty());
    }

    @Test
    void theNewRateIsOnlyTriedWhenTheBestInstantRateFails() {
        // On a free contact the fastest rate still decides the completion estimate, as before.
        BaseTopology deterministic = topology("R_DET", "");
        List<Link> route = deterministic.getOutgoingLinks("S").stream().toList();
        LRIB free = new LRIB();

        assertEquals(1e6, SchedulingCapacity.fastestSchedulableRateBps(LATER, route, deterministic.getRegime(),
                free, 1e6), 1e-6);
        assertEquals(0.5e6, SchedulingCapacity.fastestSchedulableRateBps(LATER, route, deterministic.getRegime(),
                halfUsedFrom2(), 1e6), 1e-6);
    }

    @Test
    void theEngineFindsARateBetweenTheEquilibriumAndTheCeiling() {
        // The contact is also full in [50,60), so the whole-window equilibrium (10 Mbit over 100 s)
        // cannot fit, and the ceiling (1 Mbit/s, free only in [0,2)) cannot either; half the rate can.
        BaseTopology deterministic = topology("R_DET", "");
        List<Link> route = deterministic.getOutgoingLinks("S").stream().toList();
        CRPEngine engine = engine();
        LRIB lrib = engine.getLRIB();
        lrib.reserveLinkCap("R1", "S-D", "S", "D", 0.5e6, 2, 50);
        lrib.reserveLinkCap("R2", "S-D", "S", "D", 1e6, 50, 60);
        lrib.reserveLinkCap("R3", "S-D", "S", "D", 0.5e6, 60, 100);

        // The rate policy itself is unchanged: it offers neither rate, as before.
        assertTrue(Double.isNaN(RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM.assignRate(
                LATER, route, deterministic, lrib, new NRIB(), 1.0)));
        CRPEngine.PCEComputationResult result = engine.solve(deterministic, List.of(LATER));

        assertEquals(1, result.getCommittedSchedules().size(), String.valueOf(result.getRefusalCauses()));
        assertEquals(0.5e6, result.getCommittedSchedules().get(0).getCommittedRateBps(), 1e-6);
    }

    private static void admitsBoth(BaseTopology topology) {
        CRPEngine engine = engine();
        CRPEngine.PCEComputationResult first = engine.solve(topology, List.of(HALF_FROM_2));
        assertEquals(1, first.getCommittedSchedules().size(), String.valueOf(first.getRefusalCauses()));

        CRPEngine.PCEComputationResult second = engine.solve(topology, List.of(LATER));

        assertEquals(1, second.getCommittedSchedules().size(), String.valueOf(second.getRefusalCauses()));
        assertTrue(engine.getLRIB().getAllReservations().stream()
                .filter(r -> r.getTaskId().equals("F1"))
                .allMatch(r -> r.getReservedBwBps() <= 0.5e6 + 1e-6), "never more than the contact has left");
    }

    @Test
    void theEngineAdmitsTheSecondFlowOnScheduledContacts() {
        admitsBoth(topology("R_DET", ""));
    }

    @Test
    void theEngineAdmitsTheSecondFlowOnStochasticContactsWithOverbooking() {
        BaseTopology stochastic = topology("R_STOCH", ", \"successProb\": 0.9");
        admitsBoth(stochastic);

        CRPEngine overbooking = engine().withStochasticOverbooking(true, 1.0);
        overbooking.solve(stochastic, List.of(HALF_FROM_2));
        CRPEngine.PCEComputationResult second = overbooking.solve(stochastic, List.of(LATER));
        assertEquals(1, second.getCommittedSchedules().size(), String.valueOf(second.getRefusalCauses()));
    }
}
