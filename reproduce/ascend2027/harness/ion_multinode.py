#!/usr/bin/env python3
"""ion_multinode.py — every node of a contact plan as a live ION node, in one container.

Writes <out>/<n>/ (per-node ionrc/ionconfig/bprc/ionstart), <out>/global.ionrc (the contact plan in
ABSOLUTE UTC so nodes started seconds apart share one clock), and <out>/inject.tsv (bundles to send).
Time is compressed by C: every instant and duration is divided by C and every byte count too, rates
unchanged, so a contact carries 1/C of its bytes in 1/C of its time.

With --overrides, each listed node gets ipnadmin routing overrides (node.ipnrc) and bundles carry their
data label, so a labelled bundle follows a given route hop by hop.

With --link-emu, each node's outduct to a neighbour points at a per-link relay port of ion_linkemu.py,
which forwards only inside the contact windows written to global.ionrc and at their rate (linkemu.json);
each window then lasts exactly its rounded wall duration, so ION's declared volume is the plan's.

Usage: ion_multinode.py --plan RRN-A.contactplan.txt --out DIR --t0 0 --t1 7200 --compress 60
       --start-utc 2026-09-29T20:00:00 [--inject inject.json] [--link-emu]
inject.json: [{"src": "7", "dst": "48", "t": 100.0, "bytes": 1000000, "ttl": 3600, "cos": 1, "id": "b1"}]
"""
import argparse
import calendar
import datetime as dt
import json
import os
from collections import defaultdict


def parse_plan(path):
    contacts, ranges = [], []
    for line in open(path):
        p = line.split()
        if len(p) >= 7 and p[0] == "a" and p[1] in ("contact", "range"):
            s, e = float(p[2].lstrip("+")), float(p[3].lstrip("+"))
            (contacts if p[1] == "contact" else ranges).append((s, e, p[4], p[5], float(p[6])))
    return contacts, ranges


def cos_field(r):
    """The part of bpsendfile's class of service after the custody flag (run.sh prefixes "0."): priority, or
    priority.ordinal.unreliable.critical.label when the bundle carries a data label for a routing override."""
    cos = r.get("cos", 1)
    return f"{cos}.0.0.0.{r['label']}" if r.get("label") is not None else cos


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--t0", type=float, default=0.0)
    ap.add_argument("--t1", type=float, default=86400.0)
    ap.add_argument("--compress", type=float, default=60.0)
    ap.add_argument("--start-utc", required=True, help="wall-clock UTC instant that sim time t0 maps to")
    ap.add_argument("--inject", default=None)
    ap.add_argument("--heap-words", type=int, default=250000)
    ap.add_argument("--link-emu", action="store_true", help="route every link through ion_linkemu.py")
    ap.add_argument("--overrides", default=None,
                    help="JSON [{node, label, dest, src, neighbor}]: per-bundle ION routing overrides (ipnadmin "
                         "'a rtovrd'), so each node forwards a labelled bundle to a given next hop instead of CGR's")
    a = ap.parse_args()

    C = a.compress
    start = dt.datetime.fromisoformat(a.start_utc)
    stamp = lambda t: (start + dt.timedelta(seconds=(t - a.t0) / C)).strftime("%Y/%m/%d-%H:%M:%S")
    contacts, ranges = parse_plan(a.plan)
    contacts = [c for c in contacts if c[1] > a.t0 and c[0] < a.t1]
    nodes = sorted({c[2] for c in contacts} | {c[3] for c in contacts}, key=int)
    neigh = {n: sorted({c[3] for c in contacts if c[2] == n and c[3] != n}, key=int) for n in nodes}
    port = lambda n: 20000 + int(n)
    relay = lambda u, v: 30000 + 100 * int(u) + int(v)      # the link emulator's port for link u -> v
    if a.link_emu:
        assert all(int(n) < 100 for n in nodes), "relay ports assume node ids below 100"
    epoch = lambda d: calendar.timegm(d.timetuple())
    emu = defaultdict(lambda: {"windows": []})
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "global.ionrc"), "w") as f:
        f.write("m horizon +0\n")
        for n in nodes:  # loopback so each node can deliver to itself
            f.write(f"a contact {stamp(a.t0)} {stamp(a.t1)} {n} {n} 100000000\n")
            f.write(f"a range {stamp(a.t0)} {stamp(a.t1)} {n} {n} 1\n")
        seen = set()
        for s, e, u, v, rate in contacts:
            s, e = max(s, a.t0), min(e, a.t1)
            if e - s < 1e-9:
                continue
            # Compressed contacts are sub-second; ION timestamps are whole seconds, so round outward
            # to at least one second and keep the byte volume by scaling the rate.
            ws, we = stamp(s), stamp(e)
            dur_wall = max(1.0, round((e - s) / C))
            vol = rate * (e - s) / C
            if a.link_emu:
                # The window lasts exactly dur_wall whole seconds, so rate x window = the plan's volume,
                # and the relay enforces the same window and rate.
                w0 = (start + dt.timedelta(seconds=(s - a.t0) / C)).replace(microsecond=0)
                w1 = w0 + dt.timedelta(seconds=dur_wall)
                ws, we = w0.strftime("%Y/%m/%d-%H:%M:%S"), w1.strftime("%Y/%m/%d-%H:%M:%S")
                if u != v:
                    emu[(u, v)]["windows"].append([epoch(w0), epoch(w1), max(1, int(vol / dur_wall))])
            f.write(f"a contact {ws} {we if we != ws else stamp(s + C)} {u} {v} {max(1, int(vol / dur_wall))}\n")
            if (u, v) not in seen:
                seen.add((u, v))
                f.write(f"a range {stamp(a.t0)} {stamp(a.t1)} {u} {v} 1\n")
    ovrds = json.load(open(a.overrides)) if a.overrides else []
    for n in nodes:
        d = os.path.join(a.out, n)
        os.makedirs(d, exist_ok=True)
        open(os.path.join(d, "node.ionrc"), "w").write(f"1 {n} node.ionconfig\ns\n")
        open(os.path.join(d, "node.ionconfig"), "w").write(
            f"wmKey {1000 + int(n)}\nsdrName ion{n}\nwmSize 20000000\nconfigFlags 1\n"
            f"heapWords {a.heap_words}\npathName /tmp/ion{n}\n")
        eps = "".join(f"a endpoint ipn:{n}.{k} q\n" for k in [0, 1, 2, 64, 65] + list(range(100, 132)))
        hop = (lambda m: relay(n, m)) if a.link_emu else port
        ducts = "".join(f"a outduct udp 127.0.0.1:{hop(m)} udpclo\n" for m in neigh[n])
        plans = "".join(f"a plan ipn:{m}.0\na planduct ipn:{m}.0 udp 127.0.0.1:{hop(m)}\n" for m in neigh[n])
        open(os.path.join(d, "node.bprc"), "w").write(
            f"1\na scheme ipn 'ipnfw' 'ipnadminep'\n{eps}a protocol udp\n"
            f"a induct udp 127.0.0.1:{port(n)} udpcli\n{ducts}{plans}w 1\ns\n")
        start = "#!/usr/bin/env bash\nionadmin node.ionrc\nionadmin ../global.ionrc\nbpadmin node.bprc\n"
        mine = [o for o in ovrds if str(o["node"]) == n]
        if mine:
            open(os.path.join(d, "node.ipnrc"), "w").write("".join(
                f"a rtovrd {o['label']} {o['dest']} {o['src']} {o['neighbor']}\n" for o in mine))
            start += "ipnadmin node.ipnrc\n"
        open(os.path.join(d, "ionstart"), "w").write(start)
    open(os.path.join(a.out, "nodes.txt"), "w").write("\n".join(nodes) + "\n")
    if a.link_emu:
        links = [{"src": u, "dst": v, "listen": relay(u, v), "to": port(v), "windows": spec["windows"]}
                 for (u, v), spec in sorted(emu.items())]
        json.dump({"links": links}, open(os.path.join(a.out, "linkemu.json"), "w"))
    if a.inject:
        rows = json.load(open(a.inject))
        with open(os.path.join(a.out, "inject.tsv"), "w") as f:
            for r in sorted(rows, key=lambda r: r["t"]):
                f.write("\t".join(str(x) for x in [
                    (r["t"] - a.t0) / C, r["src"], r["dst"], max(1, int(r["bytes"] / C)),
                    max(1, int(r["ttl"] / C)), cos_field(r), r["id"]]) + "\n")
    print(f"{len(nodes)} nodes, {len(contacts)} contacts, t in [{a.t0},{a.t1}] /{C}: "
          f"{(a.t1 - a.t0) / C:.0f} s wall")


if __name__ == "__main__":
    main()
