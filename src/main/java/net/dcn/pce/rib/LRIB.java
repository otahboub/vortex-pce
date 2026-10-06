package net.dcn.pce.rib;

import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.Link;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Link Resource Information Base (LRIB).
 * Maintains spatial and temporal capacity reservation records for every link in the network.
 * Record format: l-rsv(id, tid, lid, src, dst, rbw, fbw, beg, end)
 */
public class LRIB {

    private static final int MAX_CONTACT_WINDOWS_PER_QUERY = 100_000;

    public record TransmissionSlot(double startSec, double endSec) {
        public TransmissionSlot {
            if (!Double.isFinite(startSec) || startSec < 0
                    || !Double.isFinite(endSec) || endSec <= startSec) {
                throw new IllegalArgumentException("Invalid transmission slot");
            }
        }
    }

    public static class LinkReservation {
        private final String reservationId;
        private final String taskId;
        private final String linkId;
        private final String sourceNodeId;
        private final String destNodeId;
        private final double reservedBwBps;
        private final double startSec;
        private final double endSec;
        private final double weight;

        public LinkReservation(String reservationId, String taskId, String linkId,
                               String sourceNodeId, String destNodeId, double reservedBwBps,
                               double startSec, double endSec) {
            this(reservationId, taskId, linkId, sourceNodeId, destNodeId, reservedBwBps, startSec, endSec, 1.0);
        }

        /**
         * @param weight the fraction of {@code reservedBwBps} this reservation counts against the
         *               link's capacity, in (0, 1]. 1.0 unless the reservation is overbooked: an
         *               R_STOCH hop reserved at the probability that data reaches it.
         */
        public LinkReservation(String reservationId, String taskId, String linkId,
                               String sourceNodeId, String destNodeId, double reservedBwBps,
                               double startSec, double endSec, double weight) {
            this.reservationId = reservationId;
            this.taskId = taskId;
            this.linkId = linkId;
            this.sourceNodeId = sourceNodeId;
            this.destNodeId = destNodeId;
            this.reservedBwBps = reservedBwBps;
            this.startSec = startSec;
            this.endSec = endSec;
            this.weight = weight;
        }

        public String getReservationId() { return reservationId; }
        public String getTaskId() { return taskId; }
        public String getLinkId() { return linkId; }
        public String getSourceNodeId() { return sourceNodeId; }
        public String getDestNodeId() { return destNodeId; }
        public double getReservedBwBps() { return reservedBwBps; }
        public double getStartSec() { return startSec; }
        public double getEndSec() { return endSec; }
        /** Fraction of the rate counted against capacity; 1.0 unless overbooked. */
        public double getWeight() { return weight; }
        /** What this reservation counts against the link's capacity: rate x weight. */
        public double getCapacityLoadBps() { return reservedBwBps * weight; }
    }

    // Map: linkId -> List of reservations
    private final Map<String, List<LinkReservation>> linkReservations = new ConcurrentHashMap<>();

    /**
     * R_STOCH reach-probability overbooking in force for the current solve: the safety factor, or 0
     * when off. Configuration the engine sets per solve, not ledger state: never persisted and not
     * touched by {@link #clear()}. Kept here because every capacity judgement (the engine, route
     * generators, path policies) goes through this ledger and must weigh hops the same way.
     */
    private volatile double overbookingSafety = 0.0;

    public void setOverbookingSafety(double safety) {
        if (!Double.isFinite(safety) || (safety != 0.0 && safety < 1.0)) {
            throw new IllegalArgumentException("overbooking safety must be 0 (off) or a factor >= 1");
        }
        this.overbookingSafety = safety;
    }

    /** The overbooking safety factor in force, or 0 when every hop is judged at full weight. */
    public double getOverbookingSafety() { return overbookingSafety; }
    // Durable replay and live commits share this uniqueness invariant. An index avoids scanning
    // every existing reservation for every hop, which made an H-hop commit quadratic.
    private final Set<String> reservationIds = new HashSet<>();
    /**
     * Memoised occupancy extrema per link, keyed by (start, end, max|min). Planning asks the same
     * whole-horizon question for every candidate route; the answer only changes when that link's
     * reservations change, so each link's entries are dropped on any add and all on any removal.
     */
    private final Map<String, Map<String, Double>> extremumCache = new HashMap<>();

    public synchronized void clear() {
        linkReservations.clear();
        reservationIds.clear();
        extremumCache.clear();
    }

    /**
     * Reserves link capacity for a given time interval [startSec, endSec).
     */
    public synchronized LinkReservation reserveLinkCap(String taskId, String linkId, String src, String dst,
                                                       double bwBps, double startSec, double endSec) {
        return reserveLinkCap(taskId, linkId, src, dst, bwBps, startSec, endSec, 1.0);
    }

    /** Reserves {@code bwBps}, counting {@code weight} of it against capacity; see {@link LinkReservation}. */
    public synchronized LinkReservation reserveLinkCap(String taskId, String linkId, String src, String dst,
                                                       double bwBps, double startSec, double endSec,
                                                       double weight) {
        String rsvId = UUID.randomUUID().toString();
        return restoreLinkReservation(rsvId, taskId, linkId, src, dst, bwBps, startSec, endSec, weight);
    }

    public synchronized LinkReservation restoreLinkReservation(
            String reservationId, String taskId, String linkId, String src, String dst,
            double bwBps, double startSec, double endSec) {
        return restoreLinkReservation(reservationId, taskId, linkId, src, dst, bwBps, startSec, endSec, 1.0);
    }

    public synchronized LinkReservation restoreLinkReservation(
            String reservationId, String taskId, String linkId, String src, String dst,
            double bwBps, double startSec, double endSec, double weight) {
        if (!Double.isFinite(weight) || weight <= 0 || weight > 1) {
            throw new IllegalArgumentException("LRIB reservation weight must be in (0, 1]");
        }
        if (reservationId == null || reservationId.isBlank() || taskId == null || taskId.isBlank()
                || linkId == null || linkId.isBlank() || src == null || src.isBlank()
                || dst == null || dst.isBlank() || !Double.isFinite(bwBps) || bwBps <= 0
                || !Double.isFinite(startSec) || !Double.isFinite(endSec) || endSec <= startSec) {
            throw new IllegalArgumentException("Invalid LRIB reservation state");
        }
        if (!reservationIds.add(reservationId)) {
            throw new IllegalArgumentException("Duplicate LRIB reservation ID: " + reservationId);
        }
        LinkReservation rsv = new LinkReservation(
                reservationId, taskId, linkId, src, dst, bwBps, startSec, endSec, weight);
        linkReservations.computeIfAbsent(linkId, k -> new ArrayList<>()).add(rsv);
        extremumCache.remove(linkId);
        return rsv;
    }

    /**
     * Computes the available link capacity at linkId during [startSec, endSec).
     */
    public synchronized double getAvailableCap(String linkId, double baseCapacityBps, double startSec, double endSec) {
        validateCapacityQuery(baseCapacityBps, startSec, endSec);
        double peakReservedBps = occupancyExtremum(linkId, startSec, endSec, true);
        return Math.max(0.0, baseCapacityBps - peakReservedBps);
    }

    /** Returns the largest residual capacity available at any instant in the interval. */
    public synchronized double getMaximumAvailableCap(
            String linkId, double baseCapacityBps, double startSec, double endSec) {
        validateCapacityQuery(baseCapacityBps, startSec, endSec);
        double minimumReservedBps = occupancyExtremum(linkId, startSec, endSec, false);
        return Math.max(0.0, baseCapacityBps - minimumReservedBps);
    }

    /**
     * Finds the earliest contiguous interval that can carry {@code requiredBwBps}.
     * Reservation end times are the only instants at which residual capacity can increase.
     */
    public synchronized OptionalDouble findEarliestFeasibleStart(
            String linkId,
            double baseCapacityBps,
            double requiredBwBps,
            double earliestStartSec,
            double latestEndSec,
            double durationSec) {
        if (!Double.isFinite(requiredBwBps) || requiredBwBps <= 0
                || !Double.isFinite(durationSec) || durationSec <= 0
                || !Double.isFinite(earliestStartSec) || !Double.isFinite(latestEndSec)
                || latestEndSec <= earliestStartSec) {
            throw new IllegalArgumentException("Invalid LRIB slot query");
        }
        validateCapacityQuery(baseCapacityBps, earliestStartSec, latestEndSec);
        if (requiredBwBps > baseCapacityBps) {
            return OptionalDouble.empty();
        }

        TreeSet<Double> candidateStarts = new TreeSet<>();
        candidateStarts.add(earliestStartSec);
        for (LinkReservation reservation : linkReservations.getOrDefault(linkId, Collections.emptyList())) {
            if (reservation.getEndSec() >= earliestStartSec
                    && reservation.getEndSec() + durationSec <= latestEndSec) {
                candidateStarts.add(reservation.getEndSec());
            }
        }

        for (double candidate : candidateStarts) {
            double candidateEnd = candidate + durationSec;
            if (candidateEnd <= latestEndSec
                    && getAvailableCap(linkId, baseCapacityBps, candidate, candidateEnd) + 1e-6
                    >= requiredBwBps) {
                return OptionalDouble.of(candidate);
            }
        }
        return OptionalDouble.empty();
    }

    /**
     * Allocates the required transmission duration across capacity-feasible
     * active windows. R-static and R-stoch retain one contiguous interval.
     */
    public synchronized List<TransmissionSlot> findEarliestFeasibleTransmission(
            Link link,
            ContactRegime regime,
            double requiredBwBps,
            double earliestStartSec,
            double latestEndSec,
            double requiredTransmissionSec) {
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(regime, "regime");
        double capacityBps = regime.capacityBps(link);
        if (!Double.isFinite(requiredBwBps) || requiredBwBps <= 0
                || !Double.isFinite(requiredTransmissionSec) || requiredTransmissionSec <= 0
                || !Double.isFinite(earliestStartSec) || earliestStartSec < 0
                || !Double.isFinite(latestEndSec) || latestEndSec <= earliestStartSec) {
            throw new IllegalArgumentException("Invalid LRIB transmission query");
        }
        if (requiredBwBps > capacityBps + 1e-6) {
            return List.of();
        }
        // A link with an explicit contact plan schedules across its scheduled windows (store-and-
        // forward), whether the regime is R_DET or R_STOCH — the two share the temporal model and
        // differ only in per-contact failure risk, which admission (not scheduling) handles. Persistent
        // links, and non-contact-plan links outside R_DET, keep one contiguous interval. This leaves
        // every R_DET / R_STATIC case exactly as before; only R_STOCH-with-contacts changes.
        boolean singleContiguousInterval = link.hasPersistentAvailability()
                || (regime != ContactRegime.R_DET && !link.hasExplicitContactPlan());
        if (singleContiguousInterval) {
            return findEarliestFeasibleStart(link.getLinkId(), capacityBps, requiredBwBps,
                    earliestStartSec, latestEndSec, requiredTransmissionSec).stream()
                    .mapToObj(startSec -> new TransmissionSlot(
                            startSec, startSec + requiredTransmissionSec))
                    .toList();
        }

        List<TransmissionSlot> slots = new ArrayList<>();
        double remainingSec = requiredTransmissionSec;
        double cursorSec = earliestStartSec;
        int contactWindows = 0;
        while (remainingSec > 1e-9 && cursorSec < latestEndSec - 1e-9) {
            net.dcn.pce.crp.SolveDeadline.checkpoint("contact slot construction");
            if (++contactWindows > MAX_CONTACT_WINDOWS_PER_QUERY) {
                return List.of();
            }
            Optional<net.dcn.pce.model.ContactWindow> activeWindow =
                    link.findActiveWindowAtOrAfter(cursorSec, latestEndSec);
            if (activeWindow.isEmpty()) {
                return List.of();
            }
            double windowStartSec = activeWindow.get().startSec();
            double windowEndSec = activeWindow.get().endSec();

            TreeSet<Double> events = new TreeSet<>();
            events.add(windowStartSec);
            events.add(windowEndSec);
            for (LinkReservation reservation : linkReservations
                    .getOrDefault(link.getLinkId(), Collections.emptyList())) {
                if (Math.max(windowStartSec, reservation.getStartSec())
                        < Math.min(windowEndSec, reservation.getEndSec())) {
                    events.add(Math.max(windowStartSec, reservation.getStartSec()));
                    events.add(Math.min(windowEndSec, reservation.getEndSec()));
                }
            }

            List<Double> boundaries = new ArrayList<>(events);
            for (int i = 0; i + 1 < boundaries.size() && remainingSec > 1e-9; i++) {
                double segmentStartSec = boundaries.get(i);
                double segmentEndSec = boundaries.get(i + 1);
                if (segmentEndSec <= segmentStartSec + 1e-9
                        || getAvailableCap(link.getLinkId(), capacityBps,
                                segmentStartSec, segmentEndSec) + 1e-6 < requiredBwBps) {
                    continue;
                }
                double usedEndSec = Math.min(segmentEndSec, segmentStartSec + remainingSec);
                appendOrMerge(slots, segmentStartSec, usedEndSec);
                remainingSec -= usedEndSec - segmentStartSec;
            }
            cursorSec = windowEndSec;
        }
        return remainingSec <= 1e-9 ? List.copyOf(slots) : List.of();
    }

    private static void appendOrMerge(List<TransmissionSlot> slots, double startSec, double endSec) {
        if (!slots.isEmpty()) {
            TransmissionSlot prior = slots.get(slots.size() - 1);
            if (Math.abs(prior.endSec() - startSec) <= 1e-9) {
                slots.set(slots.size() - 1, new TransmissionSlot(prior.startSec(), endSec));
                return;
            }
        }
        slots.add(new TransmissionSlot(startSec, endSec));
    }

    public synchronized boolean hasReservationsForTask(String taskId) {
        return linkReservations.values().stream()
                .flatMap(Collection::stream)
                .anyMatch(reservation -> reservation.getTaskId().equals(taskId));
    }

    public synchronized int removeReservationsForTask(String taskId) {
        extremumCache.clear();
        int before = reservationCount();
        linkReservations.values().forEach(reservations ->
                reservations.removeIf(reservation -> {
                    if (!reservation.getTaskId().equals(taskId)) return false;
                    reservationIds.remove(reservation.getReservationId());
                    return true;
                }));
        linkReservations.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return before - reservationCount();
    }

    public synchronized int pruneReservationsEndingAtOrBefore(double cutoffSec) {
        return pruneReservationsEndingAtOrBefore(cutoffSec, taskId -> false);
    }

    /**
     * Prunes completed reservations, leaving protected tasks alone.
     *
     * <p>A task whose capacity is governed by an installed LSP must not be pruned on the clock:
     * the reservation ending is not the LSP ending, and dropping it would leave an intent holding
     * capacity the ledger no longer records.
     *
     * @param protectedTask returns true for tasks whose reservations must be retained
     */
    public synchronized int pruneReservationsEndingAtOrBefore(
            double cutoffSec, java.util.function.Predicate<String> protectedTask) {
        if (!Double.isFinite(cutoffSec)) {
            throw new IllegalArgumentException("LRIB prune cutoff must be finite");
        }
        int before = reservationCount();
        extremumCache.clear();
        linkReservations.values().forEach(reservations ->
                reservations.removeIf(reservation -> {
                    boolean remove = reservation.getEndSec() <= cutoffSec
                            && !protectedTask.test(reservation.getTaskId());
                    if (remove) reservationIds.remove(reservation.getReservationId());
                    return remove;
                }));
        linkReservations.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return before - reservationCount();
    }

    public synchronized List<LinkReservation> getReservations(String linkId) {
        return Collections.unmodifiableList(new ArrayList<>(linkReservations.getOrDefault(linkId, Collections.emptyList())));
    }

    public synchronized List<LinkReservation> getAllReservations() {
        return linkReservations.values().stream()
                .flatMap(Collection::stream)
                .sorted(Comparator.comparing(LinkReservation::getReservationId))
                .toList();
    }

    private int reservationCount() {
        return linkReservations.values().stream().mapToInt(List::size).sum();
    }

    /**
     * The greatest total bandwidth reserved on {@code linkId} at any single instant, across the
     * whole timeline of its reservations.
     *
     * <p>This is the peak concurrent aggregate, not the largest single reservation. Capacity
     * feedback must compare an observation against it: two overlapping 400 Mbps reservations commit
     * 800 Mbps where they overlap, and an observation of 500 Mbps has to be refused even though no
     * single reservation exceeds it. Occupancy is a step function that only rises at a reservation
     * start, so the maximum is attained at some start instant and it suffices to evaluate there.
     */
    public synchronized double getPeakReservedBps(String linkId) {
        List<LinkReservation> reservations = linkReservations.getOrDefault(linkId, Collections.emptyList());
        double peak = 0.0;
        for (LinkReservation boundary : reservations) {
            double instant = boundary.getStartSec();
            double concurrentBps = reservations.stream()
                    .filter(r -> instant >= r.getStartSec() && instant < r.getEndSec())
                    .mapToDouble(LinkReservation::getReservedBwBps)
                    .sum();
            peak = Math.max(peak, concurrentBps);
        }
        return peak;
    }

    private double occupancyExtremum(String linkId, double startSec, double endSec, boolean maximum) {
        Map<String, Double> cache = extremumCache.computeIfAbsent(linkId, k -> new HashMap<>());
        String key = startSec + "|" + endSec + "|" + maximum;
        Double cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (cache.size() > 50_000) {
            cache.clear(); // bound memory; entries are only an optimisation
        }
        double value = computeOccupancyExtremum(linkId, startSec, endSec, maximum);
        cache.put(key, value);
        return value;
    }

    private double computeOccupancyExtremum(String linkId, double startSec, double endSec, boolean maximum) {
        List<LinkReservation> all = linkReservations.getOrDefault(linkId, Collections.emptyList());
        TreeSet<Double> events = new TreeSet<>();
        events.add(startSec);
        // Every reservation active at an instant in [startSec, endSec) overlaps the interval, so summing
        // only the overlapping ones (in list order) gives exactly the full-list value at each instant.
        List<LinkReservation> reservations = new ArrayList<>();
        for (LinkReservation reservation : all) {
            if (Math.max(startSec, reservation.getStartSec()) < Math.min(endSec, reservation.getEndSec())) {
                reservations.add(reservation);
                events.add(Math.max(startSec, reservation.getStartSec()));
                if (reservation.getEndSec() < endSec) {
                    events.add(reservation.getEndSec());
                }
            }
        }

        // Sweep the events in order, keeping the reservations active at each one in list order: each
        // sum then has exactly the terms, order and (compensated) summation of filtering the list.
        int n = reservations.size();
        List<Integer> byStart = new ArrayList<>(n);
        List<Integer> byEnd = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byStart.add(i);
            byEnd.add(i);
        }
        byStart.sort(Comparator.comparingDouble(i -> reservations.get(i).getStartSec()));
        byEnd.sort(Comparator.comparingDouble(i -> reservations.get(i).getEndSec()));
        TreeMap<Integer, LinkReservation> active = new TreeMap<>();
        int nextStart = 0;
        int nextEnd = 0;
        double result = maximum ? 0.0 : Double.POSITIVE_INFINITY;
        for (double event : events) {
            while (nextStart < n && reservations.get(byStart.get(nextStart)).getStartSec() <= event) {
                active.put(byStart.get(nextStart), reservations.get(byStart.get(nextStart)));
                nextStart++;
            }
            while (nextEnd < n && reservations.get(byEnd.get(nextEnd)).getEndSec() <= event) {
                active.remove(byEnd.get(nextEnd));
                nextEnd++;
            }
            // Capacity load, not the physical rate: an overbooked reservation counts its weight.
            // For weight 1.0 the product is the rate exactly, so sums are unchanged.
            double occupiedBps = active.values().stream()
                    .mapToDouble(LinkReservation::getCapacityLoadBps)
                    .sum();
            result = maximum ? Math.max(result, occupiedBps) : Math.min(result, occupiedBps);
        }
        return Double.isInfinite(result) ? 0.0 : result;
    }

    private static void validateCapacityQuery(double baseCapacityBps, double startSec, double endSec) {
        if (!Double.isFinite(baseCapacityBps) || baseCapacityBps < 0
                || !Double.isFinite(startSec) || !Double.isFinite(endSec) || endSec <= startSec) {
            throw new IllegalArgumentException("Invalid LRIB capacity query");
        }
    }

    /**
     * Computes available capacity under Stochastic Contact Regime (SCR).
     */
    public synchronized double getAvailableCapStochastic(String linkId, String src, String dst,
                                                        double baseCapacityBps, double startSec, double endSec,
                                                        net.dcn.pce.crp.policy.StochasticContactRegime scr) {
        double deterministicCap = getAvailableCap(linkId, baseCapacityBps, startSec, endSec);
        if (scr == null || scr.getMode() == net.dcn.pce.crp.policy.StochasticContactRegime.Mode.DETERMINISTIC) {
            return deterministicCap;
        }
        double prob = scr.computeLinkContactProbability(src, dst, startSec, deterministicCap);
        return deterministicCap * prob;
    }
}
