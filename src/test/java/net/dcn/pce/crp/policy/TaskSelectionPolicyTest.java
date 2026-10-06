package net.dcn.pce.crp.policy;

import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.rib.NRIB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

class TaskSelectionPolicyTest {
    @Test
    void lweefDoesNotTraverseTopologyToRankOneTask() {
        WorkloadTask only = new WorkloadTask("one", "A", "B", 0, 10, 100);
        BaseTopology topology = new BaseTopology() {
            @Override
            public List<Link> getOutgoingLinks(String nodeId) {
                throw new AssertionError("singleton LWEEF must not traverse the topology");
            }
        };
        assertSame(only, TaskSelectionPolicy.LWEEF.selectMostCriticalTask(
                List.of(only), topology, new LRIB(), new NRIB()));
    }
}
