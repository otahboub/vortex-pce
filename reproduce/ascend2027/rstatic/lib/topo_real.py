"""Two real topologies: a Kuiper-class LEO constellation for R_DET, and Internet2 + OARnet for R_STATIC.

The synthetic shapes in `topologies` are named for the stage they isolate, and they are honest
about being constructed. These two are different: they claim to be real networks, so every number
carries a note saying where it came from and which of the three kinds it is.

    SOURCED   published by the operator; cited below and re-checkable
    COMPUTED  derived here from sourced parameters -- orbital geometry, great-circle distance
    ASSUMED   a modelling choice this study makes, stated so it can be argued with

The distinction is the whole point. Hand-drawing a plausible graph and labelling it "Internet2"
would be indistinguishable from the real thing in a results table, and this repository has already
had to retract two claims that were built that way.

A note on what is deliberately *not* modelled: OARnet publishes ring membership and metro
capacities but not link-level adjacency, and Amazon has published no inter-satellite link
topology. Neither gap is filled by guessing. Ring adjacency is marked ASSUMED and derived from
geography; the constellation is modelled bent-pipe with store-and-forward, which needs no ISL
data.
"""
import math
from typing import Dict, List, Optional, Tuple

from .topologies import Topology, _node, _link, MBIT
from .leo_constellation import (AMAZON_LEO_SHELLS, AMAZON_LEO_PROVENANCE, GroundStation,
                                compute_contacts, EARTH_RADIUS_KM)

GBIT = 1e9
#: Group velocity in single-mode fibre, ~2/3 c. ASSUMED (standard engineering figure).
FIBRE_VELOCITY_M_S = 2.0e8
#: Route kilometres per great-circle kilometre. ASSUMED; 1.5 is the usual long-haul rule of thumb.
FIBRE_ROUTE_FACTOR = 1.5


def _great_circle_km(a: Tuple[float, float], b: Tuple[float, float]) -> float:
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    h = (math.sin((lat2 - lat1) / 2) ** 2
         + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2)
    return 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(h))


def _fibre_delay_sec(a, b) -> float:
    """COMPUTED: great-circle distance, inflated for route slack, over fibre group velocity."""
    return _great_circle_km(a, b) * 1000.0 * FIBRE_ROUTE_FACTOR / FIBRE_VELOCITY_M_S


# ---------------------------------------------------------------------------
# R_STATIC -- Internet2 NGI backbone with OARnet attached at the Ohio PoPs
# ---------------------------------------------------------------------------

#: SOURCED. City coordinates are public geographic fact.
CITY_COORDS = {
    "CHI": (41.8781, -87.6298), "TOL": (41.6528, -83.5379), "CLE": (41.4993, -81.6944),
    "CIN": (39.1031, -84.5120), "PIT": (40.4406, -79.9959), "ASH": (39.0438, -77.4874),
    "BUF": (42.8864, -78.8784), "STL": (38.6270, -90.1994), "LOU": (38.2527, -85.7585),
    "COL": (39.9612, -82.9988), "DAY": (39.7589, -84.1916), "AKR": (41.0814, -81.5190),
    "YNG": (41.0998, -80.6495), "LIM": (40.7426, -84.1052), "ATH": (39.3292, -82.1013),
    "POR": (38.7317, -82.9977),
}

#: SOURCED -- read off Internet2's own "Network Infrastructure Topology, April 2024" map by
#: extracting the dashed "Fiber Infrastructure" polylines and matching each vertex to its city
#: label. Every pair below is a segment actually drawn on that map.
#: CLE-PIT and PIT-ASH come from a three-vertex polyline whose middle vertex sits between the
#: Cleveland and Pittsburgh labels; Pittsburgh is the reading consistent with the geography, but
#: it is an inference from a bend rather than a labelled node, and is marked as such.
INTERNET2_FIBRE = [
    ("CHI", "TOL"), ("TOL", "CLE"), ("CLE", "BUF"),
    ("CLE", "PIT"), ("PIT", "ASH"),          # middle vertex inferred as Pittsburgh
    ("CLE", "CIN"), ("CHI", "CIN"),
    ("CIN", "LOU"), ("CHI", "STL"),
]
#: SOURCED: Internet2 NGI runs a 400G routed backbone (29 packet nodes, forty 400G circuits).
INTERNET2_LINK_BPS = 400 * GBIT

#: SOURCED from OARnet's published architecture page: six rings, and the named cities on each.
#: The *membership* is published; the exact adjacency within each ring is not, so the ring
#: orderings below are ASSUMED from geography and marked accordingly.
OARNET_RINGS = [
    ("northwest", ["COL", "LIM", "TOL"]),
    ("southwest", ["COL", "DAY", "CIN"]),
    ("northeast", ["COL", "AKR", "CLE", "YNG"]),
    ("southeast", ["COL", "ATH", "POR"]),
]
#: SOURCED: "Akron, Cincinnati, Cleveland, Columbus, Dayton and Toledo" carry 100 Gbps;
#: "the remainder of the network backbone provides bandwidth of 10 gigabit per second."
OARNET_100G_METROS = {"AKR", "CIN", "CLE", "COL", "DAY", "TOL"}

#: SOURCED: OARnet reaches Internet2 through Cleveland, and the 2012 100G build placed
#: Internet2 interconnects at the Cleveland and Cincinnati hubs.
OARNET_UPLINKS = [("CLE", "CLE"), ("CIN", "CIN")]

INTERNET2_OARNET_PROVENANCE = (
    "Internet2 node set and fibre adjacency: extracted from Internet2's published 'Network "
    "Infrastructure Topology, April 2024' PDF (dashed Fiber Infrastructure polylines, vertices "
    "matched to city labels). Backbone rate 400 Gbps: Internet2 NGI, 29 packet nodes on forty "
    "400G circuits. OARnet ring membership, 100G metros (Akron/Cincinnati/Cleveland/Columbus/"
    "Dayton/Toledo), 10G remainder, and the Internet2 interconnect at Cleveland: OARnet's "
    "published network architecture pages. ASSUMED: within-ring adjacency order (geography), "
    "fibre route factor 1.5, group velocity 2e8 m/s, node reservoir sizes."
)


def internet2_oarnet(regime: str = "R_STATIC",
                     reservoir_bytes: float = 2.0e9,
                     demand_scale: float = 1.0,
                     source_node: str = "OAR-COL",
                     sink_node: str = "I2-CHI") -> Topology:
    """Internet2's Ohio-region fibre with OARnet's rings hanging off the Cleveland and Cincinnati PoPs.

    `demand_scale` divides every link rate. The real network is provisioned at 100-400 Gbps, which
    no plausible task set in this harness can congest; dividing all capacities by a constant
    preserves the *ratios* between backbone, metro and regional links -- which is what routing
    decisions turn on -- while moving the whole network into a range where contention exists. That
    is a scale change, not a topology change, and it is reported in the run log.

    The user's expectation for this regime is that reservoir reduction matters little and that
    schedulability and completion earliness separate Classic from Optimized. Nothing here is
    arranged to produce that; the topology is what the two operators publish.
    """
    scale = 1.0 / demand_scale
    nodes: Dict[str, Dict] = {}
    links: List[Dict] = []

    def ensure(node_id: str, reservoir: float) -> None:
        if node_id not in nodes:
            nodes[node_id] = _node(node_id, reservoir)

    # Internet2 backbone. Transit nodes carry a bounded reservoir; every one is a transit node.
    for a, b in INTERNET2_FIBRE:
        ensure(f"I2-{a}", reservoir_bytes)
        ensure(f"I2-{b}", reservoir_bytes)
        delay = _fibre_delay_sec(CITY_COORDS[a], CITY_COORDS[b])
        for src, dst in ((a, b), (b, a)):
            links.append(_link(f"I2-{src}-{dst}", f"I2-{src}", f"I2-{dst}",
                               INTERNET2_LINK_BPS * scale, propagation_sec=delay))

    # OARnet rings. Each ring closes back on Columbus.
    for ring_name, members in OARNET_RINGS:
        loop = members + [members[0]]
        for a, b in zip(loop, loop[1:]):
            ensure(f"OAR-{a}", reservoir_bytes)
            ensure(f"OAR-{b}", reservoir_bytes)
            rate = (100 * GBIT if a in OARNET_100G_METROS and b in OARNET_100G_METROS
                    else 10 * GBIT)
            delay = _fibre_delay_sec(CITY_COORDS[a], CITY_COORDS[b])
            for src, dst in ((a, b), (b, a)):
                link_id = f"OAR-{ring_name[:2]}-{src}-{dst}"
                if any(l["linkId"] == link_id for l in links):
                    continue
                links.append(_link(link_id, f"OAR-{src}", f"OAR-{dst}",
                                   rate * scale, propagation_sec=delay))

    # OARnet -> Internet2 handoffs. Intra-facility, so propagation is negligible.
    for oar_city, i2_city in OARNET_UPLINKS:
        ensure(f"OAR-{oar_city}", reservoir_bytes)
        ensure(f"I2-{i2_city}", reservoir_bytes)
        for src, dst in ((f"OAR-{oar_city}", f"I2-{i2_city}"), (f"I2-{i2_city}", f"OAR-{oar_city}")):
            links.append(_link(f"UP-{src}-{dst}", src, dst, 100 * GBIT * scale,
                               propagation_sec=50e-6))

    return Topology(
        name="R-STATIC-internet2-oarnet",
        purpose="Internet2 Ohio-region fibre with OARnet's four rings attached at the Cleveland "
                "and Cincinnati PoPs",
        path_diversity=_count_paths(links, source_node, sink_node),
        expectation=(f"{source_node} -> {sink_node}: "
                     f"{_edge_disjoint_paths(links, source_node, sink_node)} edge-disjoint routes, "
                     "so routing can genuinely act; reservoir reduction should matter little here "
                     "(capacity is generous relative to any single flow); Classic and Optimized "
                     "should separate on schedulability and completion earliness, not on buffer"),
        spec={"regime": regime, "nodes": list(nodes.values()), "links": links},
        provenance=INTERNET2_OARNET_PROVENANCE)


def _count_paths(links: List[Dict], src: str, dst: str, cap: int = 5000) -> int:
    """COMPUTED: simple paths from src to dst, so a run's log shows whether routing could act."""
    adj: Dict[str, List[str]] = {}
    for l in links:
        adj.setdefault(l["sourceNodeId"], []).append(l["destinationNodeId"])
    found = 0
    stack = [(src, {src})]
    while stack and found < cap:
        node, seen = stack.pop()
        for nxt in adj.get(node, ()):
            if nxt == dst:
                found += 1
            elif nxt not in seen and len(seen) < 12:
                stack.append((nxt, seen | {nxt}))
    return found


def _edge_disjoint_paths(links: List[Dict], src: str, dst: str) -> int:
    """COMPUTED: max edge-disjoint routes, by unit-capacity max-flow.

    Simple-path counts flatter a topology: six routes that all funnel through one final edge are
    one route wearing a disguise, and that is the difference between exercising path selection and
    only appearing to. This is the number that says whether routing has anything to choose.
    """
    cap: Dict[Tuple[str, str], int] = {}
    adj: Dict[str, set] = {}
    for l in links:
        a, b = l["sourceNodeId"], l["destinationNodeId"]
        cap[(a, b)] = cap.get((a, b), 0) + 1
        adj.setdefault(a, set()).add(b)
        adj.setdefault(b, set()).add(a)
    flow = 0
    while True:
        parent = {src: None}
        queue = [src]
        while queue and dst not in parent:
            node = queue.pop(0)
            for nxt in adj.get(node, ()):
                if nxt not in parent and cap.get((node, nxt), 0) > 0:
                    parent[nxt] = node
                    queue.append(nxt)
        if dst not in parent:
            return flow
        node = dst
        while parent[node] is not None:
            prev = parent[node]
            cap[(prev, node)] -= 1
            cap[(node, prev)] = cap.get((node, prev), 0) + 1
            node = prev
        flow += 1


# ---------------------------------------------------------------------------
# R_DET -- Amazon Leo (Project Kuiper) store-and-forward relay
# ---------------------------------------------------------------------------

#: SOURCED: Amazon's announced customer terminal classes -- ~100 Mbps compact, ~400 Mbps standard,
#: ~1 Gbps enterprise. The standard terminal is used for the remote sites.
LEO_TERMINAL_BPS = 400 * MBIT
#: ASSUMED. Amazon has not published feeder-link capacity per satellite; 1 Gbps is a deliberately
#: conservative stand-in and is the binding rate on the downlink side.
LEO_FEEDER_BPS = 1 * GBIT

AMAZON_LEO_TOPOLOGY_PROVENANCE = (
    AMAZON_LEO_PROVENANCE + " Terminal rate 400 Mbps: Amazon's announced standard customer "
    "terminal class. ASSUMED: 1 Gbps feeder link per satellite (unpublished), onboard reservoir "
    "size, 25-degree elevation mask, and the store-and-forward operating mode itself -- Amazon "
    "operates bent-pipe and is adding optical ISLs, NOT store-and-forward. What is modelled here "
    "is a data-mule regime over a Kuiper-class constellation: a real mode for disconnected-site "
    "and earth-observation downlink, and the regime in which contact scheduling is the whole "
    "problem. The constellation geometry is Amazon's; the operating mode is this study's."
)

#: Remote sites sit in Ohio, keeping continuity with the R_STATIC study; the gateway is in
#: Frankfurt. The pairing is chosen for its *geometry*, not for tidiness: satellites travel
#: west-to-east, so a westward gateway costs almost a full orbit (measured: 92-197 minute holds)
#: and a gateway as near as Northern Virginia is reached in under two minutes, which is barely
#: store-and-forward at all. Frankfurt sits where the measured hold is 17-19 minutes -- long
#: enough that onboard storage genuinely binds, short enough for deadlines a study can reason
#: about. Those three figures are COMPUTED by this module and are reproducible.
LEO_SITES = [GroundStation("COLUMBUS", 39.9612, -82.9988),
             GroundStation("CLEVELAND", 41.4993, -81.6944),
             GroundStation("CINCINNATI", 39.1031, -84.5120)]
LEO_GATEWAY = GroundStation("GW-FRANKFURT", 50.1109, 8.6821)


def amazon_leo(regime: str = "R_DET",
               reservoir_bytes: float = 2.0e9,
               horizon_sec: float = 12000.0,
               min_elevation_deg: float = 25.0,
               max_satellites_per_shell: int = 220,
               step_sec: float = 15.0,
               min_contact_sec: float = 30.0) -> Topology:
    """Ohio sites uplink to a Kuiper-class satellite, which carries the data to an Frankfurt gateway.

    Only satellites that see both an Ohio site and the gateway within the horizon become nodes --
    the rest cannot relay anything and would be dead weight in the graph. Each link carries the
    *actual computed pass windows* as an explicit contact plan rather than a periodic duty cycle,
    which is the representation the engine reserves for R_DET.

    The satellite's onboard reservoir is the bounded resource, and unlike the terrestrial case it
    binds for a real reason: a satellite holding data between an Ohio pass and a Frankfurt pass is
    storing it for minutes, and onboard storage is genuinely scarce.
    """
    if regime != "R_DET":
        raise ValueError("the Amazon Leo contact plan is only meaningful under R_DET; "
                         f"got {regime}")

    stations = LEO_SITES + [LEO_GATEWAY]
    windows = [w for w in compute_contacts(stations, horizon_sec=horizon_sec, step_sec=step_sec,
                                           min_elevation_deg=min_elevation_deg,
                                           max_satellites_per_shell=max_satellites_per_shell)
               if w.duration_sec >= min_contact_sec]

    uplinks: Dict[str, List] = {}
    downlinks: Dict[str, List] = {}
    for w in windows:
        (downlinks if w.ground_station == LEO_GATEWAY.name else uplinks) \
            .setdefault(w.satellite, []).append(w)

    # A satellite is only useful if it can pick data up and later put it down.
    relays = []
    for sat, ups in uplinks.items():
        downs = downlinks.get(sat)
        if not downs:
            continue
        if min(u.start_sec for u in ups) < max(d.end_sec for d in downs):
            relays.append(sat)
    relays.sort()

    nodes = [_node(s.name, 1e15) for s in LEO_SITES]
    nodes.append(_node(LEO_GATEWAY.name, 1e15))
    links: List[Dict] = []

    for sat in relays:
        nodes.append(_node(sat, reservoir_bytes))
        # One link per (site, satellite) pair, carrying every pass that pair gets.
        for site in sorted({w.ground_station for w in uplinks[sat]}):
            links.append(_contact_link(f"UP-{site}-{sat}", site, sat,
                                       LEO_TERMINAL_BPS, uplinks[sat], site))
        links.append(_contact_link(f"DN-{sat}-{LEO_GATEWAY.name}", sat, LEO_GATEWAY.name,
                                   LEO_FEEDER_BPS, downlinks[sat], LEO_GATEWAY.name))

    return Topology(
        name="R-DET-amazon-leo",
        purpose="Kuiper-class constellation relaying Ohio sites to a Frankfurt gateway by "
                "store-and-forward; onboard storage is the bounded resource",
        path_diversity=len(relays),
        expectation="contact-aware arms should separate sharply: a route is only usable if its "
                    "uplink window, onboard hold and downlink window line up, and onboard "
                    "reservoir is a real constraint because holds last minutes",
        spec={"regime": regime, "nodes": nodes, "links": links},
        provenance=AMAZON_LEO_TOPOLOGY_PROVENANCE)


def _contact_link(link_id: str, src: str, dst: str, bps: float, windows,
                  station_name: str) -> Dict:
    """A link whose availability is the computed pass list, as an explicit R_DET contact plan."""
    mine = sorted((w for w in windows if w.ground_station == station_name),
                  key=lambda w: w.start_sec)
    return {"linkId": link_id, "sourceNodeId": src, "destinationNodeId": dst,
            "baseBandwidthBps": bps,
            # COMPUTED: slant range at a 25-degree mask for a ~610 km shell is ~1,100 km.
            "propagationDelaySec": 1100e3 / 3.0e8,
            "contacts": [{"startSec": round(w.start_sec, 3), "endSec": round(w.end_sec, 3)}
                         for w in mine]}
