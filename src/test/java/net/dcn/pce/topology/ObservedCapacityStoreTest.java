package net.dcn.pce.topology;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import net.dcn.pce.northbound.PCERestController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An observed capacity must survive a restart.
 *
 * <p>Observations lived only in the running process, so a routine restart restored the capacity
 * declared at startup. An operator who had reduced a degraded link to 4 Mbit would find the
 * controller planning against 10 Mbit again, with nothing to say the correction had been
 * forgotten — and admitting flows onto a link that cannot carry them.
 */
class ObservedCapacityStoreTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e10, 1e9));
        topology.addNode(new Node("B", "B", 1e10, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e8, 0.0001)));
        return topology;
    }

    private static PCERestController controller(Path dir) {
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology());
        controller.withDurableObservedCapacity(
                ObservedCapacityStore.besideState(dir.resolve("state.json").toString()));
        return controller;
    }

    @Test
    void anObservationSurvivesARestart(@TempDir Path dir) {
        PCERestController before = controller(dir);
        before.recordObservedCapacity("A-B", 4e6);
        assertEquals(4e6, before.getActiveTopology().getLink("A-B").getLif().getBaseBandwidthBps());

        // A new controller over the same state path is what a restart looks like.
        PCERestController after = controller(dir);
        assertEquals(4e6, after.getActiveTopology().getLink("A-B").getLif().getBaseBandwidthBps(),
                "the restarted controller must not plan against the declared capacity again");
    }

    @Test
    void provenanceRecordsWhatTheObservationReplaced(@TempDir Path dir) {
        PCERestController controller = controller(dir);
        controller.recordObservedCapacity("A-B", 4e6);

        var recorded = controller.observedCapacities().get("A-B");
        assertEquals(4e6, recorded.observedBps);
        assertEquals(1e8, recorded.previousBps,
                "an operator asking why a link is 4 Mbit needs to see what it replaced");
        assertTrue(recorded.recordedAtEpochMillis > 0, "and when");
    }

    @Test
    void anUnreadableFileDoesNotPreventStartup(@TempDir Path dir) throws Exception {
        // The file is a refinement of the declared topology, not a ledger. Refusing to start on a
        // corrupt one would turn a damaged cache into an outage; planning against the declared
        // capacity is the behaviour from before it existed.
        Files.writeString(dir.resolve("observed-capacity.json"), "{ this is not json");
        PCERestController controller = controller(dir);
        assertEquals(1e8, controller.getActiveTopology().getLink("A-B")
                .getLif().getBaseBandwidthBps());
        assertTrue(controller.observedCapacities().isEmpty());
    }

    @Test
    void anObservationForALinkThatNoLongerExistsIsIgnored(@TempDir Path dir) {
        // The topology file can change between restarts. A stale entry must not abort startup.
        ObservedCapacityStore store =
                ObservedCapacityStore.besideState(dir.resolve("state.json").toString());
        store.record("GONE", 4e6, 1e8, System.currentTimeMillis());

        PCERestController controller = controller(dir);
        assertEquals(1e8, controller.getActiveTopology().getLink("A-B")
                .getLif().getBaseBandwidthBps());
    }

    @Test
    void anUnpersistableObservationIsNotPublishedInMemory(@TempDir Path dir) throws Exception {
        Path blocked = dir.resolve("blocked");
        Files.createFile(blocked);
        PCERestController controller = new PCERestController(new CRPEngine(), topology());
        controller.withDurableObservedCapacity(
                ObservedCapacityStore.besideState(blocked.resolve("state.json").toString()));

        assertThrows(java.io.UncheckedIOException.class,
                () -> controller.recordObservedCapacity("A-B", 4e6));
        assertEquals(1e8, controller.getActiveTopology().getLink("A-B")
                .getLif().getBaseBandwidthBps(),
                "a failed durable write must not change the active topology");
        assertTrue(controller.observedCapacities().isEmpty());
    }
}
