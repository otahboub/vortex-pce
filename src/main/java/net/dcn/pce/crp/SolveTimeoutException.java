package net.dcn.pce.crp;

/**
 * Raised when a solve exceeds its configured wall-clock budget.
 *
 * <p>Planning is single-flight, so an unbounded solve does not degrade throughput — it removes
 * the controller from service entirely, with no recovery short of a restart. This exception is
 * thrown from a cancellation point between workloads so the surrounding transaction rolls the
 * ledgers back to their pre-solve state; a partially applied plan would leave reservations
 * committed for a request that never returned a schedule.
 */
public class SolveTimeoutException extends RuntimeException {

    private final SolveCancellation.Reason reason;
    private final int completedWorkloads;
    private final int totalWorkloads;
    private final long elapsedMillis;

    public SolveTimeoutException(int completedWorkloads, int totalWorkloads, long elapsedMillis) {
        super(String.format(
                "Solve exceeded its time budget after %d ms; %d of %d workloads had been considered. "
                        + "No reservations were committed.",
                elapsedMillis, completedWorkloads, totalWorkloads));
        this.reason = SolveCancellation.Reason.DEADLINE;
        this.completedWorkloads = completedWorkloads;
        this.totalWorkloads = totalWorkloads;
        this.elapsedMillis = elapsedMillis;
    }

    /** Raised from inside a planner stage rather than at a per-workload boundary. */
    public SolveTimeoutException(String stage) {
        this(stage, SolveCancellation.Reason.DEADLINE);
    }

    /**
     * Raised from inside a planner stage, naming why the solve stopped. A shutdown and an
     * exhausted budget are both cancellations, but they need different operator responses, so the
     * message says which one occurred.
     */
    public SolveTimeoutException(String stage, SolveCancellation.Reason reason) {
        super(String.format(
                "Solve stopped during %s because %s. No reservations were committed.",
                stage, reason.description()));
        this.reason = reason;
        this.completedWorkloads = -1;
        this.totalWorkloads = -1;
        this.elapsedMillis = -1L;
    }

    /** Why the solve stopped. */
    public SolveCancellation.Reason getReason() {
        return reason;
    }

    public int getCompletedWorkloads() {
        return completedWorkloads;
    }

    public int getTotalWorkloads() {
        return totalWorkloads;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }
}
