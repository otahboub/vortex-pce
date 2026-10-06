package net.dcn.pce.model;

import java.util.AbstractList;
import java.util.List;

/**
 * A route whose hops may be pinned to depart no earlier than a given time.
 *
 * <p>Scheduling places each hop in the earliest feasible contact window after the previous hop's
 * arrival, so a plain link sequence can only ever use the earliest pass of each link. Pinning a hop to a
 * later time lets a route generator target a specific later contact window — time diversity, which is
 * how redundancy can use a later pass of the same link. An unpinned hop (0.0) behaves exactly as in a
 * plain route, so a route with no pins schedules identically to its link list.
 *
 * <p>List equality is element-wise, as for any {@link List}; two pinned routes over the same links but
 * different pins compare equal. Code that must distinguish candidates compares them by identity.
 */
public final class PinnedRoute extends AbstractList<Link> {

    private final List<Link> links;
    private final double[] notBeforeSec;

    public PinnedRoute(List<Link> links, double[] notBeforeSec) {
        if (links == null || notBeforeSec == null || links.size() != notBeforeSec.length) {
            throw new IllegalArgumentException("a pin is required for every hop");
        }
        for (double pin : notBeforeSec) {
            if (!Double.isFinite(pin) || pin < 0) {
                throw new IllegalArgumentException("pins must be finite and non-negative");
            }
        }
        this.links = List.copyOf(links);
        this.notBeforeSec = notBeforeSec.clone();
    }

    @Override
    public Link get(int index) {
        return links.get(index);
    }

    @Override
    public int size() {
        return links.size();
    }

    /** Earliest departure for {@code hop} of {@code route}: its pin, or 0 for a plain route. */
    public static double notBefore(List<Link> route, int hop) {
        return route instanceof PinnedRoute pinned ? pinned.notBeforeSec[hop] : 0.0;
    }
}
