package net.dcn.pce.crp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolveStageTimingTest {
    @Test
    void exposesEverySolveStageInResultAndJson() throws Exception {
        BaseTopology topology = new BaseTopology();
        topology.addNode(new Node("A", "A", 1_000_000, 1_000_000));
        topology.addNode(new Node("B", "B", 1_000_000, 1_000_000));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1_000_000, 0)));
        CRPEngine.PCEComputationResult result = new CRPEngine().solve(
                topology, List.of(new WorkloadTask("one", "A", "B", 0, 10, 100)));
        Map<String, Double> timings = result.getStageTimingMicros();
        assertEquals(CRPEngine.PCEComputationResult.SolveStage.values().length, timings.size());
        assertTrue(timings.values().stream().allMatch(value -> value >= 0));
        assertTrue(timings.get("ROUTE_GENERATION") > 0);
        JsonNode encoded = new ObjectMapper().readTree(JSONUtils.toResultJson(result));
        assertEquals(timings.size(), encoded.path("stageTimingMicros").size());
        assertTrue(encoded.path("stageTimingMicros").path("REPLAY_VALIDATION").isNumber());
    }
}
