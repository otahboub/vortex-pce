package net.dcn.pce.pcep;

import net.dcn.pce.install.InstallationCoordinator;
import net.dcn.pce.install.InstallationIntent;
import net.dcn.pce.install.InstallationState;
import net.dcn.pce.install.IntentLedger;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refused request should be learned from the peer that refused it.
 *
 * <p>PCErr was read and discarded. When a PCC rejects a PCInitiate it says so, with the SRP-ID of
 * the request — and ignoring that left the intent in {@code INSTALLING} until its acknowledgement
 * deadline expired into {@code UNCERTAIN}, holding capacity for an LSP the router had already
 * declined to create.
 */
class PcepErrorHandlingTest {

    /** A PCErr carrying an SRP object then a PCEP-ERROR object. */
    private static byte[] pcerr(long srpId, int errorType, int errorValue) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (srpId >= 0) {
            body.write(33);            // object class SRP
            body.write(0x10);          // type 1, no flags
            body.write(0);
            body.write(12);            // length: 4 header + 4 flags + 4 srp-id
            body.write(0); body.write(0); body.write(0); body.write(0);          // flags
            body.write((int) (srpId >> 24) & 0xFF);
            body.write((int) (srpId >> 16) & 0xFF);
            body.write((int) (srpId >> 8) & 0xFF);
            body.write((int) srpId & 0xFF);
        }
        body.write(13);                // object class PCEP-ERROR
        body.write(0x10);
        body.write(0);
        body.write(8);                 // 4 header + reserved, flags, type, value
        body.write(0);
        body.write(0);
        body.write(errorType);
        body.write(errorValue);

        byte[] payload = body.toByteArray();
        int total = 4 + payload.length;
        byte[] frame = new byte[total];
        frame[0] = 0x20;               // version 1
        frame[1] = 6;                  // PCErr
        frame[2] = (byte) ((total >> 8) & 0xFF);
        frame[3] = (byte) (total & 0xFF);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        return frame;
    }

    @Test
    void anErrorCarryingAnSrpIdentifiesTheFailedRequest() {
        List<PcepErrorDecoder.PcepError> errors = PcepErrorDecoder.decode(pcerr(42, 19, 1));
        assertEquals(1, errors.size());
        assertEquals(Optional.of(42L), errors.get(0).srpId());
        assertEquals(19, errors.get(0).errorType());
        assertEquals(1, errors.get(0).errorValue());
    }

    @Test
    void asessionLevelErrorCorrelatesToNoRequest() {
        // A malformed OPEN or an unsupported capability concerns the session, not a task. Nothing
        // may be transitioned on the strength of it.
        List<PcepErrorDecoder.PcepError> errors = PcepErrorDecoder.decode(pcerr(-1, 1, 1));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).srpId().isEmpty());
    }

    @Test
    void srpIdZeroIsReservedAndIdentifiesNothing() {
        assertTrue(PcepErrorDecoder.decode(pcerr(0, 19, 1)).get(0).srpId().isEmpty());
    }

    @Test
    void anObjectLengthRunningPastTheFrameIsRefused() {
        // Parsed from an unauthenticated peer unless PCEPS is on. A partial decode could
        // attribute a failure to the wrong task, so decoding stops rather than guessing.
        byte[] frame = pcerr(42, 19, 1);
        frame[7] = (byte) 200;         // SRP object claims to be far longer than the frame
        assertTrue(PcepErrorDecoder.decode(frame).isEmpty());
    }

    @Test
    void arefusedInstallFailsTheIntentAndReleasesItsCapacity() {
        IntentLedger ledger = new IntentLedger();
        ledger.restore(InstallationIntent.planned("T1"));
        ledger.markInstalling("T1", 42L, "speaker:pcc-alpha");
        InstallationCoordinator coordinator = new InstallationCoordinator(ledger, java.time.Clock.systemUTC(),
                java.time.Duration.ofSeconds(30));

        Optional<InstallationIntent> changed = coordinator.applyPeerError("speaker:pcc-alpha", 42L);

        assertTrue(changed.isPresent());
        assertEquals(InstallationState.FAILED, changed.get().getState());
        assertFalse(changed.get().getState().holdsCapacity(),
                "the router said it did not create the LSP, so its capacity is free");
    }

    @Test
    void arefusedRemovalDoesNotReleaseCapacity() {
        // The opposite case, and the dangerous one: a refused removal means the LSP is probably
        // still installed and still carrying traffic.
        IntentLedger ledger = new IntentLedger();
        ledger.restore(InstallationIntent.planned("T1"));
        ledger.markInstalling("T1", 1L, "speaker:pcc-alpha");
        ledger.markInstalled("T1", 1L, 7L);
        ledger.markDeleting("T1", 99L);
        InstallationCoordinator coordinator = new InstallationCoordinator(ledger, java.time.Clock.systemUTC(),
                java.time.Duration.ofSeconds(30));

        assertTrue(coordinator.applyPeerError("speaker:pcc-alpha", 99L).isEmpty(),
                "a refused removal must not release capacity for an LSP that may still exist");
        assertTrue(ledger.find("T1").orElseThrow().getState().holdsCapacity());
    }

    @Test
    void apeerCannotFailAnotherPeersRequest() {
        IntentLedger ledger = new IntentLedger();
        ledger.restore(InstallationIntent.planned("T1"));
        ledger.markInstalling("T1", 42L, "speaker:pcc-alpha");
        InstallationCoordinator coordinator = new InstallationCoordinator(ledger, java.time.Clock.systemUTC(),
                java.time.Duration.ofSeconds(30));

        assertTrue(coordinator.applyPeerError("speaker:pcc-intruder", 42L).isEmpty(),
                "quoting another session's request id must not fail its work");
        assertEquals(InstallationState.INSTALLING, ledger.find("T1").orElseThrow().getState());
    }
}
