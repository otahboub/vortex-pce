package net.dcn.pce.model;

/**
 * A half-open contact interval [startSec, endSec).
 *
 * <p>{@code successProb} is the probability that the contact actually occurs as scheduled (R_STOCH).
 * It defaults to {@code 1.0} — a deterministic contact — so every R_DET / R_STATIC window and every
 * existing two-argument construction is unchanged. Only R_STOCH contact plans that carry a measured or
 * forecast per-contact failure probability set it below 1.0; the offline PCE consumes this value as an
 * abstract input and never models the physics that produced it.
 */
public record ContactWindow(double startSec, double endSec, double successProb) {
    public ContactWindow {
        if (!Double.isFinite(startSec) || startSec < 0
                || !Double.isFinite(endSec) || endSec <= startSec) {
            throw new IllegalArgumentException("Invalid contact window");
        }
        if (!Double.isFinite(successProb) || successProb < 0.0 || successProb > 1.0) {
            throw new IllegalArgumentException("Contact successProb must be in [0,1]");
        }
    }

    /** Deterministic contact ({@code successProb = 1.0}) — the R_DET / R_STATIC default. */
    public ContactWindow(double startSec, double endSec) {
        this(startSec, endSec, 1.0);
    }
}
