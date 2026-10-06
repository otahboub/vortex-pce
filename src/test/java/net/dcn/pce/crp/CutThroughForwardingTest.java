package net.dcn.pce.crp;

import net.dcn.pce.crp.policy.PathSelectionPolicy;
import net.dcn.pce.crp.policy.RateAssignmentPolicy;
import net.dcn.pce.crp.policy.RouteGenerationPolicy;
import net.dcn.pce.crp.policy.TaskSelectionPolicy;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.rib.NRIB;
import net.dcn.pce.topology.FileTopologyParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A continuous (R_STATIC) backbone forwards data as it arrives, so a route's hops overlap. The engine
 * used to treat every hop as store-and-forward, which charged each flow |route| times the rate it
 * needs and predicted |route| times its completion time.
 */
class CutThroughForwardingTest {

    /** The paced-vs-unpaced study's testbed: senders, one 100 Mbit/s router hop, receiver. */
    private static final String PACED_STUDY_TOPOLOGY = """
            {"regime": "R_STATIC",
             "nodes": [
              {"nodeId": "SRC", "serviceRateBps": 1e10, "reservoirCapacityBytes": 1e9},
              {"nodeId": "RTR", "serviceRateBps": 1e8, "reservoirCapacityBytes": 100000},
              {"nodeId": "DST", "serviceRateBps": 1e10, "reservoirCapacityBytes": 1e9}],
             "links": [
              {"linkId": "SRC-RTR", "sourceNodeId": "SRC", "destinationNodeId": "RTR",
               "baseBandwidthBps": 1e10, "propagationDelaySec": 0.0001,
               "activeContactSec": 1.0, "inactivePreSec": 0.0, "inactivePostSec": 0.0},
              {"linkId": "RTR-DST", "sourceNodeId": "RTR", "destinationNodeId": "DST",
               "baseBandwidthBps": 1e8, "propagationDelaySec": 0.0001,
               "activeContactSec": 1.0, "inactivePreSec": 0.0, "inactivePostSec": 0.0}]}
            """;

    private static final double MB = 1e6;

    private static List<Link> route(BaseTopology topology, String... linkIds) {
        return java.util.Arrays.stream(linkIds).map(topology::getLink).toList();
    }

    private static CRPEngine engine() {
        return new CRPEngine()
                .withFSelect(TaskSelectionPolicy.LWEEF)
                .withFGenerate(RouteGenerationPolicy.BFS_MIN_HOP)
                .withFRoute(PathSelectionPolicy.EAP)
                .withFProp(RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM)
                .withUtilizationHeadroom(1.0);
    }

    @Test
    void requiredRateIsVolumeOverTheWindowNotTimesTheHopCount() {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(PACED_STUDY_TOPOLOGY);
        WorkloadTask flow = new WorkloadTask("F1", "SRC", "DST", 0.0, 24.0, 20 * MB);

        double rate = SchedulingCapacity.minimumWholeFlowRateBps(
                flow, route(topology, "SRC-RTR", "RTR-DST"), ContactRegime.R_STATIC);

        // The study's engine assigned 13.333 Mbit/s: twice this, for the two-hop path.
        assertEquals(20 * MB * 8 / (24.0 - 0.0002), rate, 1e-3);
    }

    @Test
    void predictedCompletionMatchesWhatThePacedStudyMeasured() {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(PACED_STUDY_TOPOLOGY);
        WorkloadTask flow = new WorkloadTask("F1", "SRC", "DST", 0.0, 24.0, 20 * MB);

        // Paced at the study's 13.333 Mbit/s, the flows measured 12.58 s; the engine predicted 24.0 s.
        double predicted = SchedulingCapacity.earliestCompletionSec(flow,
                route(topology, "SRC-RTR", "RTR-DST"), ContactRegime.R_STATIC, new LRIB(), 13.333e6);

        assertEquals(20 * MB * 8 / 13.333e6 + 0.0002, predicted, 1e-6);
        assertTrue(predicted < 12.58, "the model must not predict slower than the measured run");
    }

    @Test
    void scheduledContactsStayStoreAndForward() {
        assertEquals(11.0, SchedulingCapacity.nextHopReadySec(ContactRegime.R_DET, 0.0, 10.0, 1.0));
        assertEquals(11.0, SchedulingCapacity.nextHopReadySec(ContactRegime.R_STOCH, 0.0, 10.0, 1.0));
        assertEquals(1.0, SchedulingCapacity.nextHopReadySec(ContactRegime.R_STATIC, 0.0, 10.0, 1.0));
    }

    @Test
    void transitNodeHoldsOnlyTheBacklogWhenTheNextHopWaits() {
        List<LRIB.TransmissionSlot> waits = List.of(new LRIB.TransmissionSlot(12.0, 20.0));
        List<double[]> held = SchedulingCapacity.transitHolds(
                ContactRegime.R_STATIC, 10.0, 18.0, waits, 8e6, 10 * MB);
        assertEquals(1, held.size());
        assertEquals(8e6 * 2.0 / 8, held.get(0)[0], 1e-6, "rate x delay, not the whole flow");
        assertEquals(10.0, held.get(0)[1]);
        assertEquals(20.0, held.get(0)[2]);

        assertTrue(SchedulingCapacity.transitHolds(ContactRegime.R_STATIC, 12.0, 20.0, waits, 8e6,
                10 * MB).isEmpty(), "a hop that starts as data arrives holds nothing");

        List<double[]> stored = SchedulingCapacity.transitHolds(
                ContactRegime.R_DET, 11.0, 11.0, waits, 8e6, 10 * MB);
        assertEquals(10 * MB, stored.get(0)[0], "store-and-forward holds the whole flow");
        assertEquals(11.0, stored.get(0)[1]);
        assertEquals(12.0, stored.get(0)[2]);
    }

    /**
     * Four flows on a four-hop chain, each needing a fifth of the bottleneck. Store-and-forward
     * charged each 4 x 0.2 = 0.8 of it, so only one fit.
     */
    @Test
    void flowsThatFitTheBackboneAreAllAdmittedOnAMultiHopPath() {
        StringBuilder json = new StringBuilder("{\"regime\": \"R_STATIC\", \"nodes\": [");
        for (int n = 0; n <= 4; n++) {
            json.append(n > 0 ? "," : "").append("{\"nodeId\": \"N").append(n)
                    .append("\", \"serviceRateBps\": 1e9, \"reservoirCapacityBytes\": 1e9}");
        }
        json.append("], \"links\": [");
        for (int n = 0; n < 4; n++) {
            json.append(n > 0 ? "," : "").append("{\"linkId\": \"L").append(n)
                    .append("\", \"sourceNodeId\": \"N").append(n)
                    .append("\", \"destinationNodeId\": \"N").append(n + 1)
                    .append("\", \"baseBandwidthBps\": 1e7, \"propagationDelaySec\": 0.001,"
                            + " \"activeContactSec\": 1.0, \"inactivePreSec\": 0.0, \"inactivePostSec\": 0.0}");
        }
        BaseTopology topology = FileTopologyParser.parseTopologyJson(json.append("]}").toString());
        double volume = 0.2 * 1e7 * 30.0 / 8;
        List<WorkloadTask> flows = List.of(
                new WorkloadTask("A", "N0", "N4", 0.0, 30.0, volume),
                new WorkloadTask("B", "N0", "N4", 0.0, 30.0, volume),
                new WorkloadTask("C", "N0", "N4", 0.0, 30.0, volume),
                new WorkloadTask("D", "N0", "N4", 0.0, 30.0, volume));

        CRPEngine.PCEComputationResult result = engine().solve(topology, flows);

        assertEquals(0, result.getUnadmittedTasks().size());
        assertEquals(4, result.getCommittedSchedules().size());
        for (CRPEngine.CommittedFlowSchedule schedule : result.getCommittedSchedules()) {
            assertTrue(schedule.isMetDeadline());
            assertEquals(volume * 8 / (30.0 - 0.004), schedule.getCommittedRateBps(), 1e-3);
        }
    }

    /**
     * A flow whose second hop is busy when its data starts arriving: the transit node buffers the
     * backlog, rate x wait, and the schedule passes replay validation.
     */
    @Test
    void aWaitingHopReservesItsBacklogAtTheTransitNode() {
        BaseTopology topology = FileTopologyParser.parseTopologyJson("""
                {"regime": "R_STATIC",
                 "nodes": [
                  {"nodeId": "A", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
                  {"nodeId": "M", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
                  {"nodeId": "Z", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9}],
                 "links": [
                  {"linkId": "A-M", "sourceNodeId": "A", "destinationNodeId": "M", "baseBandwidthBps": 1e7,
                   "propagationDelaySec": 0.0, "activeContactSec": 1.0, "inactivePreSec": 0.0, "inactivePostSec": 0.0},
                  {"linkId": "M-Z", "sourceNodeId": "M", "destinationNodeId": "Z", "baseBandwidthBps": 1e7,
                   "propagationDelaySec": 0.0, "activeContactSec": 1.0, "inactivePreSec": 0.0, "inactivePostSec": 0.0}]}
                """);
        // "busy" is committed first and fills M-Z for its first 5 s. At its e* rate "flow" would then
        // finish late, so the engine retries at the fastest rate. EAP must judge the route at that
        // rate too; judged at e* alone it dropped the route before the retry (NO_PATH_SELECTED).
        WorkloadTask busy = new WorkloadTask("busy", "M", "Z", 0.0, 5.0, 1e7 * 5.0 / 8);
        WorkloadTask flow = new WorkloadTask("flow", "A", "Z", 0.0, 30.0, 1e7 * 6.0 / 8);

        CRPEngine engine = engine().withPersistentState(true);
        assertEquals(0, engine.solve(topology, List.of(busy)).getUnadmittedTasks().size());
        CRPEngine.PCEComputationResult result = engine.solve(topology, List.of(flow));

        assertEquals(0, result.getUnadmittedTasks().size(), String.valueOf(result.getRefusalCauses()));
        CRPEngine.CommittedFlowSchedule scheduled = result.getCommittedSchedules().stream()
                .filter(s -> s.getTask().getTaskId().equals("flow")).findFirst().orElseThrow();
        double rate = scheduled.getCommittedRateBps();
        List<NRIB.NodeReservation> held = engine.getNRIB().getAllReservations().stream()
                .filter(r -> r.getTaskId().equals("flow")).toList();
        assertEquals(1, held.size());
        assertEquals("M", held.get(0).getNodeId());
        assertEquals(Math.min(flow.getTaskSizeBytes(), rate * 5.0 / 8), held.get(0).getReservedBufferBytes(),
                1e-3, "the backlog built while M-Z was busy");
    }
}
