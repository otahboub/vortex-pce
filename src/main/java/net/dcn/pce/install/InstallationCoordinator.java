package net.dcn.pce.install;

import net.dcn.pce.pcep.ReportedLsp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Turns what a PCC reports into what the controller believes.
 *
 * <p>This is the join between the two facts the design separates: the ledger's record of booked
 * capacity, and the network's account of what is actually installed. It contains no I/O — a report
 * arrives as a decoded {@link ReportedLsp} and leaves as a state transition — so every failure
 * boundary is decidable here and testable without a socket.
 *
 * <p>Acknowledgement deadlines are held in memory only, deliberately. After a restart the
 * controller cannot know how long a request has been outstanding, and guessing would either expire
 * a live install or wait forever. Outstanding work is resolved by state synchronisation instead,
 * which is the only source that can actually answer the question.
 */
public final class InstallationCoordinator {

    /** What the coordinator did with a report, for logging and metrics. */
    public enum Outcome {
        /** A report confirmed an install this controller was waiting for. */
        CONFIRMED_INSTALLED,
        /** A report confirmed a removal this controller requested. */
        CONFIRMED_REMOVED,
        /** The LSP exists but is not yet carrying traffic; the controller keeps waiting. */
        NOT_YET_OPERATIONAL,
        /** The report names an LSP this controller has no intent for. */
        ORPHAN,
        /** The report answers an operation the controller has already abandoned. */
        STALE_OPERATION,
        /** The report told the controller nothing it did not already believe. */
        NO_CHANGE,
        /**
         * The report came from a PCC other than the one this operation was sent to.
         *
         * <p>LSP names are chosen by this controller and are therefore predictable, so matching on
         * name alone would let any peer that can reach the listener confirm or remove an operation
         * belonging to a different router.
         */
        FOREIGN_PCC
    }

    private static final Logger log =
            Logger.getLogger(InstallationCoordinator.class.getName());

    private final IntentLedger intents;
    private final Clock clock;
    private final Duration acknowledgementTimeout;

    /** Task identifier to the instant its outstanding operation stops being worth waiting for. */
    private final Map<String, Instant> acknowledgementDeadlines = new LinkedHashMap<>();

    public InstallationCoordinator(
            IntentLedger intents, Clock clock, Duration acknowledgementTimeout) {
        if (intents == null || clock == null) {
            throw new IllegalArgumentException("an intent ledger and a clock are required");
        }
        if (acknowledgementTimeout == null || acknowledgementTimeout.isNegative()
                || acknowledgementTimeout.isZero()) {
            throw new IllegalArgumentException("acknowledgement timeout must be positive");
        }
        this.intents = intents;
        this.clock = clock;
        this.acknowledgementTimeout = acknowledgementTimeout;

        // An operation that was outstanding before a restart is still outstanding after one. The
        // deadlines live in memory, so a fresh coordinator over a restored ledger previously knew
        // of no outstanding operation at all: anything left INSTALLING or DELETING by the crash
        // would wait for an acknowledgement forever, holding its capacity, with nothing left to
        // notice. Restarting the clock is the conservative choice -- it can only delay the move to
        // UNCERTAIN, and UNCERTAIN holds capacity too, so nothing is released early.
        Instant deadline = clock.instant().plus(acknowledgementTimeout);
        for (InstallationIntent intent : intents.all()) {
            if (intent.getState() == InstallationState.INSTALLING
                    || intent.getState() == InstallationState.DELETING) {
                acknowledgementDeadlines.put(intent.getTaskId(), deadline);
            }
        }
    }

    /**
     * Records that a PCInitiate was sent and starts its acknowledgement clock.
     *
     * <p>Called after the request is on the wire, not before: an operation the controller failed
     * to send is not outstanding, and arming a deadline for it would later move a task to
     * {@code UNCERTAIN} over something that never happened.
     */
    public synchronized InstallationIntent recordSent(
            String taskId, long srpId, String pccSessionKey) {
        InstallationIntent intent = intents.markInstalling(taskId, srpId, pccSessionKey);
        acknowledgementDeadlines.put(taskId, clock.instant().plus(acknowledgementTimeout));
        return intent;
    }

    /** Records that a removal was requested and starts its acknowledgement clock. */
    public synchronized InstallationIntent recordRemovalRequested(String taskId, long srpId) {
        InstallationIntent intent = intents.markDeleting(taskId, srpId);
        acknowledgementDeadlines.put(taskId, clock.instant().plus(acknowledgementTimeout));
        return intent;
    }

    /**
     * Starts the acknowledgement clock for an operation whose bytes are already on the wire.
     *
     * <p>Separate from {@link #recordRemovalRequested} because the engine owns the durable state
     * transition -- it has to release capacity in the same transaction -- while the deadline lives
     * here. If that transaction then fails the intent keeps its previous state, and the sweep only
     * acts on INSTALLING and DELETING, so a stranded deadline expires harmlessly.
     */
    public synchronized void armAcknowledgementDeadline(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be blank");
        }
        acknowledgementDeadlines.put(taskId, clock.instant().plus(acknowledgementTimeout));
    }

    /**
     * Applies one report from a PCC.
     *
     * <p>Matching is by LSP name rather than by SRP, because a PCC may report state it was never
     * asked about, and because after a restart no SRP the controller issued is meaningful any
     * more. The SRP is used only to reject a report answering an operation already abandoned.
     */
    /**
     * Applies a report and returns the intent it changed, or null when nothing changed.
     *
     * <p>Exists so a caller can wrap the transition in a durable transaction: the returned intent
     * is what the engine persists and, when terminal, whose capacity it releases.
     */
    public synchronized InstallationIntent applyAndReturn(String reportingSessionKey, ReportedLsp report) {
        Outcome outcome = apply(reportingSessionKey, report);
        if (outcome == Outcome.ORPHAN || outcome == Outcome.STALE_OPERATION
                || outcome == Outcome.NO_CHANGE || outcome == Outcome.FOREIGN_PCC) {
            return null;
        }
        return intents.findByLspName(report.lspName()).orElse(null);
    }

    public synchronized Outcome apply(String reportingSessionKey, ReportedLsp report) {
        // A report with no identifiable origin cannot be attributed, so it cannot be trusted to
        // change installation state. Previously this bypassed the owner check entirely: the
        // production listener always supplies a key, but the API itself did not fail closed, and
        // an unattributable report is exactly the one that should not be believed.
        if (reportingSessionKey == null || reportingSessionKey.isBlank()) {
            return Outcome.FOREIGN_PCC;
        }

        Optional<InstallationIntent> match = intents.findByLspName(report.lspName());
        if (match.isPresent()) {
            Optional<String> owner = match.get().getPccSessionKey();
            // An intent that names its PCC may only be answered by that PCC. Sessions are keyed on
            // speaker identity, so a reconnecting peer keeps the same key and still matches; a
            // different key genuinely means a different router is answering for this LSP.
            if (owner.isPresent() && !owner.get().equals(reportingSessionKey)) {
                return Outcome.FOREIGN_PCC;
            }
            if (owner.isEmpty()) {
                // First reporter of an ownerless intent becomes its owner. Reconciliation creates
                // these, and leaving them ownerless meant any later peer could act on the same
                // intent -- the cross-PCC hole closed for owned intents but left open here.
                intents.adoptOwner(match.get().getTaskId(), reportingSessionKey);
            }
        }
        if (match.isEmpty()) {
            // Never destructive: an LSP this controller does not know about may belong to another
            // controller or an operator, and deleting it is not this code's decision.
            return Outcome.ORPHAN;
        }

        InstallationIntent intent = match.get();
        String taskId = intent.getTaskId();

        if (report.reportsRemoval()) {
            if (intent.getState() == InstallationState.DELETING
                    || intent.getState() == InstallationState.UNCERTAIN) {
                intents.markDeleted(taskId);
                acknowledgementDeadlines.remove(taskId);
                return Outcome.CONFIRMED_REMOVED;
            }
            // A removal this controller did not ask for. Its capacity is no longer backed by
            // anything on the network, so the intent must stop claiming it is installed.
            if (intent.getState() == InstallationState.INSTALLED
                    || intent.getState() == InstallationState.INSTALLING) {
                intents.markUncertain(taskId);
                return Outcome.NOT_YET_OPERATIONAL;
            }
            return Outcome.NO_CHANGE;
        }

        if (!report.operationalState().isOperational()) {
            // The LSP exists but is not carrying traffic. Not a failure: an install in progress
            // legitimately reports DOWN or GOING-UP first, and the deadline decides how long the
            // controller is willing to wait rather than this report deciding for it.
            return Outcome.NOT_YET_OPERATIONAL;
        }

        if (intent.getState() == InstallationState.INSTALLED) {
            return Outcome.NO_CHANGE;
        }
        if (intent.getState() != InstallationState.INSTALLING
                && intent.getState() != InstallationState.UNCERTAIN) {
            return Outcome.NO_CHANGE;
        }

        try {
            intents.markInstalled(taskId, report.srp().orElse(null), report.plspId());
        } catch (IllegalStateException staleOperation) {
            // The ledger rejects a report carrying an SRP for an operation already abandoned.
            // Accepting it would confirm an install that was never acknowledged.
            return Outcome.STALE_OPERATION;
        }
        acknowledgementDeadlines.remove(taskId);
        return Outcome.CONFIRMED_INSTALLED;
    }

    /**
     * Moves operations past their acknowledgement deadline to {@code UNCERTAIN}.
     *
     * <p>Capacity is deliberately retained. A missing acknowledgement is not evidence of
     * non-installation: the LSP may be installed and carrying traffic, and releasing its bandwidth
     * would hand it to another flow while a router is still using it.
     *
     * @return the tasks moved, in the order their deadlines were armed
     */
    public synchronized List<String> expireOverdueAcknowledgements() {
        List<String> expired = new ArrayList<>();
        for (String taskId : overdueAcknowledgements()) {
            if (expireAcknowledgement(taskId) != null) {
                expired.add(taskId);
            }
        }
        return List.copyOf(expired);
    }

    /**
     * Tasks whose acknowledgement deadline has passed, without changing anything.
     *
     * <p>Separate from applying the expiry so the caller can put each transition through the
     * engine's durable transaction, the same reason {@link #planReconciliation} is separate.
     */
    public synchronized List<String> overdueAcknowledgements() {
        Instant now = clock.instant();
        List<String> overdue = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : acknowledgementDeadlines.entrySet()) {
            if (!entry.getValue().isAfter(now)) {
                overdue.add(entry.getKey());
            }
        }
        return List.copyOf(overdue);
    }

    /**
     * Moves one overdue operation to {@code UNCERTAIN}, retiring its deadline either way.
     *
     * @return the intent it changed, or null when there was nothing to change
     */
    /**
     * Applies an error a peer reported against one of our requests.
     *
     * <p>Correlated by SRP-ID, and only for a session that owns the intent — a peer must not be
     * able to fail another peer's work by quoting its request id.
     *
     * <p>An install refused is definite: the PCC is telling us the LSP was not created, so the
     * intent is {@code FAILED} and its capacity released immediately rather than after the
     * acknowledgement deadline expires into {@code UNCERTAIN}. A *removal* refused is the
     * opposite and deliberately does nothing: the LSP is probably still installed and still
     * carrying traffic, so releasing its capacity on the strength of a refusal would hand that
     * bandwidth to another flow while a router is using it. Those stay outstanding for the
     * operator, which is the same conservatism the rest of this class applies to uncertainty.
     *
     * @return the intent that changed, or empty when nothing was correlated or nothing changed
     */
    public synchronized java.util.Optional<InstallationIntent> applyPeerError(
            String reportingSessionKey, long srpId) {
        for (InstallationIntent intent : intents.snapshot()) {
            if (intent.getSrpId().filter(id -> id == srpId).isEmpty()) {
                continue;
            }
            if (intent.getPccSessionKey().filter(reportingSessionKey::equals).isEmpty()) {
                log.warning("Ignoring an error for SRP " + srpId + " from "
                        + net.dcn.pce.util.LogSanitizer.singleLine(reportingSessionKey)
                        + ", which does not own that request");
                return java.util.Optional.empty();
            }
            if (intent.getState() == InstallationState.INSTALLING) {
                return java.util.Optional.of(intents.markFailed(intent.getTaskId()));
            }
            log.info("Peer error for SRP " + srpId + " concerns " + intent.getTaskId()
                    + " in state " + intent.getState() + "; capacity is retained");
            return java.util.Optional.empty();
        }
        return java.util.Optional.empty();
    }

    public synchronized InstallationIntent expireAcknowledgement(String taskId) {
        Optional<InstallationIntent> intent = intents.find(taskId);
        if (intent.isEmpty()) {
            acknowledgementDeadlines.remove(taskId);
            return null;
        }
        InstallationState state = intent.get().getState();
        acknowledgementDeadlines.remove(taskId);
        if (state != InstallationState.INSTALLING && state != InstallationState.DELETING) {
            return null;
        }
        return intents.markUncertain(taskId);
    }

    /**
     * Reconciles every intent against a completed state synchronisation.
     *
     * <p>Delegates the decision to {@link Reconciliation}, which refuses to act on an incomplete
     * report, and applies only the transitions that are safe without operator judgement. An
     * {@code INSTALLED} LSP that has vanished is reported rather than resolved: whether to
     * reinstall or release is a policy question, and guessing would either fight an operator who
     * removed it deliberately or silently discard a commitment a client believes it holds.
     */
    public synchronized List<Reconciliation.Finding> reconcile(
            Set<String> reportedLspNames, boolean synchronisationComplete) {
        List<Reconciliation.Finding> findings =
                planReconciliation(null, reportedLspNames, synchronisationComplete);
        findings.forEach(this::applyFinding);
        return findings;
    }

    /**
     * Decides what one PCC's completed synchronisation implies, without changing anything.
     *
     * <p>Separate from applying it because each resulting transition has to go through the
     * engine's durable transaction -- a reconciliation that releases capacity in memory only would
     * be undone by a restart, leaving the ledger and the intents disagreeing about held bandwidth.
     *
     * @param pccSessionKey the reporting session, or null for every intent regardless of owner.
     *                      Scoping matters once more than one PCC is connected: "not reported"
     *                      only means "absent" for the LSPs that PCC is responsible for, and
     *                      reconciling another peer's intents against this peer's report would
     *                      release capacity for LSPs that are installed and forwarding elsewhere.
     */
    public synchronized List<Reconciliation.Finding> planReconciliation(
            String pccSessionKey, Set<String> reportedLspNames, boolean synchronisationComplete) {
        Collection<InstallationIntent> scope = pccSessionKey == null
                ? intents.all()
                : intents.all().stream()
                        .filter(intent -> pccSessionKey.equals(intent.getPccSessionKey().orElse(null)))
                        .toList();
        return Reconciliation.reconcile(scope, reportedLspNames, synchronisationComplete);
    }

    /**
     * Applies one reconciliation finding.
     *
     * @return the intent it changed, or null when the finding is one to report rather than act on
     */
    public synchronized InstallationIntent applyFinding(Reconciliation.Finding finding) {
        if (finding == null || finding.taskId() == null) {
            return null;
        }
        switch (finding.action()) {
            case CONFIRM_INSTALLED -> {
                InstallationIntent intent = intents.markInstalled(finding.taskId(), null, 0L);
                acknowledgementDeadlines.remove(finding.taskId());
                return intent;
            }
            case RELEASE -> {
                InstallationIntent intent = intents.markFailed(finding.taskId());
                acknowledgementDeadlines.remove(finding.taskId());
                return intent;
            }
            default -> {
                // AGREE, LOST, ORPHAN and IMPOSSIBLE are reported, not acted on.
                return null;
            }
        }
    }

    /** Tasks with an operation still awaiting acknowledgement. */
    public synchronized Set<String> outstandingAcknowledgements() {
        return Set.copyOf(acknowledgementDeadlines.keySet());
    }
}
