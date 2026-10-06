package net.dcn.pce.pcep;

import java.util.Optional;

/**
 * One LSP as a PCC reports it, decoded from a PCRpt state report.
 *
 * <p>This is the network's account of reality, as opposed to the controller's intent. Every
 * transition out of {@code INSTALLING} or {@code UNCERTAIN} depends on it, so the fields here are
 * exactly those the installation state machine needs: which operation is being answered, which
 * LSP, and whether it is actually up.
 */
public record ReportedLsp(
        Long srpId,
        long plspId,
        String lspName,
        OperationalState operationalState,
        boolean removed,
        boolean synchronising,
        boolean delegated,
        boolean administrativelyUp) {

    /** RFC 8231 LSP operational state, carried in the three O bits. */
    public enum OperationalState {
        DOWN,
        UP,
        ACTIVE,
        GOING_DOWN,
        GOING_UP,
        /** A value this implementation does not recognise; reported rather than guessed at. */
        UNKNOWN;

        static OperationalState fromWire(int value) {
            return switch (value) {
                case 0 -> DOWN;
                case 1 -> UP;
                case 2 -> ACTIVE;
                case 3 -> GOING_DOWN;
                case 4 -> GOING_UP;
                default -> UNKNOWN;
            };
        }

        /**
         * True when the PCC considers the LSP carrying traffic.
         *
         * <p>{@code GOING_UP} deliberately does not count: an LSP still being established has not
         * confirmed anything, and treating it as installed would let the controller stop waiting
         * for the outcome it actually needs.
         */
        public boolean isOperational() {
            return this == UP || this == ACTIVE;
        }
    }

    /** The operation this report answers, when it carries an SRP object. */
    public Optional<Long> srp() {
        return Optional.ofNullable(srpId);
    }

    /** True when this report says the LSP has been removed rather than installed. */
    public boolean reportsRemoval() {
        return removed;
    }
}
