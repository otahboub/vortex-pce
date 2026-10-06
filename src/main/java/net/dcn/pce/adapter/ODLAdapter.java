package net.dcn.pce.adapter;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.pcep.PCEPServer;
import java.util.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenDaylight-oriented schedule adapter. It builds in-memory PCEP descriptions;
 * MD-SAL synchronization and network delivery are not implemented here.
 */
public class ODLAdapter {

    private static final Logger log = Logger.getLogger(ODLAdapter.class.getName());

    private final CRPEngine crpEngine;

    public ODLAdapter(CRPEngine crpEngine) {
        this.crpEngine = crpEngine;
    }

    /**
     * Reads ODL MD-SAL Topology Data Tree and schedules workloads using CRP engine.
     */
    public List<PCEPServer.PCEPInitiateMessage> computePCEPDescriptions(BaseTopology odlTopology, List<WorkloadTask> tasks) {
        log.info(String.format("ODL Adapter: Computing from supplied topology (%d nodes, %d links)...",
                odlTopology.getNodeCount(), odlTopology.getLinkCount()));

        CRPEngine.PCEComputationResult result = crpEngine.solve(odlTopology, tasks);

        List<PCEPServer.PCEPInitiateMessage> pcepMessages = new ArrayList<>();
        for (CRPEngine.CommittedFlowSchedule schedule : result.getCommittedSchedules()) {
            PCEPServer.PCEPInitiateMessage pcepMsg = PCEPServer.buildPCEPInitiate(schedule);
            pcepMessages.add(pcepMsg);
            log.fine("ODL Adapter: Built PCEP description " + pcepMsg.getLspName());
        }
        return pcepMessages;
    }
}
