package net.dcn.pce.install;

import net.dcn.pce.pcep.ReportedLsp;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A report may only answer for the PCC the operation was sent to.
 *
 * <p>LSP names are derived deterministically from the task identifier, so they are predictable to
 * anyone who can reach the listener. Matching on name alone let any connected peer confirm or
 * remove an operation belonging to a different router — and the southbound transport carries no
 * authentication, so "any connected peer" is a low bar.
 */
class ReportOriginCorrelationTest {

    private static final String OWNER = "speaker:pcc-alpha";
    private static final String STRANGER = "speaker:pcc-beta";

    private static ReportedLsp report(String lspName, Long srpId, boolean up, boolean removed) {
        return new ReportedLsp(srpId, 42L, lspName,
                up ? ReportedLsp.OperationalState.UP : ReportedLsp.OperationalState.DOWN,
                removed, false, true, true);
    }

    private record Fixture(IntentLedger ledger, InstallationCoordinator coordinator) {}

    private static Fixture sentTo(String sessionKey) {
        IntentLedger ledger = new IntentLedger();
        InstallationCoordinator coordinator =
                new InstallationCoordinator(ledger, Clock.systemUTC(), Duration.ofSeconds(30));
        ledger.plan("T1");
        coordinator.recordSent("T1", 7L, sessionKey);
        return new Fixture(ledger, coordinator);
    }

    @Test
    void aReportFromAnotherPccCannotConfirmTheInstall() {
        Fixture f = sentTo(OWNER);

        assertEquals(InstallationCoordinator.Outcome.FOREIGN_PCC,
                f.coordinator().apply(STRANGER, report("vortex-T1", 7L, true, false)));

        assertEquals(InstallationState.INSTALLING, f.ledger().find("T1").orElseThrow().getState(),
                "a stranger's report must leave the operation outstanding");
    }

    @Test
    void aReportFromAnotherPccCannotRemoveTheLsp() {
        Fixture f = sentTo(OWNER);
        f.coordinator().apply(OWNER, report("vortex-T1", 7L, true, false));
        assertEquals(InstallationState.INSTALLED, f.ledger().find("T1").orElseThrow().getState());

        assertEquals(InstallationCoordinator.Outcome.FOREIGN_PCC,
                f.coordinator().apply(STRANGER, report("vortex-T1", null, false, true)));

        InstallationIntent intent = f.ledger().find("T1").orElseThrow();
        assertEquals(InstallationState.INSTALLED, intent.getState(),
                "a stranger must not be able to retire another router's LSP");
        assertTrue(intent.holdsCapacity());
    }

    @Test
    void theOwningPccIsStillAccepted() {
        Fixture f = sentTo(OWNER);

        assertEquals(InstallationCoordinator.Outcome.CONFIRMED_INSTALLED,
                f.coordinator().apply(OWNER, report("vortex-T1", 7L, true, false)));
    }

    @Test
    void aReconnectingPeerKeepsTheSameIdentityAndIsStillAccepted() {
        // Sessions are keyed on speaker identity, so a PCC that reconnects from a new source port
        // resolves to the same key. If that were not so, every reconnect would look foreign.
        Fixture f = sentTo(OWNER);

        assertEquals(InstallationCoordinator.Outcome.CONFIRMED_INSTALLED,
                f.coordinator().apply(OWNER, report("vortex-T1", 7L, true, false)));
    }

    private static InstallationCoordinator ownerlessIntent(IntentLedger ledger) {
        InstallationCoordinator coordinator =
                new InstallationCoordinator(ledger, Clock.systemUTC(), Duration.ofSeconds(30));
        ledger.plan("T1");
        ledger.restore(InstallationIntent.restore(
                "T1", "vortex-T1", InstallationState.UNCERTAIN, null, null, null));
        return coordinator;
    }

    @Test
    void anIntentWithNoRecordedPccAdoptsItsFirstReporter() {
        // Reached by reconciliation, which promotes intents without having sent anything itself.
        // The previous version of this test asserted only the outcome, so it passed while the
        // reporter was never recorded -- the name claimed adoption that did not happen.
        IntentLedger ledger = new IntentLedger();
        InstallationCoordinator coordinator = ownerlessIntent(ledger);

        assertEquals(InstallationCoordinator.Outcome.CONFIRMED_INSTALLED,
                coordinator.apply(OWNER, report("vortex-T1", null, true, false)));

        assertEquals(OWNER, ledger.find("T1").orElseThrow().getPccSessionKey().orElse(null),
                "the accepted reporter must be recorded as the owner");
    }

    @Test
    void onceAdoptedAnotherPccCannotActOnTheSameIntent() {
        IntentLedger ledger = new IntentLedger();
        InstallationCoordinator coordinator = ownerlessIntent(ledger);
        coordinator.apply(OWNER, report("vortex-T1", null, true, false));

        // Without adoption this intent stayed ownerless and any later peer could act on it.
        assertEquals(InstallationCoordinator.Outcome.FOREIGN_PCC,
                coordinator.apply(STRANGER, report("vortex-T1", null, false, true)));
        assertEquals(InstallationState.INSTALLED, ledger.find("T1").orElseThrow().getState());
    }

    @Test
    void anUnattributableReportIsRefused() {
        // The production listener always supplies a key, but the API itself must fail closed: a
        // report whose origin cannot be established is exactly the one not to believe.
        Fixture f = sentTo(OWNER);

        for (String noOrigin : new String[]{null, "", "   "}) {
            assertEquals(InstallationCoordinator.Outcome.FOREIGN_PCC,
                    f.coordinator().apply(noOrigin, report("vortex-T1", 7L, true, false)));
        }
        assertEquals(InstallationState.INSTALLING, f.ledger().find("T1").orElseThrow().getState());
    }

    @Test
    void anUnknownLspIsStillAnOrphanRatherThanForeign() {
        Fixture f = sentTo(OWNER);

        assertEquals(InstallationCoordinator.Outcome.ORPHAN,
                f.coordinator().apply(STRANGER, report("vortex-SOMEONE-ELSE", 1L, true, false)));
    }
}
