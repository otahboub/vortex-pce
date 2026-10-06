package net.dcn.pce.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node's address is optional, and malformed only when present.
 *
 * <p>Optional because a planner needs capacity and buffer, not addresses, and every topology
 * written before the field existed must keep working. Validated because the one thing an address
 * is for is the wire: an unparseable address would otherwise surface as a PCInitiate a router
 * silently discards, which is far harder to diagnose than a startup failure.
 */
class NodeAddressTest {

    @Test
    void aNodeWithoutAnAddressIsValidAndSimplyNotDispatchable() {
        Node node = new Node("A", "A", 1e9, 1e6);
        assertTrue(node.getIpv4().isEmpty());
    }

    @Test
    void anAddressIsCarriedWhenGiven() {
        assertEquals("10.0.0.1", new Node("A", "A", 1e9, 1e6, "10.0.0.1").getIpv4().orElseThrow());
    }

    @Test
    void blankIsTreatedAsAbsentRatherThanMalformed() {
        assertTrue(new Node("A", "A", 1e9, 1e6, "   ").getIpv4().isEmpty());
    }

    @Test
    void malformedAddressesAreRefusedAtConstruction() {
        for (String bad : new String[]{"10.0.0", "10.0.0.1.2", "10.0.0.256", "10.0.0.x", "..."}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Node("A", "A", 1e9, 1e6, bad), "should refuse " + bad);
        }
    }
}
