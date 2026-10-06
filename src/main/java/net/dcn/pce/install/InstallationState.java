package net.dcn.pce.install;

/**
 * Lifecycle of one task's LSP, from planned to installed or gone.
 *
 * <p>The controller's ledger and the network's reality are different facts. A reservation says
 * this controller booked capacity; only a PCC report says a router is forwarding the LSP. These
 * states track the second fact so the two can be reconciled instead of assumed equal.
 *
 * <p>See the README section "Southbound listener".
 */
public enum InstallationState {

    /** Reservations are committed. Nothing has been sent to a PCC. */
    PLANNED,

    /** A PCInitiate has been sent and its acknowledgement is outstanding. */
    INSTALLING,

    /** A PCC has reported the LSP operational. */
    INSTALLED,

    /**
     * The controller does not know whether the LSP exists.
     *
     * <p>Reached when an acknowledgement deadline passes or a session drops mid-operation. This
     * state exists because a missing acknowledgement is <em>not</em> evidence of
     * non-installation: the LSP may be installed and carrying traffic. Releasing its capacity
     * here would hand that bandwidth to another flow while a router is still using it.
     */
    UNCERTAIN,

    /** Definitively not installed. Terminal; capacity is released. */
    FAILED,

    /**
     * A rate change has been sent for an installed LSP and its confirmation is outstanding.
     *
     * <p>Distinct from {@code INSTALLING} because the LSP already exists and is carrying traffic:
     * a failure here leaves a working path at its previous rate, where a failure while installing
     * leaves no path at all. Distinct from {@code INSTALLED} because the committed rate is not
     * yet known to be the one the router is enforcing.
     *
     * <p>Capacity is held at the greater of the old and new rates while this is outstanding. The
     * router may still be pacing at the old rate or may already have applied the new one, and
     * reserving the lower of the two would hand bandwidth to another flow that this one might
     * still be using.
     */
    UPDATING,

    /** A removal has been requested and its confirmation is outstanding. */
    DELETING,

    /** Confirmed removed. Terminal; capacity is released. */
    DELETED;

    /** True when no further transition is possible and capacity has been released. */
    public boolean isTerminal() {
        return this == FAILED || this == DELETED;
    }

    /**
     * True while this state must continue to hold its reservations.
     *
     * <p>Capacity is held from {@link #PLANNED} onward. Holding only from {@link #INSTALLED}
     * would let a second solve plan against residual capacity that is about to disappear, and
     * admit both. Capacity is released only on a terminal state, and never on uncertainty.
     */
    public boolean holdsCapacity() {
        return !isTerminal();
    }
}
