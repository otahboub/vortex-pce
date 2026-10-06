package net.dcn.pce.metrics;

import net.dcn.pce.BuildInfo;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Controller metrics in Prometheus text exposition format.
 *
 * <p>The previous {@code /metrics} payload reported JVM heap and thread counts as bespoke JSON.
 * That is neither scrapeable nor informative: heap size does not indicate whether the scheduler
 * is admitting flows. These are the admission-control signals an operator actually alerts on —
 * accept and reject rates by cause, planning latency, deadline misses, and ledger depth.
 *
 * <p>All counters are monotonic, as Prometheus requires; gauges are sampled at scrape time.
 */
public final class ControllerMetrics {

    /**
     * Latency buckets in seconds. The spread is deliberately wide: planning a small batch is
     * sub-second, while a constellation-scale batch runs for tens of seconds, and the interesting
     * alert is the transition between those regimes.
     */
    private static final double[] LATENCY_BUCKETS_SEC = {
            0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 300
    };

    /**
     * Pre-registered so every state has a series from the first scrape. A gauge that appears only
     * once a task reaches a state leaves an alert on that state silently unevaluated until the
     * condition it is meant to catch has already happened.
     */
    private static final java.util.List<String> INTENT_STATES = java.util.List.of(
            "PLANNED", "INSTALLING", "INSTALLED", "UNCERTAIN", "DELETING", "FAILED", "DELETED");

    private final Map<String, AtomicLong> solveOutcomes = new ConcurrentHashMap<>();
    private final AtomicLong workloadsOffered = new AtomicLong();
    private final AtomicLong workloadsCommitted = new AtomicLong();
    private final AtomicLong workloadsUnadmitted = new AtomicLong();
    private final AtomicLong deadlinesMet = new AtomicLong();
    private final AtomicLong deadlinesMissed = new AtomicLong();
    private final AtomicLong tasksCancelled = new AtomicLong();

    private final AtomicLong[] latencyBucketCounts = new AtomicLong[LATENCY_BUCKETS_SEC.length];
    private final AtomicLong latencyCount = new AtomicLong();
    private final DoubleAdder latencySumSec = new DoubleAdder();

    public ControllerMetrics() {
        for (int i = 0; i < latencyBucketCounts.length; i++) {
            latencyBucketCounts[i] = new AtomicLong();
        }
        // Pre-register every outcome so a rate() over a label that has not occurred yet returns
        // zero rather than no series at all, which would leave an alert silently unevaluated.
        for (SolveOutcome outcome : SolveOutcome.values()) {
            solveOutcomes.put(outcome.label(), new AtomicLong());
        }
    }

    /** Terminal outcome of a solve request, as observed at the northbound API. */
    public enum SolveOutcome {
        SUCCESS,
        DUPLICATE,
        INVALID,
        TIMEOUT,
        REJECTED_BUSY,
        ERROR;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public void recordSolveOutcome(SolveOutcome outcome) {
        solveOutcomes.computeIfAbsent(outcome.label(), key -> new AtomicLong()).incrementAndGet();
    }

    /** Records the planning result of a completed solve. */
    public void recordSolveResult(
            int offered, int committed, int unadmitted, int metDeadline, double durationSec) {
        workloadsOffered.addAndGet(offered);
        workloadsCommitted.addAndGet(committed);
        workloadsUnadmitted.addAndGet(unadmitted);
        deadlinesMet.addAndGet(metDeadline);
        deadlinesMissed.addAndGet(Math.max(0, committed - metDeadline));
        observeLatency(durationSec);
    }

    /** Records planning latency for a solve that ended without a result, such as a timeout. */
    public void observeLatency(double durationSec) {
        if (durationSec < 0 || !Double.isFinite(durationSec)) {
            return;
        }
        latencyCount.incrementAndGet();
        latencySumSec.add(durationSec);
        for (int i = 0; i < LATENCY_BUCKETS_SEC.length; i++) {
            if (durationSec <= LATENCY_BUCKETS_SEC[i]) {
                latencyBucketCounts[i].incrementAndGet();
            }
        }
    }

    public void recordTaskCancelled() {
        tasksCancelled.incrementAndGet();
    }

    /**
     * Renders the exposition payload.
     *
     * @param linkReservations  current LRIB depth, sampled by the caller
     * @param nodeReservations  current NRIB depth, sampled by the caller
     * @param plannerBusy       whether a solve currently holds the single-flight planner
     * @param httpQueueDepth    queued requests awaiting an HTTP worker
     * @param httpActive        active HTTP workers
     */
    public String render(
            int linkReservations,
            int nodeReservations,
            boolean plannerBusy,
            int httpQueueDepth,
            int httpActive) {
        return render(linkReservations, nodeReservations, plannerBusy, httpQueueDepth, httpActive,
                java.util.Map.of());
    }

    /**
     * Renders the exposition payload including installation state.
     *
     * @param intentsByState count of installation intents per state, sampled by the caller
     */
    public String render(
            int linkReservations,
            int nodeReservations,
            boolean plannerBusy,
            int httpQueueDepth,
            int httpActive,
            java.util.Map<String, Integer> intentsByState) {
        return render(linkReservations, nodeReservations, plannerBusy, httpQueueDepth, httpActive,
                intentsByState, -1);
    }

    /**
     * @param establishedPccSessions established southbound sessions, or a negative value when no
     *                               listener is running, so "listener off" reads differently from
     *                               "listener on with no peers"
     */
    public String render(
            int linkReservations,
            int nodeReservations,
            boolean plannerBusy,
            int httpQueueDepth,
            int httpActive,
            java.util.Map<String, Integer> intentsByState,
            int establishedPccSessions) {
        StringBuilder out = new StringBuilder(2048);

        out.append("# HELP vortex_build_info Controller build identity.\n");
        out.append("# TYPE vortex_build_info gauge\n");
        out.append(String.format("vortex_build_info{version=\"%s\",artifact=\"%s\"} 1%n",
                escapeLabel(BuildInfo.version()), escapeLabel(BuildInfo.artifactId())));

        out.append("# HELP vortex_solve_requests_total Solve requests by terminal outcome.\n");
        out.append("# TYPE vortex_solve_requests_total counter\n");
        solveOutcomes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.append(String.format(
                        "vortex_solve_requests_total{outcome=\"%s\"} %d%n",
                        escapeLabel(entry.getKey()), entry.getValue().get())));

        appendCounter(out, "vortex_workloads_offered_total",
                "Workloads submitted for planning.", workloadsOffered.get());
        appendCounter(out, "vortex_workloads_committed_total",
                "Workloads admitted with a committed schedule.", workloadsCommitted.get());
        appendCounter(out, "vortex_workloads_unadmitted_total",
                "Workloads rejected by admission control.", workloadsUnadmitted.get());
        appendCounter(out, "vortex_deadlines_met_total",
                "Committed workloads whose schedule meets its deadline.", deadlinesMet.get());
        appendCounter(out, "vortex_deadlines_missed_total",
                "Committed workloads whose schedule misses its deadline.", deadlinesMissed.get());
        appendCounter(out, "vortex_tasks_cancelled_total",
                "Reservations released through the cancellation endpoint.", tasksCancelled.get());

        out.append("# HELP vortex_solve_duration_seconds Planning wall-clock duration.\n");
        out.append("# TYPE vortex_solve_duration_seconds histogram\n");
        long cumulative;
        for (int i = 0; i < LATENCY_BUCKETS_SEC.length; i++) {
            cumulative = latencyBucketCounts[i].get();
            out.append(String.format("vortex_solve_duration_seconds_bucket{le=\"%s\"} %d%n",
                    formatBucket(LATENCY_BUCKETS_SEC[i]), cumulative));
        }
        out.append(String.format("vortex_solve_duration_seconds_bucket{le=\"+Inf\"} %d%n",
                latencyCount.get()));
        out.append(String.format(Locale.ROOT, "vortex_solve_duration_seconds_sum %s%n",
                formatDouble(latencySumSec.sum())));
        out.append(String.format("vortex_solve_duration_seconds_count %d%n", latencyCount.get()));

        appendGauge(out, "vortex_link_reservations",
                "Live link reservations held in the LRIB.", linkReservations);
        appendGauge(out, "vortex_node_reservations",
                "Live node-buffer reservations held in the NRIB.", nodeReservations);
        // Installation state per task. The series that matters operationally is UNCERTAIN: those
        // tasks hold capacity the controller cannot confirm is installed, and a number that grows
        // and does not fall means acknowledgements are not arriving. A gauge rather than a
        // counter, because a task leaves a state as well as entering one.
        out.append("# HELP vortex_installation_intents Installation intents by state.\n");
        out.append("# TYPE vortex_installation_intents gauge\n");
        for (String state : INTENT_STATES) {
            out.append(String.format("vortex_installation_intents{state=\"%s\"} %d%n",
                    escapeLabel(state), intentsByState.getOrDefault(state, 0)));
        }

        if (establishedPccSessions >= 0) {
            appendGauge(out, "vortex_pcc_sessions_established",
                    "PCC sessions currently established with the southbound listener.",
                    establishedPccSessions);
        }

        appendGauge(out, "vortex_planner_busy",
                "1 when the single-flight planner is occupied.", plannerBusy ? 1 : 0);
        appendGauge(out, "vortex_http_queue_depth",
                "Requests queued for an HTTP worker.", httpQueueDepth);
        appendGauge(out, "vortex_http_active_requests",
                "Requests currently being served.", httpActive);

        Runtime runtime = Runtime.getRuntime();
        appendGauge(out, "vortex_jvm_heap_used_bytes", "Used JVM heap.",
                runtime.totalMemory() - runtime.freeMemory());
        appendGauge(out, "vortex_jvm_heap_total_bytes", "Committed JVM heap.", runtime.totalMemory());
        appendGauge(out, "vortex_jvm_heap_max_bytes", "Maximum JVM heap.", runtime.maxMemory());

        return out.toString();
    }

    private static void appendCounter(StringBuilder out, String name, String help, long value) {
        out.append(String.format("# HELP %s %s%n# TYPE %s counter%n%s %d%n", name, help, name, name, value));
    }

    private static void appendGauge(StringBuilder out, String name, String help, long value) {
        out.append(String.format("# HELP %s %s%n# TYPE %s gauge%n%s %d%n", name, help, name, name, value));
    }

    private static String formatBucket(double bound) {
        return bound == Math.floor(bound)
                ? String.format(Locale.ROOT, "%.1f", bound)
                : String.format(Locale.ROOT, "%s", bound);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
