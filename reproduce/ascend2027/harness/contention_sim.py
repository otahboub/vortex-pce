#!/usr/bin/env python3
"""
contention_sim.py — replay committed schedules on a realisation where contacts have finite capacity.

The dedicated scoring used so far (reachability over each bundle's committed contacts, bundle by
bundle) treats capacity as never binding, which holds while reservations are full weight. An
overbooked plan deliberately reserves some hops at less than full weight, so a contact can be asked
to carry more than it holds, and bundles must share it.

This replays what the controller committed, hop by hop:

  - a hop is the list of contact slices the engine reserved for it (a hop can span several windows
    of the same link), each needing rate x slice duration bits of that contact;
  - a copy can take a committed hop of its own bundle if it is at the hop's source by the hop's start,
    every slice's contact occurs, and every such contact still has the bits; it then arrives when the
    engine scheduled (copies can switch between the bundle's committed routes wherever the times
    allow);
  - a contact that occurs carries at most rate x duration bits, shared by every bundle for the run;
  - hops are served first come by start time; ties go to a primary-route hop, then the lower priority
    number (more important), then bundle order.

With unlimited capacity this is the plan's delivery under failures alone; with full-weight
reservations the engine guarantees the bits fit, so the two agree, which is the validation check.
The replay can advance one re-planning interval at a time: `reached` and `remaining` carry over.
"""
from vortex_sdp import window_containing


def edges_from_schedules(schedules, links):
    """Per task id: its committed hops as (start, arrival, u, v, slices, primary), from a solve response.

    The first committed schedule of a task is its primary route."""
    edges, seen = {}, set()
    for sched in schedules:
        task = sched["taskId"]
        primary = task not in seen
        seen.add(task)
        rate = float(sched["committedRateBps"])
        for hop in sched.get("hops", []):
            u, v = str(hop["sourceNodeId"]), str(hop["destinationNodeId"])
            lk = links.get((u, v))
            if lk is None:
                continue
            slices, ok = [], True
            for slot in hop.get("transmissionSlots") or [{"startSec": hop["startSec"], "endSec": hop["endSec"]}]:
                w = window_containing(lk["windows"], float(slot["startSec"]))
                if w is None:
                    ok = False
                    break
                slices.append((((u, v), w), rate * (float(slot["endSec"]) - float(slot["startSec"]))))
            if ok and slices:
                edges.setdefault(task, []).append(
                    (float(hop["startSec"]), float(hop["arrivalSec"]), u, v, tuple(slices), primary))
    return edges


def contact_bits(present, links):
    """Bits each occurring contact can carry: rate x duration."""
    return {key: links[(u, v)]["rate_bps"] * (we - ws) for (ws, we, u, v, key) in present}


def replay(present_set, remaining, reached, edges, priority, dst, t_from, t_to, deadline):
    """Replay every bundle's committed hops that start in [t_from, t_to).

    remaining  {contact key: bits left}; mutated. None means unlimited capacity.
    reached    list, per bundle, of {node: earliest arrival of a copy}; mutated
    edges      list, per bundle, of its committed hops (see edges_from_schedules)
    Returns the set of bundles that reached dst by the deadline during this call."""
    queue = sorted(
        (start, not primary, priority[b], b, i)
        for b, bundle_edges in enumerate(edges)
        for i, (start, _, _, _, _, primary) in enumerate(bundle_edges)
        if t_from <= start < t_to)
    delivered = set()
    for start, _, _, b, i in queue:
        _, arrival, u, v, slices, _ = edges[b][i]
        at_u = reached[b].get(u)
        if at_u is None or at_u > start + 1e-9:
            continue
        if reached[b].get(v, float("inf")) <= arrival:
            continue
        if any(key not in present_set for key, _ in slices):
            continue
        if remaining is not None:
            if any(remaining[key] < bits - 1e-6 for key, bits in slices):
                continue
            for key, bits in slices:
                remaining[key] -= bits
        reached[b][v] = arrival
        if v == dst and arrival <= deadline:
            delivered.add(b)
    return delivered
