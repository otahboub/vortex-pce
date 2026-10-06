package net.dcn.pce.rib;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/** Versioned, atomic file-backed snapshot store for LRIB and NRIB reservations. */
public class LRIBStateStore {

    private static final Logger log = Logger.getLogger(LRIBStateStore.class.getName());
    // Version 2 reservations are contact-execution slices rather than average-rate spans.
    private static final int FORMAT_VERSION = 2;

    private final Path journalPath;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public LRIBStateStore(String journalPath) {
        if (journalPath == null || journalPath.isBlank()) {
            throw new IllegalArgumentException("journalPath cannot be blank");
        }
        this.journalPath = Path.of(journalPath).toAbsolutePath().normalize();
    }

    /** Persist a complete reservation snapshot and atomically replace the prior version. */
    public synchronized void persistJournal(LRIB lrib, NRIB nrib) {
        StateSnapshot snapshot = new StateSnapshot(
                FORMAT_VERSION,
                System.currentTimeMillis(),
                lrib.getAllReservations().stream().map(LinkReservationState::from).toList(),
                nrib.getAllReservations().stream().map(NodeReservationState::from).toList());

        Path parent = journalPath.getParent();
        try {
            Files.createDirectories(parent);
            byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
            Path temporary = Files.createTempFile(parent, journalPath.getFileName().toString(), ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(
                        temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(content);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                atomicReplace(temporary, journalPath);
            } finally {
                Files.deleteIfExists(temporary);
            }
            log.info("Persisted " + snapshot.linkReservations().size() + " LRIB and "
                    + snapshot.nodeReservations().size() + " NRIB reservations to " + journalPath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist reservation state to " + journalPath, e);
        }
    }

    /** Restore a complete snapshot. Live ledgers are changed only after full validation. */
    public synchronized boolean restoreJournal(LRIB lrib, NRIB nrib) {
        if (!Files.exists(journalPath)) {
            return false;
        }

        final StateSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(journalPath.toFile(), StateSnapshot.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to parse reservation state from " + journalPath, e);
        }
        validateSnapshot(snapshot);

        LRIB restoredLrib = new LRIB();
        NRIB restoredNrib = new NRIB();
        for (LinkReservationState state : snapshot.linkReservations()) {
            restoredLrib.restoreLinkReservation(
                    state.reservationId(), state.taskId(), state.linkId(), state.sourceNodeId(),
                    state.destNodeId(), state.reservedBwBps(), state.startSec(), state.endSec(),
                    state.weight() == null ? 1.0 : state.weight());
        }
        for (NodeReservationState state : snapshot.nodeReservations()) {
            restoredNrib.restoreNodeReservation(
                    state.reservationId(), state.taskId(), state.nodeId(), state.reservedBufferBytes(),
                    state.startSec(), state.endSec());
        }

        lrib.clear();
        nrib.clear();
        for (LRIB.LinkReservation state : restoredLrib.getAllReservations()) {
            lrib.restoreLinkReservation(
                    state.getReservationId(), state.getTaskId(), state.getLinkId(), state.getSourceNodeId(),
                    state.getDestNodeId(), state.getReservedBwBps(), state.getStartSec(), state.getEndSec(),
                    state.getWeight());
        }
        for (NRIB.NodeReservation state : restoredNrib.getAllReservations()) {
            nrib.restoreNodeReservation(
                    state.getReservationId(), state.getTaskId(), state.getNodeId(), state.getReservedBufferBytes(),
                    state.getStartSec(), state.getEndSec());
        }
        log.info("Restored " + snapshot.linkReservations().size() + " LRIB and "
                + snapshot.nodeReservations().size() + " NRIB reservations from " + journalPath);
        return true;
    }

    private static void atomicReplace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateSnapshot(StateSnapshot snapshot) {
        if (snapshot == null || snapshot.formatVersion() != FORMAT_VERSION
                || snapshot.linkReservations() == null || snapshot.nodeReservations() == null) {
            throw new IllegalStateException("Unsupported or incomplete reservation snapshot");
        }
        Set<String> reservationIds = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        snapshot.linkReservations().forEach(state -> {
            if (state == null || !reservationIds.add(state.reservationId())) {
                duplicates.add(state == null ? "null" : state.reservationId());
            }
        });
        snapshot.nodeReservations().forEach(state -> {
            if (state == null || !reservationIds.add(state.reservationId())) {
                duplicates.add(state == null ? "null" : state.reservationId());
            }
        });
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("Duplicate or null reservation IDs: " + duplicates);
        }
    }

    private record StateSnapshot(
            int formatVersion,
            long timestampEpochMs,
            List<LinkReservationState> linkReservations,
            List<NodeReservationState> nodeReservations) {
    }

    private record LinkReservationState(
            String reservationId,
            String taskId,
            String linkId,
            String sourceNodeId,
            String destNodeId,
            double reservedBwBps,
            double startSec,
            double endSec,
            // Absent for full-weight reservations, so snapshots written before weights existed read
            // back as 1.0 and full-weight snapshots are unchanged.
            @JsonInclude(JsonInclude.Include.NON_NULL) Double weight) {
        static LinkReservationState from(LRIB.LinkReservation reservation) {
            return new LinkReservationState(
                    reservation.getReservationId(), reservation.getTaskId(), reservation.getLinkId(),
                    reservation.getSourceNodeId(), reservation.getDestNodeId(), reservation.getReservedBwBps(),
                    reservation.getStartSec(), reservation.getEndSec(),
                    reservation.getWeight() == 1.0 ? null : reservation.getWeight());
        }
    }

    private record NodeReservationState(
            String reservationId,
            String taskId,
            String nodeId,
            double reservedBufferBytes,
            double startSec,
            double endSec) {
        static NodeReservationState from(NRIB.NodeReservation reservation) {
            return new NodeReservationState(
                    reservation.getReservationId(), reservation.getTaskId(), reservation.getNodeId(),
                    reservation.getReservedBufferBytes(), reservation.getStartSec(), reservation.getEndSec());
        }
    }
}
