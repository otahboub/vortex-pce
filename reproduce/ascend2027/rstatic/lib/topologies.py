"""Topologies for Group A, and an assertion about each that the harness checks before running.

Three studies in this repository were run on a linear source-to-destination path, where exactly
one route exists, so route generation and path selection could not act and two of the four CRP
stages were inert by construction. The mistake is invisible in the output: every arm simply
produces the same answer, which reads as "no difference between policies" rather than "this
experiment could not have found one".

Each builder therefore declares what it is for and what must be true of it, and
`describe()` reports the path diversity so a run's own log shows whether the stages under test
could act.
"""
from dataclasses import dataclass, field
from typing import Dict, List, Optional

MBIT = 1e6

#: Reservoirs are sized as a multiple of per-flow bytes, not of link rate or contact geometry.
#: Admission charges the whole task size against a node's reservoir for the duration a flow waits
#: (`hasBufferCapacityForHoldingIntervals` adds `task.getTaskSizeBytes()`), so a reservoir smaller
#: than one flow is a hard floor: no flow that has to wait can ever be admitted, at any load.
#: Sizing from link rate and contact length instead put every reservoir below that floor and made
#: whole arms refuse every flow for want of buffer.
DEFAULT_RESERVOIR_FLOWS = 2.0


@dataclass
class Topology:
    name: str
    purpose: str
    #: Distinct source-to-destination routes. Route generation and path selection can only act
    #: when this exceeds one.
    path_diversity: int
    #: What the arms are expected to do here, checked by the harness rather than assumed.
    expectation: str
    spec: Dict
    #: Where the node, link and capacity figures came from. Empty for the synthetic shapes below,
    #: which are named for what they test rather than for anything real; the topologies in
    #: `topo_real` fill it in, because a topology claiming to be a real network has to say so.
    provenance: str = ""

    def describe(self) -> str:
        out = (f"{self.name}: {self.purpose}\n"
               f"    paths={self.path_diversity}  links={len(self.spec['links'])}  "
               f"regime={self.spec['regime']}\n"
               f"    expect: {self.expectation}")
        if self.provenance:
            out += "\n    source: " + self.provenance.replace("\n", "\n            ")
        return out


def _node(node_id: str, reservoir_bytes: float) -> Dict:
    return {"nodeId": node_id, "name": node_id, "serviceRateBps": 1e12,
            "reservoirCapacityBytes": reservoir_bytes}


def _link(link_id: str, src: str, dst: str, bps: float,
          active_sec: float = 1.0, inactive_pre_sec: float = 0.0,
          propagation_sec: float = 0.001) -> Dict:
    return {"linkId": link_id, "sourceNodeId": src, "destinationNodeId": dst,
            "baseBandwidthBps": bps, "propagationDelaySec": propagation_sec,
            "activeContactSec": active_sec, "inactivePreSec": inactive_pre_sec,
            "inactivePostSec": 0.0}


def _contact(regime: str, active_sec: float, inactive_sec: float):
    """Contact geometry for a cut edge, per regime.

    R_STATIC leaves the edge continuously available. R_DET and R_STOCH give it a duty cycle: under
    R_DET the engine plans against the windows themselves, under R_STOCH against their mean.
    Access links stay continuous in every regime -- the intermittency being modelled is the
    constrained edge, not the whole fabric.
    """
    if regime == "R_STATIC":
        return {"active_sec": 1.0, "inactive_pre_sec": 0.0}
    return {"active_sec": active_sec, "inactive_pre_sec": inactive_sec}


def t1_single_cut(regime: str, cut_mbit: float = 10.0, access_mbit: float = 100.0,
                  reservoir_bytes: float = 12.5e6,
                  active_sec: float = 10.0, inactive_sec: float = 90.0) -> Topology:
    """Every path crosses one constrained edge. The branch-WAN and ground-station shape.

    Routing is deliberately given nothing to win: access and egress are generous and every route
    crosses the same cut. What binds is the cut's capacity and node C's reservoir, where flows
    queue for it. If the selection and routing arms differ here, that is a finding about this
    topology rather than about routing.
    """
    contact = _contact(regime, active_sec, inactive_sec)
    nodes = [_node("SRC", 1e12), _node("A", 1e12), _node("B", 1e12),
             # C feeds the cut, so C is where waiting happens and the only bounded reservoir.
             _node("C", reservoir_bytes), _node("D", 1e12),
             _node("E", 1e12), _node("F", 1e12), _node("DST", 1e12)]
    links = [_link("SRC-A", "SRC", "A", access_mbit * MBIT),
             _link("SRC-B", "SRC", "B", access_mbit * MBIT),
             _link("A-C", "A", "C", access_mbit * MBIT),
             _link("B-C", "B", "C", access_mbit * MBIT),
             _link("C-D", "C", "D", cut_mbit * MBIT, **contact),   # the cut
             _link("D-E", "D", "E", access_mbit * MBIT),
             _link("D-F", "D", "F", access_mbit * MBIT),
             _link("E-DST", "E", "DST", access_mbit * MBIT),
             _link("F-DST", "F", "DST", access_mbit * MBIT)]
    return Topology(
        name="T1-single-cut",
        purpose="one constrained edge every path must cross; reservoir at the feeding node",
        path_diversity=4,
        expectation="routing arms should NOT separate (all routes cross the same cut); "
                    "DFE arms should hold less reservoir under R_DET",
        spec={"regime": regime, "nodes": nodes, "links": links})


def t2_heterogeneous_cuts(regime: str, reservoir_bytes: float = 12.5e6,
                          active_sec: float = 10.0, inactive_sec: float = 90.0) -> Topology:
    """Three disjoint routes with different bottlenecks, worst one shortest in hops.

    The satellite route is the fewest hops and the slowest link, so MIN_HOP selects the worst path
    deterministically. That is the real SD-WAN pathology; an earlier draft gave every route the
    same hop count, which made MIN_HOP merely arbitrary.

    **Every route crosses exactly one bounded transit node and one contact-windowed cut.** The
    first version made the satellite a single hop, which left it with no transit node at all and
    therefore exempt from the reservoir constraint entirely. The greedy arm's preferred path was
    then the only one with no buffer cost, so "the spreading arm loses at high load" measured that
    exemption rather than any property of routing. A ground station gives the satellite route the
    same shape as the others while keeping it the shortest.
    """
    contact = _contact(regime, active_sec, inactive_sec)
    nodes = [_node("SRC", 1e12), _node("DST", 1e12),
             # One bounded transit node per route, so the buffer cost is symmetric.
             _node("GS", reservoir_bytes),
             _node("M1", reservoir_bytes), _node("N1", 1e12),
             _node("M2", reservoir_bytes), _node("N2", 1e12)]
    links = [
        # Two hops and the slowest cut: MIN_HOP takes this every time.
        _link("SRC-GS", "SRC", "GS", 50 * MBIT),
        _link("GS-DST", "GS", "DST", 2 * MBIT, **contact),
        _link("SRC-M1", "SRC", "M1", 50 * MBIT),
        _link("M1-N1", "M1", "N1", 10 * MBIT, **contact),
        _link("N1-DST", "N1", "DST", 50 * MBIT),
        _link("SRC-M2", "SRC", "M2", 50 * MBIT),
        _link("M2-N2", "M2", "N2", 5 * MBIT, **contact),
        _link("N2-DST", "N2", "DST", 50 * MBIT),
    ]
    return Topology(
        name="T2-heterogeneous-cuts",
        purpose="three disjoint routes, differing bottlenecks, worst route shortest in hops, "
                "every route paying one bounded transit node",
        path_diversity=3,
        expectation="routing arms SHOULD separate: greedy funnels onto the 2-hop 2 Mbit satellite; "
                    "no route is exempt from the reservoir constraint",
        spec={"regime": regime, "nodes": nodes, "links": links})


def t3_generous_parallel(regime: str) -> Topology:
    """Three disjoint well-provisioned routes. A null control.

    Under generous capacity there is no case for any of these policies. All four arms should tie,
    and a separation here means the harness is manufacturing differences.
    """
    nodes = [_node("SRC", 1e12), _node("DST", 1e12)] + \
            [_node(f"P{i}", 1e12) for i in (1, 2, 3)]
    links = []
    for i in (1, 2, 3):
        links.append(_link(f"SRC-P{i}", "SRC", f"P{i}", 100 * MBIT))
        links.append(_link(f"P{i}-DST", f"P{i}", "DST", 100 * MBIT))
    return Topology(
        name="T3-generous-parallel",
        purpose="null control: ample capacity on every route",
        path_diversity=3,
        expectation="ALL FOUR ARMS MUST TIE; any separation invalidates the harness",
        spec={"regime": regime, "nodes": nodes, "links": links})


def t4_linear(regime: str) -> Topology:
    """One route. A negative control, and the topology three earlier studies used by accident.

    Route generation and path selection are provably inert -- there is one candidate and nothing to
    choose. **Task selection is not.** Ordering still decides which flows fit once capacity is
    scarce, so arms differing in F_select can legitimately separate here. The control asserts the
    inertness that actually holds: every arm must use the same links, and at a load where nothing
    is refused all arms must tie.
    """
    nodes = [_node("SRC", 1e12), _node("RTR", 12.5e6), _node("DST", 1e12)]
    links = [_link("SRC-RTR", "SRC", "RTR", 10 * MBIT),
             _link("RTR-DST", "RTR", "DST", 10 * MBIT)]
    return Topology(
        name="T4-linear",
        purpose="F_select isolation: one route, so only task selection can act",
        path_diversity=1,
        expectation="routing MUST be inert (identical links, all arms) and arms MUST tie at "
                    "loads where nothing is refused; F_select may still separate under scarcity",
        spec={"regime": regime, "nodes": nodes, "links": links})


BUILDERS = {
    "T1": t1_single_cut,
    "T2": t2_heterogeneous_cuts,
    "T3": t3_generous_parallel,
    "T4": t4_linear,
}


def build(name: str, regime: str, **kwargs) -> Topology:
    return BUILDERS[name](regime, **kwargs)
