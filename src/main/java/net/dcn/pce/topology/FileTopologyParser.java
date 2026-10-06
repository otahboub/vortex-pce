package net.dcn.pce.topology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.ContactPlan;
import net.dcn.pce.model.ContactWindow;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.LinkIntermittencyFunction;
import net.dcn.pce.model.Node;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict structural parser and serializer for external topology files. */
public final class FileTopologyParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final Set<String> ROOT_FIELDS = Set.of("regime", "nodes", "links");
    private static final Set<String> NODE_FIELDS = Set.of(
            "nodeId", "name", "serviceRateBps", "reservoirCapacityBytes", "ipv4", "mplsLabel");
    private static final Set<String> LINK_FIELDS = Set.of(
            "linkId", "sourceNodeId", "destinationNodeId", "baseBandwidthBps",
            "propagationDelaySec", "activeContactSec", "inactivePreSec", "inactivePostSec", "contacts");
    private static final Set<String> CONTACT_FIELDS = Set.of("startSec", "endSec", "successProb");

    private FileTopologyParser() {}

    public static BaseTopology parseTopologyJson(String jsonBlob) {
        if (jsonBlob == null || jsonBlob.isBlank()) {
            throw new IllegalArgumentException("Invalid topology JSON: document cannot be empty.");
        }

        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(jsonBlob);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid topology JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Invalid topology JSON: expected an object.");
        }
        rejectUnknownFields(root, ROOT_FIELDS, "topology");

        BaseTopology topology = new BaseTopology();
        topology.setRegime(requiredRegime(root));

        JsonNode nodes = requiredArray(root, "nodes");
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("Invalid topology JSON: nodes cannot be empty.");
        }
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode item = requiredObject(nodes.get(index), "node", index);
            rejectUnknownFields(item, NODE_FIELDS, "node at index " + index);
            String nodeId = requiredText(item, "nodeId", "node", index);
            JsonNode nameNode = item.get("name");
            String name = nameNode == null ? nodeId : requiredText(item, "name", "node", index);
            double serviceRate = requiredFiniteNumber(item, "serviceRateBps", "node", index);
            double bufferCapacity = requiredFiniteNumber(item, "reservoirCapacityBytes", "node", index);
            if (serviceRate <= 0) {
                throw fieldError("node", index, "serviceRateBps", "must be positive");
            }
            if (bufferCapacity < 0) {
                throw fieldError("node", index, "reservoirCapacityBytes", "must be non-negative");
            }
            // Optional: absent means the node cannot be dispatched to, not that it is invalid.
            JsonNode addressNode = item.get("ipv4");
            String ipv4 = addressNode == null || addressNode.isNull()
                    ? null : requiredText(item, "ipv4", "node", index);
            JsonNode labelNode = item.get("mplsLabel");
            Integer mplsLabel = labelNode == null || labelNode.isNull() ? null : labelNode.asInt();
            try {
                topology.addNode(new Node(nodeId, name, serviceRate, bufferCapacity, ipv4, mplsLabel));
            } catch (IllegalArgumentException malformed) {
                throw fieldError("node", index,
                        malformed.getMessage().contains("mplsLabel") ? "mplsLabel" : "ipv4",
                        malformed.getMessage());
            }
        }

        JsonNode links = requiredArray(root, "links");
        for (int index = 0; index < links.size(); index++) {
            JsonNode item = requiredObject(links.get(index), "link", index);
            rejectUnknownFields(item, LINK_FIELDS, "link at index " + index);
            String linkId = requiredText(item, "linkId", "link", index);
            String source = requiredText(item, "sourceNodeId", "link", index);
            String destination = requiredText(item, "destinationNodeId", "link", index);
            double bandwidth = requiredFiniteNumber(item, "baseBandwidthBps", "link", index);
            double propagation = requiredFiniteNumber(item, "propagationDelaySec", "link", index);
            if (bandwidth <= 0) {
                throw fieldError("link", index, "baseBandwidthBps", "must be positive");
            }
            if (propagation < 0) {
                throw fieldError("link", index, "propagationDelaySec", "must be non-negative");
            }

            JsonNode contacts = item.get("contacts");
            boolean hasPeriodicTiming = item.has("activeContactSec")
                    || item.has("inactivePreSec") || item.has("inactivePostSec");
            if (contacts != null && hasPeriodicTiming) {
                throw fieldError("link", index, "contacts",
                        "cannot be combined with periodic timing fields");
            }
            if (contacts != null) {
                if (topology.getRegime() != ContactRegime.R_DET
                        && topology.getRegime() != ContactRegime.R_STOCH) {
                    throw fieldError("link", index, "contacts",
                            "are supported only for the R_DET and R_STOCH regimes");
                }
                topology.addLink(new Link(linkId, source, destination,
                        LinkIntermittencyFunction.persistentLink(bandwidth, propagation),
                        parseContactPlan(contacts, index)));
            } else {
                double active = requiredFiniteNumber(item, "activeContactSec", "link", index);
                double inactivePre = requiredFiniteNumber(item, "inactivePreSec", "link", index);
                double inactivePost = requiredFiniteNumber(item, "inactivePostSec", "link", index);
                if (active <= 0 || inactivePre < 0 || inactivePost < 0) {
                    throw fieldError("link", index, "timing fields",
                            "require non-negative inactive durations and a positive activeContactSec");
                }
                topology.addLink(new Link(linkId, source, destination,
                        new LinkIntermittencyFunction(
                                bandwidth, propagation, active, inactivePre, inactivePost)));
            }
        }
        return topology;
    }

    public static BaseTopology parseTopologyFile(File file) throws IOException {
        return parseTopologyJson(Files.readString(file.toPath()));
    }

    public static String toTopologyJson(BaseTopology topology) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("regime", topology.getRegime().name());

        List<Map<String, Object>> nodes = new ArrayList<>();
        topology.getNodes().stream()
                .sorted(java.util.Comparator.comparing(Node::getNodeId))
                .forEach(node -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("nodeId", node.getNodeId());
                    item.put("name", node.getName());
                    item.put("serviceRateBps", node.getServiceRateBps());
                    item.put("reservoirCapacityBytes", node.getReservoirCapacityBytes());
                    nodes.add(item);
                });
        document.put("nodes", nodes);

        List<Map<String, Object>> links = new ArrayList<>();
        topology.getLinks().stream()
                .sorted(java.util.Comparator.comparing(Link::getLinkId))
                .forEach(link -> {
                    LinkIntermittencyFunction lif = link.getLif();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("linkId", link.getLinkId());
                    item.put("sourceNodeId", link.getSourceNodeId());
                    item.put("destinationNodeId", link.getDestinationNodeId());
                    item.put("baseBandwidthBps", lif.getBaseBandwidthBps());
                    item.put("propagationDelaySec", lif.getPropagationDelaySec());
                    if (link.hasExplicitContactPlan()) {
                        List<Map<String, Object>> contacts = new ArrayList<>();
                        for (ContactWindow window : link.getContactPlan().orElseThrow().getWindows()) {
                            Map<String, Object> contact = new LinkedHashMap<>();
                            contact.put("startSec", window.startSec());
                            contact.put("endSec", window.endSec());
                            // Emit successProb only when it is a real risk value, so deterministic
                            // (R_DET) plans round-trip to byte-identical JSON.
                            if (window.successProb() < 1.0) {
                                contact.put("successProb", window.successProb());
                            }
                            contacts.add(contact);
                        }
                        item.put("contacts", contacts);
                    } else {
                        item.put("activeContactSec", lif.getActiveContactSec());
                        item.put("inactivePreSec", lif.getInactivePreSec());
                        item.put("inactivePostSec", lif.getInactivePostSec());
                    }
                    links.add(item);
                });
        document.put("links", links);

        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize topology JSON", e);
        }
    }

    private static ContactRegime requiredRegime(JsonNode root) {
        JsonNode value = root.get("regime");
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Invalid topology JSON: regime must be a string.");
        }
        try {
            return ContactRegime.valueOf(value.textValue());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid topology JSON: regime must be R_STATIC, R_DET, or R_STOCH.", e);
        }
    }

    private static ContactPlan parseContactPlan(JsonNode contacts, int linkIndex) {
        if (!contacts.isArray() || contacts.isEmpty() || contacts.size() > ContactPlan.MAX_WINDOWS) {
            throw fieldError("link", linkIndex, "contacts",
                    "must be an array containing 1 to " + ContactPlan.MAX_WINDOWS + " windows");
        }
        List<ContactWindow> windows = new ArrayList<>();
        for (int contactIndex = 0; contactIndex < contacts.size(); contactIndex++) {
            JsonNode contact = requiredObject(contacts.get(contactIndex), "contact", contactIndex);
            rejectUnknownFields(contact, CONTACT_FIELDS,
                    "contact at index " + contactIndex + " for link at index " + linkIndex);
            double startSec = requiredFiniteNumber(contact, "startSec", "contact", contactIndex);
            double endSec = requiredFiniteNumber(contact, "endSec", "contact", contactIndex);
            // successProb is optional: absent means a deterministic (1.0) contact, so R_DET plans and
            // pre-existing R_STOCH files are unchanged. Present, it is the per-contact success
            // probability the offline PCE folds into a route's joint survival probability.
            JsonNode probNode = contact.get("successProb");
            double successProb = probNode == null || probNode.isNull()
                    ? 1.0 : requiredFiniteNumber(contact, "successProb", "contact", contactIndex);
            if (successProb < 0.0 || successProb > 1.0) {
                throw fieldError("contact", contactIndex, "successProb", "must be in [0,1]");
            }
            try {
                windows.add(new ContactWindow(startSec, endSec, successProb));
            } catch (IllegalArgumentException e) {
                throw fieldError("contact", contactIndex, "time range", e.getMessage());
            }
        }
        try {
            return new ContactPlan(windows);
        } catch (IllegalArgumentException e) {
            throw fieldError("link", linkIndex, "contacts", e.getMessage());
        }
    }

    private static JsonNode requiredArray(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("Invalid topology JSON: " + field + " must be an array.");
        }
        return value;
    }

    private static JsonNode requiredObject(JsonNode value, String kind, int index) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    "Invalid topology JSON: " + kind + " at index " + index + " must be an object.");
        }
        return value;
    }

    private static String requiredText(JsonNode item, String field, String kind, int index) {
        JsonNode value = item.get(field);
        if (value == null || !value.isTextual() || value.textValue().trim().isEmpty()) {
            throw fieldError(kind, index, field, "must be a non-empty string");
        }
        return value.textValue().trim();
    }

    private static double requiredFiniteNumber(JsonNode item, String field, String kind, int index) {
        JsonNode value = item.get(field);
        if (value == null || !value.isNumber()) {
            throw fieldError(kind, index, field, "must be a JSON number");
        }
        double result = value.doubleValue();
        if (!Double.isFinite(result)) {
            throw fieldError(kind, index, field, "must be finite");
        }
        return result;
    }

    private static void rejectUnknownFields(JsonNode object, Set<String> allowed, String context) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(
                        "Invalid topology JSON: " + context + " contains unknown field " + field + ".");
            }
        }
    }

    private static IllegalArgumentException fieldError(
            String kind, int index, String field, String reason) {
        return new IllegalArgumentException(
                "Invalid topology JSON: " + kind + " at index " + index + ", " + field + " " + reason + ".");
    }
}
