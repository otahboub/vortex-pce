package net.dcn.pce.crp;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Cancellation state for one solve, observable from other threads.
 *
 * <p>A deadline alone only answers "has this run too long". It cannot express the other reasons a
 * solve should stop: the server is shutting down, or an operator abandoned the request. Those
 * arrive from a thread other than the one planning, so the signal has to live in a shared object
 * rather than in the planner's own timekeeping.
 *
 * <p>Cancellation stays cooperative. The planner observes this at checkpoints; nothing here
 * forcibly stops running code. What it changes is that a solve now stops for reasons the solve
 * itself cannot see.
 */
public final class SolveCancellation {

    /** Why a solve was asked to stop. */
    public enum Reason {
        DEADLINE("its time budget expired"),
        SHUTDOWN("the controller is shutting down"),
        CLIENT_ABORT("the requesting client went away"),
        EXPLICIT("it was cancelled by an operator");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private final long deadlineNanos;
    private final AtomicReference<Reason> cancelled = new AtomicReference<>();

    private SolveCancellation(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    /** A token bounded by a wall-clock budget; a non-positive budget imposes no deadline. */
    public static SolveCancellation withBudgetNanos(long budgetNanos) {
        return new SolveCancellation(budgetNanos > 0 ? System.nanoTime() + budgetNanos : 0L);
    }

    /** A token with no deadline, cancellable only by an explicit signal. */
    public static SolveCancellation unbounded() {
        return new SolveCancellation(0L);
    }

    /**
     * Requests cancellation. Safe to call from any thread and idempotent: the first reason wins,
     * so a shutdown racing a deadline reports whichever actually stopped the solve.
     */
    public void cancel(Reason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("a cancellation reason is required");
        }
        cancelled.compareAndSet(null, reason);
    }

    /** The reason this solve should stop, or {@code null} while it may continue. */
    public Reason cancellationReason() {
        Reason reason = cancelled.get();
        if (reason != null) {
            return reason;
        }
        if (deadlineNanos > 0 && System.nanoTime() > deadlineNanos) {
            return Reason.DEADLINE;
        }
        // Interruption is how an executor asks a task to stop during shutdown. The planner never
        // checked it, so shutdownNow() previously left a solve running to completion.
        if (Thread.currentThread().isInterrupted()) {
            return Reason.SHUTDOWN;
        }
        return null;
    }

    public boolean isCancelled() {
        return cancellationReason() != null;
    }
}
