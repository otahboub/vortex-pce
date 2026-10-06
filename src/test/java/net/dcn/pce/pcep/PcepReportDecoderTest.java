package net.dcn.pce.pcep;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcepReportDecoderTest {

    /**
     * A PCRpt carrying SRP-ID 7 and PLSP-ID 42 for {@code vortex-T1}, operational UP, with the
     * Delegate and Administrative flags set.
     *
     * <p>These exact bytes were dissected by Wireshark's PCEP dissector, which was written from
     * the RFCs with no sight of this code. It reported:
     *
     * <pre>
     *   Message Type: Path Computation LSP State Report (PCRpt) (10)
     *   SRP-ID-number: 7
     *   PLSP-ID: 42
     *   Delegate (D): Set          Administrative (A): Set
     *   SYNC (S): Not set          Remove (R): Not set
     *   Operational (O): UP (1)    Create (C): Not set
     *   SYMBOLIC-PATH-NAME: vortex-T1
     * </pre>
     *
     * <p>So the assertions below check this decoder against an independent reading of the wire
     * format, not against our own encoder's assumptions.
     */
    private static final byte[] TSHARK_VALIDATED_PCRPT = HexFormat.of().parseHex(
            "200a00282110000c0000000000000007201000180002a01900110009766f727465782d5431000000");

    private static byte[] withByte(byte[] source, int index, int value) {
        byte[] copy = source.clone();
        copy[index] = (byte) value;
        return copy;
    }

    @Test
    void decodesEveryFieldTsharkReported() {
        List<ReportedLsp> reports = PcepReportDecoder.decodePcRpt(TSHARK_VALIDATED_PCRPT).reports();

        assertEquals(1, reports.size());
        ReportedLsp lsp = reports.get(0);
        assertEquals(7L, lsp.srp().orElseThrow());
        assertEquals(42L, lsp.plspId());
        assertEquals("vortex-T1", lsp.lspName());
        assertEquals(ReportedLsp.OperationalState.UP, lsp.operationalState());
        assertTrue(lsp.delegated());
        assertTrue(lsp.administrativelyUp());
        assertFalse(lsp.synchronising());
        assertFalse(lsp.reportsRemoval());
    }

    @Test
    void anUpLspIsOperationalAndAPendingOneIsNot() {
        // GOING_UP must not count as installed: an LSP still being established has confirmed
        // nothing, and treating it as installed stops the controller waiting for the real answer.
        assertTrue(ReportedLsp.OperationalState.UP.isOperational());
        assertTrue(ReportedLsp.OperationalState.ACTIVE.isOperational());
        assertFalse(ReportedLsp.OperationalState.GOING_UP.isOperational());
        assertFalse(ReportedLsp.OperationalState.DOWN.isOperational());
        assertFalse(ReportedLsp.OperationalState.GOING_DOWN.isOperational());
        assertFalse(ReportedLsp.OperationalState.UNKNOWN.isOperational());
    }

    @Test
    void anUnrecognisedOperationalValueIsReportedRatherThanGuessedAt() {
        assertEquals(ReportedLsp.OperationalState.UNKNOWN,
                ReportedLsp.OperationalState.fromWire(7));
    }

    @Test
    void aMessageThatIsNotAReportIsRejected() {
        // Message type 12 is PCInitiate. Decoding it as a report would invent an acknowledgement.
        PcepDecodeException error = assertThrows(PcepDecodeException.class,
                () -> PcepReportDecoder.decodePcRpt(withByte(TSHARK_VALIDATED_PCRPT, 1, 12)));
        assertTrue(error.getMessage().contains("message type 12"), error.getMessage());
    }

    @Test
    void aWrongProtocolVersionIsRejected() {
        assertThrows(PcepDecodeException.class,
                () -> PcepReportDecoder.decodePcRpt(withByte(TSHARK_VALIDATED_PCRPT, 0, 0x40)));
    }

    @Test
    void aDeclaredLengthThatDisagreesWithTheBufferIsRejected() {
        // Trusting either side of the disagreement reads the wrong bytes, so neither is trusted.
        assertThrows(PcepDecodeException.class,
                () -> PcepReportDecoder.decodePcRpt(withByte(TSHARK_VALIDATED_PCRPT, 3, 0xFF)));
    }

    @Test
    void truncationAtAnyOffsetIsRejectedRatherThanPartiallyDecoded() {
        for (int length = 0; length < TSHARK_VALIDATED_PCRPT.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(TSHARK_VALIDATED_PCRPT, length);
            assertThrows(PcepDecodeException.class,
                    () -> PcepReportDecoder.decodePcRpt(truncated),
                    "a message truncated to " + length + " bytes must not decode");
        }
    }

    @Test
    void anObjectOverrunningTheMessageIsRejected() {
        // Declare the SRP object longer than the remaining buffer.
        assertThrows(PcepDecodeException.class,
                () -> PcepReportDecoder.decodePcRpt(withByte(TSHARK_VALIDATED_PCRPT, 7, 0xF0)));
    }

    @Test
    void aMisalignedObjectLengthIsRejected() {
        assertThrows(PcepDecodeException.class,
                () -> PcepReportDecoder.decodePcRpt(withByte(TSHARK_VALIDATED_PCRPT, 7, 13)));
    }

    @Test
    void aZeroLengthObjectIsRejectedRatherThanLoopingForever() {
        // An object claiming length 0 would leave the cursor stationary; the decoder must refuse.
        assertThrows(PcepDecodeException.class,
                () -> PcepReportDecoder.decodePcRpt(withByte(TSHARK_VALIDATED_PCRPT, 7, 0)));
    }

    /**
     * Bytes captured from FRRouting pathd 10.4 completing state synchronisation.
     *
     * <p>Not hand-written: these came off the wire during a live interop run, split out of the
     * single TCP segment that carried both messages. Everything the controller does with a real
     * PCC's report is decided by frames of this shape, and no fixture this project authors itself
     * would have contained what these do.
     */
    private static final byte[] FRR_SYNC_REPORT = HexFormat.of().parseHex(
            "200a0058211200140000000000000000001c000400000001201200340000104200120010"
                    + "ac1d000300000000ac1d0003ac1d000200110008504f4c312d435031ffe1000600000045"
                    + "700000000712000c2408000903e8a000");

    private static final byte[] FRR_END_OF_SYNC = HexFormat.of().parseHex(
            "200a00242012001c000000000012001000000000000000000000000000000000" + "07120004");

    @Test
    void decodesARealRoutersSynchronisationReport() {
        PcepReportDecoder.PcRpt decoded = PcepReportDecoder.decodePcRpt(FRR_SYNC_REPORT);

        assertEquals(1, decoded.reports().size());
        ReportedLsp lsp = decoded.reports().get(0);
        // pathd names the LSP after the policy and candidate path, carries no SRP because this
        // answers no request of ours, and sets SYNC because it is synchronising state.
        assertEquals("POL1-CP1", lsp.lspName());
        assertEquals(1L, lsp.plspId());
        // pathd does send an SRP object here, with SRP-ID 0 -- RFC 8231's reserved "this answers
        // no request of yours". Reading that as operation zero made the report look like an
        // answer to an operation the controller never issued.
        assertTrue(lsp.srp().isEmpty(), "SRP-ID 0 means no operation, not operation 0");
        assertTrue(lsp.synchronising());
        assertEquals(ReportedLsp.OperationalState.GOING_UP, lsp.operationalState());
        assertFalse(decoded.endOfSynchronisation());
    }

    @Test
    void theEndOfSynchronisationMarkerIsRecognisedRatherThanRejected() {
        // The bug this pins: the marker has no SYMBOLIC-PATH-NAME because there is no LSP to
        // name, and the decoder demanded one from every LSP object. It threw, the session server
        // logged "Discarding unreadable report", and reconciliation -- which only runs on a
        // completed synchronisation -- was unreachable against every real PCC.
        PcepReportDecoder.PcRpt decoded = PcepReportDecoder.decodePcRpt(FRR_END_OF_SYNC);

        assertTrue(decoded.endOfSynchronisation());
        assertTrue(decoded.reports().isEmpty(), "the marker is not an LSP and must not read as one");
    }

    @Test
    void plspIdZeroWithSyncStillSetIsNotAMarker() {
        // Ending synchronisation early is the dangerous direction: after it, "not reported" starts
        // to mean "absent", and reconciliation releases capacity for LSPs the PCC had not reached
        // yet. Both conditions are required, so this one is still just a nameless LSP object.
        byte[] syncStillSet = FRR_END_OF_SYNC.clone();
        syncStillSet[9] = (byte) 0x02;

        PcepDecodeException error = assertThrows(PcepDecodeException.class,
                () -> PcepReportDecoder.decodePcRpt(syncStillSet));
        assertTrue(error.getMessage().contains("SYMBOLIC-PATH-NAME"), error.getMessage());
    }

    @Test
    void aReportWithoutASymbolicPathNameIsRejected() {
        // Without the name the report cannot be matched to an intent, which is the only reason
        // to read it. An unusable report is an error, not a success with a null field.
        byte[] noName = HexFormat.of().parseHex(
                "200a00182110000c0000000000000007201000080002a019");
        PcepDecodeException error = assertThrows(PcepDecodeException.class,
                () -> PcepReportDecoder.decodePcRpt(noName));
        assertTrue(error.getMessage().contains("SYMBOLIC-PATH-NAME"), error.getMessage());
    }

    @Test
    void aReportWithNoSrpObjectStillDecodes() {
        // A PCC's unsolicited state report carries no SRP: it answers no operation of ours.
        byte[] unsolicited = HexFormat.of().parseHex(
                "200a001c201000180002a01900110009766f727465782d5431000000");
        List<ReportedLsp> reports = PcepReportDecoder.decodePcRpt(unsolicited).reports();

        assertEquals(1, reports.size());
        assertTrue(reports.get(0).srp().isEmpty());
        assertEquals("vortex-T1", reports.get(0).lspName());
    }

    @Test
    void anEmptyOrHeaderOnlyMessageIsRejected() {
        assertThrows(PcepDecodeException.class, () -> PcepReportDecoder.decodePcRpt(null));
        assertThrows(PcepDecodeException.class, () -> PcepReportDecoder.decodePcRpt(new byte[0]));
        assertThrows(PcepDecodeException.class,
                () -> PcepReportDecoder.decodePcRpt(HexFormat.of().parseHex("200a0004")));
    }
}
