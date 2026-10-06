package net.dcn.pce.install;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentLedgerTest {

    private static IntentLedger ledgerWith(String taskId, InstallationState state) {
        IntentLedger ledger = new IntentLedger();
        ledger.plan(taskId);
        switch (state) {
            case PLANNED -> { }
            case INSTALLING -> ledger.markInstalling(taskId, 1L, "speaker:pcc-alpha");
            case INSTALLED -> {
                ledger.markInstalling(taskId, 1L, "speaker:pcc-alpha");
                ledger.markInstalled(taskId, 1L, 42L);
            }
            case UNCERTAIN -> {
                ledger.markInstalling(taskId, 1L, "speaker:pcc-alpha");
                ledger.markUncertain(taskId);
            }
            case UPDATING -> {
                ledger.markInstalling(taskId, 1L, "speaker:pcc-alpha");
                ledger.markInstalled(taskId, 1L, 42L);
                ledger.markUpdating(taskId, 3L, 5_000_000d);
            }
            case DELETING -> {
                ledger.markInstalling(taskId, 1L, "speaker:pcc-alpha");
                ledger.markInstalled(taskId, 1L, 42L);
                ledger.markDeleting(taskId, 2L);
            }
            case FAILED -> {
                ledger.markInstalling(taskId, 1L, "speaker:pcc-alpha");
                ledger.markFailed(taskId);
            }
            case DELETED -> {
                ledger.markInstalling(taskId, 1L, "speaker:pcc-alpha");
                ledger.markInstalled(taskId, 1L, 42L);
                ledger.markDeleting(taskId, 2L);
                ledger.markDeleted(taskId);
            }
        }
        return ledger;
    }

    /**
     * The test the design named first. An acknowledgement that never arrived is not evidence the
     * LSP was not installed; it may be forwarding traffic. Releasing its capacity here is the
     * double-booking bug this whole state machine exists to prevent.
     */
    @Test
    void anAcknowledgementTimeoutHoldsCapacityInsteadOfReleasingIt() {
        IntentLedger ledger = ledgerWith("T1", InstallationState.INSTALLING);

        InstallationIntent afterTimeout = ledger.markUncertain("T1");

        assertEquals(InstallationState.UNCERTAIN, afterTimeout.getState());
        assertTrue(afterTimeout.holdsCapacity(),
                "an unacknowledged install may still be live; its capacity must not be released");
        assertFalse(afterTimeout.getState().isTerminal());
        assertEquals(1, ledger.holdingCapacity().size());
    }

    @Test
    void uncertaintyCannotBeReachedFromATerminalState() {
        for (InstallationState terminal : new InstallationState[]{
                InstallationState.FAILED, InstallationState.DELETED}) {
            IntentLedger ledger = ledgerWith("T1", terminal);
            assertThrows(IllegalStateException.class, () -> ledger.markUncertain("T1"),
                    terminal + " is terminal and must not transition further");
        }
    }

    @Test
    void onlyTerminalStatesReleaseCapacity() {
        for (InstallationState state : InstallationState.values()) {
            assertEquals(state.isTerminal(), !state.holdsCapacity(),
                    state + " must hold capacity exactly while it is not terminal");
        }
    }

    @Test
    void capacityIsHeldFromThePlannedStateOnward() {
        // Holding only from INSTALLED would let a concurrent solve plan against capacity that is
        // about to disappear, and admit both.
        assertTrue(InstallationState.PLANNED.holdsCapacity());
        assertTrue(InstallationState.INSTALLING.holdsCapacity());
        assertTrue(InstallationState.INSTALLED.holdsCapacity());
        assertTrue(InstallationState.UNCERTAIN.holdsCapacity());
        assertTrue(InstallationState.DELETING.holdsCapacity());
        assertFalse(InstallationState.FAILED.holdsCapacity());
        assertFalse(InstallationState.DELETED.holdsCapacity());
    }

    @Test
    void anInstalledLspCannotSkipStraightToDeleted() {
        IntentLedger ledger = ledgerWith("T1", InstallationState.INSTALLED);

        // Removal must be requested and confirmed; assuming it succeeded would release capacity
        // for an LSP a router may still be forwarding.
        assertThrows(IllegalStateException.class, () -> ledger.markDeleted("T1"));
    }

    @Test
    void everyIllegalTransitionIsRejected() {
        for (InstallationState from : InstallationState.values()) {
            Set<InstallationState> legal = IntentLedger.legalTransitionsFrom(from);
            for (InstallationState to : InstallationState.values()) {
                if (legal.contains(to)) {
                    continue;
                }
                IntentLedger ledger = ledgerWith("T1", from);
                assertThrows(IllegalStateException.class,
                        () -> invoke(ledger, "T1", to),
                        from + " -> " + to + " should be rejected");
            }
        }
    }

    private static void invoke(IntentLedger ledger, String taskId, InstallationState to) {
        switch (to) {
            case PLANNED -> ledger.plan(taskId);
            case INSTALLING -> ledger.markInstalling(taskId, 9L, "speaker:pcc-alpha");
            case INSTALLED -> ledger.markInstalled(taskId, null, 7L);
            case UNCERTAIN -> ledger.markUncertain(taskId);
            case FAILED -> ledger.markFailed(taskId);
            case UPDATING -> ledger.markUpdating(taskId, 9L, 5_000_000d);
            case DELETING -> ledger.markDeleting(taskId, 9L);
            case DELETED -> ledger.markDeleted(taskId);
        }
    }

    @Test
    void aReportCarryingAStaleOperationIdentifierIsRejected() {
        IntentLedger ledger = ledgerWith("T1", InstallationState.INSTALLING);

        // Operation 1 is outstanding. A report for operation 99 answers something else; treating
        // it as confirmation would mark an install acknowledged that never was.
        assertThrows(IllegalStateException.class, () -> ledger.markInstalled("T1", 99L, 42L));

        assertEquals(InstallationState.INSTALLED, ledger.markInstalled("T1", 1L, 42L).getState());
    }

    @Test
    void uncertaintyIsResolvedInEitherDirection() {
        IntentLedger confirmed = ledgerWith("A", InstallationState.UNCERTAIN);
        assertEquals(InstallationState.INSTALLED,
                confirmed.markInstalled("A", null, 5L).getState());

        IntentLedger released = ledgerWith("B", InstallationState.UNCERTAIN);
        assertEquals(InstallationState.FAILED, released.markFailed("B").getState());
        assertFalse(released.find("B").orElseThrow().holdsCapacity());
    }

    @Test
    void aSessionLossMakesAnInstalledLspUncertainAgain() {
        IntentLedger ledger = ledgerWith("T1", InstallationState.INSTALLED);

        assertEquals(InstallationState.UNCERTAIN, ledger.markUncertain("T1").getState());
        assertTrue(ledger.find("T1").orElseThrow().holdsCapacity());
    }

    @Test
    void theLspNameIsDeterministicAndIdentifiesOwnership() {
        assertEquals("vortex-T1", InstallationIntent.lspNameFor("T1"));
        assertEquals(InstallationIntent.lspNameFor("T1"), InstallationIntent.lspNameFor("T1"));
        assertTrue(InstallationIntent.isOwnedName("vortex-T1"));
        assertFalse(InstallationIntent.isOwnedName("someone-elses-lsp"));
    }

    @Test
    void planningTheSameTaskTwiceIsRejected() {
        IntentLedger ledger = new IntentLedger();
        ledger.plan("T1");
        assertThrows(IllegalStateException.class, () -> ledger.plan("T1"));
    }

    @Test
    void anUnknownTaskCannotTransition() {
        assertThrows(IllegalStateException.class, () -> new IntentLedger().markUncertain("nope"));
    }

    @Test
    void anIntentIsFindableByItsNetworkIdentity() {
        IntentLedger ledger = ledgerWith("T1", InstallationState.INSTALLED);

        assertEquals("T1", ledger.findByLspName("vortex-T1").orElseThrow().getTaskId());
        assertTrue(ledger.findByLspName("vortex-absent").isEmpty());
    }
}
