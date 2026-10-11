#!/usr/bin/env python3
"""
lcrns_contactplan.py — the NASA LCRNS lunar-relay geometry as an ION contact plan for the R_DET harness.

Emits the same text format as fixtures/RRN-A_16sats_Walker.contactplan.txt, so a1_flagship.py (via
--plan/--srcs/--dst) and ion_multinode.py run on it unchanged:
    a contact +<start> +<end> <from> <to> <rate_bytes_per_s>
    a range   +<start> +<end> <from> <to> <owlt_s>

Geometry is reused, not re-derived: harness/lcrns_contact_plan.py propagates the five published LCRNS
Reference Constellation 3.1 ELFO satellites (two-body, 60 s slots, 24 h, fixed inertial Earth direction,
5 deg surface elevation mask). Its relay->Earth visibility is used as is; its surface-visibility test,
written for one site at 85 S, is applied here unchanged to a grid of sites across LunaNet Service
Volume 2 (south of 75 S). The grid contains that reference site, and the generator checks that it
reproduces lcrns_contact_plan's surface windows exactly.

Nodes (integer ids, laid out like RRN-A: ground side first, sources, satellites, destination last):
    1-2    Earth ground antennas (gen_engine_scenario.ANTENNAS = 2, the scarce resource)
    3-18   lunar surface users, latitudes 75/80/85/89 S x longitudes 0/90/180/270 E (the sources)
    19-23  LCRNS relays SV-1..SV-5
    24     Earth destination (mission operations centre; the sink)

Contacts (all symmetric, as in RRN-A):
    surface <-> relay   while the relay is above the site's 5 deg mask          37,500 B/s
    relay   <-> antenna while the antenna is scheduled on that relay              12,500 B/s
    antenna <-> dest    continuous terrestrial backhaul (RRN-A's GS -> server)   125,000,000 B/s
The surface and downlink rates are gen_engine_scenario's UPLINK_BPS (30 Mb/s) and CHANNEL_BPS
(10 Mb/s) scaled by 1/100, which puts the relay->Earth channel at RRN-A's per-contact rate (12,500 B/s)
so 1 MB bundles saturate the plan as they do on RRN-A. The backhaul rate is RRN-A's.

Antenna schedule. A contact plan cannot express "two antennas shared by five relays", so the antennas
are scheduled the way ground networks are: per 60 s slot, an antenna stays on its relay while the relay
is Earth-visible, for at most PASS_MAX_MIN; a free antenna takes the Earth-visible, unserved relay with
the least downlink time so far (ties to the lower SV). This is the one modelling choice not in
lcrns_contact_plan.py and is recorded in the sidecar.

Ranges: one per directed pair that ever has a contact, over the whole horizon. One-way light time is
whole seconds (ION ranges are integer): relay <-> antenna 1 s (Earth-Moon 1.28 s), all others 0.

Usage: lcrns_contactplan.py [--out fixtures/LCRNS_lunar.contactplan.txt] [--meta <out>.json]
"""
import argparse
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))  # harness/lcrns_contact_plan.py
import lcrns_contact_plan as L  # noqa: E402

SLOT = L.SLOT_SECONDS
HORIZON_S = L.HORIZON * SLOT                     # 24 h
SITE_LATS = [-75.0, -80.0, -85.0, -89.0]         # LunaNet SV2 is south of -75
SITE_LONS = [0.0, 90.0, 180.0, 270.0]
ANTENNAS = 2                                     # gen_engine_scenario.ANTENNAS
PASS_MAX_MIN = 120
SURFACE_RATE = 37_500                            # bytes/s: UPLINK_BPS 30 Mb/s / 8 / 100
DOWNLINK_RATE = 12_500                           # bytes/s: CHANNEL_BPS 10 Mb/s / 8 / 100 (= RRN-A)
BACKHAUL_RATE = 125_000_000                      # bytes/s: RRN-A ground station -> server
OWLT_EARTH_MOON = 1

ANT_IDS = list(range(1, 1 + ANTENNAS))
SITE_IDS = list(range(ANT_IDS[-1] + 1, ANT_IDS[-1] + 1 + len(SITE_LATS) * len(SITE_LONS)))
RELAY_IDS = list(range(SITE_IDS[-1] + 1, SITE_IDS[-1] + 1 + len(L.LCRNS)))
DST_ID = RELAY_IDS[-1] + 1


def _site_pos(lat_deg, lon_deg, t):
    """lcrns_contact_plan._site_pos for an arbitrary site (the original is fixed at SITE_LAT_DEG, 0 E)."""
    lat = math.radians(lat_deg)
    lon = math.radians(lon_deg) + L.MOON_ROT_RATE * t
    return (L.R_MOON * math.cos(lat) * math.cos(lon),
            L.R_MOON * math.cos(lat) * math.sin(lon),
            L.R_MOON * math.sin(lat))


def surface_visibility(sites):
    """vis[site][relay][slot], the same elevation-mask test as lcrns_contact_plan.build_lcrns_visibility."""
    mask = math.sin(math.radians(L.MASK_DEG))
    vis = [[[False] * L.HORIZON for _ in L.LCRNS] for _ in sites]
    for tslot in range(L.HORIZON):
        t = tslot * SLOT
        pos = [L._elem_to_pos(elem, t) for elem in L.LCRNS]
        for k, (lat, lon) in enumerate(sites):
            site = _site_pos(lat, lon, t)
            up = L._unit(site)
            for r, p in enumerate(pos):
                if L._dot(L._unit(L._sub(p, site)), up) >= mask:
                    vis[k][r][tslot] = True
    return vis


def schedule_antennas(earth):
    """on[antenna][relay][slot]: each antenna tracks one Earth-visible relay per slot (see module doc)."""
    nrel = len(earth)
    on = [[[False] * L.HORIZON for _ in range(nrel)] for _ in range(ANTENNAS)]
    track = [None] * ANTENNAS            # relay each antenna is on
    pass_len = [0] * ANTENNAS
    served = [0] * nrel                  # downlink slots so far
    for s in range(L.HORIZON):
        for a in range(ANTENNAS):
            r = track[a]
            if r is not None and (not earth[r][s] or pass_len[a] >= PASS_MAX_MIN * 60 // SLOT):
                track[a] = None
        for a in range(ANTENNAS):
            busy = set(track)
            free = [r for r in range(nrel) if earth[r][s] and r not in busy]
            if track[a] is None and free:
                track[a] = min(free, key=lambda r: (served[r], r))
                pass_len[a] = 0
        for a in range(ANTENNAS):
            r = track[a]
            if r is not None:
                on[a][r][s] = True
                served[r] += 1
                pass_len[a] += 1
    return on


def windows(row):
    """Boolean per-slot -> [(start_s, end_s)] (as gen_engine_scenario.windows, integer seconds)."""
    out, start = [], None
    for t, up in enumerate(row):
        if up and start is None:
            start = t
        elif not up and start is not None:
            out.append((start * SLOT, t * SLOT))
            start = None
    if start is not None:
        out.append((start * SLOT, len(row) * SLOT))
    return out


def build():
    sites = [(lat, lon) for lat in SITE_LATS for lon in SITE_LONS]
    ref_surf, earth = L.build_lcrns_visibility()
    surf = surface_visibility(sites)
    ref = sites.index((L.SITE_LAT_DEG, 0.0))
    if surf[ref] != ref_surf:
        raise SystemExit("surface visibility does not reproduce lcrns_contact_plan at the reference site")
    on = schedule_antennas(earth)

    contacts, owlt = [], {}

    def link(u, v, wins, rate, lt):
        for ws, we in wins:
            contacts.append((ws, we, u, v, rate))
            contacts.append((ws, we, v, u, rate))
        if wins:
            owlt[(u, v)] = owlt[(v, u)] = lt

    for k, sid in enumerate(SITE_IDS):
        for r, rid in enumerate(RELAY_IDS):
            link(sid, rid, windows(surf[k][r]), SURFACE_RATE, 0)
    for a, aid in enumerate(ANT_IDS):
        for r, rid in enumerate(RELAY_IDS):
            link(rid, aid, windows(on[a][r]), DOWNLINK_RATE, OWLT_EARTH_MOON)
        link(aid, DST_ID, [(0, HORIZON_S)], BACKHAUL_RATE, 0)
    contacts.sort(key=lambda c: (c[0], c[1], min(c[2], c[3]), max(c[2], c[3]), c[2] > c[3]))

    lines = [f"a contact +{s} +{e} {u} {v} {rate}" for s, e, u, v, rate in contacts]
    lines += [f"a range +0 +{HORIZON_S} {u} {v} {lt}" for (u, v), lt in sorted(owlt.items())]

    nodes = {}
    for a, aid in enumerate(ANT_IDS):
        nodes[str(aid)] = {"role": "ground_antenna", "name": f"Earth ground antenna {a + 1}"}
    for (lat, lon), sid in zip(sites, SITE_IDS):
        nodes[str(sid)] = {"role": "surface_user", "name": f"surface site {abs(lat):g}S {lon:g}E",
                           "lat_deg": lat, "lon_deg": lon}
    for r, rid in enumerate(RELAY_IDS):
        nodes[str(rid)] = {"role": "relay", "name": f"LCRNS SV-{r + 1}"}
    nodes[str(DST_ID)] = {"role": "earth_destination", "name": "mission operations centre (Earth)"}
    stats = {
        "surface_relay_coverage_pct": [round(100 * sum(any(surf[k][r][s] for r in range(len(RELAY_IDS)))
                                                       for s in range(L.HORIZON)) / L.HORIZON, 1)
                                       for k in range(len(sites))],
        "relay_earth_visible_pct": [round(100 * sum(e) / L.HORIZON, 1) for e in earth],
        "relay_downlink_scheduled_pct": [round(100 * sum(any(on[a][r][s] for a in range(ANTENNAS))
                                                         for s in range(L.HORIZON)) / L.HORIZON, 1)
                                         for r in range(len(RELAY_IDS))],
    }
    meta = {
        "plan": "LCRNS_lunar.contactplan.txt",
        "generator": "tests/hulc/cloud/orchestrate/datasets/lcrns_contactplan.py",
        "geometry": "NASA LCRNS Reference Constellation 3.1 (5-SV ELFO, epoch 2027-03-01), propagated by "
                    "tests/hulc/lcrns_contact_plan.py: two-body, fixed inertial Earth direction, "
                    f"{L.MASK_DEG:g} deg surface mask, {SLOT} s slots",
        "horizon_s": HORIZON_S,
        "srcs": [str(s) for s in SITE_IDS],
        "srcs_arg": f"{SITE_IDS[0]}-{SITE_IDS[-1]}",
        "dst": str(DST_ID),
        "roles": {"ground_antenna": [str(i) for i in ANT_IDS], "surface_user": [str(i) for i in SITE_IDS],
                  "relay": [str(i) for i in RELAY_IDS], "earth_destination": [str(DST_ID)]},
        "nodes": nodes,
        "rates_bytes_per_s": {"surface_relay": SURFACE_RATE, "relay_antenna": DOWNLINK_RATE,
                              "antenna_destination": BACKHAUL_RATE},
        "owlt_s": {"relay_antenna": OWLT_EARTH_MOON, "other": 0},
        "antenna_schedule": f"{ANTENNAS} antennas; each stays on an Earth-visible relay for at most "
                            f"{PASS_MAX_MIN} min, then (or when the relay is occulted) moves to the "
                            "Earth-visible unserved relay with the least downlink time so far",
        "counts": {"nodes": len(nodes), "contacts": len(contacts), "ranges": len(owlt)},
        "coverage": stats,
    }
    return lines, meta


def main():
    ap = argparse.ArgumentParser(description="NASA LCRNS lunar relay -> ION contact plan (R_DET).")
    ap.add_argument("--out", default=os.path.join(HERE, "fixtures/LCRNS_lunar.contactplan.txt"))
    ap.add_argument("--meta", default=None, help="sidecar JSON (default: <out minus .txt>.json)")
    a = ap.parse_args()
    lines, meta = build()
    with open(a.out, "w") as f:
        f.write("\n".join(lines) + "\n")
    meta_path = a.meta or (a.out[:-4] if a.out.endswith(".txt") else a.out) + ".json"
    meta["plan"] = os.path.basename(a.out)
    with open(meta_path, "w") as f:
        json.dump(meta, f, indent=1)
        f.write("\n")
    print(json.dumps(meta["counts"]), "srcs", meta["srcs_arg"], "dst", meta["dst"])


if __name__ == "__main__":
    main()
