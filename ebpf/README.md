# ⚡ VortexPCE Linux eBPF Data Plane Engine

> **Status: experimental, and not enforced by this controller.**
>
> Nothing here is wired into the control plane. The controller never invokes the loader, no
> committed schedule's rate reaches a kernel map, CI does not compile, attach or verify these
> programs, and no test exercises them against the kernel verifier. They are a design sketch of an
> enforcement path, not the enforcement path.
>
> **Pacing measurements of VortexPCE have used Linux `tc` HTB, not these programs.** Any claim
> that VortexPCE enforces `e*` in the kernel is unsupported: what was measured is `tc`, and what
> is described below has never been observed running.
>
> Evidence that it has not run: until 2026-08-22 the loader pinned `/sys/fs/bpf/pce_pacing_map`
> while `tc_edt_pacer.c` declared its map as `tc_pacing_map`. The loader would have written pacing
> rates into a map that program never reads, and they would have been silently ignored. A
> mismatch of that kind does not survive a single successful attach, so it is direct evidence the
> two files were never run together. The names are aligned now; the rest of this notice still
> stands.

This directory contains the dual Linux kernel eBPF modules for **VortexPCE**:

1. **`tc_edt_pacer.c` (egress rate pacer, experimental):**
   Uses `SEC("tc")` and Earliest Departure Time (`skb->tstamp`) timestamps with the Linux `sch_fq` (Fair Queueing) qdisc intended to pace flows at $e^*$; unverified.

2. **`xdp_rate_shaper.c` (Ingress Microburst Drop Policer):**
   Uses `SEC("xdp")` driver-native mode (`XDP_FLAGS_DRV_MODE`) intended to police microbursts (`XDP_DROP`); unverified.

---

## 🛠️ Operational Mechanisms

### 1. TC/EDT egress pacer (`ebpf/tc_edt_pacer.c`)
* **Mechanism:** Assigns departure timestamps (`skb->tstamp = t_last + delay_ns`).
* **Qdisc Setup:**
  ```bash
  # Enable Fair Queueing (sch_fq) pacing qdisc on egress interface
  tc qdisc add dev eth0 root fq pacing

  # Compile TC EDT BPF module
  clang -O2 -target bpf -c tc_edt_pacer.c -o tc_edt_pacer.o

  # Attach to egress classifier
  tc filter add dev eth0 egress bpf obj tc_edt_pacer.o sec tc
  ```

### 2. XDP Ingress Microburst Drop Policer (`ebpf/xdp_rate_shaper.c`)
* **Mechanism:** Drops unthrottled line-rate burst packets (`XDP_DROP`) exceeding rate limit $e^*$.
* **Driver Attachment:**
  ```bash
  clang -O2 -target bpf -c xdp_rate_shaper.c -o xdp_rate_shaper.o
  ip link set dev eth0 xdp obj xdp_rate_shaper.o sec xdp
  ```

---

## BPF Map Updates

`pce_ebpf_loader.py` updates the pinned XDP map through `bpftool map update` and
fails closed when the map is not present. Update latency depends on the kernel,
host, and measurement method; this repository does not claim a universal latency
or speedup without a retained benchmark artifact.
