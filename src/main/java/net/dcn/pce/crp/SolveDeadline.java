package net.dcn.pce.crp;

/**
 * Thread-local carrier for the {@link SolveCancellation} governing the solve on this thread.
 *
 * <p>Checking only between workloads bounds a batch but not a single workload: an unbounded route
 * enumeration on a dense graph can exceed the budget many times over before control returns to
 * the per-workload cancellation point. The planner stages are stateless functional interfaces
 * shared across engines, so the token is carried on the thread rather than threaded through every
 * policy signature.
 *
 * <p>{@link CRPEngine#solve} runs a solve to completion on the calling thread, so a thread-local
 * cannot leak between concurrent solves. The token itself is shared with other threads, which is
 * what lets a shutdown stop a solve already in progress.
 */
public final class SolveDeadline {

    private static final ThreadLocal<SolveDeadline> CURRENT = new ThreadLocal<>();

    /** How often a tight inner loop consults the clock. */
    private static final int CHECK_INTERVAL = 256;

    private final SolveCancellation cancellation;
    private int sinceLastCheck;

    private SolveDeadline(SolveCancellation cancellation) {
        this.cancellation = cancellation;
    }

    /** Installs a token for the current thread. A null token installs nothing. */
    static void install(SolveCancellation cancellation) {
        clear();
        if (cancellation != null) {
            CURRENT.set(new SolveDeadline(cancellation));
        }
    }

    static void clear() {
        CURRENT.remove();
    }

    /**
     * Cancellation point for an expensive planner stage.
     *
     * <p>Safe to call from any loop: with no token installed it is a single thread-local read, and
     * it consults the clock only once every {@value #CHECK_INTERVAL} calls so a hot loop is not
     * dominated by timekeeping.
     *
     * @param stage stage name reported when the solve is stopped
     * @throws SolveTimeoutException when the solve has been cancelled for any reason
     */
    public static void checkpoint(String stage) {
        SolveDeadline current = CURRENT.get();
        if (current == null) {
            return;
        }
        if (++current.sinceLastCheck < CHECK_INTERVAL) {
            return;
        }
        current.sinceLastCheck = 0;
        current.stopIfCancelled(stage);
    }

    /** Checks unconditionally, for loops whose single iteration is already costly. */
    public static void checkpointNow(String stage) {
        SolveDeadline current = CURRENT.get();
        if (current != null) {
            current.stopIfCancelled(stage);
        }
    }

    private void stopIfCancelled(String stage) {
        SolveCancellation.Reason reason = cancellation.cancellationReason();
        if (reason != null) {
            throw new SolveTimeoutException(stage, reason);
        }
    }
}
