# Independent interoperability evidence

Everything in this project's own test suite is this project checking itself: our encoder against
our decoder, our state machine against our expectations. Both fixtures here exist to introduce an
implementation written by other people, because agreement with ourselves is not evidence.

## Wire-format validation against Wireshark — enforced in CI

`dissect_with_tshark.py` encodes one of each message the controller emits, builds a capture with
`text2pcap`, and dissects it with Wireshark's PCEP dissector. That dissector was written from the
RFCs by people with no sight of this code.

It asserts parsed values, not keyword presence: message type numbers, object types, both ERO hop
addresses, the symbolic path name, the speaker entity ID, the SRP Remove flag and PLSP-ID on a
removal, and that nothing is reported malformed. 30 checks, verified against TShark 4.2.2.

```bash
python3 tests/interop/dissect_with_tshark.py
```

Frames are handed to `text2pcap` rather than captured from a socket. Capturing needs `CAP_NET_RAW`,
which a CI container does not have, and it would add timing and interface assumptions to a check
that needs neither. The bytes fed in are the bytes the encoder puts on the wire.

**What this does not prove.** Nothing about session semantics, report correlation, acknowledgement
handling, or whether a router would accept the LSP. A correct-looking PCInitiate that no PCC ever
answers is still an open control loop. That is what the fixture below is for.

## FRRouting PCC fixture — enforced in CI

`.github/workflows/interop.yml` runs the controller against FRRouting's `pathd`, an independent
router-grade PCEP implementation, and **fails the workflow** when the session does not come up.

It runs the controller with `VORTEX_PCEP_LISTENER=JAVA` deliberately. The Java listener is the one
that owns the intent ledger, the installation state machine and removal dispatch, so it is the one
whose behaviour against a real router is worth proving; a run against the Python listener would
exercise none of that.

The gate asserts, in order:

| Assertion | Why it is there |
|---|---|
| FRR logs no `Unknown command` | A rejected configuration makes every later observation meaningless |
| `Session Status UP` in `show sr-te pcep session` | The PCC's own verdict, not ours |
| `Stateful PCE` in the same output | pathd classified us as stateful rather than falling back |
| Controller logged `PCEP session established` | Both ends agree a session exists |
| Controller logged `completed state synchronisation` | RFC 8231 sync ran to its end-of-sync marker |
| No `Discarding unreadable report` | A real PCC sent nothing this decoder could not read |
| `vortex_pcc_sessions_established 1` on `/metrics` | An operator can see the peer |

The FRR image is pinned by digest. A gate that floats on `:latest` changes meaning whenever
upstream publishes, and a red run should mean this project broke interoperability rather than that
FRR shipped a new image overnight.

The fixture deliberately configures PCEP without an SR policy, segment list, or binding SID. Those
objects require the runner's host kernel to provide an MPLS dataplane, but the behavior under test
is the PCEP control plane. The official FRR image is pinned to release 10.7.0, whose `zebra` starts
without host MPLS modules. The workflow also bypasses the image's concurrent `watchfrr` startup:
`zebra` is started first, its API socket is required, and only then is `pathd` started and the
configuration loaded. This ordering matters because `pathd` blocks while connecting to zebra's
label manager; under runner load, a shared `restart all` helper can time out and leave `pathd`
stuck before its VTY or PCEP client is initialized.

### What the fixture caught

Both of these were live bugs that every unit test passed over, because our own encoder never
produces what pathd produces:

- **The end-of-synchronisation marker was discarded as malformed.** RFC 8231 §5.6 ends state
  synchronisation with an LSP object carrying PLSP-ID 0, the SYNC flag clear, and no symbolic path
  name — there is no LSP for it to name. The decoder demanded a name from every LSP object and
  threw. The session survived, the log said `Discarding unreadable report`, and reconciliation —
  whose entire premise is that the report is complete — was unreachable against any real PCC.
- **SRP-ID 0 was read as operation zero.** RFC 8231 reserves `0x00000000`, and pathd sends it on
  reports that answer no request of ours. Correlating it as a real identifier made those reports
  look like answers to an operation the controller never issued, so a report from the very router
  that installed an LSP was rejected as stale.

A third finding came from the packet capture rather than the logs: with the socket read timeout
equal to the keepalive interval, the outbound keepalive could only be checked once per interval,
and a 30-second keepalive went out at 33 seconds. The timeout is now a third of the interval.

### Findings about FRR itself

- `pathd` does **not** load PCEP by default. `pathd_options` must include `-M pathd_pcep`, or
  every `pcep` configuration line is rejected as an unknown command while the daemon still starts
  and reports healthy.
- `address ip <x>` was **not** valid inside the `pcc` block in FRR 8.4 (where this was first observed). Adding it fails the whole
  configuration file from that line onward, silently discarding the PCEP configuration after it.
- `pathd` binds dual-stack and will not dial at all without an IPv6 source address, logging
  `skipping connection to PCE ... due to missing PCC IPv6 address`. Hence `source-address ipv6 ::1`
  alongside the IPv4 one in `frr.conf`.
- `pceplib` requires `LSP-UPDATE-CAPABILITY` (`0x001`) in the STATEFUL-PCE-CAPABILITY TLV.
  Advertising instantiation alone (`0x004`) made it classify this PCE as stateless and close the
  socket. The encoder advertises `0x007`.
- pathd sends no SPEAKER-ENTITY-ID, so the session key falls back to the peer address
  (`addr:172.29.0.3`). Any identity scheme that assumed the TLV would be present would key every
  FRR peer identically.

### What it still does not prove

The gate does exercise automatic dispatch — see *Dispatch coverage* below — so what it leaves
unproved is narrower than it once was.

What it does not show is an LSP reaching a forwarding plane. The fixture has none, so the policy
FRR creates stays `DOWN`: nothing here carries traffic, nothing confirms an operational `PCRpt`
transition to `INSTALLED`, and neither side is restarted with outstanding state to prove removal,
reconnect, or post-restart reconciliation.

## Forwarding-plane coverage: implemented, but not a hosted PR gate

The control-plane gate proves an LSP is *created*, not that packets move. The separate
`tests/forwarding/run_forwarding_lifecycle.sh` fixture exercises
install -> operational `PCRpt` -> traffic -> remove -> reconnect on an MPLS-capable Linux host.
It is invoked by the manual `.github/workflows/forwarding.yml` workflow.

MPLS forwarding works in privileged containers on a host whose kernel provides the MPLS modules:

```
sysctl -w net.mpls.platform_labels=1048575   -> accepted
sysctl -w net.mpls.conf.veth1.input=1        -> accepted
ip -f mpls route add 100 dev veth0           -> installed
ip -f mpls route show                        -> 100 dev veth0
```

The fixture now configures the MPLS interfaces, drives the PCEP-originated policy to `ACTIVE`,
checks the intent reaches `INSTALLED`, observes labelled packets at an independent tail, confirms
policy deletion and labelled-traffic cessation, and restarts the PCC to prove reconnection. A release
candidate still requires a fresh run against its exact frozen identity.

**Infrastructure caveat.** Ordinary GitHub-hosted runner kernels do not ship the required MPLS
modules. Privileged containers with writable `platform_labels` also use a weaker isolation boundary
than the rest of CI, so this remains a dedicated manual job for an explicitly approved
MPLS-capable runner.

## Dispatch coverage

`topology.json` gives the arena's nodes the addresses a PCInitiate needs in its
END-POINTS and ERO, so the gate can exercise automatic dispatch and not only the
session. Without addresses a computed schedule cannot be encoded at all, and the
dispatch path — the newest code here, and the part a hand-written PCC cannot
falsify — would go untested.
