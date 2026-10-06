package net.dcn.pce.model;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Models the Link Intermittency Function (LIF) of an intermittent link e_h.
 * Duty cycle step function triplet: [alpha_h, lambda_h, mu_h].
 */
public class LinkIntermittencyFunction {

    private static final double EPSILON = 1e-9;
    private final double baseBandwidthBps;   // B_h in bps
    private final double propagationDelaySec; // l_h in seconds
    private final double activeContactSec;  // lambda_h in seconds
    private final double inactivePreSec;    // alpha_h in seconds
    private final double inactivePostSec;   // mu_h in seconds

    public LinkIntermittencyFunction(double baseBandwidthBps, double propagationDelaySec,
                                      double activeContactSec, double inactivePreSec, double inactivePostSec) {
        if (!Double.isFinite(baseBandwidthBps) || baseBandwidthBps <= 0
                || !Double.isFinite(propagationDelaySec) || propagationDelaySec < 0
                || !Double.isFinite(activeContactSec) || activeContactSec <= 0
                || !Double.isFinite(inactivePreSec) || inactivePreSec < 0
                || !Double.isFinite(inactivePostSec) || inactivePostSec < 0) {
            throw new IllegalArgumentException("Invalid link intermittency function");
        }
        this.baseBandwidthBps = baseBandwidthBps;
        this.propagationDelaySec = propagationDelaySec;
        this.activeContactSec = activeContactSec;
        this.inactivePreSec = inactivePreSec;
        this.inactivePostSec = inactivePostSec;
    }

    public static LinkIntermittencyFunction persistentLink(double bandwidthBps, double propagationDelaySec) {
        return new LinkIntermittencyFunction(bandwidthBps, propagationDelaySec, 1.0, 0.0, 0.0);
    }

    /**
     * Total time period T_h = alpha_h + lambda_h + mu_h (in seconds).
     */
    public double getTimePeriodT() {
        return inactivePreSec + activeContactSec + inactivePostSec;
    }

    /**
     * Freight size F_h = B_h * lambda_h (amount of data transmitted during active contact in bits).
     */
    public double getFreightSizeBits() {
        return baseBandwidthBps * activeContactSec;
    }

    /**
     * Effective bandwidth beta_h = F_h / T_h (in bps).
     */
    public double getEffectiveBandwidthBps() {
        double period = getTimePeriodT();
        if (period <= 0) return baseBandwidthBps;
        return getFreightSizeBits() / period;
    }

    /**
     * Evaluates whether the link is active at a given wall-clock time t (in seconds).
     */
    public boolean isActiveAt(double tSec) {
        if (!Double.isFinite(tSec) || tSec < 0) {
            throw new IllegalArgumentException("Link activity time must be finite and non-negative");
        }
        double T = getTimePeriodT();
        if (T <= 0 || activeContactSec >= T) return true;
        double modT = tSec % T;
        return modT >= inactivePreSec && modT < (inactivePreSec + activeContactSec);
    }

    /** Returns true when the duty cycle has no inactive portion. */
    public boolean isPersistent() {
        return activeContactSec + EPSILON >= getTimePeriodT();
    }

    /**
     * Returns whether the complete half-open interval [startSec, endSec) lies in
     * one active contact window. Persistent links accept every valid interval.
     */
    public boolean containsActiveInterval(double startSec, double endSec) {
        validateInterval(startSec, endSec);
        if (isPersistent()) {
            return true;
        }

        double periodSec = getTimePeriodT();
        double cycleStartSec = Math.floor(startSec / periodSec) * periodSec;
        double contactStartSec = cycleStartSec + inactivePreSec;
        double contactEndSec = contactStartSec + activeContactSec;
        return startSec + EPSILON >= contactStartSec && endSec <= contactEndSec + EPSILON;
    }

    /**
     * Finds the earliest start whose complete transmission interval lies in one
     * deterministic active window and ends no later than latestEndSec.
     */
    public OptionalDouble findEarliestActiveIntervalStart(
            double earliestStartSec, double latestEndSec, double durationSec) {
        if (!Double.isFinite(earliestStartSec) || earliestStartSec < 0
                || !Double.isFinite(latestEndSec) || latestEndSec <= earliestStartSec
                || !Double.isFinite(durationSec) || durationSec <= 0) {
            throw new IllegalArgumentException("Invalid active-contact interval query");
        }
        if (earliestStartSec + durationSec > latestEndSec + EPSILON) {
            return OptionalDouble.empty();
        }
        if (isPersistent()) {
            return OptionalDouble.of(earliestStartSec);
        }
        if (durationSec > activeContactSec + EPSILON) {
            return OptionalDouble.empty();
        }

        double periodSec = getTimePeriodT();
        double cycleStartSec = Math.floor(earliestStartSec / periodSec) * periodSec;
        double contactStartSec = cycleStartSec + inactivePreSec;
        double contactEndSec = contactStartSec + activeContactSec;
        double candidateSec = Math.max(earliestStartSec, contactStartSec);

        if (candidateSec + durationSec > contactEndSec + EPSILON) {
            contactStartSec += periodSec;
            candidateSec = contactStartSec;
        }
        return candidateSec + durationSec <= latestEndSec + EPSILON
                ? OptionalDouble.of(candidateSec)
                : OptionalDouble.empty();
    }

    /** Returns the active window containing or immediately following earliestSec. */
    public Optional<ContactWindow> findActiveWindowAtOrAfter(
            double earliestSec, double latestEndSec) {
        if (!Double.isFinite(earliestSec) || earliestSec < 0
                || !Double.isFinite(latestEndSec) || latestEndSec <= earliestSec) {
            throw new IllegalArgumentException("Invalid active-contact window query");
        }
        if (isPersistent()) {
            return Optional.of(new ContactWindow(earliestSec, latestEndSec));
        }

        double periodSec = getTimePeriodT();
        double cycleStartSec = Math.floor(earliestSec / periodSec) * periodSec;
        double contactStartSec = cycleStartSec + inactivePreSec;
        double contactEndSec = contactStartSec + activeContactSec;
        if (earliestSec >= contactEndSec - EPSILON) {
            contactStartSec += periodSec;
            contactEndSec += periodSec;
        }
        double windowStartSec = Math.max(earliestSec, contactStartSec);
        double windowEndSec = Math.min(latestEndSec, contactEndSec);
        return windowEndSec > windowStartSec + EPSILON
                ? Optional.of(new ContactWindow(windowStartSec, windowEndSec))
                : Optional.empty();
    }

    private static void validateInterval(double startSec, double endSec) {
        if (!Double.isFinite(startSec) || startSec < 0
                || !Double.isFinite(endSec) || endSec <= startSec) {
            throw new IllegalArgumentException("Invalid link activity interval");
        }
    }

    // Getters
    public double getBaseBandwidthBps() { return baseBandwidthBps; }
    public double getPropagationDelaySec() { return propagationDelaySec; }
    public double getActiveContactSec() { return activeContactSec; }
    public double getInactivePreSec() { return inactivePreSec; }
    public double getInactivePostSec() { return inactivePostSec; }

    @Override
    public String toString() {
        return String.format("LIF[B=%.0f bps, prop=%.3fs, alpha=%.1fs, lambda=%.1fs, mu=%.1fs, T=%.1fs, F=%.0f bits, beta=%.0f bps]",
                baseBandwidthBps, propagationDelaySec, inactivePreSec, activeContactSec, inactivePostSec,
                getTimePeriodT(), getFreightSizeBits(), getEffectiveBandwidthBps());
    }
}
