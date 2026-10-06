package net.dcn.pce.pcep;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The segment-routed PCInitiate, checked against bytes FRRouting itself produced.
 *
 * <p>The reference is not a reading of the RFC. During an interop run pathd sent this controller a
 * PCRpt describing its own segment-routed policy, and these are the exact subobject and TLV bytes
 * it used. Encoding to what the peer emits is the strongest check available short of the peer
 * accepting the result.
 */
class SegmentRoutedInitiateTest {

    /** From FRR's PCRpt: SR-ERO subobject, type 36, NAI absent, flags M|F, label 16010. */
    private static final String FRR_SR_SUBOBJECT = "2408000903e8a000";

    /** From FRR's SRP: PATH-SETUP-TYPE TLV, type 28, length 4, PST 1 (segment routing). */
    private static final String FRR_PATH_SETUP_TYPE_TLV = "001c000400000001";

    private static String hex(byte[] frame) {
        return HexFormat.of().formatHex(frame);
    }

    @Test
    void theSrEroSubobjectMatchesTheOneFrrEmits() {
        byte[] frame = PcepEncoder.pcInitiateSegmentRouted(
                7L, "vortex-T1", "10.0.0.1", "10.0.0.2", 1.0e7, List.of(16010));

        assertTrue(hex(frame).contains(FRR_SR_SUBOBJECT),
                "SR-ERO subobject should be byte-identical to FRR's own: " + hex(frame));
    }

    @Test
    void theOpenAdvertisesSegmentRoutingPathSetupCapability() {
        // RFC 8408/8664: without a PATH-SETUP-TYPE-CAPABILITY TLV (type 34) listing SR (PST 1),
        // FRR pathd rejects an SR PCInitiate as an unacceptable instantiation parameter. The value
        // is Reserved(3) + Num-of-PSTs(1)=2 + PST 0 (RSVP-TE) + PST 1 (SR), so 00000002 0001.
        String open = hex(PcepEncoder.open(30, 120, 1, "vortex-pce"));
        assertTrue(open.contains("00220006" + "000000020001"),
                "OPEN should carry PATH-SETUP-TYPE-CAPABILITY with PST 0 and 1: " + open);
    }

    @Test
    void theSrInitiateCarriesTheCiscoVendorColour() {
        // FRR pathd keys a PCE-initiated SR policy by (colour, endpoint) and reads the colour from a
        // Cisco vendor object (enterprise 9 = 00000009, marker 65540 = 00010004). Without it the LSP
        // is refused. The colour itself is derived from the LSP name, so only the fixed prefix is
        // asserted here.
        String frame = hex(PcepEncoder.pcInitiateSegmentRouted(
                7L, "vortex-T1", "10.0.0.1", "10.0.0.2", 1.0e7, List.of(16010)));
        assertTrue(frame.contains("22100010" /* VENDOR-INFO object: class 34, type 1, length 16 */),
                "SR initiate should carry a VENDOR-INFORMATION object: " + frame);
        assertTrue(frame.contains("0000000900010004"),
                "vendor object should identify Cisco (9) and the colour marker (65540): " + frame);
    }

    @Test
    void theSrpDeclaresSegmentRoutingAsThePathSetupType() {
        byte[] frame = PcepEncoder.pcInitiateSegmentRouted(
                7L, "vortex-T1", "10.0.0.1", "10.0.0.2", 1.0e7, List.of(16010));

        // Without this a PCC reads the ERO as RSVP-TE and misparses every subobject after it.
        assertTrue(hex(frame).contains(FRR_PATH_SETUP_TYPE_TLV),
                "SRP must carry PATH-SETUP-TYPE 1: " + hex(frame));
    }

    @Test
    void theRemovalDeclaresTheSameSegmentRoutingPathSetupType() {
        byte[] frame = PcepEncoder.pcInitiateRemoval(8L, 42L, "vortex-T1");

        assertTrue(hex(frame).contains(FRR_PATH_SETUP_TYPE_TLV),
                "SR removal must carry PATH-SETUP-TYPE 1: " + hex(frame));
    }

    @Test
    void theRemovalMarksTheLspAsDelegated() {
        byte[] frame = PcepEncoder.pcInitiateRemoval(8L, 42L, "vortex-T1");

        // PLSP-ID 42 occupies the high 20 bits. The low flags are A=0x008 and D=0x001.
        // FRR resolves the correct policy without D but then rejects removal with PCErr 19/1.
        assertTrue(hex(frame).contains("0002a009"),
                "removal LSP object must carry the delegation flag: " + hex(frame));
    }

    @Test
    void eachHopContributesItsOwnSubobjectInPathOrder() {
        byte[] frame = PcepEncoder.pcInitiateSegmentRouted(
                7L, "vortex-T1", "10.0.0.1", "10.0.0.3", 1.0e7, List.of(16010, 16020));
        String encoded = hex(frame);

        int first = encoded.indexOf("2408000903e8a000");   // label 16010 -> SID 0x03e8a000
        int second = encoded.indexOf("2408000903e94000");  // label 16020 -> SID 0x03e94000
        assertTrue(first >= 0, "first label missing: " + encoded);
        assertTrue(second > first, "second label must follow the first in path order: " + encoded);
    }

    @Test
    void itIsStillAPcInitiateWithAConsistentLength() {
        byte[] frame = PcepEncoder.pcInitiateSegmentRouted(
                9L, "vortex-T1", "10.0.0.1", "10.0.0.2", 1.0e7, List.of(16010));

        assertEquals(12, frame[1] & 0xFF, "message type 12 is PCInitiate");
        assertEquals(frame.length, ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF));
    }

    @Test
    void labelsOutsideTheUsableRangeAreRefused() {
        // 0-15 are reserved and the space is 20 bits; a value outside that is a configuration
        // error, and emitting it would produce an SR-ERO the router discards.
        for (int bad : new int[]{0, 15, 1 << 20}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PcepEncoder.pcInitiateSegmentRouted(
                            7L, "vortex-T1", "10.0.0.1", "10.0.0.2", 1.0e7, List.of(bad)),
                    "should refuse label " + bad);
        }
    }
}
