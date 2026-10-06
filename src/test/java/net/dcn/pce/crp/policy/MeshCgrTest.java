package net.dcn.pce.crp.policy;

import net.dcn.pce.crp.SchedulingCapacity;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.PinnedRoute;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.rib.NRIB;
import net.dcn.pce.topology.FileTopologyParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshCgrTest {

    /**
     * S reaches D two ways: S-A-D (S-A passes at [0,10) and [50,60); A-D at [20,30) and [70,80)) and
     * S-B-D (S-B at [5,15); B-D at [40,45)). Six contacts, each on some S-to-D path.
     */
    private static final String TOPOLOGY = """
            {"regime": "R_STOCH",
             "nodes": [
              {"nodeId": "S", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
              {"nodeId": "A", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
              {"nodeId": "B", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
              {"nodeId": "D", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9}],
             "links": [
              {"linkId": "S-A", "sourceNodeId": "S", "destinationNodeId": "A", "baseBandwidthBps": 1e6,
               "propagationDelaySec": 0, "contacts": [{"startSec": 0, "endSec": 10, "successProb": 0.9},
                                                      {"startSec": 50, "endSec": 60, "successProb": 0.9}]},
              {"linkId": "A-D", "sourceNodeId": "A", "destinationNodeId": "D", "baseBandwidthBps": 1e6,
               "propagationDelaySec": 0, "contacts": [{"startSec": 20, "endSec": 30, "successProb": 0.9},
                                                      {"startSec": 70, "endSec": 80, "successProb": 0.9}]},
              {"linkId": "S-B", "sourceNodeId": "S", "destinationNodeId": "B", "baseBandwidthBps": 1e6,
               "propagationDelaySec": 0, "contacts": [{"startSec": 5, "endSec": 15, "successProb": 0.9}]},
              {"linkId": "B-D", "sourceNodeId": "B", "destinationNodeId": "D", "baseBandwidthBps": 1e6,
               "propagationDelaySec": 0, "contacts": [{"startSec": 40, "endSec": 45, "successProb": 0.9}]}]}
            """;

    private static final WorkloadTask TASK = new WorkloadTask("t", "S", "D", 0.0, 100.0, 1000.0);

    private static List<List<Link>> generate(BaseTopology topology, String level) {
        return RouteGenerationPolicy.meshCgr(level, 100)
                .generateCandidateRoutes(TASK, topology, new LRIB(), new NRIB());
    }

    @Test
    void pinnedHopDepartsNoEarlierThanItsPin() {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(TOPOLOGY);
        List<Link> plain = List.of(topology.getLink("S-A"), topology.getLink("A-D"));
        PinnedRoute laterPass = new PinnedRoute(plain, new double[]{50.0, 0.0});
        double unpinned = SchedulingCapacity.earliestCompletionSec(
                TASK, plain, ContactRegime.R_STOCH, new LRIB(), 1e6);
        double pinned = SchedulingCapacity.earliestCompletionSec(
                TASK, laterPass, ContactRegime.R_STOCH, new LRIB(), 1e6);
        assertTrue(unpinned < 31, "earliest passes: S-A at 0, A-D at 20");
        assertTrue(pinned >= 70, "pinned to the later S-A pass, so A-D must use its later pass too");
    }

    @Test
    void allLevelCommitsARouteThroughEveryContactOnSomePath() {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(TOPOLOGY);
        List<List<Link>> routes = generate(topology, "ALL");
        assertEquals(6, routes.size(), "one route per contact: 2 + 2 + 1 + 1");
        assertTrue(routes.stream().anyMatch(r -> r.get(0).getLinkId().equals("S-A")
                && PinnedRoute.notBefore(r, 0) == 50.0), "the later S-A pass is reachable only by pinning");
    }

    @Test
    void detourLevelAddsALaterPassOfTheSameLink() {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(TOPOLOGY);
        List<List<Link>> base = generate(topology, "0");
        List<List<Link>> withDetours = generate(topology, "1");
        assertTrue(withDetours.size() > base.size());
        assertTrue(withDetours.stream().anyMatch(r -> r.get(0).getLinkId().equals("S-A")
                && PinnedRoute.notBefore(r, 0) == 50.0));
    }

    @Test
    void levelZeroIsTheCutAnchoredPlan() {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(TOPOLOGY);
        assertEquals(RouteGenerationPolicy.CUT_ANCHORED_CGR
                        .generateCandidateRoutes(TASK, topology, new LRIB(), new NRIB()).size(),
                generate(topology, "0").size());
    }
}
