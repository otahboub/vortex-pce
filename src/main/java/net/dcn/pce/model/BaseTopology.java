package net.dcn.pce.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Base Network Topology graph G = (N, E).
 * Holds set of nodes N and links E.
 */
public class BaseTopology {
    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final Map<String, List<Link>> outgoingLinks = new ConcurrentHashMap<>();
    private ContactRegime regime = ContactRegime.R_DET;

    public void addNode(Node node) {
        Objects.requireNonNull(node, "node");
        if (nodes.putIfAbsent(node.getNodeId(), node) != null) {
            throw new IllegalArgumentException("Duplicate node ID: " + node.getNodeId());
        }
        outgoingLinks.putIfAbsent(node.getNodeId(), new CopyOnWriteArrayList<>());
    }

    public void addLink(Link link) {
        Objects.requireNonNull(link, "link");
        if (!nodes.containsKey(link.getSourceNodeId()) || !nodes.containsKey(link.getDestinationNodeId())) {
            throw new IllegalArgumentException("Link endpoints must reference existing nodes: " + link.getLinkId());
        }
        if (links.putIfAbsent(link.getLinkId(), link) != null) {
            throw new IllegalArgumentException("Duplicate link ID: " + link.getLinkId());
        }
        outgoingLinks.get(link.getSourceNodeId()).add(link);
    }

    public Node getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public Link getLink(String linkId) {
        return links.get(linkId);
    }

    public Collection<Node> getNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Collection<Link> getLinks() {
        return Collections.unmodifiableCollection(links.values());
    }

    public List<Link> getOutgoingLinks(String nodeId) {
        return List.copyOf(outgoingLinks.getOrDefault(nodeId, Collections.emptyList()));
    }

    public ContactRegime getRegime() { return regime; }
    public void setRegime(ContactRegime regime) { this.regime = Objects.requireNonNull(regime, "regime"); }

    public int getNodeCount() { return nodes.size(); }
    public int getLinkCount() { return links.size(); }
}
