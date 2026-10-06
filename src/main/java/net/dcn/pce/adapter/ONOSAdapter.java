package net.dcn.pce.adapter;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import net.dcn.pce.pcep.PCEPServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * ONOS (Open Network Operating System) Controller Integration Adapter.
 * Pushes a basic device NetConfig document over HTTP and builds in-memory PCEP
 * descriptions. Topology event ingestion and PCEP network delivery are not implemented.
 */
public class ONOSAdapter {

    private static final Logger log = Logger.getLogger(ONOSAdapter.class.getName());
    private static final int HTTP_TIMEOUT_MS = 5000; // 5 Second Connection & Read Timeout

    private final CRPEngine crpEngine;

    public ONOSAdapter(CRPEngine crpEngine) {
        this.crpEngine = crpEngine;
    }

    /**
     * Pushes network configuration (devices & links) directly to ONOS NetConfig REST API.
     */
    public void pushNetConfigToONOS(BaseTopology topology) {
        String onosHost = System.getenv().getOrDefault("ONOS_HOST", System.getProperty("onos.host", "localhost"));
        String onosUser = System.getenv("ONOS_USER");
        String onosPass = System.getenv("ONOS_PASSWORD");
        // Shipping ONOS's stock onos/rocks as a default means an operator who never configured
        // this adapter still sends working credentials at whatever host resolves. Require them.
        if (onosUser == null || onosUser.isBlank() || onosPass == null || onosPass.isBlank()) {
            throw new IllegalStateException(
                    "ONOS_USER and ONOS_PASSWORD must be set before synchronising with ONOS; "
                            + "this adapter has no default credentials");
        }
        // Basic authentication over cleartext HTTP exposes the credential to anyone on the path.
        String scheme = System.getenv().getOrDefault("ONOS_SCHEME", "https");
        if (!"https".equalsIgnoreCase(scheme) && !"true".equals(System.getenv("ONOS_ALLOW_PLAINTEXT"))) {
            throw new IllegalStateException(
                    "Refusing to send ONOS credentials over " + scheme
                            + "; set ONOS_SCHEME=https, or ONOS_ALLOW_PLAINTEXT=true to accept the risk");
        }
        String apiUrl = scheme + "://" + onosHost + ":8181/onos/v1/network/configuration";

        try {
            StringBuilder json = new StringBuilder();
            json.append("{\"devices\":{");
            int idx = 1;
            for (Node n : topology.getNodes()) {
                if (idx > 1) json.append(",");
                String dpid = String.format("of:%016x", idx++);
                json.append(String.format("\"%s\":{\"basic\":{\"name\":\"%s\",\"type\":\"SWITCH\",\"driver\":\"ovs\"}}", dpid, n.getNodeId()));
            }
            json.append("}}");

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");

            String auth = onosUser + ":" + onosPass;
            String authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", authHeader);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            // Every response code was previously logged as an informational success, so a 401 or
            // 500 left the caller believing the controller had been programmed.
            if (responseCode < 200 || responseCode >= 300) {
                throw new IllegalStateException(String.format(
                        "ONOS NetConfig sync failed with HTTP %d", responseCode));
            }
            log.info(String.format("ONOS NetConfig REST Sync: Pushed %d devices to ONOS (HTTP %d)",
                    topology.getNodeCount(), responseCode));

        } catch (IOException e) {
            // Propagate rather than warn: a caller cannot distinguish a synchronised controller
            // from an unreachable one by reading the logs after the fact.
            throw new IllegalStateException("ONOS NetConfig sync failed: " + e.getMessage(), e);
        }
    }

    /**
     * Listens to ONOS Topology Store events and computes time-scheduled LSP flows.
     */
    public List<PCEPServer.PCEPInitiateMessage> computeAndBuildPCEPDescriptions(BaseTopology onosTopology, List<WorkloadTask> tasks) {
        log.info(String.format("ONOS Adapter: Processing ONOS Topology Store state (%d nodes, %d links)...",
                onosTopology.getNodeCount(), onosTopology.getLinkCount()));

        // Push topology to ONOS NetConfig REST API
        pushNetConfigToONOS(onosTopology);

        CRPEngine.PCEComputationResult result = crpEngine.solve(onosTopology, tasks);

        List<PCEPServer.PCEPInitiateMessage> pcepMessages = new ArrayList<>();
        for (CRPEngine.CommittedFlowSchedule schedule : result.getCommittedSchedules()) {
            PCEPServer.PCEPInitiateMessage pcepMsg = PCEPServer.buildPCEPInitiate(schedule);
            pcepMessages.add(pcepMsg);
            log.fine("ONOS Adapter: Built PCEP description " + pcepMsg.getLspName());
        }
        return pcepMessages;
    }
}
