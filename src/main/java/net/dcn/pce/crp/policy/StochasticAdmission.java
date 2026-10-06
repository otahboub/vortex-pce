package net.dcn.pce.crp.policy;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.ContactWindow;
import net.dcn.pce.model.Link;
import net.dcn.pce.rib.LRIB;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/**
 * R_STOCH admission mathematics.
 *
 * <p>A scheduled contact plan carries, per contact, a {@code successProb} — the probability the contact
 * actually occurs. A committed route survives only if every scheduled contact it uses occurs, so its
 * survival probability is the product of those contacts' success probabilities. Redundant, contact-
 * disjoint routes fail independently, so their union survival is {@code 1 - Prod(1 - p_i)}. The offline
 * PCE consumes {@code successProb} as an abstract input and never models the physics that produce it.
 *
 * <p>When the bottleneck of the network is the set of contacts into the destination (the terminal cut),
 * the cheapest reliability is bought UPSTREAM of it: several upstream paths into the same terminal
 * contact. Those routes share their terminal contact, so they are not independent. {@link SurvivalLedger}
 * handles that exactly by grouping routes on their terminal contact: a group survives iff its terminal
 * contact occurs and at least one of its (mutually disjoint) upstream paths survives.
 *
 * <p>Deterministic contacts (successProb 1.0) make every route survive with probability 1.0, so R_DET
 * planning is unaffected and never triggers redundancy.
 */
public final class StochasticAdmission {

    private StochasticAdmission() {}

    /**
     * A committed route's risk profile: overall survival and contact identities, split into the
     * terminal hop's contacts (into the destination) and the upstream contacts before it.
     */
    public record RouteRisk(
            double survivalProbability,
            Set<String> contactKeys,
            String terminalKey,
            Set<String> terminalContacts,
            double terminalProbability,
            Set<String> upstreamContacts,
            double upstreamSurvival,
            List<HopEdge> edges,
            Map<String, Double> contactProbability) {

        /** Without hop edges (enough for the exact ledgers; the Monte-Carlo ledger needs edges). */
        public RouteRisk(double survivalProbability, Set<String> contactKeys, String terminalKey,
                         Set<String> terminalContacts, double terminalProbability,
                         Set<String> upstreamContacts, double upstreamSurvival) {
            this(survivalProbability, contactKeys, terminalKey, terminalContacts, terminalProbability,
                    upstreamContacts, upstreamSurvival, List.of(), Map.of());
        }

        /** A route with no terminal/upstream split (all contacts treated as one independent chain). */
        public static RouteRisk of(double survivalProbability, Set<String> contactKeys) {
            return new RouteRisk(survivalProbability, Set.copyOf(contactKeys), "", Set.of(), 1.0,
                    Set.copyOf(contactKeys), survivalProbability);
        }
    }

    /**
     * One committed hop as the Monte-Carlo ledger sees it: it departs {@code from} at its reserved slot
     * ({@code departSec}), reaches {@code to} at {@code arriveSec}, and exists only if every scheduled
     * contact it uses occurs. A bundle can take it if it is at {@code from} by {@code departSec}.
     */
    public record HopEdge(String from, String to, double departSec, double arriveSec, Set<String> contacts) {}

    /**
     * Survival probability of a committed route = the product of the success probabilities of the
     * DISTINCT scheduled contacts its hops occupy. Persistent / periodic hops (no scheduled contact)
     * contribute 1.0. The final hop's contacts are recorded separately as the terminal contacts.
     */
    public static RouteRisk assess(List<CRPEngine.HopSchedule> hops) {
        Map<String, Double> upstream = new LinkedHashMap<>();
        Map<String, Double> terminal = new LinkedHashMap<>();
        Map<String, Double> probability = new HashMap<>();
        List<HopEdge> edges = new ArrayList<>();
        for (int h = 0; h < hops.size(); h++) {
            CRPEngine.HopSchedule hop = hops.get(h);
            Link link = hop.getLink();
            Map<String, Double> target = h == hops.size() - 1 ? terminal : upstream;
            Set<String> hopContacts = new HashSet<>();
            for (LRIB.TransmissionSlot slot : hop.getTransmissionSlots()) {
                link.contactWindowForInterval(slot.startSec(), slot.endSec()).ifPresent(window -> {
                    String key = contactKey(link, window);
                    target.putIfAbsent(key, window.successProb());
                    probability.putIfAbsent(key, window.successProb());
                    hopContacts.add(key);
                });
            }
            edges.add(new HopEdge(link.getSourceNodeId(), link.getDestinationNodeId(),
                    hop.getStartSec(), hop.getArrivalSec(), Set.copyOf(hopContacts)));
        }
        upstream.keySet().removeAll(terminal.keySet());
        double upstreamSurvival = product(upstream);
        double terminalProbability = product(terminal);
        Set<String> all = new HashSet<>(upstream.keySet());
        all.addAll(terminal.keySet());
        String terminalKey = terminal.isEmpty() ? "" : String.join("|", new TreeSet<>(terminal.keySet()));
        return new RouteRisk(upstreamSurvival * terminalProbability, Set.copyOf(all), terminalKey,
                Set.copyOf(terminal.keySet()), terminalProbability, Set.copyOf(upstream.keySet()),
                upstreamSurvival, List.copyOf(edges), Map.copyOf(probability));
    }

    /** Union survival of adding a CONTACT-DISJOINT route to routes whose union is {@code currentUnion}. */
    public static double unionSurvival(double currentUnion, double addedRouteSurvival) {
        return 1.0 - (1.0 - currentUnion) * (1.0 - addedRouteSurvival);
    }

    /** True iff the two routes share no scheduled contact (so their failures are independent). */
    public static boolean disjoint(Set<String> a, Set<String> b) {
        for (String key : a) {
            if (b.contains(key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tracks the committed routes of one task and their exact joint survival probability.
     *
     * <p>In the default (contact-disjoint) mode every accepted route shares no contact with any other,
     * and survival is {@code 1 - Prod(1 - s_i)}. In shared-terminal mode a route may reuse another
     * route's terminal contact provided its upstream contacts are disjoint from every committed contact;
     * routes are grouped by terminal contact and survival is
     * {@code 1 - Prod_k (1 - q_k * (1 - Prod_{j in k} (1 - u_j)))}, where {@code q_k} is the terminal
     * contact's success probability and {@code u_j} the upstream survival of route j. Both expressions
     * are exact under their acceptance rule, and they coincide when no terminal contact is shared.
     */
    public static final class SurvivalLedger {
        private final boolean sharedTerminal;
        /** group key -> {terminal success probability, product of upstream failure probabilities}. */
        private final Map<String, double[]> groups = new LinkedHashMap<>();
        private final Set<String> committedContacts = new HashSet<>();
        private final Set<String> committedUpstream = new HashSet<>();
        private final Set<String> committedTerminal = new HashSet<>();

        /** Non-null in Monte-Carlo mode (see {@link #monteCarlo}). */
        private final MonteCarlo monteCarlo;

        public SurvivalLedger(boolean sharedTerminal) {
            this.sharedTerminal = sharedTerminal;
            this.monteCarlo = null;
        }

        private SurvivalLedger(MonteCarlo monteCarlo) {
            this.sharedTerminal = false;
            this.monteCarlo = monteCarlo;
        }

        /**
         * A ledger for overlapping routes (e.g. MESH_CGR), where no closed form is exact. Survival is
         * estimated over {@code samples} failure realisations: in each, every scheduled contact occurs
         * with its success probability, drawn by a deterministic hash of the contact and sample index
         * (so results are reproducible). A bundle delivers if it can reach {@code target} over committed
         * hops that occur, taking a hop whenever it is at the hop's source by the hop's reserved
         * departure — so committed routes recombine at shared nodes (subgraph delivery). Every route is
         * accepted; the estimate only decides when the confidence target is met and, under global
         * allocation, which backup adds most.
         */
        public static SurvivalLedger monteCarlo(String source, String target, double originSec,
                                                int samples, long seed) {
            return new SurvivalLedger(new MonteCarlo(source, target, originSec, samples, seed));
        }

        /** Whether {@code risk} can be added while keeping the survival expression exact. */
        public boolean accepts(RouteRisk risk) {
            if (monteCarlo != null) {
                return true;
            }
            if (!sharedTerminal) {
                return disjoint(committedContacts, risk.contactKeys());
            }
            if (!disjoint(committedContacts, risk.upstreamContacts())) {
                return false; // upstream must be independent of everything already committed
            }
            if (!disjoint(committedUpstream, risk.terminalContacts())) {
                return false; // a terminal contact must not already carry some route's upstream
            }
            // Either join an existing terminal group exactly, or use terminal contacts nobody uses.
            return groups.containsKey(risk.terminalKey())
                    || disjoint(committedTerminal, risk.terminalContacts());
        }

        public void add(RouteRisk risk) {
            if (monteCarlo != null) {
                monteCarlo.add(risk);
                return;
            }
            committedContacts.addAll(risk.contactKeys());
            if (!sharedTerminal) {
                groups.put("#" + groups.size(), new double[]{1.0, 1.0 - risk.survivalProbability()});
                return;
            }
            committedUpstream.addAll(risk.upstreamContacts());
            committedTerminal.addAll(risk.terminalContacts());
            double[] group = groups.computeIfAbsent(risk.terminalKey(),
                    key -> new double[]{risk.terminalProbability(), 1.0});
            group[1] *= 1.0 - risk.upstreamSurvival();
        }

        /** Survival if {@code risk} were added (it must be {@link #accepts accepted}); no mutation. */
        public double survivalWith(RouteRisk risk) {
            if (monteCarlo != null) {
                return monteCarlo.survivalWith(risk);
            }
            double failure = 1.0;
            if (!sharedTerminal) {
                for (double[] group : groups.values()) {
                    failure *= 1.0 - group[0] * (1.0 - group[1]);
                }
                return 1.0 - failure * (1.0 - risk.survivalProbability());
            }
            boolean joined = false;
            for (Map.Entry<String, double[]> entry : groups.entrySet()) {
                double upstreamFailure = entry.getValue()[1];
                if (entry.getKey().equals(risk.terminalKey())) {
                    upstreamFailure *= 1.0 - risk.upstreamSurvival();
                    joined = true;
                }
                failure *= 1.0 - entry.getValue()[0] * (1.0 - upstreamFailure);
            }
            if (!joined) {
                failure *= 1.0 - risk.terminalProbability() * risk.upstreamSurvival();
            }
            return 1.0 - failure;
        }

        /** Exact probability that at least one committed route survives. */
        public double survival() {
            if (monteCarlo != null) {
                return monteCarlo.survival();
            }
            double failure = 1.0;
            for (double[] group : groups.values()) {
                failure *= 1.0 - group[0] * (1.0 - group[1]);
            }
            return 1.0 - failure;
        }

        public int routeGroups() {
            return monteCarlo != null ? monteCarlo.routes : groups.size();
        }
    }

    /** Monte-Carlo state for {@link SurvivalLedger#monteCarlo}: per-sample earliest arrival per node. */
    private static final class MonteCarlo {
        private final String source;
        private final String target;
        private final int samples;
        private final long seed;
        private final Map<String, Double> probability = new HashMap<>();
        private final Map<String, BitSet> presence = new HashMap<>();
        private final Map<String, List<HopEdge>> out = new HashMap<>();
        private final List<Map<String, Double>> arrival = new ArrayList<>();
        private final BitSet reached = new BitSet();
        private int routes;

        MonteCarlo(String source, String target, double originSec, int samples, long seed) {
            if (samples <= 0) {
                throw new IllegalArgumentException("samples must be positive");
            }
            this.source = source;
            this.target = target;
            this.samples = samples;
            this.seed = seed;
            for (int s = 0; s < samples; s++) {
                Map<String, Double> at = new HashMap<>();
                at.put(source, originSec);
                arrival.add(at);
            }
            if (source.equals(target)) {
                reached.set(0, samples);
            }
        }

        void add(RouteRisk risk) {
            routes++;
            risk.contactProbability().forEach(probability::putIfAbsent);
            for (HopEdge edge : risk.edges()) {
                out.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
            }
            for (int s = reached.nextClearBit(0); s < samples; s = reached.nextClearBit(s + 1)) {
                if (propagate(arrival.get(s), risk.edges(), s, Map.of())) {
                    reached.set(s);
                }
            }
        }

        double survivalWith(RouteRisk risk) {
            risk.contactProbability().forEach(probability::putIfAbsent);
            Map<String, List<HopEdge>> extra = new HashMap<>();
            for (HopEdge edge : risk.edges()) {
                extra.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
            }
            int count = reached.cardinality();
            for (int s = reached.nextClearBit(0); s < samples; s = reached.nextClearBit(s + 1)) {
                if (propagate(new HashMap<>(arrival.get(s)), risk.edges(), s, extra)) {
                    count++;
                }
            }
            return count / (double) samples;
        }

        double survival() {
            return reached.cardinality() / (double) samples;
        }

        /**
         * Relaxes {@code seeds}, then propagates earliest arrivals over committed hops (and {@code extra})
         * that occur in sample {@code s}. Updates {@code at} in place; true once the target is reached.
         */
        private boolean propagate(Map<String, Double> at, List<HopEdge> seeds, int s,
                                  Map<String, List<HopEdge>> extra) {
            PriorityQueue<Map.Entry<String, Double>> queue =
                    new PriorityQueue<>(Map.Entry.comparingByValue());
            for (HopEdge edge : seeds) {
                relax(at, edge, s, queue);
            }
            while (!queue.isEmpty()) {
                Map.Entry<String, Double> top = queue.poll();
                String node = top.getKey();
                if (top.getValue() > at.get(node)) {
                    continue;
                }
                if (node.equals(target)) {
                    return true;
                }
                for (HopEdge edge : out.getOrDefault(node, List.of())) {
                    relax(at, edge, s, queue);
                }
                for (HopEdge edge : extra.getOrDefault(node, List.of())) {
                    relax(at, edge, s, queue);
                }
            }
            return at.containsKey(target);
        }

        private void relax(Map<String, Double> at, HopEdge edge, int s,
                           PriorityQueue<Map.Entry<String, Double>> queue) {
            Double atFrom = at.get(edge.from());
            if (atFrom == null || atFrom > edge.departSec() + 1e-9
                    || edge.arriveSec() >= at.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)
                    || !occurs(edge, s)) {
                return;
            }
            at.put(edge.to(), edge.arriveSec());
            queue.add(Map.entry(edge.to(), edge.arriveSec()));
        }

        private boolean occurs(HopEdge edge, int s) {
            for (String contact : edge.contacts()) {
                if (!presence(contact).get(s)) {
                    return false;
                }
            }
            return true;
        }

        /** Samples in which {@code contact} occurs: a deterministic function of (contact, sample, seed). */
        private BitSet presence(String contact) {
            return presence.computeIfAbsent(contact, key -> {
                double p = probability.getOrDefault(key, 1.0);
                BitSet bits = new BitSet(samples);
                for (int s = 0; s < samples; s++) {
                    long z = splitMix(seed ^ splitMix(key.hashCode() * 0x9E3779B97F4A7C15L + s));
                    if ((z >>> 11) * 0x1.0p-53 < p) {
                        bits.set(s);
                    }
                }
                return bits;
            });
        }

        private static long splitMix(long z) {
            z += 0x9E3779B97F4A7C15L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
    }

    private static double product(Map<String, Double> probabilities) {
        double value = 1.0;
        for (double p : probabilities.values()) {
            value *= p;
        }
        return value;
    }

    private static String contactKey(Link link, ContactWindow window) {
        return link.getLinkId() + '@' + window.startSec();
    }
}
