package net.dcn.pce.crp.policy;

import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.rib.NRIB;

import java.util.*;

/**
 * Stage 1 (F_select): Task / Workload Selection Policy Interface.
 * Selects the most critical task from the unassigned workload pool.
 *
 * Policies Included:
 * 1. LWEEF: Implemented approximation of Largest Workload Estimated Earliness First.
 *    - Computes tentative shortest path P_h.
 *    - Computes bottleneck effective bandwidth B_eff(P_h) = min_{e in P_h} C_e^eff.
 *    - Computes estimated duration delta_t = TaskSize / B_eff.
 *    - Ranks tasks by Estimated Earliness EE = Deadline - (Origination + delta_t).
 * 2. LWF: Comparison Policy (Largest Workload First approximation).
 * 3. FCFS / EWOF: First Come First Served.
 *
 * Author: Dr. Omar Y. Tahboub
 */
@FunctionalInterface
public interface TaskSelectionPolicy {

    WorkloadTask selectMostCriticalTask(List<WorkloadTask> unassignedTasks, BaseTopology topology, LRIB lrib, NRIB nrib);

    // Built-in Policy: FCFS (First Come First Served)
    TaskSelectionPolicy FCFS = (tasks, topology, lrib, nrib) ->
            tasks.stream()
                 .min(Comparator.comparingDouble(WorkloadTask::getOriginationTimeSec))
                 .orElse(null);

    // Built-in Policy: EWOF (Earliest Workload Origination First)
    TaskSelectionPolicy EWOF = FCFS;

    // Comparison Policy: LWF (Largest Workload First Approximation)
    TaskSelectionPolicy LWF = (tasks, topology, lrib, nrib) -> {
        if (tasks.isEmpty()) return null;
        WorkloadTask mostCritical = null;
        double minEarliness = Double.MAX_VALUE;

        for (WorkloadTask task : tasks) {
            double estDuration = task.getTaskSizeBits() / 1e8; // baseline estimate
            double estCompletion = task.getOriginationTimeSec() + estDuration;
            double earliness = task.getDeadlineSec() - estCompletion;
            if (earliness < minEarliness) {
                minEarliness = earliness;
                mostCritical = task;
            }
        }
        return mostCritical != null ? mostCritical : tasks.get(0);
    };

    // Implemented LWEEF approximation using a nominal-capacity minimum-hop estimate.
    TaskSelectionPolicy LWEEF = (tasks, topology, lrib, nrib) -> {
        if (tasks.isEmpty()) return null;
        // Ranking a singleton cannot change the selected task. Avoid traversing the topology here;
        // route generation will perform the one graph search that is actually needed. Apart from
        // saving a complete BFS, this preserves the exact multi-task LWEEF ordering semantics.
        if (tasks.size() == 1) return tasks.get(0);
        WorkloadTask mostCritical = null;
        double minEarlinessSec = Double.MAX_VALUE;

        for (WorkloadTask task : tasks) {
            net.dcn.pce.crp.SolveDeadline.checkpoint("task selection");
            // 1. Compute tentative shortest path P_h over topology
            List<Link> tentativePath = findShortestPathBFS(topology, task.getSourceNodeId(), task.getDestinationNodeId());

            double bEff = 1e9; // Default 1 Gbps fallback if empty
            if (tentativePath != null && !tentativePath.isEmpty()) {
                double minCap = Double.MAX_VALUE;
                for (Link link : tentativePath) {
                    double effCap = link.getLif().getEffectiveBandwidthBps();
                    if (effCap < minCap) {
                        minCap = effCap;
                    }
                }
                bEff = Math.max(1.0, minCap);
            }

            // 2. Compute estimated transmission duration delta_t = TaskSize / B_eff
            double estDurationSec = task.getTaskSizeBits() / bEff;

            // 3. Compute Estimated Completion Earliness EE = Deadline - (Origination + delta_t)
            double estCompletionSec = task.getOriginationTimeSec() + estDurationSec;
            double estimatedEarlinessSec = task.getDeadlineSec() - estCompletionSec;

            // Rank by tightest estimated earliness
            if (estimatedEarlinessSec < minEarlinessSec) {
                minEarlinessSec = estimatedEarlinessSec;
                mostCritical = task;
            }
        }
        return mostCritical != null ? mostCritical : tasks.get(0);
    };

    private static List<Link> findShortestPathBFS(BaseTopology topology, String src, String dst) {
        if (src.equals(dst)) return Collections.emptyList();
        Queue<String> queue = new LinkedList<>();
        Map<String, Link> parentLink = new HashMap<>();
        Set<String> visited = new HashSet<>();

        queue.add(src);
        visited.add(src);

        while (!queue.isEmpty()) {
            net.dcn.pce.crp.SolveDeadline.checkpoint("task selection");
            String current = queue.poll();
            if (current.equals(dst)) break;

            for (Link link : topology.getOutgoingLinks(current)) {
                String next = link.getDestinationNodeId();
                if (!visited.contains(next)) {
                    visited.add(next);
                    parentLink.put(next, link);
                    queue.add(next);
                }
            }
        }

        if (!visited.contains(dst)) return Collections.emptyList();

        List<Link> path = new ArrayList<>();
        String curr = dst;
        while (!curr.equals(src)) {
            Link l = parentLink.get(curr);
            if (l == null) break;
            path.add(0, l);
            curr = l.getSourceNodeId();
        }
        return path;
    }
}
