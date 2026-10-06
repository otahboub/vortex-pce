package net.dcn.pce.northbound;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounds how much of the planner any one caller may consume.
 *
 * <p>Planning is single-flight: exactly one solve mutates the reservation ledgers at a time, while
 * admitted concurrent requests wait in the server's bounded fair queue. Fair admission prevents
 * one caller from continuously reacquiring the planner, but it does not bound how much queued work
 * a principal may contribute. Authentication alone does not help when the caller consuming the
 * queue is legitimate.
 *
 * <p>A token bucket per principal, refilled continuously rather than in steps, so a caller that
 * has been quiet accrues a burst it can spend at once and a caller that is hammering settles to
 * the sustained rate. The alternative, a fixed window, lets a client spend a whole window's
 * allowance in the last instant of one window and the next window's in the first instant of the
 * following one.
 *
 * <p>Off unless configured. Every existing deployment predates it, and a quota that silently
 * appears is an outage for whoever was above it.
 */
public final class SolveQuota {

    /** What a caller was told, and why. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static Decision allow() {
            return new Decision(true, 0);
        }
    }

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long nowNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = nowNanos;
        }
    }

    private final double capacity;
    private final double refillPerSecond;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param solvesPerMinute sustained rate per principal; also the burst, so a caller may spend
     *                        a minute's allowance at once after being idle
     */
    public SolveQuota(int solvesPerMinute, Clock clock) {
        if (solvesPerMinute <= 0) {
            throw new IllegalArgumentException("solvesPerMinute must be positive");
        }
        this.capacity = solvesPerMinute;
        this.refillPerSecond = solvesPerMinute / 60.0;
        this.clock = clock;
    }

    /** Charges one solve to {@code principal}, or reports how long until it may retry. */
    public synchronized Decision claim(String principal) {
        long now = clock.instant().toEpochMilli() * 1_000_000L;
        Bucket bucket = buckets.computeIfAbsent(principal, key -> new Bucket(capacity, now));

        double elapsedSeconds = Math.max(0, now - bucket.lastRefillNanos) / 1e9;
        bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
        bucket.lastRefillNanos = now;

        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0;
            return Decision.allow();
        }
        // Rounded up, and never zero: telling a caller to retry in zero seconds invites exactly
        // the tight loop the quota exists to stop.
        double needed = (1.0 - bucket.tokens) / refillPerSecond;
        return new Decision(false, Math.max(1, (long) Math.ceil(needed)));
    }

    /**
     * Forgets idle callers, so a long-lived controller does not accumulate a bucket per key.
     *
     * <p>The token count has to be brought up to date before it is judged. Tokens are only
     * refilled when a caller claims, so an idle bucket still holds whatever it had left when it
     * was last used — reading that stale value meant a depleted bucket was never eligible for
     * eviction and the map grew without bound, which is the opposite of what this method is for.
     * A bucket that would be full if refilled is indistinguishable from one that never existed.
     */
    public synchronized void evictIdle(Duration idleFor) {
        long now = clock.instant().toEpochMilli() * 1_000_000L;
        long cutoff = now - idleFor.toNanos();
        buckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            if (bucket.lastRefillNanos >= cutoff) {
                return false;
            }
            double elapsedSeconds = Math.max(0, now - bucket.lastRefillNanos) / 1e9;
            return bucket.tokens + elapsedSeconds * refillPerSecond >= capacity;
        });
    }

    int trackedPrincipals() {
        return buckets.size();
    }
}
