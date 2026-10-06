package net.dcn.pce.crp;

import net.dcn.pce.install.InstallationState;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The intent ledger is maintained with or without durable state.
 *
 * <p>Intent bookkeeping used to sit below the persistence guard, so a controller running with
 * in-memory state — the default, and what the shipped container runs unless a state path is set —
 * created no intents at all. An admitted flow held capacity that nothing described, and every
 * feature built on the ledger silently had nothing to act on: installation dispatch found no
 * intent to transition, cancelling an installed LSP saw no record of it, and reconciliation had
 * nothing to reconcile.
 *
 * <p>It survived because every existing test that touches intents configures durable state. These
 * deliberately do not.
 */
class InMemoryIntentLedgerTest {

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
        return new WorkloadTask(id, "A", "B", 0, 60, 1_000_000);
    }

    @Test
    void anAdmittedFlowGetsAnIntentWithoutDurableState() {
        CRPEngine engine = new CRPEngine();   // no withDurableState: in-memory, as shipped

        engine.solve(topology(), List.of(task("T1")));

        assertEquals(InstallationState.PLANNED,
                engine.getIntents().find("T1").orElseThrow(
                        () -> new AssertionError("an admitted flow must have an intent even with "
                                + "no state store; capacity is held either way")).getState());
    }

    @Test
    void releasingCapacityRetiresTheIntentWithoutDurableState() {
        CRPEngine engine = new CRPEngine();
        engine.solve(topology(), List.of(task("T1")));

        engine.cancelTask("T1");

        assertEquals(InstallationState.FAILED,
                engine.getIntents().find("T1").orElseThrow().getState(),
                "a PLANNED task whose reservations went away is definitively gone");
        assertTrue(engine.getLRIB().getAllReservations().isEmpty());
    }

    @Test
    void intentsAndReservationsAgreeWithoutDurableState() {
        // The invariant the durable path already enforces, asserted for the in-memory one.
        CRPEngine engine = new CRPEngine();
        engine.solve(topology(), List.of(task("T1"), task("T2")));

        java.util.Set<String> reserved = new java.util.LinkedHashSet<>();
        engine.getLRIB().getAllReservations().forEach(r -> reserved.add(r.getTaskId()));
        java.util.Set<String> holding = new java.util.LinkedHashSet<>();
        engine.getIntents().holdingCapacity().forEach(i -> holding.add(i.getTaskId()));

        assertEquals(reserved, holding);
    }
}
