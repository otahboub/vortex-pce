package net.dcn.pce;

import net.dcn.pce.adapter.ODLAdapter;
import net.dcn.pce.adapter.ONOSAdapter;
import net.dcn.pce.config.OperatorConfiguration;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.crp.policy.*;
import net.dcn.pce.model.*;
import net.dcn.pce.pcep.PCEPServer;
import net.dcn.pce.topology.FileTopologyParser;
import net.dcn.pce.topology.KuiperConstellationBuilder;
import net.dcn.pce.topology.OARNetTopologyBuilder;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Main Execution Runner for DCN@MPLS Flow-Aware PCE Controller Engine.
 * Runs scenarios matching Dr. Omar Tahboub's Dissertation (Table 6.3 & K = MAX candidate paths) & INFOCOM paper benchmarks:
 * 1. OARNet 11-Institution Peer-to-Peer Disaster Recovery Data Backup Scenario (K = MAX)
 * 2. Routing-policy comparison under line-rate assignment and K = MAX
 * 3. Kuiper-630 LEO Satellite Constellation Mesh Scenario (1,256 Nodes, 398 Workloads)
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("==================================================================================");
        System.out.println("     VORTEXPCE FLOW-AWARE PATH COMPUTATION ELEMENT (PCE) CONTROLLER ENGINE        ");
        System.out.println("==================================================================================\n");

        if (args.length >= 1 && "--server".equalsIgnoreCase(args[0])) {
            startRestServer(8080);
        } else if (args.length >= 2) {
            runFromExternalInputFiles(args[0], args[1]);
        } else {
            runBuiltInBenchmarks();
            System.out.println("\n[+] Starting live REST API & Health Check Server on port 8080...");
            startRestServer(8080);
        }
    }

    private static void startRestServer(int port) {
        // Parsed before anything is bound so that an invalid policy name or an unreadable
        // topology file fails startup instead of surfacing as unadmitted workloads later.
        OperatorConfiguration operatorConfiguration = OperatorConfiguration.fromProcessEnvironment();

        try {
            BaseTopology topo = loadConfiguredTopology(operatorConfiguration);
            // An unset route policy resolves to the topology regime's default from here on.
            OperatorConfiguration configuration = operatorConfiguration.forRegime(topo.getRegime());
            configuration.validateForRegime(topo.getRegime());

            net.dcn.pce.northbound.PCERestServer server =
                    new net.dcn.pce.northbound.PCERestServer(port, topo, configuration);
            server.start();
            // Stop cleanly on SIGTERM / `docker stop`. This is the only thing that reaches
            // PCERestServer.stop() on the --server path, and stop() is what demotes and calls
            // PostgresLeadership.releaseIfHeld(). Without this hook a planned stop or rolling upgrade
            // never releases the lease, so a successor waits out the full lease TTL instead of
            // promoting at once -- the graceful-release path would be dead in production even though
            // its unit test passes by calling it directly. stop() is idempotent and null-guarded, so
            // running here and on any other path is safe.
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "vortex-shutdown"));
            configuration.describe().forEach((key, value) ->
                    System.out.printf("    [+] Effective config: %-22s %s\n", key, value));
            System.out.println("    [+] Server running! Endpoints: http://localhost:8080/healthz, /livez, /readyz, /metrics, /api/v1/config, /api/v1/solve, DELETE /api/v1/tasks/{taskId}");
        } catch (IOException e) {
            System.err.println("[!] Failed to start REST server: " + e.getMessage());
            throw new IllegalStateException("Unable to start the REST server on port " + port, e);
        }
    }

    private static BaseTopology loadConfiguredTopology(OperatorConfiguration configuration)
            throws IOException {
        String topologyFile = configuration.getTopologyFile().orElse(null);
        if (topologyFile == null) {
            return OARNetTopologyBuilder.buildOARNetTopology();
        }

        File file = new File(topologyFile);
        if (!file.isFile()) {
            throw new IllegalArgumentException(
                    "Configured " + OperatorConfiguration.ENV_TOPOLOGY_FILE
                            + " is not a readable file: " + topologyFile);
        }
        BaseTopology topo = FileTopologyParser.parseTopologyFile(file);
        System.out.printf("    [+] Operator Configured Topology Loaded: %s (%d nodes, %d links, Regime: %s)\n",
                file.getName(), topo.getNodeCount(), topo.getLinkCount(), topo.getRegime());
        return topo;
    }



    /**
     * Generalizes PCE engine execution from external topology and workload JSON files.
     */
    private static void runFromExternalInputFiles(String topologyFilePath, String workloadsFilePath) {
        System.out.printf(">>> SCENARIO: Loading External Input Files\n");
        System.out.printf("    Topology File: %s\n", topologyFilePath);
        System.out.printf("    Workload File: %s\n\n", workloadsFilePath);

        try {
            BaseTopology customTopology = FileTopologyParser.parseTopologyFile(new File(topologyFilePath));
            List<WorkloadTask> customWorkloads = JSONUtils.parseWorkloadJsonFile(new File(workloadsFilePath));

            System.out.printf("    Successfully Parsed: %d Nodes, %d Links, %d Workload Tasks\n\n",
                    customTopology.getNodeCount(), customTopology.getLinkCount(), customWorkloads.size());

            String routePolicyName = OperatorConfiguration.defaultRoutePolicyNameForRegime(
                    customTopology.getRegime());
            RouteGenerationPolicy routePolicy = OperatorConfiguration.routePolicyByName(routePolicyName);
            System.out.printf("    Contact Regime: %s -> Route Generation Policy: %s\n\n",
                    customTopology.getRegime(), routePolicyName);

            CRPEngine engine = new CRPEngine()
                    .withFSelect(TaskSelectionPolicy.LWEEF)
                    .withFGenerate(routePolicy)
                    .withFRoute(PathSelectionPolicy.EAP)
                    .withFProp(RateAssignmentPolicy.LINE_RATE)
                    .withUtilizationHeadroom(1.0);



            CRPEngine.PCEComputationResult result = engine.solve(customTopology, customWorkloads);
            printComputationSummary("LWEEF + EAP + LINE_RATE bounded-K", result);

            JSONUtils.saveResultToFile(result, "custom_results.json");
            System.out.println("    [+] Exported detailed flow schedules to custom_results.json");

            // Build in-memory PCEP initiation descriptions.
            ODLAdapter odlAdapter = new ODLAdapter(engine);
            List<PCEPServer.PCEPInitiateMessage> pcepMsgs = odlAdapter.computePCEPDescriptions(customTopology, customWorkloads);
            System.out.printf("\n    Generated %d in-memory PCEP initiate descriptions.\n", pcepMsgs.size());

        } catch (IOException e) {
            System.err.println("[!] Error parsing external input files: " + e.getMessage());
        }
    }

    private static void runBuiltInBenchmarks() {
        try {
            // SCENARIO 1: OARNet Disaster Recovery Data Backup Scenario (K = MAX Exhaustive DFS/BFS)
            System.out.println(">>> SCENARIO 1: OARNet 11-Institution Disaster Recovery Scenario (Dissertation Table 6.3 - K = MAX)");
            BaseTopology oarNetTopo = OARNetTopologyBuilder.buildOARNetTopology();
            List<WorkloadTask> oarNetTasks = OARNetTopologyBuilder.buildOARNetWorkloads();

            System.out.printf("    Nodes: %d, Links: %d, Offered Workload Tasks: %d\n\n",
                    oarNetTopo.getNodeCount(), oarNetTopo.getLinkCount(), oarNetTasks.size());

            // 1. Classic Solver Configuration (EWOF + MIN-HOP + LINE_RATE)
            CRPEngine classicEngine = new CRPEngine()
                    .withFSelect(TaskSelectionPolicy.EWOF)
                    .withFGenerate(RouteGenerationPolicy.BFS_MIN_HOP)
                    .withFRoute(PathSelectionPolicy.MIN_HOP)
                    .withFProp(RateAssignmentPolicy.LINE_RATE);

            CRPEngine.PCEComputationResult classicResult = classicEngine.solve(oarNetTopo, oarNetTasks);
            classicResult.setPolicyName("Classic Solver (EWOF + MIN-HOP + LINE_RATE)");
            printComputationSummary("Classic Solver (EWOF + MIN-HOP + LINE_RATE)", classicResult);

            // 2. LWEEF/EAP comparison configuration (K=MAX Path Search)
            CRPEngine optimizedEngine = new CRPEngine()
                    .withFSelect(TaskSelectionPolicy.LWEEF)
                    .withFGenerate(RouteGenerationPolicy.K_MAX_EXHAUSTIVE)
                    .withFRoute(PathSelectionPolicy.EAP)
                    .withFProp(RateAssignmentPolicy.LINE_RATE)
                    .withUtilizationHeadroom(1.0);

            CRPEngine.PCEComputationResult optimizedResult = optimizedEngine.solve(oarNetTopo, oarNetTasks);
            optimizedResult.setPolicyName("LWEEF/EAP Comparison (LINE_RATE K=MAX)");
            printComputationSummary("LWEEF/EAP Comparison (LINE_RATE K=MAX)", optimizedResult);

            JSONUtils.saveResultToFile(optimizedResult, "oarnet_results.json");
            System.out.println("    [+] Saved detailed flow schedule result to oarnet_results.json");

            // SCENARIO 2: Rate-policy comparison. A zero-vs-zero buffer result is not evidence of separation.
            System.out.println("\n>>> SCENARIO 2: Rate-Policy Comparison (OARNet)");
            System.out.println("    -------------------------------------------------------------------------");
            System.out.printf("    Classic (Buffer-Blind)  -> Met Deadlines: %d/%d (%.1f%%), Peak Transit Buffer: %.2f GB\n",
                    classicResult.getMetDeadlineCount(), oarNetTasks.size(), classicResult.getSuccessRatioPercent(),
                    classicResult.getMaxNetworkTransitBufferBytes() / (1024.0 * 1024.0 * 1024.0));
            System.out.printf("    LWEEF/EAP (Line Rate)   -> Met Deadlines: %d/%d (%.1f%%), Peak Transit Buffer: %.2f GB\n",
                    optimizedResult.getMetDeadlineCount(), oarNetTasks.size(), optimizedResult.getSuccessRatioPercent(),
                    optimizedResult.getMaxNetworkTransitBufferBytes() / (1024.0 * 1024.0 * 1024.0));
            System.out.println("    -------------------------------------------------------------------------");
            double classicBuffer = classicResult.getMaxNetworkTransitBufferBytes();
            double optimizedBuffer = optimizedResult.getMaxNetworkTransitBufferBytes();
            if (classicBuffer > optimizedBuffer) {
                System.out.printf("    --> OBSERVED: LWEEF/EAP reduced modeled peak transit storage by %.2f GB in this scenario.\n",
                        (classicBuffer - optimizedBuffer) / (1024.0 * 1024.0 * 1024.0));
            } else {
                System.out.println("    --> OBSERVED: This scenario produced no nonzero buffer separation; no bounding claim is inferred.");
            }

            // SCENARIO 3: Kuiper-630 LEO Satellite Constellation Mesh (1,256 Nodes)
            System.out.println("\n>>> SCENARIO 3: Kuiper-630 LEO Satellite Constellation Mesh (1,256 Nodes)");
            BaseTopology kuiperTopo = KuiperConstellationBuilder.buildKuiperConstellationTopology();
            List<WorkloadTask> kuiperTasks = KuiperConstellationBuilder.buildKuiperWorkloads();

            System.out.printf("    Constellation Nodes: %d, Active Links: %d, Offered Flow Tasks: %d\n\n",
                    kuiperTopo.getNodeCount(), kuiperTopo.getLinkCount(), kuiperTasks.size());

            CRPEngine kuiperEngine = new CRPEngine()
                    .withFSelect(TaskSelectionPolicy.LWEEF)
                    .withFGenerate(RouteGenerationPolicy.BFS_MIN_HOP)
                    .withFRoute(PathSelectionPolicy.EAP)
                    .withFProp(RateAssignmentPolicy.LINE_RATE)
                    .withUtilizationHeadroom(1.0)
                    .withEnforceAdmissionGate(true);

            CRPEngine.PCEComputationResult kuiperResult = kuiperEngine.solve(kuiperTopo, kuiperTasks);
            kuiperResult.setPolicyName("Kuiper-630 Constellation Benchmark");
            printComputationSummary("Kuiper-630 Constellation Benchmark", kuiperResult);

            JSONUtils.saveResultToFile(kuiperResult, "kuiper_results.json");
            System.out.println("    [+] Saved detailed flow schedule result to kuiper_results.json");

            // SCENARIO 4: Southbound PCEP Message Generation
            System.out.println("\n>>> SCENARIO 4: Southbound PCEP Message Generation (ODL & ONOS Adapters)");
            ODLAdapter odlAdapter = new ODLAdapter(optimizedEngine);
            List<PCEPServer.PCEPInitiateMessage> odlMessages = odlAdapter.computePCEPDescriptions(oarNetTopo, oarNetTasks);

            System.out.println("\n    Sample Generated PCEP Initiate Message:");
            if (!odlMessages.isEmpty()) {
                System.out.println("    " + odlMessages.get(0).toPCEPString().replace("\n", "\n    "));
            }

            System.out.println("\n==================================================================================");
            System.out.println("                      ALL SCENARIOS COMPLETED SUCCESSFULLY!                       ");
            System.out.println("==================================================================================");

        } catch (IOException e) {
            System.err.println("[!] Error saving result files: " + e.getMessage());
        }
    }

    private static void printComputationSummary(String scenarioName, CRPEngine.PCEComputationResult result) {
        System.out.println("    [" + scenarioName + "]");
        System.out.printf("    |-- Admitted / Offered    : %d / %d (Admission Ratio: %.1f%%)\n",
                result.getCommittedFlowCount(), result.getOfferedFlowCount(), result.getFlowAdmissionRatioPercent());
        System.out.printf("    |-- Met Deadlines (Committed): %d / %d (%.1f%%)\n",
                result.getMetDeadlineCount(), result.getCommittedFlowCount(), result.getCommittedSuccessRatioPercent());
        System.out.printf("    |-- Met Deadlines (Overall): %d / %d (%.1f%%)\n",
                result.getMetDeadlineCount(), result.getOfferedFlowCount(), result.getSuccessRatioPercent());
        System.out.printf("    |-- Total Completion Earliness: %.2f hours\n", result.getTotalEarlinessSec() / 3600.0);
        System.out.printf("    |-- Peak Network Transit Buffer: %.2f GB\n", result.getMaxNetworkTransitBufferBytes() / (1024.0 * 1024.0 * 1024.0));
        System.out.printf("    +-- Engine Execution Time : %d ms\n", result.getComputationTimeMs());
    }

}
