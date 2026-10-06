package net.dcn.pce.rib;

import java.util.List;
import java.util.Set;

/**
 * Durable backing for the LRIB and NRIB reservation ledgers.
 *
 * <p>The engine commits a whole transaction or none of it, so the store is only ever handed the
 * net effect of a completed transaction. Keeping that behind an interface is what allows the
 * file-backed write-ahead log to be replaced by a shared transactional backend later without the
 * engine changing: correct multi-replica operation requires admission to serialize in the store
 * rather than in one JVM's monitor, and that substitution is only possible if the engine does not
 * depend on the storage being local.
 *
 * <p>Installation intents travel in the same delta as the reservations they concern. Marking a
 * task failed and releasing its capacity are two facts that must never be separated by a crash,
 * so they are committed together or not at all.
 */
public interface ReservationStore {

    /**
     * Loads persisted reservations into the supplied ledgers.
     *
     * @return {@code true} when durable state existed and was applied
     */
    boolean restore(LRIB lrib, NRIB nrib);

    /**
     * Loads persisted reservations and installation intents together.
     *
     * <p>They are restored in one call because they were committed in one transaction: a store
     * that could return reservations without their intents would let the controller resume
     * holding capacity it no longer knows the purpose of.
     */
    default boolean restore(LRIB lrib, NRIB nrib, net.dcn.pce.install.IntentLedger intents) {
        return restore(lrib, nrib);
    }

    /** Durably records the net effect of one committed transaction. */
    void append(ReservationDelta delta);

    /**
     * Rewrites the durable representation so its size is proportional to live reservations
     * rather than to the number of transactions ever applied.
     */
    void compact(LRIB lrib, NRIB nrib);

    /**
     * Compacts only if this store's own growth policy calls for it.
     *
     * <p>Whether compaction is needed, and what it costs, is a property of the storage backend.
     * A shared transactional store has no equivalent, so the default is to do nothing.
     */
    default void compactIfNeeded(LRIB lrib, NRIB nrib) {
    }

    /**
     * A monotonic token identifying the writer that produced the current durable state.
     *
     * <p>The ADR-0001 fencing concept, lifted onto the contract so both backends share it: for the
     * file WAL it is the writer epoch stamped when single-writer ownership was taken; for a shared
     * transactional backend it will be the leadership term. Every mutation is produced under a
     * token, and admission can reject a write carrying a stale one -- which is how a partitioned
     * former writer is prevented from corrupting state a newer one now owns. A store with no notion
     * of ownership returns {@link #NO_FENCING_TOKEN}.
     */
    default long fencingToken() {
        return NO_FENCING_TOKEN;
    }

    /** Returned by {@link #fencingToken()} for a store that does not fence writers. */
    long NO_FENCING_TOKEN = 0L;

    /** Releases any held resources. */
    default void close() {
    }

    /**
     * Net effect of one committed transaction: reservations that now exist and did not before,
     * and the identifiers of reservations that no longer exist.
     */
    record ReservationDelta(
            List<LRIB.LinkReservation> addedLinkReservations,
            List<NRIB.NodeReservation> addedNodeReservations,
            Set<String> removedReservationIds,
            List<net.dcn.pce.install.InstallationIntent> intentTransitions) {

        public ReservationDelta {
            addedLinkReservations = List.copyOf(addedLinkReservations);
            addedNodeReservations = List.copyOf(addedNodeReservations);
            removedReservationIds = Set.copyOf(removedReservationIds);
            intentTransitions = List.copyOf(intentTransitions);
        }

        /** A delta carrying no intent transitions. */
        public ReservationDelta(
                List<LRIB.LinkReservation> addedLinkReservations,
                List<NRIB.NodeReservation> addedNodeReservations,
                Set<String> removedReservationIds) {
            this(addedLinkReservations, addedNodeReservations, removedReservationIds, List.of());
        }

        public boolean isEmpty() {
            return addedLinkReservations.isEmpty()
                    && addedNodeReservations.isEmpty()
                    && removedReservationIds.isEmpty()
                    && intentTransitions.isEmpty();
        }

        public int recordCount() {
            return addedLinkReservations.size() + addedNodeReservations.size()
                    + removedReservationIds.size() + intentTransitions.size();
        }
    }
}
