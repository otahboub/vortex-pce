"""
Experimental rate-pacing and interval-ledger engine.
Introduces Schedulability Class of Service (CoS):
- High (Strict): Hard deadline d_h, infinite penalty on miss.
- Medium (Laxed): Soft deadline d_h + Delta t_lax, quadratic tardiness penalty.
- Low (Best Effort): Non-real-time fill-in traffic, linear tardiness penalty.

Calculates a deadline-derived pacing rate. This module reserves the same interval
on every caller-supplied path link; it does not model per-hop transit storage.
"""

from __future__ import annotations
import math
from typing import Any, Dict, List, Tuple, Optional

class SpaceTimeLedger:
    """
    In-memory interval ledger for link reservations.

    This is not a time-expanded graph and does not model node resources.
    """
    def __init__(self, default_link_capacity_bps: float = 10e9):
        self.default_capacity = default_link_capacity_bps
        # link_id -> list of (start_t, end_t, allocated_rate_bps)
        self.reservations: Dict[str, List[Tuple[float, float, float]]] = {}

    def get_available_capacity(self, link_id: str, start_t: float, end_t: float) -> float:
        if not math.isfinite(start_t) or not math.isfinite(end_t) or end_t <= start_t:
            raise ValueError("capacity query must use a finite, non-empty interval")
        if link_id not in self.reservations:
            return self.default_capacity

        reservations = self.reservations[link_id]
        events = {start_t}
        for r_start, r_end, _rate in reservations:
            if max(start_t, r_start) < min(end_t, r_end):
                events.add(max(start_t, r_start))
                if r_end < end_t:
                    events.add(r_end)

        peak_used = max(
            sum(rate for r_start, r_end, rate in reservations if r_start <= event < r_end)
            for event in events
        )
        return max(0.0, self.default_capacity - peak_used)

    def allocate(self, link_id: str, start_t: float, end_t: float, rate_bps: float) -> bool:
        if not link_id or not math.isfinite(rate_bps) or rate_bps <= 0:
            raise ValueError("allocation requires a link ID and a positive finite rate")
        avail = self.get_available_capacity(link_id, start_t, end_t)
        if avail < rate_bps:
            return False

        if link_id not in self.reservations:
            self.reservations[link_id] = []
        self.reservations[link_id].append((start_t, end_t, rate_bps))
        return True

    def allocate_all(
        self, entries: List[Tuple[str, float, float, float]]
    ) -> bool:
        """Commit every entry or none of them.

        Allocating entries one at a time and ignoring the results lets a batch that passed its
        prechecks succeed halfway: earlier allocations consume the capacity that later ones were
        checked against. A caller told the flow was admitted would then be holding a partial
        reservation for a path the network cannot carry.

        :param entries: ``(link_id, start_t, end_t, rate_bps)`` tuples to apply atomically
        :return: ``True`` when every entry was applied
        """
        snapshot = {link_id: list(spans) for link_id, spans in self.reservations.items()}
        try:
            for link_id, start_t, end_t, rate_bps in entries:
                if not self.allocate(link_id, start_t, end_t, rate_bps):
                    self.reservations = snapshot
                    return False
            return True
        except Exception:
            self.reservations = snapshot
            raise


def compute_cos_penalty(
    completion_time: float,
    deadline_time: float,
    cos_class: str = "high",
    laxity_sec: float = 0.0
) -> Tuple[float, bool]:
    """
    Computes CRP/DFE v2.0 tardiness penalty score P(h, completion_time):
    - High CoS: P(tau) = 0 if tau <= d_h else inf (Hard constraint)
    - Medium CoS: P(tau) = 0 if tau <= d_h else (tau - d_h)^2 if tau <= d_h + laxity else inf
    - Low CoS: P(tau) = max(0, tau - d_h) * 0.1
    Returns (penalty_score, is_acceptable).
    """
    tardiness = max(0.0, completion_time - deadline_time)
    cos = cos_class.lower()

    if cos == "high":
        if tardiness > 0.001: # Strict hard deadline
            return float("inf"), False
        return 0.0, True
    elif cos == "medium":
        if tardiness == 0.0:
            return 0.0, True
        if tardiness <= laxity_sec:
            # Smooth quadratic tardiness penalty within laxity window
            return (tardiness ** 2) * 100.0, True
        # Exceeded laxity window
        return float("inf"), False
    elif cos == "low":
        # Best effort linear penalty
        return tardiness * 5.0, True

    return 0.0, True


class DFEEngine:
    """
    CRP/DFE v2.0 Rate Calculator & Pacing Engine with Schedulability CoS.
    """
    def __init__(self, ledger: Optional[SpaceTimeLedger] = None):
        self.ledger = ledger or SpaceTimeLedger()

    def compute_equilibrated_rate(
        self,
        payload_bytes: float,
        release_time: float,
        deadline_time: float,
        propagation_delay_sec: float = 0.02,
        cos_class: str = "high",
        laxity_sec: float = 0.0
    ) -> float:
        """
        Computes DFE v2.0 rate matching e* in Bits Per Second (bps):
        e* = (V_h * 8) / (d_h + Delta t_allowed - r_h - tau_prop)
        """
        allowed_delay = 0.0
        if cos_class.lower() == "medium":
            allowed_delay = laxity_sec

        effective_deadline = deadline_time + allowed_delay
        available_window = effective_deadline - release_time - propagation_delay_sec

        if available_window <= 0:
            raise ValueError("Deadline window is smaller than propagation delay.")

        volume_bits = payload_bytes * 8.0
        e_star_bps = volume_bits / available_window
        return e_star_bps

    def schedule_flow(
        self,
        flow_id: str,
        path_links: List[str],
        payload_bytes: float,
        release_time: float,
        deadline_time: float,
        propagation_delay_sec: float = 0.02,
        cos_class: str = "high",
        laxity_sec: float = 0.0
    ) -> Tuple[bool, float, Dict[str, Any]]:
        """
        Schedules a flow using CRP/DFE v2.0 rate matching across path links with CoS & Penalty awareness.
        Returns (success, e_star_bps, metrics).
        """
        e_star_bps = self.compute_equilibrated_rate(
            payload_bytes, release_time, deadline_time, propagation_delay_sec, cos_class, laxity_sec
        )

        # Calculate completion time under e_star_bps
        duration_sec = (payload_bytes * 8.0) / e_star_bps + propagation_delay_sec
        completion_t = release_time + duration_sec

        penalty_score, is_acceptable = compute_cos_penalty(completion_t, deadline_time, cos_class, laxity_sec)
        if not is_acceptable:
            return False, e_star_bps, {
                "reason": f"Deadline violation for CoS {cos_class.upper()} (tardiness exceeded laxity {laxity_sec}s)"
            }

        # A path may traverse the same link more than once. Each traversal carries the full
        # flow, so the link must supply the rate once per occurrence; checking each occurrence
        # against the same untouched capacity would admit a flow the link cannot carry.
        required_bps: Dict[str, float] = {}
        for link_id in path_links:
            required_bps[link_id] = required_bps.get(link_id, 0.0) + e_star_bps

        for link_id, demand_bps in required_bps.items():
            avail = self.ledger.get_available_capacity(link_id, release_time, completion_t)
            if avail < demand_bps:
                return False, e_star_bps, {"reason": f"Link {link_id} capacity exceeded"}

        # Commit atomically. The prechecks above read a consistent snapshot, but the commit must
        # still be all-or-nothing so a rejected entry cannot leave earlier ones applied.
        committed = self.ledger.allocate_all([
            (link_id, release_time, completion_t, demand_bps)
            for link_id, demand_bps in required_bps.items()
        ])
        if not committed:
            return False, e_star_bps, {
                "reason": "Reservation could not be committed atomically; no capacity was consumed"
            }

        metrics = {
            "flow_id": flow_id,
            "cos_class": cos_class.upper(),
            "e_star_mbps": e_star_bps / 1e6,
            "completion_time_sec": round(completion_t, 3),
            "laxity_used_sec": max(0.0, round(completion_t - deadline_time, 3)),
            "penalty_score": round(penalty_score, 2),
            "transit_reservoir_bytes": None,
            "transit_reservoir_modeled": False,
            "source_input_bytes": payload_bytes,
            "source_holding_modeled": False,
        }
        return True, e_star_bps, metrics
