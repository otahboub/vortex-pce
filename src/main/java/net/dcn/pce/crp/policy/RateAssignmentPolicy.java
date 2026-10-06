package net.dcn.pce.crp.policy;

import net.dcn.pce.crp.SchedulingCapacity;
import net.dcn.pce.model.*;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.rib.NRIB;

import java.util.List;

/**
 * Stage 4 (F_prop): Rate Assignment & Constraint Propagation Policy Interface.
 * Governs the buffer-latency trade-off at admission time by assigning a committed flow rate.
 */
@FunctionalInterface
public interface RateAssignmentPolicy {

    /**
     * Calculates the committed flow rate (in bps) for a task along a route.
     *
     * @param task Workload task request
     * @param route Committed path
     * @param topology Network graph
     * @param lrib Link Resource Information Base
     * @param nrib Node Resource Information Base
     * @param utilizationHeadroom maximum fraction of residual capacity that may be admitted
     * @return committed rate in bps, or a non-finite value when this policy cannot admit the task
     */
    double assignRate(WorkloadTask task, List<Link> route, BaseTopology topology,
                      LRIB lrib, NRIB nrib, double utilizationHeadroom);

    /**
     * Deadline-window equilibrium: the slowest rate that still completes the flow by its deadline.
     * Under store-and-forward (scheduled contacts) the route transmits the complete volume on every
     * hop in turn, so e_hat = |P| V / (d - r - sum(propagation)); in R-det each hop is weighted by the
     * inverse of its task-horizon availability fraction and execution is checked against explicit
     * contacts. A continuous R_STATIC backbone forwards as data arrives, so its hops overlap and
     * e_hat = V / (d - r - sum(propagation)). See {@link SchedulingCapacity#forwardsAsItArrives}.
     */
    RateAssignmentPolicy DATA_FLOW_EQUILIBRIUM = (task, route, topology, lrib, nrib, headroom) -> {
        if (route.isEmpty() || !Double.isFinite(headroom) || headroom <= 0 || headroom > 1) {
            return Double.NaN;
        }

        double requiredRateBps = SchedulingCapacity.minimumWholeFlowRateBps(
                task, route, topology.getRegime());
        if (!Double.isFinite(requiredRateBps) || requiredRateBps <= 0) {
            return Double.NaN;
        }

        net.dcn.pce.crp.SolveDeadline.checkpoint("rate assignment");
        double residualCeilingBps = SchedulingCapacity.residualCeilingBps(
                task, route, topology.getRegime(), lrib, headroom);
        if (requiredRateBps > residualCeilingBps + 1e-6) {
            return Double.NaN;
        }
        double equilibriumRateBps = Math.max(1.0, requiredRateBps);
        // Contact-scheduled routes (R_DET, and R_STOCH-with-contacts) burst at the contact line rate:
        // the availability-weighted equilibrium can be infeasible on short scheduled windows, so fall
        // back to the fastest headroom-safe rate that realizes the ordered-connection timing. Continuous
        // R_STATIC links keep the whole-flow equilibrium as before.
        boolean contactScheduled = topology.getRegime() == ContactRegime.R_DET
                || route.stream().anyMatch(Link::hasExplicitContactPlan);
        if (!contactScheduled
                || Double.isFinite(SchedulingCapacity.earliestCompletionSec(
                        task, route, topology.getRegime(), lrib, equilibriumRateBps))) {
            return equilibriumRateBps;
        }
        return Double.isFinite(SchedulingCapacity.earliestCompletionSec(
                task, route, topology.getRegime(), lrib, residualCeilingBps))
                ? residualCeilingBps
                : Double.NaN;
    };

    /** Compatibility policy retaining the former residual-bottleneck headroom heuristic. */
    RateAssignmentPolicy RESIDUAL_BOTTLENECK_HEADROOM = (task, route, topology, lrib, nrib, headroom) -> {
        if (route.isEmpty() || !Double.isFinite(headroom) || headroom <= 0 || headroom > 1) {
            return Double.NaN;
        }
        double residualBottleneckBps = route.stream()
                .mapToDouble(link -> lrib.getMaximumAvailableCap(
                        link.getLinkId(), SchedulingCapacity.capacityBps(link, topology.getRegime()),
                        task.getOriginationTimeSec(), task.getEffectiveDeadlineSec()))
                .min()
                .orElse(0.0);
        return Math.max(1.0, headroom * residualBottleneckBps);
    };

    // Comparison Policy: FAIRCAP (First-hop fair share rate)
    RateAssignmentPolicy FAIRCAP = (task, route, topology, lrib, nrib, headroom) -> {
        if (route.isEmpty()) return task.getDemandedRateBps();
        Link firstLink = route.get(0);
        double firstHopBps = firstLink.getLif().getBaseBandwidthBps();
        double availBps = lrib.getMaximumAvailableCap(firstLink.getLinkId(), firstHopBps,
                task.getOriginationTimeSec(), task.getDeadlineSec());
        return Math.max(1.0, availBps);
    };

    // Comparison Policy: LINE_RATE (Unrestrained line-rate forwarding)
    RateAssignmentPolicy LINE_RATE = (task, route, topology, lrib, nrib, headroom) -> {
        if (route.isEmpty()) return task.getDemandedRateBps();
        Link firstLink = route.get(0);
        return firstLink.getLif().getBaseBandwidthBps();
    };
}
