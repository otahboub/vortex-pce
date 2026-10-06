package net.dcn.pce.crp;

/**
 * Why a workload was not admitted.
 *
 * <p>Refusals were recorded as a bare list of task ids. An operator asking why a flow was declined
 * had no answer, and neither did a benchmark: "refused" collapsed a route that does not exist, a
 * link with no capacity left, and a transit node with no buffer left into one undifferentiated
 * count. Those have different remedies — add a path, add bandwidth, add memory — and only the last
 * is what a rate-pacing policy exists to avoid.
 *
 * <p>Each value names the stage that declined, so the cause points at the decision that produced
 * it rather than at the symptom.
 */
public enum RefusalCause {

    /** F_generate produced no candidate route between the endpoints. */
    NO_CANDIDATE_ROUTE,

    /** F_route declined every candidate it was offered. */
    NO_PATH_SELECTED,

    /** F_prop returned no usable rate for the chosen path. */
    NO_FEASIBLE_RATE,

    /**
     * No transmission slot on some link reaches the deadline.
     *
     * <p>Distinct from {@link #LINK_CAPACITY}: the link may be entirely free and simply not
     * available in time, which is the ordinary case under a contact plan.
     */
    NO_LINK_SLOT,

    /** Residual bandwidth on a link is below the rate the schedule needs. */
    LINK_CAPACITY,

    /**
     * A transit node cannot hold the flow between hops.
     *
     * <p>The reservoir constraint, and the one a rate-pacing policy is meant to relieve: a slower
     * flow occupies a link for longer but arrives in smaller pieces, so pacing trades link
     * occupancy for buffer. Separating this from {@link #LINK_CAPACITY} is what makes that
     * trade measurable instead of asserted.
     */
    NODE_RESERVOIR,

    /** A schedule exists but completes after the effective deadline. */
    DEADLINE_UNREACHABLE
}
