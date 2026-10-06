package net.dcn.pce.crp.policy;

import net.dcn.pce.crp.policy.StochasticAdmission.RouteRisk;
import net.dcn.pce.crp.policy.StochasticAdmission.SurvivalLedger;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StochasticAdmissionTest {

    private static final double EPS = 1e-12;

    /** A route: upstream contacts (each successProb p) into one terminal contact (successProb q). */
    private static RouteRisk route(Set<String> upstream, String terminal, double p, double q) {
        double upstreamSurvival = Math.pow(p, upstream.size());
        java.util.Set<String> all = new java.util.HashSet<>(upstream);
        all.add(terminal);
        return new RouteRisk(upstreamSurvival * q, Set.copyOf(all), terminal, Set.of(terminal), q,
                Set.copyOf(upstream), upstreamSurvival);
    }

    @Test
    void disjointModeIsTheIndependentUnion() {
        SurvivalLedger ledger = new SurvivalLedger(false);
        RouteRisk a = route(Set.of("a1", "a2"), "ta", 0.8, 0.8);
        RouteRisk b = route(Set.of("b1"), "tb", 0.8, 0.8);
        ledger.add(a);
        assertTrue(ledger.accepts(b));
        ledger.add(b);
        double sa = 0.8 * 0.8 * 0.8;
        double sb = 0.8 * 0.8;
        assertEquals(1 - (1 - sa) * (1 - sb), ledger.survival(), EPS);
    }

    @Test
    void disjointModeRejectsAnySharedContact() {
        SurvivalLedger ledger = new SurvivalLedger(false);
        ledger.add(route(Set.of("u1"), "t", 0.9, 0.9));
        assertFalse(ledger.accepts(route(Set.of("u2"), "t", 0.9, 0.9)));
    }

    @Test
    void sharedTerminalGroupsUpstreamPathsBehindOneTerminalContact() {
        SurvivalLedger ledger = new SurvivalLedger(true);
        double p = 0.7;
        double q = 0.7;
        RouteRisk first = route(Set.of("u1", "u2"), "t", p, q);
        RouteRisk second = route(Set.of("v1", "v2", "v3"), "t", p, q);
        ledger.add(first);
        assertTrue(ledger.accepts(second));
        ledger.add(second);
        // Survives iff the shared terminal occurs AND at least one upstream path survives.
        double upstreamAtLeastOne = 1 - (1 - p * p) * (1 - p * p * p);
        assertEquals(q * upstreamAtLeastOne, ledger.survival(), EPS);
        assertEquals(1, ledger.routeGroups());
    }

    @Test
    void sharedTerminalStillRequiresIndependentUpstream() {
        SurvivalLedger ledger = new SurvivalLedger(true);
        ledger.add(route(Set.of("u1", "u2"), "t", 0.9, 0.9));
        assertFalse(ledger.accepts(route(Set.of("u2", "u3"), "t", 0.9, 0.9)));
        assertFalse(ledger.accepts(route(Set.of("x1"), "u1", 0.9, 0.9)),
                "a contact carrying committed upstream cannot become another route's terminal");
    }

    @Test
    void sharedTerminalWithDistinctTerminalsEqualsDisjointMode() {
        SurvivalLedger shared = new SurvivalLedger(true);
        SurvivalLedger disjoint = new SurvivalLedger(false);
        for (RouteRisk r : new RouteRisk[]{
                route(Set.of("a1"), "ta", 0.6, 0.6),
                route(Set.of("b1", "b2"), "tb", 0.6, 0.6),
                route(Set.of("c1", "c2", "c3"), "tc", 0.6, 0.6)}) {
            shared.add(r);
            disjoint.add(r);
        }
        assertEquals(disjoint.survival(), shared.survival(), EPS);
    }

    @Test
    void survivalWithPredictsTheSurvivalAfterAdding() {
        for (boolean shared : new boolean[]{false, true}) {
            SurvivalLedger ledger = new SurvivalLedger(shared);
            ledger.add(route(Set.of("u1", "u2"), "t1", 0.7, 0.7));
            RouteRisk sameTerminal = route(Set.of("v1"), "t1", 0.7, 0.7);
            RouteRisk newTerminal = route(Set.of("w1", "w2"), "t2", 0.7, 0.7);
            for (RouteRisk next : new RouteRisk[]{sameTerminal, newTerminal}) {
                if (!ledger.accepts(next)) {
                    continue;
                }
                double predicted = ledger.survivalWith(next);
                ledger.add(next);
                assertEquals(predicted, ledger.survival(), EPS, "shared=" + shared);
            }
        }
    }

    // ---- Monte-Carlo ledger (overlapping routes) ----

    private static StochasticAdmission.HopEdge edge(String from, String to, double depart, double arrive,
                                                    String... contacts) {
        return new StochasticAdmission.HopEdge(from, to, depart, arrive, Set.of(contacts));
    }

    private static RouteRisk mcRoute(java.util.Map<String, Double> probability,
                                     StochasticAdmission.HopEdge... edges) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (StochasticAdmission.HopEdge e : edges) {
            keys.addAll(e.contacts());
        }
        return new RouteRisk(0.0, Set.copyOf(keys), "", Set.of(), 1.0, Set.copyOf(keys), 0.0,
                java.util.List.of(edges), probability);
    }

    @Test
    void monteCarloMatchesBruteForceIncludingRecombination() {
        double p = 0.6;
        java.util.Map<String, Double> prob = java.util.Map.of(
                "c1", p, "c2", p, "c3", p, "c4", p, "c6", p, "c7", p);
        // Route A: s -> x -> d.  Route B: s -> y -> x -> z -> d.  They meet at x, so two delivery paths
        // exist that neither route has on its own: s->y->x->d (B reaches x at t=3, before A's x->d departs
        // at t=10) and s->x->z->d (A reaches x at t=1, before B's x->z departs at t=5).
        RouteRisk a = mcRoute(prob, edge("s", "x", 0, 1, "c1"), edge("x", "d", 10, 11, "c2"));
        RouteRisk b = mcRoute(prob, edge("s", "y", 0, 1, "c3"), edge("y", "x", 2, 3, "c4"),
                edge("x", "z", 5, 6, "c6"), edge("z", "d", 30, 31, "c7"));
        SurvivalLedger ledger = SurvivalLedger.monteCarlo("s", "d", 0.0, 40_000, 7L);
        ledger.add(a);
        ledger.add(b);

        // Exact: deliver iff (c1&c2) | (c3&c4&c6&c7) | (c3&c4&c2) | (c1&c6&c7), by enumeration.
        String[] cs = {"c1", "c2", "c3", "c4", "c6", "c7"};
        double exact = 0.0;
        for (int mask = 0; mask < (1 << cs.length); mask++) {
            boolean[] on = new boolean[cs.length];
            double weight = 1.0;
            for (int i = 0; i < cs.length; i++) {
                on[i] = (mask & (1 << i)) != 0;
                weight *= on[i] ? p : 1 - p;
            }
            boolean c1 = on[0], c2 = on[1], c3 = on[2], c4 = on[3], c6 = on[4], c7 = on[5];
            if ((c1 && c2) || (c3 && c4 && c6 && c7) || (c3 && c4 && c2) || (c1 && c6 && c7)) {
                exact += weight;
            }
        }
        assertEquals(exact, ledger.survival(), 0.012);
        assertTrue(ledger.accepts(a), "Monte-Carlo mode accepts overlapping routes");
    }

    @Test
    void monteCarloSurvivalWithPredictsAddingAndIsDeterministic() {
        java.util.Map<String, Double> prob = java.util.Map.of("c1", 0.5, "c2", 0.5, "c3", 0.5);
        RouteRisk a = mcRoute(prob, edge("s", "x", 0, 1, "c1"), edge("x", "d", 5, 6, "c2"));
        RouteRisk b = mcRoute(prob, edge("s", "x", 0, 1, "c3"), edge("x", "d", 5, 6, "c2"));
        SurvivalLedger one = SurvivalLedger.monteCarlo("s", "d", 0.0, 512, 3L);
        SurvivalLedger two = SurvivalLedger.monteCarlo("s", "d", 0.0, 512, 3L);
        one.add(a);
        two.add(a);
        double predicted = one.survivalWith(b);
        one.add(b);
        assertEquals(predicted, one.survival(), 0.0);
        two.add(b);
        assertEquals(one.survival(), two.survival(), 0.0, "same seed, same estimate");
    }

    @Test
    void monteCarloRespectsReservedDepartureTimes() {
        java.util.Map<String, Double> prob = java.util.Map.of("c1", 1.0, "c2", 1.0);
        // The only onward hop from x departs at t=1, before the bundle reaches x at t=4: undeliverable.
        RouteRisk late = mcRoute(prob, edge("s", "x", 3, 4, "c1"), edge("x", "d", 1, 2, "c2"));
        SurvivalLedger ledger = SurvivalLedger.monteCarlo("s", "d", 0.0, 64, 1L);
        ledger.add(late);
        assertEquals(0.0, ledger.survival(), EPS);
    }

    @Test
    void deterministicContactsSurviveWithCertainty() {
        SurvivalLedger ledger = new SurvivalLedger(true);
        ledger.add(route(Set.of("u1", "u2"), "t", 1.0, 1.0));
        assertEquals(1.0, ledger.survival(), EPS);
    }
}
