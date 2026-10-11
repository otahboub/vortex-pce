#!/usr/bin/env python3
"""
fetch_rucop_plans.py — download the two published RUCoP ring-road contact plans the paper uses.

RRN-A (16-satellite Walker constellation) and RRN-B (16-satellite A-Track constellation) are third-party
data from the DtnSim simulator's repository, so this package does not redistribute them. This script
downloads them from a pinned DtnSim commit and checks each file's SHA-256 against the exact bytes behind
the paper's results, then writes them under datasets/fixtures/ with the names the harness expects.

Usage: python3 fetch_rucop_plans.py            (from reproduce/ascend2027/)
"""
import hashlib
import os
import sys
import urllib.request

DTNSIM = "https://bitbucket.org/lcd-unc-ar/dtnsim/raw/8598c5cc4b84d02dd06d9d6513fdb91a36ea25dc"
SRC = "dtnsim/simulations/hindawi_rrn/contactPlan/RingRoad_16sats_{}_6hotspots_simtime24hs_comrange1000km.txt"
PLANS = [
    ("RRN-A_16sats_Walker.contactplan.txt", "Walker",
     "fe363dabdd182e292f5e3b74a28558245b2caf9d3aa7730db60d52afdb9763d2"),
    ("RRN-B_16sats_ATrack.contactplan.txt", "ATrack",
     "2adf0c010eeef2c642dfb43f001d657c0be29c40b8f7210a59dc2baf4000b474"),
]
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "datasets", "fixtures")


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, kind, sha in PLANS:
        path = os.path.join(OUT, name)
        if os.path.exists(path) and hashlib.sha256(open(path, "rb").read()).hexdigest() == sha:
            print(f"ok      {name} (already present)")
            continue
        url = f"{DTNSIM}/{SRC.format(kind)}"
        data = urllib.request.urlopen(url, timeout=60).read()
        got = hashlib.sha256(data).hexdigest()
        if got != sha:
            sys.exit(f"checksum mismatch for {name}: expected {sha}, got {got} ({url})")
        open(path, "wb").write(data)
        print(f"fetched {name} ({len(data):,} bytes, sha256 ok)")


if __name__ == "__main__":
    main()
