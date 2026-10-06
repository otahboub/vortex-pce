import unittest

from crp_pce.dfe import DFEEngine, SpaceTimeLedger
from crp_pce.planner import FourStagePCEPlanner


class SpaceTimeLedgerTest(unittest.TestCase):
    def test_non_overlapping_reservations_are_not_summed(self):
        ledger = SpaceTimeLedger(default_link_capacity_bps=100)
        self.assertTrue(ledger.allocate("L", 0, 5, 40))
        self.assertTrue(ledger.allocate("L", 5, 10, 40))

        self.assertEqual(ledger.get_available_capacity("L", 0, 10), 60)

    def test_overlapping_reservations_use_peak_concurrent_load(self):
        ledger = SpaceTimeLedger(default_link_capacity_bps=100)
        self.assertTrue(ledger.allocate("L", 0, 10, 40))
        self.assertTrue(ledger.allocate("L", 5, 15, 30))

        self.assertEqual(ledger.get_available_capacity("L", 0, 15), 30)


class DFERateContractTest(unittest.TestCase):
    def test_deadline_window_rate_includes_propagation(self):
        rate = DFEEngine().compute_equilibrated_rate(
            payload_bytes=100,
            release_time=0,
            deadline_time=10,
            propagation_delay_sec=1,
        )

        self.assertAlmostEqual(rate, (100 * 8) / (10 - 1))


class PlannerArchetypeTest(unittest.TestCase):
    def test_arch3_remains_executable(self):
        planner = FourStagePCEPlanner("arch3")
        result = planner.plan_batch([
            {
                "id": "F1",
                "cos_class": "high",
                "payload_bytes": 1_000_000,
                "release_sec": 0.0,
                "deadline_sec": 10.0,
                "path": ["L"],
            }
        ])

        self.assertEqual(result["admitted_count"], 1)

    def test_taxonomy_only_archetype_fails_explicitly(self):
        with self.assertRaisesRegex(NotImplementedError, "taxonomy-only"):
            FourStagePCEPlanner("arch7")


if __name__ == "__main__":
    unittest.main()
