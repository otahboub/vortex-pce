package net.dcn.pce.topology;

import java.util.Map;

/**
 * Durable record of observed link capacities, behind an interface so it can be file-backed
 * (single-node) or Postgres-backed (shared, ADR-0001).
 *
 * <p>Why the shared backend matters: capacity observations change what every future solve admits.
 * If they live only beside one replica's state, a promoted standby would plan against the declared
 * topology while the failed leader had been planning against a corrected one — two replicas
 * admitting against different capacities, which is exactly the divergence the shared reservation
 * store exists to prevent. The Postgres implementation puts observations in the same database as the
 * reservations, fenced by the same leadership term, so a new leader restores them with everything
 * else.
 */
public interface CapacityStore {

    /** Records an observation and makes it durable before returning. */
    void record(String linkId, double observedBps, double previousBps, long recordedAtEpochMillis);

    /** Forgets an observation, so the link returns to its declared capacity. */
    void forget(String linkId);

    /** Every recorded observation, keyed by link id. */
    Map<String, ObservedCapacityStore.Observation> all();
}
