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
     *
     * <p>On continuous R_STATIC links this is {@link #DATA_FLOW_EQUILIBRIUM_AVERAGED} unchanged. On
     * contact-scheduled routes (R_DET, and R_STOCH routes with explicit contacts) the
     * availability-weighted e_hat is only an estimate: averaging a few short contacts over a long
     * deadline window can put it above the residual ceiling for a task that the explicit contacts can
     * still carry in time. So when e_hat fits under the ceiling the decision is exactly the averaged
     * policy's; when it does not, the task is no longer refused on the estimate but judged on the
     * contacts themselves: the ceiling if the task completes by its deadline at it, otherwise the
     * fastest lower rate that does ({@link SchedulingCapacity#fastestSchedulableRateBps}), and a
     * refusal only if none does. Every rate returned has been replayed against the explicit contacts.
     */
    RateAssignmentPolicy DATA_FLOW_EQUILIBRIUM = (task, route, topology, lrib, nrib, headroom) -> {
        boolean contactScheduled = topology.getRegime() == ContactRegime.R_DET
                || route.stream().anyMatch(Link::hasExplicitContactPlan);
        double averaged = RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM_AVERAGED.assignRate(task, route, topology, lrib, nrib, headroom);
        if (!contactScheduled || Double.isFinite(averaged)
                || route.isEmpty() || !Double.isFinite(headroom) || headroom <= 0 || headroom > 1) {
            return averaged;
        }
        double requiredRateBps = SchedulingCapacity.minimumWholeFlowRateBps(task, route, topology.getRegime());
        double residualCeilingBps = SchedulingCapacity.residualCeilingBps(
                task, route, topology.getRegime(), lrib, headroom);
        if (!Double.isFinite(requiredRateBps) || requiredRateBps <= residualCeilingBps + 1e-6
                || !Double.isFinite(residualCeilingBps) || residualCeilingBps <= 0) {
            return averaged;    // refused for a reason other than the estimate exceeding the ceiling
        }
        return SchedulingCapacity.fastestSchedulableRateBps(
                task, route, topology.getRegime(), lrib, residualCeilingBps);
    };

    /**
     * Deadline-window equilibrium: the slowest rate that still completes the flow by its deadline.
     * Under store-and-forward (scheduled contacts) the route transmits the complete volume on every
     * hop in turn, so e_hat = |P| V / (d - r - sum(propagation)); in R-det each hop is weighted by the
     * inverse of its task-horizon availability fraction and execution is checked against explicit
     * contacts. A continuous R_STATIC backbone forwards as data arrives, so its hops overlap and
     * e_hat = V / (d - r - sum(propagation)). See {@link SchedulingCapacity#forwardsAsItArrives}.
     *
     * <p>This is the policy as it shipped until the scheduled-contact fix in
     * {@link #DATA_FLOW_EQUILIBRIUM}; it is kept so that earlier configurations and results can be
     * reproduced exactly. On scheduled contacts its availability-weighted e_hat can exceed the
     * residual ceiling for a task that the explicit contacts could still carry by its deadline, and
     * it refuses such a task.
     */
    RateAssignmentPolicy DATA_FLOW_EQUILIBRIUM_AVERAGED = (task, route, topology, lrib, nrib, headroom) -> {
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

    /**
     * The route's residual rate within the headroom: the fastest rate the route can sustain over the
     * task's window. The operator default under {@code R_DET} (see
     * {@code OperatorConfiguration.controllerDefaultRatePolicyName}), where sending at the available
     * rate keeps contact capacity in whole pieces for later tasks.
     */
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
