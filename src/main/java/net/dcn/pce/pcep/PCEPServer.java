package net.dcn.pce.pcep;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.Link;
import java.util.logging.Logger;

import java.util.List;

/**
 * In-memory description builder for a future PCEP initiate integration.
 * Binary PCInitiate encoding and TCP delivery live in the Python service.
 */
public class PCEPServer {

    private static final Logger log = Logger.getLogger(PCEPServer.class.getName());

    public static class PCEPInitiateMessage {
        private final String taskId;
        private final String lspName;
        private final List<String> explicitRouteObject; // ERO Hop IPv4s/IDs
        private final double committedRateBps;
        private final double startTimeSec;
        private final double completionTimeSec;

        public PCEPInitiateMessage(String taskId, String lspName, List<String> explicitRouteObject,
                                  double committedRateBps, double startTimeSec, double completionTimeSec) {
            this.taskId = taskId;
            this.lspName = lspName;
            this.explicitRouteObject = explicitRouteObject;
            this.committedRateBps = committedRateBps;
            this.startTimeSec = startTimeSec;
            this.completionTimeSec = completionTimeSec;
        }

        public String getTaskId() { return taskId; }
        public String getLspName() { return lspName; }
        public List<String> getExplicitRouteObject() { return explicitRouteObject; }
        public double getCommittedRateBps() { return committedRateBps; }
        public double getStartTimeSec() { return startTimeSec; }
        public double getCompletionTimeSec() { return completionTimeSec; }

        public String toPCEPString() {
            StringBuilder sb = new StringBuilder();
            sb.append("PCEInitiate-MSG [LSP=").append(lspName)
              .append(", Task=").append(taskId)
              .append(", Rate=").append(String.format("%.2f Mbps", committedRateBps / 1e6))
              .append(", Window=[").append(String.format("%.1fs", startTimeSec))
              .append(", ").append(String.format("%.1fs", completionTimeSec)).append("]]\n")
              .append("   ERO: Ingress(").append(explicitRouteObject.isEmpty() ? "" : explicitRouteObject.get(0)).append(")");

            for (int i = 1; i < explicitRouteObject.size(); i++) {
                sb.append(" -> ").append(explicitRouteObject.get(i));
            }
            return sb.toString();
        }
    }

    /**
     * Builds a PCEP Initiate Message from a committed CRP flow schedule.
     */
    public static PCEPInitiateMessage buildPCEPInitiate(CRPEngine.CommittedFlowSchedule schedule) {
        List<String> ero = new java.util.ArrayList<>();
        if (!schedule.getRoute().isEmpty()) {
            ero.add(schedule.getRoute().get(0).getSourceNodeId());
            for (Link link : schedule.getRoute()) {
                ero.add(link.getDestinationNodeId());
            }
        }
        String lspName = "DCN-LSP-" + schedule.getTask().getTaskId();
        return new PCEPInitiateMessage(schedule.getTask().getTaskId(), lspName, ero,
                schedule.getCommittedRateBps(), schedule.getStartSec(), schedule.getCompletionSec());
    }
}
