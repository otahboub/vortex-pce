package net.dcn.pce.topology;

import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactWindow;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.LinkIntermittencyFunction;
import net.dcn.pce.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTopologyParserTest {

    private static final String VALID = """
            {
              "regime": "R_DET",
              "nodes": [
                {"nodeId":"A","name":"A \\"core\\"","serviceRateBps":1.0e10,"reservoirCapacityBytes":0},
                {"nodeId":"B","name":"B","serviceRateBps":2.5e9,"reservoirCapacityBytes":5.0e8}
              ],
              "links": [
                {"linkId":"A-B","sourceNodeId":"A","destinationNodeId":"B",
                 "baseBandwidthBps":1.0e9,"propagationDelaySec":0.025,
                 "activeContactSec":30,"inactivePreSec":5,"inactivePostSec":5}
              ]
            }
            """;

    private static final String EXPLICIT = """
            {
              "regime": "R_DET",
              "nodes": [
                {"nodeId":"A","serviceRateBps":1000,"reservoirCapacityBytes":1000},
                {"nodeId":"B","serviceRateBps":1000,"reservoirCapacityBytes":1000}
              ],
              "links": [
                {"linkId":"A-B","sourceNodeId":"A","destinationNodeId":"B",
                 "baseBandwidthBps":1000,"propagationDelaySec":0.1,
                 "contacts":[{"startSec":2,"endSec":4},{"startSec":9,"endSec":12}]}
              ]
            }
            """;

    @Test
    void preservesScientificNotationAndEscapedStringsAcrossRoundTrip() {
        BaseTopology parsed = FileTopologyParser.parseTopologyJson(VALID);
        String serialized = FileTopologyParser.toTopologyJson(parsed);
        BaseTopology reparsed = FileTopologyParser.parseTopologyJson(serialized);

        assertEquals(1.0e10, reparsed.getNode("A").getServiceRateBps(), 0.0);
        assertEquals("A \"core\"", reparsed.getNode("A").getName());
        assertEquals(1.0e9, reparsed.getLink("A-B").getLif().getBaseBandwidthBps(), 0.0);
        assertEquals(0.025, reparsed.getLink("A-B").getLif().getPropagationDelaySec(), 0.0);
    }

    @Test
    void preservesExplicitContactPlansAcrossRoundTrip() {
        BaseTopology parsed = FileTopologyParser.parseTopologyJson(EXPLICIT);
        Link link = parsed.getLink("A-B");

        assertTrue(link.hasExplicitContactPlan());
        assertEquals(List.of(new ContactWindow(2, 4), new ContactWindow(9, 12)),
                link.getContactPlan().orElseThrow().getWindows());

        BaseTopology reparsed = FileTopologyParser.parseTopologyJson(
                FileTopologyParser.toTopologyJson(parsed));
        assertEquals(link.getContactPlan().orElseThrow().getWindows(),
                reparsed.getLink("A-B").getContactPlan().orElseThrow().getWindows());
    }

    @Test
    void rejectsAmbiguousMalformedAndOverlappingContactPlans() {
        assertThrows(IllegalArgumentException.class, () -> FileTopologyParser.parseTopologyJson(
                EXPLICIT.replace("\"contacts\":[", "\"activeContactSec\":1,\"contacts\":[")));
        assertThrows(IllegalArgumentException.class, () -> FileTopologyParser.parseTopologyJson(
                EXPLICIT.replace("\"R_DET\"", "\"R_STATIC\"")));
        assertThrows(IllegalArgumentException.class, () -> FileTopologyParser.parseTopologyJson(
                EXPLICIT.replace("\"startSec\":9,\"endSec\":12", "\"startSec\":3,\"endSec\":5")));
        assertThrows(IllegalArgumentException.class, () -> FileTopologyParser.parseTopologyJson(
                EXPLICIT.replace("\"endSec\":4", "\"endSec\":2")));
        assertThrows(IllegalArgumentException.class, () -> FileTopologyParser.parseTopologyJson(
                EXPLICIT.replace("\"startSec\":2", "\"startSec\":2,\"confidence\":1")));
    }

    @Test
    void rejectsMalformedUnknownDuplicateAndInvalidDomainData() {
        assertThrows(IllegalArgumentException.class,
                () -> FileTopologyParser.parseTopologyJson(VALID + " trailing"));
        assertThrows(IllegalArgumentException.class,
                () -> FileTopologyParser.parseTopologyJson(
                        VALID.replace("\"regime\": \"R_DET\"", "\"regime\": \"R_DET\", \"extra\": true")));
        assertThrows(IllegalArgumentException.class,
                () -> FileTopologyParser.parseTopologyJson(
                        VALID.replace("\"nodeId\":\"A\"", "\"nodeId\":\"A\",\"nodeId\":\"X\"")));
        assertThrows(IllegalArgumentException.class,
                () -> FileTopologyParser.parseTopologyJson(
                        VALID.replace("\"baseBandwidthBps\":1.0e9", "\"baseBandwidthBps\":-1")));
        assertThrows(IllegalArgumentException.class,
                () -> FileTopologyParser.parseTopologyJson(
                        VALID.replace("\"destinationNodeId\":\"B\"", "\"destinationNodeId\":\"MISSING\"")));
    }

    @Test
    void topologyRejectsDuplicateIdsDanglingLinksAndMutableAdjacency() {
        BaseTopology topology = new BaseTopology();
        topology.addNode(new Node("A", "A", 100, 0));
        topology.addNode(new Node("B", "B", 100, 0));
        topology.addLink(new Link(
                "L", "A", "B", LinkIntermittencyFunction.persistentLink(100, 0)));

        assertThrows(IllegalArgumentException.class,
                () -> topology.addNode(new Node("A", "duplicate", 100, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> topology.addLink(new Link(
                        "OTHER", "A", "C", LinkIntermittencyFunction.persistentLink(100, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> topology.addLink(new Link(
                        "L", "A", "B", LinkIntermittencyFunction.persistentLink(100, 0))));
        assertThrows(UnsupportedOperationException.class,
                () -> topology.getOutgoingLinks("A").clear());
    }
}
