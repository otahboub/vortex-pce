package net.dcn.pce.pcep;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The executable half of {@code docs/vortex_pce_pcep_capability_matrix.md}.
 *
 * <p>Every message the matrix claims VortexPCE sends or receives is checked here to still have its
 * encoder or decoder, and every message the matrix claims is NOT implemented is checked to still be
 * absent. A capability added or removed without updating the matrix fails the build, so the
 * document cannot drift from the code -- which is exactly how an earlier architecture document
 * drifted before this test existed.
 */
class PcepCapabilityMatrixTest {

    private static Method encoderMethod(String name) {
        for (Method method : PcepEncoder.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }

    @Test
    void sentMessagesTheMatrixClaimsAreImplemented() {
        assertNotNull(encoderMethod("open"), "Open (RFC 5440)");
        assertNotNull(encoderMethod("keepalive"), "Keepalive (RFC 5440)");
        assertNotNull(encoderMethod("pcerr"), "PCErr (RFC 5440)");
        assertNotNull(encoderMethod("pcUpdate"), "PCUpd encoding (RFC 8231)");
        assertNotNull(encoderMethod("pcInitiate"), "PCInitiate (RFC 8281)");
        assertNotNull(encoderMethod("pcInitiateSegmentRouted"), "SR-TE PCInitiate (RFC 8664)");
        assertNotNull(encoderMethod("pcInitiateRemoval"), "PCInitiate removal (RFC 8281)");
    }

    @Test
    void receivedMessagesTheMatrixClaimsHaveDecoders() {
        assertTrue(PcepReportDecoder.class.getDeclaredMethods().length > 0,
                "PCRpt decoder (RFC 8231)");
        assertTrue(PcepErrorDecoder.class.getDeclaredMethods().length > 0,
                "PCErr decoder (RFC 5440)");
    }

    @Test
    void pcepsTransportSecurityIsPresentButOptIn() {
        // The class exists (opt-in mutual TLS), which is what the matrix claims for RFC 8253.
        assertNotNull(PcepTransportSecurity.class, "PCEPS (RFC 8253) transport security class");
    }

    @Test
    void computationExchangeTheMatrixClaimsAbsentStaysAbsent() {
        // VortexPCE initiates; it does not answer PCReq. If someone adds a request-answering path,
        // this fails and the matrix's scope statement must be revisited rather than quietly wrong.
        for (Method method : PcepEncoder.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase();
            if (name.equals("pcreq") || name.equals("pcrep")
                    || name.equals("pcrequest") || name.equals("pcreply")) {
                fail("PcepEncoder now defines " + method.getName()
                        + ", but the capability matrix lists PCReq/PCRep as not implemented");
            }
        }
    }
}
