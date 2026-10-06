package net.dcn.pce.topology;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Durable record of what links turned out to have, with when and what it replaced.
 *
 * <p>An observation used to live only in the running process, so a restart silently restored the
 * capacity declared at startup. An operator who had reduced a degraded link to 4 Mbit would find
 * the controller planning against 10 Mbit again after a routine restart, with nothing to indicate
 * the correction had been forgotten — and admitting flows onto a link that cannot carry them.
 *
 * <p>Kept beside the reservation log rather than inside it, deliberately. The write-ahead log
 * exists so that reservations and installation intents commit as one transaction, because
 * separating them by a crash produces a ledger that disagrees with the network. A capacity
 * observation has no such partner: it is a correction to the topology description, not a ledger
 * entry. Losing one on a crash restores the declared capacity, which is exactly today's behaviour
 * and cannot produce an over-subscribed ledger — an observation below what is already committed
 * is refused before it is ever recorded.
 *
 * <p>Each entry carries provenance, because an operator asked "why is this link 4 Mbit?" needs to
 * know it was observed rather than configured, and when.
 */
public final class ObservedCapacityStore implements CapacityStore {

    private static final Logger log = Logger.getLogger(ObservedCapacityStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One recorded observation. */
    public static final class Observation {
        public double observedBps;
        public double previousBps;
        public long recordedAtEpochMillis;

        public Observation() {
        }

        public Observation(double observedBps, double previousBps, long recordedAtEpochMillis) {
            this.observedBps = observedBps;
            this.previousBps = previousBps;
            this.recordedAtEpochMillis = recordedAtEpochMillis;
        }
    }

    private final Path path;
    private final Map<String, Observation> observations = new LinkedHashMap<>();

    public ObservedCapacityStore(Path path) {
        this.path = path;
        load();
    }

    /** Derives the sidecar path from a reservation state path, so one setting configures both. */
    public static ObservedCapacityStore besideState(String statePath) {
        Path state = Path.of(statePath);
        Path directory = state.getParent() == null ? Path.of(".") : state.getParent();
        return new ObservedCapacityStore(directory.resolve("observed-capacity.json"));
    }

    private void load() {
        if (!Files.exists(path)) {
            return;
        }
        try {
            Map<String, Observation> restored = MAPPER.readValue(
                    Files.readString(path, StandardCharsets.UTF_8),
                    MAPPER.getTypeFactory().constructMapType(
                            LinkedHashMap.class, String.class, Observation.class));
            observations.putAll(restored);
            log.info("Restored " + observations.size() + " observed link capacit"
                    + (observations.size() == 1 ? "y" : "ies") + " from " + path);
        } catch (IOException | RuntimeException e) {
            // Unreadable observations are dropped rather than fatal. They are a refinement of the
            // declared topology; starting without them means planning against the configured
            // capacity, which is the behaviour before this file existed. Refusing to start would
            // turn a corrupt cache into an outage.
            log.log(Level.WARNING, "Ignoring unreadable observed-capacity file " + path
                    + "; planning against declared capacity", e);
            observations.clear();
        }
    }

    /** Records an observation and makes it durable before returning. */
    public synchronized void record(String linkId, double observedBps, double previousBps,
                                    long recordedAtEpochMillis) {
        Observation previous = observations.put(
                linkId, new Observation(observedBps, previousBps, recordedAtEpochMillis));
        try {
            persist();
        } catch (java.io.UncheckedIOException e) {
            if (previous == null) {
                observations.remove(linkId);
            } else {
                observations.put(linkId, previous);
            }
            throw e;
        }
    }

    /** Forgets an observation, so the link returns to its declared capacity. */
    public synchronized void forget(String linkId) {
        if (observations.remove(linkId) != null) {
            persist();
        }
    }

    public synchronized Map<String, Observation> all() {
        return new LinkedHashMap<>(observations);
    }

    private void persist() {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(temporary, MAPPER.writeValueAsString(observations),
                    StandardCharsets.UTF_8);
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                file.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems cannot move atomically. A torn file is tolerable here because
                // load() treats an unreadable one as "no observations" rather than failing.
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(path.getParent());
        } catch (IOException e) {
            log.log(Level.SEVERE, "Could not persist observed capacities to " + path, e);
            throw new java.io.UncheckedIOException(e);
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
