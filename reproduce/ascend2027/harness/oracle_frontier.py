#!/usr/bin/env python3
"""
oracle_frontier.py — what it costs a CRP plan to reach the connectivity oracle (no load).

A plan is the set of contacts committed for a bundle. Its successful delivery probability (SDP) under
independent contact failure p is the probability that the bundle's source still reaches the
destination through committed contacts that occur (subgraph delivery). SDP is monotone in the plan, and
the plan made of every contact on some source->destination path IS the oracle (flooding). So every
point between a single route and flooding is reachable by a CRP policy; the only question is cost.

This scores one family of plans on the SAME Monte-Carlo realisations (paired comparison) and reports,
per plan, committed contacts per bundle and SDP; plus the clairvoyant oracle (knows the realisation,
commits one path in it) whose SDP equals the oracle's at the cost of a single path. The ratio between
the flooding and clairvoyant costs is the price of uncertainty an offline planner pays.

Plans (Python replicas of the engine's policies; see vortex_sdp.py for real-engine runs):
  single route        earliest-arrival nominal route (plain CGR)
  disjoint k          k contact-disjoint earliest-arrival routes (DISJOINT_CGR)
  cut-anchored k      k upstream paths into each terminal relay, upstream globally disjoint (CUT_ANCHORED_CGR)
  cut + b detours     for each committed contact, b alternatives out of the same node + onward path
  mesh D              every contact on some source->destination path arriving within D hours
"""
import argparse
import csv
import heapq
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from datasets.rucop import parse_contact_plan  # noqa: E402
from sdp_score import nominal_route, path_exists  # noqa: E402


def copy_links(links, drop_inbound_to=None, excluded=()):
    out = {}
    for k, v in links.items():
        if drop_inbound_to is not None and k[1] == drop_inbound_to:
            continue
        wins = [w for w in v["windows"] if (k, w) not in excluded]
        out[k] = {"windows": wins, "rate_bps": v["rate_bps"], "owlt": v["owlt"]}
    return out


def plan_single(links, s, dst, H):
    r = nominal_route(links, s, dst, 0.0, H)
    return set(r or [])


def plan_disjoint(links, s, dst, H, k):
    used, C = set(), set()
    for _ in range(k):
        r = nominal_route(copy_links(links, excluded=used), s, dst, 0.0, H)
        if not r:
            break
        C.update(r)
        used.update(r)
    return C


def plan_cut(links, s, dst, H, k):
    terminals = sorted((a for (a, b) in links if b == dst), key=lambda x: (len(x), x))
    used, C = set(), set()
    for _ in range(k):
        progressed = False
        for t in terminals:
            up = nominal_route(copy_links(links, drop_inbound_to=dst, excluded=used), s, t, 0.0, H)
            if not up:
                continue
            C.update(up)
            used.update(up)
            C.update(((t, dst), w) for w in links[(t, dst)]["windows"])
            progressed = True
        if not progressed:
            break
    return C


def plan_detours(links, base, dst, H, nb, out):
    C = set(base)
    for ((u, v), (ws, we)) in sorted(base):
        if v == dst:
            continue
        alts = []
        for x, wins in out.get(u, []):
            for (a0, a1) in wins:
                if a1 <= ws or ((u, x), (a0, a1)) in base:
                    continue
                alts.append((max(a0, ws), x, (a0, a1)))
        alts.sort()
        added = 0
        for t, x, w in alts:
            if added >= nb:
                break
            if x == dst:
                C.add(((u, x), w))
                added += 1
                continue
            onward = nominal_route(links, x, dst, t, H)
            if onward:
                C.add(((u, x), w))
                C.update(onward)
                added += 1
    return C


def earliest_arrival(out, src):
    ea = {src: 0.0}
    pq = [(0.0, src)]
    while pq:
        t, u = heapq.heappop(pq)
        if t > ea.get(u, float("inf")):
            continue
        for b, wins in out.get(u, []):
            for (ws, we) in wins:
                d = max(t, ws)
                if d < we:
                    if d < ea.get(b, float("inf")):
                        ea[b] = d
                        heapq.heappush(pq, (d, b))
                    break
    return ea


def latest_departure(inl, dst, deadline):
    ld = {dst: deadline}
    pq = [(-deadline, dst)]
    while pq:
        nt, v = heapq.heappop(pq)
        t = -nt
        if t < ld.get(v, -1):
            continue
        for a, wins in inl.get(v, []):
            best = max([min(we - 1e-6, t) for (ws, we) in wins if min(we - 1e-6, t) >= ws] or [-1])
            if best > ld.get(a, -1):
                ld[a] = best
                heapq.heappush(pq, (-best, a))
    return ld


def plan_mesh(links, ea, ld):
    C = set()
    for (a, b), lk in links.items():
        if a not in ea or b not in ld:
            continue
        for (ws, we) in lk["windows"]:
            t = max(ea[a], ws)
            if t < we and t <= ld[b]:
                C.add(((a, b), (ws, we)))
    return C


def clairvoyant_hops(adj, src, dst, H):
    """Hop count of the earliest-arrival path in the realised graph (None if unreachable)."""
    best = {src: (0.0, 0)}
    pq = [(0.0, 0, src)]
    while pq:
        t, h, u = heapq.heappop(pq)
        if u == dst:
            return h
        if (t, h) > best.get(u, (float("inf"), 0)):
            continue
        for (v, ws, we) in adj.get(u, []):
            d = max(t, ws)
            if d < we and d <= H and (d, h + 1) < best.get(v, (float("inf"), 0)):
                best[v] = (d, h + 1)
                heapq.heappush(pq, (d, h + 1, v))
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--contact-plan", required=True)
    ap.add_argument("--dst", default="48")
    ap.add_argument("--srcs", default="7-31")
    ap.add_argument("--pfails", default="0.3,0.5,0.7")
    ap.add_argument("--runs", type=int, default=200)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default="oracle_frontier.csv")
    args = ap.parse_args()

    nodes, links, H = parse_contact_plan(args.contact_plan)
    a, b = args.srcs.split("-")
    srcs = [str(i) for i in range(int(a), int(b) + 1) if str(i) in nodes]
    dst = args.dst
    out, inl = {}, {}
    for (u, v), lk in links.items():
        out.setdefault(u, []).append((v, lk["windows"]))
        inl.setdefault(v, []).append((u, lk["windows"]))

    plans = {}
    plans["single route (CGR)"] = {s: plan_single(links, s, dst, H) for s in srcs}
    plans["disjoint k=6"] = {s: plan_disjoint(links, s, dst, H, 6) for s in srcs}
    for k in (2, 8):
        plans[f"cut-anchored k={k}"] = {s: plan_cut(links, s, dst, H, k) for s in srcs}
    cut8 = plans["cut-anchored k=8"]
    for nb in (1, 2, 3):
        plans[f"cut k=8 + {nb} detour/hop"] = {s: plan_detours(links, cut8[s], dst, H, nb, out) for s in srcs}
    ea = {s: earliest_arrival(out, s) for s in srcs}
    for hours in (4, 8, 24):
        ld = latest_departure(inl, dst, hours * 3600.0)
        plans[f"mesh {hours}h"] = {s: plan_mesh(links, ea[s], ld) for s in srcs}
    size = {n: sum(len(c) for c in P.values()) / len(srcs) for n, P in plans.items()}
    print(f"# {len(srcs)} bundles, {sum(len(v['windows']) for v in links.values())} contacts", file=sys.stderr)

    rng = random.Random(args.seed)
    pfails = [float(x) for x in args.pfails.split(",")]
    sdp = {n: {} for n in plans}
    oracle, clair_cost = {}, {}
    for p in pfails:
        tot = {n: 0 for n in plans}
        o, hops, hits = 0, 0, 0
        for _ in range(args.runs):
            present, adj = set(), {}
            for (u, v), lk in links.items():
                for w in lk["windows"]:
                    if rng.random() >= p:
                        present.add(((u, v), w))
                        adj.setdefault(u, []).append((v,) + w)
            for s in srcs:
                h = clairvoyant_hops(adj, s, dst, H)
                if h is not None:
                    o += 1
                    hops += h
                    hits += 1
                for n, P in plans.items():
                    sub = {}
                    for ((u, v), w) in P[s]:
                        if ((u, v), w) in present:
                            sub.setdefault(u, []).append((v,) + w)
                    if path_exists(sub, s, dst, 0.0, H):
                        tot[n] += 1
        N = args.runs * len(srcs)
        for n in plans:
            sdp[n][p] = tot[n] / N
        oracle[p] = o / N
        clair_cost[p] = hops / max(1, hits)

    rows = ["plan,contacts_per_bundle," + ",".join(f"sdp_p{p}" for p in pfails)]
    print(f"{'plan':26}{'contacts/bundle':>16}" + "".join(f"   p={p}" for p in pfails))
    for n in plans:
        rows.append(f"{n},{size[n]:.1f}," + ",".join(f"{sdp[n][p]:.4f}" for p in pfails))
        print(f"{n:26}{size[n]:>16.1f}" + "".join(f"  {sdp[n][p]:.3f}" for p in pfails))
    for p in pfails:
        rows.append(f"clairvoyant oracle p={p},{clair_cost[p]:.2f}," +
                    ",".join(f"{oracle[q]:.4f}" if q == p else "" for q in pfails))
    print(f"{'oracle':26}{'':>16}" + "".join(f"  {oracle[p]:.3f}" for p in pfails))
    print("clairvoyant cost (contacts on the one realised path it needs): " +
          ", ".join(f"p={p}: {clair_cost[p]:.1f}" for p in pfails))

    # contacts needed to reach a fraction of the oracle (upper envelope, linear interpolation)
    print("\ncontacts per bundle needed to reach a fraction of the oracle:")
    for p in pfails:
        pts = sorted((size[n], sdp[n][p]) for n in plans)
        env, best = [], -1.0
        for c, v in pts:
            if v > best:
                env.append((c, v))
                best = v
        line = []
        for frac in (0.90, 0.95, 0.99):
            target = frac * oracle[p]
            need = None
            for (c0, v0), (c1, v1) in zip(env, env[1:]):
                if v0 < target <= v1:
                    need = c0 + (target - v0) / (v1 - v0) * (c1 - c0)
                    break
            if need is None and env and env[0][1] >= target:
                need = env[0][0]
            line.append(f"{int(frac*100)}%: " + (f"{need:.0f}" if need else "not reached"))
            rows.append(f"need_{int(frac*100)}pct_p{p},{need if need else ''},")
        print(f"  p={p}: " + "  ".join(line) + f"   (clairvoyant: {clair_cost[p]:.1f}, flooding: {size['mesh 24h']:.0f})")
    with open(args.out, "w") as f:
        f.write("\n".join(rows) + "\n")
    print(f"# wrote {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
