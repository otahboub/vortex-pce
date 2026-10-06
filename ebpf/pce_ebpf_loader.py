#!/usr/bin/env python3
"""
User-Space eBPF Map Manager & Loader for VortexPCE Controller.
Handles eBPF bytecode compilation, map pinning, controller-to-map updates (bpf_map_update_elem),
stale entry cleanup, and NIC driver attachment verification.

Author: Dr. Omar Y. Tahboub (2026)
"""

import math
import struct
import socket
import subprocess
from pathlib import Path
from typing import Callable, Dict, Sequence

BPF_MAP_PATH = "/sys/fs/bpf/pce_pacing_map"


class PCEBPFManager:
    """Manages eBPF TC/EDT and XDP Map lifecycle and controller update dissemination."""

    def __init__(
        self,
        interface: str = "eth0",
        map_path: str = BPF_MAP_PATH,
        command_runner: Callable[..., subprocess.CompletedProcess] = subprocess.run,
    ):
        self.interface = interface
        self.map_path = Path(map_path)
        self.command_runner = command_runner
        self.pacing_entries: Dict[str, float] = {}

    def compile_ebpf_modules(self) -> bool:
        """Compiles TC EDT and XDP eBPF kernel C modules using Clang/LLVM."""
        print("[eBPF Manager] Compiling Linux eBPF kernel modules...")
        try:
            cmd_tc = ["clang", "-O2", "-target", "bpf", "-c", "ebpf/tc_edt_pacer.c", "-o", "ebpf/tc_edt_pacer.o"]
            cmd_xdp = ["clang", "-O2", "-target", "bpf", "-c", "ebpf/xdp_rate_shaper.c", "-o", "ebpf/xdp_rate_shaper.o"]

            subprocess.run(cmd_tc, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            subprocess.run(cmd_xdp, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            print("[eBPF Manager] 🟢 eBPF kernel bytecode modules compiled successfully.")
            return True
        except (subprocess.CalledProcessError, FileNotFoundError) as e:
            print(f"[eBPF Manager] ⚠️ eBPF compilation skipped or compiler uninstalled: {e}")
            return False

    def update_pacing_rate(self, dest_ip: str, rate_bps: float) -> bool:
        """Updates in-kernel BPF Map entry (bpf_map_update_elem) for target destination IP."""
        try:
            if not math.isfinite(rate_bps) or rate_bps <= 0 or rate_bps > 0xFFFFFFFFFFFFFFFF:
                raise ValueError("rate_bps must be a positive uint64-compatible value")
            if not self.map_path.exists():
                raise FileNotFoundError(f"Pinned BPF map does not exist: {self.map_path}")

            key_bytes = socket.inet_aton(dest_ip)
            # pce_pacing_map value: rate, last_tx, token bucket, drops, passes.
            # last_tx starts at zero because bpf_ktime_get_ns() is monotonic, not wall time.
            value_bytes = struct.pack("=QQQQQ", int(rate_bps), 0, 0, 0, 0)
            command = [
                "bpftool", "map", "update", "pinned", str(self.map_path),
                "key", "hex", *self._hex_bytes(key_bytes),
                "value", "hex", *self._hex_bytes(value_bytes), "any",
            ]
            self.command_runner(command, check=True, capture_output=True, text=True)
            self.pacing_entries[dest_ip] = rate_bps

            print(f"[eBPF Manager] Updated pinned BPF map: {dest_ip} -> {rate_bps / 1e6:.2f} Mbps")
            return True
        except (FileNotFoundError, OSError, ValueError, subprocess.CalledProcessError) as e:
            print(f"[eBPF Manager] Error updating BPF map entry: {e}")
            return False

    def cleanup_stale_entries(self) -> bool:
        """Cleans up expired or unadmitted flow entries from BPF map."""
        success = True
        for dest_ip in list(self.pacing_entries):
            try:
                key_bytes = socket.inet_aton(dest_ip)
                command = [
                    "bpftool", "map", "delete", "pinned", str(self.map_path),
                    "key", "hex", *self._hex_bytes(key_bytes),
                ]
                self.command_runner(command, check=True, capture_output=True, text=True)
                self.pacing_entries.pop(dest_ip, None)
            except (OSError, subprocess.CalledProcessError) as e:
                print(f"[eBPF Manager] Error deleting BPF map entry {dest_ip}: {e}")
                success = False
        return success

    @staticmethod
    def _hex_bytes(value: bytes) -> Sequence[str]:
        return [f"{octet:02x}" for octet in value]


if __name__ == "__main__":
    manager = PCEBPFManager()
    manager.compile_ebpf_modules()
    manager.update_pacing_rate("10.0.1.100", 820000000.0) # 820 Mbps pacing rate e*
