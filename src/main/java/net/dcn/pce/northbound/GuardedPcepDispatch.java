package net.dcn.pce.northbound;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.pcep.PcepSessionServer.SendOutcome;

import java.util.function.BooleanSupplier;

/**
 * The single point at which a dispatched PCEP frame reaches a PCC. Install and removal both route
 * through here so the leadership guard is <em>defined once</em> and evaluated as the last statement
 * before the socket write — the tightest an in-process check can be against a lost lease.
 *
 * <h2>What this closes, and what it cannot</h2>
 *
 * <p>A dispatch must never leave this process once the lease is no longer ours; otherwise a
 * resumed-but-superseded leader could initiate or remove an LSP a successor already owns. The two
 * call sites used to each carry their own {@code if (!isActiveLeader()) return} before
 * {@code send(...)}, so the guard could drift between them and neither was demonstrably the last
 * thing before the write. Folding both into this one method makes the guard singular and puts it
 * immediately before {@link #transport}, with nothing between.
 *
 * <p>That narrows, but cannot eliminate, the window the finding names. A stop-the-world pause that
 * lands <em>strictly between</em> the guard returning {@code true} and the kernel accepting the
 * bytes can still outlive the lease: the check has already passed and the write proceeds on resume.
 * Database-fenced reservation writes stop any <em>durable</em> consequence, but they cannot retract
 * bytes already handed to the socket. Closing that residual needs the PCC to validate a leadership
 * term (a per-session epoch the peer rejects when stale), which stock PCEP does not carry — see
 * {@code docs/ha_leadership_and_dispatch_fencing.md}. On demotion the listener is closed, so a
 * straggler that resumes after a full heartbeat finds no session to send on.
 *
 * <p>Not thread-safety: the caller (single dispatch loop) serialises calls. This class only
 * guarantees the guard/write ordering and one definition of the guard.
 */
final class GuardedPcepDispatch {

    /** Sends an encoded frame to the session that owns the LSP. */
    @FunctionalInterface
    interface Transport {
        SendOutcome send(String owner, byte[] frame);
    }

    /** Arms the acknowledgement deadline for a task whose frame may have reached the peer. */
    @FunctionalInterface
    interface AckArmer {
        void arm(String taskId);
    }

    private final BooleanSupplier leaseLive;
    private final Transport transport;
    private final AckArmer ackArmer;

    /**
     * Test-only seam: run in the instant between entering {@link #dispatch} and the guard, so a test
     * can inject a pause exactly at the boundary the finding is about. Never set in production.
     */
    private volatile Runnable boundaryHook;

    GuardedPcepDispatch(BooleanSupplier leaseLive, Transport transport, AckArmer ackArmer) {
        this.leaseLive = leaseLive;
        this.transport = transport;
        this.ackArmer = ackArmer;
    }

    /** Installs a pause-at-the-boundary hook for tests; see {@link #boundaryHook}. */
    void setBoundaryHookForTest(Runnable hook) {
        this.boundaryHook = hook;
    }

    /**
     * Guards on leadership and, only if still held, sends {@code frame} to {@code owner}.
     *
     * @return {@code NOT_ATTEMPTED} if nothing left the process (no owner/frame, lease lost, or no
     *         session), {@code SENT} once the frame was written, or {@code UNCERTAIN} if the write
     *         failed part-way and may have reached the peer. The acknowledgement deadline is armed
     *         whenever a frame may have been (partly) delivered.
     */
    CRPEngine.DispatchOutcome dispatch(String owner, byte[] frame, String taskId) {
        if (owner == null || frame == null) {
            return CRPEngine.DispatchOutcome.NOT_ATTEMPTED;
        }
        Runnable hook = boundaryHook;
        if (hook != null) {
            hook.run();
        }
        // The last statement before the write. Nothing may come between this guard and send():
        // this is as late as an in-process check can fail closed on a lost lease.
        if (!leaseLive.getAsBoolean()) {
            return CRPEngine.DispatchOutcome.NOT_ATTEMPTED;
        }
        switch (transport.send(owner, frame)) {
            case NO_SESSION:
                return CRPEngine.DispatchOutcome.NOT_ATTEMPTED;
            case WRITE_FAILED:
                // May have partly reached the peer, so the operation is outstanding until something
                // says otherwise.
                ackArmer.arm(taskId);
                return CRPEngine.DispatchOutcome.UNCERTAIN;
            default:
                ackArmer.arm(taskId);
                return CRPEngine.DispatchOutcome.SENT;
        }
    }
}
