package net.dcn.pce.install;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Installation intents and the only transitions permitted between them.
 *
 * <p>The transition table is data rather than scattered conditionals so the legal graph can be
 * read in one place and asserted against the design. An illegal transition throws: silently
 * tolerating one would let a task reach a terminal state and release capacity through a path
 * nobody reasoned about.
 *
 * <p>This class performs no I/O and knows nothing about sockets. Every failure boundary in design
 * §3 is decidable here, which is what allows them to be tested exhaustively without a network.
 */
public final class IntentLedger {

    private static final Map<InstallationState, Set<InstallationState>> LEGAL =
            new EnumMap<>(InstallationState.class);

    static {
        LEGAL.put(InstallationState.PLANNED, EnumSet.of(
                InstallationState.INSTALLING,
                // A plan can be abandoned before anything was sent; nothing is on the network.
                InstallationState.FAILED));
        LEGAL.put(InstallationState.INSTALLING, EnumSet.of(
                InstallationState.INSTALLED,
                InstallationState.FAILED,
                InstallationState.UNCERTAIN));
        LEGAL.put(InstallationState.INSTALLED, EnumSet.of(
                InstallationState.DELETING,
                // A rate change on a path that is already carrying traffic.
                InstallationState.UPDATING,
                // A session loss makes even an installed LSP's fate unknown again.
                InstallationState.UNCERTAIN));
        LEGAL.put(InstallationState.UPDATING, EnumSet.of(
                // Confirmed at the new rate, or refused and left at the old one -- both land back
                // in INSTALLED, because the LSP exists either way. Which rate applies is carried
                // by the reservations, not by the state.
                InstallationState.INSTALLED,
                // Cancelling an LSP mid-update is legitimate; the removal supersedes the change.
                InstallationState.DELETING,
                // FAILED is deliberately absent: a refused update does not remove the LSP, so
                // treating it as failure would release capacity for a path still carrying traffic.
                InstallationState.UNCERTAIN));
        LEGAL.put(InstallationState.UNCERTAIN, EnumSet.of(
                InstallationState.INSTALLED,
                InstallationState.FAILED,
                InstallationState.DELETING));
        LEGAL.put(InstallationState.DELETING, EnumSet.of(
                InstallationState.DELETED,
                InstallationState.UNCERTAIN));
        LEGAL.put(InstallationState.FAILED, EnumSet.noneOf(InstallationState.class));
        LEGAL.put(InstallationState.DELETED, EnumSet.noneOf(InstallationState.class));
    }

    private final Map<String, InstallationIntent> byTaskId = new LinkedHashMap<>();

    /** Records a newly planned task. */
    public synchronized InstallationIntent plan(String taskId) {
        return plan(taskId, null);
    }

    /** Records a newly planned task belonging to {@code owner}. */
    public synchronized InstallationIntent plan(String taskId, String owner) {
        if (byTaskId.containsKey(taskId)) {
            throw new IllegalStateException("An intent already exists for task " + taskId);
        }
        InstallationIntent intent = InstallationIntent.planned(taskId, owner);
        byTaskId.put(taskId, intent);
        return intent;
    }

    /** Reinstates an intent from durable state, bypassing transition checks. */
    public synchronized InstallationIntent restore(InstallationIntent intent) {
        byTaskId.put(intent.getTaskId(), intent);
        // Returned so a caller can use this as a durable transaction's transition -- undoing a
        // record written just before a dispatch that turned out never to have been attempted.
        return intent;
    }

    public synchronized Optional<InstallationIntent> find(String taskId) {
        return Optional.ofNullable(byTaskId.get(taskId));
    }

    public synchronized Optional<InstallationIntent> findByLspName(String lspName) {
        return byTaskId.values().stream()
                .filter(intent -> intent.getLspName().equals(lspName))
                .findFirst();
    }

    /** A snapshot for transactional rollback. */
    public synchronized java.util.List<InstallationIntent> snapshot() {
        return java.util.List.copyOf(byTaskId.values());
    }

    /**
     * Restores a snapshot wholesale, discarding anything added since.
     *
     * <p>Used when a transaction that touched intents fails to commit: an intent published before
     * a durable write that then failed would otherwise outlive the transaction that created it.
     */
    public synchronized void resetTo(java.util.List<InstallationIntent> snapshot) {
        byTaskId.clear();
        snapshot.forEach(intent -> byTaskId.put(intent.getTaskId(), intent));
    }

    public synchronized Collection<InstallationIntent> all() {
        return java.util.List.copyOf(byTaskId.values());
    }

    /** Intents whose reservations must still be held. */
    public synchronized Collection<InstallationIntent> holdingCapacity() {
        return byTaskId.values().stream().filter(InstallationIntent::holdsCapacity).toList();
    }

    /**
     * Records which PCC answered for an intent that had no recorded owner.
     *
     * <p>Not a state transition, so it bypasses the transition table: the intent's lifecycle
     * position is unchanged and only its attribution is filled in.
     */
    public synchronized InstallationIntent adoptOwner(String taskId, String pccSessionKey) {
        InstallationIntent current = require(taskId);
        if (current.getPccSessionKey().isPresent()) {
            return current;
        }
        InstallationIntent owned = current.transitionedTo(
                current.getState(), null, null, pccSessionKey);
        byTaskId.put(taskId, owned);
        return owned;
    }

    /** Records that a PCInitiate was sent, binding this operation to an SRP identifier. */
    public synchronized InstallationIntent markInstalling(
            String taskId, long srpId, String pccSessionKey) {
        if (srpId < 1 || srpId > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("srpId must be a non-zero unsigned 32-bit value");
        }
        if (pccSessionKey == null || pccSessionKey.isBlank()) {
            throw new IllegalArgumentException("a PCC session key is required");
        }
        return transition(taskId, InstallationState.INSTALLING, srpId, null, pccSessionKey);
    }

    /**
     * Records a PCC report that the LSP is operational.
     *
     * <p>When the report answers an outstanding operation its SRP identifier must match; a report
     * carrying a stale SRP belongs to an operation this controller has already given up on, and
     * accepting it would confirm an install that was never acknowledged.
     */
    public synchronized InstallationIntent markInstalled(String taskId, Long srpId, long plspId) {
        InstallationIntent current = require(taskId);
        if (current.getState() == InstallationState.INSTALLING && srpId != null
                && current.getSrpId().isPresent() && !current.getSrpId().get().equals(srpId)) {
            throw new IllegalStateException(String.format(
                    "Report for task %s carries SRP %d but operation %d is outstanding",
                    taskId, srpId, current.getSrpId().get()));
        }
        return transition(taskId, InstallationState.INSTALLED, srpId, plspId, null);
    }

    /** Records definitive evidence that the LSP does not exist. Terminal; releases capacity. */
    public synchronized InstallationIntent markFailed(String taskId) {
        return transition(taskId, InstallationState.FAILED, null, null, null);
    }

    /**
     * Records that the LSP's existence is unknown.
     *
     * <p>Capacity is deliberately retained. An acknowledgement that never arrived does not mean
     * the LSP was not installed.
     */
    public synchronized InstallationIntent markUncertain(String taskId) {
        return transition(taskId, InstallationState.UNCERTAIN, null, null, null);
    }

    /**
     * Records that a rate change has been sent for an installed LSP.
     *
     * <p>The rate being converged to is carried on the intent because confirmation arrives later,
     * on a session thread: when the PCC reports the LSP, the controller has to know which rate to
     * settle the reservations at.
     */
    public synchronized InstallationIntent markUpdating(String taskId, long srpId,
                                                        double pendingRateBps) {
        return transition(taskId, InstallationState.UPDATING, srpId, null, null, pendingRateBps);
    }

    public synchronized InstallationIntent markDeleting(String taskId, long srpId) {
        return transition(taskId, InstallationState.DELETING, srpId, null, null);
    }

    public synchronized InstallationIntent markDeleted(String taskId) {
        return transition(taskId, InstallationState.DELETED, null, null, null);
    }

    private InstallationIntent require(String taskId) {
        InstallationIntent current = byTaskId.get(taskId);
        if (current == null) {
            throw new IllegalStateException("No installation intent for task " + taskId);
        }
        return current;
    }

    private InstallationIntent transition(
            String taskId, InstallationState next, Long srpId, Long plspId, String sessionKey) {
        return transition(taskId, next, srpId, plspId, sessionKey, null);
    }

    private InstallationIntent transition(
            String taskId, InstallationState next, Long srpId, Long plspId, String sessionKey,
            Double pendingRateBps) {
        InstallationIntent current = require(taskId);
        if (!LEGAL.get(current.getState()).contains(next)) {
            throw new IllegalStateException(String.format(
                    "Illegal installation transition for task %s: %s -> %s",
                    taskId, current.getState(), next));
        }
        InstallationIntent updated = pendingRateBps == null
                ? current.transitionedTo(next, srpId, plspId, sessionKey)
                : current.transitionedTo(next, srpId, plspId, sessionKey, pendingRateBps);
        byTaskId.put(taskId, updated);
        return updated;
    }

    /** Transitions permitted from a state, for tests and diagnostics. */
    public static Set<InstallationState> legalTransitionsFrom(InstallationState state) {
        return Set.copyOf(LEGAL.get(state));
    }
}
