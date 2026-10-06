package net.dcn.pce.crp;

import net.dcn.pce.crp.policy.RouteGenerationPolicy;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admission must be monotone in the deadline under a contact plan.
 *
 * <p>A flow feasible by deadline D is feasible by any later deadline — the extra time can be left
 * unused. Two defects broke that. The final admission gate compared completion to the deadline
 * without the tolerance every other check on the path uses, while the rate is derived to complete
 * exactly at the deadline, so rounding decided admission: 14.9 s and 15.001 s were admitted and
 * 15.0 s and 15.1 s were not. And the rate policy chose from link capacity and contact windows
 * without consulting node buffers, so at longer deadlines it preferred a slower rate that forced
 * the gateway to hold the whole flow across a blackout it had no room for — and never retried the
 * faster rate it had already proven deliverable. Relaxing 80 s to 100 s turned a feasible flow
 * infeasible.
 */
class CalendaredAdmissionMonotonicityTest {

    /** Gateway buffer is deliberately small: holding a whole flow across a blackout must not fit. */
    private static BaseTopology calendaredTopology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_DET);
        topology.addNode(new Node("SRC", "SRC", 1e10, 1e9));
        topology.addNode(new Node("RTR", "RTR", 1e8, 100_000));
        topology.addNode(new Node("DST", "DST", 1e10, 1e9));
        topology.addLink(new Link("SRC-RTR", "SRC", "RTR",
                LinkIntermittencyFunction.persistentLink(1e10, 0.0001)));
        topology.addLink(new Link("RTR-DST", "RTR", "DST",
                LinkIntermittencyFunction.persistentLink(1e8, 0.0001),
                new ContactPlan(List.of(
                        new ContactWindow(0, 20), new ContactWindow(40, 60),
                        new ContactWindow(80, 100), new ContactWindow(120, 140),
                        new ContactWindow(160, 180)))));
        return topology;
    }

    private static boolean admits(double deadlineSec) {
        CRPEngine engine = new CRPEngine().withFGenerate(RouteGenerationPolicy.BOUNDED_CGR);
        return engine.solve(calendaredTopology(),
                List.of(new WorkloadTask("T", "SRC", "DST", 0.0, deadlineSec, 1_000_000)))
                .getCommittedFlowCount() == 1;
    }

    @Test
    void admissionIsMonotoneInTheDeadline() {
        double[] deadlines = {5, 10, 14, 14.5, 14.9, 15, 15.001, 15.1, 16, 18, 20, 25, 40, 60,
                80, 100, 120, 140, 160, 180, 200};
        List<Double> rejectedAfterFirstAccept = new ArrayList<>();
        boolean seenAccept = false;
        for (double deadline : deadlines) {
            boolean admitted = admits(deadline);
            if (admitted) {
                seenAccept = true;
            } else if (seenAccept) {
                rejectedAfterFirstAccept.add(deadline);
            }
        }
        assertTrue(rejectedAfterFirstAccept.isEmpty(),
                "more time must never make a feasible flow infeasible; rejected at "
                        + rejectedAfterFirstAccept);
    }

    @Test
    void theRoundingKnifeEdgeIsGone() {
        // The rate is built to complete exactly at the deadline, so these differ only by which way
        // the last bit rounds.
        for (double deadline : new double[]{14.9, 15.0, 15.001, 15.1}) {
            assertTrue(admits(deadline), "deadline " + deadline + " must be admitted");
        }
    }

    @Test
    void aSlowRateThatOverflowsTheGatewayFallsBackToOneThatDoesNot() {
        // At 100 s the equilibrium rate is link-feasible but forces the gateway to hold 1 MB across
        // the 20-40 s blackout with a 100 KB reservoir. The ceiling rate finishes inside the first
        // contact window instead.
        CRPEngine engine = new CRPEngine().withFGenerate(RouteGenerationPolicy.BOUNDED_CGR);
        CRPEngine.PCEComputationResult result = engine.solve(calendaredTopology(),
                List.of(new WorkloadTask("T", "SRC", "DST", 0.0, 100.0, 1_000_000)));

        assertEquals(1, result.getCommittedFlowCount());
        CRPEngine.CommittedFlowSchedule schedule = result.getCommittedSchedules().get(0);
        assertTrue(schedule.getCompletionTimeSec() <= 20.0,
                "the fallback rate should complete inside the first contact window, not straddle "
                        + "a blackout; completed at " + schedule.getCompletionTimeSec());
        assertTrue(schedule.isMetDeadline());
    }
}
