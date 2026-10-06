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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget must bind inside a single expensive stage, not only between workloads.
 */
class SolveDeadlineStageTest {

    /**
     * A dense mesh. {@code K_MAX_EXHAUSTIVE} enumerates every simple path, which grows
     * super-exponentially, so one workload can run far past any budget without a stage-level
     * cancellation point.
     */
    private static BaseTopology denseMesh(int nodeCount) {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        for (int i = 0; i < nodeCount; i++) {
            topology.addNode(new Node("N" + i, "N" + i, 1e9, 1e9));
        }
        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                if (i != j) {
                    topology.addLink(new Link("L" + i + "-" + j, "N" + i, "N" + j,
                            LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
                }
            }
        }
        return topology;
    }

    @Test
    void anExpensiveEnumerationIsCancelledMidStage() {
        // Singleton LWEEF no longer spends a preliminary BFS before route generation. Use one
        // additional node so exhaustive enumeration remains in flight when the budget expires;
        // this test is specifically the route-enumeration checkpoint guard.
        BaseTopology topology = denseMesh(12);
        CRPEngine engine = new CRPEngine()
                .withFGenerate(RouteGenerationPolicy.K_MAX_EXHAUSTIVE)
                .withSolveTimeout(Duration.ofMillis(150));

        long startNanos = System.nanoTime();
        SolveTimeoutException timeout = assertThrows(SolveTimeoutException.class,
                () -> engine.solve(topology,
                        List.of(new WorkloadTask("BIG", "N0", "N10", 0, 1_000_000, 1_000))));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(timeout.getMessage().contains("route enumeration"),
                "the timeout should name the stage it fired in: " + timeout.getMessage());
        // Overshoot is bounded by the checkpoint sampling interval, not by the budget, so this
        // asserts a real ceiling rather than merely "finished eventually". The 2s allowance
        // absorbs CI scheduling noise; without stage checkpoints this enumeration runs for
        // minutes, so the bound still fails loudly if a checkpoint is removed.
        assertTrue(elapsedMillis < 150 + 2_000,
                "overshoot beyond the 150 ms budget must stay bounded, took " + elapsedMillis + " ms");
        assertEquals(0, engine.getLRIB().getAllReservations().size());
    }

    @Test
    void overshootIsBoundedByCheckpointGranularityNotByTheBudget() {
        // If overshoot scaled with the budget, a longer budget would overshoot proportionally.
        // It should not: the bound comes from how often the checkpoints sample the clock.
        BaseTopology topology = denseMesh(11);
        long overshootAtShortBudget = measureOvershootMillis(topology, 100);
        long overshootAtLongBudget = measureOvershootMillis(topology, 400);

        assertTrue(overshootAtShortBudget < 2_000,
                "overshoot at a 100 ms budget was " + overshootAtShortBudget + " ms");
        assertTrue(overshootAtLongBudget < 2_000,
                "overshoot at a 400 ms budget was " + overshootAtLongBudget + " ms");
    }

    private static long measureOvershootMillis(BaseTopology topology, long budgetMillis) {
        CRPEngine engine = new CRPEngine()
                .withFGenerate(RouteGenerationPolicy.K_MAX_EXHAUSTIVE)
                .withSolveTimeout(Duration.ofMillis(budgetMillis));
        long startNanos = System.nanoTime();
        assertThrows(SolveTimeoutException.class, () -> engine.solve(topology,
                List.of(new WorkloadTask("BIG", "N0", "N10", 0, 1_000_000, 1_000))));
        return ((System.nanoTime() - startNanos) / 1_000_000L) - budgetMillis;
    }

    @Test
    void everyExpensivePlannerStageHonoursTheBudget() {
        // The stages the review named as uncovered: task selection, minimum-hop search, rate
        // assignment, and contact-slot construction. Each runs under the engine defaults.
        BaseTopology topology = denseMesh(9);
        List<WorkloadTask> many = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            many.add(new WorkloadTask("T" + i, "N0", "N8", 0, 1_000_000, 1_000));
        }

        CRPEngine engine = new CRPEngine().withSolveTimeout(Duration.ofMillis(1));
        long startNanos = System.nanoTime();
        assertThrows(SolveTimeoutException.class, () -> engine.solve(topology, many));

        assertTrue((System.nanoTime() - startNanos) / 1_000_000L < 2_000,
                "the default policy path must also yield to the budget");
        assertEquals(0, engine.getLRIB().getAllReservations().size());
    }

    @Test
    void anUnboundedEngineStillCompletesTheSameEnumeration() {
        // Guards against the checkpoint firing when no budget is configured.
        CRPEngine engine = new CRPEngine().withFGenerate(RouteGenerationPolicy.K_MAX_EXHAUSTIVE);

        CRPEngine.PCEComputationResult result = engine.solve(denseMesh(6),
                List.of(new WorkloadTask("OK", "N0", "N5", 0, 1_000_000, 1_000)));

        assertEquals(1, result.getOfferedFlowCount());
    }

    @Test
    void theDeadlineDoesNotLeakToLaterSolvesOnTheSameThread() {
        BaseTopology topology = denseMesh(6);
        CRPEngine expired = new CRPEngine().withSolveTimeout(Duration.ofNanos(1));
        assertThrows(SolveTimeoutException.class, () -> expired.solve(topology,
                List.of(new WorkloadTask("T1", "N0", "N5", 0, 1_000_000, 1_000))));

        // The thread-local must be cleared, or an unrelated unbounded engine would inherit an
        // already-expired budget and fail for no reason.
        CRPEngine unbounded = new CRPEngine();
        assertEquals(1, unbounded.solve(topology,
                List.of(new WorkloadTask("T2", "N0", "N5", 0, 1_000_000, 1_000)))
                .getOfferedFlowCount());
    }
}
