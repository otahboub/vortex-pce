package net.dcn.pce.northbound;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.util.LogSanitizer;
import net.dcn.pce.model.*;
import java.util.logging.Logger;

import java.io.IOException;
import java.util.List;

/**
 * Northbound REST API Controller for DCN PCE.
 * Receives Workload Task JSON blobs over HTTP/REST, invokes the 4-stage CRP PCE engine,
 * and returns computed per-hop schedules and aggregate planning metrics.
 */
public class PCERestController {

    private static final Logger log = Logger.getLogger(PCERestController.class.getName());

    private final CRPEngine crpEngine;
    private final net.dcn.pce.metrics.ControllerMetrics metrics;
    private BaseTopology activeTopology;

    public PCERestController(CRPEngine crpEngine, BaseTopology activeTopology) {
        this(crpEngine, activeTopology, new net.dcn.pce.metrics.ControllerMetrics());
    }

    public PCERestController(
            CRPEngine crpEngine,
            BaseTopology activeTopology,
            net.dcn.pce.metrics.ControllerMetrics metrics) {
        this.crpEngine = crpEngine;
        this.activeTopology = activeTopology;
        this.metrics = metrics == null ? new net.dcn.pce.metrics.ControllerMetrics() : metrics;
    }

    public net.dcn.pce.metrics.ControllerMetrics getMetrics() {
        return metrics;
    }

    /** Live LRIB depth, sampled for metrics exposition. */
    public int getLinkReservationCount() {
        return crpEngine.getLRIB().getAllReservations().size();
    }

    /** Applies an installation change inside the engine's durable transaction. */
    public java.util.Optional<net.dcn.pce.install.InstallationIntent> applyInstallationReport(
            java.util.function.Supplier<net.dcn.pce.install.InstallationIntent> transition) {
        return crpEngine.applyInstallationChange(transition);
    }

    /** The engine's intent ledger, for wiring the southbound loop. */
    public net.dcn.pce.install.IntentLedger getIntentLedger() {
        return crpEngine.getIntents();
    }

    /** Installation intents grouped by state, sampled for metrics exposition. */
    public java.util.Map<String, Integer> getIntentCountsByState() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        crpEngine.getIntents().all().forEach(intent ->
                counts.merge(intent.getState().name(), 1, Integer::sum));
        return counts;
    }

    /** The installation intent for one task, if the controller has one. */
    public java.util.Optional<net.dcn.pce.install.InstallationIntent> findIntent(String taskId) {
        return crpEngine.getIntents().find(taskId);
    }

    /** Live NRIB depth, sampled for metrics exposition. */
    public int getNodeReservationCount() {
        return crpEngine.getNRIB().getAllReservations().size();
    }

    /**
     * Accepts a JSON Blob string of workload tasks, computes path & rate schedule using CRP engine,
     * and returns a structurally serialized JSON response with per-hop timing.
     */
    /**
     * Convenience entry point for an uncancellable solve.
     *
     * <p>Deliberately {@code final}: the server calls the token-carrying overload, so a subclass
     * that overrode this one would be silently bypassed. Overriders must target the primary
     * method below and the compiler now says so.
     */
    /**
     * Excludes capacity changes from solves in flight.
     *
     * <p>Both operations were individually safe and raced each other. A solve reads
     * {@code activeTopology} and admits against it; {@code recordObservedCapacity} independently
     * checked the LRIB and swapped the topology. With no lock between them an observation could
     * pass its committed-capacity check while a solve holding the *old* topology had not yet
     * committed, and that solve would then reserve bandwidth the link no longer has — leaving the
     * ledger over-subscribed, which is the state the 409 refusal exists to prevent.
     *
     * <p>A read/write lock rather than a monitor, because solves must not exclude each other here
     * (the server already admits one at a time) while a capacity change must exclude all of them.
     *
     * <p>Package-private so a test can hold the read lock deterministically instead of racing a
     * real solve and hoping the window opens.
     */
    final java.util.concurrent.locks.ReentrantReadWriteLock topologyLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    /**
     * How long a capacity observation waits for a solve to finish before giving up.
     *
     * <p>Package-private so a test can shorten it. Exercising this honestly needs a second thread
     * holding the lock for longer than the wait, and at the production value that is five seconds
     * of sleeping per assertion.
     */
    volatile long capacityLockWaitMillis = 5_000;

    /**
     * Durable observations, when the deployment has a state path. Absent for in-memory use.
     *
     * <p>Without this an observation lived only in the running process: a restart restored the
     * capacity declared at startup, so a link an operator had corrected downward was silently
     * planned against at its original rate again.
     */
    private net.dcn.pce.topology.CapacityStore capacityStore;

    /**
     * Makes capacity observations survive a restart, and re-applies any already recorded.
     *
     * <p>Applied directly to the topology rather than through {@code recordObservedCapacity},
     * because that path refuses an observation below what is already committed — correct for a
     * live correction, wrong at startup, where the reservations being compared against are the
     * ones this very capacity admitted.
     */
    public void withDurableObservedCapacity(
            net.dcn.pce.topology.CapacityStore store) {
        // Takes the same write lock as a live observation. Nothing can be solving at startup, so
        // this is uncontended -- but activeTopology has exactly one rule about who may replace it
        // and having a second path that quietly ignores it is how the first race got in.
        topologyLock.writeLock().lock();
        try {
            applyDurableObservations(store);
        } finally {
            topologyLock.writeLock().unlock();
        }
    }

    private void applyDurableObservations(net.dcn.pce.topology.CapacityStore store) {
        this.capacityStore = store;
        store.all().forEach((linkId, observation) -> {
            if (activeTopology.getLink(linkId) == null) {
                log.warning("Ignoring observed capacity for unknown link "
                        + LogSanitizer.singleLine(linkId)
                        + "; the topology no longer contains it");
                return;
            }
            activeTopology = net.dcn.pce.topology.ObservedCapacity.withLinkCapacity(
                    activeTopology, linkId, observation.observedBps);
            log.info(String.format("Restored observed capacity for %s: %.0f bps (declared %.0f)",
                    LogSanitizer.singleLine(linkId), observation.observedBps,
                    observation.previousBps));
        });
    }

    /** What each link is running on, and whether that was observed or declared. */
    public java.util.Map<String, net.dcn.pce.topology.ObservedCapacityStore.Observation>
            observedCapacities() {
        return capacityStore == null ? java.util.Map.of() : capacityStore.all();
    }

    /**
     * Re-reads durable observations into the active topology, used on promotion.
     *
     * <p>Observations are loaded once when the controller is built. On the shared backend another
     * instance keeps writing corrections while this one stands by, so a standby promoted later would
     * otherwise plan against capacity as of its own startup — over-admitting on any link the former
     * leader had corrected downward in between (failover staleness). Refreshing on
     * promotion, before this instance serves solves, closes that. Idempotent: the store holds each
     * link's current corrected capacity and {@code withLinkCapacity} sets it absolutely, so
     * re-applying the same observations is a no-op. No store, nothing to do.
     */
    public void refreshDurableObservedCapacity() {
        net.dcn.pce.topology.CapacityStore store = this.capacityStore;
        if (store == null) {
            return;
        }
        topologyLock.writeLock().lock();
        try {
            applyDurableObservations(store);
        } finally {
            topologyLock.writeLock().unlock();
        }
    }

    /** Raised when a capacity observation cannot be applied because a solve is in flight. */
    public static final class CapacityUpdateBusyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CapacityUpdateBusyException(String message) {
            super(message);
        }
    }

    /**
     * Raised when work cannot be admitted because no PCC is connected and the undispatched buffer
     * is full: accepting it would commit capacity the controller cannot install. Retryable once a
     * router attaches or the backlog drains.
     */
    public static final class UndispatchableBacklogException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UndispatchableBacklogException(String message) {
            super(message);
        }
    }

    public final String handleScheduleWorkloadsRequest(String workloadJsonBlob) throws IOException {
        return handleScheduleWorkloadsRequest(workloadJsonBlob, null);
    }

    /** Primary entry point: schedules under a caller-supplied cancellation token. */
    public String handleScheduleWorkloadsRequest(
            String workloadJsonBlob, net.dcn.pce.crp.SolveCancellation cancellation)
            throws IOException {
        return handleScheduleWorkloadsRequest(workloadJsonBlob, cancellation, null);
    }

    /** Schedules on behalf of {@code owner}, which is recorded with the reservations it commits. */
    public String handleScheduleWorkloadsRequest(
            String workloadJsonBlob, net.dcn.pce.crp.SolveCancellation cancellation, String owner)
            throws IOException {
        log.info(RequestContext.tag("Northbound REST API: Received Workload Task JSON Blob..."));
        List<WorkloadTask> tasks = JSONUtils.parseWorkloadJson(workloadJsonBlob);

        // Enforce the undispatched-buffer bound before admission, not after. When no PCC is
        // connected every admitted task is held for later dispatch; the controller used to commit
        // the reservation and only then find the buffer full and drop the dispatch inputs --
        // accepting work it knew it could not converge. Reserve the slots atomically up front so two
        // concurrent solves cannot both claim the last one and leave the loser committed-then-
        // cancelled yet reported as installed. With a PCC attached, admitted tasks
        // install immediately and the backlog does not grow, so no reservation is needed.
        boolean holdsPendingSlots = installDispatcher != null && pccSelector.get().isEmpty();
        if (holdsPendingSlots && !tryReservePendingSlots(tasks.size())) {
            throw new UndispatchableBacklogException(String.format(
                    "no PCC is connected and the undispatched buffer holds %d of %d installations; "
                            + "%d more cannot be accepted until a router attaches or the backlog "
                            + "drains", undispatched.size(), maxUndispatched, tasks.size()));
        }
        // The reserve/release is kept in its own frame around the call so the solve body below holds
        // the topology read lock in exactly the shape it always has -- lock(); try {...} finally
        // {unlock();} -- with no enclosing try. Wrapping the lock acquisition in this method's
        // try/finally instead would leave a lock() lexically inside a try whose finally does not
        // unlock it, which the lock-safety analysis reads as a possibly-unreleased lock.
        try {
            return solveAndDispatch(tasks, cancellation, owner);
        } finally {
            // Once dispatch has run, the admitted schedules are in undispatched (or were installed),
            // so undispatched.size() accounts for them and the reservation is no longer needed.
            if (holdsPendingSlots) {
                releasePendingSlots(tasks.size());
            }
        }
    }

    /**
     * Solves and dispatches under the topology read lock, having already reserved any backlog slots.
     *
     * <p>Split out from {@link #handleScheduleWorkloadsRequest} so the pending-slot reservation is
     * released by a try/finally in the caller while this method keeps the lock/unlock structure the
     * lock-safety analysis expects.
     */
    private String solveAndDispatch(
            List<WorkloadTask> tasks, net.dcn.pce.crp.SolveCancellation cancellation, String owner)
            throws IOException {
        long startNanos = System.nanoTime();
        CRPEngine.PCEComputationResult result;
        // The topology is read and its capacity is committed against inside this region, so a
        // capacity change must not land in the middle of it. Dispatch stays outside: it touches
        // no topology and holding the lock across network writes would block observations for as
        // long as a router takes to answer.
        topologyLock.readLock().lock();
        try {
            result = crpEngine.solve(activeTopology, tasks, cancellation, owner);
        } catch (RuntimeException e) {
            // Latency is recorded for failed solves too; excluding them would understate the
            // tail exactly when planning is degrading.
            metrics.observeLatency((System.nanoTime() - startNanos) / 1e9);
            throw e;
        } finally {
            topologyLock.readLock().unlock();
        }

        metrics.recordSolveResult(
                result.getOfferedFlowCount(),
                result.getCommittedFlowCount(),
                result.getUnadmittedTasks().size(),
                result.getMetDeadlineCount(),
                (System.nanoTime() - startNanos) / 1e9);

        log.info(RequestContext.tag(String.format(
                "Northbound REST API: Successfully computed %d LSPs. Success Ratio: %.1f%%",
                result.getCommittedFlowCount(), result.getSuccessRatioPercent())));

        // Installation is attempted after the plan is committed, never before: a schedule that was
        // not admitted has no capacity reserved and must not reach a router.
        java.util.Map<String, CRPEngine.DispatchOutcome> delivery = new java.util.LinkedHashMap<>();
        for (String outcome : dispatchSchedules(result.getCommittedSchedules(), delivery)) {
            log.info(RequestContext.tag("Install dispatch: " + outcome));
        }
        return JSONUtils.toResultJson(result, installationStates(result, delivery));
    }


    /**
     * Records what a link's capacity turned out to be, so the next solve plans against it.
     *
     * <p>Refuses an observation that would leave existing reservations over-subscribed, and this
     * is the honest boundary of the feedback loop rather than a limitation dressed up as a policy.
     * Applying it anyway leaves the ledger holding more bandwidth than the link has, and the very
     * next solve fails its own replay validation with an LRIB capacity violation -- a 500 caused
     * by a state this controller created. Discovered exactly that way: an observation was accepted
     * cleanly and the following request threw.
     *
     * <p>Resolving it properly means deciding what happens to commitments already made: whether a
     * client promised a deadline can have it withdrawn, whether an LSP a router is carrying is
     * re-rated or torn down, and who is told. Those are answerable, and answering them by accident
     * would be worse than refusing until they are answered. Until then an operator must release
     * the affected tasks first, and the refusal says so with the numbers.
     *
     * @return the capacity previously assumed for that link
     * @throws IllegalStateException if committed reservations exceed the observed capacity
     */
    public double recordObservedCapacity(String linkId, double observedBps) {
        // Bounded rather than indefinite: this runs on an HTTP worker, and a solve may hold the
        // read lock for its whole timeout budget. Refusing with "retry" keeps the worker free and
        // matches how the planner already reports being busy.
        boolean acquired;
        try {
            acquired = topologyLock.writeLock().tryLock(
                    capacityLockWaitMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CapacityUpdateBusyException("interrupted while waiting for the planner");
        }
        if (!acquired) {
            throw new CapacityUpdateBusyException(
                    "a solve is in flight; the observation was not applied. Retry shortly.");
        }
        try {
            return recordObservedCapacityLocked(linkId, observedBps);
        } finally {
            topologyLock.writeLock().unlock();
        }
    }

    private double recordObservedCapacityLocked(String linkId, double observedBps) {
        Link existing = activeTopology.getLink(linkId);
        if (existing == null) {
            throw new IllegalArgumentException("unknown link: " + linkId);
        }

        // The peak concurrent aggregate, not the largest single reservation: overlapping
        // reservations sum where they coincide, and an observation below that sum would leave the
        // overlap over-subscribed even when it clears every reservation taken alone.
        double committedBps = crpEngine.getLRIB().getPeakReservedBps(linkId);
        if (committedBps > observedBps) {
            throw new IllegalStateException(String.format(
                    "%.0f bps is already committed on %s; observing %.0f bps would leave those "
                            + "commitments over-subscribed. Release them first.",
                    committedBps, linkId, observedBps));
        }

        double previousBps = existing.getLif().getBaseBandwidthBps();
        if (capacityStore != null) {
            // Persist before publishing the new topology. A successful API response must mean the
            // observation will survive restart; on failure neither memory nor disk changes.
            capacityStore.record(linkId, observedBps, previousBps, System.currentTimeMillis());
        }
        activeTopology = net.dcn.pce.topology.ObservedCapacity.withLinkCapacity(
                activeTopology, linkId, observedBps);
        log.info(String.format(
                "Observed capacity for %s: %.0f bps (was %.0f); future solves plan against it",
                LogSanitizer.singleLine(linkId), observedBps, previousBps));
        return previousBps;
    }

    public BaseTopology getActiveTopology() { return activeTopology; }
    public void setActiveTopology(BaseTopology activeTopology) { this.activeTopology = activeTopology; }
    /**
     * Cancels a task, releasing capacity only when nothing was sent to a PCC.
     *
     * <p>Returns the outcome rather than a boolean so the API can distinguish "released" from
     * "removal requested": a caller that reads 200 for both would believe the bandwidth was free
     * while the controller is still waiting for a router to confirm the LSP is gone.
     */
    public CRPEngine.CancellationOutcome cancelTask(String taskId) {
        CRPEngine.CancellationOutcome outcome = crpEngine.requestCancellation(taskId);
        // An undeliverable removal is not a cancellation: nothing was released and nothing was
        // asked of the network. Counting it would make the metric report work that did not happen.
        if (outcome == CRPEngine.CancellationOutcome.RELEASED
                || outcome == CRPEngine.CancellationOutcome.REMOVAL_REQUESTED) {
            metrics.recordTaskCancelled();
        }
        return outcome;
    }

    /**
     * Chooses the PCC a freshly computed schedule should be installed on, when one can be chosen.
     */
    private java.util.function.Supplier<java.util.Optional<String>> pccSelector = java.util.Optional::empty;

    /** Sends an encoded install request; returns what it established about delivery. */
    @FunctionalInterface
    public interface InstallDispatcher {
        /**
         * @param route the links the traffic traverses, in order
         * @param rateBps the committed rate
         *
         * <p>Route and rate rather than the whole solve result, so an installation planned before
         * any router connected can be dispatched later from something small enough to persist.
         */
        CRPEngine.DispatchOutcome requestInstall(
                net.dcn.pce.install.InstallationIntent intent, long srpId,
                java.util.List<net.dcn.pce.model.Link> route, double rateBps);
    }

    private InstallDispatcher installDispatcher;

    /**
     * Schedules that were committed but never sent, held so a later dispatch can encode them.
     *
     * <p>A solve that commits while no PCC is connected leaves its intents {@code PLANNED} and
     * returns "no dispatch". Nothing then re-dispatched them, so the reservations were held for a
     * deadline that could never be met: the schedule was planned, the capacity was spent, and the
     * router was never asked. Connecting the PCC afterwards did not help, because the route and
     * rate needed to encode a PCInitiate live on the solve result and nowhere else --
     * {@link net.dcn.pce.install.InstallationIntent} carries an LSP name and identifiers, not a
     * path.
     *
     * <p>Retrying these is safe in a way that retrying an {@code UNCERTAIN} operation is not.
     * Entries land here only when the transport established that nothing left the process, so
     * there is no router holding a copy and no duplicate to create. {@code UNCERTAIN} means the
     * opposite and is deliberately never retried here.
     *
     * <p>Held in memory for fast re-dispatch, and no longer only there: the sidecar persists the
     * request inputs, and on restart any that are missing are reconstructed from the durable
     * reservations that still hold their capacity (see {@code reconstructDispatchFromReservations}
     * below). A {@code PLANNED} intent therefore survives a restart with a route and rate to
     * re-dispatch, rather than stranding capacity it can no longer encode.
     */
    private final java.util.Map<String, net.dcn.pce.install.PendingDispatchStore.Request>
            undispatched = new java.util.concurrent.ConcurrentHashMap<>();

    /** Durable backing for {@link #undispatched}, when the deployment has a state path. */
    private net.dcn.pce.install.PendingDispatchStore pendingDispatchStore;

    /** Bounds the memory a long-lived controller with no PCC can accumulate. */
    static final int DEFAULT_MAX_UNDISPATCHED = 1024;
    /** The live bound; overridable in tests so the admission gate can be exercised affordably. */
    private int maxUndispatched = DEFAULT_MAX_UNDISPATCHED;

    /**
     * Slots claimed by solves in flight but not yet landed in {@link #undispatched}.
     *
     * <p>The backlog gate reads {@code undispatched.size()} before a solve and fills it after, and
     * with concurrent solves those two moments race: two could each see the last free slot, both
     * commit, and the second's dispatch inputs then get dropped -- so the reservation was cancelled
     * while the response still called it committed. Reserving the slots up front, atomically, closes
     * that: the second solve is refused before it admits anything, and the response is honest. The
     * reservation is released once the schedules are in {@code undispatched}, which then counts them.
     */
    private final Object pendingSlotLock = new Object();
    private int reservedPendingSlots = 0;

    /** Atomically reserves {@code count} backlog slots, or returns false if the bound is reached. */
    private boolean tryReservePendingSlots(int count) {
        synchronized (pendingSlotLock) {
            if (undispatched.size() + reservedPendingSlots + count > maxUndispatched) {
                return false;
            }
            reservedPendingSlots += count;
            return true;
        }
    }

    private void releasePendingSlots(int count) {
        synchronized (pendingSlotLock) {
            reservedPendingSlots = Math.max(0, reservedPendingSlots - count);
        }
    }

    /** Test hook: lower the undispatched bound so the admission gate is reachable in one request. */
    public void setMaxUndispatchedForTest(int value) {
        this.maxUndispatched = value;
    }
    private final java.util.concurrent.atomic.AtomicLong installSrpSequence =
            new java.util.concurrent.atomic.AtomicLong(1L);

    /** Wires automatic installation of computed schedules. */
    public void setInstallDispatch(
            java.util.function.Supplier<java.util.Optional<String>> pccSelector,
            InstallDispatcher dispatcher) {
        this.pccSelector = pccSelector == null ? java.util.Optional::empty : pccSelector;
        this.installDispatcher = dispatcher;
    }

    /**
     * Dispatches every committed schedule to the PCC that will carry it.
     *
     * <p>Persist, then send -- the same ordering cancellation uses, and for the same reason. The
     * intent is recorded INSTALLING before the frame goes out, so a crash in between leaves a
     * router holding an LSP the ledger knows about, which state synchronisation can resolve. The
     * reverse order leaves an LSP nothing has any record of.
     *
     * <p>The record is undone only when the transport is certain nothing left the process. A write
     * that failed part-way keeps it, because a router acting on a request the controller has
     * forgotten is the state this ordering exists to prevent.
     *
     * @return one line per schedule describing what happened, for the operator log
     */
    public java.util.List<String> dispatchCommittedSchedules(CRPEngine.PCEComputationResult result) {
        return dispatchSchedules(result.getCommittedSchedules());
    }

    /** Dispatches a set of already-committed schedules; the retry path shares this exactly. */
    private java.util.List<String> dispatchSchedules(
            java.util.List<CRPEngine.CommittedFlowSchedule> schedules) {
        return dispatchSchedules(schedules, new java.util.LinkedHashMap<>());
    }

    /**
     * @param sent filled with the delivery outcome per task, so a caller can tell a frame that
     *             reached the socket from one whose write may have half-arrived. Both leave the
     *             intent {@code INSTALLING}, which is correct — the difference is what the
     *             transport established, not what the controller intends — so the intent state
     *             alone cannot express it.
     */
    private java.util.List<String> dispatchSchedules(
            java.util.List<CRPEngine.CommittedFlowSchedule> schedules,
            java.util.Map<String, CRPEngine.DispatchOutcome> sent) {
        java.util.List<String> outcomes = new java.util.ArrayList<>();
        if (installDispatcher == null || schedules.isEmpty()) {
            return outcomes;
        }
        java.util.Optional<String> pcc = pccSelector.get();
        if (pcc.isEmpty()) {
            int remembered = rememberUndispatched(schedules);
            outcomes.add("no dispatch: no single established PCC to install on; " + remembered
                    + " schedule(s) held for dispatch when one connects");
            return outcomes;
        }

        for (CRPEngine.CommittedFlowSchedule schedule : schedules) {
            String taskId = schedule.getTask().getTaskId();
            java.util.Optional<net.dcn.pce.install.InstallationIntent> before =
                    crpEngine.getIntents().find(taskId);
            if (before.isEmpty()
                    || before.get().getState() != net.dcn.pce.install.InstallationState.PLANNED) {
                continue;
            }
            long srpId = installSrpSequence.incrementAndGet();
            java.util.Optional<net.dcn.pce.install.InstallationIntent> marked =
                    crpEngine.applyInstallationChange(
                            () -> crpEngine.getIntents().markInstalling(taskId, srpId, pcc.get()));
            if (marked.isEmpty()) {
                outcomes.add(taskId + ": not dispatched, the intent could not be recorded");
                continue;
            }

            CRPEngine.DispatchOutcome outcome;
            try {
                outcome = installDispatcher.requestInstall(
                        marked.get(), srpId, schedule.getRoute(),
                        schedule.getCommittedRateBps());
            } catch (RuntimeException e) {
                log.log(java.util.logging.Level.WARNING, "Install dispatch failed for " + taskId, e);
                outcome = CRPEngine.DispatchOutcome.UNCERTAIN;
            }

            if (outcome == CRPEngine.DispatchOutcome.NOT_ATTEMPTED) {
                sent.put(taskId, outcome);
                net.dcn.pce.install.InstallationIntent previous = before.get();
                crpEngine.applyInstallationChange(() -> crpEngine.getIntents().restore(previous));
                rememberUndispatched(java.util.List.of(schedule));
                outcomes.add(taskId + ": not dispatched, nothing left the controller; held for retry");
            } else {
                forgetUndispatched(taskId);
                sent.put(taskId, outcome);
                outcomes.add(taskId + ": PCInitiate " + outcome + " to " + pcc.get()
                        + " (SRP " + srpId + ")");
            }
        }
        return outcomes;
    }

    /**
     * The installation state of every committed schedule, read after dispatch has run.
     *
     * <p>Taken from the intent ledger rather than from the dispatch outcomes, because the ledger
     * is what the rest of the system acts on and a second account of the same thing would be a
     * second thing to keep correct. {@code PLANNED} here means nothing was sent, {@code INSTALLING}
     * that a PCInitiate is outstanding, {@code UNCERTAIN} that a write may have reached the router.
     *
     * <p>Absent entirely when no dispatch path is wired: a field reporting {@code PLANNED} for
     * every task would suggest installation was attempted and declined, when in fact this
     * controller was never asked to install anything.
     */
    private java.util.Map<String, String> installationStates(
            CRPEngine.PCEComputationResult result,
            java.util.Map<String, CRPEngine.DispatchOutcome> delivery) {
        if (installDispatcher == null) {
            return null;
        }
        java.util.Map<String, String> states = new java.util.LinkedHashMap<>();
        for (CRPEngine.CommittedFlowSchedule schedule : result.getCommittedSchedules()) {
            String taskId = schedule.getTask().getTaskId();
            String state = crpEngine.getIntents().find(taskId)
                    .map(intent -> intent.getState().name())
                    .orElse("NONE");
            CRPEngine.DispatchOutcome outcome = delivery.get(taskId);
            // INSTALLING covers both "the frame reached the socket" and "the write may have
            // half-arrived". A client deciding whether to wait or to investigate needs the
            // difference, so the delivery outcome is reported beside the state rather than
            // collapsed into it.
            states.put(taskId, outcome == null ? state : state + "/" + outcome);
        }
        return states;
    }

    /** Makes held dispatch requests survive a restart, and restores any already recorded. */
    public void withDurablePendingDispatch(net.dcn.pce.install.PendingDispatchStore store) {
        this.pendingDispatchStore = store;
        store.all().forEach((taskId, request) -> {
            if (crpEngine.getIntents().find(taskId).isEmpty()) {
                // The intent is gone, so its capacity is released and there is nothing to install.
                store.remove(taskId);
                return;
            }
            undispatched.put(taskId, request);
        });

        // Close the crash window between the reservation/intent commit and the sidecar write. The
        // route and rate a dispatch needs are the task's own reservations -- linkIds, endpoints,
        // and reserved bandwidth -- which are already durable in the WAL. So any PLANNED intent
        // still holding capacity but absent from the sidecar (because a crash landed in that gap,
        // or the sidecar was unreadable) is recovered from its reservations rather than lost: the
        // separate file is a cache, not the source of truth.
        int recovered = 0;
        for (net.dcn.pce.install.InstallationIntent intent
                : crpEngine.getIntents().holdingCapacity()) {
            String taskId = intent.getTaskId();
            if (intent.getState() != net.dcn.pce.install.InstallationState.PLANNED
                    || undispatched.containsKey(taskId)) {
                continue;
            }
            net.dcn.pce.install.PendingDispatchStore.Request request =
                    reconstructDispatchFromReservations(taskId);
            if (request == null) {
                continue;
            }
            undispatched.put(taskId, request);
            try {
                store.put(taskId, request);        // re-seed the cache so it agrees with the WAL
            } catch (java.io.UncheckedIOException ignored) {
                // The cache could not be written; recovery still holds it in memory and will retry.
            }
            recovered++;
        }

        if (!undispatched.isEmpty()) {
            log.info("Restored " + undispatched.size() + " installation(s) awaiting a PCC from "
                    + "durable state" + (recovered > 0
                    ? " (" + recovered + " reconstructed from reservations after a crash before "
                      + "the dispatch sidecar was written)" : ""));
        }
    }

    /**
     * Rebuilds a dispatch request from a task's committed link reservations: the ordered route and
     * the reserved rate. Returns null if the task holds no link reservations.
     *
     * <p>Ordering chains the reservations end to end -- the route begins at the link whose source
     * is no other link's destination -- so the reconstructed ERO matches the path that was planned
     * rather than an arbitrary permutation of its links.
     */
    private net.dcn.pce.install.PendingDispatchStore.Request reconstructDispatchFromReservations(
            String taskId) {
        java.util.List<net.dcn.pce.rib.LRIB.LinkReservation> mine =
                crpEngine.getLRIB().getAllReservations().stream()
                        .filter(reservation -> taskId.equals(reservation.getTaskId()))
                        .toList();
        if (mine.isEmpty()) {
            return null;
        }
        java.util.Map<String, net.dcn.pce.rib.LRIB.LinkReservation> bySource =
                new java.util.HashMap<>();
        java.util.Set<String> destinations = new java.util.HashSet<>();
        double rateBps = 0.0;
        for (net.dcn.pce.rib.LRIB.LinkReservation reservation : mine) {
            bySource.put(reservation.getSourceNodeId(), reservation);
            destinations.add(reservation.getDestNodeId());
            rateBps = Math.max(rateBps, reservation.getReservedBwBps());
        }
        String start = mine.stream()
                .map(net.dcn.pce.rib.LRIB.LinkReservation::getSourceNodeId)
                .filter(source -> !destinations.contains(source))
                .findFirst()
                .orElse(mine.get(0).getSourceNodeId());   // a cycle should not occur; degrade safely
        java.util.List<String> route = new java.util.ArrayList<>();
        java.util.Set<String> guard = new java.util.HashSet<>();
        String node = start;
        while (bySource.containsKey(node) && guard.add(node)) {
            net.dcn.pce.rib.LRIB.LinkReservation hop = bySource.get(node);
            route.add(hop.getLinkId());
            node = hop.getDestNodeId();
        }
        if (route.size() != mine.size()) {
            // The reservations did not form a single simple chain; fall back to their link ids in
            // stored order rather than emitting a route that drops or repeats a hop.
            route = mine.stream().map(net.dcn.pce.rib.LRIB.LinkReservation::getLinkId)
                    .distinct().toList();
        }
        return new net.dcn.pce.install.PendingDispatchStore.Request(route, rateBps);
    }

    private int rememberUndispatched(java.util.List<CRPEngine.CommittedFlowSchedule> schedules) {
        int held = 0;
        for (CRPEngine.CommittedFlowSchedule schedule : schedules) {
            String taskId = schedule.getTask().getTaskId();
            if (undispatched.size() >= maxUndispatched && !undispatched.containsKey(taskId)) {
                // Admission is gated on this bound before the solve commits, so with serialized
                // solves this is unreachable. Kept as a backstop, and it releases rather than
                // strands: holding a reservation whose dispatch inputs we refuse to keep is the
                // exact "accepted but uninstallable" state this fix removes.
                log.severe("Undispatched buffer full at " + maxUndispatched + "; releasing "
                        + LogSanitizer.singleLine(taskId) + " rather than holding capacity for "
                        + "work that cannot be dispatched");
                crpEngine.requestCancellation(taskId);
                continue;
            }
            net.dcn.pce.install.PendingDispatchStore.Request request =
                    new net.dcn.pce.install.PendingDispatchStore.Request(
                            schedule.getRoute().stream()
                                    .map(net.dcn.pce.model.Link::getLinkId).toList(),
                            schedule.getCommittedRateBps());
            if (pendingDispatchStore != null) {
                try {
                    pendingDispatchStore.put(taskId, request);
                } catch (java.io.UncheckedIOException e) {
                    // The reservation is already committed, so leaving it would hold capacity for
                    // work that can never be encoded again: accepted, paid for, impossible to
                    // install. Releasing it turns a silent leak into an honest refusal, and the
                    // caller sees the task as unadmitted rather than as planned.
                    log.log(java.util.logging.Level.SEVERE,
                            "Releasing " + taskId + ": its dispatch request could not be made "
                                    + "durable, so it could never be installed", e);
                    crpEngine.requestCancellation(taskId);
                    undispatched.remove(taskId);
                    throw e;
                }
            }
            undispatched.put(taskId, request);
            held++;
        }
        return held;
    }

    /**
     * Dispatches schedules that were planned while no PCC was available.
     *
     * <p>Called when a session finishes synchronising, which is the moment the thing that was
     * missing arrives. Only intents still {@code PLANNED} are sent: anything that has since been
     * installed, cancelled or become uncertain is left alone, and the reconciliation that runs at
     * the same moment owns those.
     *
     * @return one line per attempt, for the operator log
     */
    public java.util.List<String> dispatchPending() {
        if (installDispatcher == null || undispatched.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> outcomes = new java.util.ArrayList<>();
        java.util.Optional<String> pcc = pccSelector.get();
        if (pcc.isEmpty()) {
            return outcomes;
        }

        for (java.util.Map.Entry<String, net.dcn.pce.install.PendingDispatchStore.Request> entry
                : java.util.Map.copyOf(undispatched).entrySet()) {
            String taskId = entry.getKey();
            java.util.Optional<net.dcn.pce.install.InstallationIntent> before =
                    crpEngine.getIntents().find(taskId);
            if (before.isEmpty()) {
                // The task is gone, so its capacity is released and there is nothing to install.
                forgetUndispatched(taskId);
                continue;
            }
            if (before.get().getState() != net.dcn.pce.install.InstallationState.PLANNED) {
                continue;
            }

            java.util.List<net.dcn.pce.model.Link> route = new java.util.ArrayList<>();
            boolean resolvable = true;
            for (String linkId : entry.getValue().routeLinkIds) {
                net.dcn.pce.model.Link link = activeTopology.getLink(linkId);
                if (link == null) {
                    resolvable = false;
                    break;
                }
                route.add(link);
            }
            if (!resolvable) {
                // The topology changed under a plan made against the old one. Sending a path over
                // a link that no longer exists is worse than not sending: the reservation stays,
                // visible and cancellable, rather than becoming an LSP nobody can account for.
                forgetUndispatched(taskId);
                outcomes.add(taskId + ": not dispatched, its route no longer exists in the topology");
                continue;
            }

            long srpId = installSrpSequence.incrementAndGet();
            java.util.Optional<net.dcn.pce.install.InstallationIntent> marked =
                    crpEngine.applyInstallationChange(
                            () -> crpEngine.getIntents().markInstalling(taskId, srpId, pcc.get()));
            if (marked.isEmpty()) {
                outcomes.add(taskId + ": not dispatched, the intent could not be recorded");
                continue;
            }

            CRPEngine.DispatchOutcome outcome;
            try {
                outcome = installDispatcher.requestInstall(
                        marked.get(), srpId, route, entry.getValue().rateBps);
            } catch (RuntimeException e) {
                log.log(java.util.logging.Level.WARNING, "Install dispatch failed for " + taskId, e);
                outcome = CRPEngine.DispatchOutcome.UNCERTAIN;
            }

            if (outcome == CRPEngine.DispatchOutcome.NOT_ATTEMPTED) {
                net.dcn.pce.install.InstallationIntent previous = before.get();
                crpEngine.applyInstallationChange(() -> crpEngine.getIntents().restore(previous));
                outcomes.add(taskId + ": still not dispatched, nothing left the controller");
            } else {
                forgetUndispatched(taskId);
                outcomes.add(taskId + ": PCInitiate " + outcome + " to " + pcc.get()
                        + " (SRP " + srpId + ")");
            }
        }
        return outcomes;
    }

    private void forgetUndispatched(String taskId) {
        undispatched.remove(taskId);
        if (pendingDispatchStore != null) {
            pendingDispatchStore.remove(taskId);
        }
    }

    /** Schedules held for a PCC that has not connected yet; visible so they are not invisible. */
    public java.util.Set<String> pendingDispatchTaskIds() {
        return java.util.Set.copyOf(undispatched.keySet());
    }

    /** An intent an operator may need to resolve, with why it is stuck. */
    public record StuckIntent(String taskId, String lspName, String state, String pccSessionKey,
                              boolean holdsCapacity) { }

    /**
     * Intents the controller cannot resolve on its own.
     *
     * <p>{@code UNCERTAIN} means a write may or may not have reached a router. Retrying is unsafe
     * because a duplicate PCInitiate for an existing LSP is worse than a stranded one, and
     * releasing is unsafe because the LSP may be carrying traffic. So the controller holds the
     * capacity and waits — correctly, but invisibly, and capacity held for something nobody is
     * looking at is indistinguishable from a leak.
     */
    public java.util.List<StuckIntent> intentsNeedingOperator() {
        java.util.List<StuckIntent> stuck = new java.util.ArrayList<>();
        for (net.dcn.pce.install.InstallationIntent intent : crpEngine.getIntents().snapshot()) {
            if (intent.getState() == net.dcn.pce.install.InstallationState.UNCERTAIN) {
                stuck.add(new StuckIntent(intent.getTaskId(), intent.getLspName(),
                        intent.getState().name(), intent.getPccSessionKey().orElse(null),
                        intent.getState().holdsCapacity()));
            }
        }
        return stuck;
    }

    /** What an operator determined about an uncertain LSP by looking at the router. */
    public enum Resolution {
        /** The LSP is not on the router. Capacity is released. */
        NOT_INSTALLED
    }

    /**
     * Applies an operator's determination about an {@code UNCERTAIN} intent.
     *
     * <p>This is the deliberate, attested step the controller cannot take for itself. Releasing
     * the capacity of an LSP that does in fact exist double-books that bandwidth, so the caller is
     * asserting they checked the router — not guessing, and not hoping. It is logged as an
     * operator action for that reason.
     *
     * <p>Only the release direction is offered. Confirming an LSP *is* installed needs its PLSP-ID
     * and is what state synchronisation already does from the router's own report, which is
     * better evidence than an assertion over HTTP.
     *
     * @throws IllegalStateException if the task is not uncertain
     * @throws IllegalArgumentException if there is no such intent
     */
    public net.dcn.pce.install.InstallationIntent resolveUncertain(
            String taskId, Resolution resolution) {
        net.dcn.pce.install.InstallationIntent intent = crpEngine.getIntents().find(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no installation intent for task " + taskId));
        if (intent.getState() != net.dcn.pce.install.InstallationState.UNCERTAIN) {
            throw new IllegalStateException(String.format(
                    "task %s is %s, not UNCERTAIN; only an uncertain operation needs an operator "
                            + "to decide what the router actually has",
                    taskId, intent.getState()));
        }
        if (resolution != Resolution.NOT_INSTALLED) {
            throw new IllegalArgumentException("unsupported resolution: " + resolution);
        }
        java.util.Optional<net.dcn.pce.install.InstallationIntent> resolved =
                crpEngine.applyInstallationChange(() -> crpEngine.getIntents().markFailed(taskId));
        log.warning(String.format(
                "Operator resolved %s as NOT_INSTALLED; capacity released on their assertion that "
                        + "the LSP is absent from %s",
                LogSanitizer.singleLine(taskId),
                LogSanitizer.singleLine(intent.getPccSessionKey().orElse("its PCC"))));
        return resolved.orElseThrow(() -> new IllegalStateException(
                "the resolution could not be recorded for " + taskId));
    }

    /** Wires the southbound path the engine uses to remove installed LSPs. */
    public void setRemovalDispatcher(CRPEngine.RemovalDispatcher dispatcher) {
        crpEngine.withRemovalDispatcher(dispatcher);
    }
}
