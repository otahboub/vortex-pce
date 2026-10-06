package net.dcn.pce.pcep;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PCUpd (RFC 8231 section 6.2): changing an installed LSP without taking it down.
 *
 * <p>Without it the only way to change an LSP's rate is to remove it and create a new one, which
 * takes the path down in between — an outage to accomplish a rate change on a flow the controller
 * is actively carrying.
 *
 * <p>The three differences from a PCInitiate are the ones a PCC rejects if they are wrong, so
 * each is asserted rather than assumed: the message type, the PLSP-ID identifying an LSP that
 * already exists, and the absence of END-POINTS, which belongs to the initiate grammar.
 */
class PcepUpdateEncoderTest {

    private static final int OBJECT_CLASS_ENDPOINTS = 4;
    private static final int OBJECT_CLASS_LSP = 32;
    private static final int OBJECT_CLASS_SRP = 33;
    private static final int OBJECT_CLASS_ERO = 7;
    private static final int OBJECT_CLASS_BANDWIDTH = 5;

    private static byte[] update() {
        return PcepEncoder.pcUpdate(7L, 42L, "vortex-T1", 5_000_000d,
                List.of("10.0.0.1", "10.0.0.2"));
    }

    /** Walks the object headers, returning the classes present in order. */
    private static java.util.List<Integer> objectClasses(byte[] frame) {
        java.util.List<Integer> classes = new java.util.ArrayList<>();
        int declared = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        int offset = 4;
        while (offset + 4 <= declared) {
            classes.add(frame[offset] & 0xFF);
            int length = ((frame[offset + 2] & 0xFF) << 8) | (frame[offset + 3] & 0xFF);
            if (length < 4) {
                break;
            }
            offset += length;
        }
        return classes;
    }

    @Test
    void theMessageTypeIsPcUpdNotPcInitiate() {
        // 11 is PCUpd; 12 is PCInitiate. A PCC dispatches on this byte, so confusing them turns
        // an update into a request to create a second LSP.
        assertEquals(11, update()[1] & 0xFF);
    }

    @Test
    void theDeclaredLengthMatchesTheFrame() {
        byte[] frame = update();
        assertEquals(frame.length, ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF));
    }

    @Test
    void itCarriesSrpLspEroAndBandwidthInThatOrder() {
        // RFC 8231 section 6.2: <SRP><LSP><path>, where the intended path is the ERO and the rate
        // rides as an intended attribute.
        assertEquals(List.of(OBJECT_CLASS_SRP, OBJECT_CLASS_LSP, OBJECT_CLASS_ERO,
                        OBJECT_CLASS_BANDWIDTH),
                objectClasses(update()));
    }

    @Test
    void itCarriesNoEndpointsObject() {
        // END-POINTS belongs to PCInitiate and PCReq. Including it here is a grammar violation
        // that a strict PCC answers with a PCErr rather than an update.
        assertTrue(objectClasses(update()).stream().noneMatch(c -> c == OBJECT_CLASS_ENDPOINTS));
    }

    @Test
    void theLspObjectNamesTheExistingPlspId() {
        byte[] frame = update();
        int offset = 4;
        while ((frame[offset] & 0xFF) != OBJECT_CLASS_LSP) {
            offset += ((frame[offset + 2] & 0xFF) << 8) | (frame[offset + 3] & 0xFF);
        }
        int word = ((frame[offset + 4] & 0xFF) << 24) | ((frame[offset + 5] & 0xFF) << 16)
                | ((frame[offset + 6] & 0xFF) << 8) | (frame[offset + 7] & 0xFF);
        assertEquals(42L, (word >>> 12) & 0xFFFFF,
                "the PLSP-ID identifies the LSP being changed; zero would mean a new one");
        assertEquals(0, word & 0x20,
                "the create flag must be clear, or this asks for a second LSP");
    }

    @Test
    void aZeroPlspIdIsRefused() {
        // PLSP-ID 0 is reserved and names no LSP, so an update quoting it cannot be acted on.
        assertThrows(IllegalArgumentException.class,
                () -> PcepEncoder.pcUpdate(7L, 0L, "vortex-T1", 5e6, List.of("10.0.0.1")));
    }

    @Test
    void anUpdateWithNoPathIsRefused() {
        // Not a smaller update but a different message: the grammar requires an intended path,
        // and a PCC given none has nothing to install.
        assertThrows(IllegalArgumentException.class,
                () -> PcepEncoder.pcUpdate(7L, 42L, "vortex-T1", 5e6, List.of()));
    }

    @Test
    void anonPositiveRateIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PcepEncoder.pcUpdate(7L, 42L, "vortex-T1", 0d, List.of("10.0.0.1")));
    }

    @Test
    void adifferentRateProducesADifferentFrame() {
        assertNotEquals(
                java.util.Arrays.toString(PcepEncoder.pcUpdate(7L, 42L, "n", 5e6, List.of("10.0.0.1"))),
                java.util.Arrays.toString(PcepEncoder.pcUpdate(7L, 42L, "n", 9e6, List.of("10.0.0.1"))),
                "the rate is the point of the message and must reach the wire");
    }
}
