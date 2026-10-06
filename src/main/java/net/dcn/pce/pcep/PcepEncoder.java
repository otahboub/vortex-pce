package net.dcn.pce.pcep;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes the PCEP messages a PCE sends: OPEN, KEEPALIVE and RFC 8281 PCInitiate.
 *
 * <p>The design's process-architecture decision (§7) puts PCEP in Java, so that the planner, the
 * intent ledger and the session share one transaction boundary rather than an IPC hop in the
 * middle of the correctness-critical path. This is the encoding half of that move; the session and
 * transport follow.
 *
 * <p>Every frame produced here is checked against Wireshark's dissector in CI. That dissector was
 * written from the RFCs with no sight of this code, so it is an independent referee on the bytes
 * rather than this project agreeing with itself.
 */
public final class PcepEncoder {

    private static final int PCEP_VERSION = 1;
    private static final int COMMON_HEADER_LENGTH = 4;

    private static final int MSG_TYPE_OPEN = 1;
    private static final int MSG_TYPE_KEEPALIVE = 2;
    private static final int MSG_TYPE_PCUPD = 11;
    private static final int MSG_TYPE_PCINITIATE = 12;
    private static final int MSG_TYPE_PCERR = 6;

    private static final int OBJECT_CLASS_OPEN = 1;
    private static final int OBJECT_CLASS_ENDPOINTS = 4;
    private static final int OBJECT_CLASS_BANDWIDTH = 5;
    private static final int OBJECT_CLASS_ERO = 7;
    private static final int OBJECT_CLASS_LSP = 32;
    private static final int OBJECT_CLASS_SRP = 33;
    private static final int OBJECT_CLASS_PCEP_ERROR = 13;
    private static final int OBJECT_CLASS_VENDOR_INFORMATION = 34;   // RFC 7470
    // FRR pathd (and Cisco SR-PCE) key a PCE-initiated SR policy by (colour, endpoint) and read the
    // colour from a Cisco vendor object; without it pathd refuses the LSP as an unacceptable
    // instantiation parameter. These are the values its pceplib matches (enterprise 9 = Cisco).
    private static final int ENTERPRISE_NUMBER_CISCO = 9;
    private static final int ENTERPRISE_COLOR_CISCO = 65540;

    private static final int TLV_STATEFUL_PCE_CAPABILITY = 16;
    private static final int TLV_SYMBOLIC_PATH_NAME = 17;
    private static final int TLV_SPEAKER_ENTITY_ID = 24;

    private static final int STATEFUL_CAPABILITY_UPDATE = 0x001;
    private static final int STATEFUL_CAPABILITY_SYNC = 0x002;
    private static final int STATEFUL_CAPABILITY_INSTANTIATION = 0x004;
    private static final int STATEFUL_CAPABILITY_FLAGS =
            STATEFUL_CAPABILITY_UPDATE | STATEFUL_CAPABILITY_SYNC | STATEFUL_CAPABILITY_INSTANTIATION;

    /** RFC 8281 Create bit, at wire position 0x080 within the 12-bit LSP flag field. */
    private static final int LSP_FLAG_CREATE = 0x080;
    private static final int LSP_FLAG_ADMINISTRATIVE = 0x008;
    private static final int LSP_FLAG_DELEGATE = 0x001;

    /** RFC 8281 SRP Remove bit: bit 31 of the flag word, so the least significant bit. */
    private static final int SRP_FLAG_REMOVE = 0x00000001;

    private static final int ERO_SUBOBJECT_IPV4_PREFIX = 1;

    /**
     * RFC 8664 SR-ERO subobject, and the TLV that tells a PCC to read it as one.
     *
     * <p>These byte layouts are not guesses. They were read off a PCRpt that FRRouting's pathd
     * sent this controller during an interop run: subobject type 36, length 8, NAI absent, flags
     * M|F, and a SID holding the label in its top twenty bits; and an SRP carrying
     * PATH-SETUP-TYPE with PST 1. Encoding to what the peer itself emits is the closest thing to
     * a specification a wire format has.
     */
    private static final int ERO_SUBOBJECT_SR = 36;
    private static final int SR_FLAG_MPLS_LABEL = 0x008;
    private static final int SR_FLAG_NAI_ABSENT = 0x001;
    private static final int TLV_PATH_SETUP_TYPE = 28;
    private static final int TLV_PATH_SETUP_TYPE_CAPABILITY = 34;
    private static final int PATH_SETUP_TYPE_RSVP = 0;
    private static final int PATH_SETUP_TYPE_SR = 1;
    /** A label occupies the top twenty bits of the 32-bit SID; the rest is TC, S and TTL. */
    private static final int SR_LABEL_SHIFT = 12;

    private PcepEncoder() {
    }

    /**
     * Encodes a segment-routed PCInitiate: an SR-ERO of labels rather than IPv4 prefixes.
     *
     * <p>Needed because an SR-TE PCC rejects the RSVP-TE form outright. FRR's pathd answered our
     * prefix-based ERO with a PCEP error while its session stayed up, which is the protocol
     * working exactly as designed: it advertised SR-TE as its only path setup type, and we sent it
     * a path it had told us it does not implement.
     *
     * <p>The labels come from the topology because a PCE cannot invent them. They are allocated by
     * the network, and a controller that guessed would install a path onto whatever those labels
     * happen to mean.
     *
     * @param labels one label per hop, in path order
     */
    public static byte[] pcInitiateSegmentRouted(
            long srpId, String lspName, String ingressIpv4, String egressIpv4,
            double rateBps, List<Integer> labels) {
        if (srpId < 1 || srpId > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("srpId must be a non-zero unsigned 32-bit value");
        }
        if (lspName == null || lspName.isBlank()) {
            throw new IllegalArgumentException("lspName is required");
        }
        if (!Double.isFinite(rateBps) || rateBps <= 0) {
            throw new IllegalArgumentException("rate must be finite and positive");
        }
        if (labels == null || labels.isEmpty()) {
            throw new IllegalArgumentException("a segment-routed path needs at least one label");
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();

        // The SRP says how to read the ERO. Without PATH-SETUP-TYPE a PCC assumes RSVP-TE and
        // would misread the subobjects that follow.
        ByteArrayOutputStream srp = new ByteArrayOutputStream();
        writeAll(srp, intToBytes(0));
        writeAll(srp, intToBytes((int) srpId));
        ByteArrayOutputStream srpTlvs = new ByteArrayOutputStream();
        writeTlv(srpTlvs, TLV_PATH_SETUP_TYPE, intToBytes(PATH_SETUP_TYPE_SR));
        writeAll(srp, srpTlvs.toByteArray());
        writeAll(payload, object(OBJECT_CLASS_SRP, 1, srp.toByteArray()));

        ByteArrayOutputStream lsp = new ByteArrayOutputStream();
        writeAll(lsp, intToBytes(LSP_FLAG_CREATE | LSP_FLAG_ADMINISTRATIVE));
        ByteArrayOutputStream lspTlvs = new ByteArrayOutputStream();
        writeTlv(lspTlvs, TLV_SYMBOLIC_PATH_NAME, lspName.getBytes(StandardCharsets.US_ASCII));
        writeAll(lsp, lspTlvs.toByteArray());
        writeAll(payload, object(OBJECT_CLASS_LSP, 1, lsp.toByteArray()));

        // A Cisco-format vendor colour, which FRR pathd requires to place the LSP into an SR
        // policy. The colour is derived from the LSP name so it is stable across a re-initiate and
        // distinct per LSP; the P flag is left clear, so a PCC that does not use it ignores it.
        ByteArrayOutputStream vendor = new ByteArrayOutputStream();
        writeAll(vendor, intToBytes(ENTERPRISE_NUMBER_CISCO));
        writeAll(vendor, intToBytes(ENTERPRISE_COLOR_CISCO));
        writeAll(vendor, intToBytes(colourFor(lspName)));
        writeAll(payload, object(OBJECT_CLASS_VENDOR_INFORMATION, 1, vendor.toByteArray()));

        ByteArrayOutputStream endpoints = new ByteArrayOutputStream();
        writeAll(endpoints, ipv4ToBytes(ingressIpv4));
        writeAll(endpoints, ipv4ToBytes(egressIpv4));
        writeAll(payload, object(OBJECT_CLASS_ENDPOINTS, 1, endpoints.toByteArray()));

        ByteArrayOutputStream ero = new ByteArrayOutputStream();
        for (Integer label : labels) {
            if (label == null || label < 16 || label > 0xFFFFF) {
                throw new IllegalArgumentException("label outside the usable range: " + label);
            }
            ero.write(ERO_SUBOBJECT_SR);
            ero.write(8);
            // NAI type in the high nibble (absent), then the flags.
            writeAll(ero, shortToBytes(SR_FLAG_MPLS_LABEL | SR_FLAG_NAI_ABSENT));
            writeAll(ero, intToBytes(label << SR_LABEL_SHIFT));
        }
        writeAll(payload, object(OBJECT_CLASS_ERO, 1, ero.toByteArray()));

        ByteArrayOutputStream bandwidth = new ByteArrayOutputStream();
        writeAll(bandwidth, intToBytes(Float.floatToIntBits((float) rateBps)));
        writeAll(payload, object(OBJECT_CLASS_BANDWIDTH, 1, bandwidth.toByteArray()));

        return message(MSG_TYPE_PCINITIATE, payload.toByteArray());
    }

    /**
     * Encodes a PCInitiate requesting removal of one previously initiated LSP.
     *
     * <p>RFC 8281 §5.4: removal is the R flag in the SRP object plus the PLSP-ID the PCC assigned,
     * which is why this takes a PLSP-ID and the creation encoder does not. A removal for PLSP-ID
     * zero is refused: zero means "the PCC chooses", and sending it would ask a router to delete
     * an LSP nobody has identified.
     *
     * <p>No END-POINTS and no ERO: the LSP being removed is already installed, so re-describing
     * its path would invite a PCC to compare the two and reject the request over a mismatch that
     * does not matter.
     */
    public static byte[] pcInitiateRemoval(long srpId, long plspId, String lspName) {
        if (srpId < 1 || srpId > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("srpId must be a non-zero unsigned 32-bit value");
        }
        if (plspId < 1 || plspId > 0xFFFFF) {
            throw new IllegalArgumentException(
                    "plspId must be a non-zero 20-bit value assigned by the PCC");
        }
        if (lspName == null || lspName.isBlank()) {
            throw new IllegalArgumentException("lspName is required");
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();

        ByteArrayOutputStream srp = new ByteArrayOutputStream();
        writeAll(srp, intToBytes(SRP_FLAG_REMOVE));
        writeAll(srp, intToBytes((int) srpId));
        // This controller creates SR-TE LSPs, so the operation that removes one must identify the
        // same path setup type. FRR pathd deliberately refuses an otherwise valid removal without
        // this TLV because the PLSP-ID alone does not identify the instantiation mechanism.
        writeTlv(srp, TLV_PATH_SETUP_TYPE, intToBytes(PATH_SETUP_TYPE_SR));
        writeAll(payload, object(OBJECT_CLASS_SRP, 1, srp.toByteArray()));

        // PLSP-ID occupies the top 20 bits of the word the flags share.
        ByteArrayOutputStream lsp = new ByteArrayOutputStream();
        // RFC 8231 D flag. FRR resolves the PLSP-ID to the right internal path first, but its
        // removal path also requires the incoming LSP object to state that the path is delegated.
        // Omitting D makes pathd reject its own delegated PCE-initiated LSP with PCErr 19/1.
        writeAll(lsp, intToBytes((int) ((plspId << 12)
                | LSP_FLAG_ADMINISTRATIVE | LSP_FLAG_DELEGATE)));
        // Carried for diagnosis rather than identification: a capture of a removal should say
        // which LSP it removed without needing the session history that assigned the PLSP-ID.
        ByteArrayOutputStream lspTlvs = new ByteArrayOutputStream();
        writeTlv(lspTlvs, TLV_SYMBOLIC_PATH_NAME, lspName.getBytes(StandardCharsets.US_ASCII));
        writeAll(lsp, lspTlvs.toByteArray());
        writeAll(payload, object(OBJECT_CLASS_LSP, 1, lsp.toByteArray()));

        return message(MSG_TYPE_PCINITIATE, payload.toByteArray());
    }

    /**
     * Encodes a PCUpd (RFC 8231 section 6.2) changing an installed LSP's committed rate.
     *
     * <p>Without this the only way to change an LSP's rate is to remove it and create a new one,
     * which takes the path down in between — for a flow the controller is actively carrying, that
     * is an outage to accomplish a rate change.
     *
     * <p>The message differs from a PCInitiate in three ways that matter, all of them things a
     * PCC will reject if got wrong. The LSP object carries the PLSP-ID the PCC assigned rather
     * than zero, because this identifies an LSP that already exists. The create flag is absent:
     * setting it would ask for a second LSP rather than a change to this one. And there is no
     * END-POINTS object — RFC 8231's update grammar is {@code <SRP><LSP><path>}, and END-POINTS
     * belongs to PCInitiate and PCReq.
     */
    public static byte[] pcUpdate(
            long srpId, long plspId, String lspName, double rateBps, List<String> eroHops) {
        if (srpId < 1 || srpId > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("srpId must be a non-zero unsigned 32-bit value");
        }
        if (plspId < 1 || plspId > 0xFFFFF) {
            throw new IllegalArgumentException(
                    "plspId must be a non-zero 20-bit value assigned by the PCC");
        }
        if (!Double.isFinite(rateBps) || rateBps <= 0) {
            throw new IllegalArgumentException("rate must be finite and positive");
        }
        if (eroHops == null || eroHops.isEmpty()) {
            // An update with no path is not a smaller update, it is a different message: the
            // grammar requires an intended path, and a PCC given none has nothing to install.
            throw new IllegalArgumentException("at least one ERO hop is required");
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();

        ByteArrayOutputStream srp = new ByteArrayOutputStream();
        writeAll(srp, intToBytes(0));
        writeAll(srp, intToBytes((int) srpId));
        writeAll(payload, object(OBJECT_CLASS_SRP, 1, srp.toByteArray()));

        ByteArrayOutputStream lsp = new ByteArrayOutputStream();
        writeAll(lsp, intToBytes((int) ((plspId << 12) | LSP_FLAG_ADMINISTRATIVE)));
        if (lspName != null && !lspName.isBlank()) {
            // Diagnostic only, as in a removal: a capture should say which LSP was updated
            // without needing the session history that assigned the PLSP-ID.
            ByteArrayOutputStream lspTlvs = new ByteArrayOutputStream();
            writeTlv(lspTlvs, TLV_SYMBOLIC_PATH_NAME, lspName.getBytes(StandardCharsets.US_ASCII));
            writeAll(lsp, lspTlvs.toByteArray());
        }
        writeAll(payload, object(OBJECT_CLASS_LSP, 1, lsp.toByteArray()));

        ByteArrayOutputStream ero = new ByteArrayOutputStream();
        for (String hop : eroHops) {
            ero.write(ERO_SUBOBJECT_IPV4_PREFIX);
            ero.write(8);
            writeAll(ero, ipv4ToBytes(hop));
            ero.write(32);
            ero.write(0);
        }
        writeAll(payload, object(OBJECT_CLASS_ERO, 1, ero.toByteArray()));

        ByteArrayOutputStream bandwidth = new ByteArrayOutputStream();
        writeAll(bandwidth, intToBytes(Float.floatToIntBits((float) rateBps)));
        writeAll(payload, object(OBJECT_CLASS_BANDWIDTH, 1, bandwidth.toByteArray()));

        return message(MSG_TYPE_PCUPD, payload.toByteArray());
    }

    /** Encodes an OPEN advertising RFC 8281 LSP instantiation, optionally naming this speaker. */
    public static byte[] open(int keepaliveSec, int deadTimerSec, int sessionId, String speakerEntityId) {
        if (keepaliveSec < 1 || keepaliveSec > 0xFF) {
            throw new IllegalArgumentException("keepalive must be in [1, 255]");
        }
        if (deadTimerSec <= keepaliveSec || deadTimerSec > 0xFF) {
            throw new IllegalArgumentException("dead timer must exceed keepalive and be at most 255");
        }
        if (sessionId < 0 || sessionId > 0xFF) {
            throw new IllegalArgumentException("session id must be in [0, 255]");
        }

        ByteArrayOutputStream tlvs = new ByteArrayOutputStream();
        writeTlv(tlvs, TLV_STATEFUL_PCE_CAPABILITY, intToBytes(STATEFUL_CAPABILITY_FLAGS));
        // RFC 8408/8664: a speaker that will instantiate SR-TE LSPs MUST advertise the path setup
        // types it supports here, or a PCC rejects an SR PCInitiate as an unacceptable instantiation
        // parameter (Error-Type 24/1) because SR was never negotiated. FRR pathd does exactly that.
        // Both RSVP-TE (PST 0, the prefix-ERO path) and SR (PST 1, the label-ERO path) are listed
        // because this encoder emits both depending on whether the topology carries labels.
        writeTlv(tlvs, TLV_PATH_SETUP_TYPE_CAPABILITY,
                new byte[] {0, 0, 0, 2, (byte) PATH_SETUP_TYPE_RSVP, (byte) PATH_SETUP_TYPE_SR});
        if (speakerEntityId != null && !speakerEntityId.isBlank()) {
            writeTlv(tlvs, TLV_SPEAKER_ENTITY_ID, speakerEntityId.getBytes(StandardCharsets.UTF_8));
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write((PCEP_VERSION & 0x07) << 5);
        body.write(keepaliveSec);
        body.write(deadTimerSec);
        body.write(sessionId);
        writeAll(body, tlvs.toByteArray());

        return message(MSG_TYPE_OPEN, object(OBJECT_CLASS_OPEN, 1, body.toByteArray()));
    }

    /** Encodes a KEEPALIVE, which carries no objects. */
    public static byte[] keepalive() {
        return message(MSG_TYPE_KEEPALIVE, new byte[0]);
    }

    /**
     * Encodes a PCErr carrying a single PCEP-ERROR object (RFC 5440 section 7.15).
     *
     * <p>The receiver of a malformed or out-of-sequence message MUST answer with a PCErr before
     * the session is torn down; sending nothing and closing the TCP connection, which this
     * implementation did previously, is a conformance gap a peer cannot distinguish from a crash.
     * Error-Type and Error-value are the 8-bit fields IANA manages; callers pass the pair the RFC
     * assigns to the condition they detected.
     */
    public static byte[] pcerr(int errorType, int errorValue) {
        if (errorType < 0 || errorType > 0xFF || errorValue < 0 || errorValue > 0xFF) {
            throw new IllegalArgumentException("PCEP error type and value are 8-bit fields");
        }
        // PCEP-ERROR object body: Reserved(8) Flags(8) Error-Type(8) Error-value(8).
        byte[] body = new byte[] {0, 0, (byte) errorType, (byte) errorValue};
        return message(MSG_TYPE_PCERR, object(OBJECT_CLASS_PCEP_ERROR, 1, body));
    }

    /**
     * Encodes a PCInitiate requesting creation of one LSP.
     *
     * <p>PLSP-ID is zero: the PCE asks for creation and the PCC assigns the real identifier, which
     * arrives in its report. Asserting one here would invent an identity the network never agreed
     * to.
     */
    public static byte[] pcInitiate(
            long srpId, String lspName, String ingressIpv4, String egressIpv4,
            double rateBps, List<String> eroHops) {
        if (srpId < 1 || srpId > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("srpId must be a non-zero unsigned 32-bit value");
        }
        if (lspName == null || lspName.isBlank()) {
            throw new IllegalArgumentException("lspName is required");
        }
        if (!Double.isFinite(rateBps) || rateBps <= 0) {
            throw new IllegalArgumentException("rate must be finite and positive");
        }
        List<String> hops = (eroHops == null || eroHops.isEmpty())
                ? List.of(ingressIpv4, egressIpv4) : eroHops;
        if (hops.isEmpty()) {
            throw new IllegalArgumentException("at least one ERO hop is required");
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();

        // SRP: four flag bytes then the request identifier this operation will be answered by.
        ByteArrayOutputStream srp = new ByteArrayOutputStream();
        writeAll(srp, intToBytes(0));
        writeAll(srp, intToBytes((int) srpId));
        writeAll(payload, object(OBJECT_CLASS_SRP, 1, srp.toByteArray()));

        ByteArrayOutputStream lsp = new ByteArrayOutputStream();
        writeAll(lsp, intToBytes(LSP_FLAG_CREATE | LSP_FLAG_ADMINISTRATIVE));
        ByteArrayOutputStream lspTlvs = new ByteArrayOutputStream();
        writeTlv(lspTlvs, TLV_SYMBOLIC_PATH_NAME, lspName.getBytes(StandardCharsets.US_ASCII));
        writeAll(lsp, lspTlvs.toByteArray());
        writeAll(payload, object(OBJECT_CLASS_LSP, 1, lsp.toByteArray()));

        ByteArrayOutputStream endpoints = new ByteArrayOutputStream();
        writeAll(endpoints, ipv4ToBytes(ingressIpv4));
        writeAll(endpoints, ipv4ToBytes(egressIpv4));
        writeAll(payload, object(OBJECT_CLASS_ENDPOINTS, 1, endpoints.toByteArray()));

        ByteArrayOutputStream ero = new ByteArrayOutputStream();
        for (String hop : hops) {
            // IPv4 prefix subobject: type with the L bit clear, length 8, address, prefix 32.
            ero.write(ERO_SUBOBJECT_IPV4_PREFIX);
            ero.write(8);
            writeAll(ero, ipv4ToBytes(hop));
            ero.write(32);
            ero.write(0);
        }
        writeAll(payload, object(OBJECT_CLASS_ERO, 1, ero.toByteArray()));

        ByteArrayOutputStream bandwidth = new ByteArrayOutputStream();
        writeAll(bandwidth, intToBytes(Float.floatToIntBits((float) rateBps)));
        writeAll(payload, object(OBJECT_CLASS_BANDWIDTH, 1, bandwidth.toByteArray()));

        return message(MSG_TYPE_PCINITIATE, payload.toByteArray());
    }

    private static byte[] message(int messageType, byte[] payload) {
        int length = COMMON_HEADER_LENGTH + payload.length;
        if (length > 0xFFFF) {
            throw new IllegalArgumentException("PCEP message exceeds the 16-bit length field");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((PCEP_VERSION & 0x07) << 5);
        out.write(messageType);
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        writeAll(out, payload);
        return out.toByteArray();
    }

    private static byte[] object(int objectClass, int objectType, byte[] body) {
        int length = 4 + body.length;
        if (length % 4 != 0) {
            throw new IllegalStateException("PCEP object body must be 4-byte aligned");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(objectClass);
        out.write((objectType & 0x0F) << 4);
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        writeAll(out, body);
        return out.toByteArray();
    }

    private static void writeTlv(ByteArrayOutputStream out, int type, byte[] value) {
        out.write((type >> 8) & 0xFF);
        out.write(type & 0xFF);
        out.write((value.length >> 8) & 0xFF);
        out.write(value.length & 0xFF);
        writeAll(out, value);
        // TLVs are padded to a 4-byte boundary; the padding is not counted in the length.
        for (int i = value.length; i % 4 != 0; i++) {
            out.write(0);
        }
    }

    /** A stable, non-zero colour for an LSP name: distinct per name, identical across re-initiates. */
    private static int colourFor(String lspName) {
        return (lspName.hashCode() & 0x7FFFFFFF) | 1;
    }

    private static byte[] ipv4ToBytes(String address) {
        String[] octets = address == null ? new String[0] : address.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("Not an IPv4 address: " + address);
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int value = Integer.parseInt(octets[i]);
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("Not an IPv4 address: " + address);
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static byte[] shortToBytes(int value) {
        return new byte[]{(byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static void writeAll(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }
}
