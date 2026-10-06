"""
4-Stage PCE Planner Decomposition Framework v2.0.
PCE = <H1 Demand Selection, F1 Candidate Path Generation, H2 Allocation & Rate Admission, F2 Persistent Commitment>
Includes Schedulability Class of Service (High/Medium/Low) & Penalty Optimization.
"""

from __future__ import annotations
from typing import Dict, List, Any
from .dfe import DFEEngine, SpaceTimeLedger, compute_cos_penalty

ARCHETYPE_TUPLES = {
    "arch1": {"name": "Static Shortest-Path Burst (CSPF)", "h1": "FCFS", "f1": "FAST_K", "h2": "LINE_RATE", "f2": "Calendar"},
    "arch2": {"name": "Multi-Path Fair-Alloc (SWAN/B4 LP)", "h1": "LWF", "f1": "Multi-Tunnel", "h2": "Multi-Path LP", "f2": "WAN TE"},
    "arch3": {"name": "Advance Reservation DFE Prototype", "h1": "LWEEF_CoS", "f1": "Caller Path", "h2": "DFE Rate", "f2": "In-Memory Link Ledger"},
    "arch4": {"name": "Time-Gated (IEEE 802.1Qbv TSN)", "h1": "Priority", "f1": "Single-Path", "h2": "Microsecond GCL", "f2": "Switch GCL"},
    "arch5": {"name": "Storage-Assisted (NetStitcher)", "h1": "LWEEF", "f1": "Storage Edges", "h2": "Store-and-Forward", "f2": "Node Storage"},
    "arch6": {"name": "Snapshot-Routing (iSatCR / SGR)", "h1": "FCFS Orbit", "f1": "Snapshots", "h2": "LINE_RATE_ISL", "f2": "SRv6 ERO"},
    "arch7": {"name": "Deterministic Contact (NASA ION CGR)", "h1": "Contact Prio", "f1": "Contact Dijkstra", "h2": "Contact Volume", "f2": "CGR Ledgers"},
    "arch8": {"name": "Stochastic-Contact (PRoPHET / RUCoP)", "h1": "Risk Weight", "f1": "Chance Search", "h2": "SLA Chance alpha", "f2": "Stoch Ledgers"}
}

# The remaining entries above are a comparison taxonomy, not executable policies.
IMPLEMENTED_ARCHETYPES = frozenset({"arch3"})

COS_PRIORITY = {
    "HIGH": 3,
    "MEDIUM": 2,
    "LOW": 1
}

class FourStagePCEPlanner:
    """
    CRP/DFE v2.0 4-Stage PCE Decision Planner Framework Engine.
    """
    def __init__(self, archetype: str = "arch3"):
        if archetype not in ARCHETYPE_TUPLES:
            raise ValueError(f"Unknown policy archetype: {archetype}. Choose from {list(ARCHETYPE_TUPLES.keys())}")
        if archetype not in IMPLEMENTED_ARCHETYPES:
            raise NotImplementedError(
                f"Policy archetype {archetype} is taxonomy-only; executable support is currently limited to arch3"
            )

        self.archetype = archetype
        self.tuple_config = ARCHETYPE_TUPLES[archetype]
        self.ledger = SpaceTimeLedger()
        self.dfe_engine = DFEEngine(self.ledger)

    def sort_demands(self, flows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Stage 1 (H1): Demand Selection Policy with CoS Tiering."""
        policy = self.tuple_config["h1"]
        if policy == "LWEEF_CoS" or policy == "LWEEF":
            # Sort by CoS Tier first (HIGH > MEDIUM > LOW), then by Slack/Deadline Window
            return sorted(
                flows,
                key=lambda f: (
                    -COS_PRIORITY.get(f.get("cos_class", "HIGH").upper(), 1),
                    f["deadline_sec"] + f.get("laxity_sec", 0.0) - f["release_sec"],
                    f["release_sec"]
                )
            )
        elif policy == "FCFS":
            return sorted(flows, key=lambda f: f["release_sec"])
        elif policy == "Priority":
            return sorted(flows, key=lambda f: f.get("priority", 0), reverse=True)
        return flows

    def plan_batch(self, flows: List[Dict[str, Any]]) -> Dict[str, Any]:
        """
        Executes the full CRP/DFE v2.0 planning pipeline over a batch of flow demands.
        """
        sorted_flows = self.sort_demands(flows)
        admitted = []
        rejected = []

        cos_stats = {"HIGH": 0, "MEDIUM": 0, "LOW": 0}
        total_penalty = 0.0

        for flow in sorted_flows:
            path = flow.get("path", ["link_0_1"])
            cos = flow.get("cos_class", "HIGH").lower()
            laxity = flow.get("laxity_sec", 0.0)

            success, rate_bps, metrics = self.dfe_engine.schedule_flow(
                flow_id=flow["id"],
                path_links=path,
                payload_bytes=flow["payload_bytes"],
                release_time=flow["release_sec"],
                deadline_time=flow["deadline_sec"],
                cos_class=cos,
                laxity_sec=laxity
            )

            if success:
                admitted.append({**flow, "allocated_rate_mbps": rate_bps / 1e6, **metrics})
                cos_stats[cos.upper()] = cos_stats.get(cos.upper(), 0) + 1
                total_penalty += metrics.get("penalty_score", 0.0)
            else:
                rejected.append({**flow, "reason": metrics.get("reason", "Capacity constraint")})

        far = (len(admitted) / len(flows)) * 100.0 if flows else 0.0
        return {
            "version": "CRP/DFE Powered PCEP v2.0",
            "archetype": self.archetype,
            "tuple": self.tuple_config,
            "total_flows": len(flows),
            "admitted_count": len(admitted),
            "rejected_count": len(rejected),
            "far_pct": far,
            "cos_breakdown": cos_stats,
            "total_penalty_score": round(total_penalty, 2),
            "admitted_flows": admitted,
            "rejected_flows": rejected
        }
