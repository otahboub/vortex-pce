package net.dcn.pce.crp;

import net.dcn.pce.config.OperatorConfiguration;
import net.dcn.pce.crp.CRPEngine.HeadroomEnforcement;
import net.dcn.pce.crp.CRPEngine.TransportMargin;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.topology.FileTopologyParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The utilisation-headroom and transport-margin selectors, from configuration to the committed plan. */
class HeadroomAndTransportMarginTest {

    /** The paced-vs-unpaced testbed: the 100 Mbit/s bottleneck is the second hop. */
    private static final String TOPOLOGY = """
            {"regime": "R_STATIC",
             "nodes": [
              {"nodeId": "SRC", "serviceRateBps": 1e10, "reservoirCapacityBytes": 1e9},
              {"nodeId": "RTR", "serviceRateBps": 1e10, "reservoirCapacityBytes": 1e9},
              {"nodeId": "DST", "serviceRateBps": 1e10, "reservoirCapacityBytes": 1e9}],
             "links": [
              {"linkId": "SRC-RTR", "sourceNodeId": "SRC", "destinationNodeId": "RTR",
               "baseBandwidthBps": 1e10, "propagationDelaySec": 0.0001,
               "activeContactSec": 1.0, "inactivePreSec": 0.0, "inactivePostSec": 0.0},
              {"linkId": "RTR-DST", "sourceNodeId": "RTR", "destinationNodeId": "DST",
               "baseBandwidthBps": 1e8, "propagationDelaySec": 0.0001,
               "activeContactSec": 1.0, "inactivePreSec": 0.0, "inactivePostSec": 0.0}]}
            """;

    private static final double WINDOW_SEC = 24.0 - 0.0002;

    private static CRPEngine engine(Map<String, String> environment) {
        return OperatorConfiguration.parse(environment).forRegime(ContactRegime.R_STATIC)
                .applyTo(new CRPEngine());
    }

    /** Four flows that together need 99.9% of the bottleneck. */
    private static int admittedOfFourFillingTheBottleneck(Map<String, String> environment) {
        double volume = 1e8 * 24.0 / 8 / 4 * 0.999;
        List<WorkloadTask> flows = IntStream.range(0, 4)
                .mapToObj(i -> new WorkloadTask("F" + i, "SRC", "DST", 0.0, 24.0, volume)).toList();
        return engine(environment).solve(FileTopologyParser.parseTopologyJson(TOPOLOGY), flows)
                .getCommittedSchedules().size();
    }

    private static CRPEngine.CommittedFlowSchedule oneFlow(Map<String, String> environment, double volume) {
        CRPEngine.PCEComputationResult result = engine(environment).solve(
                FileTopologyParser.parseTopologyJson(TOPOLOGY),
                List.of(new WorkloadTask("F", "SRC", "DST", 0.0, 24.0, volume)));
        assertEquals(1, result.getCommittedSchedules().size());
        return result.getCommittedSchedules().get(0);
    }

    // ---- configuration ----

    @Test
    void defaultsKeepTheLongStandingBehaviour() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of());
        assertEquals(0.90, configuration.getUtilizationHeadroom());
        assertEquals("WINDOW_PEAK", configuration.getHeadroomEnforcement());
        assertEquals("NONE", configuration.getTransportMargin());
        Map<String, String> described = configuration.describe();
        assertEquals("0.9", described.get("utilizationHeadroom"));
        assertEquals("WINDOW_PEAK", described.get("headroomEnforcement"));
        assertEquals("NONE", described.get("transportMargin"));

        CRPEngine engine = configuration.applyTo(new CRPEngine());
        assertEquals(0.90, engine.getUtilizationHeadroom());
        assertEquals(HeadroomEnforcement.WINDOW_PEAK, engine.getHeadroomEnforcement());
        assertEquals(TransportMargin.NONE, engine.getTransportMargin());
    }

    @Test
    void everySelectorReachesTheEngineAndSurvivesRegimeBinding() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_UTILIZATION_HEADROOM, "0.75",
                OperatorConfiguration.ENV_HEADROOM_ENFORCEMENT, "transmission_interval",
                OperatorConfiguration.ENV_TRANSPORT_MARGIN, "RATE_FACTOR",
                OperatorConfiguration.ENV_TRANSPORT_MARGIN_VALUE, "1.05"));
        CRPEngine engine = configuration.forRegime(ContactRegime.R_DET).applyTo(new CRPEngine());
        assertEquals(0.75, engine.getUtilizationHeadroom());
        assertEquals(HeadroomEnforcement.TRANSMISSION_INTERVAL, engine.getHeadroomEnforcement());
        assertEquals(TransportMargin.RATE_FACTOR, engine.getTransportMargin());
        assertEquals(1.05, engine.getTransportMarginValue());
        assertEquals("RATE_FACTOR 1.05", configuration.describe().get("transportMargin"));
    }

    @Test
    void invalidSettingsFailStartupInsteadOfBeingIgnored() {
        List<Map<String, String>> invalid = List.of(
                Map.of(OperatorConfiguration.ENV_UTILIZATION_HEADROOM, "0"),
                Map.of(OperatorConfiguration.ENV_UTILIZATION_HEADROOM, "1.5"),
                Map.of(OperatorConfiguration.ENV_UTILIZATION_HEADROOM, "NaN"),
                Map.of(OperatorConfiguration.ENV_UTILIZATION_HEADROOM, "ninety"),
                Map.of(OperatorConfiguration.ENV_HEADROOM_ENFORCEMENT, "SOMETIMES"),
                Map.of(OperatorConfiguration.ENV_TRANSPORT_MARGIN, "GUESS"),
                Map.of(OperatorConfiguration.ENV_TRANSPORT_MARGIN_VALUE, "1.05"),
                Map.of(OperatorConfiguration.ENV_TRANSPORT_MARGIN, "RATE_FACTOR"),
                Map.of(OperatorConfiguration.ENV_TRANSPORT_MARGIN, "RATE_FACTOR",
                        OperatorConfiguration.ENV_TRANSPORT_MARGIN_VALUE, "0.95"),
                Map.of(OperatorConfiguration.ENV_TRANSPORT_MARGIN, "DEADLINE_GUARD",
                        OperatorConfiguration.ENV_TRANSPORT_MARGIN_VALUE, "-1"),
                Map.of(OperatorConfiguration.ENV_TRANSPORT_MARGIN, "DEADLINE_GUARD",
                        OperatorConfiguration.ENV_TRANSPORT_MARGIN_VALUE, "Infinity"));
        for (Map<String, String> environment : invalid) {
            assertThrows(IllegalArgumentException.class, () -> OperatorConfiguration.parse(environment),
                    environment.toString());
        }
    }

    // ---- headroom enforcement ----

    @Test
    void windowPeakRarelyBindsOnAContinuousRouteButTheTransmissionIntervalDoes() {
        // WINDOW_PEAK: the bottleneck hop's reservations start one propagation delay into the window,
        // so the check sees an idle link and admits all four despite the 90% headroom.
        assertEquals(4, admittedOfFourFillingTheBottleneck(Map.of()));
        // TRANSMISSION_INTERVAL: the fourth flow would take more than 90% of what is left.
        assertEquals(3, admittedOfFourFillingTheBottleneck(Map.of(
                OperatorConfiguration.ENV_HEADROOM_ENFORCEMENT, "TRANSMISSION_INTERVAL")));
        assertEquals(4, admittedOfFourFillingTheBottleneck(Map.of(
                OperatorConfiguration.ENV_HEADROOM_ENFORCEMENT, "TRANSMISSION_INTERVAL",
                OperatorConfiguration.ENV_UTILIZATION_HEADROOM, "1.0")));
    }

    // ---- transport margin ----

    @Test
    void rateFactorSendsFasterByTheFactor() {
        double volume = 20e6;
        double eStar = volume * 8 / WINDOW_SEC;
        CRPEngine.CommittedFlowSchedule plain = oneFlow(Map.of(), volume);
        CRPEngine.CommittedFlowSchedule faster = oneFlow(Map.of(
                OperatorConfiguration.ENV_TRANSPORT_MARGIN, "RATE_FACTOR",
                OperatorConfiguration.ENV_TRANSPORT_MARGIN_VALUE, "1.05"), volume);

        assertEquals(eStar, plain.getCommittedRateBps(), 1e-3);
        assertEquals(eStar * 1.05, faster.getCommittedRateBps(), 1e-3);
        assertTrue(faster.getCompletionSec() < plain.getCompletionSec());
    }

    @Test
    void deadlineGuardFinishesThatManySecondsEarly() {
        CRPEngine.CommittedFlowSchedule guarded = oneFlow(Map.of(
                OperatorConfiguration.ENV_TRANSPORT_MARGIN, "DEADLINE_GUARD",
                OperatorConfiguration.ENV_TRANSPORT_MARGIN_VALUE, "0.5"), 20e6);

        assertEquals(24.0 - 0.5, guarded.getCompletionSec(), 1e-6);
    }

    @Test
    void aMarginNeverExceedsTheHeadroomSafeCeiling() {
        // A factor of 10 on a flow needing a quarter of the link would ask for 250%; it is capped at the
        // 90% headroom ceiling, and the flow is still admitted.
        CRPEngine.CommittedFlowSchedule capped = oneFlow(Map.of(
                OperatorConfiguration.ENV_TRANSPORT_MARGIN, "RATE_FACTOR",
                OperatorConfiguration.ENV_TRANSPORT_MARGIN_VALUE, "10"), 1e8 * 24.0 / 8 / 4);

        assertEquals(0.9 * 1e8, capped.getCommittedRateBps(), 1e-3);
    }
}
