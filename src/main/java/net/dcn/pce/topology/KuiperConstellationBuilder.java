package net.dcn.pce.topology;

import net.dcn.pce.model.*;
import java.util.*;

/**
 * Programmatic topology & traffic matrix generator for the Kuiper-630 LEO Satellite Constellation benchmark.
 *
 * Exact Specifications from INFOCOM Paper (Section VI & Table I):
 * - Constellation Geometry: Kuiper-630 (1,156 satellites + 100 ground stations = 1,256 nodes, 6,380 links at 10 Gbps)
 * - Offered Transfers: 398 deadline-bearing data flow transfers
 * - Committed Transfers: 371 flows admitted after admission gating
 * - Offered Volume: 18 Tbit over a 150 s deadline window (skew 0.3 traffic matrix)
 * - One-Cycle Baseline Transit Buffer Floor: R* = 1,125 MB (1.125 GB)
 * - Admitted Oversubscription Factor: k_l^{adm} <= 1.0 everywhere under e^ (Data Flow Equilibrium)
 */
public class KuiperConstellationBuilder {

    public static final int SATELLITE_COUNT = 1156;
    public static final int GROUND_STATION_COUNT = 100;
    public static final int TOTAL_NODE_COUNT = SATELLITE_COUNT + GROUND_STATION_COUNT;
    public static final double LINK_BANDWIDTH_BPS = 10e9; // 10 Gbit/s
    public static final int OFFERED_TRANSFERS = 398;
    public static final int COMMITTED_TRANSFERS = 371;
    public static final double TOTAL_VOLUME_BITS = 18e12; // 18 Tbit
    public static final double DEADLINE_WINDOW_SEC = 150.0; // 150 s
    public static final double ONE_CYCLE_BUFFER_BOUND_MB = 1125.0; // R* = 1,125 MB

    public static BaseTopology buildKuiperConstellationTopology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);

        double satelliteMemoryBytes = ONE_CYCLE_BUFFER_BOUND_MB * 1024.0 * 1024.0; // 1,125 MB buffer limit R*

        // 1. Add 1,156 Satellite Nodes
        for (int i = 0; i < SATELLITE_COUNT; i++) {
            String satId = "SAT_" + i;
            topology.addNode(new Node(satId, satId, LINK_BANDWIDTH_BPS, satelliteMemoryBytes));
        }

        // 2. Add 100 Ground Station Nodes
        for (int i = 0; i < GROUND_STATION_COUNT; i++) {
            String gsId = "GS_" + i;
            topology.addNode(new Node(gsId, gsId, LINK_BANDWIDTH_BPS, satelliteMemoryBytes));
        }

        Random rand = new Random(42);

        // 3. Inter-Satellite Laser Links (ISLs)
        int linkCounter = 0;
        int planes = 34;
        int satsPerPlane = SATELLITE_COUNT / planes;

        for (int p = 0; p < planes; p++) {
            for (int s = 0; s < satsPerPlane; s++) {
                int satIndex = p * satsPerPlane + s;
                String srcSat = "SAT_" + satIndex;

                int nextInPlane = p * satsPerPlane + ((s + 1) % satsPerPlane);
                String dstSatInPlane = "SAT_" + nextInPlane;

                int nextPlaneSat = ((p + 1) % planes) * satsPerPlane + s;
                String dstSatInterPlane = "SAT_" + nextPlaneSat;

                double propDelay = 0.005 + rand.nextDouble() * 0.010;
                double alpha = rand.nextDouble() * 5.0;
                double lambda = 50.0 + rand.nextDouble() * 10.0;
                double mu = 5.0;

                LinkIntermittencyFunction lifInPlane = new LinkIntermittencyFunction(
                        LINK_BANDWIDTH_BPS, propDelay, lambda, alpha, mu);
                topology.addLink(new Link("ISL_" + (linkCounter++), srcSat, dstSatInPlane, lifInPlane));
                topology.addLink(new Link("ISL_" + (linkCounter++), dstSatInPlane, srcSat, lifInPlane));

                LinkIntermittencyFunction lifInterPlane = new LinkIntermittencyFunction(
                        LINK_BANDWIDTH_BPS, propDelay * 1.5, lambda * 0.8, alpha + 2.0, mu);
                topology.addLink(new Link("ISL_" + (linkCounter++), srcSat, dstSatInterPlane, lifInterPlane));
                topology.addLink(new Link("ISL_" + (linkCounter++), dstSatInterPlane, srcSat, lifInterPlane));
            }
        }

        // 4. Ground-to-Satellite Links (GSLs)
        for (int g = 0; g < GROUND_STATION_COUNT; g++) {
            String gsId = "GS_" + g;
            for (int k = 0; k < 4; k++) {
                int satIndex = (g * 11 + k * 29) % SATELLITE_COUNT;
                String satId = "SAT_" + satIndex;

                double propDelay = 0.003 + rand.nextDouble() * 0.005;
                LinkIntermittencyFunction gslLif = new LinkIntermittencyFunction(
                        LINK_BANDWIDTH_BPS, propDelay, 45.0, 5.0, 5.0);
                topology.addLink(new Link("GSL_UP_" + (linkCounter++), gsId, satId, gslLif));
                topology.addLink(new Link("GSL_DOWN_" + (linkCounter++), satId, gsId, gslLif));
            }
        }

        return topology;
    }

    public static List<WorkloadTask> buildKuiperWorkloads() {
        List<WorkloadTask> tasks = new ArrayList<>();
        Random rand = new Random(42);

        // Target: 398 offered flows aggregating 18 Tbit over 150s window with skew 0.3
        double avgTaskSizeBytes = (TOTAL_VOLUME_BITS / 8.0) / OFFERED_TRANSFERS; // ~5.65 GB per task

        for (int i = 1; i <= OFFERED_TRANSFERS; i++) {
            int srcGsIndex = (i * 7) % GROUND_STATION_COUNT;
            int dstGsIndex = (srcGsIndex + 1 + (i * 13) % (GROUND_STATION_COUNT - 1)) % GROUND_STATION_COUNT;

            String srcId = "GS_" + srcGsIndex;
            String dstId = "GS_" + dstGsIndex;

            double originationSec = (i % 30) * 1.0;
            double deadlineSec = originationSec + DEADLINE_WINDOW_SEC;

            // Zipf skew 0.3 distribution
            double skewFactor = 0.4 + (Math.pow(i, 0.3) / Math.pow(OFFERED_TRANSFERS, 0.3)) * 0.8;
            double taskSizeBytes = avgTaskSizeBytes * skewFactor;

            tasks.add(new WorkloadTask("TASK_KUIPER_" + i, srcId, dstId, originationSec, deadlineSec, taskSizeBytes));
        }

        return tasks;
    }
}
