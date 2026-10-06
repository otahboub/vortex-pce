"""
Command Line Interface for the crp-pce package.
Usage:
  python3 -m crp_pce.cli compute --policy arch3 --cos medium --laxity-sec 2.0
  python3 -m crp_pce.cli serve --host 127.0.0.1 --port 4189
"""

import argparse
import json
import signal
import sys
import threading
from .planner import FourStagePCEPlanner, ARCHETYPE_TUPLES, IMPLEMENTED_ARCHETYPES
from .pcep import PCEPStatefulServer, PCEPMessage


def build_parser():
    parser = argparse.ArgumentParser(
        description="CRP-PCE: Open-Source Constraint Relaxation Problem (CRP) & DFE Rate Engine with Schedulability CoS (Tahboub & Khan, 2009-2026)"
    )
    subparsers = parser.add_subparsers(dest="command", help="Sub-commands")

    # Command: compute
    compute_parser = subparsers.add_parser("compute", help="Compute flow schedules using 4-stage CRP planner")
    compute_parser.add_argument(
        "--policy", default="arch3", choices=sorted(IMPLEMENTED_ARCHETYPES),
        help="Executable policy archetype (other published archetypes remain taxonomy metadata)",
    )
    compute_parser.add_argument("--payload-gb", type=float, default=10.0, help="Payload volume in GB")
    compute_parser.add_argument("--window-sec", type=float, default=10.0, help="Reservation window in seconds")
    compute_parser.add_argument("--cos", default="high", choices=["high", "medium", "low"], help="Schedulability Class of Service (High/Medium/Low)")
    compute_parser.add_argument("--laxity-sec", type=float, default=0.0, help="Laxity window in seconds (for Medium CoS)")

    # Command: serve
    serve_parser = subparsers.add_parser("serve", help="Start the stateful PCEP TCP server")
    serve_parser.add_argument(
        "--host", default="127.0.0.1",
        help="PCEP bind address (use 0.0.0.0 only behind a trusted transport boundary)",
    )
    serve_parser.add_argument("--port", type=int, default=4189, help="PCEP TCP Port")
    return parser

def main():
    parser = build_parser()
    args = parser.parse_args()

    if args.command == "compute":
        planner = FourStagePCEPlanner(archetype=args.policy)
        sample_flows = [
            {
                "id": "FLOW_HIGH_01",
                "cos_class": "high",
                "payload_bytes": args.payload_gb * 1e9,
                "release_sec": 0.0,
                "deadline_sec": args.window_sec,
                "path": ["node_1_link", "node_2_link"]
            },
            {
                "id": "FLOW_MED_02",
                "cos_class": args.cos,
                "laxity_sec": args.laxity_sec,
                "payload_bytes": (args.payload_gb * 0.5) * 1e9,
                "release_sec": 1.0,
                "deadline_sec": args.window_sec,
                "path": ["node_1_link", "node_2_link"]
            }
        ]
        results = planner.plan_batch(sample_flows)
        print("==================================================================================")
        print(f"   CRP/DFE PLANNING RESULTS [{ARCHETYPE_TUPLES[args.policy]['name']}]")
        print("==================================================================================")
        print(json.dumps(results, indent=2))

    elif args.command == "serve":
        server = PCEPStatefulServer(host=args.host, port=args.port)
        server.start()
        stopped = threading.Event()

        def request_shutdown(_signum, _frame):
            stopped.set()

        signal.signal(signal.SIGTERM, request_shutdown)
        signal.signal(signal.SIGINT, request_shutdown)
        try:
            stopped.wait()
        finally:
            server.stop()
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
