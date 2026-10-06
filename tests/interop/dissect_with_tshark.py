"""Validate our PCEP wire format against Wireshark's independent dissector.

Every PCEP test in this repository runs our encoder against our decoder, which proves the two
agree with each other and nothing about whether either matches the RFCs. Wireshark's PCEP
dissector was written from those RFCs by people with no sight of this code, so making it parse
our frames is an external check on the bytes we emit.

The frames are encoded and handed to ``text2pcap`` rather than captured from a live socket.
Capturing needs CAP_NET_RAW, which a CI container does not have, and a capture adds timing and
interface assumptions to a check that does not need either. The bytes fed in are the same bytes
``send_message`` would put on the wire.

This is not a substitute for a PCC fixture. It says nothing about session semantics, PCRpt
correlation, or whether a router would accept the LSP. It establishes that our framing, object
headers, and TLVs are what the standard describes.
"""
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

# Python puts this script's own directory on sys.path, not the working directory, so running it
# by path from the repository root would not find crp_pce. Making the script self-sufficient
# means it behaves the same however it is invoked, rather than depending on the caller
# remembering PYTHONPATH -- which is exactly the difference that broke CI while passing locally.
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from crp_pce.pcep import PCEPMessage  # noqa: E402

PORT = 4189


def pcrpt_fixture(srp_id=7, plsp_id=42, lsp_name="vortex-T1", flags=0x010 | 0x008 | 0x001):
    """An RFC 8231 PCRpt, as a PCC would send one.

    The controller does not emit this message -- it reads it. The frame exists so the layout the
    Java decoder assumes is checked against Wireshark rather than against itself, and so a change
    to that assumption fails here instead of silently misreading a real PCC.

    The default flags are Operational=UP(1), Administrative, Delegate. These bytes are the same
    vector asserted in PcepReportDecoderTest.
    """
    srp = struct.pack(">BBHI", 33, 1 << 4, 12, 0) + struct.pack(">I", srp_id)
    name = lsp_name.encode("ascii")
    padded = (len(name) + 3) & ~3
    tlv = struct.pack(">HH", 17, len(name)) + name + b"\x00" * (padded - len(name))
    lsp_body = struct.pack(">I", (plsp_id << 12) | flags) + tlv
    lsp = struct.pack(">BBH", 32, 1 << 4, 4 + len(lsp_body)) + lsp_body
    payload = srp + lsp
    return struct.pack(">BBH", 1 << 5, 10, 4 + len(payload)) + payload


def java_frames():
    """Frames written by the Java encoder, when a Maven build has produced them.

    The Java and Python encoders are both ours, so agreeing with each other proves nothing. What
    this adds is that the Java bytes face the same independent dissector the Python bytes do.
    """
    dump = Path(__file__).resolve().parents[2] / "target" / "java-pcep-frames.hex"
    if not dump.exists():
        return []
    named = []
    for line in dump.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        label, payload = line.split(maxsplit=1)
        named.append((f"java:{label}", bytes.fromhex(payload)))
    return named


def frames():
    """One of each message this controller emits, plus the one it must be able to read."""
    return [
        ("OPEN", PCEPMessage.encode_binary_open(30, 120, speaker_entity_id="vortex-dissect")),
        ("KEEPALIVE", PCEPMessage.encode_binary_keepalive()),
        ("PCInitiate", PCEPMessage.encode_binary_pcinitiate(
            srp_id=1, lsp_name="vortex-DISSECT",
            ingress_ip="10.0.0.1", egress_ip="10.0.0.2",
            rate_bps=1_000_000.0, ero_path=["10.0.0.1", "10.0.0.2"])),
        ("PCRpt", pcrpt_fixture()),
    ]


def to_text2pcap(payloads):
    """text2pcap hex format: a new packet begins whenever the offset resets to zero."""
    lines = []
    for payload in payloads:
        for offset in range(0, len(payload), 16):
            chunk = payload[offset:offset + 16]
            lines.append(f"{offset:06x}  " + " ".join(f"{b:02x}" for b in chunk))
        lines.append("")
    return "\n".join(lines) + "\n"


def main():
    named = frames()
    from_java = java_frames()
    if from_java:
        named = named + from_java
    with tempfile.TemporaryDirectory() as tmp:
        hex_path = Path(tmp) / "frames.txt"
        pcap_path = Path(tmp) / "frames.pcap"
        hex_path.write_text(to_text2pcap([payload for _, payload in named]))

        build = subprocess.run(
            ["text2pcap", "-T", f"{PORT},{PORT}", str(hex_path), str(pcap_path)],
            capture_output=True, text=True)
        if build.returncode != 0:
            print("text2pcap failed:\n" + build.stderr)
            return 1

        dissected = subprocess.run(
            ["tshark", "-r", str(pcap_path), "-d", f"tcp.port=={PORT},pcep", "-V"],
            capture_output=True, text=True).stdout

    upper = dissected.upper()
    # Assert the values the dissector parsed, not merely that a keyword appears. Presence of the
    # string "ERO object" only shows the dissector reached that object; the subobject addresses
    # show it agreed with what we meant by it.
    checks = {
        "PCEP protocol recognised": "PATH COMPUTATION ELEMENT COMMUNICATION PROTOCOL" in upper,
        "OPEN typed as message 1": "Message Type: Open (1)" in dissected,
        "KEEPALIVE typed as message 2": "Message Type: Keepalive (2)" in dissected,
        "PCInitiate typed as message 12":
            "Path Computation LSP Initiate (PCInitiate) (12)" in dissected,
        "OPEN object": "OPEN object" in dissected,
        "SRP object": "SRP object" in dissected,
        "LSP object": "LSP object" in dissected,
        "END-POINT object typed IPv4": "END-POINT Object-Type: IPv4 addresses (1)" in dissected,
        "ERO object": "EXPLICIT ROUTE object (ERO)" in dissected,
        "BANDWIDTH object": "BANDWIDTH object" in dissected,
        # The ERO hops we asked for, read back by an implementation that is not ours.
        "ERO carries the first hop": "IPv4 Prefix: 10.0.0.1/32" in dissected,
        "ERO carries the second hop": "IPv4 Prefix: 10.0.0.2/32" in dissected,
        "STATEFUL-PCE-CAPABILITY TLV": "STATEFUL-PCE-CAPABILITY" in upper,
        "SPEAKER-ENTITY-ID carries our identity": "vortex-dissect" in dissected,
        "SYMBOLIC-PATH-NAME carries the LSP name": "vortex-DISSECT" in dissected,
        # The PCRpt fixture: the message the controller must read rather than write. These
        # assertions pin the exact fields PcepReportDecoder extracts, so a drift in the layout it
        # assumes fails here rather than misreading a real PCC's report in production.
        "PCRpt typed as message 10":
            "Path Computation LSP State Report (PCRpt) (10)" in dissected,
        "PCRpt carries the SRP identifier": "SRP-ID-number: 7" in dissected,
        "PCRpt carries the PLSP identifier": "PLSP-ID: 42" in dissected,
        "PCRpt reports the LSP operational": "Operational (O): UP (1)" in dissected,
        "PCRpt names the LSP": "SYMBOLIC-PATH-NAME: vortex-T1" in dissected,
        "PCRpt does not claim removal": "Remove (R): Not set" in dissected,
        # The decisive one: the dissector must not report our bytes as malformed.
        "No malformed packets": "MALFORMED" not in upper,
        # Guards against a vacuous pass if dissection produced nothing at all.
        "Dissection is non-trivial": len(dissected) > 2000,
    }

    if from_java:
        # The Java encoder faces the same referee. Its PCInitiate carries a three-hop ERO with a
        # distinct middle address, so a hop the encoder dropped or reordered surfaces here rather
        # than in a router's rejection.
        checks.update({
            "java OPEN names this speaker": "vortex-java" in dissected,
            "java PCInitiate names its LSP": upper.count("VORTEX-T1") >= 2,
            "java ERO carries its middle hop": "IPv4 Prefix: 10.0.0.9/32" in dissected,
            # The removal PCInitiate. What makes it a removal is the R flag in the SRP object
            # rather than anything in the LSP object, so that flag is the assertion: an encoder
            # that set the wrong bit would still produce a frame the dissector parses happily,
            # and a router would simply create a second LSP instead of deleting the first.
            "java removal sets the SRP Remove flag": "Remove (R): Set" in dissected,
            "java removal carries its own SRP": "SRP-ID-number: 8" in dissected,
            "java removal names the LSP by PLSP-ID": "PLSP-ID: 4242" in dissected,
            "java removal names the LSP": "SYMBOLIC-PATH-NAME: vortex-REMOVE" in dissected,
        })

    width = max(len(name) for name in checks)
    failed = [name for name, ok in checks.items() if not ok]
    for name, ok in checks.items():
        print(f"  {name.ljust(width)}  {'PASS' if ok else 'FAIL'}")

    if failed:
        print(f"\n{len(failed)} check(s) failed. Dissection follows:\n")
        print(dissected[:9000] if dissected else "(no dissector output)")
        return 1
    print(f"\nAll {len(checks)} checks passed against {tshark_version()}")
    return 0


def tshark_version():
    out = subprocess.run(["tshark", "-v"], capture_output=True, text=True).stdout
    return out.splitlines()[0] if out else "unknown"


if __name__ == "__main__":
    sys.exit(main())
