package net.dcn.pce.pcep;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Encoding a committed schedule into the PCInitiate that would install it. */
class ScheduleInitiateTest {

    private static BaseTopology topology(String bAddress) {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e9, 1e6, "10.0.0.1"));
        topology.addNode(new Node("B", "B", 1e9, 1e6, bAddress));
        topology.addNode(new Node("C", "C", 1e9, 1e6, "10.0.0.3"));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
        topology.addLink(new Link("B-C", "B", "C",
                LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
        return topology;
    }

    private static CRPEngine.PCEComputationResult solve(BaseTopology topology) {
        return new CRPEngine().solve(topology,
                List.of(new WorkloadTask("T1", "A", "C", 0, 60, 1_000_000)));
    }

    @Test
    void aScheduleWithAddressedNodesEncodesTheWholePathIntoTheEro() {
        BaseTopology topology = topology("10.0.0.2");
        CRPEngine.CommittedFlowSchedule schedule = solve(topology).getCommittedSchedules().get(0);

        ScheduleInitiate.Result result =
                ScheduleInitiate.encode(schedule, topology, 7L, "vortex-T1");

        assertTrue(result.isEncoded(), result.refusal());
        // Every node on the route, in order, including the transit hop -- not just the endpoints.
        assertArrayEquals(
                PcepEncoder.pcInitiate(7L, "vortex-T1", "10.0.0.1", "10.0.0.3",
                        schedule.getCommittedRateBps(),
                        List.of("10.0.0.1", "10.0.0.2", "10.0.0.3")),
                result.frame());
    }

    @Test
    void anUnaddressedTransitNodeRefusesAndNamesItself() {
        // The legible failure this exists for: the planner can route through a node the controller
        // cannot address, and a PCInitiate with that hop invented or omitted is worse than none.
        BaseTopology topology = topology(null);
        CRPEngine.CommittedFlowSchedule schedule = solve(topology).getCommittedSchedules().get(0);

        ScheduleInitiate.Result result =
                ScheduleInitiate.encode(schedule, topology, 7L, "vortex-T1");

        assertFalse(result.isEncoded());
        assertTrue(result.refusal().contains("node B"), result.refusal());
        assertTrue(result.refusal().contains("no ipv4"), result.refusal());
    }

    @Test
    void aTopologyWithLabelsProducesASegmentRoutedInitiate() {
        // The topology decides, not a setting: an SR-TE PCC rejects a prefix ERO, and a network
        // without labels configured cannot be sent an SR-ERO.
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e9, 1e6, "10.0.0.1", 16010));
        topology.addNode(new Node("B", "B", 1e9, 1e6, "10.0.0.2", 16020));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
        CRPEngine.CommittedFlowSchedule schedule = new CRPEngine().solve(topology,
                List.of(new WorkloadTask("T1", "A", "B", 0, 60, 1_000_000)))
                .getCommittedSchedules().get(0);

        byte[] frame = ScheduleInitiate.encode(schedule, topology, 7L, "vortex-T1").frame();

        String encoded = java.util.HexFormat.of().formatHex(frame);
        assertTrue(encoded.contains("001c000400000001"), "SRP must declare PATH-SETUP-TYPE 1");
        assertTrue(encoded.contains("2408000903e94000"), "ERO must carry B's label as a SID");
    }

    @Test
    void aTopologyWithoutLabelsStillProducesThePrefixForm() {
        BaseTopology topology = topology("10.0.0.2");
        CRPEngine.CommittedFlowSchedule schedule = solve(topology).getCommittedSchedules().get(0);

        String encoded = java.util.HexFormat.of().formatHex(
                ScheduleInitiate.encode(schedule, topology, 7L, "vortex-T1").frame());

        assertFalse(encoded.contains("001c000400000001"), "no path setup type without labels");
    }

    @Test
    void theEncodedFrameIsAPcInitiateCarryingTheCommittedRate() {
        BaseTopology topology = topology("10.0.0.2");
        CRPEngine.CommittedFlowSchedule schedule = solve(topology).getCommittedSchedules().get(0);

        byte[] frame = ScheduleInitiate.encode(schedule, topology, 9L, "vortex-T1").frame();

        assertEquals(12, frame[1] & 0xFF, "PCEP message type 12 is PCInitiate");
        assertEquals(frame.length, ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF),
                "declared length must match the frame");
    }
}
