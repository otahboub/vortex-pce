package net.dcn.pce.northbound;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import net.dcn.pce.topology.CapacityStore;
import net.dcn.pce.topology.ObservedCapacityStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capacity refresh on promotion (failover staleness).
 *
 * <p>Observed capacities are loaded once, when the controller is built. On the shared backend the
 * acting leader keeps recording corrections while a standby stands by, so a standby promoted later
 * holds a topology as of its own startup — and would admit against a link the former leader had
 * corrected downward, over-subscribing it. {@code refreshDurableObservedCapacity()} re-reads the
 * shared store on promotion, before the instance serves, and closes that.
 *
 * <p>The two arms share one setup and differ only in whether the refresh runs: with it, a correction
 * written after startup changes what the next solve admits; without it, the same correction is
 * invisible and the solve plans against the stale declared capacity. That difference is the fix.
 */
class CapacityRefreshOnPromoteTest {

    /** A shared store a "second instance" can write to between this controller's build and promote. */
    private static final class MutableCapacityStore implements CapacityStore {
        private final Map<String, ObservedCapacityStore.Observation> observations =
                new ConcurrentHashMap<>();

        @Override
        public void record(String linkId, double observedBps, double previousBps, long atMillis) {
            observations.put(linkId,
                    new ObservedCapacityStore.Observation(observedBps, previousBps, atMillis));
        }

        @Override
        public void forget(String linkId) {
            observations.remove(linkId);
        }

        @Override
        public Map<String, ObservedCapacityStore.Observation> all() {
            return new LinkedHashMap<>(observations);
        }
    }

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e10, 1e9));
        topology.addNode(new Node("B", "B", 1e10, 1e9));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e8, 0.0001)));   // declared 100 Mbit
        return topology;
    }

    /** Six 15 MB flows over a 10 s deadline: a 100 Mbit link takes them all, a 20 Mbit link few. */
    private static String sixFlows(String tag) {
        StringBuilder body = new StringBuilder("[");
        for (int i = 1; i <= 6; i++) {
            body.append(i > 1 ? "," : "").append(String.format(
                    "{\"taskId\": \"%s%d\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\","
                            + " \"originationTimeSec\": 0, \"deadlineSec\": 10,"
                            + " \"taskSizeBytes\": 15000000}", tag, i));
        }
        return body.append("]").toString();
    }

    private static int committedFlows(String resultJson) {
        String marker = "\"committedFlowCount\" : ";
        int start = resultJson.indexOf(marker) + marker.length();
        return Integer.parseInt(resultJson.substring(start, resultJson.indexOf(',', start)).trim());
    }

    private static PCERestController controllerLoadedFrom(MutableCapacityStore store, Path stateFile)
            throws Exception {
        CRPEngine engine = new CRPEngine().withDurableState(stateFile.toString());
        PCERestController controller = new PCERestController(engine, topology());
        controller.withDurableObservedCapacity(store);   // loads what exists now (nothing yet)
        return controller;
    }

    @Test
    void aRefreshAppliesACorrectionWrittenAfterStartupAndWithoutItTheSolveIsStale(@TempDir Path dir)
            throws Exception {
        // Two controllers, identical setup: build against an empty store (topology at declared
        // 100 Mbit), then a "second instance" corrects A-B down to 20 Mbit in the shared store.
        MutableCapacityStore refreshedStore = new MutableCapacityStore();
        PCERestController refreshed = controllerLoadedFrom(refreshedStore, dir.resolve("a.json"));

        MutableCapacityStore staleStore = new MutableCapacityStore();
        PCERestController stale = controllerLoadedFrom(staleStore, dir.resolve("b.json"));

        refreshedStore.record("A-B", 20_000_000, 1e8, 1_000L);
        staleStore.record("A-B", 20_000_000, 1e8, 1_000L);

        // Only the promoted (refreshed) controller re-reads the shared store.
        refreshed.refreshDurableObservedCapacity();

        int refreshedAdmits = committedFlows(refreshed.handleScheduleWorkloadsRequest(sixFlows("R")));
        int staleAdmits = committedFlows(stale.handleScheduleWorkloadsRequest(sixFlows("S")));

        assertTrue(refreshedAdmits >= 1 && refreshedAdmits <= 2,
                "after refresh the 20 Mbit correction is in effect; admitted=" + refreshedAdmits);
        assertEquals(6, staleAdmits,
                "without refresh the controller still plans against the stale 100 Mbit capacity");
        assertTrue(staleAdmits > refreshedAdmits,
                "the refresh is exactly what makes the promoted leader admit against corrected capacity");
    }

    @Test
    void refreshWithNoStoreIsANoOp(@TempDir Path dir) throws Exception {
        // The in-memory / no-durable-capacity path must tolerate the promotion hook.
        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("c.json").toString());
        PCERestController controller = new PCERestController(engine, topology());
        controller.refreshDurableObservedCapacity();   // no capacity store wired; must not throw
        assertEquals(6, committedFlows(controller.handleScheduleWorkloadsRequest(sixFlows("N"))),
                "with no observations the declared 100 Mbit capacity still admits all six");
    }
}
