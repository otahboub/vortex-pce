package net.dcn.pce.northbound;

import net.dcn.pce.crp.CRPEngine.DispatchOutcome;
import net.dcn.pce.pcep.PcepSessionServer.SendOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single guarded dispatch point. These pin the two things this class exists
 * to guarantee: the leadership guard is evaluated as the last step before the socket write, and
 * every send path maps to the right {@link DispatchOutcome} and arms the acknowledgement deadline
 * exactly when a frame may have reached the peer.
 *
 * <p>The final test is deliberately honest about the residual the finding names: a pause that lands
 * <em>after</em> the guard passes and before the write cannot be caught in-process. It asserts that
 * residual as a known, documented property rather than pretending it is closed — closing it needs
 * the PCC to validate a leadership term, which stock PCEP does not carry.
 */
class GuardedPcepDispatchTest {

    private final List<String> sent = new ArrayList<>();
    private final List<String> armed = new ArrayList<>();

    private GuardedPcepDispatch dispatch(AtomicBoolean leaseLive, SendOutcome sendResult) {
        return new GuardedPcepDispatch(
                leaseLive::get,
                (owner, frame) -> {
                    sent.add(owner);
                    return sendResult;
                },
                armed::add);
    }

    @Test
    void aLostLeaseFailsClosedWithNoSend() {
        GuardedPcepDispatch d = dispatch(new AtomicBoolean(false), SendOutcome.SENT);
        DispatchOutcome outcome = d.dispatch("pcc-a", new byte[]{1}, "T1");
        assertEquals(DispatchOutcome.NOT_ATTEMPTED, outcome);
        assertTrue(sent.isEmpty(), "no frame may leave the process once the lease is not ours");
        assertTrue(armed.isEmpty(), "nothing was sent, so no acknowledgement is pending");
    }

    @Test
    void aMissingOwnerOrFrameIsNotAttempted() {
        GuardedPcepDispatch d = dispatch(new AtomicBoolean(true), SendOutcome.SENT);
        assertEquals(DispatchOutcome.NOT_ATTEMPTED, d.dispatch(null, new byte[]{1}, "T1"));
        assertEquals(DispatchOutcome.NOT_ATTEMPTED, d.dispatch("pcc-a", null, "T1"));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aSentFrameArmsTheAcknowledgementDeadline() {
        GuardedPcepDispatch d = dispatch(new AtomicBoolean(true), SendOutcome.SENT);
        assertEquals(DispatchOutcome.SENT, d.dispatch("pcc-a", new byte[]{1}, "T1"));
        assertEquals(List.of("pcc-a"), sent);
        assertEquals(List.of("T1"), armed, "a sent frame is outstanding until acknowledged");
    }

    @Test
    void aWriteThatFailedPartWayIsUncertainAndStillArmed() {
        GuardedPcepDispatch d = dispatch(new AtomicBoolean(true), SendOutcome.WRITE_FAILED);
        assertEquals(DispatchOutcome.UNCERTAIN, d.dispatch("pcc-a", new byte[]{1}, "T1"));
        assertEquals(List.of("T1"), armed, "a partial write may have reached the peer");
    }

    @Test
    void noSessionIsNotAttemptedAndNotArmed() {
        GuardedPcepDispatch d = dispatch(new AtomicBoolean(true), SendOutcome.NO_SESSION);
        assertEquals(DispatchOutcome.NOT_ATTEMPTED, d.dispatch("pcc-a", new byte[]{1}, "T1"));
        assertTrue(armed.isEmpty(), "nothing left the process, so nothing is outstanding");
    }

    @Test
    void aPauseThatEndsJustBeforeTheGuardFailsClosed() {
        // The guard is the last statement before the write. Simulate a stop-the-world pause that
        // lasts right up to that guard: the lease lapses during the pause, and because the guard
        // runs after the boundary hook, the send is refused. This is the window the refactor closes
        // -- any pause up to and including the guard evaluation fails closed.
        AtomicBoolean leaseLive = new AtomicBoolean(true);
        GuardedPcepDispatch d = dispatch(leaseLive, SendOutcome.SENT);
        d.setBoundaryHookForTest(() -> leaseLive.set(false));   // lease dies during the "pause"
        assertEquals(DispatchOutcome.NOT_ATTEMPTED, d.dispatch("pcc-a", new byte[]{1}, "T1"));
        assertTrue(sent.isEmpty(), "a pause ending before the guard must still fail closed");
    }

    @Test
    void theResidualPauseAfterTheGuardCannotBeCaughtInProcess() {
        // Honest boundary: if the pause lands strictly AFTER the guard has passed and before the
        // kernel accepts the bytes, no in-process check can retract them. We model that by letting
        // the guard pass, then losing the lease inside the transport itself (i.e. after the guard,
        // at the moment of the write). The frame is still sent -- this is the documented residual
        // that only PCC-side term validation could close, not a defect this class can remove.
        AtomicBoolean leaseLive = new AtomicBoolean(true);
        GuardedPcepDispatch d = new GuardedPcepDispatch(
                leaseLive::get,
                (owner, frame) -> {
                    leaseLive.set(false);   // lease lost at the instant of the write, after the guard
                    sent.add(owner);
                    return SendOutcome.SENT;
                },
                armed::add);
        assertEquals(DispatchOutcome.SENT, d.dispatch("pcc-a", new byte[]{1}, "T1"));
        assertEquals(List.of("pcc-a"), sent,
                "a pause strictly between guard and write is the irreducible H1 residual");
    }
}
