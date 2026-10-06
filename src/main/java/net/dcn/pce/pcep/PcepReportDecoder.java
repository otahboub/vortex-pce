package net.dcn.pce.pcep;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes RFC 8231 PCRpt messages into {@link ReportedLsp} values.
 *
 * <p>Nothing in this project could previously read a PCC's report, which is why no acknowledgement
 * could be correlated and no installation could be confirmed.
 *
 * <p>This parses bytes from an unauthenticated peer, so it is written to be hostile-input safe:
 * every length is bounds-checked against the remaining buffer before use, declared lengths are
 * never trusted to be self-consistent, and a malformed message raises rather than returning a
 * partially populated result. Object types this implementation does not handle are skipped by
 * their declared length rather than rejected, because a conformant PCC may legitimately include
 * them.
 */
public final class PcepReportDecoder {

    private static final int COMMON_HEADER_LENGTH = 4;
    private static final int OBJECT_HEADER_LENGTH = 4;
    private static final int PCEP_VERSION = 1;
    private static final int MSG_TYPE_PCRPT = 10;

    private static final int OBJECT_CLASS_LSP = 32;
    private static final int OBJECT_CLASS_SRP = 33;
    private static final int TLV_SYMBOLIC_PATH_NAME = 17;

    // Within the 12-bit LSP flag field.
    private static final int LSP_FLAG_OPERATIONAL_MASK = 0x070;
    private static final int LSP_FLAG_OPERATIONAL_SHIFT = 4;
    private static final int LSP_FLAG_ADMINISTRATIVE = 0x008;
    private static final int LSP_FLAG_REMOVE = 0x004;
    private static final int LSP_FLAG_SYNC = 0x002;
    private static final int LSP_FLAG_DELEGATE = 0x001;

    /** A symbolic path name longer than this is refused rather than allocated. */
    private static final int MAX_LSP_NAME_BYTES = 1024;

    private PcepReportDecoder() {
    }

    /**
     * One decoded PCRpt: the LSPs it reported, and whether it ended state synchronisation.
     *
     * <p>The marker is a separate field rather than an entry in {@code reports} because it is not
     * an LSP. RFC 8231 §5.6 signals the end of synchronisation with an LSP object carrying
     * PLSP-ID 0 and the SYNC flag clear, and no symbolic path name -- there is no LSP for it to
     * name. An earlier version of this decoder demanded a name from every LSP object and threw on
     * this one, so the whole message was discarded as unreadable. FRR's pathd sends it at the end
     * of every session's sync, which meant reconciliation -- the one thing that is gated on
     * synchronisation being complete -- could never run against a real router.
     */
    public record PcRpt(List<ReportedLsp> reports, boolean endOfSynchronisation) {
        public PcRpt {
            reports = List.copyOf(reports);
        }
    }

    /**
     * Decodes a complete PCRpt message.
     *
     * @param frame the full message including its common header
     * @return the reports in wire order, and whether synchronisation ended here
     * @throws PcepDecodeException if the message is not a well-formed PCRpt
     */
    public static PcRpt decodePcRpt(byte[] frame) {
        if (frame == null || frame.length < COMMON_HEADER_LENGTH) {
            throw new PcepDecodeException("PCRpt message is shorter than its common header");
        }

        int versionFlags = frame[0] & 0xFF;
        if (((versionFlags >> 5) & 0x07) != PCEP_VERSION) {
            throw new PcepDecodeException("Unsupported PCEP version in message header");
        }
        int messageType = frame[1] & 0xFF;
        if (messageType != MSG_TYPE_PCRPT) {
            throw new PcepDecodeException("Expected a PCRpt message, found message type " + messageType);
        }
        int declaredLength = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        if (declaredLength != frame.length) {
            // A length that disagrees with the buffer is either truncation or a framing attack;
            // trusting either side of the disagreement would read the wrong bytes.
            throw new PcepDecodeException(String.format(
                    "PCRpt declares %d bytes but %d were supplied", declaredLength, frame.length));
        }
        if (declaredLength == COMMON_HEADER_LENGTH) {
            throw new PcepDecodeException("PCRpt message carries no state report");
        }

        List<ReportedLsp> reports = new ArrayList<>();
        boolean endOfSynchronisation = false;
        int offset = COMMON_HEADER_LENGTH;
        Long pendingSrpId = null;

        while (offset < frame.length) {
            if (frame.length - offset < OBJECT_HEADER_LENGTH) {
                throw new PcepDecodeException("PCRpt ends inside an object header");
            }
            int objectClass = frame[offset] & 0xFF;
            int objectLength = ((frame[offset + 2] & 0xFF) << 8) | (frame[offset + 3] & 0xFF);
            if (objectLength < OBJECT_HEADER_LENGTH) {
                throw new PcepDecodeException("PCEP object declares an impossible length " + objectLength);
            }
            if (objectLength % 4 != 0) {
                throw new PcepDecodeException("PCEP object length " + objectLength + " is not 4-byte aligned");
            }
            if (offset + objectLength > frame.length) {
                throw new PcepDecodeException("PCEP object overruns the end of the message");
            }

            switch (objectClass) {
                case OBJECT_CLASS_SRP -> {
                    long srpId = decodeSrpId(frame, offset, objectLength);
                    // RFC 8231 §7.2 reserves SRP-ID-number 0, and §5.4 has a PCC use it for a
                    // report that answers no request. Carrying it through as a real identifier
                    // made every such report look like an answer to operation zero: FRR's pathd
                    // sends SRP-ID 0 on its synchronisation reports, and correlating that against
                    // an outstanding SRP rejected the report as stale, so an install could never
                    // be confirmed by the router that had just installed it.
                    pendingSrpId = srpId == 0 ? null : srpId;
                }
                case OBJECT_CLASS_LSP -> {
                    if (isEndOfSynchronisation(frame, offset, objectLength)) {
                        endOfSynchronisation = true;
                    } else {
                        reports.add(decodeLsp(frame, offset, objectLength, pendingSrpId));
                    }
                    // An SRP applies to the report it precedes and no other.
                    pendingSrpId = null;
                }
                default -> {
                    // A conformant PCC may include objects this implementation has no use for.
                    // Skipping by declared length is safe because the length was bounds-checked.
                }
            }
            offset += objectLength;
        }

        if (reports.isEmpty() && !endOfSynchronisation) {
            throw new PcepDecodeException("PCRpt contained no LSP object");
        }
        return new PcRpt(reports, endOfSynchronisation);
    }

    /**
     * True when this LSP object is RFC 8231 §5.6's end-of-synchronisation marker.
     *
     * <p>Both conditions are required. PLSP-ID 0 with the SYNC flag still set is not a marker, and
     * treating it as one would end synchronisation early -- after which "not reported" starts to
     * mean "absent", and reconciliation would release capacity for LSPs the PCC had not got to
     * yet. The rest of the object is deliberately not inspected: pathd sends a zeroed
     * IPV4-LSP-IDENTIFIERS TLV and an empty ERO alongside it, and neither carries meaning here.
     */
    private static boolean isEndOfSynchronisation(byte[] frame, int offset, int objectLength) {
        if (objectLength < OBJECT_HEADER_LENGTH + 4) {
            throw new PcepDecodeException("LSP object is too short to carry a PLSP-ID");
        }
        long plspAndFlags = readUnsignedInt(frame, offset + OBJECT_HEADER_LENGTH);
        return (plspAndFlags >>> 12) == 0 && ((int) (plspAndFlags & 0xFFF) & LSP_FLAG_SYNC) == 0;
    }

    private static long decodeSrpId(byte[] frame, int offset, int objectLength) {
        // SRP body: 4 reserved/flag bytes then a 32-bit SRP-ID.
        if (objectLength < OBJECT_HEADER_LENGTH + 8) {
            throw new PcepDecodeException("SRP object is too short to carry an SRP-ID");
        }
        return readUnsignedInt(frame, offset + OBJECT_HEADER_LENGTH + 4);
    }

    private static ReportedLsp decodeLsp(byte[] frame, int offset, int objectLength, Long srpId) {
        if (objectLength < OBJECT_HEADER_LENGTH + 4) {
            throw new PcepDecodeException("LSP object is too short to carry a PLSP-ID");
        }
        long plspAndFlags = readUnsignedInt(frame, offset + OBJECT_HEADER_LENGTH);
        long plspId = plspAndFlags >>> 12;
        int flags = (int) (plspAndFlags & 0xFFF);

        String lspName = readSymbolicPathName(frame,
                offset + OBJECT_HEADER_LENGTH + 4, offset + objectLength);

        return new ReportedLsp(
                srpId,
                plspId,
                lspName,
                ReportedLsp.OperationalState.fromWire(
                        (flags & LSP_FLAG_OPERATIONAL_MASK) >> LSP_FLAG_OPERATIONAL_SHIFT),
                (flags & LSP_FLAG_REMOVE) != 0,
                (flags & LSP_FLAG_SYNC) != 0,
                (flags & LSP_FLAG_DELEGATE) != 0,
                (flags & LSP_FLAG_ADMINISTRATIVE) != 0);
    }

    /** Walks the LSP object's TLVs for the symbolic path name, the LSP's durable identity. */
    private static String readSymbolicPathName(byte[] frame, int start, int end) {
        int offset = start;
        while (offset < end) {
            if (end - offset < 4) {
                throw new PcepDecodeException("LSP object ends inside a TLV header");
            }
            int tlvType = ((frame[offset] & 0xFF) << 8) | (frame[offset + 1] & 0xFF);
            int tlvLength = ((frame[offset + 2] & 0xFF) << 8) | (frame[offset + 3] & 0xFF);
            int paddedLength = (tlvLength + 3) & ~3;
            if (offset + 4 + paddedLength > end) {
                throw new PcepDecodeException("TLV declares a length that overruns the LSP object");
            }
            if (tlvType == TLV_SYMBOLIC_PATH_NAME) {
                if (tlvLength == 0) {
                    throw new PcepDecodeException("SYMBOLIC-PATH-NAME TLV is empty");
                }
                if (tlvLength > MAX_LSP_NAME_BYTES) {
                    throw new PcepDecodeException(
                            "SYMBOLIC-PATH-NAME TLV exceeds " + MAX_LSP_NAME_BYTES + " bytes");
                }
                return new String(frame, offset + 4, tlvLength, StandardCharsets.UTF_8);
            }
            offset += 4 + paddedLength;
        }
        // Without a name the report cannot be matched to an intent, which is the whole point of
        // reading it. Reporting that plainly beats returning a nameless LSP nothing can use.
        throw new PcepDecodeException("LSP object carries no SYMBOLIC-PATH-NAME TLV");
    }

    private static long readUnsignedInt(byte[] frame, int offset) {
        return ((long) (frame[offset] & 0xFF) << 24)
                | ((long) (frame[offset + 1] & 0xFF) << 16)
                | ((long) (frame[offset + 2] & 0xFF) << 8)
                | (frame[offset + 3] & 0xFF);
    }
}
