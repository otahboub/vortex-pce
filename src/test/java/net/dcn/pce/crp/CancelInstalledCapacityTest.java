package net.dcn.pce.crp;

import net.dcn.pce.install.InstallationCoordinator;
import net.dcn.pce.install.InstallationIntent;
import net.dcn.pce.install.InstallationState;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.LinkIntermittencyFunction;
import net.dcn.pce.model.Node;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.pcep.ReportedLsp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cancelling work that reached the network must not release its capacity early.
 *
 * <p>An intent that holds capacity and a ledger that does not are the same class of divergence
 * the transactional-installation work closed, arrived at from the other direction: cancellation
 * dropped the reservations immediately, and the intent was then demoted to UNCERTAIN — a state
 * whose entire purpose is to keep capacity held because the PCC may still be forwarding.
 */
class CancelInstalledCapacityTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e9, 1e9));
        topology.addNode(new Node("B", "B", 1e9, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
        return topology;
    }

    private static ReportedLsp report(String lspName, Long srpId, boolean up, boolean removed) {
        return new ReportedLsp(srpId, 42L, lspName,
                up ? ReportedLsp.OperationalState.UP : ReportedLsp.OperationalState.DOWN,
                removed, false, true, true);
    }

    /**
     * Stands in for the PCC that owns the LSP, and records what was actually asked of it.
     *
     * <p>The point of the recording is that cancellation used to report a removal request without
     * sending one. A test that only checked the resulting state passed against that code.
     */
    private static final class RecordingPcc implements CRPEngine.RemovalDispatcher {
        private final boolean reachable;
        private InstallationIntent removed;
        private long srpId;
        private byte[] frame;
        private InstallationState stateWhenDispatched;

        RecordingPcc(boolean reachable) {
            this.reachable = reachable;
        }

        @Override
        public CRPEngine.DispatchOutcome requestRemoval(InstallationIntent intent, long srpId) {
            if (!reachable) {
                return CRPEngine.DispatchOutcome.NOT_ATTEMPTED;
            }
            this.removed = intent;
            this.srpId = srpId;
            this.stateWhenDispatched = intent.getState();
            this.frame = net.dcn.pce.pcep.PcepEncoder.pcInitiateRemoval(
                    srpId, intent.getPlspId().orElseThrow(), intent.getLspName());
            return CRPEngine.DispatchOutcome.SENT;
        }
    }

    private static CRPEngine installedEngine(Path dir) {
        return installedEngine(dir, new RecordingPcc(true));
    }

    private static CRPEngine installedEngine(Path dir, CRPEngine.RemovalDispatcher dispatcher) {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString())
                .withRemovalDispatcher(dispatcher);
        engine.solve(topology(), List.of(new WorkloadTask("T1", "A", "B", 0, 10_000, 1_000)));
        InstallationCoordinator coordinator = new InstallationCoordinator(
                engine.getIntents(), Clock.systemUTC(), Duration.ofSeconds(30));
        coordinator.recordSent("T1", 7L, "speaker:pcc-alpha");
        engine.applyInstallationChange(
                () -> coordinator.applyAndReturn("speaker:pcc-alpha", report("vortex-T1", 7L, true, false)));
        return engine;
    }

    /** The invariant, stated once: an intent holding capacity must have reservations backing it. */
    private static void assertLedgerAgreesWithIntents(CRPEngine engine, String context) {
        java.util.Set<String> reserved = new java.util.LinkedHashSet<>();
        engine.getLRIB().getAllReservations().forEach(r -> reserved.add(r.getTaskId()));
        engine.getNRIB().getAllReservations().forEach(r -> reserved.add(r.getTaskId()));
        java.util.Set<String> holding = new java.util.LinkedHashSet<>();
        engine.getIntents().holdingCapacity().forEach(i -> holding.add(i.getTaskId()));

        assertEquals(holding, reserved,
                context + ": intents holding capacity and tasks with reservations must agree");
    }

    @Test
    void cancellingAnInstalledTaskDoesNotReleaseCapacityBeforeRemovalIsConfirmed(@TempDir Path dir) {
        CRPEngine engine = installedEngine(dir);
        assertEquals(InstallationState.INSTALLED, engine.getIntents().find("T1").orElseThrow().getState());

        engine.cancelTask("T1");

        InstallationIntent intent = engine.getIntents().find("T1").orElseThrow();
        assertTrue(intent.holdsCapacity(),
                "an installed LSP may still be forwarding until the PCC confirms removal");
        assertFalse(engine.getLRIB().getAllReservations().isEmpty(),
                "capacity must stay reserved until removal is confirmed, or a later solve can "
                        + "hand the same bandwidth to another flow");
        assertLedgerAgreesWithIntents(engine, "after cancelling an installed task");
    }

    @Test
    void confirmedRemovalAfterCancellationReleasesTheCapacity(@TempDir Path dir) {
        CRPEngine engine = installedEngine(dir);
        InstallationCoordinator coordinator = new InstallationCoordinator(
                engine.getIntents(), Clock.systemUTC(), Duration.ofSeconds(30));

        engine.cancelTask("T1");
        engine.applyInstallationChange(
                () -> coordinator.applyAndReturn("speaker:pcc-alpha", report("vortex-T1", null, false, true)));

        assertEquals(InstallationState.DELETED, engine.getIntents().find("T1").orElseThrow().getState());
        assertTrue(engine.getLRIB().getAllReservations().isEmpty(),
                "a confirmed removal releases the capacity the cancellation reserved");
        assertLedgerAgreesWithIntents(engine, "after confirmed removal");
    }

    @Test
    void cancellingAnInstalledTaskSendsTheRemovalToTheOwningPcc(@TempDir Path dir) {
        RecordingPcc pcc = new RecordingPcc(true);
        CRPEngine engine = installedEngine(dir, pcc);

        assertEquals(CRPEngine.CancellationOutcome.REMOVAL_REQUESTED, engine.requestCancellation("T1"));

        assertNotNull(pcc.removed, "a removal must actually be dispatched, not merely recorded");
        assertEquals("speaker:pcc-alpha", pcc.removed.getPccSessionKey().orElseThrow(),
                "the removal goes to the PCC that installed the LSP, not to whichever peer is up");
        assertEquals(42L, pcc.removed.getPlspId().orElseThrow(),
                "the router identifies the LSP by the PLSP-ID it assigned");
        assertArrayEquals(
                net.dcn.pce.pcep.PcepEncoder.pcInitiateRemoval(pcc.srpId, 42L, "vortex-T1"),
                pcc.frame);
        assertEquals(pcc.srpId,
                engine.getIntents().find("T1").orElseThrow().getSrpId().orElseThrow(),
                "the ledger records the SRP the PCC will answer with");
    }

    @Test
    void anUndeliverableRemovalIsNotReportedAsRequested(@TempDir Path dir) {
        // The southbound session is down. The LSP is still installed and may still be forwarding,
        // so capacity stays held -- but nothing was asked of any router, and saying otherwise
        // leaves a task waiting for an acknowledgement that cannot arrive.
        CRPEngine engine = installedEngine(dir, new RecordingPcc(false));

        assertEquals(CRPEngine.CancellationOutcome.REMOVAL_UNDELIVERABLE,
                engine.requestCancellation("T1"));

        assertEquals(InstallationState.INSTALLED,
                engine.getIntents().find("T1").orElseThrow().getState(),
                "an intent must not be marked DELETING for a request that was never sent");
        assertFalse(engine.getLRIB().getAllReservations().isEmpty(),
                "the LSP may still be forwarding, so its capacity stays held");
        assertLedgerAgreesWithIntents(engine, "after an undeliverable removal");
    }

    @Test
    void withNoSouthboundPathAtAllRemovalIsUndeliverable(@TempDir Path dir) {
        CRPEngine engine = installedEngine(dir, null);

        assertEquals(CRPEngine.CancellationOutcome.REMOVAL_UNDELIVERABLE,
                engine.requestCancellation("T1"));
        assertEquals(InstallationState.INSTALLED,
                engine.getIntents().find("T1").orElseThrow().getState());
    }

    @Test
    void aDispatcherThatThrowsIsTreatedAsUndelivered(@TempDir Path dir) {
        CRPEngine engine = installedEngine(dir, (intent, srpId) -> {
            throw new IllegalStateException("socket closed");
        });

        // The record stands. An exception says nothing about how far the write got, and a PCC
        // acting on a removal the controller has forgotten is the state this ordering prevents.
        assertEquals(CRPEngine.CancellationOutcome.REMOVAL_REQUESTED,
                engine.requestCancellation("T1"));
        assertEquals(InstallationState.DELETING,
                engine.getIntents().find("T1").orElseThrow().getState());
    }

    @Test
    void theRemovalIsDurableBeforeItIsDispatched(@TempDir Path dir) {
        // The crash window this ordering closes. Sending first read well -- the ledger only ever
        // recorded requests actually made -- until the process died in between, leaving the PCC
        // removing an LSP durable state still called INSTALLED, indistinguishable afterwards from
        // a router that dropped it on its own.
        RecordingPcc pcc = new RecordingPcc(true);
        CRPEngine engine = installedEngine(dir, pcc);

        engine.requestCancellation("T1");

        assertEquals(InstallationState.DELETING, pcc.stateWhenDispatched,
                "the intent must already be DELETING at the moment the frame is handed over");
    }

    @Test
    void aRemovalThatWasNeverAttemptedLeavesNoTraceInTheLedger(@TempDir Path dir) {
        // The other half: because NO_SESSION means nothing left the process, the record written
        // a moment earlier can be undone. Anything weaker than that certainty must not undo it.
        CRPEngine engine = installedEngine(dir, new RecordingPcc(false));

        assertEquals(CRPEngine.CancellationOutcome.REMOVAL_UNDELIVERABLE,
                engine.requestCancellation("T1"));

        InstallationIntent after = engine.getIntents().find("T1").orElseThrow();
        assertEquals(InstallationState.INSTALLED, after.getState());
        assertEquals(42L, after.getPlspId().orElseThrow(), "the rollback restores the whole intent");
        assertEquals("speaker:pcc-alpha", after.getPccSessionKey().orElseThrow());
        assertLedgerAgreesWithIntents(engine, "after a removal that was never attempted");
    }

    @Test
    void askingTwiceDoesNotSendTwice(@TempDir Path dir) {
        RecordingPcc pcc = new RecordingPcc(true);
        CRPEngine engine = installedEngine(dir, pcc);
        engine.requestCancellation("T1");
        long firstSrpId = pcc.srpId;

        assertEquals(CRPEngine.CancellationOutcome.REMOVAL_REQUESTED, engine.requestCancellation("T1"));
        assertEquals(firstSrpId, pcc.srpId,
                "a second request must not issue a second SRP the PCC would have to answer");
    }

    @Test
    void cancellingAPlannedTaskReleasesImmediately(@TempDir Path dir) {
        // Nothing was ever sent, so there is no LSP to confirm removal of and no reason to wait.
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        engine.solve(topology(), List.of(new WorkloadTask("T1", "A", "B", 0, 10_000, 1_000)));
        assertEquals(InstallationState.PLANNED, engine.getIntents().find("T1").orElseThrow().getState());

        assertTrue(engine.cancelTask("T1"));

        assertTrue(engine.getLRIB().getAllReservations().isEmpty());
        assertLedgerAgreesWithIntents(engine, "after cancelling a planned task");
    }

    @Test
    void theInvariantHoldsAcrossARestart(@TempDir Path dir) {
        CRPEngine engine = installedEngine(dir);
        engine.cancelTask("T1");

        CRPEngine restarted = new CRPEngine()
                .withDurableState(dir.resolve("state.json").toString());

        assertLedgerAgreesWithIntents(restarted, "after restart following cancellation");
    }
}
