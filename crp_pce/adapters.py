"""
Experimental payload formatters for crp-pce: builds dicts and commands in the shape of
stateful-PCEP, SDN-controller and Linux tc/eBPF requests. These formatters are not tested
against vendor equipment or controllers.
"""

from typing import Dict, Any, List

class MPLSSRv6PCEPAdapter:
    """
    Adapter for Contemporary MPLS-TE & Segment Routing (SRv6 / SR-MPLS) Routers.
    Uses RFC 8281 PCInitiate with ERO Segment Stacks & Bandwidth Pacing Objects.
    """
    @staticmethod
    def format_srv6_pcinitiate(
        lsp_name: str,
        ingress_ip: str,
        egress_ip: str,
        sid_stack: List[str],
        e_star_bps: float
    ) -> Dict[str, Any]:
        return {
            "protocol": "PCEP_RFC8281",
            "message_type": "PCInitiate",
            "lsp_name": lsp_name,
            "ingress": ingress_ip,
            "egress": egress_ip,
            "srv6_segment_list": sid_stack,
            "bandwidth_object": {
                "allocated_rate_bps": e_star_bps,
                "shaping_pacing_flag": True
            }
        }


class SDNHypervisorAdapter:
    """
    Adapter for SDN Hypervisors (ONOS, OpenDaylight, OVS).
    Generates OpenFlow 1.3 / 1.5 OFPT_FLOW_MOD & Meter Table Rate Limiters.
    """
    @staticmethod
    def format_openflow_flow_mod(
        flow_id: str,
        in_port: int,
        out_port: int,
        e_star_bps: float
    ) -> Dict[str, Any]:
        meter_id = int(hash(flow_id) % 1000) + 1
        return {
            "protocol": "OpenFlow_1.3",
            "flow_mod": {
                "match": {"in_port": in_port},
                "instructions": [
                    {"apply_meter": meter_id},
                    {"output": out_port}
                ]
            },
            "meter_mod": {
                "meter_id": meter_id,
                "flags": ["KBPS"],
                "bands": [
                    {"type": "DROP", "rate": int(e_star_bps / 1000)}
                ]
            }
        }


class LinuxKernelEBPFAdapter:
    """
    Generates Linux tc qdisc HTB and eBPF socket pacing commands (experimental).
    """
    @staticmethod
    def format_tc_htb_command(interface: str, e_star_bps: float) -> str:
        rate_kbit = int(e_star_bps / 1000)
        return (
            f"tc qdisc add dev {interface} root handle 1: htb default 10 && "
            f"tc class add dev {interface} parent 1: classid 1:10 htb rate {rate_kbit}kbit ceil {rate_kbit}kbit"
        )
