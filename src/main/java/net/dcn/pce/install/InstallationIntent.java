package net.dcn.pce.install;

import java.util.Objects;
import java.util.Optional;

/**
 * What the controller intends for one task's LSP, and what it currently believes.
 *
 * <p>Immutable: a transition produces a new intent, so a rejected transition cannot leave a
 * half-mutated record behind.
 *
 * <p>Three identifiers with different lifetimes:
 * <ul>
 *   <li>{@code taskId} — the caller's, already unique-enforced by the planner.</li>
 *   <li>{@code lspName} — the network object's durable identity. Survives controller restart and
 *       session loss, and is what reconciliation matches on.</li>
 *   <li>{@code srpId} — one operation. Meaningless after a restart, because the counter resets
 *       and the PCC has moved on.</li>
 *   <li>{@code plspId} — assigned by the PCC, learned from its report.</li>
 * </ul>
 */
public final class InstallationIntent {

    /** Prefix marking an LSP as belonging to this controller, for orphan detection. */
    public static final String LSP_NAME_PREFIX = "vortex-";

    private final String taskId;
    private final String lspName;
    private final InstallationState state;
    private final Long srpId;
    private final Long plspId;
    private final String pccSessionKey;
    /**
     * The rate an outstanding update is converging to, when one is outstanding.
     *
     * <p>Needed because confirmation arrives later and on another thread: when the PCC reports the
     * LSP, the controller has to know which rate to settle the reservations at. Without it a
     * confirmed decrease could not be applied, because nothing would record what the new rate was
     * meant to be.
     */
    private final Double pendingRateBps;

    /**
     * The tenant this work belongs to, or null when the deployment has no tenants.
     *
     * <p>Carried on the intent so it commits in the same durable transaction as the reservation
     * it authorises access to. It previously lived in a sidecar written after the solve returned,
     * which left a window: a crash in between produced a live task with no recorded owner.
     */
    private final String owner;

    private InstallationIntent(String taskId, String lspName, InstallationState state,
                               Long srpId, Long plspId, String pccSessionKey, String owner,
                               Double pendingRateBps) {
        this.taskId = taskId;
        this.lspName = lspName;
        this.state = state;
        this.srpId = srpId;
        this.plspId = plspId;
        this.pccSessionKey = pccSessionKey;
        this.owner = owner;
        this.pendingRateBps = pendingRateBps;
    }

    /** A freshly planned intent whose reservations are committed but which has not been sent. */
    public static InstallationIntent planned(String taskId) {
        return planned(taskId, null);
    }

    /** A freshly planned intent belonging to {@code owner}. */
    public static InstallationIntent planned(String taskId, String owner) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        return new InstallationIntent(taskId, lspNameFor(taskId), InstallationState.PLANNED,
                null, null, null, owner, null);
    }

    /**
     * Deterministic mapping from task to network identity.
     *
     * <p>Must be derivable without any stored state: after a restart the controller has to
     * recompute the name to match what a PCC reports. Changing this scheme breaks reconciliation
     * of in-flight LSPs and is a versioned change.
     */
    public static String lspNameFor(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        return LSP_NAME_PREFIX + taskId;
    }

    /** True when a reported LSP name looks like one this controller creates. */
    public static boolean isOwnedName(String lspName) {
        return lspName != null && lspName.startsWith(LSP_NAME_PREFIX);
    }

    /** Restores an intent from durable state without re-running transition checks. */
    public static InstallationIntent restore(String taskId, String lspName, InstallationState state,
                                             Long srpId, Long plspId, String pccSessionKey) {
        return restore(taskId, lspName, state, srpId, plspId, pccSessionKey, null);
    }

    /** Restores an intent from durable state, including which tenant it belongs to. */
    public static InstallationIntent restore(String taskId, String lspName, InstallationState state,
                                             Long srpId, Long plspId, String pccSessionKey,
                                             String owner) {
        if (taskId == null || taskId.isBlank() || lspName == null || lspName.isBlank()
                || state == null) {
            throw new IllegalArgumentException("taskId, lspName and state are required");
        }
        return restore(taskId, lspName, state, srpId, plspId, pccSessionKey, owner, null);
    }

    /** Restores an intent, including any rate an outstanding update was converging to. */
    public static InstallationIntent restore(String taskId, String lspName, InstallationState state,
                                             Long srpId, Long plspId, String pccSessionKey,
                                             String owner, Double pendingRateBps) {
        if (taskId == null || taskId.isBlank() || lspName == null || lspName.isBlank()
                || state == null) {
            throw new IllegalArgumentException("taskId, lspName and state are required");
        }
        return new InstallationIntent(taskId, lspName, state, srpId, plspId, pccSessionKey, owner,
                pendingRateBps);
    }

    InstallationIntent transitionedTo(
            InstallationState next, Long srpId, Long plspId, String pccSessionKey) {
        // The owner is carried across every transition. A task does not change hands because it
        // moved from PLANNED to INSTALLING, and losing it here would reintroduce the unowned-task
        // window this field exists to close.
        return transitionedTo(next, srpId, plspId, pccSessionKey,
                // Carried only while the update is outstanding. Once the LSP settles there is no
                // rate being converged to, and a stale value would misdirect the next confirmation.
                next == InstallationState.UPDATING ? this.pendingRateBps : null);
    }

    InstallationIntent transitionedTo(
            InstallationState next, Long srpId, Long plspId, String pccSessionKey,
            Double pendingRateBps) {
        return new InstallationIntent(taskId, lspName, next,
                srpId != null ? srpId : this.srpId,
                plspId != null ? plspId : this.plspId,
                pccSessionKey != null ? pccSessionKey : this.pccSessionKey,
                owner,
                pendingRateBps);
    }

    public String getTaskId() { return taskId; }
    public String getLspName() { return lspName; }
    public InstallationState getState() { return state; }
    public Optional<Long> getSrpId() { return Optional.ofNullable(srpId); }
    public Optional<Long> getPlspId() { return Optional.ofNullable(plspId); }
    public Optional<String> getPccSessionKey() { return Optional.ofNullable(pccSessionKey); }
    /** The rate an outstanding update is converging to, when one is outstanding. */
    public Optional<Double> getPendingRateBps() { return Optional.ofNullable(pendingRateBps); }
    /** The tenant this task belongs to, empty when unowned. */
    public Optional<String> getOwner() { return Optional.ofNullable(owner); }

    /** True while this intent's reservations must remain committed. */
    public boolean holdsCapacity() {
        return state.holdsCapacity();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InstallationIntent that)) {
            return false;
        }
        return taskId.equals(that.taskId) && lspName.equals(that.lspName) && state == that.state
                && Objects.equals(srpId, that.srpId) && Objects.equals(plspId, that.plspId)
                && Objects.equals(pccSessionKey, that.pccSessionKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, lspName, state, srpId, plspId, pccSessionKey);
    }

    @Override
    public String toString() {
        return String.format("InstallationIntent[%s %s state=%s srp=%s plsp=%s session=%s]",
                taskId, lspName, state, srpId, plspId, pccSessionKey);
    }
}
