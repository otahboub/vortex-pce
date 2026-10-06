package net.dcn.pce.northbound;

import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admission into the undispatched backlog must be atomic.
 *
 * <p>When no PCC is connected an admitted schedule is held for later dispatch, bounded by
 * {@code maxUndispatched}. The old gate read {@code undispatched.size()} before solving and filled
 * it after: two concurrent solves could both see the last free slot, both commit, and the second's
 * dispatch inputs would then be dropped by the buffer backstop — cancelling its reservations — while
 * the response still reported the task as committed. The reservation is now claimed atomically before
 * the solve, so exactly {@code maxUndispatched} solves can win the buffer and the rest are refused up
 * front; no caller is ever told a schedule was installed that was in fact cancelled.
 */
class BacklogAdmissionRaceTest {

    private static BaseTopology topology() {
        BaseTopology topology = new BaseTopology();
        topology.setRegime(ContactRegime.R_STATIC);
        topology.addNode(new Node("A", "A", 1e10, 1e9, "10.0.0.1"));
        topology.addNode(new Node("B", "B", 1e10, 1e9, "10.0.0.2"));
        topology.addLink(new Link("A-B", "A", "B",
                LinkIntermittencyFunction.persistentLink(1e8, 0.0001)));
        return topology;
    }

    private static String task(String id) {
        return "[{\"taskId\": \"" + id + "\", \"sourceNodeId\": \"A\", \"destinationNodeId\": \"B\","
                + " \"originationTimeSec\": 0, \"deadlineSec\": 100,"
                + " \"taskSizeBytes\": 4000000, \"priority\": 1}]";
    }

    @Test
    void concurrentSolvesCannotOverfillTheBacklog(@TempDir Path dir) throws Exception {
        int capacity = 3;
        int contenders = 24;

        CRPEngine engine = new CRPEngine().withDurableState(dir.resolve("state.json").toString());
        PCERestController controller = new PCERestController(engine, topology());
        controller.setMaxUndispatchedForTest(capacity);

        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        // No PCC will ever be offered, so every admitted schedule must be held, not installed.
        controller.setInstallDispatch(Optional::empty, (intent, srpId, route, rateBps) -> {
            sent.add(intent.getTaskId());
            return CRPEngine.DispatchOutcome.SENT;
        });

        CyclicBarrier start = new CyclicBarrier(contenders);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<String> admittedIds = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String id = "T" + i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    controller.handleScheduleWorkloadsRequest(task(id));
                    admitted.incrementAndGet();
                    admittedIds.add(id);
                } catch (PCERestController.UndispatchableBacklogException refuse) {
                    refused.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(capacity, admitted.get(),
                "exactly the buffer's worth of solves may be admitted with no PCC");
        assertEquals(contenders - capacity, refused.get(),
                "every solve past the buffer must be refused up front, not committed-then-cancelled");
        assertTrue(sent.isEmpty(), "nothing can be installed with no PCC");

        // Every task the response admitted must still be genuinely held for dispatch — none may have
        // been silently cancelled by the buffer backstop after being reported as committed.
        Set<String> held = controller.pendingDispatchTaskIds();
        assertEquals(capacity, held.size(), "held == admitted; no admitted task was dropped");
        for (String id : admittedIds) {
            assertTrue(held.contains(id), "admitted task " + id + " must actually be held: " + held);
        }
    }
}
