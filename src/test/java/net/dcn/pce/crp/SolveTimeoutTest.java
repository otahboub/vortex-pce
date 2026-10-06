package net.dcn.pce.crp;

import net.dcn.pce.crp.policy.RouteGenerationPolicy;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.LinkIntermittencyFunction;
import net.dcn.pce.model.Node;
import net.dcn.pce.model.WorkloadTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolveTimeoutTest {

    /** A linear chain, so each workload requires real route generation work. */
    private static BaseTopology chainTopology(int nodeCount) {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        for (int i = 0; i < nodeCount; i++) {
            topology.addNode(new Node("N" + i, "N" + i, 1e9, 1e9));
        }
        for (int i = 0; i < nodeCount - 1; i++) {
            topology.addLink(new Link("L" + i, "N" + i, "N" + (i + 1),
                    LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
        }
        return topology;
    }

    private static List<WorkloadTask> workloads(int count) {
        List<WorkloadTask> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(new WorkloadTask("T" + i, "N0", "N9", 0, 1_000_000, 1_000));
        }
        return tasks;
    }

    @Test
    void solveIsUnboundedByDefaultSoExistingBehaviorIsUnchanged() {
        CRPEngine engine = new CRPEngine();
        CRPEngine.PCEComputationResult result = engine.solve(chainTopology(10), workloads(20));

        assertEquals(20, result.getOfferedFlowCount());
    }

    @Test
    void aZeroTimeoutMeansUnbounded() {
        CRPEngine engine = new CRPEngine().withSolveTimeout(Duration.ZERO);
        CRPEngine.PCEComputationResult result = engine.solve(chainTopology(10), workloads(20));

        assertEquals(20, result.getOfferedFlowCount());
    }

    @Test
    void anExpiredBudgetAbortsTheSolve() {
        // A one-nanosecond budget is already spent when the first cancellation point is reached.
        CRPEngine engine = new CRPEngine().withSolveTimeout(Duration.ofNanos(1));

        SolveTimeoutException timeout = assertThrows(SolveTimeoutException.class,
                () -> engine.solve(chainTopology(10), workloads(50)));

        assertEquals(50, timeout.getTotalWorkloads());
        assertTrue(timeout.getMessage().contains("No reservations were committed"));
    }

    @Test
    void anAbortedSolveCommitsNoReservations() {
        BaseTopology topology = chainTopology(10);
        CRPEngine engine = new CRPEngine()
                .withPersistentState(true)
                .withSolveTimeout(Duration.ofNanos(1));

        assertThrows(SolveTimeoutException.class, () -> engine.solve(topology, workloads(50)));

        // The transaction must leave the ledgers exactly as it found them: a partially applied
        // plan would hold capacity for a request that never returned a schedule.
        assertEquals(0, engine.getLRIB().getAllReservations().size(),
                "an aborted solve must not leave link reservations behind");
        assertEquals(0, engine.getNRIB().getAllReservations().size(),
                "an aborted solve must not leave node reservations behind");
    }

    @Test
    void anAbortedSolveLeavesPriorReservationsIntact() {
        BaseTopology topology = chainTopology(10);
        CRPEngine engine = new CRPEngine().withPersistentState(true);

        engine.solve(topology, List.of(new WorkloadTask("KEEP", "N0", "N9", 0, 1_000_000, 1_000)));
        int committedBefore = engine.getLRIB().getAllReservations().size();
        assertTrue(committedBefore > 0, "the first solve should commit reservations");

        engine.withSolveTimeout(Duration.ofNanos(1));
        assertThrows(SolveTimeoutException.class, () -> engine.solve(topology, workloads(50)));

        assertEquals(committedBefore, engine.getLRIB().getAllReservations().size(),
                "rollback must restore the pre-solve ledger, not clear it");
        assertTrue(engine.getLRIB().getAllReservations().stream()
                        .allMatch(reservation -> "KEEP".equals(reservation.getTaskId())),
                "only the previously committed task should remain reserved");
    }

    @Test
    void aGenerousBudgetCompletesNormally() {
        CRPEngine engine = new CRPEngine()
                .withFGenerate(RouteGenerationPolicy.BFS_MIN_HOP)
                .withSolveTimeout(Duration.ofSeconds(60));

        CRPEngine.PCEComputationResult result = engine.solve(chainTopology(10), workloads(20));

        assertEquals(20, result.getOfferedFlowCount());
        assertEquals(20, result.getCommittedFlowCount());
    }

    @Test
    void aNegativeBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CRPEngine().withSolveTimeout(Duration.ofSeconds(-1)));
    }
}
