package net.dcn.pce.rib;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node Resource Information Base (NRIB).
 * Maintains input/output buffer reservation records for intermediate transit nodes.
 * Record format: in-rsv/out-rsv(id, tid, nid, rbuf, beg, end)
 */
public class NRIB {

    public static class NodeReservation {
        private final String reservationId;
        private final String taskId;
        private final String nodeId;
        private final double reservedBufferBytes;
        private final double startSec;
        private final double endSec;

        public NodeReservation(String reservationId, String taskId, String nodeId,
                               double reservedBufferBytes, double startSec, double endSec) {
            this.reservationId = reservationId;
            this.taskId = taskId;
            this.nodeId = nodeId;
            this.reservedBufferBytes = reservedBufferBytes;
            this.startSec = startSec;
            this.endSec = endSec;
        }

        public String getReservationId() { return reservationId; }
        public String getTaskId() { return taskId; }
        public String getNodeId() { return nodeId; }
        public double getReservedBufferBytes() { return reservedBufferBytes; }
        public double getStartSec() { return startSec; }
        public double getEndSec() { return endSec; }
    }

    private final Map<String, List<NodeReservation>> nodeReservations = new ConcurrentHashMap<>();
    private final Set<String> reservationIds = new HashSet<>();
    /**
     * All-time peak occupancy per node, kept current as reservations are added (adding a reservation
     * can only raise occupancy inside its own interval, so the new peak is max(old peak, peak inside
     * that interval)) and dropped on any removal. Values are bit-identical to a full recomputation:
     * the same terms are summed in the same order.
     */
    private final Map<String, Double> peakCache = new HashMap<>();

    public synchronized void clear() {
        nodeReservations.clear();
        reservationIds.clear();
        peakCache.clear();
    }

    /**
     * Reserves transit buffer capacity at nodeId for [startSec, endSec).
     */
    public synchronized NodeReservation reserveNodeBuff(String taskId, String nodeId, double bufferBytes, double startSec, double endSec) {
        String rsvId = UUID.randomUUID().toString();
        return restoreNodeReservation(rsvId, taskId, nodeId, bufferBytes, startSec, endSec);
    }

    public synchronized NodeReservation restoreNodeReservation(
            String reservationId, String taskId, String nodeId, double bufferBytes,
            double startSec, double endSec) {
        if (reservationId == null || reservationId.isBlank() || taskId == null || taskId.isBlank()
                || nodeId == null || nodeId.isBlank() || !Double.isFinite(bufferBytes) || bufferBytes < 0
                || !Double.isFinite(startSec) || !Double.isFinite(endSec) || endSec <= startSec) {
            throw new IllegalArgumentException("Invalid NRIB reservation state");
        }
        if (!reservationIds.add(reservationId)) {
            throw new IllegalArgumentException("Duplicate NRIB reservation ID: " + reservationId);
        }
        NodeReservation rsv = new NodeReservation(reservationId, taskId, nodeId, bufferBytes, startSec, endSec);
        List<NodeReservation> list = nodeReservations.computeIfAbsent(nodeId, k -> new ArrayList<>());
        list.add(rsv);
        Double cachedPeak = peakCache.get(nodeId);
        if (cachedPeak != null) {
            peakCache.put(nodeId, Math.max(cachedPeak, peakWithin(list, rsv.getStartSec(), rsv.getEndSec())));
        }
        return rsv;
    }

    public synchronized void recordTransitBuffer(String nodeId, double bufferBytes, double timestampSec) {
        recordTransitBuffer("TRANSIT", nodeId, bufferBytes, timestampSec);
    }

    public synchronized void recordTransitBuffer(String taskId, String nodeId, double bufferBytes, double timestampSec) {
        reserveNodeBuff(taskId, nodeId, bufferBytes, timestampSec, timestampSec + 1.0);
    }

    /**
     * Calculates peak transit reservoir buffer occupancy (in bytes) at nodeId across all time.
     */
    public synchronized double getPeakBufferOccupancy(String nodeId) {
        Double cached = peakCache.get(nodeId);
        if (cached != null) {
            return cached;
        }
        double peak = computePeakBufferOccupancy(nodeId);
        peakCache.put(nodeId, peak);
        return peak;
    }

    /**
     * Plain-summed occupancy peak over the timestamps of {@code list} that fall in [startSec, endSec),
     * summing only reservations overlapping that interval (the only ones active there), in list order —
     * the same terms and order {@link #computePeakBufferOccupancy} uses at those timestamps.
     */
    private static double peakWithin(List<NodeReservation> list, double startSec, double endSec) {
        List<NodeReservation> overlap = new ArrayList<>();
        TreeSet<Double> timestamps = new TreeSet<>();
        for (NodeReservation r : list) {
            if (Math.max(startSec, r.getStartSec()) < Math.min(endSec, r.getEndSec())) {
                overlap.add(r);
                if (r.getStartSec() >= startSec && r.getStartSec() < endSec) timestamps.add(r.getStartSec());
                if (r.getEndSec() >= startSec && r.getEndSec() < endSec) timestamps.add(r.getEndSec());
            }
        }
        return Math.max(0.0, sweepPeak(overlap, timestamps, true));
    }

    /**
     * Occupancy at each of {@code events} (ascending) over {@code reservations}, summing the reservations
     * active at that instant (start <= t < end) in list order — with a plain running sum if
     * {@code plainSum}, else with DoubleStream's compensated sum — exactly as filtering the list at each
     * instant would, but in one sweep. Returns the maximum (0.0 if none).
     */
    private static double sweepPeak(List<NodeReservation> reservations, Collection<Double> events,
                                    boolean plainSum) {
        int n = reservations.size();
        List<Integer> byStart = new ArrayList<>(n);
        List<Integer> byEnd = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byStart.add(i);
            byEnd.add(i);
        }
        byStart.sort(Comparator.comparingDouble(i -> reservations.get(i).getStartSec()));
        byEnd.sort(Comparator.comparingDouble(i -> reservations.get(i).getEndSec()));
        TreeMap<Integer, NodeReservation> active = new TreeMap<>();
        int nextStart = 0;
        int nextEnd = 0;
        double peak = 0.0;
        boolean any = false;
        for (double event : events) {
            while (nextStart < n && reservations.get(byStart.get(nextStart)).getStartSec() <= event) {
                active.put(byStart.get(nextStart), reservations.get(byStart.get(nextStart)));
                nextStart++;
            }
            while (nextEnd < n && reservations.get(byEnd.get(nextEnd)).getEndSec() <= event) {
                active.remove(byEnd.get(nextEnd));
                nextEnd++;
            }
            double occupied;
            if (plainSum) {
                occupied = 0.0;
                for (NodeReservation r : active.values()) {
                    occupied += r.getReservedBufferBytes();
                }
            } else {
                occupied = active.values().stream().mapToDouble(NodeReservation::getReservedBufferBytes).sum();
            }
            peak = any ? Math.max(peak, occupied) : occupied;
            any = true;
        }
        return any ? peak : 0.0;
    }

    private double computePeakBufferOccupancy(String nodeId) {
        List<NodeReservation> rsvs = nodeReservations.getOrDefault(nodeId, Collections.emptyList());
        if (rsvs.isEmpty()) return 0.0;

        // Collect time events
        TreeSet<Double> timestamps = new TreeSet<>();
        for (NodeReservation r : rsvs) {
            timestamps.add(r.getStartSec());
            timestamps.add(r.getEndSec());
        }

        return Math.max(0.0, sweepPeak(rsvs, timestamps, true));
    }

    /** Returns peak occupancy within the half-open interval [startSec, endSec). */
    public synchronized double getPeakBufferOccupancy(String nodeId, double startSec, double endSec) {
        if (!Double.isFinite(startSec) || !Double.isFinite(endSec) || endSec <= startSec) {
            throw new IllegalArgumentException("Invalid NRIB occupancy interval");
        }
        List<NodeReservation> reservations = nodeReservations.getOrDefault(nodeId, Collections.emptyList());
        TreeSet<Double> events = new TreeSet<>();
        events.add(startSec);
        // Every reservation active at an instant in [startSec, endSec) overlaps the interval, so summing
        // only the overlapping ones (in list order) gives exactly getBufferOccupancy's value there.
        List<NodeReservation> overlap = new ArrayList<>();
        for (NodeReservation reservation : reservations) {
            if (Math.max(startSec, reservation.getStartSec()) < Math.min(endSec, reservation.getEndSec())) {
                overlap.add(reservation);
                events.add(Math.max(startSec, reservation.getStartSec()));
                if (reservation.getEndSec() < endSec) {
                    events.add(reservation.getEndSec());
                }
            }
        }
        return sweepPeak(overlap, events, false);
    }

    public synchronized double getBufferOccupancy(String nodeId, double timestampSec) {
        return nodeReservations.getOrDefault(nodeId, Collections.emptyList()).stream()
                .filter(reservation -> timestampSec >= reservation.getStartSec()
                        && timestampSec < reservation.getEndSec())
                .mapToDouble(NodeReservation::getReservedBufferBytes)
                .sum();
    }

    public synchronized List<NodeReservation> getReservations(String nodeId) {
        return Collections.unmodifiableList(new ArrayList<>(nodeReservations.getOrDefault(nodeId, Collections.emptyList())));
    }

    public synchronized boolean hasReservationsForTask(String taskId) {
        return nodeReservations.values().stream()
                .flatMap(Collection::stream)
                .anyMatch(reservation -> reservation.getTaskId().equals(taskId));
    }

    public synchronized int removeReservationsForTask(String taskId) {
        peakCache.clear();
        int before = reservationCount();
        nodeReservations.values().forEach(reservations ->
                reservations.removeIf(reservation -> {
                    if (!reservation.getTaskId().equals(taskId)) return false;
                    reservationIds.remove(reservation.getReservationId());
                    return true;
                }));
        nodeReservations.entrySet().removeIf(entry -> entry.getValue().isEmpty());
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
            throw new IllegalArgumentException("NRIB prune cutoff must be finite");
        }
        int before = reservationCount();
        peakCache.clear();
        nodeReservations.values().forEach(reservations ->
                reservations.removeIf(reservation -> {
                    boolean remove = reservation.getEndSec() <= cutoffSec
                            && !protectedTask.test(reservation.getTaskId());
                    if (remove) reservationIds.remove(reservation.getReservationId());
                    return remove;
                }));
        nodeReservations.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return before - reservationCount();
    }

    public synchronized List<NodeReservation> getAllReservations() {
        return nodeReservations.values().stream()
                .flatMap(Collection::stream)
                .sorted(Comparator.comparing(NodeReservation::getReservationId))
                .toList();
    }

    private int reservationCount() {
        return nodeReservations.values().stream().mapToInt(List::size).sum();
    }
}
