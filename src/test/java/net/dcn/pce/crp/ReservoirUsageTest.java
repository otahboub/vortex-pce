package net.dcn.pce.crp;

import net.dcn.pce.crp.policy.RateAssignmentPolicy;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How much of each transit node's reservoir a plan actually consumes.
 *
 * <p>The response reported a network-wide maximum and a per-schedule peak. Neither says whether
 * one node is close to exhausting its buffer while the rest sit idle, and the claim that pacing
 * trades link occupancy for buffer is a claim about exactly that number.
 */
class ReservoirUsageTest {

    private static Node node(String id, double reservoirBytes) {
        return new Node(id, id, 1e12, reservoirBytes);
    }

    /** SRC -> MID -> DST where the outbound hop is closed when data first arrives. */
    private static BaseTopology intermittentOut(double reservoirBytes) {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);
        topology.addNode(node("SRC", 1e12));
        topology.addNode(node("MID", reservoirBytes));
        topology.addNode(node("DST", 1e12));
        topology.addLink(new Link("SRC-MID", "SRC", "MID",
                new LinkIntermittencyFunction(1e7, 0.0001, 10.0, 0.0, 0.0)));
        topology.addLink(new Link("MID-DST", "MID", "DST",
                new LinkIntermittencyFunction(1e7, 0.0001, 10.0, 90.0, 0.0)));
        return topology;
    }

    private static WorkloadTask task(String id, double bytes, double deadline) {
        return new WorkloadTask(id, "SRC", "DST", 0.0, deadline, bytes,
                WorkloadTask.ClassOfService.STRICT_HARD_DEADLINE);
    }

    private static CRPEngine.ReservoirUsage at(CRPEngine.PCEComputationResult result, String node) {
        return result.getReservoirUsage().stream()
                .filter(u -> u.nodeId().equals(node)).findFirst().orElseThrow();
    }

    @Test
    void everyNodeIsListedIncludingThoseHoldingNothing() {
        // A zero is evidence that a policy avoided buffering. Omitting idle nodes would make that
        // indistinguishable from a node the plan never touched.
        CRPEngine.PCEComputationResult result = new CRPEngine()
                .solve(intermittentOut(5e7), List.of(task("T1", 5_000_000, 600)));

        assertEquals(3, result.getReservoirUsage().size());
        assertEquals(0.0, at(result, "SRC").peakBufferBytes(),
                "the source holds nothing; it originates the flow");
    }

    @Test
    void aflowHeldForAContactShowsUpAsReservoirConsumption() {
        CRPEngine.PCEComputationResult result = new CRPEngine()
                .solve(intermittentOut(5e7), List.of(task("T1", 5_000_000, 600)));

        assertEquals(1, result.getCommittedFlowCount());
        CRPEngine.ReservoirUsage mid = at(result, "MID");
        assertTrue(mid.peakBufferBytes() > 0,
                "a flow waiting for the next contact must show as held bytes at the transit node");
        assertTrue(mid.utilisation() > 0 && mid.utilisation() <= 1.0,
                "utilisation should be a sane fraction, got " + mid.utilisation());
    }

    @Test
    void utilisationIsMeasuredAgainstTheNodesOwnCapacity() {
        // The same plan against a reservoir ten times larger must report the same held bytes and
        // a tenth of the utilisation. Without that, "utilisation" would just be held bytes in
        // disguise and could not be compared across topologies.
        CRPEngine.PCEComputationResult tight = new CRPEngine()
                .solve(intermittentOut(5e7), List.of(task("T1", 5_000_000, 600)));
        CRPEngine.PCEComputationResult roomy = new CRPEngine()
                .solve(intermittentOut(5e8), List.of(task("T1", 5_000_000, 600)));

        assertEquals(at(tight, "MID").peakBufferBytes(), at(roomy, "MID").peakBufferBytes(), 1.0);
        assertTrue(at(roomy, "MID").utilisation() < at(tight, "MID").utilisation() / 5,
                "a ten-times larger reservoir should report far lower utilisation");
    }

    @Test
    void pacingHoldsNoMoreBufferThanTakingTheLink() {
        // The claim under test, stated as a comparison rather than asserted in prose. Identical
        // topology and workload; only the rate policy differs.
        CRPEngine.PCEComputationResult paced = new CRPEngine()
                .withFProp(RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM)
                .solve(intermittentOut(5e8), List.of(task("T1", 5_000_000, 600)));
        CRPEngine.PCEComputationResult greedy = new CRPEngine()
                .withFProp(RateAssignmentPolicy.LINE_RATE)
                .solve(intermittentOut(5e8), List.of(task("T1", 5_000_000, 600)));

        // Both should admit on a reservoir this large; the interesting quantity is what they held.
        assertEquals(1, paced.getCommittedFlowCount());
        assertEquals(1, greedy.getCommittedFlowCount());
        assertTrue(at(paced, "MID").peakBufferBytes() <= at(greedy, "MID").peakBufferBytes(),
                "pacing must not hold more buffer than line rate: paced="
                        + at(paced, "MID").peakBufferBytes()
                        + " greedy=" + at(greedy, "MID").peakBufferBytes());
    }
}
