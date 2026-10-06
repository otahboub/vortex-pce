package net.dcn.pce.crp;

import net.dcn.pce.crp.policy.RateAssignmentPolicy;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refused workload must say which constraint refused it.
 *
 * <p>Refusals were a bare list of task ids. "Refused" collapsed a route that does not exist, a link
 * with no bandwidth left, and a transit node with no buffer left into one count — three problems
 * with three different remedies, and only the last is the one rate pacing exists to relieve.
 * Separating them is what lets a study measure that claim instead of asserting it.
 */
class RefusalCauseTest {

    private static Node node(String id, double reservoirBytes) {
        return new Node(id, id, 1e10, reservoirBytes);
    }

    private static Link link(String id, String from, String to, double bps) {
        return new Link(id, from, to, LinkIntermittencyFunction.persistentLink(bps, 0.0001));
    }

    private static WorkloadTask task(String id, double sizeBytes, double deadline) {
        return new WorkloadTask(id, "SRC", "DST", 0.0, deadline, sizeBytes,
                WorkloadTask.ClassOfService.STRICT_HARD_DEADLINE);
    }

    /**
     * SRC -> MID -> DST where the outbound hop is only intermittently available.
     *
     * <p>This is the only shape in which a transit reservoir can bind. A schedule applies one rate
     * to every hop, so under a continuously available path a flow leaves a node as fast as it
     * arrives and nothing is ever held. Buffer pressure comes from *waiting* — data reaches MID
     * and the next contact has not opened yet — which makes the reservoir an R_DET phenomenon
     * rather than a general one.
     */
    private static BaseTopology intermittentOut(double linkBps, double reservoirBytes,
                                                double activeSec, double inactiveSec) {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);
        topology.addNode(node("SRC", 1e12));
        topology.addNode(node("MID", reservoirBytes));
        topology.addNode(node("DST", 1e12));
        topology.addLink(new Link("SRC-MID", "SRC", "MID",
                new LinkIntermittencyFunction(linkBps, 0.0001, activeSec, 0.0, 0.0)));
        topology.addLink(new Link("MID-DST", "MID", "DST",
                new LinkIntermittencyFunction(linkBps, 0.0001, activeSec, inactiveSec, 0.0)));
        return topology;
    }

    /** SRC -> MID -> DST with independently chosen hop rates and a transit reservoir. */
    private static BaseTopology twoHop(double inBps, double outBps, double reservoirBytes) {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(node("SRC", 1e12));
        topology.addNode(node("MID", reservoirBytes));
        topology.addNode(node("DST", 1e12));
        topology.addLink(link("SRC-MID", "SRC", "MID", inBps));
        topology.addLink(link("MID-DST", "MID", "DST", outBps));
        return topology;
    }

    @Test
    void anUnreachableDestinationIsRefusedForWantOfARoute() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(node("SRC", 1e12));
        topology.addNode(node("DST", 1e12));
        // No link between them at all.
        CRPEngine.PCEComputationResult result =
                new CRPEngine().solve(topology, List.of(task("T1", 1_000_000, 60)));

        assertEquals(0, result.getCommittedFlowCount());
        assertEquals(RefusalCause.NO_CANDIDATE_ROUTE, result.getRefusalCauses().get("T1"));
    }

    @Test
    void aFlowTooLargeForItsDeadlineIsNotBlamedOnBuffer() {
        // The link simply cannot move this many bytes in time. Generous reservoir, so the buffer
        // must not be implicated.
        BaseTopology topology = twoHop(1e6, 1e6, 1e12);
        CRPEngine.PCEComputationResult result =
                new CRPEngine().solve(topology, List.of(task("T1", 500_000_000, 5)));

        assertEquals(0, result.getCommittedFlowCount());
        // The property that matters is not which link-side stage declined, but that a generous
        // reservoir is never blamed for a shortage of bandwidth. In this case F_route declines the
        // path before a rate is ever assigned.
        RefusalCause cause = result.getRefusalCauses().get("T1");
        assertEquals(RefusalCause.NO_PATH_SELECTED, cause);
        assertTrue(cause != RefusalCause.NODE_RESERVOIR,
                "a generous reservoir must never be blamed for a link shortage");
    }

    @Test
    void areservoirOnlyBindsWhenTheFlowMustWait() {
        // Equal hop rates leave nothing to hold: data departs MID as fast as it arrives, the
        // holding interval is zero, and a reservoir of 1 kB admits a 50 MB flow without
        // contradiction. This is the engine being right, and it is why a uniform R_STATIC
        // topology cannot exercise the buffer constraint at all.
        BaseTopology balanced = twoHop(1e9, 1e9, 1_000);
        CRPEngine.PCEComputationResult admitted =
                new CRPEngine().solve(balanced, List.of(task("T1", 50_000_000, 600)));
        assertEquals(1, admitted.getCommittedFlowCount(),
                "with no holding interval the reservoir is not consulted");
    }

    @Test
    void aflowHeldForTheNextContactIsRefusedForTheReservoir() {
        // The outbound contact is closed when the data arrives, so MID must hold the whole flow
        // until it opens. Both links can carry the rate and the deadline is generous, so the
        // transit reservoir is the only constraint left to refuse it.
        BaseTopology waiting = intermittentOut(1e7, 1_000, 10.0, 90.0);
        CRPEngine.PCEComputationResult result =
                new CRPEngine().solve(waiting, List.of(task("T1", 5_000_000, 600)));

        assertEquals(0, result.getCommittedFlowCount());
        assertEquals(RefusalCause.NODE_RESERVOIR, result.getRefusalCauses().get("T1"),
                "a flow waiting for a contact must be refused for the buffer, not the link");
    }

    @Test
    void alargerReservoirAdmitsWhatTheSmallOneRefused() {
        // Control: identical topology, identical workload, larger buffer. Without this the
        // refusal above is not pinned to the reservoir.
        BaseTopology roomy = intermittentOut(1e7, 5e7, 10.0, 90.0);
        CRPEngine.PCEComputationResult result =
                new CRPEngine().solve(roomy, List.of(task("T1", 5_000_000, 600)));

        assertEquals(1, result.getCommittedFlowCount(),
                "only the reservoir changed, so only the reservoir explains the refusal above");
        assertTrue(result.getRefusalCauses().isEmpty());
    }

    @Test
    void anAdmittedFlowRecordsNoCause() {
        BaseTopology topology = twoHop(1e9, 1e9, 1e12);
        CRPEngine.PCEComputationResult result =
                new CRPEngine().solve(topology, List.of(task("T1", 1_000_000, 600)));

        assertEquals(1, result.getCommittedFlowCount());
        assertTrue(result.getRefusalCauses().isEmpty());
    }
}
