package net.dcn.pce.crp;

import net.dcn.pce.crp.policy.*;
import net.dcn.pce.model.*;
import net.dcn.pce.rib.*;

import java.util.*;
import java.util.logging.Logger;

/**
 * Core DCN@MPLS Flow-Aware PCE Engine v2.0 (Constructive Resource Placement - CRP Solver).
 * Implements 4-Stage Optimization Workflow with Schedulability Class of Service (CoS) & Tardiness Penalty Scoring.
 *
 * CoS Classes:
 * - STRICT_HARD_DEADLINE (High CoS): Cannot miss deadline d_h. Infinite penalty on miss.
 * - MEDIUM_SOFT_LAXITY (Medium CoS): Can miss d_h within +20% laxity window. Quadratic penalty score.
 * - LOW_BEST_EFFORT (Low CoS): Non-real-time fill-in traffic. Linear penalty score.
 *
 * Author: Dr. Omar Y. Tahboub
 */
public class CRPEngine {

    private static final Logger log = Logger.getLogger(CRPEngine.class.getName());

    private TaskSelectionPolicy fSelect = TaskSelectionPolicy.LWEEF;
    private RouteGenerationPolicy fGenerate = RouteGenerationPolicy.BFS_MIN_HOP;
    private PathSelectionPolicy fRoute = PathSelectionPolicy.EAP;
    private RateAssignmentPolicy fProp = RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM;

    private double utilizationHeadroom = 0.90; // 90% link cap threshold

    /** Where the utilisation headroom is checked. */
    public enum HeadroomEnforcement {
        /**
         * Against the best instant in the task's window: rate <= headroom x the most residual capacity
         * at any time in [origination, deadline]. The long-standing behaviour; on a continuous route
         * the window almost always has an unreserved sliver, so it rarely binds.
         */
        WINDOW_PEAK,
        /** Against every slot the flow transmits in: rate <= headroom x the residual capacity there. */
        TRANSMISSION_INTERVAL
    }

    /**
     * How much faster than the policy's rate to send, to absorb what the plan does not model
     * (protocol headers, connection start-up). A margin only speeds a flow up, never beyond the
     * headroom-safe ceiling, and never moves its deadline.
     */
    public enum TransportMargin {
        /** Send at the policy's rate. */
        NONE,
        /** Send at the policy's rate x a factor >= 1: for overhead that grows with the bytes sent. */
        RATE_FACTOR,
        /**
         * Send fast enough to finish a fixed number of seconds early: for overhead that is a fixed
         * time, such as connection start-up. Exact for a flow paced across its whole window.
         */
        DEADLINE_GUARD
    }

    private HeadroomEnforcement headroomEnforcement = HeadroomEnforcement.WINDOW_PEAK;
    private TransportMargin transportMargin = TransportMargin.NONE;
    private double transportMarginValue = 0.0;

    /**
     * R_STOCH delivery-confidence target: a task's committed routes are augmented with contact-disjoint
     * backups until their union survival probability reaches this. Deterministic contacts (R_DET, or
     * R_STOCH contacts with successProb 1.0) survive with probability 1.0, so this never fires there.
     * Overridable via {@code VORTEX_STOCH_ALPHA}.
     */
    private double stochasticConfidenceAlpha = defaultStochasticAlpha();

    /**
     * R_STOCH redundancy may share a terminal contact (into the destination) when upstream paths are
     * disjoint — buying reliability upstream of the terminal cut. Off by default so contact-disjoint
     * admission (and every earlier R_STOCH result) is unchanged. Overridable via
     * {@code VORTEX_STOCH_SHARED_TERMINAL=true}.
     */
    private boolean stochasticSharedTerminal =
            Boolean.parseBoolean(System.getenv().getOrDefault("VORTEX_STOCH_SHARED_TERMINAL", "false").trim());

    /**
     * How R_STOCH redundancy is allocated across tasks. {@code PER_TASK} (default) buys each task's
     * backups right after its primary, in F_select order — under load the first tasks can exhaust
     * capacity that later tasks need even for a primary. {@code GLOBAL_MARGINAL} commits every task's
     * primary first, then allocates backups across all tasks by largest marginal survival gain (lazy
     * greedy; joint survival is monotone submodular in the committed routes). Overridable via
     * {@code VORTEX_STOCH_ALLOCATION}.
     */
    private boolean stochasticGlobalAllocation = "GLOBAL_MARGINAL".equalsIgnoreCase(
            System.getenv().getOrDefault("VORTEX_STOCH_ALLOCATION", "PER_TASK").trim());

    /**
     * How a task's joint survival is computed. {@code EXACT} (default) uses the closed forms, which
     * require contact-disjoint routes (or shared-terminal routes). {@code MONTE_CARLO} estimates it over
     * {@code VORTEX_STOCH_MC_SAMPLES} (default 256) deterministic failure samples and accepts overlapping
     * routes — required for MESH_CGR. Overridable via {@code VORTEX_STOCH_SURVIVAL}.
     */
    private boolean stochasticMonteCarlo = "MONTE_CARLO".equalsIgnoreCase(
            System.getenv().getOrDefault("VORTEX_STOCH_SURVIVAL", "EXACT").trim());
    private int stochasticMonteCarloSamples = positiveEnvInt("VORTEX_STOCH_MC_SAMPLES", 256);

    /**
     * R_STOCH overbooking. {@code NONE} (default) reserves every hop at full weight, exactly as before.
     * {@code REACH_PROBABILITY} reserves each hop at the probability that the flow's data reaches it
     * (the product of the success probabilities of the contacts its earlier hops use), scaled by
     * {@code VORTEX_STOCH_OVERBOOK_SAFETY} (>= 1, default 1) and capped at 1. Capacity downstream of a
     * likely failure is then mostly left for others; when more data turns up than a contact carries,
     * the network serves it by priority. Overridable via {@code VORTEX_STOCH_OVERBOOK}.
     */
    private boolean stochasticOverbook = "REACH_PROBABILITY".equalsIgnoreCase(
            System.getenv().getOrDefault("VORTEX_STOCH_OVERBOOK", "NONE").trim());
    private double stochasticOverbookSafety = envDoubleAtLeast("VORTEX_STOCH_OVERBOOK_SAFETY", 1.0, 1.0);

    private static double envDoubleAtLeast(String name, double fallback, double minimum) {
        String value = System.getenv(name);
        if (value != null) {
            try {
                double parsed = Double.parseDouble(value.trim());
                if (Double.isFinite(parsed) && parsed >= minimum) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return fallback;
    }
    private long stochasticMonteCarloSeed = positiveEnvInt("VORTEX_STOCH_MC_SEED", 1);

    private static int positiveEnvInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value != null) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return fallback;
    }

    public CRPEngine withStochasticMonteCarlo(boolean monteCarlo, int samples, long seed) {
        if (samples <= 0) {
            throw new IllegalArgumentException("Monte-Carlo samples must be positive");
        }
        this.stochasticMonteCarlo = monteCarlo;
        this.stochasticMonteCarloSamples = samples;
        this.stochasticMonteCarloSeed = seed;
        return this;
    }

    /** The survival ledger for a task under the configured survival mode. */
    private StochasticAdmission.SurvivalLedger newSurvivalLedger(WorkloadTask task) {
        return stochasticMonteCarlo
                ? StochasticAdmission.SurvivalLedger.monteCarlo(task.getSourceNodeId(),
                        task.getDestinationNodeId(), task.getOriginationTimeSec(),
                        stochasticMonteCarloSamples, stochasticMonteCarloSeed)
                : new StochasticAdmission.SurvivalLedger(stochasticSharedTerminal);
    }

    /** A task whose primary is committed and whose backups are allocated in the global phase. */
    private static final class PendingRedundancy {
        final WorkloadTask task;
        final List<List<Link>> candidates;
        final List<Link> primaryRoute;
        final StochasticAdmission.SurvivalLedger ledger;
        final int order;
        int nextCandidate;

        PendingRedundancy(WorkloadTask task, List<List<Link>> candidates, List<Link> primaryRoute,
                          StochasticAdmission.SurvivalLedger ledger, int order) {
            this.task = task;
            this.candidates = candidates;
            this.primaryRoute = primaryRoute;
            this.ledger = ledger;
            this.order = order;
        }
    }

    /** A schedulable backup for a pending task, with the survival it would add. */
    private record BackupProposal(PendingRedundancy pending, List<Link> route, double rateBps,
                                  List<HopSchedule> hops, StochasticAdmission.RouteRisk risk,
                                  double gain) {}

    /** Wall-clock budget for a single solve; zero disables the bound. */
    private long solveTimeoutNanos = 0L;

    private final LRIB lrib = new LRIB();
    private final NRIB nrib = new NRIB();

    public CRPEngine() {}

    public CRPEngine withFSelect(TaskSelectionPolicy policy) { this.fSelect = Objects.requireNonNull(policy); return this; }
    public CRPEngine withFGenerate(RouteGenerationPolicy policy) { this.fGenerate = Objects.requireNonNull(policy); return this; }
    public CRPEngine withFRoute(PathSelectionPolicy policy) { this.fRoute = Objects.requireNonNull(policy); return this; }
    public CRPEngine withFProp(RateAssignmentPolicy policy) { this.fProp = Objects.requireNonNull(policy); return this; }
    public CRPEngine withUtilizationHeadroom(double headroom) {
        if (!Double.isFinite(headroom) || headroom <= 0 || headroom > 1) {
            throw new IllegalArgumentException("utilization headroom must be in (0, 1]");
        }
        this.utilizationHeadroom = headroom;
        return this;
    }

    /** Largest fraction of residual link capacity admission may commit. */
    public double getUtilizationHeadroom() { return utilizationHeadroom; }

    public CRPEngine withHeadroomEnforcement(HeadroomEnforcement enforcement) {
        this.headroomEnforcement = Objects.requireNonNull(enforcement);
        return this;
    }

    public HeadroomEnforcement getHeadroomEnforcement() { return headroomEnforcement; }

    /**
     * @param value for {@link TransportMargin#RATE_FACTOR} a factor >= 1; for
     *              {@link TransportMargin#DEADLINE_GUARD} seconds >= 0; ignored for NONE
     */
    public CRPEngine withTransportMargin(TransportMargin margin, double value) {
        Objects.requireNonNull(margin);
        if (margin == TransportMargin.RATE_FACTOR && !(Double.isFinite(value) && value >= 1.0)) {
            throw new IllegalArgumentException("a rate-factor margin must be a finite factor >= 1");
        }
        if (margin == TransportMargin.DEADLINE_GUARD && !(Double.isFinite(value) && value >= 0.0)) {
            throw new IllegalArgumentException("a deadline-guard margin must be finite seconds >= 0");
        }
        this.transportMargin = margin;
        this.transportMarginValue = margin == TransportMargin.NONE ? 0.0 : value;
        return this;
    }

    public TransportMargin getTransportMargin() { return transportMargin; }

    public double getTransportMarginValue() { return transportMarginValue; }

    public CRPEngine withStochasticConfidenceAlpha(double alpha) {
        if (!Double.isFinite(alpha) || alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("stochastic confidence alpha must be in (0, 1]");
        }
        this.stochasticConfidenceAlpha = alpha;
        return this;
    }

    public double getStochasticConfidenceAlpha() { return stochasticConfidenceAlpha; }

    public CRPEngine withStochasticSharedTerminal(boolean sharedTerminal) {
        this.stochasticSharedTerminal = sharedTerminal;
        return this;
    }

    public boolean isStochasticSharedTerminal() { return stochasticSharedTerminal; }

    /**
     * Turns R_STOCH reach-probability overbooking on or off.
     *
     * @param safety scales each hop's reach probability before it is capped at 1; >= 1
     */
    public CRPEngine withStochasticOverbooking(boolean overbook, double safety) {
        if (!Double.isFinite(safety) || safety < 1.0) {
            throw new IllegalArgumentException("overbooking safety must be a finite factor >= 1");
        }
        this.stochasticOverbook = overbook;
        this.stochasticOverbookSafety = safety;
        return this;
    }

    public boolean isStochasticOverbooking() { return stochasticOverbook; }

    public double getStochasticOverbookSafety() { return stochasticOverbookSafety; }

    public CRPEngine withStochasticGlobalAllocation(boolean globalAllocation) {
        this.stochasticGlobalAllocation = globalAllocation;
        return this;
    }

    public boolean isStochasticGlobalAllocation() { return stochasticGlobalAllocation; }

    private static double defaultStochasticAlpha() {
        String value = System.getenv("VORTEX_STOCH_ALPHA");
        if (value != null) {
            try {
                double alpha = Double.parseDouble(value.trim());
                if (alpha > 0 && alpha <= 1) {
                    return alpha;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return 0.90;
    }
    /**
     * Bounds the wall-clock duration of a single solve. A {@code null} or zero duration leaves
     * solves unbounded, which is the historical behavior.
     */
    public CRPEngine withSolveTimeout(java.time.Duration timeout) {
        if (timeout == null || timeout.isZero()) {
            this.solveTimeoutNanos = 0L;
            return this;
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("solve timeout must be positive");
        }
        this.solveTimeoutNanos = timeout.toNanos();
        return this;
    }

    /** Retained for configuration compatibility; deadline feasibility cannot be disabled. */
    public CRPEngine withEnforceAdmissionGate(boolean enforce) {
        if (!enforce) {
            throw new IllegalArgumentException("deadline admission cannot be disabled");
        }
        return this;
    }

    public static class HopSchedule {
        private final Link link;
        private final List<LRIB.TransmissionSlot> transmissionSlots;
        private final double startSec;
        private final double endSec;
        private final double arrivalSec;

        public HopSchedule(Link link, double startSec, double endSec) {
            this(link, List.of(new LRIB.TransmissionSlot(startSec, endSec)));
        }

        public HopSchedule(Link link, List<LRIB.TransmissionSlot> transmissionSlots) {
            if (link == null || transmissionSlots == null || transmissionSlots.isEmpty()) {
                throw new IllegalArgumentException("Invalid hop schedule");
            }
            this.link = link;
            this.transmissionSlots = List.copyOf(transmissionSlots);
            this.startSec = transmissionSlots.get(0).startSec();
            this.endSec = transmissionSlots.get(transmissionSlots.size() - 1).endSec();
            this.arrivalSec = this.endSec + link.getLif().getPropagationDelaySec();
        }

        public Link getLink() { return link; }
        public double getStartSec() { return startSec; }
        public double getEndSec() { return endSec; }
        public double getArrivalSec() { return arrivalSec; }
        public List<LRIB.TransmissionSlot> getTransmissionSlots() { return transmissionSlots; }
        public double getActiveTransmissionSec() {
            return transmissionSlots.stream()
                    .mapToDouble(slot -> slot.endSec() - slot.startSec())
                    .sum();
        }
    }

    public static class CommittedFlowSchedule {
        private final WorkloadTask task;
        private final List<Link> route;
        private final List<HopSchedule> hopSchedules;
        private final double assignedRateBps;
        private final double startTimeSec;
        private final double completionTimeSec;
        private final double maxTransitBufferBytes;
        private final double penaltyScore;

        public CommittedFlowSchedule(WorkloadTask task, List<HopSchedule> hopSchedules, double assignedRateBps,
                                     double maxTransitBufferBytes) {
            if (hopSchedules == null || hopSchedules.isEmpty()) {
                throw new IllegalArgumentException("Committed schedule requires at least one hop");
            }
            this.task = task;
            this.hopSchedules = List.copyOf(hopSchedules);
            this.route = hopSchedules.stream().map(HopSchedule::getLink).toList();
            this.assignedRateBps = assignedRateBps;
            this.startTimeSec = hopSchedules.get(0).getStartSec();
            this.completionTimeSec = hopSchedules.get(hopSchedules.size() - 1).getArrivalSec();
            this.maxTransitBufferBytes = maxTransitBufferBytes;

            // Calculate CoS Penalty score P(tau)
            double tardiness = Math.max(0.0, completionTimeSec - task.getDeadlineSec());
            if (task.getCosClass() == WorkloadTask.ClassOfService.MEDIUM_SOFT_LAXITY) {
                this.penaltyScore = Math.pow(tardiness, 2.0) * 100.0;
            } else if (task.getCosClass() == WorkloadTask.ClassOfService.LOW_BEST_EFFORT) {
                this.penaltyScore = tardiness * 5.0;
            } else {
                this.penaltyScore = tardiness > 0.001 ? 9999.0 : 0.0;
            }
        }

        public WorkloadTask getTask() { return task; }
        public List<Link> getRoute() { return route; }
        public List<HopSchedule> getHopSchedules() { return hopSchedules; }
        public double getAssignedRateBps() { return assignedRateBps; }
        public double getCommittedRateBps() { return assignedRateBps; }
        public double getStartTimeSec() { return startTimeSec; }
        public double getStartSec() { return startTimeSec; }
        public double getCompletionTimeSec() { return completionTimeSec; }
        public double getCompletionSec() { return completionTimeSec; }
        public double getMaxTransitBufferBytes() { return maxTransitBufferBytes; }
        public double getPeakTransitBufferBytes() { return maxTransitBufferBytes; }
        public double getPenaltyScore() { return penaltyScore; }
        public boolean isMetDeadline() { return completionTimeSec <= task.getEffectiveDeadlineSec(); }
        public double getEarlinessSec() { return Math.max(0.0, task.getEffectiveDeadlineSec() - completionTimeSec); }
    }

    /**
     * Peak buffer held at one node, against what that node has.
     *
     * <p>The response reported a network-wide maximum and a per-schedule peak, neither of which
     * says whether any particular node is close to exhausting its reservoir. The claim that pacing
     * trades link occupancy for buffer is a claim about exactly this number, so it has to be
     * visible per node rather than aggregated away.
     */
    public record ReservoirUsage(String nodeId, double peakBufferBytes,
                                 double reservoirCapacityBytes) {
        /** Fraction of the node's reservoir held at the peak, or 0 when the node has none. */
        public double utilisation() {
            return reservoirCapacityBytes <= 0 ? 0.0 : peakBufferBytes / reservoirCapacityBytes;
        }
    }

    public static class PCEComputationResult {
        public enum SolveStage {
            TASK_SELECTION, ROUTE_GENERATION, PATH_SELECTION, RATE_ASSIGNMENT,
            SCHEDULE_AND_COMMIT, REPLAY_VALIDATION, RESERVOIR_REPORTING
        }
        private final List<CommittedFlowSchedule> committedSchedules = new ArrayList<>();
        private final List<WorkloadTask> unadmittedTasks = new ArrayList<>();
        private final java.util.Map<String, RefusalCause> refusalCauses =
                new java.util.LinkedHashMap<>();
        private final List<ReservoirUsage> reservoirUsage = new ArrayList<>();
        private double totalEarlinessSec = 0.0;
        private double totalPenaltyScore = 0.0;
        private double maxNetworkTransitBufferBytes = 0.0;
        private int metDeadlineCount = 0;
        private long computationTimeMs = 0;
        private String policyName = "LWEEF_CoS + EAP + deadline-window DFE";
        private final EnumMap<SolveStage, Long> stageNanos = new EnumMap<>(SolveStage.class);

        private void recordStageNanos(SolveStage stage, long elapsedNanos) {
            stageNanos.merge(stage, elapsedNanos, Long::sum);
        }

        /** Per-stage elapsed time in microseconds, accumulated across all offered workloads. */
        public Map<String, Double> getStageTimingMicros() {
            Map<String, Double> timings = new LinkedHashMap<>();
            for (SolveStage stage : SolveStage.values()) {
                timings.put(stage.name(), stageNanos.getOrDefault(stage, 0L) / 1_000.0);
            }
            return Collections.unmodifiableMap(timings);
        }

        public void addCommitted(CommittedFlowSchedule schedule) {
            committedSchedules.add(schedule);
            totalPenaltyScore += schedule.getPenaltyScore();
            if (schedule.isMetDeadline()) {
                metDeadlineCount++;
                totalEarlinessSec += schedule.getEarlinessSec();
            }
            if (schedule.getMaxTransitBufferBytes() > maxNetworkTransitBufferBytes) {
                maxNetworkTransitBufferBytes = schedule.getMaxTransitBufferBytes();
            }
        }

        public void addUnadmitted(WorkloadTask task) {
            addUnadmitted(task, null);
        }

        public void addUnadmitted(WorkloadTask task, RefusalCause cause) {
            unadmittedTasks.add(task);
            if (cause != null) {
                refusalCauses.put(task.getTaskId(), cause);
            }
        }

        /** Why each unadmitted task was declined, where the stage recorded a reason. */
        public java.util.Map<String, RefusalCause> getRefusalCauses() {
            return java.util.Collections.unmodifiableMap(refusalCauses);
        }

        public List<CommittedFlowSchedule> getCommittedSchedules() { return committedSchedules; }

        /** How much of each transit node's reservoir this plan consumes at its peak. */
        public List<ReservoirUsage> getReservoirUsage() {
            return java.util.Collections.unmodifiableList(reservoirUsage);
        }

        void addReservoirUsage(ReservoirUsage usage) {
            reservoirUsage.add(usage);
        }
        public List<WorkloadTask> getUnadmittedTasks() { return unadmittedTasks; }
        public double getTotalEarlinessSec() { return totalEarlinessSec; }
        public double getTotalPenaltyScore() { return totalPenaltyScore; }
        public int getMetDeadlineCount() { return metDeadlineCount; }
        public int getOfferedFlowCount() { return committedSchedules.size() + unadmittedTasks.size(); }
        public int getCommittedFlowCount() { return committedSchedules.size(); }
        public double getSuccessRatioPercent() {
            int offered = getOfferedFlowCount();
            if (offered == 0) return 0.0;
            return (metDeadlineCount * 100.0) / offered;
        }
        public double getCommittedSuccessRatioPercent() {
            if (committedSchedules.isEmpty()) return 0.0;
            return (metDeadlineCount * 100.0) / committedSchedules.size();
        }
        public double getFlowAdmissionRatioPercent() {
            int offered = getOfferedFlowCount();
            if (offered == 0) return 0.0;
            return (committedSchedules.size() * 100.0) / offered;
        }
        public double getMaxNetworkTransitBufferBytes() { return maxNetworkTransitBufferBytes; }
        public long getComputationTimeMs() { return computationTimeMs; }
        public void setComputationTimeMs(long ms) { this.computationTimeMs = ms; }
        public String getPolicyName() { return policyName; }
        public void setPolicyName(String name) { this.policyName = name; }
    }


    private boolean persistentState = false;
    private ReservationStore stateStore;

    /**
     * What this controller intends for each task's LSP, as distinct from the capacity it has
     * booked. A reservation says the controller committed bandwidth; only a PCC report says a
     * router is forwarding. Tracking the second separately is what allows the two to be
     * reconciled rather than assumed equal.
     */
    private final net.dcn.pce.install.IntentLedger intents = new net.dcn.pce.install.IntentLedger();

    /** Retain ledgers between solve calls in this process. This is not durable by itself. */
    public CRPEngine withPersistentState(boolean persistent) { this.persistentState = persistent; return this; }

    public CRPEngine withDurableState(String statePath) {
        return withReservationStore(new FileWalReservationStore(statePath));
    }

    /**
     * Backs the ledgers with an explicit store. Supplying the store rather than a path is what
     * allows a shared transactional backend to replace the local write-ahead log without the
     * engine changing.
     */
    public CRPEngine withReservationStore(ReservationStore store) {
        this.stateStore = Objects.requireNonNull(store, "store");
        // Intents are restored with the ledgers, not separately: they were committed in the same
        // transaction, and reservations recovered without them would leave the controller holding
        // capacity whose purpose it can no longer state.
        this.stateStore.restore(lrib, nrib, intents);
        this.persistentState = true;
        return this;
    }

    public synchronized void resetLedgers() {
        // Snapshot before mutating, like solve, cancel, and prune. Clearing memory first and
        // appending afterwards left no way back: an append failure emptied the live ledgers while
        // the prior reservations were still durable, so the running process disagreed with the
        // state a restart would restore.
        List<LRIB.LinkReservation> priorLinkReservations = new ArrayList<>(lrib.getAllReservations());
        List<NRIB.NodeReservation> priorNodeReservations = new ArrayList<>(nrib.getAllReservations());
        List<net.dcn.pce.install.InstallationIntent> priorIntents = intents.snapshot();
        Set<String> removed = currentReservationIds();

        lrib.clear();
        nrib.clear();
        if (stateStore == null) {
            return;
        }

        try {
            stateStore.append(new ReservationStore.ReservationDelta(List.of(), List.of(), removed));
        } catch (RuntimeException e) {
            rollbackLedgers(priorLinkReservations, priorNodeReservations, priorIntents);
            throw e;
        }

        // Past the commit point: the reset is durable and compaction is maintenance only.
        try {
            stateStore.compact(lrib, nrib);
        } catch (RuntimeException e) {
            log.log(java.util.logging.Level.WARNING,
                    "Reservation log compaction failed after a durable reset; the reset stands", e);
        }
    }

    /** What cancelling a task actually did, which depends on how far it reached the network. */
    public enum CancellationOutcome {
        /** Reservations were released immediately; nothing had been sent to a PCC. */
        RELEASED,
        /** A removal request is on the wire; capacity stays held until the PCC confirms it. */
        REMOVAL_REQUESTED,
        /**
         * The LSP is on the network and the removal could not be sent.
         *
         * <p>Distinct from {@link #REMOVAL_REQUESTED} because nothing was asked of any router:
         * the session may be down, the PCC unknown, or no PLSP-ID assigned yet. Capacity stays
         * held -- the LSP may still be forwarding -- but no acknowledgement will ever arrive, so
         * this needs an operator rather than patience.
         */
        REMOVAL_UNDELIVERABLE,
        /** No reservations and no intent for that task. */
        NOT_FOUND
    }

    /** What a dispatch attempt established about whether the request reached the network. */
    public enum DispatchOutcome {
        /** The request is on the wire. */
        SENT,
        /**
         * Nothing was written and nothing could have been -- no session, no owner, no PLSP-ID.
         *
         * <p>The only outcome that permits undoing a durable record written before the send.
         */
        NOT_ATTEMPTED,
        /**
         * A send was attempted and failed. Some of it may have reached the peer.
         *
         * <p>Deliberately not treated as failure by the caller: the durable record has to stand,
         * because the alternative is a PCC acting on a request the controller has forgotten.
         */
        UNCERTAIN
    }

    /**
     * Sends a removal request for an installed LSP to the PCC that owns it.
     *
     * <p>Supplied by whoever owns the southbound session, so the engine can require delivery
     * without depending on the transport.
     */
    @FunctionalInterface
    public interface RemovalDispatcher {
        DispatchOutcome requestRemoval(net.dcn.pce.install.InstallationIntent intent, long srpId);
    }

    private volatile RemovalDispatcher removalDispatcher;

    /** Wires the southbound path used to remove LSPs the controller has installed. */
    public CRPEngine withRemovalDispatcher(RemovalDispatcher dispatcher) {
        this.removalDispatcher = dispatcher;
        return this;
    }

    /**
     * Cancels a task, releasing its capacity only when that is safe.
     *
     * <p>Immediate release is safe only for work that never left the controller. Once a PCInitiate
     * has been sent, the LSP may be forwarding traffic, and dropping the reservations would let a
     * later solve hand the same bandwidth to another flow while a router is still using it. For
     * anything past PLANNED this records a removal request and keeps the capacity held until a
     * report confirms the LSP is gone.
     */
    public synchronized CancellationOutcome requestCancellation(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be blank");
        }
        java.util.Optional<net.dcn.pce.install.InstallationIntent> intent = intents.find(taskId);
        boolean reachedTheNetwork = intent.isPresent()
                && intent.get().getState() != net.dcn.pce.install.InstallationState.PLANNED
                && intent.get().holdsCapacity();

        if (reachedTheNetwork) {
            return requestRemovalFromTheNetwork(intent.get());
        }

        return releaseReservationsNow(taskId) ? CancellationOutcome.RELEASED : CancellationOutcome.NOT_FOUND;
    }

    /**
     * Asks the owning PCC to remove an LSP, recording the request durably before sending it.
     *
     * <p>Persist, then send. The previous order sent first so that the ledger only ever recorded
     * requests actually made, which reads well until the process dies in between: the PCC removes
     * an LSP that durable state still calls INSTALLED, and nothing afterwards can tell that from
     * an LSP a router dropped on its own. Writing first inverts the crash window into one that is
     * recoverable -- DELETING with nothing sent is resolved by the next state synchronisation,
     * which reports the LSP as still present.
     *
     * <p>The record is undone only for {@link DispatchOutcome#NOT_ATTEMPTED}, where no bytes can
     * have left this process. A failed write is not that: it may have partly reached the peer, so
     * the record stands and the acknowledgement deadline takes it from there.
     */
    private CancellationOutcome requestRemovalFromTheNetwork(
            net.dcn.pce.install.InstallationIntent intent) {
        if (intent.getState() == net.dcn.pce.install.InstallationState.DELETING) {
            return CancellationOutcome.REMOVAL_REQUESTED; // Already outstanding; asking twice changes nothing.
        }
        RemovalDispatcher dispatcher = this.removalDispatcher;
        if (dispatcher == null || intent.getPccSessionKey().isEmpty()
                || intent.getPlspId().isEmpty()) {
            // No southbound path, no known owner, or no PLSP-ID to name the LSP by. Reporting a
            // removal here would tell the caller a router had been asked when none had been.
            return CancellationOutcome.REMOVAL_UNDELIVERABLE;
        }

        String taskId = intent.getTaskId();
        long srpId = nextRemovalSrpId();
        if (applyInstallationChange(() -> intents.markDeleting(taskId, srpId)).isEmpty()) {
            // Nothing was recorded, so nothing may be sent: a removal the ledger does not know
            // about is the exact state this ordering exists to prevent.
            return CancellationOutcome.REMOVAL_UNDELIVERABLE;
        }

        DispatchOutcome outcome;
        try {
            outcome = dispatcher.requestRemoval(intents.find(taskId).orElse(intent), srpId);
        } catch (RuntimeException e) {
            // An exception says nothing about how far the write got, so it is treated as the
            // uncertain case rather than the safe one.
            log.log(java.util.logging.Level.WARNING,
                    "Removal dispatch failed for " + taskId, e);
            outcome = DispatchOutcome.UNCERTAIN;
        }

        if (outcome == DispatchOutcome.NOT_ATTEMPTED) {
            applyInstallationChange(() -> intents.restore(intent));
            return CancellationOutcome.REMOVAL_UNDELIVERABLE;
        }
        return CancellationOutcome.REMOVAL_REQUESTED;
    }

    /**
     * Places every hop at one rate and checks the schedule is deliverable, committing nothing.
     *
     * <p>Returns an empty list when the rate cannot be scheduled -- no slots, arrival past the
     * deadline, insufficient residual link capacity, or a transit node without the buffer to hold
     * the flow between hops. Separated from the solve loop so a rejected rate can be retried at a
     * different one before anything is reserved.
     */
    /**
     * The outcome of trying to schedule one task at one rate.
     *
     * <p>An empty schedule used to be the whole answer, which meant the caller could report that a
     * flow was refused but never why. The cause is carried out so the distinction between "no
     * bandwidth" and "no buffer" survives to the API — they have different remedies, and only the
     * second is what rate pacing exists to relieve.
     */
    record ScheduleAttempt(List<HopSchedule> hops, RefusalCause cause) {
        boolean scheduled() {
            return !hops.isEmpty();
        }

        static ScheduleAttempt refused(RefusalCause cause) {
            return new ScheduleAttempt(List.of(), cause);
        }
    }

    /**
     * The residual capacity a slot must offer a flow sent at {@code rateBps}: the rate itself, or,
     * when the headroom is enforced over the transmission interval, rate / headroom, so the flow takes
     * at most that fraction of what remains while it transmits.
     */
    private double slotRequirementBps(double rateBps) {
        return headroomEnforcement == HeadroomEnforcement.TRANSMISSION_INTERVAL
                ? rateBps / utilizationHeadroom : rateBps;
    }

    /** The rate policy's choice for this route, sped up by the transport margin. */
    private double assignRate(WorkloadTask task, List<Link> route, BaseTopology topology) {
        double rateBps = fProp.assignRate(task, route, topology, lrib, nrib, utilizationHeadroom);
        if (transportMargin == TransportMargin.NONE || !Double.isFinite(rateBps) || rateBps <= 0) {
            return rateBps;
        }
        double fasterBps;
        if (transportMargin == TransportMargin.RATE_FACTOR) {
            fasterBps = rateBps * transportMarginValue;
        } else {
            double windowSec = task.getEffectiveDeadlineSec() - task.getOriginationTimeSec()
                    - route.stream().mapToDouble(link -> link.getLif().getPropagationDelaySec()).sum();
            fasterBps = windowSec > transportMarginValue
                    ? rateBps * windowSec / (windowSec - transportMarginValue)
                    : Double.POSITIVE_INFINITY;
        }
        double ceilingBps = SchedulingCapacity.residualCeilingBps(
                task, route, topology.getRegime(), lrib, utilizationHeadroom);
        return Math.max(rateBps, Math.min(fasterBps, ceilingBps));
    }


    /**
     * The capacity weight of the hop that follows {@code placedBefore}: 1.0, or under R_STOCH
     * overbooking the probability that data reaches it, times the safety factor, capped at 1.
     */
    private boolean overbooks(ContactRegime regime) {
        return stochasticOverbook && regime == ContactRegime.R_STOCH;
    }

    /**
     * The capacity weight of the hop that follows {@code placedBefore}; see
     * {@link SchedulingCapacity#capacityWeight}. The solve puts the overbooking setting on the ledger,
     * so this and every generator and path policy weigh hops identically.
     */
    private double capacityWeight(ContactRegime regime, List<HopSchedule> placedBefore) {
        double reach = 1.0;
        for (HopSchedule hop : placedBefore) {
            reach *= SchedulingCapacity.hopSuccessProbability(hop.getLink(), hop.getTransmissionSlots());
        }
        return SchedulingCapacity.capacityWeight(lrib, reach);
    }

    private ScheduleAttempt scheduleAtRate(
            WorkloadTask tc, List<Link> rc, double rateBps, BaseTopology topology) {
        double totalTransferSec = tc.getTaskSizeBits() / rateBps;
        List<HopSchedule> hopSchedules = new ArrayList<>();
        double earliestHopStart = tc.getOriginationTimeSec();
        for (int hop = 0; hop < rc.size(); hop++) {
            Link link = rc.get(hop);
            // A pinned hop targets a specific (possibly later) contact; unpinned hops are unaffected.
            earliestHopStart = Math.max(earliestHopStart, PinnedRoute.notBefore(rc, hop));
            // Under overbooking the hop only has to fit at its capacity weight.
            double weight = capacityWeight(topology.getRegime(), hopSchedules);
            List<LRIB.TransmissionSlot> transmissionSlots = lrib.findEarliestFeasibleTransmission(
                    link, topology.getRegime(), slotRequirementBps(rateBps) * weight,
                    earliestHopStart, tc.getEffectiveDeadlineSec(), totalTransferSec);
            if (transmissionSlots.isEmpty()) {
                return ScheduleAttempt.refused(RefusalCause.NO_LINK_SLOT);
            }
            HopSchedule hopSchedule = new HopSchedule(link, transmissionSlots);
            if (hopSchedule.getArrivalSec()
                    > tc.getEffectiveDeadlineSec() + SchedulingCapacity.DEADLINE_TOLERANCE_SEC) {
                return ScheduleAttempt.refused(RefusalCause.DEADLINE_UNREACHABLE);
            }
            hopSchedules.add(hopSchedule);
            earliestHopStart = nextHopReadySec(topology.getRegime(), hopSchedule);
        }

        // The same tolerance as every other deadline comparison on this path. It used to be a bare
        // comparison here, which rejected flows whose completion landed one ULP past a deadline the
        // rate was constructed to hit exactly.
        double completionSec = hopSchedules.get(hopSchedules.size() - 1).getArrivalSec();
        if (completionSec
                > tc.getEffectiveDeadlineSec() + SchedulingCapacity.DEADLINE_TOLERANCE_SEC) {
            return ScheduleAttempt.refused(RefusalCause.DEADLINE_UNREACHABLE);
        }

        for (int h = 0; h < hopSchedules.size(); h++) {
            HopSchedule hopSchedule = hopSchedules.get(h);
            Link link = hopSchedule.getLink();
            double weight = capacityWeight(topology.getRegime(), hopSchedules.subList(0, h));
            for (LRIB.TransmissionSlot slot : hopSchedule.getTransmissionSlots()) {
                double availableBps = lrib.getAvailableCap(
                        link.getLinkId(), SchedulingCapacity.capacityBps(link, topology.getRegime()),
                        slot.startSec(), slot.endSec());
                if (slotRequirementBps(rateBps) * weight > availableBps + 1e-6) {
                    return ScheduleAttempt.refused(RefusalCause.LINK_CAPACITY);
                }
            }
            if (h > 0) {
                Node transitNode = topology.getNode(link.getSourceNodeId());
                if (transitNode == null || !hasBufferCapacityForHolds(nrib, transitNode,
                        transitHolds(topology.getRegime(), tc, hopSchedules.get(h - 1),
                                hopSchedule, rateBps))) {
                    return ScheduleAttempt.refused(RefusalCause.NODE_RESERVOIR);
                }
            }
        }
        return new ScheduleAttempt(hopSchedules, null);
    }

    /** Removal operations need their own SRP identifiers, distinct from installs. */
    private long nextRemovalSrpId() {
        return removalSrpSequence.incrementAndGet();
    }

    private final java.util.concurrent.atomic.AtomicLong removalSrpSequence =
            new java.util.concurrent.atomic.AtomicLong(1_000_000L);

    /**
     * Cancels a task safely.
     *
     * <p>Delegates to {@link #requestCancellation}, so this cannot release capacity for work that
     * reached the network. It was previously the unsafe path: it dropped the reservations for an
     * installed LSP while the intent still held capacity, and a later solve could hand that
     * bandwidth to another flow while a router was still forwarding.
     *
     * @return true when the task existed, whether its capacity was released or its removal
     *         requested; use {@link #requestCancellation} to tell those apart
     */
    public synchronized boolean cancelTask(String taskId) {
        return requestCancellation(taskId) != CancellationOutcome.NOT_FOUND;
    }

    private synchronized boolean releaseReservationsNow(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be blank");
        }
        List<LRIB.LinkReservation> priorLinkReservations = new ArrayList<>(lrib.getAllReservations());
        List<NRIB.NodeReservation> priorNodeReservations = new ArrayList<>(nrib.getAllReservations());
        List<net.dcn.pce.install.InstallationIntent> priorIntents = intents.snapshot();
        try {
            int removed = lrib.removeReservationsForTask(taskId) + nrib.removeReservationsForTask(taskId);
            if (removed > 0) {
                persistDelta(priorLinkReservations, priorNodeReservations);
            }
            return removed > 0;
        } catch (RuntimeException e) {
            rollbackLedgers(priorLinkReservations, priorNodeReservations, priorIntents);
            throw e;
        }
    }

    public synchronized int pruneCompletedReservations(double cutoffSec) {
        List<LRIB.LinkReservation> priorLinkReservations = new ArrayList<>(lrib.getAllReservations());
        List<NRIB.NodeReservation> priorNodeReservations = new ArrayList<>(nrib.getAllReservations());
        List<net.dcn.pce.install.InstallationIntent> priorIntents = intents.snapshot();
        try {
            int removed = pruneCompletedReservationsInMemory(cutoffSec);
            if (removed > 0) {
                persistDelta(priorLinkReservations, priorNodeReservations);
            }
            return removed;
        } catch (RuntimeException e) {
            rollbackLedgers(priorLinkReservations, priorNodeReservations, priorIntents);
            throw e;
        }
    }

    private Set<String> currentReservationIds() {
        Set<String> ids = new LinkedHashSet<>();
        lrib.getAllReservations().forEach(reservation -> ids.add(reservation.getReservationId()));
        nrib.getAllReservations().forEach(reservation -> ids.add(reservation.getReservationId()));
        return ids;
    }

    /**
     * Durably records the net effect of a completed transaction.
     *
     * <p>Only the difference is written. The prior lists are already materialized for rollback,
     * so computing the delta costs no additional traversal beyond a set membership test, and it
     * replaces a serialize-and-fsync of the entire ledger with one proportional to the change.
     */
    /**
     * Brings the intent ledger into agreement with a reservation change.
     *
     * <p>A task whose capacity was just booked is PLANNED; one whose reservations have all gone
     * away is terminal. Deliberately independent of persistence: these are facts about what the
     * controller intends, and they hold whether or not there is a log to write them to.
     */
    private void updateIntentsForReservationChange(
            List<LRIB.LinkReservation> addedLinks, List<NRIB.NodeReservation> addedNodes) {
        Set<String> plannedTaskIds = new LinkedHashSet<>();
        addedLinks.forEach(reservation -> plannedTaskIds.add(reservation.getTaskId()));
        addedNodes.forEach(reservation -> plannedTaskIds.add(reservation.getTaskId()));
        for (String taskId : plannedTaskIds) {
            if (intents.find(taskId).isEmpty()) {
                intents.plan(taskId, solvingForOwner);
            }
        }

        Set<String> stillHoldingCapacity = new LinkedHashSet<>();
        lrib.getAllReservations().forEach(r -> stillHoldingCapacity.add(r.getTaskId()));
        nrib.getAllReservations().forEach(r -> stillHoldingCapacity.add(r.getTaskId()));
        for (net.dcn.pce.install.InstallationIntent intent : intents.holdingCapacity()) {
            if (!stillHoldingCapacity.contains(intent.getTaskId())) {
                // Nothing was ever sent for a PLANNED task, so its removal is definitive. A task
                // that had reached the network must be confirmed removed instead, which is the
                // southbound loop's job rather than this diff's.
                if (intent.getState() == net.dcn.pce.install.InstallationState.PLANNED) {
                    intents.markFailed(intent.getTaskId());
                } else {
                    intents.markUncertain(intent.getTaskId());
                }
            }
        }
    }

    private void persistDelta(
            List<LRIB.LinkReservation> priorLinkReservations,
            List<NRIB.NodeReservation> priorNodeReservations) {
        Set<String> priorIds = new LinkedHashSet<>();
        priorLinkReservations.forEach(reservation -> priorIds.add(reservation.getReservationId()));
        priorNodeReservations.forEach(reservation -> priorIds.add(reservation.getReservationId()));

        Set<String> currentIds = new LinkedHashSet<>();
        List<LRIB.LinkReservation> addedLinks = new ArrayList<>();
        List<NRIB.NodeReservation> addedNodes = new ArrayList<>();
        for (LRIB.LinkReservation reservation : lrib.getAllReservations()) {
            currentIds.add(reservation.getReservationId());
            if (!priorIds.contains(reservation.getReservationId())) {
                addedLinks.add(reservation);
            }
        }
        for (NRIB.NodeReservation reservation : nrib.getAllReservations()) {
            currentIds.add(reservation.getReservationId());
            if (!priorIds.contains(reservation.getReservationId())) {
                addedNodes.add(reservation);
            }
        }

        Set<String> removedIds = new LinkedHashSet<>();
        priorIds.forEach(id -> {
            if (!currentIds.contains(id)) {
                removedIds.add(id);
            }
        });

        // The intent ledger follows the reservation diff whether or not anything is being
        // persisted. This used to sit below the stateStore guard, so with in-memory state -- the
        // default -- no intent was ever created: an admitted flow held capacity that nothing
        // described, and installation dispatch, cancellation of an installed LSP and
        // reconciliation all silently had nothing to act on. Every test covering intents happened
        // to configure durable state, so the whole in-memory configuration went unexercised.
        updateIntentsForReservationChange(addedLinks, addedNodes);

        if (stateStore == null) {
            return;
        }

        // Every intent travels with the transaction, not only the ones this diff changed:
        // a transition made by applyInstallationChange would otherwise update memory and never
        // reach the log.
        List<net.dcn.pce.install.InstallationIntent> intentTransitions =
                new ArrayList<>(intents.all());

        // The append is the commit point: once it returns, the transaction is durable.
        stateStore.append(new ReservationStore.ReservationDelta(
                addedLinks, addedNodes, removedIds, intentTransitions));

        // Compaction is maintenance, not part of the commit. Letting it throw here would
        // propagate to solve()'s rollback, so the caller would be told the request failed while
        // the appended record stayed durable — a restart would then restore a reservation the
        // client already observed as rejected. Log and carry on; the log is merely larger.
        try {
            stateStore.compactIfNeeded(lrib, nrib);
        } catch (RuntimeException e) {
            log.log(java.util.logging.Level.WARNING,
                    "Reservation log compaction failed after a durable commit; "
                            + "the transaction stands and the log will be compacted on a later commit", e);
        }
    }

    public synchronized void validateLedgers(BaseTopology topology) {
        CRPScheduleReplayValidator.ValidationReport validation =
                CRPScheduleReplayValidator.validateResult(new PCEComputationResult(), topology, lrib, nrib);
        if (!validation.isValid()) {
            throw new IllegalStateException("Persisted reservation state is invalid: " + validation.getErrorReason());
        }
    }

    /**
     * Executes 4-stage CRP Path Computation v2.0 with Schedulability CoS.
     */
    public synchronized PCEComputationResult solve(BaseTopology topology, List<WorkloadTask> tasks) {
        return solve(topology, tasks, SolveCancellation.withBudgetNanos(solveTimeoutNanos));
    }

    /**
     * Solves under a caller-supplied cancellation token.
     *
     * <p>The token is created by the caller so it can be held and signalled from another thread:
     * a solve that must stop because the controller is shutting down cannot learn that from its
     * own timekeeping.
     */
    public synchronized PCEComputationResult solve(
            BaseTopology topology, List<WorkloadTask> tasks, SolveCancellation cancellation) {
        return solve(topology, tasks, cancellation, null);
    }

    /**
     * Solves on behalf of {@code owner}, recording it on every intent this solve plans.
     *
     * <p>The owner reaches intent creation through a field rather than a parameter because the
     * intents are planned several layers down, inside the reservation transaction. That is safe
     * here and nowhere else: this method is synchronized, so exactly one solve is in flight, and
     * the field is cleared before the monitor is released.
     *
     * <p>Recording ownership inside the transaction is the point. It used to be claimed by the
     * HTTP handler after this method returned, so a crash in between left a committed reservation
     * whose task belonged to nobody.
     */
    public synchronized PCEComputationResult solve(
            BaseTopology topology, List<WorkloadTask> tasks, SolveCancellation cancellation,
            String owner) {
        this.solvingForOwner = owner;
        try {
            return solveOwned(topology, tasks, cancellation);
        } finally {
            this.solvingForOwner = null;
        }
    }

    /** The tenant the in-flight solve belongs to; only ever read under this object's monitor. */
    private String solvingForOwner;

    private PCEComputationResult solveOwned(
            BaseTopology topology, List<WorkloadTask> tasks, SolveCancellation cancellation) {
        List<LRIB.LinkReservation> priorLinkReservations = !persistentState
                ? List.of() : new ArrayList<>(lrib.getAllReservations());
        List<NRIB.NodeReservation> priorNodeReservations = !persistentState
                ? List.of() : new ArrayList<>(nrib.getAllReservations());
        List<net.dcn.pce.install.InstallationIntent> priorIntents = intents.snapshot();

        // Overbooking is judged on the ledger, so generators and path policies see it too.
        lrib.setOverbookingSafety(overbooks(topology.getRegime()) ? stochasticOverbookSafety : 0.0);

        if (!persistentState) {
            lrib.clear();
            nrib.clear();
        }

        // Carried on the thread so the expensive planner stages can honour the same token
        // without every policy signature growing a cancellation parameter.
        SolveDeadline.install(cancellation == null
                ? SolveCancellation.withBudgetNanos(solveTimeoutNanos) : cancellation);
        try {
            validateTaskStructure(tasks);
            if (persistentState) {
                double logicalNow = tasks.stream()
                        .mapToDouble(WorkloadTask::getOriginationTimeSec)
                        .min()
                        .orElseThrow();
                pruneCompletedReservationsInMemory(logicalNow);
            }
            validateTaskIdentities(tasks);
            PCEComputationResult result = solveTransaction(topology, tasks);
            // Persist only after replay validation succeeds, so the log never contains a plan
            // the engine itself rejected.
            persistDelta(priorLinkReservations, priorNodeReservations);
            return result;
        } catch (RuntimeException e) {
            rollbackLedgers(priorLinkReservations, priorNodeReservations, priorIntents);
            throw e;
        } finally {
            SolveDeadline.clear();
        }
    }

    private PCEComputationResult solveTransaction(BaseTopology topology, List<WorkloadTask> tasks) {
        long startTime = System.currentTimeMillis();
        // Monotonic, so a wall-clock adjustment mid-solve cannot extend or collapse the budget.
        long solveStartNanos = System.nanoTime();
        PCEComputationResult result = new PCEComputationResult();

        // Strict priority for R_STOCH global redundancy: each class of service is planned completely
        // (primaries, then its backups) before the next class starts, so best-effort primaries can never
        // occupy the capacity critical traffic needs for its redundancy. Otherwise a single pass.
        List<List<WorkloadTask>> phases = new ArrayList<>();
        if (stochasticGlobalAllocation && topology.getRegime() == ContactRegime.R_STOCH) {
            for (WorkloadTask.ClassOfService cos : WorkloadTask.ClassOfService.values()) {
                List<WorkloadTask> phase = new ArrayList<>();
                for (WorkloadTask task : tasks) {
                    if (task.getCosClass() == cos) {
                        phase.add(task);
                    }
                }
                if (!phase.isEmpty()) {
                    phases.add(phase);
                }
            }
        } else {
            phases.add(new ArrayList<>(tasks));
        }

        int totalTasksCount = tasks.size();
        int processedCount = 0;
        List<PendingRedundancy> pendingRedundancy = new ArrayList<>();

        System.out.printf("   [*] Starting DCN PCE v2.0 4-Stage Optimization (CoS-Aware) for %d flows...\n", totalTasksCount);
        System.out.flush();

        for (List<WorkloadTask> unassignedPool : phases) {
            while (!unassignedPool.isEmpty()) {
                // Cancellation point. Placed between workloads so a cancelled solve never leaves a
                // partially planned workload behind; the caller's rollback restores prior ledgers.
                if (solveTimeoutNanos > 0
                        && System.nanoTime() - solveStartNanos > solveTimeoutNanos) {
                    throw new SolveTimeoutException(
                            processedCount, totalTasksCount,
                            (System.nanoTime() - solveStartNanos) / 1_000_000L);
                }
                processedCount++;

                // Stage 1 (F_select): Select most critical task (CoS Tier & Slack aware)
                long stageStartNanos = System.nanoTime();
                WorkloadTask tc = fSelect.selectMostCriticalTask(unassignedPool, topology, lrib, nrib);
                result.recordStageNanos(PCEComputationResult.SolveStage.TASK_SELECTION,
                        System.nanoTime() - stageStartNanos);
                if (tc == null) break;

                // Stage 2 (F_generate): Enumerate candidate routes
                stageStartNanos = System.nanoTime();
                List<List<Link>> candidates = fGenerate.generateCandidateRoutes(tc, topology, lrib, nrib);
                result.recordStageNanos(PCEComputationResult.SolveStage.ROUTE_GENERATION,
                        System.nanoTime() - stageStartNanos);

                if (candidates.isEmpty()) {
                    result.addUnadmitted(tc, RefusalCause.NO_CANDIDATE_ROUTE);
                    unassignedPool.remove(tc);
                    continue;
                }

                // Stage 3 (F_route): Select least-crucial / earliest available path (EAP)
                stageStartNanos = System.nanoTime();
                List<Link> rc = fRoute.selectPath(tc, candidates, topology, lrib, nrib);
                result.recordStageNanos(PCEComputationResult.SolveStage.PATH_SELECTION,
                        System.nanoTime() - stageStartNanos);

                if (rc.isEmpty()) {
                    result.addUnadmitted(tc, RefusalCause.NO_PATH_SELECTED);
                    unassignedPool.remove(tc);
                    continue;
                }

                // Stage 4 (F_prop): deadline-window rate assignment.
                stageStartNanos = System.nanoTime();
                double eHatBps = assignRate(tc, rc, topology);
                if (!Double.isFinite(eHatBps) || eHatBps <= 0) {
                    // Last resort before refusing: on a partly used contact neither the equilibrium
                    // nor the best-instant ceiling may fit while a rate between them does. A task
                    // whose equilibrium already exceeds the ceiling keeps the policy's refusal.
                    double ceilingBps = SchedulingCapacity.residualCeilingBps(
                            tc, rc, topology.getRegime(), lrib, utilizationHeadroom);
                    double equilibriumBps = SchedulingCapacity.minimumWholeFlowRateBps(tc, rc, topology.getRegime());
                    if (Double.isFinite(equilibriumBps) && equilibriumBps <= ceilingBps + 1e-6) {
                        eHatBps = SchedulingCapacity.fastestSchedulableRateBps(
                                tc, rc, topology.getRegime(), lrib, ceilingBps);
                    }
                }
                result.recordStageNanos(PCEComputationResult.SolveStage.RATE_ASSIGNMENT,
                        System.nanoTime() - stageStartNanos);
                if (!Double.isFinite(eHatBps) || eHatBps <= 0) {
                    result.addUnadmitted(tc, RefusalCause.NO_FEASIBLE_RATE);
                    unassignedPool.remove(tc);
                    continue;
                }

                // The policy chooses a rate from link capacity and contact windows alone; it never
                // consults the node buffers. A slower rate is easier on links and *harder* on transit
                // buffers, because data arrives before the next contact opens and has to be held --
                // so a rate the policy considers feasible can still be rejected below. When that
                // happens, retry once at the fastest headroom-safe rate, which the policy has already
                // established is deliverable. Without this the admission decision is non-monotonic in
                // the deadline: more time moves the preferred rate out of the band where it is
                // rejected early and harmlessly, into the band where it is accepted by the link test
                // and then fatally rejected by the buffer.
                //
                // Nothing is committed until after these checks, so retrying needs no rollback.
                stageStartNanos = System.nanoTime();
                ScheduleAttempt attempt = scheduleAtRate(tc, rc, eHatBps, topology);
                if (!attempt.scheduled()) {
                    double ceilingBps = SchedulingCapacity.residualCeilingBps(
                            tc, rc, topology.getRegime(), lrib, utilizationHeadroom);
                    if (Double.isFinite(ceilingBps) && ceilingBps > eHatBps + 1e-6) {
                        ScheduleAttempt retried = scheduleAtRate(tc, rc, ceilingBps, topology);
                        if (retried.scheduled()) {
                            eHatBps = ceilingBps;
                            attempt = retried;
                        } else {
                            // The retry's cause supersedes the first attempt's: it is the reason the
                            // fastest deliverable rate also failed, which is the one an operator can
                            // act on.
                            attempt = retried;
                        }
                    }
                }
                List<HopSchedule> hopSchedules = attempt.hops();
                if (hopSchedules.isEmpty()) {
                    result.addUnadmitted(tc, attempt.cause());
                    unassignedPool.remove(tc);
                    continue;
                }
                double completionSec = hopSchedules.get(hopSchedules.size() - 1).getArrivalSec();

                // Commit link & node reservations across the primary route.
                double peakBufferBytes = commitRouteReservations(tc, hopSchedules, eHatBps, topology.getRegime());

                CommittedFlowSchedule schedule = new CommittedFlowSchedule(
                        tc, hopSchedules, eHatBps, peakBufferBytes);
                result.addCommitted(schedule);

                // R_STOCH only: buy contact-disjoint backup routes until the task's union survival
                // probability meets the confidence target. Deterministic contacts survive with probability
                // 1.0, so R_DET / R_STATIC never enter here and stay byte-identical.
                if (topology.getRegime() == ContactRegime.R_STOCH) {
                    if (stochasticGlobalAllocation) {
                        StochasticAdmission.SurvivalLedger ledger = newSurvivalLedger(tc);
                        ledger.add(StochasticAdmission.assess(hopSchedules));
                        pendingRedundancy.add(new PendingRedundancy(
                                tc, candidates, rc, ledger, pendingRedundancy.size()));
                    } else {
                        augmentStochasticRedundancy(tc, candidates, rc, hopSchedules, topology, result);
                    }
                }

                result.recordStageNanos(PCEComputationResult.SolveStage.SCHEDULE_AND_COMMIT,
                        System.nanoTime() - stageStartNanos);

                unassignedPool.remove(tc);
            }

            if (!pendingRedundancy.isEmpty()) {
                long redundancyStartNanos = System.nanoTime();
                allocateStochasticRedundancyGlobally(pendingRedundancy, topology, result);
                result.recordStageNanos(PCEComputationResult.SolveStage.SCHEDULE_AND_COMMIT,
                        System.nanoTime() - redundancyStartNanos);
                pendingRedundancy.clear();
            }
        }

        result.setComputationTimeMs(System.currentTimeMillis() - startTime);

        // Independent Replay Validation of All Admitted Schedules
        long validationStartNanos = System.nanoTime();
        CRPScheduleReplayValidator.ValidationReport validation =
                CRPScheduleReplayValidator.validateResult(result, topology, lrib, nrib);
        result.recordStageNanos(PCEComputationResult.SolveStage.REPLAY_VALIDATION,
                System.nanoTime() - validationStartNanos);
        if (!validation.isValid()) {
            throw new IllegalStateException("Schedule replay validation failed: " + validation.getErrorReason());
        }

        // Recorded after validation so it describes the plan that was actually committed. Every
        // node is listed, including those holding nothing: a zero is the evidence that a policy
        // avoided buffering, and omitting it would leave that indistinguishable from a node the
        // plan never touched.
        long reservoirStartNanos = System.nanoTime();
        for (Node node : topology.getNodes()) {
            result.addReservoirUsage(new ReservoirUsage(
                    node.getNodeId(),
                    nrib.getPeakBufferOccupancy(node.getNodeId()),
                    node.getReservoirCapacityBytes()));
        }
        result.recordStageNanos(PCEComputationResult.SolveStage.RESERVOIR_REPORTING,
                System.nanoTime() - reservoirStartNanos);

        return result;
    }

    private void validateTaskStructure(List<WorkloadTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("At least one task is required");
        }
        for (WorkloadTask task : tasks) {
            if (task == null) {
                throw new IllegalArgumentException("Task list cannot contain null entries");
            }
        }
    }

    private void validateTaskIdentities(List<WorkloadTask> tasks) {
        Set<String> requestTaskIds = new HashSet<>();
        for (WorkloadTask task : tasks) {
            String taskId = task.getTaskId();
            if (!requestTaskIds.add(taskId)
                    || (persistentState && (lrib.hasReservationsForTask(taskId)
                    || nrib.hasReservationsForTask(taskId)))) {
                throw new DuplicateTaskException(taskId);
            }
        }
    }

    /**
     * True when a task's capacity is governed by the LSP lifecycle rather than by logical time.
     *
     * <p>Once something has been sent to a PCC, only a confirmed removal releases its capacity.
     * Pruning it on the clock would drop the reservations while the intent still holds capacity,
     * which is the same divergence cancellation used to create.
     */
    private boolean isGovernedByTheNetwork(String taskId) {
        return intents.find(taskId)
                .filter(intent -> intent.getState() != net.dcn.pce.install.InstallationState.PLANNED)
                .filter(net.dcn.pce.install.InstallationIntent::holdsCapacity)
                .isPresent();
    }

    private int pruneCompletedReservationsInMemory(double cutoffSec) {
        // Work the network may still be carrying is exempt: its capacity is released by a
        // confirmed removal, not by the clock.
        java.util.function.Predicate<String> governed = this::isGovernedByTheNetwork;
        return lrib.pruneReservationsEndingAtOrBefore(cutoffSec, governed)
                + nrib.pruneReservationsEndingAtOrBefore(cutoffSec, governed);
    }

    /**
     * Commits a route's link-capacity and transit-buffer reservations and returns its peak transit
     * buffer. Shared by the primary commit and the R_STOCH backup commits so they reserve identically.
     */
    private double commitRouteReservations(WorkloadTask tc, List<HopSchedule> hopSchedules, double rateBps,
                                           ContactRegime regime) {
        double peakBufferBytes = 0.0;
        for (int h = 0; h < hopSchedules.size(); h++) {
            HopSchedule hopSchedule = hopSchedules.get(h);
            Link link = hopSchedule.getLink();
            double weight = capacityWeight(regime, hopSchedules.subList(0, h));
            for (LRIB.TransmissionSlot slot : hopSchedule.getTransmissionSlots()) {
                lrib.reserveLinkCap(tc.getTaskId(), link.getLinkId(), link.getSourceNodeId(),
                        link.getDestinationNodeId(), rateBps, slot.startSec(), slot.endSec(), weight);
            }
            // Reserve transit buffer only for what the node actually holds between hops.
            if (h > 0) {
                for (double[] hold : transitHolds(regime, tc, hopSchedules.get(h - 1),
                        hopSchedule, rateBps)) {
                    nrib.reserveNodeBuff(tc.getTaskId(), link.getSourceNodeId(),
                            hold[0], hold[1], hold[2]);
                }
                peakBufferBytes = Math.max(peakBufferBytes,
                        nrib.getPeakBufferOccupancy(link.getSourceNodeId()));
            }
        }
        return peakBufferBytes;
    }

    /**
     * R_STOCH redundancy: commit additional contact-disjoint routes for {@code tc} until the union
     * survival probability of its committed routes reaches {@link #stochasticConfidenceAlpha}, or the
     * candidate routes are exhausted. Each backup is an independent committed schedule for the same
     * task; delivery succeeds if ANY committed route survives, which is what lifts stochastic SDP
     * toward the routing-redundancy (CGR-UCoP) frontier. Best-effort: the task is already admitted via
     * its primary route, so a backup that cannot schedule (capacity) or is not contact-disjoint (which
     * would break the independence the union formula assumes) is simply skipped.
     */
    /**
     * GLOBAL_MARGINAL R_STOCH redundancy. Runs after every task's primary is committed, so backups can
     * never consume capacity a later task needs for its primary. Each step commits, across all tasks,
     * the schedulable backup with the largest marginal survival gain; joint survival is monotone
     * submodular in the committed routes, so this is the greedy optimum for spending finite capacity.
     * Lazy evaluation: a proposal computed earlier is re-scheduled before commit, because capacity it
     * relied on may since have been reserved; if it changed it is re-queued with its new gain.
     */
    private void allocateStochasticRedundancyGlobally(
            List<PendingRedundancy> pending, BaseTopology topology, PCEComputationResult result) {
        PriorityQueue<BackupProposal> queue = new PriorityQueue<>(Comparator
                .comparingDouble(BackupProposal::gain).reversed()
                .thenComparingInt(proposal -> proposal.pending().order));
        for (PendingRedundancy task : pending) {
            if (task.ledger.survival() < stochasticConfidenceAlpha) {
                BackupProposal proposal = nextBackup(task, topology);
                if (proposal != null) {
                    queue.add(proposal);
                }
            }
        }
        while (!queue.isEmpty()) {
            net.dcn.pce.crp.SolveDeadline.checkpoint("global stochastic redundancy");
            BackupProposal top = queue.poll();
            PendingRedundancy task = top.pending();
            BackupProposal current = evaluateBackup(task, top.route(), topology);
            if (current == null) {
                BackupProposal next = nextBackup(task, topology);
                if (next != null) {
                    queue.add(next);
                }
                continue;
            }
            BackupProposal best = queue.peek();
            if (best != null && current.gain() + 1e-12 < best.gain()) {
                queue.add(current); // stale: its gain fell below the next best; revisit later
                continue;
            }
            double peakBufferBytes = commitRouteReservations(task.task, current.hops(), current.rateBps(), topology.getRegime());
            result.addCommitted(new CommittedFlowSchedule(
                    task.task, current.hops(), current.rateBps(), peakBufferBytes));
            task.ledger.add(current.risk());
            if (task.ledger.survival() < stochasticConfidenceAlpha) {
                BackupProposal next = nextBackup(task, topology);
                if (next != null) {
                    queue.add(next);
                }
            }
        }
    }

    /** Weight of a task's delivery in the global redundancy objective (by class of service). */
    private static double redundancyWeight(WorkloadTask task) {
        return switch (task.getCosClass()) {
            case STRICT_HARD_DEADLINE -> 10.0;
            case MEDIUM_SOFT_LAXITY -> 3.0;
            default -> 1.0;
        };
    }

    /** The task's next candidate that schedules now and keeps its survival expression exact. */
    private BackupProposal nextBackup(PendingRedundancy task, BaseTopology topology) {
        while (task.nextCandidate < task.candidates.size()) {
            List<Link> candidate = task.candidates.get(task.nextCandidate++);
            if (candidate == task.primaryRoute) { // identity: pinned variants share links
                continue;
            }
            BackupProposal proposal = evaluateBackup(task, candidate, topology);
            if (proposal != null) {
                return proposal;
            }
        }
        return null;
    }

    /** Schedules {@code route} against current reservations; null if it cannot be committed now. */
    private BackupProposal evaluateBackup(PendingRedundancy task, List<Link> route, BaseTopology topology) {
        double rateBps = assignRate(task.task, route, topology);
        if (!Double.isFinite(rateBps) || rateBps <= 0) {
            return null;
        }
        ScheduleAttempt attempt = scheduleAtRate(task.task, route, rateBps, topology);
        if (!attempt.scheduled()) {
            return null;
        }
        StochasticAdmission.RouteRisk risk = StochasticAdmission.assess(attempt.hops());
        if (!task.ledger.accepts(risk)) {
            return null;
        }
        double gain = task.ledger.survivalWith(risk) - task.ledger.survival();
        if (gain <= 1e-12) {
            return null; // adds no delivery probability; do not spend capacity on it
        }
        // Maximise priority-weighted delivery, sum_i w_i * SDP_i: still monotone submodular, so greedy
        // by weighted marginal gain remains the right way to spend capacity, and critical traffic is
        // preferred for backups exactly in proportion to its weight.
        return new BackupProposal(task, route, rateBps, attempt.hops(), risk,
                gain * redundancyWeight(task.task));
    }

    private void augmentStochasticRedundancy(
            WorkloadTask tc, List<List<Link>> candidates, List<Link> primaryRoute,
            List<HopSchedule> primaryHops, BaseTopology topology, PCEComputationResult result) {
        StochasticAdmission.SurvivalLedger ledger = newSurvivalLedger(tc);
        ledger.add(StochasticAdmission.assess(primaryHops));
        if (ledger.survival() >= stochasticConfidenceAlpha) {
            return; // the primary route alone meets the confidence target
        }
        for (List<Link> candidate : candidates) {
            if (ledger.survival() >= stochasticConfidenceAlpha) {
                break;
            }
            if (candidate == primaryRoute) { // identity: pinned variants share links
                continue;
            }
            double rateBps = assignRate(tc, candidate, topology);
            if (!Double.isFinite(rateBps) || rateBps <= 0) {
                continue;
            }
            ScheduleAttempt attempt = scheduleAtRate(tc, candidate, rateBps, topology);
            if (!attempt.scheduled()) {
                continue;
            }
            List<HopSchedule> hops = attempt.hops();
            StochasticAdmission.RouteRisk risk = StochasticAdmission.assess(hops);
            if (!ledger.accepts(risk)) {
                continue; // would break the independence the survival expression relies on
            }
            double peakBufferBytes = commitRouteReservations(tc, hops, rateBps, topology.getRegime());
            result.addCommitted(new CommittedFlowSchedule(tc, hops, rateBps, peakBufferBytes));
            ledger.add(risk);
        }
    }

    private static boolean hasBufferCapacityForHolds(NRIB nrib, Node node, List<double[]> holds) {
        for (double[] hold : holds) {
            if (nrib.getPeakBufferOccupancy(node.getNodeId(), hold[1], hold[2])
                    + hold[0] > node.getReservoirCapacityBytes() + 1e-6) {
                return false;
            }
        }
        return true;
    }

    /** When the hop after {@code previous} may start; see {@link SchedulingCapacity#nextHopReadySec}. */
    static double nextHopReadySec(ContactRegime regime, HopSchedule previous) {
        return SchedulingCapacity.nextHopReadySec(regime, previous.getStartSec(), previous.getEndSec(),
                previous.getLink().getLif().getPropagationDelaySec());
    }

    /** What the node between {@code previous} and {@code hop} holds; see {@link SchedulingCapacity#transitHolds}. */
    static List<double[]> transitHolds(ContactRegime regime, WorkloadTask task, HopSchedule previous,
                                       HopSchedule hop, double rateBps) {
        return SchedulingCapacity.transitHolds(regime, nextHopReadySec(regime, previous),
                previous.getArrivalSec(), hop.getTransmissionSlots(), rateBps, task.getTaskSizeBytes());
    }

    /**
     * Restores the ledgers and the intent ledger together.
     *
     * <p>Intents are part of the transaction: persistDelta publishes a PLANNED intent before the
     * durable append, so a failed append that restored only LRIB and NRIB would leave an intent
     * for capacity the engine no longer holds. That is the same "publish nothing before the
     * durable write" rule the reservation log follows, applied to the ledger that sits beside it.
     */
    private void rollbackLedgers(
            List<LRIB.LinkReservation> linkReservations,
            List<NRIB.NodeReservation> nodeReservations,
            List<net.dcn.pce.install.InstallationIntent> intentSnapshot) {
        if (intentSnapshot != null) {
            intents.resetTo(intentSnapshot);
        }
        rollbackLedgers(linkReservations, nodeReservations);
    }

    private void rollbackLedgers(
            List<LRIB.LinkReservation> linkReservations,
            List<NRIB.NodeReservation> nodeReservations) {
        lrib.clear();
        nrib.clear();
        for (LRIB.LinkReservation state : linkReservations) {
            lrib.restoreLinkReservation(
                    state.getReservationId(), state.getTaskId(), state.getLinkId(), state.getSourceNodeId(),
                    state.getDestNodeId(), state.getReservedBwBps(), state.getStartSec(), state.getEndSec(),
                    state.getWeight());
        }
        for (NRIB.NodeReservation state : nodeReservations) {
            nrib.restoreNodeReservation(
                    state.getReservationId(), state.getTaskId(), state.getNodeId(), state.getReservedBufferBytes(),
                    state.getStartSec(), state.getEndSec());
        }
    }

    /** Installation intents held by this engine. */
    public net.dcn.pce.install.IntentLedger getIntents() { return intents; }

    /**
     * Applies a change to installation intent as one durable transaction.
     *
     * <p>Every southbound-driven transition goes through here rather than mutating the intent
     * ledger directly. Three things must move together or not at all: the intent's state, the
     * reservations it accounts for, and the durable record of both. Applying the transition to
     * memory alone loses it on restart; releasing capacity without recording it, or recording it
     * without releasing, leaves the ledger and the intent disagreeing about whether bandwidth is
     * spoken for.
     *
     * <p>Capacity is released exactly when an intent reaches a terminal state. That is the point
     * at which the network has confirmed the LSP is gone, or the controller has confirmed it never
     * existed; before that, an unacknowledged operation may still be carrying traffic.
     *
     * @param transition mutates the intent ledger and returns the intent it changed, or null for
     *                   no change
     * @return the resulting intent, or empty when the transition made no change
     */
    public synchronized java.util.Optional<net.dcn.pce.install.InstallationIntent> applyInstallationChange(
            java.util.function.Supplier<net.dcn.pce.install.InstallationIntent> transition) {
        List<LRIB.LinkReservation> priorLinkReservations = new ArrayList<>(lrib.getAllReservations());
        List<NRIB.NodeReservation> priorNodeReservations = new ArrayList<>(nrib.getAllReservations());
        List<net.dcn.pce.install.InstallationIntent> priorIntents = intents.snapshot();

        try {
            net.dcn.pce.install.InstallationIntent changed = transition.get();
            if (changed == null) {
                return java.util.Optional.empty();
            }
            if (changed.getState().isTerminal()) {
                lrib.removeReservationsForTask(changed.getTaskId());
                nrib.removeReservationsForTask(changed.getTaskId());
            }
            persistDelta(priorLinkReservations, priorNodeReservations);
            return java.util.Optional.of(changed);
        } catch (RuntimeException e) {
            rollbackLedgers(priorLinkReservations, priorNodeReservations, priorIntents);
            throw e;
        }
    }

    public LRIB getLRIB() { return lrib; }
    public NRIB getNRIB() { return nrib; }
}
