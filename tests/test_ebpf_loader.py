import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from ebpf.pce_ebpf_loader import PCEBPFManager


class PCEBPFManagerTest(unittest.TestCase):
    def test_update_writes_the_pinned_map_with_bpftool(self):
        with tempfile.TemporaryDirectory() as directory:
            map_path = Path(directory) / "pce_pacing_map"
            map_path.touch()
            runner = Mock(return_value=subprocess.CompletedProcess([], 0))
            manager = PCEBPFManager(map_path=str(map_path), command_runner=runner)

            self.assertTrue(manager.update_pacing_rate("10.0.1.100", 820_000_000))

            command = runner.call_args.args[0]
            self.assertEqual(command[:5], ["bpftool", "map", "update", "pinned", str(map_path)])
            self.assertEqual(command[5:11], ["key", "hex", "0a", "00", "01", "64"])
            self.assertEqual(command[-1], "any")
            self.assertEqual(len(command[command.index("value") + 2:-1]), 40)

    def test_update_fails_when_the_pinned_map_is_missing(self):
        runner = Mock()
        manager = PCEBPFManager(map_path="/missing/pce_pacing_map", command_runner=runner)

        self.assertFalse(manager.update_pacing_rate("10.0.1.100", 1_000_000))
        runner.assert_not_called()

    def test_cleanup_deletes_each_tracked_key(self):
        with tempfile.TemporaryDirectory() as directory:
            map_path = Path(directory) / "pce_pacing_map"
            map_path.touch()
            runner = Mock(return_value=subprocess.CompletedProcess([], 0))
            manager = PCEBPFManager(map_path=str(map_path), command_runner=runner)
            manager.pacing_entries["192.0.2.1"] = 1_000_000

            self.assertTrue(manager.cleanup_stale_entries())
            self.assertEqual(manager.pacing_entries, {})
            command = runner.call_args.args[0]
            self.assertEqual(command[:5], ["bpftool", "map", "delete", "pinned", str(map_path)])
            self.assertEqual(command[-6:], ["key", "hex", "c0", "00", "02", "01"])


if __name__ == "__main__":
    unittest.main()
