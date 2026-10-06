package net.dcn.pce.pcep;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a committed schedule into the PCInitiate that asks a router to install it.
 *
 * <p>This is the join the controller was missing. The planner produced a route and a rate, and the
 * southbound could send a PCInitiate, but nothing could turn one into the other: a PCInitiate
 * carries END-POINTS and an ERO, and the topology model had no addresses to put in them. A
 * computed schedule was therefore not encodable at all, which is why every measured result in this
 * repository so far was the engine's arithmetic applied to a testbed by hand.
 *
 * <p>Encoding fails cleanly rather than partially. A route through a node with no address returns
 * empty and names the node, because a PCInitiate with an invented or omitted hop is worse than no
 * PCInitiate: the router either rejects it or, worse, installs a path nobody chose.
 */
public final class ScheduleInitiate {

    private ScheduleInitiate() {
    }

    /**
     * Encodes one committed schedule.
     *
     * @return the frame, or the reason it cannot be built
     */
    public static Result encode(CRPEngine.CommittedFlowSchedule schedule, BaseTopology topology,
                                long srpId, String lspName) {
        return encode(schedule.getRoute(), schedule.getCommittedRateBps(), topology, srpId,
                lspName);
    }

    /**
     * Encodes from the route and rate alone, which is all a PCInitiate needs.
     *
     * <p>Separated from the solve result so an installation that was planned but not sent can be
     * encoded later — after a restart, say — from something small enough to have been persisted.
     * A whole {@code CommittedFlowSchedule} carries the task, per-hop timings and buffer figures
     * that the wire format never sees.
     */
    public static Result encode(List<Link> route, double committedRateBps, BaseTopology topology,
                                long srpId, String lspName) {
        if (route.isEmpty()) {
            return Result.refused("schedule has no route");
        }

        // Every node the traffic touches, in order: the first link's source, then each
        // destination. The ERO must name the whole path, so a single unaddressed node on it is
        // enough to make the schedule undeliverable.
        List<String> nodeIds = new ArrayList<>();
        nodeIds.add(route.get(0).getSourceNodeId());
        for (Link link : route) {
            nodeIds.add(link.getDestinationNodeId());
        }

        List<String> hops = new ArrayList<>();
        for (String nodeId : nodeIds) {
            Node node = topology.getNode(nodeId);
            if (node == null) {
                return Result.refused("route crosses unknown node " + nodeId);
            }
            Optional<String> address = node.getIpv4();
            if (address.isEmpty()) {
                return Result.refused("node " + nodeId + " has no ipv4 address, so this schedule "
                        + "cannot be encoded into a PCInitiate");
            }
            hops.add(address.get());
        }

        double rateBps = committedRateBps;
        if (!Double.isFinite(rateBps) || rateBps <= 0) {
            return Result.refused("schedule has no usable rate");
        }

        // Segment routing when the topology supplies labels for the hops, RSVP-TE otherwise. The
        // choice is the topology's rather than a setting, because it is a property of the network:
        // an SR-TE PCC rejects a prefix ERO, and a network with no labels configured cannot be
        // sent an SR-ERO. One label per link, taken from the node the link arrives at.
        List<Integer> labels = new ArrayList<>();
        for (Link link : route) {
            Node arrival = topology.getNode(link.getDestinationNodeId());
            Optional<Integer> label = arrival == null ? Optional.empty() : arrival.getMplsLabel();
            if (label.isEmpty()) {
                labels.clear();
                break;
            }
            labels.add(label.get());
        }

        if (!labels.isEmpty()) {
            return Result.encoded(PcepEncoder.pcInitiateSegmentRouted(
                    srpId, lspName, hops.get(0), hops.get(hops.size() - 1), rateBps, labels));
        }
        return Result.encoded(PcepEncoder.pcInitiate(
                srpId, lspName, hops.get(0), hops.get(hops.size() - 1), rateBps, hops));
    }

    /** Either a frame to send, or the reason there is none. */
    public static final class Result {
        private final byte[] frame;
        private final String refusal;

        private Result(byte[] frame, String refusal) {
            this.frame = frame;
            this.refusal = refusal;
        }

        static Result encoded(byte[] frame) {
            return new Result(frame, null);
        }

        static Result refused(String reason) {
            return new Result(null, reason);
        }

        public boolean isEncoded() {
            return frame != null;
        }

        public byte[] frame() {
            if (frame == null) {
                throw new IllegalStateException("no frame: " + refusal);
            }
            return frame.clone();
        }

        public String refusal() {
            return refusal;
        }
    }
}
