"""Batch admission in the Python planner must be all-or-nothing.

The engine prechecks a path and then commits it. If the commit can partially succeed, a caller
told its flow was admitted holds a reservation for a path the network cannot actually carry, and
the ledger reports capacity that no longer exists.
"""
import unittest

from crp_pce.dfe import DFEEngine, SpaceTimeLedger


class RepeatedLinkAdmissionTest(unittest.TestCase):

    def setUp(self):
        self.ledger = SpaceTimeLedger(default_link_capacity_bps=100.0)
        self.engine = DFEEngine(self.ledger)

    def schedule(self, path, payload_bytes=100.0):
        # 100 bytes = 800 bits over a 10 s window = 80 bps.
        return self.engine.schedule_flow(
            "F1", path, payload_bytes=payload_bytes, release_time=0.0, deadline_time=10.0,
            propagation_delay_sec=0.0, cos_class="high")

    def test_repeated_link_demand_is_aggregated_not_checked_independently(self):
        # Each traversal carries the whole flow, so ["L", "L"] needs 160 bps of a 100 bps link.
        admitted, _rate, metrics = self.schedule(["L", "L"])

        self.assertFalse(admitted, "a path needing 160 bps of a 100 bps link must be rejected")
        self.assertIn("capacity exceeded", metrics["reason"])

    def test_a_rejected_batch_consumes_no_capacity(self):
        self.schedule(["L", "L"])

        self.assertEqual({}, self.ledger.reservations,
                         "a rejected flow must leave no reservation behind")
        self.assertEqual(100.0, self.ledger.get_available_capacity("L", 0.0, 10.0),
                         "capacity must be fully restored after a rejected admission")

    def test_a_rejected_batch_preserves_earlier_flows(self):
        self.assertTrue(self.ledger.allocate("L", 0.0, 10.0, 30.0))
        before = {k: list(v) for k, v in self.ledger.reservations.items()}

        admitted, _rate, _metrics = self.schedule(["L", "L"])

        self.assertFalse(admitted)
        self.assertEqual(before, self.ledger.reservations,
                         "an existing reservation must survive a failed admission")

    def test_a_feasible_repeated_link_path_is_still_admitted(self):
        # 25 bytes = 200 bits over 10 s = 20 bps; two traversals need 40 bps of 100 bps.
        admitted, _rate, _metrics = self.schedule(["L", "L"], payload_bytes=25.0)

        self.assertTrue(admitted, "aggregation must not reject a path that genuinely fits")
        self.assertEqual(40.0, 100.0 - self.ledger.get_available_capacity("L", 0.0, 10.0),
                         "both traversals must be charged to the link")

    def test_a_simple_multi_link_path_is_unaffected(self):
        admitted, _rate, _metrics = self.schedule(["A", "B", "C"])

        self.assertTrue(admitted)
        for link in ("A", "B", "C"):
            self.assertEqual(20.0, self.ledger.get_available_capacity(link, 0.0, 10.0))


class LedgerAtomicityTest(unittest.TestCase):

    def setUp(self):
        self.ledger = SpaceTimeLedger(default_link_capacity_bps=100.0)

    def test_allocate_all_rolls_back_when_any_entry_cannot_fit(self):
        entries = [("A", 0.0, 10.0, 60.0), ("B", 0.0, 10.0, 60.0), ("A", 0.0, 10.0, 60.0)]

        self.assertFalse(self.ledger.allocate_all(entries))
        self.assertEqual({}, self.ledger.reservations)

    def test_allocate_all_commits_every_entry_when_all_fit(self):
        entries = [("A", 0.0, 10.0, 40.0), ("B", 0.0, 10.0, 40.0)]

        self.assertTrue(self.ledger.allocate_all(entries))
        self.assertEqual(60.0, self.ledger.get_available_capacity("A", 0.0, 10.0))
        self.assertEqual(60.0, self.ledger.get_available_capacity("B", 0.0, 10.0))

    def test_allocate_all_rolls_back_when_an_entry_raises(self):
        self.assertTrue(self.ledger.allocate("A", 0.0, 10.0, 10.0))
        before = {k: list(v) for k, v in self.ledger.reservations.items()}

        with self.assertRaises(ValueError):
            self.ledger.allocate_all([("B", 0.0, 10.0, 10.0), ("C", 0.0, 10.0, -1.0)])

        self.assertEqual(before, self.ledger.reservations,
                         "an invalid entry must not leave earlier entries of the batch applied")


if __name__ == "__main__":
    unittest.main()
