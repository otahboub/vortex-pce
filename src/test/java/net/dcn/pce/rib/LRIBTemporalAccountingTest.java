package net.dcn.pce.rib;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.crp.DuplicateTaskException;
import net.dcn.pce.crp.policy.PathSelectionPolicy;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.ContactRegime;
import net.dcn.pce.model.ContactPlan;
import net.dcn.pce.model.ContactWindow;
import net.dcn.pce.model.Link;
import net.dcn.pce.model.LinkIntermittencyFunction;
import net.dcn.pce.model.Node;
import net.dcn.pce.model.WorkloadTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LRIBTemporalAccountingTest {

    @Test
    void nonOverlappingReservationsAreNotSummedAsConcurrentLoad() {
        LRIB lrib = new LRIB();
        lrib.reserveLinkCap("T1", "L", "A", "B", 40, 0, 5);
        lrib.reserveLinkCap("T2", "L", "A", "B", 40, 5, 10);

        assertEquals(60, lrib.getAvailableCap("L", 100, 0, 10), 1e-9);
    }

    @Test
    void overlappingReservationsUsePeakSimultaneousLoad() {
        LRIB lrib = new LRIB();
        lrib.reserveLinkCap("T1", "L", "A", "B", 40, 0, 10);
        lrib.reserveLinkCap("T2", "L", "A", "B", 30, 5, 15);

        assertEquals(30, lrib.getAvailableCap("L", 100, 0, 15), 1e-9);
        assertEquals(70, lrib.getMaximumAvailableCap("L", 100, 0, 15), 1e-9);
    }

    @Test
    void earliestSlotStartsWhenEnoughCapacityActuallyReturns() {
        LRIB lrib = new LRIB();
        lrib.reserveLinkCap("T1", "L", "A", "B", 80, 0, 5);

        assertEquals(5, lrib.findEarliestFeasibleStart("L", 100, 50, 0, 10, 2)
                .orElseThrow(), 1e-9);
    }

    @Test
    void deterministicTransmissionFragmentsAcrossActiveContacts() {
        LRIB lrib = new LRIB();
        Link link = new Link("L", "A", "B",
                new LinkIntermittencyFunction(100, 0, 2, 1, 7));

        List<LRIB.TransmissionSlot> slots = lrib.findEarliestFeasibleTransmission(
                link, ContactRegime.R_DET, 100, 0, 25, 6);

        assertEquals(List.of(
                new LRIB.TransmissionSlot(1, 3),
                new LRIB.TransmissionSlot(11, 13),
                new LRIB.TransmissionSlot(21, 23)), slots);
    }

    @Test
    void deterministicTransmissionUsesFiniteExplicitContacts() {
        LRIB lrib = new LRIB();
        Link link = new Link("L", "A", "B",
                LinkIntermittencyFunction.persistentLink(100, 0),
                new ContactPlan(List.of(new ContactWindow(1, 3), new ContactWindow(10, 12))));

        List<LRIB.TransmissionSlot> slots = lrib.findEarliestFeasibleTransmission(
                link, ContactRegime.R_DET, 100, 0, 20, 3);

        assertEquals(List.of(
                new LRIB.TransmissionSlot(1, 3),
                new LRIB.TransmissionSlot(10, 11)), slots);
        assertTrue(lrib.findEarliestFeasibleTransmission(
                link, ContactRegime.R_DET, 100, 12, 30, 1).isEmpty());
    }

    @Test
    void reservationConflictDelaysWorkWithinTheActiveWindow() {
        LRIB lrib = new LRIB();
        Link link = new Link("L", "A", "B",
                new LinkIntermittencyFunction(100, 0, 5, 0, 5));
        lrib.reserveLinkCap("BLOCK", "L", "A", "B", 50, 0, 2);

        List<LRIB.TransmissionSlot> slots = lrib.findEarliestFeasibleTransmission(
                link, ContactRegime.R_DET, 60, 0, 15, 3);

        assertEquals(List.of(new LRIB.TransmissionSlot(2, 5)), slots);
    }

    @Test
    void deadlineMatchedTasksShareResidualCapacityAtTheSameRate() {
        BaseTopology topology = oneLinkTopology();
        CRPEngine engine = new CRPEngine().withPersistentState(true);
        WorkloadTask first = new WorkloadTask("T1", "A", "B", 0, 10, 100);
        WorkloadTask second = new WorkloadTask("T2", "A", "B", 0, 10, 100);

        CRPEngine.CommittedFlowSchedule firstSchedule = engine.solve(topology, List.of(first))
                .getCommittedSchedules().get(0);
        CRPEngine.CommittedFlowSchedule secondSchedule = engine.solve(topology, List.of(second))
                .getCommittedSchedules().get(0);

        assertEquals(firstSchedule.getCommittedRateBps(), secondSchedule.getCommittedRateBps(), 1e-9);
        assertEquals(firstSchedule.getStartSec(), secondSchedule.getStartSec(), 1e-9);
    }

    @Test
    void duplicateTaskIsRejectedWithoutChangingTheLedger() {
        BaseTopology topology = oneLinkTopology();
        CRPEngine engine = new CRPEngine().withPersistentState(true);
        WorkloadTask task = new WorkloadTask("T1", "A", "B", 0, 10, 100);
        engine.solve(topology, List.of(task));
        int reservationCount = engine.getLRIB().getAllReservations().size();

        assertThrows(DuplicateTaskException.class, () -> engine.solve(topology, List.of(task)));
        assertEquals(reservationCount, engine.getLRIB().getAllReservations().size());
        assertTrue(engine.getNRIB().getAllReservations().isEmpty());
    }

    @Test
    void failureAfterAPartialCommitRollsBackTheWholeRequest() {
        BaseTopology topology = oneLinkTopology();
        AtomicInteger routeSelections = new AtomicInteger();
        CRPEngine engine = new CRPEngine()
                .withPersistentState(true)
                .withFRoute((task, candidates, ignoredTopology, lrib, nrib) -> {
                    if (routeSelections.incrementAndGet() == 2) {
                        throw new IllegalStateException("injected route-selection failure");
                    }
                    return candidates.get(0);
                });
        List<WorkloadTask> tasks = List.of(
                new WorkloadTask("T1", "A", "B", 0, 10, 100),
                new WorkloadTask("T2", "A", "B", 0, 10, 100));

        assertThrows(IllegalStateException.class, () -> engine.solve(topology, tasks));
        assertTrue(engine.getLRIB().getAllReservations().isEmpty());
        assertTrue(engine.getNRIB().getAllReservations().isEmpty());
    }

    @Test
    void restoredStateMustMatchTopologyAndEffectiveCapacity() {
        BaseTopology topology = oneLinkTopology();
        CRPEngine engine = new CRPEngine().withPersistentState(true);
        engine.getLRIB().reserveLinkCap("T1", "L", "A", "B", 1_001, 0, 1);

        assertThrows(IllegalStateException.class, () -> engine.validateLedgers(topology));
    }

    @Test
    void reservationIdsRetainTheFullUuidEntropy() {
        LRIB lrib = new LRIB();
        NRIB nrib = new NRIB();

        assertEquals(36, lrib.reserveLinkCap("T1", "L", "A", "B", 10, 0, 1)
                .getReservationId().length());
        assertEquals(36, nrib.reserveNodeBuff("T1", "A", 10, 0, 1)
                .getReservationId().length());
    }

    @Test
    void completedReservationsExpireBeforeANewLogicalTime() {
        BaseTopology topology = oneLinkTopology();
        CRPEngine engine = new CRPEngine().withPersistentState(true);
        WorkloadTask first = new WorkloadTask("REUSABLE", "A", "B", 0, 1, 100);
        WorkloadTask later = new WorkloadTask("REUSABLE", "A", "B", 2, 3, 100);

        assertEquals(1, engine.solve(topology, List.of(first)).getCommittedFlowCount());
        assertEquals(1, engine.solve(topology, List.of(later)).getCommittedFlowCount());
        assertEquals(1, engine.getLRIB().getAllReservations().size());
    }

    @Test
    void cancellationReleasesTaskReservations() {
        BaseTopology topology = oneLinkTopology();
        CRPEngine engine = new CRPEngine().withPersistentState(true);
        WorkloadTask task = new WorkloadTask("T1", "A", "B", 0, 10, 100);
        engine.solve(topology, List.of(task));

        assertTrue(engine.cancelTask("T1"));
        assertTrue(engine.getLRIB().getAllReservations().isEmpty());
        assertTrue(engine.getNRIB().getAllReservations().isEmpty());
        assertTrue(!engine.cancelTask("T1"));
    }

    @Test
    void eapIgnoresFarFutureReservationsWhenALinkIsFreeNow() {
        LRIB lrib = new LRIB();
        Link freeNow = new Link("FREE_NOW", "A", "B", LinkIntermittencyFunction.persistentLink(100, 0));
        Link busyNow = new Link("BUSY_NOW", "A", "B", LinkIntermittencyFunction.persistentLink(100, 0));
        lrib.reserveLinkCap("FUTURE", "FREE_NOW", "A", "B", 100, 100, 110);
        lrib.reserveLinkCap("CURRENT", "BUSY_NOW", "A", "B", 100, 0, 10);
        WorkloadTask task = new WorkloadTask("T", "A", "B", 0, 20, 12.5);

        List<Link> selected = PathSelectionPolicy.EAP.selectPath(
                task, List.of(List.of(freeNow), List.of(busyNow)),
                new BaseTopology(), lrib, new NRIB());

        assertEquals("FREE_NOW", selected.get(0).getLinkId());
    }

    private static BaseTopology oneLinkTopology() {
        BaseTopology topology = new BaseTopology();
        topology.addNode(new Node("A", "A", 1_000, 1_000_000));
        topology.addNode(new Node("B", "B", 1_000, 1_000_000));
        topology.addLink(new Link("L", "A", "B", LinkIntermittencyFunction.persistentLink(1_000, 0)));
        return topology;
    }
}
