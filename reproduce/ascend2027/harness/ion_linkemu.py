#!/usr/bin/env python3
"""
ion_linkemu.py — a link emulator for the multi-node ION harness: each directed link exists only inside
its contact windows and carries at most its contact rate.

Without it, ION's nodes talk over the container's loopback, which never goes down: a bundle queued for a
neighbour keeps crossing after the contact has closed (ion_volume_check.py). Here every node's outduct
to a neighbour points at a relay port for that directed link; the relay forwards each datagram to the
neighbour's induct like a serial link:

  - a datagram is transmitted at max(arrival, link free) and takes size / rate seconds;
  - each window carries at most rate x duration bytes, its planned volume;
  - it is delivered when its transmission ends, if that is no later than the window's end plus a grace
    period, and the window's volume is not used up;
  - otherwise (outside every window, cut off, or over volume) it is dropped, as a real link would lose
    it. The UDP convergence layer has no retransmission, so the bundle is lost.

Windows and rates are exactly the ones ION was given in global.ionrc (wall clock, whole seconds), so the
relay enforces ION's own contact plan rather than a different one. The grace period (default 2 s)
absorbs ION's one-second clock, on which it starts and ends contacts about a second late; it adds no
volume, only timing slack, and that slack can only favour ION.

Config (written by ion_multinode.py --link-emu): {"links": [{"src": "7", "dst": "32", "listen": 30732,
"to": 20032, "windows": [[t0, t1, rate], ...]}, ...]}, windows in epoch seconds, rates in bytes/s.
Stdlib only. Runs until SIGTERM, then writes per-link counters to --stats.

Usage: ion_linkemu.py CONFIG.json --stats STATS.json
"""
import argparse
import asyncio
import bisect
import json
import signal
import socket
import time


TRACE = bool(int(__import__("os").environ.get("LINKEMU_TRACE", "0")))


class Link(asyncio.DatagramProtocol):
    def __init__(self, spec, loop, grace):
        self.spec = spec
        self.loop = loop
        self.to = ("127.0.0.1", spec["to"])
        self.windows = sorted((float(a), float(b), float(r)) for a, b, r in spec["windows"])
        self.grace = grace
        self.used = [0.0] * len(self.windows)  # bytes carried per window
        self.starts = [w[0] for w in self.windows]
        self.free = 0.0                       # when the link finishes its current transmission
        self.sock = None
        self.n = {"fwd": 0, "fwd_bytes": 0, "drop_closed": 0, "drop_closed_bytes": 0, "drop_cut": 0,
                  "drop_cut_bytes": 0, "drop_volume": 0, "drop_volume_bytes": 0}

    def connection_made(self, transport):
        self.transport = transport

    def window_at(self, t):
        """Index of the latest window that has started by t and has not ended, grace included."""
        i = bisect.bisect_right(self.starts, t) - 1
        if i >= 0 and t < self.windows[i][1] + self.grace:
            return i
        return None

    def datagram_received(self, data, addr):
        now = time.time()
        size = len(data)
        i = self.window_at(now)
        if TRACE:
            print(f"trace {self.spec['src']}->{self.spec['dst']} t={now:.3f} size={size} window="
                  f"{None if i is None else self.windows[i]} free={self.free:.3f}", flush=True)
        if i is None:
            self.n["drop_closed"] += 1
            self.n["drop_closed_bytes"] += size
            return
        t0, t1, rate = self.windows[i]
        if self.used[i] + size > rate * (t1 - t0) + 1e-6:
            self.n["drop_volume"] += 1
            self.n["drop_volume_bytes"] += size
            return
        begin = max(now, self.free)
        done = begin + size / rate
        if done > t1 + self.grace:
            self.n["drop_cut"] += 1
            self.n["drop_cut_bytes"] += size
            return
        self.free = done
        self.used[i] += size
        self.n["fwd"] += 1
        self.n["fwd_bytes"] += size
        self.loop.call_later(max(0.0, done - now), self.sock.sendto, data, self.to)


async def main(config, stats, grace):
    loop = asyncio.get_running_loop()
    out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    out.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 8 << 20)
    links = []
    for spec in json.load(open(config))["links"]:
        link = Link(spec, loop, grace)
        link.sock = out
        await loop.create_datagram_endpoint(lambda link=link: link, local_addr=("127.0.0.1", spec["listen"]))
        links.append(link)
    stop = asyncio.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)
    print(f"linkemu: {len(links)} links up", flush=True)
    await stop.wait()
    json.dump({f"{l.spec['src']}->{l.spec['dst']}": l.n for l in links}, open(stats, "w"), indent=0)
    tot = {k: sum(l.n[k] for l in links) for k in links[0].n} if links else {}
    print("linkemu:", json.dumps(tot), flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("config")
    ap.add_argument("--stats", required=True)
    ap.add_argument("--grace", type=float, default=2.0, help="seconds after each window's end (no extra volume)")
    a = ap.parse_args()
    asyncio.run(main(a.config, a.stats, a.grace))
