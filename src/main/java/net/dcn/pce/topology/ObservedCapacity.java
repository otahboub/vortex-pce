package net.dcn.pce.topology;

import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.LinkIntermittencyFunction;
import net.dcn.pce.model.Node;

/**
 * Replaces one link's assumed capacity with what the network turned out to have.
 *
 * <p>Until now a topology was a fixed description: whatever the operator declared at startup was
 * what every solve believed, for as long as the process ran. That is defensible when capacity is a
 * constant and indefensible when it is not — the variance study measured this engine planning
 * against a mean while the link drifted below it, dropping 1547 packets at 40% coefficient of
 * variation because the plan committed to a rate the link no longer sustained.
 *
 * <p>This is the first half of closing that loop: an observation reaches the model, so the *next*
 * solve reasons about the capacity that exists rather than the one that was declared. The second
 * half — recomputing commitments already made — is deliberately not here. Tearing down or
 * re-rating an LSP a router is already carrying raises questions this controller has not answered:
 * whether a client that was promised a deadline can have it withdrawn, and what happens to traffic
 * mid-flight. Doing the easy half and calling the loop closed would be the wrong claim.
 */
public final class ObservedCapacity {

    private ObservedCapacity() {
    }

    /**
     * Returns a topology identical to {@code topology} except for one link's bandwidth.
     *
     * <p>A new topology rather than a mutation: {@link Link} and {@link LinkIntermittencyFunction}
     * are immutable, and a solve in flight must keep reasoning about the topology it started with
     * rather than have capacity change underneath it.
     *
     * @throws IllegalArgumentException if the link is unknown or the capacity is not usable
     */
    public static BaseTopology withLinkCapacity(
            BaseTopology topology, String linkId, double observedBps) {
        if (linkId == null || linkId.isBlank()) {
            throw new IllegalArgumentException("linkId is required");
        }
        if (!Double.isFinite(observedBps) || observedBps <= 0) {
            throw new IllegalArgumentException("observed capacity must be finite and positive");
        }
        Link existing = topology.getLink(linkId);
        if (existing == null) {
            throw new IllegalArgumentException("unknown link: " + linkId);
        }

        BaseTopology updated = new BaseTopology();
        updated.setRegime(topology.getRegime());
        for (Node node : topology.getNodes()) {
            updated.addNode(node);
        }
        for (Link link : topology.getLinks()) {
            updated.addLink(link.getLinkId().equals(linkId) ? rerated(link, observedBps) : link);
        }
        return updated;
    }

    /**
     * Rebuilds one link at a new bandwidth, preserving everything else about it.
     *
     * <p>Propagation delay, duty-cycle timing and any contact plan are carried across unchanged.
     * Observing that a link is slower says nothing about when it is available, and quietly
     * discarding a contact plan here would turn a calendared link into a persistent one.
     */
    private static Link rerated(Link link, double observedBps) {
        LinkIntermittencyFunction lif = link.getLif();
        LinkIntermittencyFunction rerated = new LinkIntermittencyFunction(
                observedBps,
                lif.getPropagationDelaySec(),
                lif.getActiveContactSec(),
                lif.getInactivePreSec(),
                lif.getInactivePostSec());
        return link.getContactPlan()
                .map(plan -> new Link(link.getLinkId(), link.getSourceNodeId(),
                        link.getDestinationNodeId(), rerated, plan))
                .orElseGet(() -> new Link(link.getLinkId(), link.getSourceNodeId(),
                        link.getDestinationNodeId(), rerated));
    }
}
