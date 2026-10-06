package net.dcn.pce.crp;

import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.Node;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.rib.NRIB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;

/** Reconstructs committed hop execution and validates its resource invariants. */
public class CRPScheduleReplayValidator {

    private static final Logger log = Logger.getLogger(CRPScheduleReplayValidator.class.getName());
    private static final double EPSILON = 1e-6;

    public static class ValidationReport {
        private boolean valid = true;
        private int validatedScheduleCount = 0;
        private int totalHopsValidated = 0;
        private String errorReason = "PASS";

        public boolean isValid() { return valid; }
        public int getValidatedScheduleCount() { return validatedScheduleCount; }
        public int getTotalHopsValidated() { return totalHopsValidated; }
        public String getErrorReason() { return errorReason; }

        private ValidationReport fail(String reason) {
            valid = false;
            errorReason = reason;
            return this;
        }
    }

    public static ValidationReport validateResult(CRPEngine.PCEComputationResult result) {
        return validateResult(result, null, null, null);
    }

    public static ValidationReport validateResult(
            CRPEngine.PCEComputationResult result,
            BaseTopology topology,
            LRIB lrib,
            NRIB nrib) {
        ValidationReport report = new ValidationReport();
        ReservationIndex<LRIB.LinkReservation> linkIndex = new ReservationIndex<>();
        if (lrib != null) {
            for (LRIB.LinkReservation reservation : lrib.getAllReservations()) {
                linkIndex.add(reservation.getLinkId(), reservation.getTaskId(), reservation);
            }
        }
        ReservationIndex<NRIB.NodeReservation> nodeIndex = new ReservationIndex<>();
        if (nrib != null) {
            for (NRIB.NodeReservation reservation : nrib.getAllReservations()) {
                nodeIndex.add(reservation.getNodeId(), reservation.getTaskId(), reservation);
            }
        }
        for (CRPEngine.CommittedFlowSchedule schedule : result.getCommittedSchedules()) {
            SolveDeadline.checkpointNow("schedule replay validation");
            WorkloadTask task = schedule.getTask();
            List<Link> route = schedule.getRoute();
            List<CRPEngine.HopSchedule> hops = schedule.getHopSchedules();

            if (route == null || route.isEmpty() || hops == null || hops.size() != route.size()) {
                return report.fail("Missing or inconsistent hop timeline for task " + task.getTaskId());
            }
            if (!Double.isFinite(schedule.getCommittedRateBps()) || schedule.getCommittedRateBps() <= 0) {
                return report.fail("Invalid committed rate for task " + task.getTaskId());
            }

            String expectedSource = task.getSourceNodeId();
            ContactRegime regime = topology != null ? topology.getRegime() : ContactRegime.R_DET;
            // When each hop may start: after the previous hop's first bit arrives under cut-through,
            // its last bit under store-and-forward (SchedulingCapacity.nextHopReadySec).
            double previousReady = task.getOriginationTimeSec();
            double previousArrival = task.getOriginationTimeSec();
            for (int index = 0; index < route.size(); index++) {
                Link link = route.get(index);
                CRPEngine.HopSchedule hop = hops.get(index);
                if (!hop.getLink().equals(link)) {
                    return report.fail("Hop timeline/route mismatch at hop " + index + " for task " + task.getTaskId());
                }
                if (!link.getSourceNodeId().equals(expectedSource)) {
                    return report.fail("Route discontinuity at hop " + index + " for task " + task.getTaskId());
                }
                if (hop.getStartSec() + EPSILON < previousReady) {
                    return report.fail("Hop causality violation at hop " + index + " for task " + task.getTaskId());
                }
                // A hop cannot finish sending before the last of its data has arrived.
                if (index > 0 && hop.getEndSec() + EPSILON < previousArrival) {
                    return report.fail("Hop finishes before its data arrives at hop " + index
                            + " for task " + task.getTaskId());
                }
                double priorSlotEndSec = previousReady;
                for (LRIB.TransmissionSlot slot : hop.getTransmissionSlots()) {
                    if (slot.startSec() + EPSILON < priorSlotEndSec) {
                        return report.fail("Transmission-slice ordering violation at hop " + index
                                + " for task " + task.getTaskId());
                    }
                    if (topology != null && topology.getRegime() == ContactRegime.R_DET
                            && !link.containsActiveInterval(slot.startSec(), slot.endSec())) {
                        return report.fail("Transmission outside active contact at hop " + index
                                + " for task " + task.getTaskId());
                    }
                    if (lrib != null && !hasMatchingReservation(
                            linkIndex, task, link, slot, schedule.getCommittedRateBps())) {
                        return report.fail("Missing LRIB reservation at hop " + index
                                + " for task " + task.getTaskId());
                    }
                    priorSlotEndSec = slot.endSec();
                }

                double transferredBits = schedule.getCommittedRateBps() * hop.getActiveTransmissionSec();
                if (!approximatelyEqual(transferredBits, task.getTaskSizeBits(), 1e-6)) {
                    return report.fail("Per-hop volume conservation failure at hop " + index + " for task " + task.getTaskId());
                }
                if (nrib != null && index > 0) {
                    for (double[] hold : SchedulingCapacity.transitHolds(regime, previousReady,
                            previousArrival, hop.getTransmissionSlots(),
                            schedule.getCommittedRateBps(), task.getTaskSizeBytes())) {
                        if (!hasMatchingBufferReservation(nodeIndex, task, link.getSourceNodeId(),
                                hold[0], hold[1], hold[2])) {
                            return report.fail("Missing NRIB holding reservation at hop " + index
                                    + " for task " + task.getTaskId());
                        }
                    }
                }

                expectedSource = link.getDestinationNodeId();
                previousReady = SchedulingCapacity.nextHopReadySec(regime, hop.getStartSec(),
                        hop.getEndSec(), link.getLif().getPropagationDelaySec());
                previousArrival = hop.getArrivalSec();
                report.totalHopsValidated++;
            }

            if (!expectedSource.equals(task.getDestinationNodeId())) {
                return report.fail("Route destination mismatch for task " + task.getTaskId());
            }
            if (!approximatelyEqual(schedule.getStartSec(), hops.get(0).getStartSec(), EPSILON)
                    || !approximatelyEqual(schedule.getCompletionSec(), previousArrival, EPSILON)) {
                return report.fail("Aggregate timing does not match final-hop arrival for task " + task.getTaskId());
            }
            if (schedule.getCompletionSec() > task.getEffectiveDeadlineSec() + EPSILON) {
                return report.fail("Deadline violation for task " + task.getTaskId());
            }
            report.validatedScheduleCount++;
        }

        if (topology != null && lrib != null) {
            for (LRIB.LinkReservation reservation : lrib.getAllReservations()) {
                Link link = topology.getLink(reservation.getLinkId());
                if (link == null
                        || !link.getSourceNodeId().equals(reservation.getSourceNodeId())
                        || !link.getDestinationNodeId().equals(reservation.getDestNodeId())) {
                    return report.fail("LRIB reservation references an unknown or mismatched link "
                            + reservation.getLinkId());
                }
                if (topology.getRegime() == ContactRegime.R_DET
                        && !link.containsActiveInterval(
                                reservation.getStartSec(), reservation.getEndSec())) {
                    return report.fail("LRIB reservation lies outside an active contact on link "
                            + reservation.getLinkId());
                }
            }
            for (Link link : topology.getLinks()) {
                if (!validateLinkCapacity(link, topology.getRegime(), lrib)) {
                    return report.fail("LRIB capacity violation on link " + link.getLinkId());
                }
            }
        }
        if (topology != null && nrib != null) {
            for (NRIB.NodeReservation reservation : nrib.getAllReservations()) {
                if (topology.getNode(reservation.getNodeId()) == null) {
                    return report.fail("NRIB reservation references an unknown node " + reservation.getNodeId());
                }
            }
            for (Node node : topology.getNodes()) {
                if (nrib.getPeakBufferOccupancy(node.getNodeId()) > node.getReservoirCapacityBytes() + EPSILON) {
                    return report.fail("NRIB capacity violation at node " + node.getNodeId());
                }
            }
        }

        log.info(String.format(
                "CRPScheduleReplayValidator: validated %d schedules (%d total hops). Result: PASS",
                report.validatedScheduleCount, report.totalHopsValidated));
        return report;
    }

    /**
     * Reservations indexed by (resource, task), so a schedule's hop is matched against its own task's
     * reservations instead of copying and scanning every reservation on the resource. Only
     * reservations of the same task can match, so the answer is unchanged.
     */
    private static final class ReservationIndex<T> {
        private final Map<String, Map<String, List<T>>> byResourceAndTask = new HashMap<>();

        void add(String resource, String taskId, T reservation) {
            byResourceAndTask.computeIfAbsent(resource, k -> new HashMap<>())
                    .computeIfAbsent(taskId, k -> new ArrayList<>()).add(reservation);
        }

        List<T> of(String resource, String taskId) {
            return byResourceAndTask.getOrDefault(resource, Map.of()).getOrDefault(taskId, List.of());
        }
    }

    private static boolean hasMatchingReservation(
            ReservationIndex<LRIB.LinkReservation> index,
            WorkloadTask task,
            Link link,
            LRIB.TransmissionSlot slot,
            double rateBps) {
        return index.of(link.getLinkId(), task.getTaskId()).stream().anyMatch(reservation ->
                reservation.getTaskId().equals(task.getTaskId())
                        && approximatelyEqual(reservation.getReservedBwBps(), rateBps, 1e-9)
                        && approximatelyEqual(reservation.getStartSec(), slot.startSec(), EPSILON)
                        && approximatelyEqual(reservation.getEndSec(), slot.endSec(), EPSILON));
    }

    private static boolean hasMatchingBufferReservation(
            ReservationIndex<NRIB.NodeReservation> index,
            WorkloadTask task,
            String nodeId,
            double bytes,
            double startSec,
            double endSec) {
        return index.of(nodeId, task.getTaskId()).stream().anyMatch(reservation ->
                reservation.getTaskId().equals(task.getTaskId())
                        && approximatelyEqual(reservation.getReservedBufferBytes(), bytes, 1e-9)
                        && approximatelyEqual(reservation.getStartSec(), startSec, EPSILON)
                        && approximatelyEqual(reservation.getEndSec(), endSec, EPSILON));
    }

    /**
     * Checks the link's total reservation at every reservation start/end instant against capacity.
     * A sweep keeps the reservations active at each instant in list order, so each sum has exactly the
     * terms, order and (compensated) summation of filtering the whole list — the same answer, in
     * near-linear rather than quadratic time.
     */
    private static boolean validateLinkCapacity(Link link, ContactRegime regime, LRIB lrib) {
        List<LRIB.LinkReservation> reservations = lrib.getReservations(link.getLinkId());
        int n = reservations.size();
        TreeSet<Double> events = new TreeSet<>();
        List<Integer> byStart = new ArrayList<>(n);
        List<Integer> byEnd = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            events.add(reservations.get(i).getStartSec());
            events.add(reservations.get(i).getEndSec());
            byStart.add(i);
            byEnd.add(i);
        }
        byStart.sort(Comparator.comparingDouble(i -> reservations.get(i).getStartSec()));
        byEnd.sort(Comparator.comparingDouble(i -> reservations.get(i).getEndSec()));
        double capacityBps = SchedulingCapacity.capacityBps(link, regime);
        TreeMap<Integer, LRIB.LinkReservation> active = new TreeMap<>();
        int nextStart = 0;
        int nextEnd = 0;
        for (double event : events) {
            while (nextStart < n && reservations.get(byStart.get(nextStart)).getStartSec() <= event) {
                active.put(byStart.get(nextStart), reservations.get(byStart.get(nextStart)));
                nextStart++;
            }
            while (nextEnd < n && reservations.get(byEnd.get(nextEnd)).getEndSec() <= event) {
                active.remove(byEnd.get(nextEnd));
                nextEnd++;
            }
            // The load admission counted: rate x weight (identical to the rate at weight 1.0). An
            // overbooked plan may exceed physical capacity by design; its expected load may not.
            double reservedBps = active.values().stream()
                    .mapToDouble(LRIB.LinkReservation::getCapacityLoadBps)
                    .sum();
            if (reservedBps > capacityBps + EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static boolean approximatelyEqual(double left, double right, double relativeTolerance) {
        double scale = Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
        return Math.abs(left - right) <= relativeTolerance * scale;
    }
}
