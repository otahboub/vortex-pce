package net.dcn.pce.install;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import net.dcn.pce.northbound.PCERestController;
import net.dcn.pce.rib.FileWalReservationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A task's owner and its reservation must be recovered together or not at all.
 *
 * <p>Ownership used to be claimed by the HTTP handler after the solve returned, into a file beside
 * the log. A crash in between produced a committed reservation whose task belonged to nobody —
 * and unknown ownership then decided who could cancel it. The owner now travels with the solve
 * and is written in the same durable transaction as the reservation it authorises access to, so
 * the two cannot disagree.
 */
class OwnershipIsCommittedWithTheReservationTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e10, 1e9));
        topology.addNode(new Node("B", "B", 1e10, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e8, 0.0001)));
        return topology;
    }

    private static final String TASK =
            "[{\"taskId\": \"T1\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\","
                    + " \"originationTimeSec\": 0, \"deadlineSec\": 100,"
                    + " \"taskSizeBytes\": 4000000, \"priority\": 1}]";

    @Test
    void theOwnerSurvivesARestartWithItsReservation(@TempDir Path dir) throws Exception {
        String statePath = dir.resolve("state.json").toString();

        FileWalReservationStore store = new FileWalReservationStore(statePath);
        try {
            PCERestController controller =
                    new PCERestController(new CRPEngine().withReservationStore(store), topology());
            controller.handleScheduleWorkloadsRequest(TASK, null, "tenant-a");
            assertEquals("tenant-a", controller.findIntent("T1").orElseThrow()
                    .getOwner().orElseThrow());
        } finally {
            store.close();
        }

        FileWalReservationStore reopened = new FileWalReservationStore(statePath);
        try {
            CRPEngine engine = new CRPEngine().withReservationStore(reopened);
            PCERestController restarted = new PCERestController(engine, topology());
            assertEquals("tenant-a", restarted.findIntent("T1").orElseThrow()
                            .getOwner().orElseThrow(),
                    "the owner must be recovered with the reservation it guards");
            assertTrue(engine.getLRIB().getAllReservations().stream()
                            .anyMatch(r -> "T1".equals(r.getTaskId())),
                    "and the reservation itself must still be there");
        } finally {
            reopened.close();
        }
    }

    @Test
    void ownershipIsCarriedAcrossStateTransitions(@TempDir Path dir) throws Exception {
        // A task does not change hands because it moved from PLANNED to INSTALLING.
        InstallationIntent planned = InstallationIntent.planned("T1", "tenant-a");
        assertEquals("tenant-a", planned.getOwner().orElseThrow());

        IntentLedger ledger = new IntentLedger();
        ledger.restore(planned);
        InstallationIntent installing = ledger.markInstalling("T1", 7L, "speaker:pcc-alpha");
        assertEquals("tenant-a", installing.getOwner().orElseThrow(),
                "an intent must not become unowned by being dispatched");
    }

    @Test
    void aLogWrittenBeforeOwnersExistedStillRestores(@TempDir Path dir) throws Exception {
        // Format 5 recorded no owner. Such a log must load, with its tasks unowned -- which now
        // denies tenant-scoped access rather than granting it.
        String statePath = dir.resolve("state.json").toString();
        FileWalReservationStore store = new FileWalReservationStore(statePath);
        try {
            PCERestController controller =
                    new PCERestController(new CRPEngine().withReservationStore(store), topology());
            controller.handleScheduleWorkloadsRequest(TASK, null, null);
        } finally {
            store.close();
        }

        Path wal = Path.of(statePath + ".wal");
        String downgraded = Files.readString(wal).replace("\"formatVersion\":6", "\"formatVersion\":5");
        Files.writeString(wal, downgraded);

        FileWalReservationStore reopened = new FileWalReservationStore(statePath);
        try {
            CRPEngine engine = new CRPEngine().withReservationStore(reopened);
            PCERestController restarted = new PCERestController(engine, topology());
            assertTrue(restarted.findIntent("T1").isPresent(), "a format 5 log must still restore");
            assertTrue(restarted.findIntent("T1").orElseThrow().getOwner().isEmpty(),
                    "and its tasks are unowned");
        } finally {
            reopened.close();
        }
    }
}
