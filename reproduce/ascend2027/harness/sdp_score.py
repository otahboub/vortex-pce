#!/usr/bin/env python3
"""
sdp_score.py — R_STOCH Successful-Delivery-Probability (SDP) scorer for the RUCoP ring-road benchmark.

Matches how RUCoP (Raverta/Fraire et al., arXiv:2108.07092) reports results: SDP vs contact FAILURE
PROBABILITY on RRN-A (16-sat Walker, cold spots -> server, all-to-one). For each failure probability p,
Monte-Carlo N realizations sample each nominal contact as present with prob (1-p), and we compute:

  * oracle : a temporal (store-and-forward) path exists to the destination in THIS realization.
  * cgr    : the NOMINAL best route (earliest-arrival on the full plan) survives the realization
             (basic CGR commits to a route on the schedule; if a contact on it fails, the bundle is
             lost). This is the baseline the paper's CGR curve measures.
  * vortex : (optional, --vortex-schedules) a VortexPCE committed route survives the realization.

SDP = delivered / offered, averaged over realizations. Sweep p to reproduce the paper's curves and
overlay Vortex. Connectivity/route-survival model (no capacity) — matches the paper's routing-survival
SDP; add capacity for the R_DET oversubscription study separately.

Reuses rucop.parse_contact_plan for the ION/DtnSim contact-plan format.
"""
import argparse
import heapq
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from datasets.rucop import parse_contact_plan  # noqa: E402


def nominal_route(links, src, dst, t0, horizon):
    """Earliest-arrival temporal path on the FULL (no-failure) plan. Returns the ordered list of
    contact keys ((a,b),(ws,we)) used, or None. This is what basic CGR commits to."""
    # adjacency: node -> list of (nbr, ws, we, key)
    adj = {}
    for (a, b), lk in links.items():
        for (ws, we) in lk["windows"]:
            adj.setdefault(a, []).append((b, ws, we, ((a, b), (ws, we))))
    best = {src: t0}
    pred = {src: None}
    pq = [(t0, src)]
    while pq:
        t, u = heapq.heappop(pq)
        if t > best.get(u, float("inf")):
            continue
        if u == dst:
            break
        for (v, ws, we, key) in adj.get(u, []):
            dep = max(t, ws)
            if dep < we and dep <= horizon:
                if dep < best.get(v, float("inf")):
                    best[v] = dep
                    pred[v] = (u, key)
                    heapq.heappush(pq, (dep, v))
    if dst not in pred:
        return None
    route, cur = [], dst
    while pred.get(cur):
        u, key = pred[cur]
        route.append(key)
        cur = u
    return list(reversed(route))


def path_exists(present_adj, src, dst, t0, horizon):
    """Does a temporal path exist in a sampled realization? (oracle)."""
    best = {src: t0}
    pq = [(t0, src)]
    while pq:
        t, u = heapq.heappop(pq)
        if u == dst:
            return True
        if t > best.get(u, float("inf")):
            continue
        for (v, ws, we) in present_adj.get(u, []):
            dep = max(t, ws)
            if dep < we and dep <= horizon and dep < best.get(v, float("inf")):
                best[v] = dep
                heapq.heappush(pq, (dep, v))
    return dst in best


def route_survives(route, present_set):
    """A committed route survives iff every contact on it occurred."""
    return all(key in present_set for key in route)


def build_workload(links, srcs, dst, bundles_per_src, horizon, rng):
    """All-to-one: each cold spot sends `bundles_per_src` bundles spread over the horizon."""
    w = []
    for s in srcs:
        for k in range(bundles_per_src):
            w.append((s, dst, rng.uniform(0, horizon * 0.8)))
    return w


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--contact-plan", required=True)
    ap.add_argument("--dst", default="48")
    ap.add_argument("--srcs", default="7-31", help="range a-b or comma list")
    ap.add_argument("--bundles-per-src", type=int, default=10)
    ap.add_argument("--pfails", default="0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9")
    ap.add_argument("--runs", type=int, default=100)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--vortex-schedules", default=None,
                    help="JSON {taskId: [route keys]} of VortexPCE committed routes to score too")
    args = ap.parse_args()

    nodes, links, horizon = parse_contact_plan(args.contact_plan)
    if "-" in args.srcs:
        a, b = args.srcs.split("-"); srcs = [str(i) for i in range(int(a), int(b) + 1)]
    else:
        srcs = args.srcs.split(",")
    srcs = [s for s in srcs if s in nodes]
    dst = args.dst
    rng = random.Random(args.seed)
    workload = build_workload(links, srcs, dst, args.bundles_per_src, horizon, rng)

    # Precompute each bundle's nominal CGR route once (on the full plan).
    nominal = {}
    for i, (s, d, t0) in enumerate(workload):
        nominal[i] = nominal_route(links, s, d, t0, horizon)

    pfails = [float(x) for x in args.pfails.split(",")]
    print("pfail,cgr_sdp,oracle_sdp,nominal_reachable")
    for p in pfails:
        cgr_tot = oracle_tot = 0
        for _ in range(args.runs):
            present_adj, present_set = {}, set()
            for (a, b), lk in links.items():
                for (ws, we) in lk["windows"]:
                    if rng.random() >= p:               # contact occurs w.p. (1-p)
                        present_adj.setdefault(a, []).append((b, ws, we))
                        present_set.add(((a, b), (ws, we)))
            for i, (s, d, t0) in enumerate(workload):
                if nominal[i] and route_survives(nominal[i], present_set):
                    cgr_tot += 1
                if path_exists(present_adj, s, d, t0, horizon):
                    oracle_tot += 1
        n = args.runs * len(workload)
        reach = sum(1 for i in nominal if nominal[i]) / max(1, len(workload))
        print(f"{p},{cgr_tot/n:.3f},{oracle_tot/n:.3f},{reach:.3f}")


if __name__ == "__main__":
    main()
