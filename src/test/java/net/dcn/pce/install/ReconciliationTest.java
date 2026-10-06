package net.dcn.pce.install;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationTest {

    private static InstallationIntent intent(String taskId, InstallationState state) {
        return InstallationIntent.restore(
                taskId, InstallationIntent.lspNameFor(taskId), state, null, null, null);
    }

    private static Reconciliation.Action actionFor(List<Reconciliation.Finding> findings, String taskId) {
        return findings.stream()
                .filter(finding -> taskId.equals(finding.taskId()))
                .findFirst().orElseThrow().action();
    }

    /**
     * The rule the design turns on: "not reported" only means "absent" once the report is
     * complete. Reconciling a partial sync would release capacity for LSPs the PCC simply had
     * not got to yet.
     */
    @Test
    void reconcilingBeforeSyncCompletesIsRefused() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> Reconciliation.reconcile(
                        List.of(intent("T1", InstallationState.UNCERTAIN)), Set.of(), false));

        assertTrue(error.getMessage().contains("synchronization"), error.getMessage());
    }

    @Test
    void anUncertainLspReportedByThePccIsConfirmed() {
        List<Reconciliation.Finding> findings = Reconciliation.reconcile(
                List.of(intent("T1", InstallationState.UNCERTAIN)),
                Set.of("vortex-T1"), true);

        assertEquals(Reconciliation.Action.CONFIRM_INSTALLED, actionFor(findings, "T1"));
    }

    @Test
    void anUncertainLspAbsentFromACompleteReportIsReleased() {
        List<Reconciliation.Finding> findings = Reconciliation.reconcile(
                List.of(intent("T1", InstallationState.UNCERTAIN)), Set.of(), true);

        assertEquals(Reconciliation.Action.RELEASE, actionFor(findings, "T1"));
    }

    @Test
    void aDeletingLspStillReportedRemainsPending() {
        List<Reconciliation.Finding> findings = Reconciliation.reconcile(
                List.of(intent("T1", InstallationState.DELETING)),
                Set.of("vortex-T1"), true);

        assertEquals(Reconciliation.Action.AGREE, actionFor(findings, "T1"));
    }

    @Test
    void aDeletingLspAbsentFromACompleteReportIsReleased() {
        List<Reconciliation.Finding> findings = Reconciliation.reconcile(
                List.of(intent("T1", InstallationState.DELETING)), Set.of(), true);

        assertEquals(Reconciliation.Action.RELEASE, actionFor(findings, "T1"));
    }

    @Test
    void anInstalledLspThatVanishedIsReportedAsLostRatherThanSilentlyReleased() {
        // Distinct from RELEASE on purpose: the controller believed this was live, so its
        // disappearance is a fact an operator needs to see (design 8.1), not a routine cleanup.
        List<Reconciliation.Finding> findings = Reconciliation.reconcile(
                List.of(intent("T1", InstallationState.INSTALLED)), Set.of(), true);

        assertEquals(Reconciliation.Action.LOST, actionFor(findings, "T1"));
    }

    @Test
    void anInstalledLspStillReportedAgrees() {
        List<Reconciliation.Finding> findings = Reconciliation.reconcile(
                List.of(intent("T1", InstallationState.INSTALLED)), Set.of("vortex-T1"), true);

        assertEquals(Reconciliation.Action.AGREE, actionFor(findings, "T1"));
    }

    @Test
    void anOwnedLspWithNoLocalIntentIsAnOrphan() {
        List<Reconciliation.Finding> findings = Reconciliation.reconcile(
                List.of(), Set.of("vortex-STRAY"), true);

        assertEquals(1, findings.size());
        assertEquals(Reconciliation.Action.ORPHAN, findings.get(0).action());
        assertEquals("vortex-STRAY", findings.get(0).lspName());
    }

    @Test
    void foreignLspsAreLeftAlone() {
        // Another controller's or an operator's LSP is not ours to reconcile or delete.
        List<Reconciliation.Finding> findings = Reconciliation.reconcile(
                List.of(), Set.of("someone-elses-lsp"), true);

        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    void aPlannedLspReportedByThePccIsImpossibleAndFlagged() {
        // Nothing was sent, so the PCC cannot have it. Reaching this means durable state and
        // reality disagree in a way no legal transition produces.
        List<Reconciliation.Finding> findings = Reconciliation.reconcile(
                List.of(intent("T1", InstallationState.PLANNED)), Set.of("vortex-T1"), true);

        assertEquals(Reconciliation.Action.IMPOSSIBLE, actionFor(findings, "T1"));
    }

    @Test
    void aTerminatedIntentStillReportedIsAnOrphan() {
        List<Reconciliation.Finding> findings = Reconciliation.reconcile(
                List.of(intent("T1", InstallationState.DELETED)), Set.of("vortex-T1"), true);

        assertEquals(Reconciliation.Action.ORPHAN, actionFor(findings, "T1"));
    }

    @Test
    void aMixedEstateIsReconciledPerTask() {
        List<Reconciliation.Finding> findings = Reconciliation.reconcile(
                List.of(
                        intent("KEPT", InstallationState.INSTALLED),
                        intent("GONE", InstallationState.INSTALLED),
                        intent("PENDING", InstallationState.UNCERTAIN),
                        intent("DEAD", InstallationState.UNCERTAIN)),
                Set.of("vortex-KEPT", "vortex-PENDING", "vortex-STRAY"), true);

        assertEquals(Reconciliation.Action.AGREE, actionFor(findings, "KEPT"));
        assertEquals(Reconciliation.Action.LOST, actionFor(findings, "GONE"));
        assertEquals(Reconciliation.Action.CONFIRM_INSTALLED, actionFor(findings, "PENDING"));
        assertEquals(Reconciliation.Action.RELEASE, actionFor(findings, "DEAD"));
        assertTrue(findings.stream().anyMatch(f -> f.action() == Reconciliation.Action.ORPHAN
                && "vortex-STRAY".equals(f.lspName())));
    }
}
