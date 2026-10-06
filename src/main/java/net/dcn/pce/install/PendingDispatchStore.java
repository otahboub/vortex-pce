package net.dcn.pce.install;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What a planned-but-unsent installation needs in order to be sent later.
 *
 * <p>A solve that commits while no PCC is connected leaves its intents {@code PLANNED} holding
 * capacity. Retrying them when a router arrives requires the route and rate a PCInitiate is built
 * from, and those live on the solve result: an {@link InstallationIntent} carries an LSP name and
 * identifiers, not a path. Held only in memory, they were lost on restart, so a controller that
 * restarted before its router connected kept the reservations for ever and could never install
 * them.
 *
 * <p>Deliberately not in the write-ahead log. That log exists so reservations and installation
 * intents commit as one transaction, because separating them by a crash produces a ledger that
 * disagrees with the network. These are neither: they are a planning output kept so a later
 * attempt can be encoded, and losing one degrades convergence rather than correctness — the
 * intent stays {@code PLANNED}, its capacity stays held, and an operator can still cancel it.
 * Putting a route array on every intent record would also enlarge every reservation transaction
 * on the hot path to serve the small subset of intents that are waiting for a router.
 */
public final class PendingDispatchStore {

    private static final Logger log = Logger.getLogger(PendingDispatchStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The inputs a PCInitiate is encoded from, small enough to persist. */
    public static final class Request {
        public List<String> routeLinkIds = List.of();
        public double rateBps;

        public Request() {
        }

        public Request(List<String> routeLinkIds, double rateBps) {
            this.routeLinkIds = List.copyOf(routeLinkIds);
            this.rateBps = rateBps;
        }
    }

    private final Path path;
    private final Map<String, Request> requests = new LinkedHashMap<>();

    public PendingDispatchStore(Path path) {
        this.path = path;
        load();
    }

    /** Derives the path from the reservation state path, so one setting configures both. */
    public static PendingDispatchStore besideState(String statePath) {
        Path state = Path.of(statePath);
        Path directory = state.getParent() == null ? Path.of(".") : state.getParent();
        return new PendingDispatchStore(directory.resolve("pending-dispatch.json"));
    }

    private void load() {
        if (!Files.exists(path)) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Request> restored = MAPPER.readValue(in,
                    MAPPER.getTypeFactory().constructMapType(
                            LinkedHashMap.class, String.class, Request.class));
            requests.putAll(restored);
            log.info("Restored " + requests.size() + " pending installation dispatch request(s) "
                    + "from " + path);
        } catch (IOException | RuntimeException e) {
            // Dropped rather than fatal, for the same reason they are not in the WAL: without
            // them the intents stay PLANNED and visible, which is the behaviour before this file
            // existed. Refusing to start would turn a damaged cache into an outage.
            log.log(Level.WARNING, "Ignoring unreadable pending-dispatch file " + path
                    + "; planned installations will not be retried automatically", e);
            requests.clear();
        }
    }

    /**
     * Records a request, throwing if it cannot be made durable.
     *
     * <p>Swallowing the failure here was the defect. The reservation is already committed by this
     * point, so a lost dispatch record leaves capacity durably held for work the controller can
     * no longer encode — accepted, paid for, and impossible to install. The caller cancels the
     * task rather than acknowledging an admission it cannot act on.
     */
    public synchronized void put(String taskId, Request request) {
        Request previous = requests.put(taskId, request);
        if (!persist()) {
            if (previous == null) {
                requests.remove(taskId);
            } else {
                requests.put(taskId, previous);
            }
            throw new java.io.UncheckedIOException(new IOException(
                    "Could not persist the dispatch request for " + taskId + " to " + path));
        }
    }

    public synchronized void remove(String taskId) {
        if (requests.remove(taskId) != null) {
            // A removal that fails to persist leaves a stale request on disk. Harmless: replay
            // skips a task whose intent is gone or no longer PLANNED.
            persist();
        }
    }

    public synchronized Map<String, Request> all() {
        return new LinkedHashMap<>(requests);
    }

    /** @return whether the state reached disk */
    private boolean persist() {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(temporary, MAPPER.writeValueAsString(requests),
                    StandardCharsets.UTF_8);
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                file.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(path.getParent());
            return true;
        } catch (IOException e) {
            log.log(Level.SEVERE, "Could not persist pending dispatch requests to " + path, e);
            return false;
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
