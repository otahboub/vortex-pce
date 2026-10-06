package net.dcn.pce.adapter;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter previously defaulted to ONOS's stock {@code onos}/{@code rocks} credentials over
 * cleartext HTTP, so an operator who never configured it still transmitted working credentials to
 * whatever host {@code ONOS_HOST} resolved to. It must now refuse to run unconfigured.
 *
 * <p>The test relies on {@code ONOS_USER} and {@code ONOS_PASSWORD} being absent, which is the
 * same condition an unconfigured deployment has, so no environment mutation is needed and the
 * test stays order-independent.
 */
class ONOSAdapterCredentialTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.addNode(new Node("A", "A", 1_000, 1_000));
        return topology;
    }

    @Test
    void synchronisingWithoutConfiguredCredentialsIsRefused() {
        if (System.getenv("ONOS_USER") != null && System.getenv("ONOS_PASSWORD") != null) {
            return; // A configured environment cannot exercise the unconfigured path.
        }

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ONOSAdapter(new CRPEngine()).pushNetConfigToONOS(topology()));

        assertTrue(error.getMessage().contains("ONOS_USER"),
                "the operator needs to be told which variables to set: " + error.getMessage());
        assertTrue(error.getMessage().contains("no default credentials"),
                "the message should state that no default is supplied: " + error.getMessage());
    }
}
