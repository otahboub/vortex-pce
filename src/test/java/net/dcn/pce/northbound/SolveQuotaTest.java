package net.dcn.pce.northbound;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One caller must not be able to hold the planner against everyone else.
 *
 * <p>Planning is single-flight, so a client issuing solves back to back can fill its fair queue and
 * consume most of its turns. Authentication does not address that — the client consuming the
 * planner is legitimate — so the bound has to be on how much of the shared resource any one
 * principal may take.
 */
class SolveQuotaTest {

    /** A clock the test moves, so the refill is exercised rather than waited for. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-08-22T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void aCallerSpendingItsAllowanceIsRefused() {
        MovableClock clock = new MovableClock();
        SolveQuota quota = new SolveQuota(3, clock);

        for (int i = 1; i <= 3; i++) {
            assertTrue(quota.claim("alpha").allowed(), "claim " + i + " should be allowed");
        }
        SolveQuota.Decision refused = quota.claim("alpha");
        assertFalse(refused.allowed());
        assertTrue(refused.retryAfterSeconds() >= 1,
                "retry-after must never be zero, or it invites the tight loop this prevents");
    }

    @Test
    void oneCallerExhaustingItsQuotaDoesNotAffectAnother() {
        // The whole point: starvation is per-principal, not global.
        MovableClock clock = new MovableClock();
        SolveQuota quota = new SolveQuota(2, clock);

        assertTrue(quota.claim("noisy").allowed());
        assertTrue(quota.claim("noisy").allowed());
        assertFalse(quota.claim("noisy").allowed());

        assertTrue(quota.claim("quiet").allowed(),
                "a second principal must still be served while the first is over its share");
    }

    @Test
    void theBucketRefillsContinuouslyRatherThanInSteps() {
        // A fixed window would let a caller spend a whole window at the end of one and the next
        // at the start of the following, taking double the rate across the boundary.
        MovableClock clock = new MovableClock();
        SolveQuota quota = new SolveQuota(60, clock);   // one per second

        for (int i = 0; i < 60; i++) {
            assertTrue(quota.claim("alpha").allowed());
        }
        assertFalse(quota.claim("alpha").allowed());

        clock.advance(Duration.ofSeconds(1));
        assertTrue(quota.claim("alpha").allowed(), "one second should restore exactly one solve");
        assertFalse(quota.claim("alpha").allowed(), "and no more than one");
    }

    @Test
    void anIdleCallerAccruesABurstUpToTheLimit() {
        MovableClock clock = new MovableClock();
        SolveQuota quota = new SolveQuota(5, clock);

        for (int i = 0; i < 5; i++) {
            assertTrue(quota.claim("alpha").allowed());
        }
        assertFalse(quota.claim("alpha").allowed());

        clock.advance(Duration.ofHours(1));
        for (int i = 0; i < 5; i++) {
            assertTrue(quota.claim("alpha").allowed(), "an idle caller regains its burst");
        }
        assertFalse(quota.claim("alpha").allowed(), "but not more than the burst");
    }

    @Test
    void idleCallersAreForgotten() {
        // A long-lived controller must not accumulate a bucket per key it has ever seen.
        MovableClock clock = new MovableClock();
        SolveQuota quota = new SolveQuota(5, clock);
        quota.claim("alpha");
        assertEquals(1, quota.trackedPrincipals());

        clock.advance(Duration.ofHours(2));
        quota.evictIdle(Duration.ofHours(1));
        assertEquals(0, quota.trackedPrincipals());
    }
}
