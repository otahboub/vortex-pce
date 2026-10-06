package net.dcn.pce.rib;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives a {@link PostgresLeadership} lease over time and promotes/demotes the controller with it
 * (ADR-0001, action 5). This is what turns a one-shot "took the lease at startup" into automatic
 * failover: the leader keeps the lease alive with a heartbeat, and the instant it cannot, it demotes
 * — closing the PCEP listener and standing down — so a standby that acquires the lapsed lease is the
 * only one acting.
 *
 * <p>Correctness over availability. The heartbeat runs every {@code heartbeatMillis}, well inside the
 * lease TTL, so an ordinary scheduling hiccup does not lose leadership; but a leader that is
 * partitioned or stalled stops renewing, its lease lapses after the TTL, and a successor cannot
 * appear before then. {@link #onDemote} runs on the very tick that discovers the loss, before any
 * successor can have taken over, so the windows do not overlap. A standby polls the same way and
 * calls {@link #onPromote} only once it holds the lease.
 *
 * <p>{@code onPromote} and {@code onDemote} must be idempotent and quick; long work belongs off the
 * scheduler thread.
 */
public final class LeadershipLifecycle implements AutoCloseable {

    private static final Logger log = Logger.getLogger(LeadershipLifecycle.class.getName());

    private final PostgresLeadership leadership;
    private final long heartbeatMillis;
    private final Runnable onPromote;
    private final Runnable onDemote;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean promoted = new AtomicBoolean(false);
    private volatile String lastDemotionReason;

    /**
     * A point-in-time view of this instance's leadership, for a cluster-status endpoint. The expiry
     * is the database-authoritative one; {@code leaseLocallyLive} is the conservative local guard.
     */
    public record LeadershipStatus(String instanceId, long term, boolean leader,
                                   boolean leaseLocallyLive, java.time.Instant leaseExpiry,
                                   String lastDemotionReason) {
    }

    public LeadershipLifecycle(PostgresLeadership leadership, long heartbeatMillis,
                               Runnable onPromote, Runnable onDemote) {
        this.leadership = leadership;
        this.heartbeatMillis = heartbeatMillis;
        this.onPromote = onPromote;
        this.onDemote = onDemote;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "vortex-leadership");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Attempts leadership immediately, then keeps it (or keeps trying) on the heartbeat. */
    public void start() {
        tick();
        scheduler.scheduleWithFixedDelay(this::tick, heartbeatMillis, heartbeatMillis,
                TimeUnit.MILLISECONDS);
    }

    public boolean isLeader() {
        return promoted.get();
    }

    /**
     * The guard every externally visible action — admission and PCEP dispatch — must pass. Stricter
     * than {@link #isLeader()}: it also requires the lease to be live by the conservative local
     * monotonic deadline, so an instance that was promoted but has since been paused past its lease
     * (while a successor took over) fails closed immediately, without waiting for the next heartbeat
     * to observe the loss. This is the resume-after-pause safety barrier for side effects that the
     * database-fenced writes alone do not cover.
     */
    public boolean leaseLocallyLive() {
        return promoted.get() && leadership.leaseLocallyLive();
    }

    /** A snapshot of leadership state for diagnostics / a cluster-status endpoint. */
    public LeadershipStatus status() {
        return new LeadershipStatus(
                leadership.instanceId(), leadership.term(), promoted.get(),
                leaseLocallyLive(), leadership.leaseExpiry(), lastDemotionReason);
    }

    private synchronized void tick() {
        try {
            boolean holds = promoted.get() ? leadership.renew() : leadership.tryBecomeLeader();
            if (holds && promoted.compareAndSet(false, true)) {
                log.info("Promoted to leader at term " + leadership.term());
                runCallback(onPromote, "promote");
            } else if (!holds && promoted.compareAndSet(true, false)) {
                log.warning("Lost leadership; demoting and standing down");
                lastDemotionReason = "lease-lost";
                runCallback(onDemote, "demote");
            }
        } catch (RuntimeException e) {
            // A database blip must not crash the scheduler. Treat an error while leading as a loss
            // -- the safe direction -- so the listener closes rather than serving on stale state.
            log.log(Level.WARNING, "Leadership heartbeat failed", e);
            if (promoted.compareAndSet(true, false)) {
                lastDemotionReason = "heartbeat-error";
                runCallback(onDemote, "demote");
            }
        }
    }

    private void runCallback(Runnable callback, String name) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Leadership " + name + " callback threw", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        if (promoted.compareAndSet(true, false)) {
            lastDemotionReason = "graceful-shutdown";
            runCallback(onDemote, "demote");
        }
        // Graceful path only: hand the lease back so a successor promotes at once rather than waiting
        // out the TTL. Conditional on still owning it, so it never disturbs a lease already taken over.
        // A crash never reaches here and falls back to TTL expiry -- the same safety, more slowly.
        leadership.releaseIfHeld();
    }
}
