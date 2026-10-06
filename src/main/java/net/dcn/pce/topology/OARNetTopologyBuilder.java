package net.dcn.pce.topology;

import net.dcn.pce.model.*;
import java.util.*;

/**
 * Builds the complete OARNet (Ohio Academic Research Network) mesh backbone topology
 * and the 22 daily remote backup workload tasks specified in Appendix B (Table B.1 & B.2)
 * and Chapter 6 (Table 6.1 & 6.2) of Dr. Omar Tahboub's Dissertation.
 */
public class OARNetTopologyBuilder {

    public static BaseTopology buildOARNetTopology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);

        double defaultBufferBytes = 500L * 1024L * 1024L * 1024L; // 500 GB buffer
        double defaultServiceRateBps = 10e9; // 10 Gbps

        // 17 PoP Nodes (n1 to n17) and 11 Member Institution Sites
        String[] sites = {
            "OSU", "OU", "UC", "WSU", "BGSU", "UT", "CSU", "CSWR", "KSU", "AU", "YSU",
            "PoP12", "PoP13", "PoP14", "PoP15", "PoP16", "PoP17"
        };

        for (String site : sites) {
            topology.addNode(new Node(site, site, defaultServiceRateBps, defaultBufferBytes));
        }

        // Table B.2: Link Intermittency Functions of Link-hops in OARNet Topology (Links 1 to 33)
        // Format: {linkID, src, dst, B (Mbps), alpha (min), lambda (min), mu (min)}
        Object[][] linkTableB2 = {
            {1, "OSU", "OU", 1000.0, 35.0, 5.0, 0.0},
            {2, "OU", "UC", 1000.0, 5.0, 5.0, 30.0},
            {3, "UC", "WSU", 1000.0, 10.0, 5.0, 25.0},
            {4, "WSU", "BGSU", 1000.0, 20.0, 5.0, 15.0},
            {5, "BGSU", "UT", 50.0, 30.0, 5.0, 5.0},
            {6, "UT", "CSU", 1000.0, 10.0, 5.0, 25.0},
            {7, "CSU", "CSWR", 1000.0, 35.0, 5.0, 0.0},
            {8, "CSWR", "KSU", 1000.0, 25.0, 5.0, 10.0},
            {9, "KSU", "AU", 1000.0, 30.0, 5.0, 5.0},
            {10, "AU", "YSU", 1000.0, 10.0, 5.0, 25.0},
            {11, "YSU", "OSU", 1000.0, 0.0, 5.0, 35.0},
            {12, "OSU", "WSU", 1000.0, 5.0, 5.0, 30.0},
            {13, "OU", "BGSU", 1000.0, 0.0, 5.0, 35.0},
            {14, "UC", "UT", 50.0, 15.0, 5.0, 20.0},
            {15, "WSU", "CSU", 50.0, 0.0, 5.0, 35.0},
            {16, "BGSU", "CSWR", 1000.0, 0.0, 5.0, 35.0},
            {17, "UT", "KSU", 1000.0, 10.0, 5.0, 25.0},
            {18, "CSU", "AU", 1000.0, 20.0, 5.0, 15.0},
            {19, "CSWR", "YSU", 50.0, 5.0, 5.0, 30.0},
            {20, "KSU", "OSU", 1000.0, 10.0, 5.0, 25.0},
            {21, "AU", "OU", 1000.0, 15.0, 5.0, 20.0},
            {22, "YSU", "UC", 1000.0, 5.0, 5.0, 30.0},
            {23, "OSU", "PoP12", 10000.0, 0.0, 5.0, 35.0},
            {24, "OU", "PoP13", 10000.0, 0.0, 5.0, 35.0},
            {25, "UC", "PoP14", 10000.0, 0.0, 5.0, 35.0},
            {26, "WSU", "PoP15", 10000.0, 0.0, 5.0, 35.0},
            {27, "BGSU", "PoP16", 100.0, 0.0, 5.0, 35.0},
            {28, "UT", "PoP17", 100.0, 0.0, 5.0, 35.0},
            {29, "PoP12", "PoP13", 100.0, 0.0, 5.0, 35.0},
            {30, "PoP13", "PoP14", 100.0, 0.0, 5.0, 35.0},
            {31, "PoP14", "PoP15", 100.0, 0.0, 5.0, 35.0},
            {32, "PoP15", "PoP16", 100.0, 0.0, 5.0, 35.0},
            {33, "PoP16", "PoP17", 100.0, 0.0, 5.0, 35.0}
        };

        for (Object[] row : linkTableB2) {
            int linkId = (int) row[0];
            String src = (String) row[1];
            String dst = (String) row[2];
            double bwBps = ((Double) row[3]) * 1e6;
            double alphaSec = ((Double) row[4]) * 60.0;
            double lambdaSec = ((Double) row[5]) * 60.0;
            double muSec = ((Double) row[6]) * 60.0;

            LinkIntermittencyFunction lif = new LinkIntermittencyFunction(bwBps, 0.005, lambdaSec, alphaSec, muSec);
            topology.addLink(new Link("L_" + linkId + "_fwd", src, dst, lif));
            topology.addLink(new Link("L_" + linkId + "_rev", dst, src, lif));
        }

        return topology;
    }

    public static List<WorkloadTask> buildOARNetWorkloads() {
        List<WorkloadTask> tasks = new ArrayList<>();

        // Table 6.2 Remote Data Backup Workloads
        Object[][] workloads = {
            {1, "OSU", "YSU", 2.0, 80.0, 450.0},
            {2, "OU", "UT", 5.0, 78.0, 200.0},
            {3, "UC", "CSWR", 0.0, 50.0, 250.0},
            {4, "WSU", "KSU", 8.0, 77.0, 350.0},
            {5, "BGSU", "AU", 0.0, 92.0, 350.0},
            {6, "UT", "YSU", 2.0, 37.0, 75.0},
            {7, "CSU", "AU", 9.0, 58.0, 250.0},
            {8, "CSWR", "UT", 0.0, 50.0, 350.0},
            {9, "KSU", "WSU", 12.0, 44.0, 300.0},
            {10, "AU", "OU", 10.0, 28.0, 250.0},
            {11, "YSU", "OSU", 0.0, 35.0, 200.0},
            {12, "OSU", "UC", 100.0, 120.0, 450.0},
            {13, "OU", "AU", 78.0, 110.0, 200.0},
            {14, "UC", "YSU", 0.0, 50.0, 250.0},
            {15, "WSU", "UT", 8.0, 77.0, 350.0},
            {16, "BGSU", "CSU", 0.0, 92.0, 350.0},
            {17, "UT", "UC", 40.0, 75.0, 75.0},
            {18, "CSU", "WSU", 58.0, 90.0, 250.0},
            {19, "CSWR", "OSU", 50.0, 90.0, 350.0},
            {20, "KSU", "OU", 12.0, 44.0, 300.0},
            {21, "AU", "WSU", 10.0, 28.0, 250.0},
            {22, "YSU", "UC", 35.0, 70.0, 200.0}
        };

        for (Object[] w : workloads) {
            int j = (int) w[0];
            String u = (String) w[1];
            String v = (String) w[2];
            double oSec = ((Double) w[3]) * 3600.0;
            double dlSec = ((Double) w[4]) * 3600.0;
            double sizeBytes = ((Double) w[5]) * 1024.0 * 1024.0 * 1024.0;

            tasks.add(new WorkloadTask("W_" + j, u, v, oSec, dlSec, sizeBytes));
        }
        return tasks;
    }
}
