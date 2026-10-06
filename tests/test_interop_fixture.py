import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
FRR_CONFIG = ROOT / "tests" / "interop" / "frr" / "frr.conf"
INTEROP_WORKFLOW = ROOT / ".github" / "workflows" / "interop.yml"


class FRRInteropFixtureTest(unittest.TestCase):
    def test_fixture_does_not_configure_an_mpls_forwarding_policy(self):
        config = FRR_CONFIG.read_text(encoding="utf-8")

        for dataplane_directive in (
            "binding-sid",
            "candidate-path",
            "segment-list",
            "mpls label",
        ):
            self.assertNotIn(dataplane_directive, config)

    def test_workflow_starts_zebra_before_pathd_and_then_loads_config(self):
        workflow = INTEROP_WORKFLOW.read_text(encoding="utf-8")

        image = workflow.index(
            "quay.io/frrouting/frr@sha256:"
            "65e5967b922572c0565d968388fb06af69d7e9b3b3eea40ad7e3810687667f68"
        )
        zebra = workflow.index("/usr/lib/frr/zebra -d")
        zebra_ready = workflow.index("wait_for_socket /var/run/frr/zserv.api")
        pathd = workflow.index("/usr/lib/frr/pathd -d")
        pathd_ready = workflow.index("wait_for_socket /var/run/frr/pathd.vty")
        load_config = workflow.index("vtysh -b")

        self.assertNotIn("modprobe", workflow)
        self.assertIn(
            'tests/interop/frr/vtysh.conf\":/etc/frr/vtysh.conf:ro', workflow
        )
        self.assertLess(image, zebra)
        self.assertLess(zebra, zebra_ready)
        self.assertLess(zebra_ready, pathd)
        self.assertLess(pathd, pathd_ready)
        self.assertLess(pathd_ready, load_config)


if __name__ == "__main__":
    unittest.main()
