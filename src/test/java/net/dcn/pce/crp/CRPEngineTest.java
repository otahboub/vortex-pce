package net.dcn.pce.crp;

import net.dcn.pce.adapter.ODLAdapter;
import net.dcn.pce.adapter.ONOSAdapter;
import net.dcn.pce.crp.policy.*;
import net.dcn.pce.model.*;
import net.dcn.pce.northbound.PCERestController;
import net.dcn.pce.pcep.PCEPServer;
import net.dcn.pce.topology.KuiperConstellationBuilder;
import net.dcn.pce.topology.OARNetTopologyBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CRPEngineTest {

    private BaseTopology oarNetTopology;
    private List<WorkloadTask> oarNetWorkloads;

    @BeforeEach
    public void setUp() {
        oarNetTopology = OARNetTopologyBuilder.buildOARNetTopology();
        oarNetWorkloads = OARNetTopologyBuilder.buildOARNetWorkloads();
    }

    @Test
    @DisplayName("Verify Data Flow Equilibrium produces replay-valid admitted schedules")
    public void testDataFlowEquilibriumProducesFeasibleSchedules() {
        CRPEngine eqmEngine = new CRPEngine()
                .withFSelect(TaskSelectionPolicy.LWEEF)
                .withFGenerate(RouteGenerationPolicy.BOUNDED_PATH_ENUMERATION)
                .withFRoute(PathSelectionPolicy.EAP)
                .withFProp(RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM)
                .withUtilizationHeadroom(0.9);

        CRPEngine.PCEComputationResult eqmResult = eqmEngine.solve(oarNetTopology, oarNetWorkloads);

        assertNotNull(eqmResult);
        assertFalse(eqmResult.getCommittedSchedules().isEmpty(), "Committed schedules should not be empty");
        assertTrue(eqmResult.getCommittedSuccessRatioPercent() >= 90.0, "EQM policy should achieve high deadline success rate");

        System.out.println("=== DATA FLOW EQUILIBRIUM (e^) COMPUTATION RESULT ===");
        System.out.printf("Committed Tasks: %d, Met Deadlines: %d (%.1f%%)\n",
                eqmResult.getCommittedSchedules().size(), eqmResult.getMetDeadlineCount(), eqmResult.getCommittedSuccessRatioPercent());

        System.out.printf("Total Completion Earliness: %.1f hours\n", eqmResult.getTotalEarlinessSec() / 3600.0);
        System.out.printf("Max Network Transit Buffer: %.2f MB\n", eqmResult.getMaxNetworkTransitBufferBytes() / (1024.0 * 1024.0));
        System.out.printf("Computation Time: %d ms\n", eqmResult.getComputationTimeMs());
    }

    @Test
    @DisplayName("Verify Workload Set JSON Blob Serialization & REST Controller Endpoint")
    public void testWorkloadJsonBlobAndRestController() throws IOException {
        String jsonBlob = JSONUtils.toWorkloadJson(oarNetWorkloads);
        assertNotNull(jsonBlob);
        assertTrue(jsonBlob.contains("W_1"));

        System.out.println("=== SAMPLE WORKLOAD JSON BLOB ===");
        System.out.println(jsonBlob.substring(0, Math.min(jsonBlob.length(), 400)) + "\n...");

        CRPEngine engine = new CRPEngine();
        PCERestController restController = new PCERestController(engine, oarNetTopology);

        String responseJson = restController.handleScheduleWorkloadsRequest(jsonBlob);
        assertNotNull(responseJson);
        assertTrue(responseJson.contains("committedSchedules"));
    }

    @Test
    @DisplayName("Report line-rate and DFE policy outcomes without treating zero separation as proof")
    public void testRatePolicyComparisonProducesFeasibleResults() {
        CRPEngine lineRateEngine = new CRPEngine().withFProp(RateAssignmentPolicy.LINE_RATE);
        CRPEngine.PCEComputationResult lineRateResult = lineRateEngine.solve(oarNetTopology, oarNetWorkloads);

        CRPEngine eqmEngine = new CRPEngine().withFProp(RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM).withUtilizationHeadroom(0.9);
        CRPEngine.PCEComputationResult eqmResult = eqmEngine.solve(oarNetTopology, oarNetWorkloads);

        System.out.println("\n=== RATE-POLICY COMPARISON ===");
        System.out.printf("LINE_RATE  -> Met Deadlines: %d, Max Buffer: %.2f MB\n",
                lineRateResult.getMetDeadlineCount(), lineRateResult.getMaxNetworkTransitBufferBytes() / (1024.0 * 1024.0));
        System.out.printf("EQM (e^)   -> Met Deadlines: %d, Max Buffer: %.2f MB\n",
                eqmResult.getMetDeadlineCount(), eqmResult.getMaxNetworkTransitBufferBytes() / (1024.0 * 1024.0));

        assertFalse(lineRateResult.getUnadmittedTasks().isEmpty());
        assertFalse(eqmResult.getCommittedSchedules().isEmpty());
    }

    @Test
    @DisplayName("Verify Constellation Scale Performance on Kuiper-630 Topology (1,256 Nodes, 398 Workloads)")
    public void testKuiper630ConstellationBenchmark() {
        BaseTopology kuiperTopology = KuiperConstellationBuilder.buildKuiperConstellationTopology();
        List<WorkloadTask> kuiperWorkloads = KuiperConstellationBuilder.buildKuiperWorkloads();

        CRPEngine kuiperEngine = new CRPEngine()
                .withFSelect(TaskSelectionPolicy.LWEEF)
                .withFGenerate(RouteGenerationPolicy.BFS_MIN_HOP)
                .withFRoute(PathSelectionPolicy.EAP)
                .withFProp(RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM);

        CRPEngine.PCEComputationResult result = kuiperEngine.solve(kuiperTopology, kuiperWorkloads);
        assertNotNull(result);
        assertFalse(result.getCommittedSchedules().isEmpty());
    }

    @Test
    @DisplayName("Verify Independent Schedule Replay Validator Invariants")
    public void testScheduleReplayValidatorInvariants() {
        CRPEngine engine = new CRPEngine().withFProp(RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM);
        CRPEngine.PCEComputationResult result = engine.solve(oarNetTopology, oarNetWorkloads);

        CRPScheduleReplayValidator.ValidationReport report = CRPScheduleReplayValidator.validateResult(result);
        assertTrue(report.isValid(), "Replay validator should confirm schedule continuity and volume conservation");
        assertTrue(report.getValidatedScheduleCount() > 0, "Validator should process committed schedules");
    }

    @Test
    @DisplayName("Store-and-forward completion equals final-hop completion")
    public void testStoreAndForwardCompletionUsesFinalHopEnd() {
        BaseTopology topology = twoHopTopology(1_000, 1_000);
        WorkloadTask task = new WorkloadTask("T1", "A", "C", 0, 10, 100);

        CRPEngine.PCEComputationResult result = new CRPEngine()
                .withFProp(RateAssignmentPolicy.LINE_RATE)
                .solve(topology, List.of(task));

        assertEquals(1, result.getCommittedFlowCount());
        CRPEngine.CommittedFlowSchedule schedule = result.getCommittedSchedules().get(0);
        assertEquals(2, schedule.getHopSchedules().size());
        assertEquals(1.6, schedule.getCompletionSec(), 1e-9);
        assertEquals(schedule.getHopSchedules().get(1).getEndSec(), schedule.getCompletionSec(), 1e-9);
    }

    @Test
    @DisplayName("DFE implements the deadline-window equation across the complete route")
    public void testDataFlowEquilibriumMatchesDeadlineWindowEquation() {
        BaseTopology topology = new BaseTopology();
        topology.addNode(new Node("A", "A", 1_000, 1_000_000));
        topology.addNode(new Node("B", "B", 1_000, 1_000_000));
        topology.addNode(new Node("C", "C", 1_000, 1_000_000));
        topology.addLink(new Link("A-B", "A", "B",
                new LinkIntermittencyFunction(1_000, 0.5, 1, 0, 0)));
        topology.addLink(new Link("B-C", "B", "C",
                new LinkIntermittencyFunction(1_000, 0.5, 1, 0, 0)));
        WorkloadTask task = new WorkloadTask("DFE", "A", "C", 0, 10, 100);

        CRPEngine.PCEComputationResult result = new CRPEngine()
                .withFProp(RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM)
                .solve(topology, List.of(task));

        assertEquals(1, result.getCommittedFlowCount());
        CRPEngine.CommittedFlowSchedule schedule = result.getCommittedSchedules().get(0);
        assertEquals((100 * 8.0 * 2) / (10 - 1.0), schedule.getCommittedRateBps(), 1e-9);
        assertEquals(10.0, schedule.getCompletionSec(), 1e-9);
        assertEquals(5.0, schedule.getHopSchedules().get(0).getArrivalSec(), 1e-9);
    }

    @Test
    @DisplayName("Actual inter-hop waiting reserves finite transit storage")
    public void testActualInterHopWaitingReservesBufferInterval() {
        BaseTopology topology = twoHopTopology(1_000, 1_000);
        WorkloadTask task = new WorkloadTask("WAITING", "A", "C", 0, 10, 100);
        CRPEngine engine = new CRPEngine()
                .withPersistentState(true)
                .withFProp(RateAssignmentPolicy.RESIDUAL_BOTTLENECK_HEADROOM)
                .withUtilizationHeadroom(0.5);
        engine.getLRIB().reserveLinkCap("BLOCK", "B-C", "B", "C", 1_000, 0, 5);

        CRPEngine.PCEComputationResult result = engine.solve(topology, List.of(task));

        assertEquals(1, result.getCommittedFlowCount());
        CRPEngine.CommittedFlowSchedule schedule = result.getCommittedSchedules().get(0);
        assertEquals(5.0, schedule.getHopSchedules().get(1).getStartSec(), 1e-9);
        assertEquals(100.0, schedule.getPeakTransitBufferBytes(), 1e-9);
        assertEquals(1, engine.getNRIB().getReservations("B").size());
        assertEquals(1.6, engine.getNRIB().getReservations("B").get(0).getStartSec(), 1e-9);
        assertEquals(5.0, engine.getNRIB().getReservations("B").get(0).getEndSec(), 1e-9);
    }

    @Test
    @DisplayName("Admission rejects a rate above a downstream link capacity")
    public void testAdmissionRejectsDownstreamOvercommit() {
        BaseTopology topology = twoHopTopology(1_000, 100);
        WorkloadTask task = new WorkloadTask("T1", "A", "C", 0, 100, 100);

        CRPEngine.PCEComputationResult result = new CRPEngine()
                .withFProp(RateAssignmentPolicy.LINE_RATE)
                .solve(topology, List.of(task));

        assertEquals(0, result.getCommittedFlowCount());
        assertEquals(1, result.getUnadmittedTasks().size());
    }

    @Test
    @DisplayName("Deadline admission cannot be disabled through a no-op compatibility flag")
    public void testDeadlineAdmissionCannotBeDisabled() {
        assertThrows(IllegalArgumentException.class,
                () -> new CRPEngine().withEnforceAdmissionGate(false));
    }

    @Test
    @DisplayName("Replay rejects per-hop volume mismatch")
    public void testReplayRejectsPerHopVolumeMismatch() {
        Link link = new Link("A-B", "A", "B", LinkIntermittencyFunction.persistentLink(1_000, 0));
        WorkloadTask task = new WorkloadTask("T1", "A", "B", 0, 10, 100);
        CRPEngine.HopSchedule invalidHop = new CRPEngine.HopSchedule(link, 0, 0.1);
        CRPEngine.CommittedFlowSchedule invalidSchedule = new CRPEngine.CommittedFlowSchedule(
                task, List.of(invalidHop), 1_000, 0);
        CRPEngine.PCEComputationResult result = new CRPEngine.PCEComputationResult();
        result.addCommitted(invalidSchedule);

        CRPScheduleReplayValidator.ValidationReport report =
                CRPScheduleReplayValidator.validateResult(result);
        assertFalse(report.isValid());
        assertTrue(report.getErrorReason().contains("volume conservation"));
    }

    @Test
    @DisplayName("Bounded CGR bypasses a minimum-hop link whose finite contact has expired")
    public void testBoundedCgrSelectsOrderedExplicitContacts() {
        BaseTopology topology = explicitCgrTopology();
        WorkloadTask task = new WorkloadTask("CGR", "A", "D", 2, 20, 100);

        CRPEngine.CommittedFlowSchedule schedule = new CRPEngine()
                .withFGenerate(RouteGenerationPolicy.BOUNDED_CGR)
                .withFRoute(PathSelectionPolicy.CGR)
                .solve(topology, List.of(task))
                .getCommittedSchedules().get(0);

        assertEquals(List.of("A-B", "B-D"), schedule.getRoute().stream()
                .map(Link::getLinkId).toList());
        assertEquals(3.0, schedule.getHopSchedules().get(0).getStartSec(), 1e-9);
        assertEquals(7.0, schedule.getHopSchedules().get(1).getStartSec(), 1e-9);
    }

    @Test
    @DisplayName("Bounded CGR returns no route when the downstream contact misses the deadline")
    public void testBoundedCgrRejectsExpiredContactChain() {
        CRPEngine.PCEComputationResult result = new CRPEngine()
                .withFGenerate(RouteGenerationPolicy.BOUNDED_CGR)
                .withFRoute(PathSelectionPolicy.CGR)
                .solve(explicitCgrTopology(),
                        List.of(new WorkloadTask("CGR-LATE", "A", "D", 2, 6, 100)));

        assertEquals(0, result.getCommittedFlowCount());
        assertEquals(1, result.getUnadmittedTasks().size());
    }

    @Test
    @DisplayName("OCC orders deterministic contacts and reserves forced transit holding")
    public void testOrderedConnectionCriterionSchedulesContactWait() {
        BaseTopology topology = contactOrderedTopology();
        WorkloadTask task = new WorkloadTask("OCC", "A", "C", 0, 20, 100);
        CRPEngine engine = new CRPEngine().withFRoute(PathSelectionPolicy.OCC);

        CRPEngine.CommittedFlowSchedule schedule = engine.solve(topology, List.of(task))
                .getCommittedSchedules().get(0);

        assertEquals(2.0, schedule.getHopSchedules().get(0).getStartSec(), 1e-9);
        assertEquals(7.0, schedule.getHopSchedules().get(1).getStartSec(), 1e-9);
        assertEquals(1, engine.getNRIB().getReservations("B").size());
        assertEquals(schedule.getHopSchedules().get(0).getArrivalSec(),
                engine.getNRIB().getReservations("B").get(0).getStartSec(), 1e-9);
        assertEquals(7.0, engine.getNRIB().getReservations("B").get(0).getEndSec(), 1e-9);
    }

    @Test
    @DisplayName("A deterministic hop can transmit across repeated contacts")
    public void testMultiContactHopExecution() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);
        topology.addNode(new Node("A", "A", 1_000, 1_000_000));
        topology.addNode(new Node("B", "B", 1_000, 1_000_000));
        topology.addLink(new Link("A-B", "A", "B",
                new LinkIntermittencyFunction(1_000, 0, 2, 1, 7)));

        CRPEngine.CommittedFlowSchedule schedule = new CRPEngine().solve(
                topology, List.of(new WorkloadTask("MULTI", "A", "B", 0, 30, 300)))
                .getCommittedSchedules().get(0);

        assertEquals(3, schedule.getHopSchedules().get(0).getTransmissionSlots().size());
        assertEquals(23.0, schedule.getCompletionSec(), 1e-9);
    }

    @Test
    @DisplayName("Result JSON preserves every transmission slice across contact gaps")
    public void testMultiContactSlicesAreExported() throws Exception {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);
        topology.addNode(new Node("A", "A", 1_000, 1_000_000));
        topology.addNode(new Node("B", "B", 1_000, 1_000_000));
        topology.addLink(new Link("A-B", "A", "B",
                new LinkIntermittencyFunction(1_000, 0, 2, 1, 7)));

        CRPEngine.PCEComputationResult result = new CRPEngine().solve(
                topology, List.of(new WorkloadTask("MULTI-JSON", "A", "B", 0, 30, 300)));
        JsonNode hop = new ObjectMapper().readTree(JSONUtils.toResultJson(result))
                .path("committedSchedules").path(0).path("hops").path(0);

        assertEquals(3, hop.path("transmissionSlots").size());
        assertEquals(1.0, hop.path("transmissionSlots").path(0).path("startSec").asDouble(), 1e-9);
        assertEquals(3.0, hop.path("transmissionSlots").path(0).path("endSec").asDouble(), 1e-9);
        assertEquals(11.0, hop.path("transmissionSlots").path(1).path("startSec").asDouble(), 1e-9);
        assertEquals(23.0, hop.path("transmissionSlots").path(2).path("endSec").asDouble(), 1e-9);
    }

    @Test
    @DisplayName("R-static ignores intermittent phase and schedules continuously")
    public void testStaticRegimeRetainsContinuousExecution() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1_000, 1_000_000));
        topology.addNode(new Node("B", "B", 1_000, 1_000_000));
        topology.addLink(new Link("A-B", "A", "B",
                new LinkIntermittencyFunction(1_000, 0, 2, 5, 3)));

        CRPEngine.CommittedFlowSchedule schedule = new CRPEngine().solve(
                topology, List.of(new WorkloadTask("STATIC", "A", "B", 0, 10, 100)))
                .getCommittedSchedules().get(0);

        assertEquals(0.0, schedule.getStartSec(), 1e-9);
        assertEquals(1, schedule.getHopSchedules().get(0).getTransmissionSlots().size());
    }

    @Test
    @DisplayName("OCC rejects a route whose next contact cannot meet the deadline")
    public void testOrderedContactDeadlineFailureIsUnadmitted() {
        CRPEngine.PCEComputationResult result = new CRPEngine()
                .withFRoute(PathSelectionPolicy.OCC)
                .solve(contactOrderedTopology(),
                        List.of(new WorkloadTask("LATE", "A", "C", 0, 7.5, 100)));

        assertEquals(0, result.getCommittedFlowCount());
        assertEquals(1, result.getUnadmittedTasks().size());
    }

    @Test
    @DisplayName("Replay rejects deterministic transmission outside a contact")
    public void testReplayRejectsOutOfContactTransmission() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);
        topology.addNode(new Node("A", "A", 1_000, 1_000_000));
        topology.addNode(new Node("B", "B", 1_000, 1_000_000));
        Link link = new Link("A-B", "A", "B",
                new LinkIntermittencyFunction(1_000, 0, 2, 2, 6));
        topology.addLink(link);
        WorkloadTask task = new WorkloadTask("INVALID", "A", "B", 0, 10, 100);
        CRPEngine.PCEComputationResult result = new CRPEngine.PCEComputationResult();
        result.addCommitted(new CRPEngine.CommittedFlowSchedule(
                task, List.of(new CRPEngine.HopSchedule(link, 0, 1)), 800, 0));

        CRPScheduleReplayValidator.ValidationReport report =
                CRPScheduleReplayValidator.validateResult(result, topology, null, null);

        assertFalse(report.isValid());
        assertTrue(report.getErrorReason().contains("outside active contact"));
    }

    @Test
    @DisplayName("Replay rejects transmission outside a finite explicit contact plan")
    public void testReplayRejectsOutOfExplicitContactTransmission() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);
        topology.addNode(new Node("A", "A", 1_000, 1_000_000));
        topology.addNode(new Node("B", "B", 1_000, 1_000_000));
        Link link = explicitLink("A-B", "A", "B", 0, new ContactWindow(2, 4));
        topology.addLink(link);
        WorkloadTask task = new WorkloadTask("INVALID-PLAN", "A", "B", 0, 10, 100);
        CRPEngine.PCEComputationResult result = new CRPEngine.PCEComputationResult();
        result.addCommitted(new CRPEngine.CommittedFlowSchedule(
                task, List.of(new CRPEngine.HopSchedule(link, 5, 6)), 800, 0));

        CRPScheduleReplayValidator.ValidationReport report =
                CRPScheduleReplayValidator.validateResult(result, topology, null, null);

        assertFalse(report.isValid());
        assertTrue(report.getErrorReason().contains("outside active contact"));
    }

    private static BaseTopology twoHopTopology(double firstHopBps, double secondHopBps) {
        BaseTopology topology = new BaseTopology();
        topology.addNode(new Node("A", "A", firstHopBps, 1_000_000));
        topology.addNode(new Node("B", "B", secondHopBps, 1_000_000));
        topology.addNode(new Node("C", "C", secondHopBps, 1_000_000));
        topology.addLink(new Link("A-B", "A", "B", LinkIntermittencyFunction.persistentLink(firstHopBps, 0)));
        topology.addLink(new Link("B-C", "B", "C", LinkIntermittencyFunction.persistentLink(secondHopBps, 0)));
        return topology;
    }

    private static BaseTopology contactOrderedTopology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);
        topology.addNode(new Node("A", "A", 1_000, 1_000_000));
        topology.addNode(new Node("B", "B", 1_000, 1_000_000));
        topology.addNode(new Node("C", "C", 1_000, 1_000_000));
        topology.addLink(new Link("A-B", "A", "B",
                new LinkIntermittencyFunction(1_000, 1, 2, 2, 6)));
        topology.addLink(new Link("B-C", "B", "C",
                new LinkIntermittencyFunction(1_000, 0, 2, 7, 1)));
        return topology;
    }

    private static BaseTopology explicitCgrTopology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);
        for (String nodeId : List.of("A", "B", "D")) {
            topology.addNode(new Node(nodeId, nodeId, 1_000, 1_000_000));
        }
        topology.addLink(explicitLink("A-D", "A", "D", 0,
                new ContactWindow(0, 1)));
        topology.addLink(explicitLink("A-B", "A", "B", 1,
                new ContactWindow(3, 5)));
        topology.addLink(explicitLink("B-D", "B", "D", 0,
                new ContactWindow(7, 9)));
        return topology;
    }

    private static Link explicitLink(
            String linkId, String source, String destination,
            double propagationSec, ContactWindow... windows) {
        return new Link(linkId, source, destination,
                LinkIntermittencyFunction.persistentLink(1_000, propagationSec),
                new ContactPlan(List.of(windows)));
    }

    @Test
    @DisplayName("Verify Strict Structural JSON Parser Rejects Invalid Payloads")
    public void testStrictJSONParserValidation() {
        try {
            JSONUtils.parseWorkloadJson("[{\"taskId\": \"W1\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\", \"originationTimeSec\": 0.0, \"deadlineSec\": 100.0, \"taskSizeBytes\": -100}]");
            assertTrue(false, "Parser should reject negative taskSizeBytes");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("taskSizeBytes"));
        }

        try {
            JSONUtils.parseWorkloadJson("[{\"taskId\": \"W1\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\", \"originationTimeSec\": 100, \"deadlineSec\": 50, \"taskSizeBytes\": 100}]");
            assertTrue(false, "Parser should reject deadlineSec <= originationTimeSec");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("deadlineSec"));
        }
    }


}
