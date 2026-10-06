/*
 * Linux eBPF TC/EDT Lossless Egress Rate Pacing Kernel Program for VortexPCE Controller.
 *
 * Implements sub-millisecond, driver/egress-native (SEC("tc")) Earliest Departure Time (EDT) pacing e*
 * using skb->tstamp timestamps and sch_fq (Fair Queueing) pacing qdiscs.
 *
 * Author: Dr. Omar Y. Tahboub (2026)
 * License: GPL v2 / Dual MIT
 */

#include <linux/bpf.h>
#include <linux/pkt_cls.h>
#include <linux/if_ether.h>
#include <linux/ip.h>
#include <linux/in.h>
#include <bpf/bpf_helpers.h>

struct flow_pacing_params {
    __u64 rate_bps;            /* Equilibrated rate e* in bits per second */
    __u64 last_tx_ns;          /* Timestamp of last scheduled packet (nanoseconds) */
    __u64 total_paced_bytes;   /* Counter for total EDT-paced payload bytes */
};

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 65536);
    __type(key, __u32);                      /* Flow ID / Destination IP */
    __type(value, struct flow_pacing_params);/* Pacing Parameters */
} pce_pacing_map SEC(".maps");
/* Named pce_pacing_map to match what pce_ebpf_loader.py pins at
 * /sys/fs/bpf/pce_pacing_map, and what xdp_rate_shaper.c already used. It was
 * tc_pacing_map, so the loader updated a map this program never read: rates
 * would have been written and silently ignored. Nothing attaches these programs
 * yet, so the mismatch could only be found by reading both files. */

SEC("tc")
int tc_vortex_edt_pacer(struct __sk_buff *skb) {
    void *data_end = (void *)(long)skb->data_end;
    void *data     = (void *)(long)skb->data;

    struct ethhdr *eth = data;
    if ((void *)(eth + 1) > data_end)
        return TC_ACT_OK;

    if (eth->h_proto != __constant_htons(ETH_P_IP))
        return TC_ACT_OK;

    struct iphdr *iph = (void *)(eth + 1);
    if ((void *)(iph + 1) > data_end)
        return TC_ACT_OK;

    __u32 dest_ip = iph->daddr;

    /* Lookup flow pacing entry in pinned BPF map */
    struct flow_pacing_params *params = bpf_map_lookup_elem(&pce_pacing_map, &dest_ip);
    if (!params || params->rate_bps == 0) {
        return TC_ACT_OK; /* Unpaced flow pass-through */
    }

    __u64 now_ns = bpf_ktime_get_ns();
    __u64 pkt_bits = (__u64)skb->len * 8;

    /* Calculate inter-packet delay ns = (pkt_bits * 10^9) / rate_bps */
    __u64 delay_ns = (pkt_bits * 1000000000ULL) / params->rate_bps;

    __u64 edt_ns = params->last_tx_ns + delay_ns;
    if (edt_ns < now_ns) {
        edt_ns = now_ns;
    }

    /* Assign Earliest Departure Time (EDT) timestamp to skb for kernel sch_fq pacing */
    skb->tstamp = edt_ns;
    params->last_tx_ns = edt_ns;
    __sync_fetch_and_add(&params->total_paced_bytes, skb->len);

    return TC_ACT_OK; /* Pass to Linux sch_fq qdisc for delayed hardware egress */
}

char _license[] SEC("license") = "GPL";
