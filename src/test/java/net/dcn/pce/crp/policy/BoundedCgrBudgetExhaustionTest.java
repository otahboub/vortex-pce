package net.dcn.pce.crp.policy;

import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.rib.NRIB;
import net.dcn.pce.topology.FileTopologyParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOUNDED_CGR when a zero-delay relay mesh exhausts the label budget. Eight always-on relays are
 * fully meshed with no propagation delay, so a bundle entering the mesh has about 13,700 loop-free
 * partial paths, all arriving within a second, while the only contact to the destination opens an
 * hour later. Labels are expanded in arrival order, so the 10,000-expansion budget is spent inside
 * the mesh before any label reaches the destination, and BOUNDED_CGR used to return no route at
 * all although one exists. Constellations whose satellites keep always-on inter-satellite links
 * behave this way.
 */
class BoundedCgrBudgetExhaustionTest {

    private static final String DESTINATION = "99";
    private static final int FIRST_RELAY = 10;
    private static final int RELAYS = 8;

    @Test
    void everySourceWithATemporalPathGetsACandidateRoute() {
        BaseTopology topology = FileTopologyParser.parseTopologyJson(topologyJson(plan()));
        List<String> refused = new ArrayList<>();
        for (String source : List.of("1", "2", "3")) {
            WorkloadTask task = new WorkloadTask("b" + source, source, DESTINATION, 0.0, 86400.0, 1000.0);
            List<List<Link>> routes = RouteGenerationPolicy.BOUNDED_CGR
                    .generateCandidateRoutes(task, topology, new LRIB(), new NRIB());
            if (routes.isEmpty()) {
                refused.add(source);
                continue;
            }
            for (List<Link> route : routes) {
                assertEquals(source, route.get(0).getSourceNodeId());
                assertEquals(DESTINATION, route.get(route.size() - 1).getDestinationNodeId());
            }
        }
        assertEquals(List.of(), refused, "sources refused although they have a temporal path");
        // Source 4 reaches the mesh only after the destination's contact has closed.
        WorkloadTask late = new WorkloadTask("b4", "4", DESTINATION, 0.0, 86400.0, 1000.0);
        assertTrue(RouteGenerationPolicy.BOUNDED_CGR
                .generateCandidateRoutes(late, topology, new LRIB(), new NRIB()).isEmpty(),
                "source 4 has no temporal path to the destination");
    }

    /** The scenario as ION contact-plan lines: 125 kB/s contacts, zero range inside the mesh. */
    private static List<String> plan() {
        List<String> lines = new ArrayList<>();
        for (int a = FIRST_RELAY; a < FIRST_RELAY + RELAYS; a++) {
            for (int b = FIRST_RELAY; b < FIRST_RELAY + RELAYS; b++) {
                if (a != b) {
                    lines.add(String.format(Locale.ROOT, "a contact +0 +86400 %d %d 125000", a, b));
                    lines.add(String.format(Locale.ROOT, "a range +0 +86400 %d %d 0", a, b));
                }
            }
        }
        lines.add("a contact +0 +86400 1 10 125000");
        lines.add("a contact +0 +86400 2 11 125000");
        lines.add("a contact +0 +86400 3 12 125000");
        lines.add("a contact +80000 +86400 4 10 125000");
        lines.add(String.format(Locale.ROOT, "a contact +3600 +7200 %d %s 125000",
                FIRST_RELAY + RELAYS - 1, DESTINATION));
        return lines;
    }

    /**
     * An ION-format contact plan as R_DET topology JSON: one link per ordered node pair, rate =
     * bytes/s x 8, propagation from a later "range" line (else 1 s).
     */
    private static String topologyJson(List<String> plan) {
        Map<String, double[]> links = new TreeMap<>(); // "src-dst" -> {rateBps, owlt}
        Map<String, List<double[]>> windows = new TreeMap<>();
        java.util.Set<String> nodes = new java.util.TreeSet<>();
        for (String raw : plan) {
            String[] parts = raw.trim().split("\\s+");
            if (parts.length < 6 || !parts[0].equals("a")) {
                continue;
            }
            double start = Double.parseDouble(parts[2].replace("+", ""));
            double end = Double.parseDouble(parts[3].replace("+", ""));
            String key = parts[4] + "-" + parts[5];
            nodes.add(parts[4]);
            nodes.add(parts[5]);
            if (parts[1].equals("contact") && !parts[4].equals(parts[5])) {
                double rateBps = Double.parseDouble(parts[6]) * 8.0;
                links.computeIfAbsent(key, k -> new double[]{rateBps, 1.0})[0] = rateBps;
                windows.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[]{start, end});
            } else if (parts[1].equals("range") && links.containsKey(key)) {
                links.get(key)[1] = Double.parseDouble(parts[6]);
            }
        }
        StringBuilder json = new StringBuilder("{\"regime\": \"R_DET\", \"nodes\": [");
        String separator = "";
        for (String node : nodes) {
            json.append(separator).append(String.format(Locale.ROOT,
                    "{\"nodeId\": \"%s\", \"serviceRateBps\": 1e7, \"reservoirCapacityBytes\": 5e8}", node));
            separator = ",";
        }
        json.append("], \"links\": [");
        separator = "";
        for (Map.Entry<String, double[]> link : links.entrySet()) {
            String[] ends = link.getKey().split("-");
            json.append(separator).append(String.format(Locale.ROOT,
                    "{\"linkId\": \"%s\", \"sourceNodeId\": \"%s\", \"destinationNodeId\": \"%s\", "
                            + "\"baseBandwidthBps\": %s, \"propagationDelaySec\": %s, \"contacts\": [",
                    link.getKey(), ends[0], ends[1], link.getValue()[0], link.getValue()[1]));
            List<double[]> sorted = new ArrayList<>(windows.get(link.getKey()));
            sorted.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
            String windowSeparator = "";
            for (double[] window : sorted) {
                json.append(windowSeparator).append(String.format(Locale.ROOT,
                        "{\"startSec\": %s, \"endSec\": %s}", window[0], window[1]));
                windowSeparator = ",";
            }
            json.append("]}");
            separator = ",";
        }
        return json.append("]}").toString();
    }
}
