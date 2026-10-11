#!/usr/bin/env python3
"""
flood_vs_crp_load.py — the oracle's disadvantage: flooding under finite contact capacity vs CRP.

The connectivity oracle assumes every contact can carry every bundle's copy. Its realisable form is
flooding: forward a copy over every contact that lies on some source->destination path (directed
flooding over the oracle's own subgraph). With finite contact capacity those copies compete, so the
oracle's number is only an upper bound at vanishing load.

This drives the offered load up (bundles per cold spot) and compares, on the SAME failure realisations:
  oracle    reachability of the realised graph, no capacity limit (the textbook bound)
  vortex    the REAL engine (CUT_ANCHORED_CGR + shared-terminal admission). Its routes are reserved in
            the LRIB at planning time, so every committed contact is capacity-feasible and carries the
            bundle whenever the contact occurs; delivery = reachability over committed contacts that occur.
  flooding  event-driven directed flooding with per-contact capacity floor(rate x duration / bundle
            size): when a contact opens (or a copy arrives while it is open) the copies held at its
            source that the far side lacks compete for its remaining capacity in random order.

Output per (p, load): SDP of each scheme, plus contacts per bundle (Vortex plan size) and copies
transmitted per bundle (flooding).
"""
import argparse
import heapq
import json
import os
import random
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from datasets.rucop import parse_contact_plan, build_topology  # noqa: E402
from sdp_score import path_exists  # noqa: E402
from vortex_sdp import solve_and_extract, with_success_prob  # noqa: E402
from oracle_frontier import earliest_arrival, latest_departure, plan_mesh  # noqa: E402


def flood_sim(present, cap, bundle_src, mesh_keys, dst, rng, priority=None):
    """Directed flooding with per-contact capacity; returns ({bundle: arrival time at dst}, transmissions)."""
    remaining = {key: cap[key] for (_, _, _, _, key) in present}
    held, has, open_at = defaultdict(set), defaultdict(set), defaultdict(list)
    events, seq = [], 0
    for (ws, we, u, v, key) in present:
        heapq.heappush(events, (ws, 1, seq, ("open", u, v, key, we))); seq += 1
        heapq.heappush(events, (we, 0, seq, ("close", u, key))); seq += 1
    for b, s in enumerate(bundle_src):
        has[s].add(b)
        heapq.heappush(events, (0.0, 2, seq, ("arrive", s, b))); seq += 1
    delivered, tx = {}, 0
    while events:
        t, _, _, e = heapq.heappop(events)
        if e[0] == "close":
            u, key = e[1], e[2]
            open_at[u] = [c for c in open_at[u] if c[2] != key]
        elif e[0] == "open":
            _, u, v, key, we = e
            open_at[u].append((we, v, key))
            waiting = [b for b in held[u] if b not in has[v] and key in mesh_keys[bundle_src[b]]]
            rng.shuffle(waiting)
            if priority is not None:
                waiting.sort(key=lambda x: priority[x])  # critical copies first, random within a class
            for b in waiting:
                if remaining[key] <= 0:
                    break
                remaining[key] -= 1
                has[v].add(b)
                tx += 1
                heapq.heappush(events, (t, 2, seq, ("arrive", v, b))); seq += 1
        else:
            _, u, b = e
            if b in held[u]:
                continue
            held[u].add(b)
            if u == dst:
                delivered[b] = t
                continue
            for (we, v, key) in open_at[u]:
                if we <= t or remaining[key] <= 0 or b in has[v] or key not in mesh_keys[bundle_src[b]]:
                    continue
                remaining[key] -= 1
                has[v].add(b)
                tx += 1
                heapq.heappush(events, (t, 2, seq, ("arrive", v, b))); seq += 1
    return delivered, tx


class _Dinic:
    """Max-flow (Dinic). Small, dependency-free."""

    def __init__(self, n):
        self.n = n
        self.g = [[] for _ in range(n)]

    def add(self, u, v, cap):
        self.g[u].append([v, cap, len(self.g[v])])
        self.g[v].append([u, 0, len(self.g[u]) - 1])

    def maxflow(self, s, t):
        flow = 0
        while True:
            level = [-1] * self.n
            level[s] = 0
            q = [s]
            for u in q:
                for v, cap, _ in self.g[u]:
                    if cap > 0 and level[v] < 0:
                        level[v] = level[u] + 1
                        q.append(v)
            if level[t] < 0:
                return flow
            it = [0] * self.n
            while True:
                u, path = s, []
                while u != t:
                    while it[u] < len(self.g[u]):
                        v, cap, _ = self.g[u][it[u]]
                        if cap > 0 and level[v] == level[u] + 1:
                            break
                        it[u] += 1
                    if it[u] == len(self.g[u]):
                        if u == s:
                            break
                        level[u] = -1  # dead end: prune and retreat
                        u, _ = path.pop()
                        it[u] += 1
                        continue
                    path.append((u, it[u]))
                    u = self.g[u][it[u]][0]
                if u != t:
                    break
                f = min(self.g[a][i][1] for a, i in path)
                for a, i in path:
                    e = self.g[a][i]
                    e[1] -= f
                    self.g[e[0]][e[2]][1] += f
                flow += f


def capacity_oracle(present, cap, bundle_src, dst):
    """Upper bound on bundles ANY scheme could deliver in this realisation, even knowing every failure:
    max-flow over the time-expanded graph of occurring contacts, each carrying at most cap[contact]
    bundles, unlimited storage at nodes. Relaxation: a contact may be used by a bundle that reaches its
    source before the contact closes, arriving at its far end at the contact's start — so this is an
    upper bound (never below what a real schedule achieves)."""
    events = defaultdict(set)
    for b, s in enumerate(bundle_src):
        events[s].add(0.0)
    for (ws, we, u, v, key) in present:
        events[u].update((ws, we))
        events[v].update((ws, we))
    idx, times = {}, {}
    n = 2
    for node, ts in events.items():
        tl = sorted(ts)
        times[node] = tl
        for t in tl:
            idx[(node, t)] = n
            n += 1
    k0 = n
    n += len(present)
    g = _Dinic(n)
    S, T = 0, 1
    big = 10 ** 9
    for node, tl in times.items():
        for a, b in zip(tl, tl[1:]):
            g.add(idx[(node, a)], idx[(node, b)], big)
        if node == dst:
            for t in tl:
                g.add(idx[(node, t)], T, big)
    for i, (ws, we, u, v, key) in enumerate(present):
        c = cap[key]
        if c <= 0:
            continue
        tl = times[u]
        # latest event at u strictly before the contact closes (storage edges feed earlier arrivals)
        lo, hi = 0, len(tl) - 1
        j = -1
        while lo <= hi:
            mid = (lo + hi) // 2
            if tl[mid] < we:
                j = mid
                lo = mid + 1
            else:
                hi = mid - 1
        if j < 0:
            continue
        g.add(idx[(u, tl[j])], k0 + i, big)
        g.add(k0 + i, idx[(v, ws)], c)
    for s, m in Counter(bundle_src).items():
        g.add(S, idx[(s, 0.0)], m)
    return g.maxflow(S, T)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--contact-plan", required=True)
    ap.add_argument("--dst", default="48")
    ap.add_argument("--srcs", default="7-31")
    ap.add_argument("--loads", default="1,4,8,16,32", help="bundles per cold spot")
    ap.add_argument("--bundle-bytes", type=int, default=1_000_000)
    ap.add_argument("--critical-every", type=int, default=0,
                    help="if > 0, every Nth bundle is critical (priority 1, else 3); adds priority-aware "
                         "flooding and critical-only SDP")
    ap.add_argument("--critical-deadline-h", type=float, default=None,
                    help="deadline (hours after origination) for critical bundles; others keep the full "
                         "horizon. Every scheme is scored against each bundle's own deadline.")
    ap.add_argument("--capacity-oracle", action="store_true",
                    help="add oracle_capacity: max-flow upper bound on deliverable bundles given contact "
                         "capacities (the true ceiling under load); single-deadline runs only")
    ap.add_argument("--allocations", default="PER_TASK,GLOBAL_MARGINAL",
                    help="VORTEX_STOCH_ALLOCATION modes to run")
    ap.add_argument("--pfails", default="0.3,0.5")
    ap.add_argument("--runs", type=int, default=100)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--cut-upstream", type=int, default=8)
    ap.add_argument("--alpha", type=float, default=0.999)
    ap.add_argument("--image", default="vortex-pce-controller:paper")
    ap.add_argument("--scn-root", required=True)
    ap.add_argument("--out", default="flood_vs_crp_load.csv")
    args = ap.parse_args()

    nodes, links, H = parse_contact_plan(args.contact_plan)
    a, b = args.srcs.split("-")
    srcs = [str(i) for i in range(int(a), int(b) + 1) if str(i) in nodes]
    dst, B = args.dst, args.bundle_bytes
    out, inl = {}, {}
    for (u, v), lk in links.items():
        out.setdefault(u, []).append((v, lk["windows"]))
        inl.setdefault(v, []).append((u, lk["windows"]))
    ld = latest_departure(inl, dst, H)
    mesh = {s: plan_mesh(links, earliest_arrival(out, s), ld) for s in srcs}
    cap = {((u, v), w): max(0, int(lk["rate_bps"] * (w[1] - w[0]) / 8 / B))
           for (u, v), lk in links.items() for w in lk["windows"]}
    base_topo = build_topology(nodes, links, H)
    extra_env = {"VORTEX_STOCH_SHARED_TERMINAL": "true",
                 "VORTEX_CUT_UPSTREAM": str(args.cut_upstream),
                 "VORTEX_SOLVE_TIMEOUT_SEC": "1800"}

    allocations = [a.strip() for a in args.allocations.split(",") if a.strip()]
    rows = ["pfail,bundles_per_src,bundles,offered_mb,scheme,sdp,critical_sdp,admitted,contacts_or_tx_per_bundle"]
    print(rows[0])
    for p in [float(x) for x in args.pfails.split(",")]:
        topo = with_success_prob(base_topo, p)
        for m in [int(x) for x in args.loads.split(",")]:
            bundle_src = [s for s in srcs for _ in range(m)]
            crit = [args.critical_every > 0 and i % args.critical_every == 0 for i in range(len(bundle_src))]
            prio = [1 if c else 3 for c in crit]
            deadline = [min(H, args.critical_deadline_h * 3600.0) if (c and args.critical_deadline_h) else H
                        for c in crit]
            vwork = [{"taskId": f"b{i}", "sourceNodeId": s, "destinationNodeId": dst,
                      "originationTimeSec": 0.0, "deadlineSec": deadline[i], "taskSizeBytes": B,
                      "priority": prio[i]}
                     for i, s in enumerate(bundle_src)]
            vortex = {}
            for alloc in allocations:
                scn = os.path.join(args.scn_root, f"p{p}_m{m}_{alloc}")
                os.makedirs(scn, exist_ok=True)
                env = dict(extra_env, VORTEX_STOCH_ALLOCATION=alloc)
                resp, routes_by_task = solve_and_extract(scn, topo, vwork, links, args.image,
                                                         "CUT_ANCHORED_CGR", args.alpha, env)
                committed = []
                for i in range(len(bundle_src)):
                    c = set()
                    for r in routes_by_task.get(f"b{i}") or []:
                        if r:
                            c.update(r)
                    committed.append(c)
                vortex[alloc] = committed
                with open(os.path.join(scn, "solve_summary.json"), "w") as f:
                    json.dump({"committed": resp.get("committedFlowCount"),
                               "unadmitted": resp.get("unadmittedTaskCount"),
                               "computationTimeMs": resp.get("computationTimeMs")}, f)

            rng = random.Random(args.seed)
            schemes = ["oracle", "flooding"] + (["flooding_priority"] if args.critical_every > 0 else []) \
                + (["oracle_capacity"] if args.capacity_oracle else []) \
                + [f"vortex_{a}" for a in allocations]
            tot = {k: 0 for k in schemes}
            tot_c = {k: 0 for k in schemes}
            tx = {"flooding": 0, "flooding_priority": 0}
            for _ in range(args.runs):
                present, present_set, adj = [], set(), {}
                for (u, v), lk in links.items():
                    for w in lk["windows"]:
                        if rng.random() >= p:
                            key = ((u, v), w)
                            present.append((w[0], w[1], u, v, key))
                            present_set.add(key)
                            adj.setdefault(u, []).append((v,) + w)
                reach = {(s, dl): path_exists(adj, s, dst, 0.0, dl) for s, dl in set(zip(bundle_src, deadline))}
                for i, s in enumerate(bundle_src):
                    ok = {"oracle": reach[(s, deadline[i])]}
                    for a in allocations:
                        c = vortex[a][i]
                        hit = False
                        if c:
                            sub = {}
                            for ((u, v), w) in c:
                                if ((u, v), w) in present_set:
                                    sub.setdefault(u, []).append((v,) + w)
                            hit = path_exists(sub, s, dst, 0.0, deadline[i])
                        ok[f"vortex_{a}"] = hit
                    for k, hit in ok.items():
                        tot[k] += hit
                        tot_c[k] += hit and crit[i]
                if args.capacity_oracle:
                    tot["oracle_capacity"] += capacity_oracle(present, cap, bundle_src, dst)
                floods = [("flooding", None)] + ([("flooding_priority", prio)] if args.critical_every > 0 else [])
                for name, pr in floods:
                    arrivals, t = flood_sim(present, cap, bundle_src, mesh, dst, rng, pr)
                    on_time = [b for b, at in arrivals.items() if at <= deadline[b]]
                    tot[name] += len(on_time)
                    tot_c[name] += sum(1 for b in on_time if crit[b])
                    tx[name] += t
            nb = len(bundle_src)
            n = args.runs * nb
            nc = args.runs * max(1, sum(crit))
            for k in schemes:
                if k.startswith("vortex_"):
                    c = vortex[k[len("vortex_"):]]
                    extra = (f"{sum(1 for x in c if x)/nb:.3f}", f"{sum(len(x) for x in c)/nb:.1f}")
                elif k in tx:
                    extra = ("", f"{tx[k]/n:.1f}")
                else:
                    extra = ("", "")
                crit_sdp = f"{tot_c[k]/nc:.4f}" if args.critical_every > 0 else ""
                row = f"{p},{m},{nb},{nb*B/1e6:.1f},{k},{tot[k]/n:.4f},{crit_sdp},{extra[0]},{extra[1]}"
                rows.append(row)
                print(row, flush=True)
    with open(args.out, "w") as f:
        f.write("\n".join(rows) + "\n")
    print(f"# wrote {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
