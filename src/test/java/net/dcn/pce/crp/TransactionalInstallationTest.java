package net.dcn.pce.crp;

import net.dcn.pce.install.InstallationCoordinator;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installation transitions must be durable and must move capacity with them.
 *
 * <p>The review found that reports mutated only the in-memory ledger: INSTALLED, UNCERTAIN and
 * DELETED were lost on restart, a confirmed removal retired the intent without freeing its
 * reservations, and reconciliation could mark an intent failed while its capacity stayed
 * committed. These tests pin all three.
 */
class TransactionalInstallationTest {

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

    private static CRPEngine engineAt(Path dir) {
        return new CRPEngine().withDurableState(dir.resolve("state.json").toString());
    }

    @Test
    void aConfirmedInstallSurvivesARestart(@TempDir Path dir) {
        CRPEngine engine = engineAt(dir);
        engine.solve(topology(), List.of(new WorkloadTask("T1", "A", "B", 0, 10_000, 1_000)));
        InstallationCoordinator coordinator = new InstallationCoordinator(
                engine.getIntents(), Clock.systemUTC(), Duration.ofSeconds(30));
        coordinator.recordSent("T1", 7L, "speaker:pcc-alpha");

        engine.applyInstallationChange(
                () -> coordinator.applyAndReturn("speaker:pcc-alpha", report("vortex-T1", 7L, true, false)));
        assertEquals(InstallationState.INSTALLED, engine.getIntents().find("T1").orElseThrow().getState());

        CRPEngine restarted = engineAt(dir);
        assertEquals(InstallationState.INSTALLED,
                restarted.getIntents().find("T1").orElseThrow().getState(),
                "a confirmed install must not be forgotten across a restart");
    }

    @Test
    void aConfirmedRemovalReleasesTheCapacityItRetires(@TempDir Path dir) {
        CRPEngine engine = engineAt(dir);
        engine.solve(topology(), List.of(new WorkloadTask("T1", "A", "B", 0, 10_000, 1_000)));
        InstallationCoordinator coordinator = new InstallationCoordinator(
                engine.getIntents(), Clock.systemUTC(), Duration.ofSeconds(30));
        coordinator.recordSent("T1", 7L, "speaker:pcc-alpha");
        engine.applyInstallationChange(
                () -> coordinator.applyAndReturn("speaker:pcc-alpha", report("vortex-T1", 7L, true, false)));
        assertFalse(engine.getLRIB().getAllReservations().isEmpty());

        coordinator.recordRemovalRequested("T1", 8L);
        engine.applyInstallationChange(
                () -> coordinator.applyAndReturn("speaker:pcc-alpha", report("vortex-T1", 8L, false, true)));

        // The intent says the capacity is no longer held; the ledger must agree, or the planner
        // keeps bandwidth reserved for an LSP the network has confirmed is gone.
        assertEquals(InstallationState.DELETED, engine.getIntents().find("T1").orElseThrow().getState());
        assertTrue(engine.getLRIB().getAllReservations().isEmpty(),
                "a retired intent must not leave its reservations behind");
        assertTrue(engine.getNRIB().getAllReservations().isEmpty());

        CRPEngine restarted = engineAt(dir);
        assertTrue(restarted.getLRIB().getAllReservations().isEmpty(),
                "the release must survive a restart too");
    }

    @Test
    void reconciliationReleaseFreesReservations(@TempDir Path dir) {
        CRPEngine engine = engineAt(dir);
        engine.solve(topology(), List.of(new WorkloadTask("T1", "A", "B", 0, 10_000, 1_000)));
        InstallationCoordinator coordinator = new InstallationCoordinator(
                engine.getIntents(), Clock.systemUTC(), Duration.ofSeconds(30));
        coordinator.recordSent("T1", 7L, "speaker:pcc-alpha");
        engine.getIntents().markUncertain("T1");

        // A completed synchronisation that does not mention the LSP: definitively absent.
        engine.applyInstallationChange(() -> {
            coordinator.reconcile(java.util.Set.of(), true);
            return engine.getIntents().find("T1").orElse(null);
        });

        assertEquals(InstallationState.FAILED, engine.getIntents().find("T1").orElseThrow().getState());
        assertTrue(engine.getLRIB().getAllReservations().isEmpty(),
                "a released intent must release its capacity, not merely record failure");
    }

    @Test
    void anUncertainTransitionIsDurableAndKeepsItsCapacity(@TempDir Path dir) {
        CRPEngine engine = engineAt(dir);
        engine.solve(topology(), List.of(new WorkloadTask("T1", "A", "B", 0, 10_000, 1_000)));
        InstallationCoordinator coordinator = new InstallationCoordinator(
                engine.getIntents(), Clock.systemUTC(), Duration.ofSeconds(30));
        coordinator.recordSent("T1", 7L, "speaker:pcc-alpha");

        engine.applyInstallationChange(() -> engine.getIntents().markUncertain("T1"));

        CRPEngine restarted = engineAt(dir);
        assertEquals(InstallationState.UNCERTAIN,
                restarted.getIntents().find("T1").orElseThrow().getState());
        assertFalse(restarted.getLRIB().getAllReservations().isEmpty(),
                "an unacknowledged operation may still be live; its capacity stays held");
    }

    /** M2: a failed durable append must not leave an intent the transaction never committed. */
    @Test
    void aFailedAppendLeavesNoOrphanedIntent(@TempDir Path dir) throws Exception {
        java.nio.file.Path statePath = dir.resolve("state.json");
        java.nio.file.Path walPath = dir.resolve("state.json.wal");
        CRPEngine engine = new CRPEngine().withDurableState(statePath.toString());
        engine.solve(topology(), List.of(new WorkloadTask("SEED", "A", "B", 0, 10_000, 1_000)));

        // Make the durable write fail, as a full or unwritable volume would.
        java.nio.file.Files.delete(walPath);
        java.nio.file.Files.createDirectory(walPath);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> engine.solve(topology(),
                        List.of(new WorkloadTask("NEVER", "A", "B", 0, 10_000, 1_000))));

        assertTrue(engine.getIntents().find("NEVER").isEmpty(),
                "an intent published before a failed append must be rolled back with the ledgers");
        assertTrue(engine.getIntents().find("SEED").isPresent(),
                "the rollback must restore the prior intents, not clear them");
    }
}
