package net.dcn.pce.install;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares controller intent against what a PCC reports, after state synchronization.
 *
 * <p>RFC 8231 state synchronization ends with a sync-done marker. Before that marker arrives the
 * report is incomplete, so the absence of an LSP proves nothing. This class therefore refuses to
 * produce an outcome until synchronization has completed: acting on a partial report is the same
 * error as releasing capacity on an acknowledgement timeout.
 */
public final class Reconciliation {

    /** What the controller should do about one task after comparing intent with reality. */
    public enum Action {
        /** Intent and report agree. */
        AGREE,
        /** Reported while the controller was unsure. Adopt the report. */
        CONFIRM_INSTALLED,
        /** Not reported after a complete sync. Definitively absent; release capacity. */
        RELEASE,
        /** Was believed installed and is now absent. Needs an operator policy. */
        LOST,
        /** Reported, owned by this controller, but unknown locally. */
        ORPHAN,
        /** Reported while the controller had sent nothing. Should be impossible. */
        IMPOSSIBLE
    }

    /** One reconciliation decision. */
    public record Finding(String taskId, String lspName, Action action, String detail) {}

    private Reconciliation() {
    }

    /**
     * Reconciles intents against a completed report.
     *
     * @param intents        every intent the controller holds
     * @param reportedNames  LSP names the PCC reported
     * @param syncComplete   whether the sync-done marker was received
     * @throws IllegalStateException if synchronization has not completed
     */
    public static List<Finding> reconcile(
            Collection<InstallationIntent> intents,
            Set<String> reportedNames,
            boolean syncComplete) {
        if (!syncComplete) {
            // The whole correctness of "not reported means absent" rests on the report being
            // complete. Refusing here is what stops a partial sync from releasing live capacity.
            throw new IllegalStateException(
                    "Refusing to reconcile before state synchronization completes: an incomplete "
                            + "report cannot distinguish an absent LSP from an unreported one");
        }

        List<Finding> findings = new ArrayList<>();
        Set<String> unmatched = new LinkedHashSet<>(reportedNames);

        for (InstallationIntent intent : intents) {
            boolean reported = unmatched.remove(intent.getLspName());
            findings.add(new Finding(
                    intent.getTaskId(), intent.getLspName(),
                    actionFor(intent.getState(), reported),
                    describe(intent.getState(), reported)));
        }

        for (String name : unmatched) {
            if (InstallationIntent.isOwnedName(name)) {
                findings.add(new Finding(null, name, Action.ORPHAN,
                        "reported and named as ours, but no local intent exists"));
            }
            // Foreign names belong to another controller or an operator. Not ours to judge.
        }
        return findings;
    }

    private static Action actionFor(InstallationState state, boolean reported) {
        return switch (state) {
            case INSTALLED -> reported ? Action.AGREE : Action.LOST;
            case INSTALLING, UNCERTAIN ->
                    reported ? Action.CONFIRM_INSTALLED : Action.RELEASE;
            // A deletion still reported at the end of synchronisation has not been confirmed.
            // Keep waiting rather than trying to revive it as INSTALLED: DELETING -> INSTALLED is
            // deliberately not a legal transition, and a request may still be in flight. If the
            // acknowledgement deadline expires, the intent becomes UNCERTAIN and can be retried.
            case DELETING -> reported ? Action.AGREE : Action.RELEASE;
            // An LSP being re-rated already exists, so being reported is agreement rather than
            // confirmation of the update -- a report at end-of-synchronisation says the path is
            // there, not which rate the router settled on. Not reported means it is gone, which
            // is the same loss as for INSTALLED and is reported rather than acted on: releasing
            // capacity for a path that may still be forwarding is the failure this whole ledger
            // exists to avoid.
            case UPDATING -> reported ? Action.AGREE : Action.LOST;
            case PLANNED -> reported ? Action.IMPOSSIBLE : Action.AGREE;
            case FAILED, DELETED -> reported ? Action.ORPHAN : Action.AGREE;
        };
    }

    private static String describe(InstallationState state, boolean reported) {
        return String.format("intent %s, %s by the PCC", state, reported ? "reported" : "not reported");
    }
}
