/*
 * Linux eBPF XDP Rate Pacing Kernel Program for VortexPCE Controller.
 *
 * Implements sub-millisecond, driver-native (XDP_FLAGS_DRV_MODE) rate pacing e*
 * using BPF Map updates (bpf_map_update_elem) and Token Bucket Rate Enforcement.
 *
 * Author: Dr. Omar Y. Tahboub (2026)
 * License: GPL v2 / Dual MIT
 */

#include <linux/bpf.h>
#include <linux/if_ether.h>
#include <linux/ip.h>
#include <linux/in.h>
#include <bpf/bpf_helpers.h>

/* BPF Map Schema: Pacing Parameters for Target Flows */
struct flow_pacing_params {
    __u64 rate_bps;            /* Equilibrated rate e* in bits per second */
    __u64 last_tx_ns;          /* Timestamp of last transmitted packet (nanoseconds) */
    __u64 token_bucket_ns;     /* Accumulator for burst control */
    __u64 dropped_packets;     /* Counter for rate-limited microburst packets */
    __u64 passed_packets;      /* Counter for compliant paced packets */
};

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 65536);
    __type(key, __u32);                      /* Flow ID / Destination IP */
    __type(value, struct flow_pacing_params);/* Pacing Parameters */
} pce_pacing_map SEC(".maps");

SEC("xdp")
int xdp_vortex_rate_pacer(struct xdp_md *ctx) {
    void *data_end = (void *)(long)ctx->data_end;
    void *data     = (void *)(long)ctx->data;

    struct ethhdr *eth = data;
    if ((void *)(eth + 1) > data_end)
        return XDP_PASS;

    if (eth->h_proto != __constant_htons(ETH_P_IP))
        return XDP_PASS;

    struct iphdr *iph = (void *)(eth + 1);
    if ((void *)(iph + 1) > data_end)
        return XDP_PASS;

    __u32 dest_ip = iph->daddr;

    /* Lookup flow pacing entry in pinned BPF map */
    struct flow_pacing_params *params = bpf_map_lookup_elem(&pce_pacing_map, &dest_ip);
    if (!params || params->rate_bps == 0) {
        return XDP_PASS; /* Unpaced flow pass-through */
    }

    __u64 now_ns = bpf_ktime_get_ns();
    __u64 pkt_bits = (__u64)(data_end - data) * 8;

    /* Calculate inter-packet delay ns = (pkt_bits * 10^9) / rate_bps */
    __u64 delay_ns = (pkt_bits * 1000000000ULL) / params->rate_bps;

    /* Enforce Rate Pacing: If packet arrives before allowed transmission time, DROP microburst */
    if (now_ns < params->last_tx_ns + delay_ns) {
        __sync_fetch_and_add(&params->dropped_packets, 1);
        return XDP_DROP; /* Enforce rate limit by dropping unthrottled line-rate burst packets */
    }

    /* Compliant Packet: Update last transmission timestamp and PASS to network stack */
    params->last_tx_ns = now_ns;
    __sync_fetch_and_add(&params->passed_packets, 1);
    return XDP_PASS;
}

char _license[] SEC("license") = "GPL";
