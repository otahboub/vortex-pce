package net.dcn.pce.model;

/**
 * Represents the three contact regimes in flow-aware networking:
 * - R_STATIC: Persistent links, scarcity occurs only under load/oversubscription.
 * - R_DET: Known, deterministic contact plan (e.g. LEO satellite mesh Kuiper-630, scheduled data backup drills).
 * - R_STOCH: Stochastic link availability (disaster-degraded or severely degraded interconnects).
 */
public enum ContactRegime {
    R_STATIC,
    R_DET,
    R_STOCH;

    /** Capacity available while this regime's execution model is in service. */
    public double capacityBps(Link link) {
        return this == R_STOCH
                ? link.getLif().getEffectiveBandwidthBps()
                : link.getLif().getBaseBandwidthBps();
    }
}
