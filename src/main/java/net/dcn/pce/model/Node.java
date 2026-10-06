package net.dcn.pce.model;

import java.util.*;

/**
 * Node representation in network base topology G = (N, E).
 * Contains node service capacity (bps) and reservoir buffer capacity (q_h in bytes).
 */
public class Node {
    private final String nodeId;
    private final String name;
    private final double serviceRateBps;     // c_i in bps
    private final double reservoirCapacityBytes; // b_i / q_h in bytes

    /**
     * The node's IPv4 address, when the operator has given it one.
     *
     * <p>Optional on purpose. A planner needs only capacity and buffer, and every topology written
     * before this field existed remains valid. It is required for one thing: a PCInitiate carries
     * END-POINTS and an ERO, so a computed schedule cannot be encoded into one unless the nodes on
     * its route have addresses. A node without an address is therefore not a broken node, it is a
     * node this controller cannot dispatch to -- which is a legible reason to refuse rather than a
     * parse error.
     */
    private final String ipv4;

    /**
     * The MPLS label a segment-routed path uses to reach this node, when the operator supplies one.
     *
     * <p>Optional for the same reason as the address, and needed for a narrower one: an SR-TE PCC
     * such as FRR's pathd rejects an RSVP-TE-style ERO of IPv4 prefixes. Reaching one requires an
     * SR-ERO of labels, which the topology has to supply because a PCE cannot invent them -- they
     * are allocated by the network, not by the controller.
     */
    private final Integer mplsLabel;

    public Node(String nodeId, String name, double serviceRateBps, double reservoirCapacityBytes) {
        this(nodeId, name, serviceRateBps, reservoirCapacityBytes, null);
    }

    public Node(String nodeId, String name, double serviceRateBps, double reservoirCapacityBytes,
                String ipv4) {
        this(nodeId, name, serviceRateBps, reservoirCapacityBytes, ipv4, null);
    }

    public Node(String nodeId, String name, double serviceRateBps, double reservoirCapacityBytes,
                String ipv4, Integer mplsLabel) {
        if (nodeId == null || nodeId.isBlank() || name == null || name.isBlank()
                || !Double.isFinite(serviceRateBps) || serviceRateBps <= 0
                || !Double.isFinite(reservoirCapacityBytes) || reservoirCapacityBytes < 0) {
            throw new IllegalArgumentException("Invalid node definition");
        }
        this.nodeId = nodeId.trim();
        this.name = name.trim();
        this.serviceRateBps = serviceRateBps;
        this.reservoirCapacityBytes = reservoirCapacityBytes;
        this.ipv4 = validatedIpv4(ipv4, nodeId);
        this.mplsLabel = validatedLabel(mplsLabel, nodeId);
    }

    /**
     * Refuses a label the network could not have allocated.
     *
     * <p>The MPLS label space is 20 bits and 0-15 are reserved for special purposes, so a value
     * outside 16..1048575 is a configuration error rather than an unusual choice. Catching it here
     * beats emitting an SR-ERO a router discards.
     */
    private static Integer validatedLabel(Integer candidate, String nodeId) {
        if (candidate == null) {
            return null;
        }
        if (candidate < 16 || candidate > 0xFFFFF) {
            throw new IllegalArgumentException("Node " + nodeId + " has an mplsLabel outside the "
                    + "usable 16..1048575 range: " + candidate);
        }
        return candidate;
    }

    /**
     * Rejects a malformed address rather than carrying it to the wire.
     *
     * <p>An address that does not parse would otherwise surface as a PCInitiate a router silently
     * discards, which is far harder to diagnose than a startup failure.
     */
    private static String validatedIpv4(String candidate, String nodeId) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String trimmed = candidate.trim();
        String[] octets = trimmed.split("\\.", -1);
        if (octets.length != 4) {
            throw new IllegalArgumentException(
                    "Node " + nodeId + " has a malformed ipv4 address: " + trimmed);
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException(
                        "Node " + nodeId + " has a malformed ipv4 address: " + trimmed);
            }
            int value = Integer.parseInt(octet);
            if (value > 255) {
                throw new IllegalArgumentException(
                        "Node " + nodeId + " has an ipv4 octet out of range: " + trimmed);
            }
        }
        return trimmed;
    }

    public String getNodeId() { return nodeId; }
    public String getName() { return name; }
    public double getServiceRateBps() { return serviceRateBps; }
    public double getReservoirCapacityBytes() { return reservoirCapacityBytes; }

    /** The node's address, empty when the operator did not give it one. */
    public Optional<String> getIpv4() { return Optional.ofNullable(ipv4); }

    /** The label reaching this node in a segment-routed path, when one is configured. */
    public Optional<Integer> getMplsLabel() { return Optional.ofNullable(mplsLabel); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Node node = (Node) o;
        return Objects.equals(nodeId, node.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }

    @Override
    public String toString() {
        return String.format("Node[%s (%s), buff=%.1fMB]", nodeId, name, reservoirCapacityBytes / (1024 * 1024));
    }
}
