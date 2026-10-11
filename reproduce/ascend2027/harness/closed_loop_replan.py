#!/usr/bin/env python3
"""
closed_loop_replan.py — does re-planning as failures are revealed close Vortex's gap under load?

§4f found that under load an offline Vortex plan reaches 24–41% of the capacity-aware ceiling at
p=0.3–0.5 while flooding reaches 66–79%: every admitted route reserves all of its hops, and when an
early hop fails the reservations behind it sit idle while other bundles wait. This harness adds the
closed loop and compares, on the SAME failure realisations:

  V0        the real engine, planned once at t=0 and never revised (the §4f arm)
  V1        the real engine, re-planned every --interval-h hours, acting on what it knew
            --report-delay-h earlier (0: everything that has happened by t). At each boundary t:
              - a bundle that has been delivered releases whatever it still holds;
              - a bundle whose committed contacts no longer offer any path to the destination (a
                contact it depended on was seen to fail) is cancelled, releasing its reservations,
                and re-offered from the node that holds it;
              - a bundle that was never admitted is re-offered from its source.
            Re-offered bundles are solved together with origination time t, so the engine can only
            plan onto contacts from t on. Only contacts that have already started are known to have
            failed or occurred; the plan for the future still sees each contact's success probability.
  flooding  event-driven directed flooding with per-contact capacity (flood_vs_crp_load.flood_sim)
  ceiling   the capacity-aware oracle: max-flow over the realised time-expanded graph

By default delivery for V0 and V1 is reachability through committed contacts that occur (a copy at a
shared node can take any committed onward contact), exactly as V0 was scored in §4f; full-weight
reservations are capacity-feasible by construction, so no contention is simulated. With --contention
both are scored by replaying the committed schedules with contacts sharing their real capacity
(contention_sim.py), which an overbooked plan (--overbook-safety) needs; that replay is stricter, so
its numbers are not comparable to the default scoring.

Usage: python3 closed_loop_replan.py --contact-plan ... --scn-root DIR --pfails 0.3 --loads 8
       [--runs 20] [--interval-h 1] [--report-delay-h 0] [--contention] [--overbook-safety 1]
       [--out results/closed_loop_replan.csv]
"""
import argparse
import heapq
import json
import os
import random
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from datasets.rucop import parse_contact_plan, build_topology  # noqa: E402
from sdp_score import path_exists  # noqa: E402
from vortex_sdp import (API, KEY, boot_controller, hops_to_route, http_post_solve,  # noqa: E402
                        kill_controller, with_success_prob)
from flood_vs_crp_load import capacity_oracle, flood_sim  # noqa: E402
from oracle_frontier import earliest_arrival, latest_departure, plan_mesh  # noqa: E402
from contention_sim import contact_bits, edges_from_schedules, replay  # noqa: E402


def cancel(task_id):
    req = urllib.request.Request(f"{API}/api/v1/tasks/{task_id}", method="DELETE",
                                 headers={"X-API-Key": KEY})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def advance(reached, plan, present_set, until, deadline):
    """Extend a bundle's reached nodes over committed contacts that occur and depart before `until`.

    Same rule as sdp_score.path_exists: depart at max(arrival, contact start), before the contact
    ends and no later than the deadline; transmission is instantaneous."""
    adj = {}
    for key in plan:
        if key in present_set:
            (u, v), (ws, we) = key
            adj.setdefault(u, []).append((v, ws, we))
    pq = [(t, n) for n, t in reached.items()]
    heapq.heapify(pq)
    while pq:
        t, u = heapq.heappop(pq)
        if t > reached.get(u, float("inf")):
            continue
        for (v, ws, we) in adj.get(u, []):
            dep = max(t, ws)
            if dep < we and dep <= deadline and dep < until and dep < reached.get(v, float("inf")):
                reached[v] = dep
                heapq.heappush(pq, (dep, v))


def still_viable(reached, plan, present_set, now, dst, deadline):
    """Can the committed contacts still deliver? Contacts that started before `now` count only if they
    occurred; later ones are still possible."""
    adj = {}
    for key in plan:
        (u, v), (ws, we) = key
        if ws < now and key not in present_set:
            continue
        adj.setdefault(u, []).append((v, ws, we))
    return any(path_exists(adj, n, dst, t, deadline) for n, t in reached.items())


def best_holder(reached, out, now, dst, deadline):
    """The reached node with the earliest possible arrival at dst over contacts from `now` on."""
    best = None
    for node, t in reached.items():
        start = max(now, t)
        ea = {node: start}
        pq = [(start, node)]
        while pq:
            tt, u = heapq.heappop(pq)
            if tt > ea.get(u, float("inf")):
                continue
            for b, wins in out.get(u, []):
                for (ws, we) in wins:
                    d = max(tt, ws)
                    if d < we:
                        if d <= deadline and d < ea.get(b, float("inf")):
                            ea[b] = d
                            heapq.heappush(pq, (d, b))
                        break
        if dst in ea and (best is None or ea[dst] < best[1]):
            best = (node, ea[dst], start)
    return best


def plans_from(resp, links):
    """Per task: every committed contact, and the contacts of its primary (first committed) route."""
    plans, primaries = {}, {}
    for sched in resp.get("committedSchedules", []):
        route = hops_to_route(sched.get("hops", []), links)
        if route:
            plans.setdefault(sched["taskId"], set()).update(route)
            primaries.setdefault(sched["taskId"], set(route))
    return plans, primaries


def run_once(present, present_set, bundle_src, dst, H, links, out, interval, topo_scn, args, env, cap):
    """One realisation: V0 (plan once) and V1 (re-plan each interval) on the same engine boot."""
    n = len(bundle_src)
    boot_controller(topo_scn, args.image, "CUT_ANCHORED_CGR", args.alpha, env)
    stats = {"solves": 0, "solve_ms": 0, "reserved_contacts": 0}
    try:
        reached = [{s: 0.0} for s in bundle_src]
        task = [None] * n
        plan = [set() for _ in range(n)]
        # With --contention the committed hops are replayed with contacts sharing their real capacity.
        plan_edges = [[] for _ in range(n)]
        priority = [3] * n
        remaining = contact_bits(present, links)
        delivered = [False] * n
        dead = [False] * n
        v0_plan = None
        t = 0.0
        epoch = 0
        # The controller acts on the network as it was `delay` ago: which contacts had failed, where
        # each bundle was, which had arrived. Physics runs on the real state. Copies persist at the
        # nodes a bundle reached, so a plan from a stale position is still physically valid.
        lag = round(args.report_delay_h / args.interval_h)
        history = []
        while t < H:
            history.append(([dict(r) for r in reached], list(delivered)))
            view_reached, view_delivered = history[max(0, len(history) - 1 - lag)]
            known_until = max(0.0, t - args.report_delay_h * 3600.0)
            # 1. Release what the controller knows has arrived; find plans it knows are broken.
            offer = []
            for i in range(n):
                if view_delivered[i] or dead[i]:
                    if task[i]:
                        cancel(task[i])
                        task[i] = None
                        plan_edges[i] = []
                    continue
                if task[i] and still_viable(view_reached[i], plan[i], present_set, known_until, dst, H):
                    continue
                if task[i]:
                    cancel(task[i])
                    task[i] = None
                    plan[i] = set()
                    plan_edges[i] = []
                holder = best_holder(view_reached[i], out, t, dst, H)
                if holder is None:
                    dead[i] = True
                    continue
                offer.append((i, holder[0], holder[2]))
            # 2. Re-offer them together, from where each bundle is, no earlier than now.
            if offer:
                work = [{"taskId": f"b{i}e{epoch}", "sourceNodeId": node, "destinationNodeId": dst,
                         "originationTimeSec": round(start, 3), "deadlineSec": H,
                         "taskSizeBytes": args.bundle_bytes, "priority": 3}
                        for (i, node, start) in offer]
                resp = http_post_solve(work)
                stats["solves"] += 1
                stats["solve_ms"] += int(resp.get("computationTimeMs") or 0)
                plans, _ = plans_from(resp, links)
                edges = edges_from_schedules(resp.get("committedSchedules", []), links)
                for (i, _, _) in offer:
                    tid = f"b{i}e{epoch}"
                    if tid in plans:
                        task[i] = tid
                        plan[i] = plans[tid]
                        plan_edges[i] = edges.get(tid, [])
                        stats["reserved_contacts"] += len(plans[tid])
            if v0_plan is None:
                v0_plan = [set(p) for p in plan]
                v0_edges = [list(e) for e in plan_edges]
                if not args.closed_loop:
                    break
            # 3. Let the realisation play out until the next boundary.
            t_next = min(H, t + interval)
            if args.contention:
                for i in replay(present_set, remaining, reached, plan_edges, priority, dst,
                                t, t_next if t_next < H else float("inf"), H):
                    delivered[i] = True
            else:
                for i in range(n):
                    if not delivered[i] and plan[i]:
                        advance(reached[i], plan[i], present_set, t_next if t_next < H else H + 1, H)
                        if dst in reached[i]:
                            delivered[i] = True
            t = t_next
            epoch += 1
    finally:
        kill_controller()

    # Plan-once: dedicated scoring (each bundle alone on its committed contacts) and, with
    # --contention, the same plan with contacts shared at their real capacity.
    v0_dedicated = 0
    for i, s in enumerate(bundle_src):
        sub = {}
        for key in v0_plan[i]:
            if key in present_set:
                (u, v), w = key
                sub.setdefault(u, []).append((v,) + w)
        v0_dedicated += path_exists(sub, s, dst, 0.0, H)
    stats["v0_dedicated"] = v0_dedicated
    v0 = v0_dedicated
    stats["v0_replay_unlimited"] = 0
    if args.contention:
        # Validation: with full-weight reservations the committed bits fit every contact, so a
        # replay with finite capacity must equal one with unlimited capacity.
        stats["v0_replay_unlimited"] = len(replay(
            present_set, None, [{s: 0.0} for s in bundle_src], v0_edges, [3] * n, dst,
            0.0, float("inf"), H))
        v0 = len(replay(present_set, contact_bits(present, links), [{s: 0.0} for s in bundle_src],
                        v0_edges, [3] * n, dst, 0.0, float("inf"), H))
    v0_contacts = sum(len(p) for p in v0_plan)
    return v0, v0_contacts, sum(delivered), stats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--contact-plan", required=True)
    ap.add_argument("--dst", default="48")
    ap.add_argument("--srcs", default="7-31")
    ap.add_argument("--loads", default="8,32", help="bundles per cold spot")
    ap.add_argument("--pfails", default="0.3,0.5")
    ap.add_argument("--runs", type=int, default=20)
    ap.add_argument("--interval-h", type=float, default=1.0)
    ap.add_argument("--report-delay-h", type=float, default=0.0,
                    help="the controller learns failures, positions and arrivals this late; a multiple "
                         "of --interval-h")
    ap.add_argument("--bundle-bytes", type=int, default=1_000_000)
    ap.add_argument("--alpha", type=float, default=0.999)
    ap.add_argument("--cut-upstream", type=int, default=8)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--contention", action="store_true",
                    help="score plans with contacts shared at their real capacity (contention_sim)")
    ap.add_argument("--overbook-safety", type=float, default=0.0,
                    help="> 0 turns on VORTEX_STOCH_OVERBOOK=REACH_PROBABILITY with this safety factor")
    ap.add_argument("--no-closed-loop", dest="closed_loop", action="store_false",
                    help="plan once only (V0), for a quick check")
    ap.add_argument("--image", default="vortex-pce-controller:paper")
    ap.add_argument("--scn-root", required=True)
    ap.add_argument("--out", default="closed_loop_replan.csv")
    args = ap.parse_args()
    lag = args.report_delay_h / args.interval_h
    if args.report_delay_h < 0 or abs(lag - round(lag)) > 1e-9:
        ap.error("--report-delay-h must be a non-negative multiple of --interval-h")

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
    env = {"VORTEX_STOCH_SHARED_TERMINAL": "true", "VORTEX_CUT_UPSTREAM": str(args.cut_upstream),
           "VORTEX_STOCH_ALLOCATION": "GLOBAL_MARGINAL", "VORTEX_SOLVE_TIMEOUT_SEC": "1800"}
    if args.overbook_safety > 0:
        env["VORTEX_STOCH_OVERBOOK"] = "REACH_PROBABILITY"
        env["VORTEX_STOCH_OVERBOOK_SAFETY"] = str(args.overbook_safety)
    base_topo = build_topology(nodes, links, H)

    header = ("pfail,bundles_per_src,bundles,offered_mb,runs,ceiling,flooding,v0,v1,"
              "v0_contacts_per_bundle,flood_tx_per_bundle,v1_reserved_per_bundle,"
              "v1_solves_per_run,v1_solve_ms_per_run,wall_s_per_run,interval_h,report_delay_h,"
              "contention,overbook_safety,v0_dedicated,v0_replay_unlimited")
    rows = [header]
    print(header, flush=True)
    for p in [float(x) for x in args.pfails.split(",")]:
        topo = with_success_prob(base_topo, p)
        scn = os.path.join(args.scn_root, f"p{p}")
        os.makedirs(scn, exist_ok=True)
        json.dump(topo, open(os.path.join(scn, "topology.json"), "w"))
        for m in [int(x) for x in args.loads.split(",")]:
            bundle_src = [s for s in srcs for _ in range(m)]
            nb = len(bundle_src)
            rng = random.Random(args.seed)
            tot = {"ceiling": 0, "flooding": 0, "v0": 0, "v1": 0, "v0c": 0, "tx": 0,
                   "res": 0, "solves": 0, "ms": 0, "wall": 0.0, "v0d": 0, "v0u": 0}
            for _ in range(args.runs):
                present, present_set = [], set()
                for (u, v), lk in links.items():
                    for w in lk["windows"]:
                        if rng.random() >= p:
                            key = ((u, v), w)
                            present.append((w[0], w[1], u, v, key))
                            present_set.add(key)
                tot["ceiling"] += capacity_oracle(present, cap, bundle_src, dst)
                arrivals, tx = flood_sim(present, cap, bundle_src, mesh, dst, rng)
                tot["flooding"] += sum(1 for at in arrivals.values() if at <= H)
                tot["tx"] += tx
                t0 = time.time()
                v0, v0c, v1, st = run_once(present, present_set, bundle_src, dst, H, links, out,
                                           args.interval_h * 3600.0, scn, args, env, cap)
                tot["v0d"] += st["v0_dedicated"]
                tot["v0u"] += st["v0_replay_unlimited"]
                tot["wall"] += time.time() - t0
                tot["v0"] += v0
                tot["v0c"] += v0c
                tot["v1"] += v1
                tot["res"] += st["reserved_contacts"]
                tot["solves"] += st["solves"]
                tot["ms"] += st["solve_ms"]
            r, nbr = args.runs, args.runs * nb
            row = (f"{p},{m},{nb},{nb * B / 1e6:.0f},{r},{tot['ceiling'] / nbr:.4f},"
                   f"{tot['flooding'] / nbr:.4f},{tot['v0'] / nbr:.4f},"
                   f"{(tot['v1'] / nbr if args.closed_loop else float('nan')):.4f},"
                   f"{tot['v0c'] / nbr:.1f},{tot['tx'] / nbr:.1f},{tot['res'] / nbr:.1f},"
                   f"{tot['solves'] / r:.1f},{tot['ms'] / r:.0f},{tot['wall'] / r:.1f},"
                   f"{args.interval_h},{args.report_delay_h},"
                   f"{int(args.contention)},{args.overbook_safety},{tot['v0d'] / nbr:.4f},"
                   f"{tot['v0u'] / nbr:.4f}")
            rows.append(row)
            print(row, flush=True)
            with open(args.out, "w") as f:
                f.write("\n".join(rows) + "\n")


if __name__ == "__main__":
    main()
