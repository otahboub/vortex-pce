package net.dcn.pce.crp.policy;

import net.dcn.pce.crp.SchedulingCapacity;
import net.dcn.pce.model.*;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.rib.NRIB;

import java.util.*;

/**
 * Stage 2 (F_generate): Route Generation / Enumeration Policy Interface.
 * Generates candidate routes between task source u and destination v.
 */
@FunctionalInterface
public interface RouteGenerationPolicy {

    int BOUNDED_CGR_MAX_ROUTES = 20;
    int BOUNDED_CGR_MAX_HOPS = 12;
    int BOUNDED_CGR_MAX_LABEL_EXPANSIONS = 10_000;

    List<List<Link>> generateCandidateRoutes(WorkloadTask task, BaseTopology topology, LRIB lrib, NRIB nrib);

    // Built-in Policy: breadth-first minimum-hop path (single path K=1).
    RouteGenerationPolicy BFS_MIN_HOP = (task, topology, lrib, nrib) -> {
        List<Link> path = findMinimumHopPath(task.getSourceNodeId(), task.getDestinationNodeId(), topology);
        if (path.isEmpty()) return Collections.emptyList();
        return Collections.singletonList(path);
    };

    /** @deprecated Use {@link #BFS_MIN_HOP}; all graph edges have unit hop cost. */
    @Deprecated
    RouteGenerationPolicy DIJKSTRA_MIN_HOP = BFS_MIN_HOP;

    // Built-in Policy: Fast BFS Multi-Path Generator (K=5 candidate paths, max depth 12)
    RouteGenerationPolicy FAST_K_SHORTEST_PATHS = (task, topology, lrib, nrib) -> {
        List<List<Link>> result = new ArrayList<>();
        Queue<List<Link>> queue = new LinkedList<>();

        String src = task.getSourceNodeId();
        String dst = task.getDestinationNodeId();

        for (Link link : topology.getOutgoingLinks(src)) {
            List<Link> initialPath = new ArrayList<>();
            initialPath.add(link);
            queue.add(initialPath);
        }

        while (!queue.isEmpty() && result.size() < 5) {
            net.dcn.pce.crp.SolveDeadline.checkpoint("k-shortest path search");
            List<Link> currentPath = queue.poll();
            if (currentPath.size() > 12) continue; // Max hop depth 12

            Link lastLink = currentPath.get(currentPath.size() - 1);
            String current = lastLink.getDestinationNodeId();

            if (current.equals(dst)) {
                result.add(currentPath);
                continue;
            }

            Set<String> visited = new HashSet<>();
            visited.add(src);
            for (Link l : currentPath) {
                visited.add(l.getDestinationNodeId());
            }

            for (Link nextLink : topology.getOutgoingLinks(current)) {
                String nextNode = nextLink.getDestinationNodeId();
                if (!visited.contains(nextNode)) {
                    List<Link> newPath = new ArrayList<>(currentPath);
                    newPath.add(nextLink);
                    queue.add(newPath);
                }
            }
        }

        if (result.isEmpty()) {
            List<Link> minimumHopPath = findMinimumHopPath(src, dst, topology);
            if (!minimumHopPath.isEmpty()) result.add(minimumHopPath);
        }

        return result;
    };

    // Built-in Policy: bounded simple-path enumeration (K=20).
    RouteGenerationPolicy BOUNDED_PATH_ENUMERATION = (task, topology, lrib, nrib) -> {
        List<List<Link>> candidatePaths = new ArrayList<>();
        Set<String> visitedNodes = new HashSet<>();
        List<Link> currentPath = new ArrayList<>();
        visitedNodes.add(task.getSourceNodeId());

        dfsPathEnumeration(task.getSourceNodeId(), task.getDestinationNodeId(), topology,
                           visitedNodes, currentPath, candidatePaths, 20); // Bounded K=20
        return candidatePaths;
    };

    /**
     * @deprecated This enumerator does not evaluate contact windows or the Ordered Connection Criterion.
     */
    @Deprecated
    RouteGenerationPolicy OCC_PATH_ENUMERATION = BOUNDED_PATH_ENUMERATION;

    /**
     * Bounded contact-graph routing. Partial paths are expanded in earliest
     * achievable arrival order using explicit/periodic contacts and residual
     * LRIB capacity. The search is loop-free and capped at 20 routes, 12 hops,
     * and 10,000 label expansions.
     *
     * <p>Each extension is judged at the best residual rate any instant of the task's window offers.
     * A partly used contact may offer that rate only briefly and a lower one for long enough, so when
     * that search finds no route at all, it is repeated letting each extension fall back to a lower
     * rate (see {@link SchedulingCapacity#fastestSchedulableRateBps}). Only tasks that would otherwise
     * have no candidate route are affected; the retry costs nothing for the rest.
     *
     * <p>Labels are loop-free paths, not nodes, so a well-connected part of the graph reached early
     * (e.g. an always-on relay chain) yields exponentially many partial paths that all arrive before
     * any route to the destination; they can use up the expansion budget before one is found. A task
     * still without a candidate route is therefore searched once more expanding each node at most
     * {@link #BOUNDED_CGR_MAX_ROUTES} times, in arrival order, which bounds the search by the node
     * count rather than the path count. It too only runs when the searches above found nothing.
     */
    RouteGenerationPolicy BOUNDED_CGR = (task, topology, lrib, nrib) -> {
        List<List<Link>> routes = generateBoundedCgr(task, topology, lrib, false, Integer.MAX_VALUE);
        if (routes.isEmpty()) {
            routes = generateBoundedCgr(task, topology, lrib, true, Integer.MAX_VALUE);
        }
        return routes.isEmpty()
                ? generateBoundedCgr(task, topology, lrib, true, BOUNDED_CGR_MAX_ROUTES)
                : routes;
    };

    /**
     * Disjoint-maximal contact-graph routing: successive earliest-arrival temporal paths, removing the
     * scheduled contacts each one uses before finding the next. This surfaces up to the temporal min-cut
     * of contact-DISJOINT source->destination routes (which earliest-arrival {@link #BOUNDED_CGR}
     * does not — its best-by-arrival candidates overlap on the fastest shared contacts). Paired with
     * R_STOCH risk-aware admission it lets the engine commit independent backups up to the min-cut, so
     * union survival can approach the connectivity oracle. Contact-scheduled regimes only.
     */
    RouteGenerationPolicy DISJOINT_CGR = (task, topology, lrib, nrib) -> {
        if ((topology.getRegime() != ContactRegime.R_DET
                && topology.getRegime() != ContactRegime.R_STOCH)
                || task.getSourceNodeId().equals(task.getDestinationNodeId())) {
            return List.of();
        }
        Set<String> usedContacts = new HashSet<>();
        CapacityFilter capacity = capacityFilter(task, topology, lrib);
        List<List<Link>> routes = new ArrayList<>();
        for (int k = 0; k < BOUNDED_CGR_MAX_ROUTES; k++) {
            net.dcn.pce.crp.SolveDeadline.checkpoint("disjoint CGR round");
            DisjointPath path = earliestArrivalDisjoint(task, topology, usedContacts, capacity);
            if (path == null) {
                break;
            }
            routes.add(path.links());
            usedContacts.addAll(path.contactKeys());
        }
        return routes;
    };

    int CUT_ANCHORED_MAX_ROUTES = 64;

    /**
     * Terminal-cut-anchored contact-graph routing. When the bottleneck is the set of links into the
     * destination (its terminal cut), whole disjoint routes spend redundancy in the wrong place: each
     * gets one fragile upstream chain per terminal link. This policy instead round-robins over the
     * terminal links and, each round, adds one more upstream path into each terminal link's relay;
     * upstream paths are contact-disjoint from one another (across all terminal links), while a
     * terminal link may carry several upstream paths. Up to {@code VORTEX_CUT_UPSTREAM} (default 8)
     * upstream paths per terminal link, {@link #CUT_ANCHORED_MAX_ROUTES} in total. Pair with R_STOCH
     * shared-terminal admission ({@code VORTEX_STOCH_SHARED_TERMINAL=true}), whose survival expression
     * is exact for exactly this structure. Contact-scheduled regimes only.
     */
    RouteGenerationPolicy CUT_ANCHORED_CGR = (task, topology, lrib, nrib) -> {
        if ((topology.getRegime() != ContactRegime.R_DET
                && topology.getRegime() != ContactRegime.R_STOCH)
                || task.getSourceNodeId().equals(task.getDestinationNodeId())) {
            return List.of();
        }
        String src = task.getSourceNodeId();
        String dst = task.getDestinationNodeId();
        double origin = task.getOriginationTimeSec();
        double horizon = task.getEffectiveDeadlineSec();
        List<Link> terminals = topology.getLinks().stream()
                .filter(link -> link.getDestinationNodeId().equals(dst) && link.hasExplicitContactPlan())
                .sorted(Comparator.comparing(Link::getLinkId))
                .toList();
        int perTerminal = cutAnchoredUpstreamPerTerminal();
        Set<String> usedUpstream = new HashSet<>();
        CapacityFilter capacity = capacityFilter(task, topology, lrib);
        List<List<Link>> routes = new ArrayList<>();
        for (int round = 0; round < perTerminal; round++) {
            boolean progressed = false;
            for (Link terminal : terminals) {
                if (routes.size() >= CUT_ANCHORED_MAX_ROUTES) {
                    return routes;
                }
                net.dcn.pce.crp.SolveDeadline.checkpoint("cut-anchored CGR round");
                String relay = terminal.getSourceNodeId();
                List<Link> upstream;
                List<String> upstreamContacts;
                double arrivalSec;
                if (relay.equals(src)) {
                    if (round > 0) {
                        continue; // a direct terminal link is a single route
                    }
                    upstream = List.of();
                    upstreamContacts = List.of();
                    arrivalSec = origin;
                } else {
                    DisjointPath path = earliestArrivalDisjoint(
                            src, relay, origin, horizon, topology, usedUpstream, Set.of(dst), capacity);
                    if (path == null) {
                        continue;
                    }
                    upstream = path.links();
                    upstreamContacts = path.contactKeys();
                    arrivalSec = path.arrivalSec();
                }
                if (arrivalSec >= horizon
                        || terminal.findActiveWindowAtOrAfter(arrivalSec, horizon).isEmpty()) {
                    continue; // reached the relay too late to use its terminal contact
                }
                List<Link> route = new ArrayList<>(upstream);
                route.add(terminal);
                routes.add(List.copyOf(route));
                usedUpstream.addAll(upstreamContacts);
                progressed = true;
            }
            if (!progressed) {
                break;
            }
        }
        return routes;
    };

    int MESH_MAX_ROUTES = 4096;

    /**
     * Frontier-walking contact-graph routing: the knob between a cheap plan and the oracle.
     *
     * <p>Delivery probability is monotone in the set of committed contacts, and the plan that commits
     * every contact on some source-to-destination path is flooding, i.e. the connectivity oracle. This
     * policy walks that frontier by level ({@code VORTEX_MESH_LEVEL}):
     * <ul>
     *   <li>{@code 0}: the {@link #CUT_ANCHORED_CGR} routes.</li>
     *   <li>{@code b} (1..16): additionally, at every hop of each base route, up to {@code b} detours —
     *       another contact out of the same node, either to a different next hop or a later pass of the
     *       same link (a pinned hop) — each followed by the earliest onward path to the destination.
     *       Detours never revisit a node already on the path.</li>
     *   <li>{@code ALL}: one pinned route through every contact that lies on some source-to-destination
     *       path: the flooding plan, whose delivery probability is the oracle's.</li>
     * </ul>
     * Routes overlap, so pair it with Monte-Carlo survival admission
     * ({@code VORTEX_STOCH_SURVIVAL=MONTE_CARLO}); the exact ledgers reject overlapping routes. At most
     * {@link #MESH_MAX_ROUTES} candidates ({@code VORTEX_MESH_MAX_ROUTES}). Contact-scheduled regimes only.
     */
    RouteGenerationPolicy MESH_CGR = (task, topology, lrib, nrib) ->
            meshCgr(System.getenv().getOrDefault("VORTEX_MESH_LEVEL", "0"),
                    envInt("VORTEX_MESH_MAX_ROUTES", MESH_MAX_ROUTES, 1, 1 << 16))
                    .generateCandidateRoutes(task, topology, lrib, nrib);

    /** {@link #MESH_CGR} at an explicit level ("0".."16" or "ALL") and candidate cap. */
    static RouteGenerationPolicy meshCgr(String level, int maxRoutes) {
        String normalized = level == null ? "0" : level.trim().toUpperCase(Locale.ROOT);
        return (task, topology, lrib, nrib) -> {
            if ((topology.getRegime() != ContactRegime.R_DET
                    && topology.getRegime() != ContactRegime.R_STOCH)
                    || task.getSourceNodeId().equals(task.getDestinationNodeId())) {
                return List.of();
            }
            CapacityFilter capacity = capacityFilter(task, topology, lrib);
            if ("ALL".equals(normalized)) {
                return floodingPlan(task, topology, capacity, maxRoutes);
            }
            int detours;
            try {
                detours = Math.max(0, Math.min(16, Integer.parseInt(normalized)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("VORTEX_MESH_LEVEL must be 0..16 or ALL, got " + level);
            }
            List<List<Link>> base = CUT_ANCHORED_CGR.generateCandidateRoutes(task, topology, lrib, nrib);
            if (detours == 0 || base.isEmpty()) {
                return base;
            }
            List<List<Link>> routes = new ArrayList<>(base);
            Set<String> seen = new HashSet<>();
            for (List<Link> route : base) {
                seen.add(routeSignature(route));
            }
            for (List<Link> route : base) {
                if (routes.size() >= maxRoutes) {
                    break;
                }
                addDetours(task, topology, capacity, route, detours, routes, seen, maxRoutes);
            }
            return routes;
        };
    }

    /** A contact out of a node to try instead of the one a base route uses. */
    record Detour(Link link, double departureSec) {}

    private static void addDetours(WorkloadTask task, BaseTopology topology, CapacityFilter capacity,
                                   List<Link> base, int perHop, List<List<Link>> routes,
                                   Set<String> seen, int maxRoutes) {
        double horizon = task.getEffectiveDeadlineSec();
        double arriveSec = task.getOriginationTimeSec();
        Set<String> onPath = new HashSet<>(Set.of(task.getSourceNodeId()));
        for (int hop = 0; hop < base.size(); hop++) {
            net.dcn.pce.crp.SolveDeadline.checkpoint("mesh detours");
            Link used = base.get(hop);
            String node = used.getSourceNodeId();
            double atNode = Math.max(arriveSec, PinnedRoute.notBefore(base, hop));
            if (atNode >= horizon) {
                return;
            }
            Optional<ContactWindow> window = used.findActiveWindowAtOrAfter(atNode, horizon);
            if (window.isEmpty()) {
                return;
            }
            List<Detour> options = new ArrayList<>();
            double usedEnd = window.get().endSec();
            if (usedEnd < horizon) { // a later pass of the same link
                used.findActiveWindowAtOrAfter(usedEnd, horizon)
                        .ifPresent(later -> options.add(new Detour(used, later.startSec())));
            }
            for (Link other : topology.getOutgoingLinks(node)) { // a different next hop
                if (other.equals(used) || other.getContactPlan().isEmpty()
                        || onPath.contains(other.getDestinationNodeId())) {
                    continue;
                }
                other.findActiveWindowAtOrAfter(atNode, horizon)
                        .ifPresent(w -> options.add(new Detour(other, w.startSec())));
            }
            options.sort(Comparator.comparingDouble(Detour::departureSec)
                    .thenComparing(d -> d.link().getLinkId()));
            int added = 0;
            for (Detour option : options) {
                if (added >= perHop || routes.size() >= maxRoutes) {
                    break;
                }
                List<Link> route = routeThrough(task, topology, capacity, base.subList(0, hop), onPath,
                        option.link(), option.departureSec());
                if (route != null && seen.add(routeSignature(route))) {
                    routes.add(route);
                    added++;
                }
            }
            arriveSec = window.get().startSec() + used.getLif().getPropagationDelaySec();
            onPath.add(used.getDestinationNodeId());
        }
    }

    /**
     * {@code prefix} (ending at the contact's source), then the contact pinned at {@code departureSec},
     * then the earliest onward path to the destination avoiding {@code onPath}; null if none exists.
     */
    private static List<Link> routeThrough(WorkloadTask task, BaseTopology topology, CapacityFilter capacity,
                                           List<Link> prefix, Set<String> onPath, Link contact,
                                           double departureSec) {
        String dst = task.getDestinationNodeId();
        String next = contact.getDestinationNodeId();
        double horizon = task.getEffectiveDeadlineSec();
        List<Link> links = new ArrayList<>(prefix);
        links.add(contact);
        if (!next.equals(dst)) {
            double arrive = departureSec + contact.getLif().getPropagationDelaySec();
            if (arrive >= horizon) {
                return null;
            }
            Set<String> forbidden = new HashSet<>(onPath);
            forbidden.add(contact.getSourceNodeId());
            DisjointPath onward = earliestArrivalDisjoint(next, dst, arrive, horizon, topology,
                    Set.of(), forbidden, capacity);
            if (onward == null) {
                return null;
            }
            links.addAll(onward.links());
        }
        double[] pins = new double[links.size()];
        pins[prefix.size()] = departureSec;
        return new PinnedRoute(links, pins);
    }

    /**
     * The flooding plan: one pinned route through every contact that lies on some source-to-destination
     * path (earliest path to the contact's source, the contact, earliest onward path), in departure order.
     */
    private static List<List<Link>> floodingPlan(WorkloadTask task, BaseTopology topology,
                                                 CapacityFilter capacity, int maxRoutes) {
        String src = task.getSourceNodeId();
        String dst = task.getDestinationNodeId();
        double horizon = task.getEffectiveDeadlineSec();
        TemporalSearch tree = temporalSearch(src, null, task.getOriginationTimeSec(), horizon, topology,
                Set.of(), Set.of(), capacity);
        record Candidate(double departureSec, String linkId, List<Link> route) {}
        List<Candidate> candidates = new ArrayList<>();
        for (Link link : topology.getLinks()) {
            net.dcn.pce.crp.SolveDeadline.checkpoint("flooding plan");
            String u = link.getSourceNodeId();
            String v = link.getDestinationNodeId();
            Double reachU = tree.arrival().get(u);
            if (reachU == null || link.getContactPlan().isEmpty() || v.equals(src)) {
                continue;
            }
            List<Link> prefix = u.equals(src) ? List.of() : tree.pathTo(u).links();
            Set<String> onPath = new HashSet<>(Set.of(src));
            for (Link l : prefix) {
                onPath.add(l.getDestinationNodeId());
            }
            if (onPath.contains(v)) {
                continue;
            }
            for (ContactWindow window : link.getContactPlan().get().getWindows()) {
                if (window.endSec() <= reachU) {
                    continue;
                }
                double departure = Math.max(reachU, window.startSec());
                if (departure >= horizon) {
                    break;
                }
                List<Link> route = routeThrough(task, topology, capacity, prefix, onPath, link, departure);
                if (route != null) {
                    candidates.add(new Candidate(departure, link.getLinkId(), route));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::departureSec)
                .thenComparing(Candidate::linkId));
        List<List<Link>> routes = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (routes.size() >= maxRoutes) {
                break;
            }
            routes.add(candidate.route());
        }
        return routes;
    }

    private static String routeSignature(List<Link> route) {
        StringBuilder signature = new StringBuilder();
        for (int hop = 0; hop < route.size(); hop++) {
            signature.append(route.get(hop).getLinkId()).append('@')
                    .append(PinnedRoute.notBefore(route, hop)).append('>');
        }
        return signature.toString();
    }

    private static int envInt(String name, int fallback, int min, int max) {
        String value = System.getenv(name);
        if (value != null) {
            try {
                return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return fallback;
    }

    // Built-in Policy: K = MAX Exhaustive DFS/BFS Graph Traversal
    RouteGenerationPolicy K_MAX_EXHAUSTIVE = (task, topology, lrib, nrib) -> {
        List<List<Link>> candidatePaths = new ArrayList<>();
        Set<String> visitedNodes = new HashSet<>();
        List<Link> currentPath = new ArrayList<>();
        visitedNodes.add(task.getSourceNodeId());

        dfsPathEnumeration(task.getSourceNodeId(), task.getDestinationNodeId(), topology,
                           visitedNodes, currentPath, candidatePaths, Integer.MAX_VALUE);
        return candidatePaths;
    };

    /**
     * A disjoint route: its links, the "linkId@windowStart" keys of the contacts it occupies, and its
     * earliest arrival time at the target.
     */
    record DisjointPath(List<Link> links, List<String> contactKeys, double arrivalSec) {}

    /**
     * Optional capacity filter for route generation ({@code VORTEX_GEN_CAPACITY_AWARE}): a contact is
     * only usable if its residual LRIB capacity can carry the whole task before the contact closes, and
     * arrival then includes the transfer time. Without it, generation assumes every contact is free.
     */
    record CapacityFilter(LRIB lrib, ContactRegime regime, double taskBits) {}

    /** Result of a temporal search: earliest arrival per node and the contact that achieved it. */
    record TemporalSearch(Map<String, Double> arrival, Map<String, Link> viaLink,
                          Map<String, String> viaPrev, Map<String, String> viaContact) {

        /** The earliest-arrival path to {@code target}, or null if unreachable. */
        DisjointPath pathTo(String target) {
            if (!viaLink.containsKey(target)) {
                return null;
            }
            LinkedList<Link> links = new LinkedList<>();
            List<String> contacts = new ArrayList<>();
            String cur = target;
            while (viaLink.containsKey(cur)) {
                links.addFirst(viaLink.get(cur));
                contacts.add(viaContact.get(cur));
                cur = viaPrev.get(cur);
            }
            return new DisjointPath(links, contacts, arrival.get(target));
        }
    }

    /**
     * Earliest-arrival temporal search (store-and-forward) from {@code src} at {@code origin}, over
     * scheduled contact windows, never using an {@code excluded} contact or entering a {@code forbidden}
     * node. Stops once {@code stopAt} is settled (null explores everything). Contact keys are
     * "linkId@windowStart", matching {@code StochasticAdmission}. Contact-plan links only.
     */
    private static TemporalSearch temporalSearch(
            String src, String stopAt, double origin, double horizon, BaseTopology topology,
            Set<String> excluded, Set<String> forbidden, CapacityFilter capacity) {
        Map<String, Double> bestArrival = new HashMap<>();
        Map<String, Link> viaLink = new HashMap<>();
        Map<String, String> viaPrev = new HashMap<>();
        Map<String, String> viaContact = new HashMap<>();
        bestArrival.put(src, origin);
        PriorityQueue<Map.Entry<String, Double>> frontier =
                new PriorityQueue<>(Comparator.comparingDouble(Map.Entry::getValue));
        frontier.add(Map.entry(src, origin));

        while (!frontier.isEmpty()) {
            net.dcn.pce.crp.SolveDeadline.checkpoint("temporal route search");
            Map.Entry<String, Double> top = frontier.poll();
            String u = top.getKey();
            double t = top.getValue();
            if (t > bestArrival.getOrDefault(u, Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (u.equals(stopAt)) {
                break;
            }
            for (Link link : topology.getOutgoingLinks(u)) {
                if (link.getContactPlan().isEmpty()) {
                    continue;
                }
                double owlt = link.getLif().getPropagationDelaySec();
                String v = link.getDestinationNodeId();
                if (forbidden.contains(v)) {
                    continue;
                }
                for (ContactWindow window : link.getContactPlan().get().getWindows()) {
                    String key = link.getLinkId() + '@' + window.startSec();
                    if (excluded.contains(key)) {
                        continue;
                    }
                    double departure = Math.max(t, window.startSec());
                    if (departure >= window.endSec()) {
                        continue; // t is past this window; try a later one on the same link
                    }
                    if (departure > horizon) {
                        break; // later windows are only further out
                    }
                    double transferSec = 0.0;
                    if (capacity != null) {
                        double residual = capacity.lrib().getMaximumAvailableCap(link.getLinkId(),
                                SchedulingCapacity.capacityBps(link, capacity.regime()),
                                departure, window.endSec());
                        if (residual <= 0 || departure + capacity.taskBits() / residual > window.endSec()) {
                            continue; // this contact cannot carry the task; a later one may
                        }
                        transferSec = capacity.taskBits() / residual;
                    }
                    double arrival = departure + transferSec + owlt;
                    if (arrival <= horizon && arrival < bestArrival.getOrDefault(v, Double.POSITIVE_INFINITY)) {
                        bestArrival.put(v, arrival);
                        viaLink.put(v, link);
                        viaPrev.put(v, u);
                        viaContact.put(v, key);
                        frontier.add(Map.entry(v, arrival));
                    }
                    break; // earliest usable window realises the earliest arrival via this link
                }
            }
        }
        return new TemporalSearch(bestArrival, viaLink, viaPrev, viaContact);
    }

    /** Earliest-arrival path for the task avoiding {@code excluded} contacts (DISJOINT_CGR). */
    private static DisjointPath earliestArrivalDisjoint(
            WorkloadTask task, BaseTopology topology, Set<String> excluded, CapacityFilter capacity) {
        return temporalSearch(task.getSourceNodeId(), task.getDestinationNodeId(),
                task.getOriginationTimeSec(), task.getEffectiveDeadlineSec(), topology, excluded,
                Set.of(), capacity).pathTo(task.getDestinationNodeId());
    }

    /** As above, to an explicit {@code dst}, never entering any {@code forbidden} node. */
    private static DisjointPath earliestArrivalDisjoint(
            String src, String dst, double origin, double horizon, BaseTopology topology,
            Set<String> excluded, Set<String> forbidden, CapacityFilter capacity) {
        return temporalSearch(src, dst, origin, horizon, topology, excluded, forbidden, capacity).pathTo(dst);
    }

    /** Capacity filter for this task when {@code VORTEX_GEN_CAPACITY_AWARE=true}, else null. */
    private static CapacityFilter capacityFilter(WorkloadTask task, BaseTopology topology, LRIB lrib) {
        boolean aware = Boolean.parseBoolean(
                System.getenv().getOrDefault("VORTEX_GEN_CAPACITY_AWARE", "false").trim());
        return aware ? new CapacityFilter(lrib, topology.getRegime(), task.getTaskSizeBits()) : null;
    }

    /** Upstream paths per terminal link for {@link #CUT_ANCHORED_CGR}; {@code VORTEX_CUT_UPSTREAM}. */
    private static int cutAnchoredUpstreamPerTerminal() {
        String value = System.getenv("VORTEX_CUT_UPSTREAM");
        if (value != null) {
            try {
                return Math.max(1, Math.min(32, Integer.parseInt(value.trim())));
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return 8;
    }

    private static void dfsPathEnumeration(String current, String dest, BaseTopology topology,
                                           Set<String> visited, List<Link> path,
                                           List<List<Link>> result, int maxPaths) {
        net.dcn.pce.crp.SolveDeadline.checkpoint("route enumeration");
        if (result.size() >= maxPaths) return;
        if (current.equals(dest)) {
            result.add(new ArrayList<>(path));
            return;
        }

        for (Link link : topology.getOutgoingLinks(current)) {
            String nextNode = link.getDestinationNodeId();
            if (!visited.contains(nextNode)) {
                visited.add(nextNode);
                path.add(link);
                dfsPathEnumeration(nextNode, dest, topology, visited, path, result, maxPaths);
                path.remove(path.size() - 1);
                visited.remove(nextNode);
            }
        }
    }

    private static List<Link> findMinimumHopPath(String src, String dst, BaseTopology topology) {
        if (src.equals(dst)) return Collections.emptyList();
        Map<String, Link> prevLink = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        visited.add(src);
        queue.add(src);

        while (!queue.isEmpty()) {
            net.dcn.pce.crp.SolveDeadline.checkpoint("minimum-hop search");
            String u = queue.remove();
            if (u.equals(dst)) break;

            for (Link link : topology.getOutgoingLinks(u)) {
                String v = link.getDestinationNodeId();
                if (visited.add(v)) {
                    prevLink.put(v, link);
                    queue.add(v);
                }
            }
        }

        if (!prevLink.containsKey(dst) && !src.equals(dst)) {
            return Collections.emptyList();
        }

        LinkedList<Link> path = new LinkedList<>();
        String curr = dst;
        while (prevLink.containsKey(curr)) {
            Link link = prevLink.get(curr);
            path.addFirst(link);
            curr = link.getSourceNodeId();
        }
        return path;
    }

    private static List<List<Link>> generateBoundedCgr(
            WorkloadTask task, BaseTopology topology, LRIB lrib, boolean lowerRates,
            int maxExpansionsPerNode) {
        record ContactLabel(
                String nodeId,
                double arrivalSec,
                double bottleneckRateBps,
                List<Link> path,
                Set<String> visitedNodes) {}

        // Contact-aware temporal routing serves both R_DET and R_STOCH: they share the scheduled-
        // contact topology and differ only in per-contact failure risk, which admission handles.
        if ((topology.getRegime() != ContactRegime.R_DET
                && topology.getRegime() != ContactRegime.R_STOCH)
                || task.getSourceNodeId().equals(task.getDestinationNodeId())) {
            return List.of();
        }

        PriorityQueue<ContactLabel> frontier = new PriorityQueue<>(Comparator
                .comparingDouble(ContactLabel::arrivalSec)
                .thenComparingInt(label -> label.path().size())
                .thenComparing(label -> routeKey(label.path())));
        frontier.add(new ContactLabel(
                task.getSourceNodeId(), task.getOriginationTimeSec(),
                Double.POSITIVE_INFINITY, List.of(), Set.of(task.getSourceNodeId())));

        List<List<Link>> routes = new ArrayList<>();
        Map<String, Integer> expansionsPerNode = new HashMap<>();
        int expansions = 0;
        while (!frontier.isEmpty() && routes.size() < BOUNDED_CGR_MAX_ROUTES
                && expansions++ < BOUNDED_CGR_MAX_LABEL_EXPANSIONS) {
            net.dcn.pce.crp.SolveDeadline.checkpoint("bounded CGR expansion");
            ContactLabel label = frontier.remove();
            if (label.nodeId().equals(task.getDestinationNodeId())) {
                routes.add(label.path());
                continue;
            }
            if (label.path().size() >= BOUNDED_CGR_MAX_HOPS) {
                continue;
            }
            if (expansionsPerNode.merge(label.nodeId(), 1, Integer::sum) > maxExpansionsPerNode) {
                expansions--; // dropped unexpanded, so it does not use up the budget
                continue;
            }

            topology.getOutgoingLinks(label.nodeId()).stream()
                    .sorted(Comparator.comparing(Link::getLinkId))
                    .forEach(link -> {
                        String nextNode = link.getDestinationNodeId();
                        if (label.visitedNodes().contains(nextNode)) {
                            return;
                        }
                        double residualRateBps = lrib.getMaximumAvailableCap(
                                link.getLinkId(), SchedulingCapacity.capacityBps(link, topology.getRegime()),
                                task.getOriginationTimeSec(), task.getEffectiveDeadlineSec());
                        double bottleneckRateBps = Math.min(label.bottleneckRateBps(), residualRateBps);
                        if (!Double.isFinite(bottleneckRateBps) || bottleneckRateBps <= 0) {
                            return;
                        }

                        List<Link> path = new ArrayList<>(label.path());
                        path.add(link);
                        // On the retry, a partly used contact may carry the task only below the
                        // best-instant rate, which later hops then inherit.
                        SchedulingCapacity.RatedCompletion arrival = lowerRates
                                ? SchedulingCapacity.earliestCompletionAtSchedulableRate(
                                        task, path, topology.getRegime(), lrib, bottleneckRateBps)
                                : new SchedulingCapacity.RatedCompletion(bottleneckRateBps,
                                        SchedulingCapacity.earliestCompletionSec(
                                                task, path, topology.getRegime(), lrib, bottleneckRateBps));
                        if (!Double.isFinite(arrival.completionSec())) {
                            return;
                        }
                        Set<String> visited = new HashSet<>(label.visitedNodes());
                        visited.add(nextNode);
                        frontier.add(new ContactLabel(
                                nextNode, arrival.completionSec(), arrival.rateBps(),
                                List.copyOf(path), Set.copyOf(visited)));
                    });
        }
        return List.copyOf(routes);
    }

    private static String routeKey(List<Link> route) {
        return route.stream()
                .map(link -> link.getLinkId().length() + ":" + link.getLinkId())
                .collect(java.util.stream.Collectors.joining());
    }
}
