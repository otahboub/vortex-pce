package net.dcn.pce.install;

import net.dcn.pce.pcep.ReportedLsp;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallationCoordinatorTest {

    @Test
    void aCoordinatorBuiltOverARestoredLedgerReArmsOutstandingDeadlines() {
        // Deadlines live in memory. After a restart a fresh coordinator knew of no outstanding
        // operation at all, so anything the crash left INSTALLING or DELETING would have waited
        // for an acknowledgement forever, holding its capacity, with nothing left to notice it.
        IntentLedger ledger = new IntentLedger();
        ledger.plan("T1");
        ledger.restore(InstallationIntent.restore(
                "T1", "vortex-T1", InstallationState.INSTALLING, 7L, null, "speaker:pcc-alpha"));

        TestClock clock = new TestClock();
        InstallationCoordinator restarted =
                new InstallationCoordinator(ledger, clock, Duration.ofSeconds(30));

        assertTrue(restarted.overdueAcknowledgements().isEmpty(),
                "the clock restarts rather than expiring immediately");

        clock.advance(Duration.ofSeconds(31));

        assertEquals(java.util.List.of("T1"), restarted.overdueAcknowledgements());
        assertEquals(java.util.List.of("T1"), restarted.expireOverdueAcknowledgements());
        assertEquals(InstallationState.UNCERTAIN, ledger.find("T1").orElseThrow().getState());
    }

    @Test
    void aRestoredIntentThatIsNotOutstandingGetsNoDeadline() {
        IntentLedger ledger = new IntentLedger();
        ledger.plan("T1");
        ledger.restore(InstallationIntent.restore(
                "T1", "vortex-T1", InstallationState.INSTALLED, 7L, 42L, "speaker:pcc-alpha"));

        TestClock clock = new TestClock();
        InstallationCoordinator restarted =
                new InstallationCoordinator(ledger, clock, Duration.ofSeconds(30));
        clock.advance(Duration.ofHours(1));

        assertTrue(restarted.overdueAcknowledgements().isEmpty(),
                "an installed LSP is not waiting for anything");
    }

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-08-18T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static ReportedLsp report(
            String name, Long srpId, ReportedLsp.OperationalState state, boolean removed) {
        return new ReportedLsp(srpId, 42L, name, state, removed, false, true, true);
    }

    private record Fixture(IntentLedger ledger, InstallationCoordinator coordinator, TestClock clock) {}

    private static Fixture withSentTask(String taskId) {
        IntentLedger ledger = new IntentLedger();
        TestClock clock = new TestClock();
        InstallationCoordinator coordinator =
                new InstallationCoordinator(ledger, clock, Duration.ofSeconds(30));
        ledger.plan(taskId);
        coordinator.recordSent(taskId, 7L, "speaker:pcc-alpha");
        return new Fixture(ledger, coordinator, clock);
    }

    @Test
    void anOperationalReportConfirmsTheInstall() {
        Fixture f = withSentTask("T1");

        assertEquals(InstallationCoordinator.Outcome.CONFIRMED_INSTALLED,
                f.coordinator().apply("speaker:pcc-alpha", report("vortex-T1", 7L, ReportedLsp.OperationalState.UP, false)));

        assertEquals(InstallationState.INSTALLED, f.ledger().find("T1").orElseThrow().getState());
        assertTrue(f.coordinator().outstandingAcknowledgements().isEmpty(),
                "a confirmed install is no longer awaiting acknowledgement");
    }

    @Test
    void aReportThatTheLspIsNotYetUpDoesNotFailTheInstall() {
        Fixture f = withSentTask("T1");

        // An install in progress legitimately reports DOWN or GOING-UP first. Treating that as
        // failure would abandon an LSP that is about to come up.
        for (ReportedLsp.OperationalState pending : List.of(
                ReportedLsp.OperationalState.DOWN, ReportedLsp.OperationalState.GOING_UP)) {
            assertEquals(InstallationCoordinator.Outcome.NOT_YET_OPERATIONAL,
                    f.coordinator().apply("speaker:pcc-alpha", report("vortex-T1", 7L, pending, false)));
            assertEquals(InstallationState.INSTALLING,
                    f.ledger().find("T1").orElseThrow().getState());
        }
        assertTrue(f.coordinator().outstandingAcknowledgements().contains("T1"),
                "the controller should still be waiting");
    }

    /**
     * The invariant the whole design exists to protect. A missing acknowledgement is not evidence
     * of non-installation: the LSP may be carrying traffic, and releasing its capacity would hand
     * that bandwidth to another flow while a router is still using it.
     */
    @Test
    void anExpiredAcknowledgementBecomesUncertainAndKeepsHoldingCapacity() {
        Fixture f = withSentTask("T1");

        f.clock().advance(Duration.ofSeconds(31));
        assertEquals(List.of("T1"), f.coordinator().expireOverdueAcknowledgements());

        InstallationIntent intent = f.ledger().find("T1").orElseThrow();
        assertEquals(InstallationState.UNCERTAIN, intent.getState());
        assertTrue(intent.holdsCapacity(), "an unacknowledged install may still be live");
    }

    @Test
    void anAcknowledgementInsideTheDeadlineDoesNotExpire() {
        Fixture f = withSentTask("T1");

        f.clock().advance(Duration.ofSeconds(29));

        assertTrue(f.coordinator().expireOverdueAcknowledgements().isEmpty());
        assertEquals(InstallationState.INSTALLING, f.ledger().find("T1").orElseThrow().getState());
    }

    @Test
    void aLateReportStillRescuesAnUncertainInstall() {
        Fixture f = withSentTask("T1");
        f.clock().advance(Duration.ofSeconds(31));
        f.coordinator().expireOverdueAcknowledgements();

        assertEquals(InstallationCoordinator.Outcome.CONFIRMED_INSTALLED,
                f.coordinator().apply("speaker:pcc-alpha", report("vortex-T1", null, ReportedLsp.OperationalState.ACTIVE, false)));
        assertEquals(InstallationState.INSTALLED, f.ledger().find("T1").orElseThrow().getState());
    }

    @Test
    void aReportForAnUnknownLspIsAnOrphanAndChangesNothing() {
        Fixture f = withSentTask("T1");

        assertEquals(InstallationCoordinator.Outcome.ORPHAN,
                f.coordinator().apply("speaker:pcc-alpha", report("vortex-SOMEONE-ELSE", 1L, ReportedLsp.OperationalState.UP, false)));

        assertEquals(InstallationState.INSTALLING, f.ledger().find("T1").orElseThrow().getState());
    }

    @Test
    void aReportAnsweringAnAbandonedOperationIsRejected() {
        Fixture f = withSentTask("T1");

        // Operation 7 is outstanding; a report for operation 99 answers something else.
        assertEquals(InstallationCoordinator.Outcome.STALE_OPERATION,
                f.coordinator().apply("speaker:pcc-alpha", report("vortex-T1", 99L, ReportedLsp.OperationalState.UP, false)));

        assertEquals(InstallationState.INSTALLING, f.ledger().find("T1").orElseThrow().getState());
    }

    @Test
    void aRequestedRemovalIsConfirmed() {
        Fixture f = withSentTask("T1");
        f.coordinator().apply("speaker:pcc-alpha", report("vortex-T1", 7L, ReportedLsp.OperationalState.UP, false));
        f.coordinator().recordRemovalRequested("T1", 8L);

        assertEquals(InstallationCoordinator.Outcome.CONFIRMED_REMOVED,
                f.coordinator().apply("speaker:pcc-alpha", report("vortex-T1", 8L, ReportedLsp.OperationalState.DOWN, true)));

        InstallationIntent intent = f.ledger().find("T1").orElseThrow();
        assertEquals(InstallationState.DELETED, intent.getState());
        assertFalse(intent.holdsCapacity(), "a confirmed removal releases its capacity");
    }

    @Test
    void anUnrequestedRemovalStopsClaimingTheLspIsInstalled() {
        Fixture f = withSentTask("T1");
        f.coordinator().apply("speaker:pcc-alpha", report("vortex-T1", 7L, ReportedLsp.OperationalState.UP, false));

        // Someone removed it without asking. The capacity is no longer backed by anything on the
        // network, but the controller cannot assume it is safe to release either.
        f.coordinator().apply("speaker:pcc-alpha", report("vortex-T1", null, ReportedLsp.OperationalState.DOWN, true));

        InstallationIntent intent = f.ledger().find("T1").orElseThrow();
        assertEquals(InstallationState.UNCERTAIN, intent.getState());
        assertTrue(intent.holdsCapacity());
    }

    @Test
    void reconciliationConfirmsWhatIsReportedAndReleasesWhatIsNot() {
        IntentLedger ledger = new IntentLedger();
        TestClock clock = new TestClock();
        InstallationCoordinator coordinator =
                new InstallationCoordinator(ledger, clock, Duration.ofSeconds(30));
        for (String taskId : List.of("SEEN", "GONE")) {
            ledger.plan(taskId);
            coordinator.recordSent(taskId, taskId.equals("SEEN") ? 1L : 2L, "speaker:pcc-alpha");
            ledger.markUncertain(taskId);
        }

        coordinator.reconcile(Set.of("vortex-SEEN"), true);

        assertEquals(InstallationState.INSTALLED, ledger.find("SEEN").orElseThrow().getState());
        assertEquals(InstallationState.FAILED, ledger.find("GONE").orElseThrow().getState());
        assertFalse(ledger.find("GONE").orElseThrow().holdsCapacity(),
                "an LSP absent from a complete report is definitively gone");
        assertTrue(coordinator.outstandingAcknowledgements().isEmpty());
    }

    @Test
    void aRestoredDeletionStillReportedStaysPendingUntilItsDeadline() {
        IntentLedger ledger = new IntentLedger();
        ledger.restore(InstallationIntent.restore(
                "T1", "vortex-T1", InstallationState.DELETING,
                1_000_001L, 42L, "speaker:pcc-alpha"));
        TestClock clock = new TestClock();
        InstallationCoordinator coordinator =
                new InstallationCoordinator(ledger, clock, Duration.ofSeconds(30));

        List<Reconciliation.Finding> findings = coordinator.planReconciliation(
                "speaker:pcc-alpha", Set.of("vortex-T1"), true);
        findings.forEach(coordinator::applyFinding);

        assertEquals(Reconciliation.Action.AGREE, findings.get(0).action());
        assertEquals(InstallationState.DELETING, ledger.find("T1").orElseThrow().getState());
        assertTrue(coordinator.outstandingAcknowledgements().contains("T1"));

        clock.advance(Duration.ofSeconds(31));
        assertEquals(List.of("T1"), coordinator.expireOverdueAcknowledgements());
        assertEquals(InstallationState.UNCERTAIN, ledger.find("T1").orElseThrow().getState());
        assertTrue(ledger.find("T1").orElseThrow().holdsCapacity());
    }

    @Test
    void aVanishedInstalledLspIsReportedRatherThanResolved() {
        IntentLedger ledger = new IntentLedger();
        InstallationCoordinator coordinator =
                new InstallationCoordinator(ledger, new TestClock(), Duration.ofSeconds(30));
        ledger.plan("T1");
        coordinator.recordSent("T1", 1L, "speaker:pcc-alpha");
        coordinator.apply("speaker:pcc-alpha", report("vortex-T1", 1L, ReportedLsp.OperationalState.UP, false));

        List<Reconciliation.Finding> findings = coordinator.reconcile(Set.of(), true);

        // Whether to reinstall or release is an operator policy decision; guessing would either
        // fight a deliberate removal or silently discard a commitment a client believes it holds.
        assertTrue(findings.stream().anyMatch(f -> f.action() == Reconciliation.Action.LOST));
        assertEquals(InstallationState.INSTALLED, ledger.find("T1").orElseThrow().getState());
    }

    @Test
    void reconciliationIsRefusedBeforeSynchronisationCompletes() {
        Fixture f = withSentTask("T1");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> f.coordinator().reconcile(Set.of(), false));
        assertEquals(InstallationState.INSTALLING, f.ledger().find("T1").orElseThrow().getState());
    }

    @Test
    void aPositiveAcknowledgementTimeoutIsRequired() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new InstallationCoordinator(new IntentLedger(), Clock.systemUTC(), invalid));
        }
    }
}
