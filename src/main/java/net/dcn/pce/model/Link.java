package net.dcn.pce.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Link representation in base topology G = (N, E).
 * Connects pair of nodes (n_u, n_v) with link intermittency function (LIF).
 */
public class Link {
    private final String linkId;
    private final String sourceNodeId;
    private final String destinationNodeId;
    private final LinkIntermittencyFunction lif;
    private final ContactPlan contactPlan;

    public Link(String linkId, String sourceNodeId, String destinationNodeId, LinkIntermittencyFunction lif) {
        this(linkId, sourceNodeId, destinationNodeId, lif, null);
    }

    public Link(String linkId, String sourceNodeId, String destinationNodeId,
                LinkIntermittencyFunction lif, ContactPlan contactPlan) {
        if (linkId == null || linkId.isBlank() || sourceNodeId == null || sourceNodeId.isBlank()
                || destinationNodeId == null || destinationNodeId.isBlank() || lif == null) {
            throw new IllegalArgumentException("Invalid link definition");
        }
        if (contactPlan != null && !lif.isPersistent()) {
            throw new IllegalArgumentException(
                    "Explicit contact plans cannot be combined with periodic LIF timing");
        }
        this.linkId = linkId.trim();
        this.sourceNodeId = sourceNodeId.trim();
        this.destinationNodeId = destinationNodeId.trim();
        this.lif = lif;
        this.contactPlan = contactPlan;
    }

    public String getLinkId() { return linkId; }
    public String getSourceNodeId() { return sourceNodeId; }
    public String getDestinationNodeId() { return destinationNodeId; }
    public LinkIntermittencyFunction getLif() { return lif; }
    public Optional<ContactPlan> getContactPlan() { return Optional.ofNullable(contactPlan); }
    public boolean hasExplicitContactPlan() { return contactPlan != null; }

    public Optional<ContactWindow> findActiveWindowAtOrAfter(double earliestSec, double latestEndSec) {
        return contactPlan != null
                ? contactPlan.findWindowAtOrAfter(earliestSec, latestEndSec)
                : lif.findActiveWindowAtOrAfter(earliestSec, latestEndSec);
    }

    public boolean containsActiveInterval(double startSec, double endSec) {
        return contactPlan != null
                ? contactPlan.containsInterval(startSec, endSec)
                : lif.containsActiveInterval(startSec, endSec);
    }

    public boolean hasPersistentAvailability() {
        return contactPlan == null && lif.isPersistent();
    }

    /**
     * Success probability of the contact carrying a transmission over [{@code startSec},
     * {@code endSec}). Links without an explicit risk-annotated contact plan are treated as
     * deterministic (1.0), so R_DET / R_STATIC and periodic links are unaffected.
     */
    public double successProbForInterval(double startSec, double endSec) {
        return contactPlan != null ? contactPlan.successProbForInterval(startSec, endSec) : 1.0;
    }

    /** The scheduled contact carrying a transmission over the interval, if this is a contact-plan link. */
    public Optional<ContactWindow> contactWindowForInterval(double startSec, double endSec) {
        return contactPlan != null ? contactPlan.windowForInterval(startSec, endSec) : Optional.empty();
    }

    public double availabilityFraction(double startSec, double endSec) {
        if (!Double.isFinite(startSec) || startSec < 0
                || !Double.isFinite(endSec) || endSec <= startSec) {
            throw new IllegalArgumentException("Invalid link-availability interval");
        }
        if (contactPlan != null) {
            return contactPlan.activeDurationBetween(startSec, endSec) / (endSec - startSec);
        }
        return lif.getActiveContactSec() / lif.getTimePeriodT();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Link link = (Link) o;
        return Objects.equals(linkId, link.linkId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(linkId);
    }

    @Override
    public String toString() {
        return String.format("Link[%s: %s -> %s, %s]", linkId, sourceNodeId, destinationNodeId, lif);
    }
}
