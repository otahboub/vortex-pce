package net.dcn.pce.crp.policy;

import net.dcn.pce.crp.SchedulingCapacity;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.rib.NRIB;
import net.dcn.pce.topology.FileTopologyParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DATA_FLOW_EQUILIBRIUM on short scheduled contacts. A 1 MB bundle released at t = 100 s with a
 * 2,500 s deadline can go T1 -> S1 in a 400 s contact and S1 -> G in a 100 s contact, both at
 * 100 kb/s. At 0.9 headroom the route carries 90 kb/s, so each hop takes 89 s and the bundle arrives
 * at about 2,089 s. The availability-weighted estimate averages the two contacts over the whole
 * 2,400 s window, 8 Mb x (2400/400 + 2400/100) / 2400 s = 100 kb/s, which is above the 90 kb/s
 * ceiling, and the averaged policy refused the bundle on that estimate alone.
 */
class DataFlowEquilibriumScheduledContactTest {

    private static final double HEADROOM = 0.9;
    private static final WorkloadTask BUNDLE = new WorkloadTask("b", "1", "5", 100.0, 2500.0, 1_000_000.0);

    @Test
    void aBundleTheContactsCanCarryIsAdmittedAtARateThatMeetsItsDeadline() {
        BaseTopology topology = rdet(100.0);
        List<Link> route = route(topology);
        LRIB lrib = new LRIB();

        double averaged = RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM_AVERAGED
                .assignRate(BUNDLE, route, topology, lrib, new NRIB(), HEADROOM);
        assertFalse(Double.isFinite(averaged), "the averaged policy refuses on its estimate");

        double rate = RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM
                .assignRate(BUNDLE, route, topology, lrib, new NRIB(), HEADROOM);
        assertTrue(Double.isFinite(rate) && rate > 0, "the contacts can carry it, so it is admitted");
        assertTrue(rate <= HEADROOM * 100_000 + 1e-6, "never above the headroom ceiling");
        double done = SchedulingCapacity.earliestCompletionSec(BUNDLE, route, topology.getRegime(), lrib, rate);
        assertTrue(done <= BUNDLE.getEffectiveDeadlineSec(), "it completes by its deadline: " + done);
    }

    @Test
    void aBundleTheContactsCannotCarryIsStillRefused() {
        // A 50 s downlink carries 4.5 Mb at 90 kb/s, less than the 8 Mb bundle.
        BaseTopology topology = rdet(50.0);
        double rate = RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM
                .assignRate(BUNDLE, route(topology), topology, new LRIB(), new NRIB(), HEADROOM);
        assertFalse(Double.isFinite(rate), "refused when no rate completes by the deadline");
    }

    @Test
    void whereTheEstimateFitsBothPoliciesDecideAlike() {
        // A long downlink: the estimate is under the ceiling, so the averaged decision stands.
        BaseTopology topology = rdet(1500.0);
        List<Link> route = route(topology);
        for (double headroom : new double[]{0.5, 0.9, 1.0}) {
            assertEquals(
                    RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM_AVERAGED
                            .assignRate(BUNDLE, route, topology, new LRIB(), new NRIB(), headroom),
                    RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM
                            .assignRate(BUNDLE, route, topology, new LRIB(), new NRIB(), headroom),
                    1e-9, "headroom " + headroom);
        }
    }

    @Test
    void continuousLinksAreUnchanged() {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(json("R_STATIC", null));
        List<Link> route = route(topology);
        for (double deadline : new double[]{150.0, 300.0, 2500.0}) {
            WorkloadTask task = new WorkloadTask("s", "1", "5", 100.0, deadline, 1_000_000.0);
            assertEquals(
                    RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM_AVERAGED
                            .assignRate(task, route, topology, new LRIB(), new NRIB(), HEADROOM),
                    RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM
                            .assignRate(task, route, topology, new LRIB(), new NRIB(), HEADROOM),
                    1e-9, "deadline " + deadline);
        }
    }

    private static BaseTopology rdet(double downlinkSec) {
        return FileTopologyParser.parseTopologyJson(json("R_DET", downlinkSec));
    }

    private static List<Link> route(BaseTopology topology) {
        return List.of(topology.getLink("1-3"), topology.getLink("3-5"));
    }

    /** T1 (1) -> S1 (3) -> G (5) at 100 kb/s, zero propagation; continuous under R_STATIC. */
    private static String json(String regime, Double downlinkSec) {
        String always = ", \"activeContactSec\": 86400.0, \"inactivePreSec\": 0.0, \"inactivePostSec\": 0.0";
        String up = downlinkSec == null ? always : ", \"contacts\": [{\"startSec\": 600.0, \"endSec\": 1000.0}]";
        String down = downlinkSec == null ? always : String.format(Locale.ROOT,
                ", \"contacts\": [{\"startSec\": 2000.0, \"endSec\": %s}]", 2000.0 + downlinkSec);
        String node = "{\"nodeId\": \"%s\", \"serviceRateBps\": 1e7, \"reservoirCapacityBytes\": 5e8}";
        String link = "{\"linkId\": \"%s\", \"sourceNodeId\": \"%s\", \"destinationNodeId\": \"%s\", "
                + "\"baseBandwidthBps\": 100000.0, \"propagationDelaySec\": 0.0%s}";
        return "{\"regime\": \"" + regime + "\", \"nodes\": ["
                + String.format(node, "1") + "," + String.format(node, "3") + "," + String.format(node, "5")
                + "], \"links\": ["
                + String.format(Locale.ROOT, link, "1-3", "1", "3", up) + ","
                + String.format(Locale.ROOT, link, "3-5", "3", "5", down) + "]}";
    }
}
