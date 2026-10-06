package net.dcn.pce.crp;

import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.LinkIntermittencyFunction;
import net.dcn.pce.model.Node;
import net.dcn.pce.model.WorkloadTask;
import net.dcn.pce.rib.LRIB;
import net.dcn.pce.rib.NRIB;
import net.dcn.pce.rib.ReservationStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A committed transaction must stay committed even when post-commit log maintenance fails.
 */
class WalCommitDurabilityTest {

    private static BaseTopology twoNodeTopology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e9, 1e9));
        topology.addNode(new Node("B", "B", 1e9, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e9, 0.001)));
        return topology;
    }

    /** Records durable appends; fails only during compaction. */
    private static final class CompactionFailingStore implements ReservationStore {
        private final List<ReservationDelta> durableAppends = new ArrayList<>();
        private boolean failCompaction = true;

        @Override
        public boolean restore(LRIB lrib, NRIB nrib) {
            return false;
        }

        @Override
        public void append(ReservationDelta delta) {
            durableAppends.add(delta);
        }

        @Override
        public void compact(LRIB lrib, NRIB nrib) {
            if (failCompaction) {
                throw new IllegalStateException("simulated compaction failure");
            }
        }

        @Override
        public void compactIfNeeded(LRIB lrib, NRIB nrib) {
            compact(lrib, nrib);
        }
    }

    @Test
    void compactionFailureAfterADurableAppendDoesNotFailTheTransaction() {
        CompactionFailingStore store = new CompactionFailingStore();
        CRPEngine engine = new CRPEngine().withReservationStore(store);

        CRPEngine.PCEComputationResult result = engine.solve(twoNodeTopology(),
                List.of(new WorkloadTask("T1", "A", "B", 0, 1000, 1_000)));

        // Before the fix this threw, the ledgers were rolled back, and the client saw a failure
        // while the appended record stayed durable — so a restart resurrected the reservation.
        assertEquals(1, result.getCommittedFlowCount(),
                "a durable append followed by a failed compaction is still a commit");
        assertFalse(store.durableAppends.isEmpty(), "the delta should have been appended");
        assertTrue(engine.getLRIB().getAllReservations().stream()
                        .anyMatch(reservation -> "T1".equals(reservation.getTaskId())),
                "the in-memory ledger must agree with what was made durable");
    }

    @Test
    void theDurableRecordAndTheReportedOutcomeAgree() {
        CompactionFailingStore store = new CompactionFailingStore();
        CRPEngine engine = new CRPEngine().withReservationStore(store);

        engine.solve(twoNodeTopology(), List.of(new WorkloadTask("T1", "A", "B", 0, 1000, 1_000)));

        // Replay what was made durable and confirm it matches the live ledger, which is exactly
        // the invariant a restart depends on.
        LRIB replayedLrib = new LRIB();
        NRIB replayedNrib = new NRIB();
        for (ReservationStore.ReservationDelta delta : store.durableAppends) {
            delta.addedLinkReservations().forEach(r -> replayedLrib.restoreLinkReservation(
                    r.getReservationId(), r.getTaskId(), r.getLinkId(), r.getSourceNodeId(),
                    r.getDestNodeId(), r.getReservedBwBps(), r.getStartSec(), r.getEndSec()));
            delta.addedNodeReservations().forEach(r -> replayedNrib.restoreNodeReservation(
                    r.getReservationId(), r.getTaskId(), r.getNodeId(), r.getReservedBufferBytes(),
                    r.getStartSec(), r.getEndSec()));
        }

        assertEquals(engine.getLRIB().getAllReservations().size(),
                replayedLrib.getAllReservations().size(),
                "restoring the durable log must reproduce the committed ledger");
    }

    /** Appends fail; used to prove reset restores memory when its durable write fails. */
    private static final class AppendFailingStore implements ReservationStore {
        private boolean failAppend;

        @Override
        public boolean restore(LRIB lrib, NRIB nrib) {
            return false;
        }

        @Override
        public void append(ReservationDelta delta) {
            if (failAppend) {
                throw new IllegalStateException("simulated append failure");
            }
        }

        @Override
        public void compact(LRIB lrib, NRIB nrib) {
        }
    }

    @Test
    void resetRollsBackWhenItsDurableAppendFails() {
        AppendFailingStore store = new AppendFailingStore();
        CRPEngine engine = new CRPEngine().withReservationStore(store);
        engine.solve(twoNodeTopology(), List.of(new WorkloadTask("KEEP", "A", "B", 0, 1000, 1_000)));

        int before = engine.getLRIB().getAllReservations().size();
        assertTrue(before > 0, "the seeding solve should commit reservations");

        store.failAppend = true;
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                engine::resetLedgers);

        // resetLedgers() previously cleared memory before appending, so a failed append left the
        // process empty while the reservations were still durable: the running controller and a
        // restarted one would disagree about committed capacity.
        assertEquals(before, engine.getLRIB().getAllReservations().size(),
                "a reset whose durable write failed must leave the ledgers untouched");
        assertTrue(engine.getLRIB().getAllReservations().stream()
                .allMatch(r -> "KEEP".equals(r.getTaskId())));
    }

    @Test
    void aSuccessfulResetStillClearsBothLedgers() {
        AppendFailingStore store = new AppendFailingStore();
        CRPEngine engine = new CRPEngine().withReservationStore(store);
        engine.solve(twoNodeTopology(), List.of(new WorkloadTask("GONE", "A", "B", 0, 1000, 1_000)));

        engine.resetLedgers();

        assertEquals(0, engine.getLRIB().getAllReservations().size());
        assertEquals(0, engine.getNRIB().getAllReservations().size());
    }

    @Test
    void aFailedTransactionStillCommitsNothing() {
        CompactionFailingStore store = new CompactionFailingStore();
        CRPEngine engine = new CRPEngine()
                .withReservationStore(store)
                .withSolveTimeout(java.time.Duration.ofNanos(1));

        org.junit.jupiter.api.Assertions.assertThrows(SolveTimeoutException.class,
                () -> engine.solve(twoNodeTopology(),
                        List.of(new WorkloadTask("T1", "A", "B", 0, 1000, 1_000))));

        // Tolerating compaction failure must not also start tolerating uncommitted writes.
        assertTrue(store.durableAppends.isEmpty(),
                "an aborted transaction must append nothing durable");
        assertEquals(0, engine.getLRIB().getAllReservations().size());
    }
}
