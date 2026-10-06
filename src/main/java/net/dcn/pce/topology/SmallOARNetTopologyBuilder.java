package net.dcn.pce.topology;

import net.dcn.pce.model.*;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Small-Scale OARNet Topology & Workload Task Generator.
 * Consists of 5 key educational institutions (OSU, OU, UC, WSU, KSU),
 * 6 interconnecting links with LIF duty cycle parameters [alpha, lambda, mu],
 * and 3 remote disaster recovery backup workloads.
 */
public class SmallOARNetTopologyBuilder {

    public static BaseTopology buildSmallOARNetTopology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);

        double defaultBufferBytes = 100L * 1024L * 1024L * 1024L; // 100 GB buffer
        double defaultServiceRateBps = 10e9; // 10 Gbps

        String[] sites = {"OSU", "OU", "UC", "WSU", "KSU"};
        for (String site : sites) {
            topology.addNode(new Node(site, site, defaultServiceRateBps, defaultBufferBytes));
        }

        // 6 Small-Scale OARNet Links
        // Format: {linkID, src, dst, B (Mbps), propDelay (s), alpha (min), lambda (min), mu (min)}
        Object[][] links = {
            {"L_1", "OSU", "OU", 1000.0, 0.005, 35.0, 5.0, 0.0},
            {"L_2", "OU", "UC", 1000.0, 0.005, 5.0, 5.0, 30.0},
            {"L_3", "UC", "WSU", 1000.0, 0.005, 10.0, 5.0, 25.0},
            {"L_4", "WSU", "KSU", 1000.0, 0.005, 20.0, 5.0, 15.0},
            {"L_5", "KSU", "OSU", 1000.0, 0.005, 10.0, 5.0, 25.0},
            {"L_6", "OSU", "UC", 1000.0, 0.005, 5.0, 5.0, 30.0}
        };

        for (Object[] row : links) {
            String id = (String) row[0];
            String src = (String) row[1];
            String dst = (String) row[2];
            double bwBps = ((Double) row[3]) * 1e6;
            double prop = (Double) row[4];
            double alphaSec = ((Double) row[5]) * 60.0;
            double lambdaSec = ((Double) row[6]) * 60.0;
            double muSec = ((Double) row[7]) * 60.0;

            LinkIntermittencyFunction lif = new LinkIntermittencyFunction(bwBps, prop, lambdaSec, alphaSec, muSec);
            topology.addLink(new Link(id + "_fwd", src, dst, lif));
            topology.addLink(new Link(id + "_rev", dst, src, lif));
        }

        return topology;
    }

    public static List<WorkloadTask> buildSmallOARNetWorkloads() {
        List<WorkloadTask> tasks = new ArrayList<>();
        // Task 1: OSU -> UC (450 GB)
        tasks.add(new WorkloadTask("W_SMALL_1", "OSU", "UC", 2.0 * 3600.0, 80.0 * 3600.0, 450.0 * 1024.0 * 1024.0 * 1024.0));
        // Task 2: OU -> WSU (200 GB)
        tasks.add(new WorkloadTask("W_SMALL_2", "OU", "WSU", 5.0 * 3600.0, 78.0 * 3600.0, 200.0 * 1024.0 * 1024.0 * 1024.0));
        // Task 3: KSU -> OSU (300 GB)
        tasks.add(new WorkloadTask("W_SMALL_3", "KSU", "OSU", 12.0 * 3600.0, 44.0 * 3600.0, 300.0 * 1024.0 * 1024.0 * 1024.0));
        return tasks;
    }

    public static void exportJsonFiles() {
        try {
            BaseTopology topo = buildSmallOARNetTopology();
            List<WorkloadTask> tasks = buildSmallOARNetWorkloads();

            String topoJson = FileTopologyParser.toTopologyJson(topo);
            String tasksJson = JSONUtils.toWorkloadJson(tasks);

            try (FileWriter fw = new FileWriter("small_oarnet_topology.json")) {
                fw.write(topoJson);
            }
            try (FileWriter fw = new FileWriter("small_oarnet_workloads.json")) {
                fw.write(tasksJson);
            }
            System.out.println("[+] Exported small_oarnet_topology.json and small_oarnet_workloads.json");

        } catch (IOException e) {
            System.err.println("[!] Error exporting small OARNet files: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        exportJsonFiles();
    }
}
