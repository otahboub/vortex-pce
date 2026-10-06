package net.dcn.pce.pcep;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decodes PCErr (RFC 5440 message type 6), correlating stateful errors to the request that failed.
 *
 * <p>PCErr was read and discarded. When a PCC refuses a PCInitiate it says so on the wire, with
 * the SRP-ID of the request it rejected — and ignoring that left the intent sitting in
 * {@code INSTALLING} until its acknowledgement deadline expired into {@code UNCERTAIN}, holding
 * capacity for an LSP the router had already told us it would not create. Waiting out a timer for
 * an answer that has arrived is the expensive way to learn something.
 *
 * <p>Only errors carrying an SRP object identify a request. A session-level PCErr — a malformed
 * OPEN, an unsupported capability — concerns the session rather than any one task, and is decoded
 * to an empty correlation so nothing is transitioned on the strength of it.
 */
public final class PcepErrorDecoder {

    private static final int OBJECT_CLASS_SRP = 33;
    private static final int OBJECT_CLASS_PCEP_ERROR = 13;
    private static final int COMMON_HEADER_LENGTH = 4;
    private static final int OBJECT_HEADER_LENGTH = 4;

    private PcepErrorDecoder() {
    }

    /** One decoded error: which request failed, and what the peer said about it. */
    public record PcepError(Optional<Long> srpId, int errorType, int errorValue) {

        /** A human-readable rendering for the operator log. */
        public String describe() {
            return "PCEP error type " + errorType + " value " + errorValue
                    + srpId.map(id -> " for SRP " + id).orElse(" (session level)");
        }
    }

    /**
     * Decodes every error carried in one PCErr frame.
     *
     * <p>Bounds are checked against the declared object lengths rather than trusting them, in the
     * same way {@link PcepReportDecoder} does: this parses bytes from an unauthenticated peer
     * unless PCEPS is configured.
     */
    public static List<PcepError> decode(byte[] frame) {
        List<PcepError> errors = new ArrayList<>();
        if (frame == null || frame.length < COMMON_HEADER_LENGTH) {
            return errors;
        }
        int declared = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        int limit = Math.min(frame.length, Math.max(COMMON_HEADER_LENGTH, declared));

        Long pendingSrpId = null;
        int offset = COMMON_HEADER_LENGTH;
        while (offset + OBJECT_HEADER_LENGTH <= limit) {
            int objectClass = frame[offset] & 0xFF;
            int objectLength = ((frame[offset + 2] & 0xFF) << 8) | (frame[offset + 3] & 0xFF);
            if (objectLength < OBJECT_HEADER_LENGTH || offset + objectLength > limit) {
                // A length that runs past the frame is malformed. Stop rather than guess: a
                // partial decode of an error message could attribute a failure to the wrong task.
                break;
            }

            if (objectClass == OBJECT_CLASS_SRP) {
                pendingSrpId = decodeSrpId(frame, offset, objectLength);
            } else if (objectClass == OBJECT_CLASS_PCEP_ERROR) {
                // PCEP-ERROR body: reserved(1) flags(1) type(1) value(1)
                if (objectLength >= OBJECT_HEADER_LENGTH + 4) {
                    int errorType = frame[offset + OBJECT_HEADER_LENGTH + 2] & 0xFF;
                    int errorValue = frame[offset + OBJECT_HEADER_LENGTH + 3] & 0xFF;
                    errors.add(new PcepError(Optional.ofNullable(pendingSrpId), errorType,
                            errorValue));
                }
            }
            offset += objectLength;
        }
        return errors;
    }

    private static Long decodeSrpId(byte[] frame, int offset, int objectLength) {
        // SRP body: flags(4) srp-id(4)
        if (objectLength < OBJECT_HEADER_LENGTH + 8) {
            return null;
        }
        int base = offset + OBJECT_HEADER_LENGTH + 4;
        long srpId = ((long) (frame[base] & 0xFF) << 24)
                | ((long) (frame[base + 1] & 0xFF) << 16)
                | ((long) (frame[base + 2] & 0xFF) << 8)
                | (frame[base + 3] & 0xFF);
        // SRP-ID 0 is reserved and identifies no request, so it must not correlate to a task.
        return srpId == 0 ? null : srpId;
    }
}
