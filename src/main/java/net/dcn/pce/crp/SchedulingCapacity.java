package net.dcn.pce.crp;

import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.ContactWindow;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.PinnedRoute;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.rib.LRIB;

import java.util.List;

/** Shared capacity and rate semantics for planning, admission, and replay. */
public final class SchedulingCapacity {

    /**
     * Tolerance for "completes by its deadline".
     *
     * <p>Every deadline comparison on the planning path uses this. {@link #minimumWholeFlowRateBps}
     * derives a rate designed to complete exactly at the deadline, so completion lands within an
     * ULP of it and an untoleranced comparison admits or rejects on the direction of a rounding
     * error -- deterministically for a given deadline, arbitrarily across deadlines.
     */
    public static final double DEADLINE_TOLERANCE_SEC = 1e-6;

    private SchedulingCapacity() {}

    /**
     * The fastest rate the route can carry within the operator's utilisation headroom.
     *
     * <p>Extracted so the rate policy and the engine's fallback agree by construction: the engine
     * retries at this rate when the policy's preferred rate turns out to be unschedulable, and a
     * second definition that drifted would make that retry meaningless.
     */
    public static double residualCeilingBps(
            WorkloadTask task, List<Link> route, ContactRegime regime, LRIB lrib, double headroom) {
        double ceilingBps = Double.POSITIVE_INFINITY;
        for (Link link : route) {
            double capacityBps = capacityBps(link, regime);
            double availableBps = lrib.getMaximumAvailableCap(link.getLinkId(), capacityBps,
                    task.getOriginationTimeSec(), task.getEffectiveDeadlineSec());
            ceilingBps = Math.min(ceilingBps, headroom * availableBps);
        }
        return ceilingBps;
    }

    /**
     * R-static and R-det transmit at nominal capacity. R-stoch retains the
     * effective-rate approximation until a distributional planner is added.
     */
    public static double capacityBps(Link link, ContactRegime regime) {
        return regime.capacityBps(link);
    }

    /** Keeps an overbooked weight positive when a contact can never occur (successProb 0). */
    public static final double MIN_CAPACITY_WEIGHT = 1e-6;

    /** Probability a flow's data gets through a hop: the product of its distinct contacts'. */
    public static double hopSuccessProbability(Link link, List<LRIB.TransmissionSlot> slots) {
        java.util.Set<ContactWindow> contacts = new java.util.HashSet<>();
        double probability = 1.0;
        for (LRIB.TransmissionSlot slot : slots) {
            java.util.Optional<ContactWindow> window = link.contactWindowForInterval(slot.startSec(), slot.endSec());
            if (window.isPresent() && contacts.add(window.get())) {
                probability *= window.get().successProb();
            }
        }
        return probability;
    }

    /**
     * The capacity weight of a hop that the flow's data reaches with probability {@code reach}: 1.0
     * unless the ledger has reach-probability overbooking in force, then safety x reach, capped at 1.
     */
    public static double capacityWeight(LRIB lrib, double reach) {
        double safety = lrib.getOverbookingSafety();
        return safety <= 0.0 ? 1.0 : Math.min(1.0, Math.max(MIN_CAPACITY_WEIGHT, safety * reach));
    }

    /**
     * Whether a route's hops forward data as it arrives (cut-through) rather than after the whole
     * flow has arrived (store-and-forward).
     *
     * <p>A continuous backbone forwards packets as they arrive, so every hop of a route transmits
     * at once, offset by propagation. Scheduled contacts (R_DET, R_STOCH) carry bundles that are
     * held whole at each node until the next contact, so their hops run one after another. Treating
     * an R_STATIC path as store-and-forward charged every flow |route| times the rate it needs and
     * predicted |route| times its completion time.
     */
    public static boolean forwardsAsItArrives(ContactRegime regime) {
        return regime == ContactRegime.R_STATIC;
    }

    /**
     * Earliest start of the hop that follows one transmitting over [startSec, endSec] on a link with
     * the given propagation delay: when its first bit arrives under cut-through, its last bit under
     * store-and-forward.
     */
    public static double nextHopReadySec(
            ContactRegime regime, double startSec, double endSec, double propagationSec) {
        return (forwardsAsItArrives(regime) ? startSec : endSec) + propagationSec;
    }

    /**
     * What a transit node holds for a flow between the previous hop and {@code slots}, the next
     * hop's transmission, as {bytes, startSec, endSec} entries.
     *
     * <p>Store-and-forward holds the whole flow from its arrival until each transmission slice
     * starts. Cut-through holds nothing when the next hop starts as data arrives; when it starts
     * {@code delay} later, the node receives and sends at the same rate, so its backlog peaks at
     * rate × delay (never more than the flow) and is held from the first arrival until the next hop
     * finishes.
     */
    public static List<double[]> transitHolds(ContactRegime regime, double readySec,
            double previousEndArrivalSec, List<LRIB.TransmissionSlot> slots, double rateBps,
            double taskSizeBytes) {
        List<double[]> holds = new java.util.ArrayList<>();
        if (forwardsAsItArrives(regime)) {
            double delaySec = slots.get(0).startSec() - readySec;
            if (delaySec > 1e-9) {
                double backlogBytes = Math.min(taskSizeBytes, rateBps * delaySec / 8.0);
                holds.add(new double[]{backlogBytes, readySec, slots.get(slots.size() - 1).endSec()});
            }
            return holds;
        }
        double holdingStartSec = previousEndArrivalSec;
        for (LRIB.TransmissionSlot slot : slots) {
            if (slot.startSec() > holdingStartSec + 1e-9) {
                holds.add(new double[]{taskSizeBytes, holdingStartSec, slot.startSec()});
            }
            holdingStartSec = slot.endSec();
        }
        return holds;
    }

    /**
     * Minimum common rate that completes the flow by its deadline.
     *
     * <p>Store-and-forward transmits the complete volume on every hop in turn, so the route needs
     * |route| × V over the usable window (availability-weighted per hop in R_DET). Cut-through hops
     * overlap, so the route needs V over the usable window.
     */
    public static double minimumWholeFlowRateBps(
            WorkloadTask task, List<Link> route, ContactRegime regime) {
        if (route.isEmpty()) {
            return Double.NaN;
        }
        double totalPropagationSec = route.stream()
                .mapToDouble(link -> link.getLif().getPropagationDelaySec())
                .sum();
        double usableWindowSec = task.getEffectiveDeadlineSec()
                - task.getOriginationTimeSec() - totalPropagationSec;
        if (!Double.isFinite(usableWindowSec) || usableWindowSec <= 0) {
            return Double.NaN;
        }

        double serviceWeight = forwardsAsItArrives(regime) ? 1.0 : route.size();
        if (regime == ContactRegime.R_DET) {
            serviceWeight = route.stream()
                    .mapToDouble(link -> {
                        double availability = link.availabilityFraction(
                                task.getOriginationTimeSec(), task.getEffectiveDeadlineSec());
                        return availability > 0 ? 1.0 / availability : Double.POSITIVE_INFINITY;
                    })
                    .sum();
        }
        double requiredRateBps = task.getTaskSizeBits() * serviceWeight / usableWindowSec;
        return requiredRateBps;
    }

    /** Most halvings below the preferred rate tried by {@link #fastestSchedulableRateBps}. */
    public static final int RATE_FALLBACK_HALVINGS = 32;

    /**
     * {@code preferredBps} if the route can carry the task at it by the deadline; otherwise the first
     * of preferred/2, preferred/4, ... that it can, stopping at the slowest rate that could still
     * send the whole task inside its window. NaN if none can.
     *
     * <p>The preferred rate is usually the route's best residual rate at any instant of the task's
     * window. A partly used contact may offer that rate only briefly and a lower one for much longer,
     * so a route judged at the best instant alone is dropped although a lower rate fits. Lower rates
     * are tried only when the preferred one fails, so every route that is schedulable at the
     * preferred rate is judged exactly as before.
     */
    public static double fastestSchedulableRateBps(
            WorkloadTask task, List<Link> route, ContactRegime regime, LRIB lrib, double preferredBps) {
        return earliestCompletionAtSchedulableRate(task, route, regime, lrib, preferredBps).rateBps();
    }

    /** A rate and the completion it achieves; NaN and infinity when no rate fits. */
    public record RatedCompletion(double rateBps, double completionSec) {
        static final RatedCompletion NONE = new RatedCompletion(Double.NaN, Double.POSITIVE_INFINITY);
    }

    /** {@link #fastestSchedulableRateBps} with the completion time it achieves, in one replay per rate tried. */
    public static RatedCompletion earliestCompletionAtSchedulableRate(
            WorkloadTask task, List<Link> route, ContactRegime regime, LRIB lrib, double preferredBps) {
        if (!Double.isFinite(preferredBps) || preferredBps <= 0) {
            return RatedCompletion.NONE;
        }
        double completionSec = earliestCompletionSec(task, route, regime, lrib, preferredBps);
        if (Double.isFinite(completionSec)) {
            return new RatedCompletion(preferredBps, completionSec);
        }
        double windowSec = task.getEffectiveDeadlineSec() - task.getOriginationTimeSec();
        double slowestBps = windowSec > 0 ? task.getTaskSizeBits() / windowSec : Double.POSITIVE_INFINITY;
        double rateBps = preferredBps;
        for (int halving = 0; halving < RATE_FALLBACK_HALVINGS; halving++) {
            rateBps /= 2;
            if (rateBps < slowestBps) {
                break;
            }
            completionSec = earliestCompletionSec(task, route, regime, lrib, rateBps);
            if (Double.isFinite(completionSec)) {
                return new RatedCompletion(rateBps, completionSec);
            }
        }
        return RatedCompletion.NONE;
    }

    /** Replays route timing without committing reservations. */
    public static double earliestCompletionSec(
            WorkloadTask task,
            List<Link> route,
            ContactRegime regime,
            LRIB lrib,
            double rateBps) {
        if (!Double.isFinite(rateBps) || rateBps <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double transmissionSec = task.getTaskSizeBits() / rateBps;
        double earliestHopStartSec = task.getOriginationTimeSec();
        double completionSec = earliestHopStartSec;
        double reach = 1.0;
        for (int hop = 0; hop < route.size(); hop++) {
            Link link = route.get(hop);
            earliestHopStartSec = Math.max(earliestHopStartSec, PinnedRoute.notBefore(route, hop));
            if (earliestHopStartSec >= task.getEffectiveDeadlineSec()) {
                return Double.POSITIVE_INFINITY;
            }
            List<LRIB.TransmissionSlot> slots = lrib.findEarliestFeasibleTransmission(
                    link, regime, rateBps * capacityWeight(lrib, reach), earliestHopStartSec,
                    task.getEffectiveDeadlineSec(), transmissionSec);
            if (slots.isEmpty()) {
                return Double.POSITIVE_INFINITY;
            }
            reach *= hopSuccessProbability(link, slots);
            double propagationSec = link.getLif().getPropagationDelaySec();
            double endSec = slots.get(slots.size() - 1).endSec();
            completionSec = endSec + propagationSec;
            earliestHopStartSec = nextHopReadySec(
                    regime, slots.get(0).startSec(), endSec, propagationSec);
        }
        return completionSec <= task.getEffectiveDeadlineSec() + DEADLINE_TOLERANCE_SEC
                ? completionSec
                : Double.POSITIVE_INFINITY;
    }
}
