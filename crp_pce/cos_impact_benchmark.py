"""Deterministic workload exercise for the executable Python planner prototype.

The historical function name is retained for compatibility. This module does
not compare policy archetypes and must not be treated as an empirical benchmark.
"""

from typing import Any, Dict

from .planner import FourStagePCEPlanner


def run_cos_impact_benchmark() -> Dict[str, Any]:
    """Run one declared workload through the implemented ``arch3`` policy."""
    workload = []
    for i in range(5):
        workload.append({
            "id": f"FLOW_HIGH_{i + 1:02d}",
            "cos_class": "high",
            "payload_bytes": 10e9,
            "release_sec": float(i * 2),
            "deadline_sec": float(i * 2 + 10),
            "laxity_sec": 0.0,
            "path": ["link_1", "link_2", "link_3"],
        })
    for i in range(10):
        workload.append({
            "id": f"FLOW_MED_{i + 1:02d}",
            "cos_class": "medium",
            "payload_bytes": 5e9,
            "release_sec": float(i),
            "deadline_sec": float(i + 12),
            "laxity_sec": 3.0,
            "path": ["link_1", "link_2", "link_3"],
        })
    for i in range(5):
        workload.append({
            "id": f"FLOW_LOW_{i + 1:02d}",
            "cos_class": "low",
            "payload_bytes": 2e9,
            "release_sec": float(i * 3),
            "deadline_sec": float(i * 3 + 20),
            "laxity_sec": 10.0,
            "path": ["link_1", "link_2", "link_3"],
        })

    result = FourStagePCEPlanner(archetype="arch3").plan_batch(workload)
    return {
        "scope": "single-policy deterministic prototype exercise",
        "comparative_benchmark": False,
        "transit_storage_modeled": False,
        "workload_composition": {"total": 20, "high": 5, "medium": 10, "low": 5},
        "arch3_result": result,
    }


if __name__ == "__main__":
    exercise = run_cos_impact_benchmark()
    result = exercise["arch3_result"]
    print("Python arch3 deterministic workload exercise")
    print(f"Admitted: {result['admitted_count']}/{result['total_flows']}")
    print("Transit storage is not modeled by this exercise.")
