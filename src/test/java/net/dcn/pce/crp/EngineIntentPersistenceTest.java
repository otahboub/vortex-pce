package net.dcn.pce.crp;

import net.dcn.pce.install.InstallationIntent;
import net.dcn.pce.install.InstallationState;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.LinkIntermittencyFunction;
import net.dcn.pce.model.Node;
import net.dcn.pce.model.WorkloadTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intent persistence through the engine's own startup path.
 *
 * <p>The store could already carry intents, but the production engine restored only LRIB and NRIB,
 * so nothing wrote or recovered them in a real deployment. These tests exercise
 * {@link CRPEngine#withReservationStore}, which is the path a running controller takes.
 */
class EngineIntentPersistenceTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e9, 1e9));
        topology.addNode(new Node("B", "B", 1e9, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
        return topology;
    }

    private static WorkloadTask task(String id) {
        return new WorkloadTask(id, "A", "B", 0, 10_000, 1_000);
    }

    @Test
    void aCommittedSolveRecordsAPlannedIntent(@TempDir Path dir) {
        CRPEngine engine = new CRPEngine()
                .withDurableState(dir.resolve("state.json").toString());

        engine.solve(topology(), List.of(task("T1")));

        InstallationIntent intent = engine.getIntents().find("T1").orElseThrow();
        assertEquals(InstallationState.PLANNED, intent.getState());
        assertEquals("vortex-T1", intent.getLspName());
        assertTrue(intent.holdsCapacity());
    }

    @Test
    void anIntentSurvivesARestartThroughTheEngineStartupPath(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();

        CRPEngine first = new CRPEngine().withDurableState(statePath);
        first.solve(topology(), List.of(task("T1")));
        assertTrue(first.getIntents().find("T1").isPresent());

        // A fresh engine over the same state, as a restarted controller would be.
        CRPEngine restarted = new CRPEngine().withDurableState(statePath);

        InstallationIntent recovered = restarted.getIntents().find("T1").orElseThrow();
        assertEquals(InstallationState.PLANNED, recovered.getState());
        assertEquals("vortex-T1", recovered.getLspName());
        assertFalse(restarted.getLRIB().getAllReservations().isEmpty(),
                "the reservations that intent explains should be recovered too");
    }

    @Test
    void cancellingATaskRetiresItsIntentInTheSameTransaction(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();
        CRPEngine engine = new CRPEngine().withDurableState(statePath);
        engine.solve(topology(), List.of(task("T1")));

        assertTrue(engine.cancelTask("T1"));

        InstallationIntent intent = engine.getIntents().find("T1").orElseThrow();
        assertFalse(intent.holdsCapacity(),
                "a cancelled task's intent must stop holding capacity with its reservations");

        // The pairing has to survive a restart, not merely hold in memory.
        CRPEngine restarted = new CRPEngine().withDurableState(statePath);
        assertTrue(restarted.getLRIB().getAllReservations().isEmpty());
        assertTrue(restarted.getIntents().find("T1").isEmpty()
                        || !restarted.getIntents().find("T1").orElseThrow().holdsCapacity(),
                "a restart must not resurrect a cancelled task as holding capacity");
    }

    @Test
    void reservationsAndIntentsAgreeAfterARestart(@TempDir Path dir) {
        String statePath = dir.resolve("state.json").toString();
        CRPEngine engine = new CRPEngine().withDurableState(statePath);
        engine.solve(topology(), List.of(task("T1"), task("T2"), task("T3")));
        engine.cancelTask("T2");

        CRPEngine restarted = new CRPEngine().withDurableState(statePath);

        // Every task still holding reservations must have an intent that says so, and every
        // intent holding capacity must have reservations. A disagreement is capacity the
        // controller cannot explain, or an intent for capacity it does not hold.
        java.util.Set<String> reserved = new java.util.LinkedHashSet<>();
        restarted.getLRIB().getAllReservations().forEach(r -> reserved.add(r.getTaskId()));
        java.util.Set<String> intended = new java.util.LinkedHashSet<>();
        restarted.getIntents().holdingCapacity().forEach(i -> intended.add(i.getTaskId()));

        assertEquals(reserved, intended,
                "reserved tasks and capacity-holding intents disagree after restart");
        assertFalse(reserved.contains("T2"), "the cancelled task should appear in neither");
    }

    @Test
    void anEngineWithoutDurableStateStillTracksIntentsInMemory(@TempDir Path dir) {
        CRPEngine engine = new CRPEngine().withPersistentState(true);

        engine.solve(topology(), List.of(task("T1")));

        // This test previously asserted the opposite -- that no intent is recorded without a
        // store -- while its own name and the comment above it said intents must be tracked. The
        // assertion was describing a defect rather than a requirement: intent bookkeeping sat
        // below the persistence guard, so with in-memory state an admitted flow held capacity
        // that nothing described, and dispatch, cancellation and reconciliation all had nothing
        // to act on. Whether there is a log to write to has no bearing on what the controller
        // intends.
        assertEquals(net.dcn.pce.install.InstallationState.PLANNED,
                engine.getIntents().find("T1").orElseThrow(
                        () -> new AssertionError("an admitted flow must have an intent whether or "
                                + "not a state store is configured")).getState());
        assertFalse(engine.getLRIB().getAllReservations().isEmpty());
    }
}
