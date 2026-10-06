package net.dcn.pce.rib;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Append-only, file-backed write-ahead log for reservation state.
 *
 * <p>Replaces a full-snapshot rewrite per mutating request. That design serialized and fsynced
 * every live reservation on every solve, cancel, and prune, so the durable write cost grew with
 * total ledger depth while the information written grew only with the size of the change. Because
 * the write happened inside the same monitor that serializes planning, it throttled admission
 * throughput directly.
 *
 * <p>Each committed transaction appends only its net effect. The log is compacted once it grows
 * past a multiple of live state, which bounds both file size and restore time without making the
 * common path pay for it.
 *
 * <p>Durability: records are written and fsynced before {@link #append} returns, so a transaction
 * the caller observed as committed survives a crash. Compaction writes a new file and moves it
 * into place atomically, so an interruption leaves either the old log or the new one intact.
 */
public final class FileWalReservationStore implements ReservationStore {

    private static final Logger log = Logger.getLogger(FileWalReservationStore.class.getName());

    static final int FORMAT_VERSION = 7;

    /**
     * Format 6 recorded intents without the rate an outstanding update is converging to. A log at
     * this version restores cleanly: no intent in it can be UPDATING, because that state did not
     * exist when it was written.
     *
     * <p>The version is bumped rather than the field simply added, so that an older binary reading
     * a newer log is told the version it cannot handle instead of failing on an unrecognised
     * field. Replay rejects unknown record shapes, so the failure would otherwise be opaque.
     */
    static final int LEGACY_NO_PENDING_RATE_VERSION = 6;
    /**
     * Format 5 recorded intents without the owning tenant, which lived in a sidecar written after
     * the solve committed. A log at this version restores cleanly; its intents are simply unowned,
     * which now denies tenant-scoped access rather than granting it.
     */
    static final int LEGACY_NO_OWNER_VERSION = 5;
    /** Version 4 framed transactions but carried no installation intents. */
    static final int LEGACY_NO_INTENT_VERSION = 4;
    /** Version 3 wrote unframed records; a torn tail could apply a fraction of a transaction. */
    static final int LEGACY_UNFRAMED_VERSION = 3;
    private static final String OP_HEADER = "header";
    private static final String OP_PUT_LINK = "put_link";
    private static final String OP_PUT_NODE = "put_node";
    private static final String OP_REMOVE = "remove";
    /** An installation intent's current state, committed alongside its reservations. */
    private static final String OP_PUT_INTENT = "put_intent";
    /** Terminates a transaction. Records before it are applied only once it verifies. */
    private static final String OP_COMMIT = "commit";

    /** Compact when the log holds this multiple of live records, or this many records at minimum. */
    private static final int COMPACTION_FACTOR = 4;
    private static final int COMPACTION_FLOOR_RECORDS = 512;

    private final Path walPath;
    private final Path legacySnapshotPath;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Data records currently in the log, used only to decide when to compact. */
    private int loggedRecordCount;

    /**
     * Sequence number of the last committed transaction. Gaps or regressions in this series
     * distinguish a damaged file from a crash-truncated one.
     */
    private long lastTxnSeq;

    /**
     * Latest non-terminal intent per task, so compaction can carry them forward without being
     * handed the ledger. A {@code compact} overload that took the ledger optionally would drop
     * every intent whenever a caller used the older two-argument form, which is a silent data
     * loss rather than a compile error.
     *
     * <p>Terminal intents are removed rather than retained: their capacity is already released,
     * and a PCC still reporting such an LSP is an orphan whether or not the record survives.
     */
    private final java.util.Map<String, IntentRecord> liveIntents = new java.util.LinkedHashMap<>();

    /**
     * Held for as long as this store owns the log.
     *
     * <p>The log is a single-writer structure: appends carry a monotonic transaction sequence and
     * compaction rewrites the file wholesale. Two controllers sharing a volume would interleave
     * both while neither was aware of the other, and the damage would surface as a sequence gap at
     * the next restart rather than as a refusal.
     *
     * <p>Taken on the first write rather than at restore, because the invariant is single-writer
     * and reading violates nothing. Recovery tooling and successive restarts may read the same log
     * freely; only a second writer is refused.
     *
     * <p>This is a guard against accidental co-tenancy, not leader election. It does not make two
     * controllers safe; it makes the unsafe configuration fail loudly instead of quietly.
     */
    private java.nio.channels.FileLock ownershipLock;
    private java.nio.channels.FileChannel ownershipChannel;

    /**
     * This writer's epoch, stamped into the lock file when ownership is taken.
     *
     * <p>The lock alone is not enough, and the place shared state would actually live is exactly
     * where it is least enough. {@code FileLock} is advisory and its behaviour over NFS, EFS and
     * similar network filesystems ranges from unreliable to silently absent, so two controllers
     * on a shared volume can each believe they hold it. The lock then reports success to both and
     * the damage surfaces later as a sequence gap, long after the writes that caused it.
     *
     * <p>An epoch makes that detectable rather than merely unlikely. Taking ownership increments
     * a counter in the lock file; every append re-reads it and refuses if it has moved. A second
     * writer that slipped past the lock therefore fences the first one out on its very next write
     * — the first controller discovers it has been superseded and stops, instead of interleaving
     * transactions with a peer it cannot see.
     *
     * <p>This does not make two controllers safe, and it is not leader election. It converts an
     * undetected corruption into a loud refusal, which is what a shared-state backend will need
     * from this layer regardless of which backend is chosen.
     */
    private long ownershipEpoch;

    public FileWalReservationStore(String statePath) {
        if (statePath == null || statePath.isBlank()) {
            throw new IllegalArgumentException("statePath cannot be blank");
        }
        Path base = Path.of(statePath).toAbsolutePath().normalize();
        this.legacySnapshotPath = base;
        this.walPath = base.resolveSibling(base.getFileName().toString() + ".wal");
    }

    Path getWalPath() {
        return walPath;
    }

    @Override
    public synchronized boolean restore(LRIB lrib, NRIB nrib) {
        return restore(lrib, nrib, null);
    }

    /**
     * This writer's ownership epoch — the file WAL's realisation of the fencing token.
     *
     * <p>Stamped when single-writer ownership was taken and incremented on each re-acquisition, so
     * a writer that lost and regained the lock carries a strictly higher token than the stale one
     * it displaced. Zero before ownership is taken, which is {@link #NO_FENCING_TOKEN}.
     */
    @Override
    public synchronized long fencingToken() {
        return ownershipEpoch;
    }

    @Override
    public synchronized boolean restore(
            LRIB lrib, NRIB nrib, net.dcn.pce.install.IntentLedger intents) {
        if (Files.exists(walPath)) {
            return restoreFromWal(lrib, nrib, intents);
        }
        // Upgrade path: a deployment carrying a pre-WAL snapshot must not silently start with an
        // empty ledger and re-admit flows whose capacity is already committed.
        if (Files.exists(legacySnapshotPath)) {
            log.info("No write-ahead log found; importing legacy snapshot " + legacySnapshotPath);
            boolean restored = new LRIBStateStore(legacySnapshotPath.toString()).restoreJournal(lrib, nrib);
            if (restored) {
                compact(lrib, nrib);
            }
            return restored;
        }
        return false;
    }

    private boolean restoreFromWal(LRIB lrib, NRIB nrib) {
        return restoreFromWal(lrib, nrib, null);
    }

    private boolean restoreFromWal(
            LRIB lrib, NRIB nrib, net.dcn.pce.install.IntentLedger intentLedger) {
        boolean migrationRequired = false;
        boolean tornTailDetected = false;
        java.util.Map<String, IntentRecord> intents = new java.util.LinkedHashMap<>();
        List<LinkRecord> links = new ArrayList<>();
        List<NodeRecord> nodes = new ArrayList<>();
        Set<String> removed = new LinkedHashSet<>();
        int records = 0;
        long lineNumber = 0;

        List<String> allLines;
        try {
            // Read up front so a failure can inspect what follows it. Compaction bounds the file
            // to live state, and replay already materializes every record, so this costs nothing
            // beyond what the previous streaming pass allocated.
            allLines = Files.readAllLines(walPath, StandardCharsets.UTF_8);
            long size = Files.size(walPath);
            if (size > 0) {
                try (var channel = java.nio.channels.FileChannel.open(
                        walPath, StandardOpenOption.READ)) {
                    java.nio.ByteBuffer lastByte = java.nio.ByteBuffer.allocate(1);
                    channel.position(size - 1);
                    channel.read(lastByte);
                    tornTailDetected = lastByte.array()[0] != (byte) '\n';
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read reservation log " + walPath, e);
        }

        {
            boolean headerSeen = false;
            boolean requireCommit = true;
            boolean legacyUnframed = false;
            boolean parsedAnyLine = false;
            long expectedTxnSeq = 1;
            long highestTxnSeq = 0;
            List<LinkRecord> pendingLinks = new ArrayList<>();
            List<NodeRecord> pendingNodes = new ArrayList<>();
            List<String> pendingRemoved = new ArrayList<>();
            List<IntentRecord> pendingIntents = new ArrayList<>();
            List<String> pendingLines = new ArrayList<>();

            for (int index = 0; index < allLines.size(); index++) {
                String line = allLines.get(index);
                lineNumber = index + 1L;
                if (line.isBlank()) {
                    continue;
                }

                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (IOException e) {
                    failIfNotTail(allLines, index, "unparseable record", lineNumber);
                    tornTailDetected = true;
                    break;
                }

                parsedAnyLine = true;
                String op = node.path("op").asText();
                if (OP_HEADER.equals(op)) {
                    int version = node.path("formatVersion").asInt(-1);
                    if (version != FORMAT_VERSION && version != LEGACY_NO_PENDING_RATE_VERSION
                            && version != LEGACY_NO_OWNER_VERSION
                            && version != LEGACY_NO_INTENT_VERSION
                            && version != LEGACY_UNFRAMED_VERSION) {
                        throw new IllegalStateException(String.format(
                                "Unsupported reservation log format version %d at %s; expected %d. "
                                        + "Migrate or reset the state deliberately.",
                                version, walPath, FORMAT_VERSION));
                    }
                    requireCommit = version != LEGACY_UNFRAMED_VERSION;
                    legacyUnframed = version == LEGACY_UNFRAMED_VERSION;
                    headerSeen = true;
                    continue;
                }

                if (OP_COMMIT.equals(op)) {
                    if (!verifyCommit(node, pendingLines)) {
                        failIfNotTail(allLines, index, "transaction failing its checksum or record count", lineNumber);
                        tornTailDetected = true;
                        break;
                    }
                    long txnSeq = node.path("txnSeq").asLong(-1L);
                    if (txnSeq != expectedTxnSeq) {
                        // A gap means a transaction was lost; a regression means records moved.
                        // Neither can happen to an append-only file through crashing alone.
                        throw new IllegalStateException(String.format(
                                "Reservation log %s is corrupt: transaction sequence %d at line %d, expected %d. "
                                        + "Restore from backup or reset the state deliberately.",
                                walPath, txnSeq, lineNumber, expectedTxnSeq));
                    }
                    expectedTxnSeq++;
                    highestTxnSeq = txnSeq;
                    links.addAll(pendingLinks);
                    nodes.addAll(pendingNodes);
                    removed.addAll(pendingRemoved);
                    // Later transitions supersede earlier ones for the same task.
                    pendingIntents.forEach(record -> intents.put(record.taskId, record));
                    records += pendingLines.size();
                    pendingLinks.clear();
                    pendingNodes.clear();
                    pendingRemoved.clear();
                    pendingIntents.clear();
                    pendingLines.clear();
                    continue;
                }

                try {
                    switch (op) {
                        case OP_PUT_LINK -> pendingLinks.add(objectMapper.treeToValue(node, LinkRecord.class));
                        case OP_PUT_NODE -> pendingNodes.add(objectMapper.treeToValue(node, NodeRecord.class));
                        case OP_REMOVE -> pendingRemoved.add(node.path("reservationId").asText());
                        case OP_PUT_INTENT -> pendingIntents.add(
                                objectMapper.treeToValue(node, IntentRecord.class));
                        default -> throw new IllegalStateException(
                                "Unknown reservation log operation '" + op + "' at " + walPath + ":" + lineNumber);
                    }
                } catch (IOException e) {
                    // The line parsed as JSON but does not carry a usable record. On a tail that
                    // is a partial write; anywhere else it is damage.
                    failIfNotTail(allLines, index, "record with an unreadable body", lineNumber);
                    tornTailDetected = true;
                    break;
                }
                pendingLines.add(line);

                if (!requireCommit) {
                    links.addAll(pendingLinks);
                    nodes.addAll(pendingNodes);
                    removed.addAll(pendingRemoved);
                    pendingIntents.forEach(record -> intents.put(record.taskId, record));
                    records += pendingLines.size();
                    pendingLinks.clear();
                    pendingNodes.clear();
                    pendingRemoved.clear();
                    pendingIntents.clear();
                    pendingLines.clear();
                }
            }

            if (!headerSeen) {
                if (parsedAnyLine) {
                    throw new IllegalStateException(
                            "Reservation log " + walPath + " has no format header");
                }
                log.warning("Reservation log " + walPath + " is empty or torn before its header");
                tornTailDetected = true;
            }
            if (!pendingLines.isEmpty()) {
                log.warning(String.format(
                        "Discarding %d uncommitted record(s) at the tail of %s",
                        pendingLines.size(), walPath));
                tornTailDetected = true;
            }
            lastTxnSeq = highestTxnSeq;
            migrationRequired = legacyUnframed;
        }



        // A removal always postdates the put it cancels, so applying removals as a final filter
        // yields the same ledger as replaying strictly in order, without materializing the
        // intermediate states.
        LRIB restoredLrib = new LRIB();
        NRIB restoredNrib = new NRIB();
        Set<String> applied = new LinkedHashSet<>();
        for (LinkRecord record : links) {
            if (!removed.contains(record.reservationId) && applied.add(record.reservationId)) {
                restoredLrib.restoreLinkReservation(
                        record.reservationId, record.taskId, record.linkId, record.sourceNodeId,
                        record.destNodeId, record.reservedBwBps, record.startSec, record.endSec,
                        record.weight == null ? 1.0 : record.weight);
            }
        }
        for (NodeRecord record : nodes) {
            if (!removed.contains(record.reservationId) && applied.add(record.reservationId)) {
                restoredNrib.restoreNodeReservation(
                        record.reservationId, record.taskId, record.nodeId,
                        record.reservedBufferBytes, record.startSec, record.endSec);
            }
        }

        lrib.clear();
        nrib.clear();
        for (LRIB.LinkReservation reservation : restoredLrib.getAllReservations()) {
            lrib.restoreLinkReservation(
                    reservation.getReservationId(), reservation.getTaskId(), reservation.getLinkId(),
                    reservation.getSourceNodeId(), reservation.getDestNodeId(),
                    reservation.getReservedBwBps(), reservation.getStartSec(), reservation.getEndSec(),
                    reservation.getWeight());
        }
        for (NRIB.NodeReservation reservation : restoredNrib.getAllReservations()) {
            nrib.restoreNodeReservation(
                    reservation.getReservationId(), reservation.getTaskId(), reservation.getNodeId(),
                    reservation.getReservedBufferBytes(), reservation.getStartSec(), reservation.getEndSec());
        }

        liveIntents.clear();
        intents.forEach((taskId, record) -> {
            if (!net.dcn.pce.install.InstallationState.valueOf(record.state).isTerminal()) {
                liveIntents.put(taskId, record);
            }
        });
        if (intentLedger != null) {
            for (IntentRecord record : intents.values()) {
                intentLedger.restore(net.dcn.pce.install.InstallationIntent.restore(
                        record.taskId, record.lspName,
                        net.dcn.pce.install.InstallationState.valueOf(record.state),
                        record.srpId, record.plspId, record.pccSessionKey, record.owner,
                        record.pendingRateBps));
            }
        }

        loggedRecordCount = records;

        // A version-3 log carries no commit markers, so replay reads it under unframed rules.
        // Appending framed transactions to a file that still declares version 3 would leave the
        // next restore reading them the same way, which is the torn-write exposure the framing
        // work closed. Rewrite it now, before the store accepts any mutation.
        if (migrationRequired || tornTailDetected) {
            if (migrationRequired) {
                log.info("Migrating unframed reservation log " + walPath
                        + " to format " + FORMAT_VERSION);
            }
            if (tornTailDetected) {
                // Replay ignored only the incomplete physical suffix. Leaving those bytes in
                // place is not recovery: the next append puts valid records after them, turning
                // the old tail into mid-file corruption at the following restart. Rewrite the
                // recovered complete prefix atomically before this store may accept a mutation.
                log.warning("Atomically healing crash-torn reservation log tail at " + walPath);
            }
            compact(lrib, nrib);
        }

        log.info(String.format("Restored %d link and %d node reservations from %d log records at %s",
                lrib.getAllReservations().size(), nrib.getAllReservations().size(), records, walPath));
        return true;
    }

    @Override
    public synchronized void append(ReservationDelta delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }

        List<String> lines = new ArrayList<>(delta.recordCount());
        try {
            for (LRIB.LinkReservation reservation : delta.addedLinkReservations()) {
                lines.add(objectMapper.writeValueAsString(LinkRecord.from(reservation)));
            }
            for (NRIB.NodeReservation reservation : delta.addedNodeReservations()) {
                lines.add(objectMapper.writeValueAsString(NodeRecord.from(reservation)));
            }
            for (String reservationId : delta.removedReservationIds()) {
                ObjectNode record = objectMapper.createObjectNode();
                record.put("op", OP_REMOVE);
                record.put("reservationId", reservationId);
                lines.add(objectMapper.writeValueAsString(record));
            }
            for (net.dcn.pce.install.InstallationIntent intent : delta.intentTransitions()) {
                lines.add(objectMapper.writeValueAsString(IntentRecord.from(intent)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to encode reservation log records", e);
        }

        acquireOwnership();
        requireOwnershipEpoch();
        ensureHeader();
        int dataRecords = lines.size();
        // The sequence number is computed but not published. Advancing it before the write meant
        // a failed append still consumed a number, so the next successful append left a gap --
        // and replay treats a gap as corruption. A transient storage error became a controller
        // that would not start.
        long nextTxnSeq = lastTxnSeq + 1;
        lines.add(commitRecord(lines, nextTxnSeq));
        // Data records and their commit marker go out in one write followed by one fsync, so the
        // marker can never reach the device without the records it certifies.
        writeLines(lines, StandardOpenOption.APPEND);

        // Past the commit point. Only now is any of it visible to a later append or compaction.
        lastTxnSeq = nextTxnSeq;
        loggedRecordCount += dataRecords;
        trackLiveIntents(delta.intentTransitions());
    }

    /**
     * Builds the commit marker certifying the preceding records of this transaction.
     *
     * <p>The record count alone would not detect a tail that happens to end on a line boundary
     * with a plausible-looking record, so the marker also carries a checksum of the exact bytes
     * it commits.
     */
    private String commitRecord(List<String> dataLines, long txnSeq) {
        ObjectNode commit = objectMapper.createObjectNode();
        commit.put("op", OP_COMMIT);
        commit.put("txnSeq", txnSeq);
        commit.put("recordCount", dataLines.size());
        commit.put("checksum", checksumOf(dataLines));
        try {
            return objectMapper.writeValueAsString(commit);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to encode reservation log commit record", e);
        }
    }

    private static long checksumOf(List<String> dataLines) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        for (String dataLine : dataLines) {
            crc.update(dataLine.getBytes(StandardCharsets.UTF_8));
            crc.update((byte) '\n');
        }
        return crc.getValue();
    }

    /**
     * Decides whether a replay failure is a crash artifact or damage.
     *
     * <p>A torn tail has nothing valid after it: the records simply never reached the device.
     * Anything parseable following the failure means the file was written completely and then
     * altered, so stopping there would silently drop committed reservations and let their
     * capacity be handed out twice.
     */
    private void failIfNotTail(List<String> allLines, int failureIndex, String reason, long lineNumber) {
        for (int index = failureIndex + 1; index < allLines.size(); index++) {
            String line = allLines.get(index);
            if (line.isBlank()) {
                continue;
            }
            try {
                String op = objectMapper.readTree(line).path("op").asText();
                if (OP_PUT_LINK.equals(op) || OP_PUT_NODE.equals(op)
                        || OP_REMOVE.equals(op) || OP_COMMIT.equals(op) || OP_HEADER.equals(op)) {
                    throw new IllegalStateException(String.format(
                            "Reservation log %s is corrupt: %s at line %d is followed by further valid "
                                    + "records, so this is not a crash-truncated tail. Restore from backup "
                                    + "or reset the state deliberately.",
                            walPath, reason, lineNumber));
                }
            } catch (IOException ignored) {
                // Also unreadable; keep scanning for anything intact.
            }
        }
        log.warning(String.format("Discarding torn tail of %s: %s at line %d", walPath, reason, lineNumber));
    }

    private void trackLiveIntents(List<net.dcn.pce.install.InstallationIntent> transitions) {
        for (net.dcn.pce.install.InstallationIntent intent : transitions) {
            if (intent.getState().isTerminal()) {
                liveIntents.remove(intent.getTaskId());
            } else {
                liveIntents.put(intent.getTaskId(), IntentRecord.from(intent));
            }
        }
    }

    /**
     * Takes exclusive ownership of the log, or explains why it cannot.
     *
     * <p>The lock is taken on a sidecar file rather than the log itself, so that compaction may
     * replace the log without dropping ownership mid-rewrite.
     */
    private void acquireOwnership() {
        if (ownershipLock != null) {
            return;
        }
        Path lockPath = walPath.resolveSibling(walPath.getFileName() + ".lock");
        try {
            Files.createDirectories(lockPath.getParent());
            ownershipChannel = java.nio.channels.FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            ownershipLock = ownershipChannel.tryLock();
            if (ownershipLock != null) {
                ownershipEpoch = stampEpoch(lockPath);
            }
        } catch (java.nio.channels.OverlappingFileLockException alreadyHeldInThisJvm) {
            closeOwnershipChannel();
            throw new IllegalStateException(refusalMessage(lockPath), alreadyHeldInThisJvm);
        } catch (IOException e) {
            closeOwnershipChannel();
            throw new IllegalStateException(
                    "Unable to take ownership of reservation log " + walPath, e);
        }
        if (ownershipLock == null) {
            closeOwnershipChannel();
            throw new IllegalStateException(refusalMessage(lockPath));
        }
    }

    /** Increments and records the epoch in the lock file, returning this writer's value. */
    private long stampEpoch(Path lockPath) throws IOException {
        long next = readEpoch(lockPath).orElse(0L) + 1;
        Path temporary = lockPath.resolveSibling(lockPath.getFileName() + ".epoch");
        Files.writeString(temporary, Long.toString(next), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, epochPath(lockPath),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, epochPath(lockPath),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return next;
    }

    private static Path epochPath(Path lockPath) {
        return lockPath.resolveSibling(lockPath.getFileName() + ".generation");
    }

    /**
     * The recorded epoch, or empty when there is no readable one.
     *
     * <p>Empty and zero are deliberately different answers. "No epoch recorded" is what a fresh
     * log or a damaged sidecar looks like, and it says nothing about whether another writer
     * exists; treating it as a number would fence out the legitimate owner the moment the file
     * was corrupted, which a test caught doing exactly that.
     */
    private static java.util.OptionalLong readEpoch(Path lockPath) {
        Path path = epochPath(lockPath);
        if (!Files.exists(path)) {
            return java.util.OptionalLong.empty();
        }
        try {
            return java.util.OptionalLong.of(Long.parseLong(
                    Files.readString(path, StandardCharsets.UTF_8).trim()));
        } catch (IOException | NumberFormatException e) {
            return java.util.OptionalLong.empty();
        }
    }

    /**
     * Refuses the write if another writer has taken ownership since this one did.
     *
     * <p>Checked on every append rather than once, because the whole point is to catch a writer
     * that appeared after this one started. A single check at startup would prove only that
     * nobody had superseded us before we began.
     */
    private void requireOwnershipEpoch() {
        if (ownershipEpoch == 0) {
            return;
        }
        Path lockPath = walPath.resolveSibling(walPath.getFileName() + ".lock");
        java.util.OptionalLong recorded = readEpoch(lockPath);
        if (recorded.isEmpty()) {
            // Cannot tell. The lock still names this controller as the owner, so refusing here
            // would turn a damaged sidecar into an outage for the instance that is, as far as
            // exclusion goes, correct.
            return;
        }
        long current = recorded.getAsLong();
        if (current != ownershipEpoch) {
            throw new IllegalStateException(String.format(
                    "This controller has been superseded: it owns reservation log %s at epoch %d "
                            + "but the log is now at epoch %d. Another writer took ownership, so "
                            + "continuing would interleave transactions with a controller this "
                            + "one cannot see. Stop this instance.",
                    walPath, ownershipEpoch, current));
        }
    }

    private static String refusalMessage(Path lockPath) {
        return "Another controller already owns this reservation log (" + lockPath + "). "
                + "The log is single-writer: two controllers sharing it would interleave "
                + "transactions and corrupt the sequence. Run one controller per state volume, "
                + "or give this one its own VORTEX_STATE_PATH.";
    }

    private void closeOwnershipChannel() {
        if (ownershipChannel != null) {
            try {
                ownershipChannel.close();
            } catch (IOException ignored) {
                // Nothing useful to do while already failing to acquire.
            }
            ownershipChannel = null;
        }
    }

    @Override
    public synchronized void close() {
        if (ownershipLock != null) {
            try {
                ownershipLock.release();
            } catch (IOException ignored) {
                // A lock that cannot be released is released by process exit anyway.
            }
            ownershipLock = null;
        }
        closeOwnershipChannel();
    }

    /** True when the marker's count and checksum both match the records actually read. */
    private static boolean verifyCommit(JsonNode commit, List<String> pendingLines) {
        return commit.path("recordCount").asInt(-1) == pendingLines.size()
                && commit.path("checksum").asLong(-1L) == checksumOf(pendingLines);
    }

    /** True when the log has grown enough that restore time no longer reflects live state. */
    synchronized boolean shouldCompact(int liveReservationCount) {
        return loggedRecordCount > Math.max(COMPACTION_FLOOR_RECORDS,
                COMPACTION_FACTOR * Math.max(1, liveReservationCount));
    }

    @Override
    public synchronized void compactIfNeeded(LRIB lrib, NRIB nrib) {
        int live = lrib.getAllReservations().size() + nrib.getAllReservations().size();
        if (shouldCompact(live)) {
            compact(lrib, nrib);
        }
    }

    @Override
    public synchronized void compact(LRIB lrib, NRIB nrib) {
        acquireOwnership();
        requireOwnershipEpoch();
        List<String> lines = new ArrayList<>();
        try {
            ObjectNode header = objectMapper.createObjectNode();
            header.put("op", OP_HEADER);
            header.put("formatVersion", FORMAT_VERSION);
            header.put("compactedAtEpochMs", System.currentTimeMillis());
            lines.add(objectMapper.writeValueAsString(header));

            for (LRIB.LinkReservation reservation : lrib.getAllReservations()) {
                lines.add(objectMapper.writeValueAsString(LinkRecord.from(reservation)));
            }
            for (NRIB.NodeReservation reservation : nrib.getAllReservations()) {
                lines.add(objectMapper.writeValueAsString(NodeRecord.from(reservation)));
            }
            for (IntentRecord intent : liveIntents.values()) {
                lines.add(objectMapper.writeValueAsString(intent));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to encode compacted reservation log", e);
        }

        // The compacted file is one transaction: header, records, commit. Without the marker a
        // reader of the new format would discard every record as uncommitted.
        List<String> dataLines = new ArrayList<>(lines.subList(1, lines.size()));
        // Compaction restarts the series: the rewritten file is transaction one.
        lastTxnSeq = 1;
        lines.add(commitRecord(dataLines, lastTxnSeq));

        Path parent = walPath.getParent();
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, walPath.getFileName().toString(), ".compact");
            try {
                writeTo(temporary, lines, StandardOpenOption.TRUNCATE_EXISTING);
                atomicReplace(temporary, walPath);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to compact reservation log " + walPath, e);
        }

        loggedRecordCount = lines.size() - 2;
        log.info(String.format("Compacted reservation log to %d records at %s",
                loggedRecordCount, walPath));
    }

    private void ensureHeader() {
        if (Files.exists(walPath)) {
            return;
        }
        try {
            Files.createDirectories(walPath.getParent());
            ObjectNode header = objectMapper.createObjectNode();
            header.put("op", OP_HEADER);
            header.put("formatVersion", FORMAT_VERSION);
            header.put("createdAtEpochMs", System.currentTimeMillis());
            writeTo(walPath, List.of(objectMapper.writeValueAsString(header)),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            syncDirectory(walPath.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize reservation log " + walPath, e);
        }
    }

    private void writeLines(List<String> lines, StandardOpenOption mode) {
        try {
            writeTo(walPath, lines, mode);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to append to reservation log " + walPath, e);
        }
    }

    private void writeTo(Path path, List<String> lines, StandardOpenOption... options) throws IOException {
        StringBuilder payload = new StringBuilder();
        lines.forEach(line -> payload.append(line).append('\n'));
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);

        List<StandardOpenOption> openOptions = new ArrayList<>(List.of(options));
        openOptions.add(StandardOpenOption.WRITE);
        openOptions.add(StandardOpenOption.CREATE);

        try (var channel = java.nio.channels.FileChannel.open(
                path, openOptions.toArray(new StandardOpenOption[0]))) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            // Durable before the caller is told the transaction committed.
            channel.force(true);
        }
    }

    private static void atomicReplace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Retained so the controller still runs on filesystems without atomic rename, but a
            // crash during this window can leave a truncated log. Say so rather than degrading
            // the crash-consistency contract silently.
            log.warning("Filesystem at " + destination.getParent() + " does not support atomic rename; "
                    + "reservation log replacement is not crash-atomic on this volume");
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        // A durable file whose directory entry is not durable is still lost on power failure.
        syncDirectory(destination.getParent());
    }

    /**
     * Flushes a directory entry so a newly created or replaced file survives a crash.
     *
     * <p>Not every platform permits opening a directory for read; where it does not, the entry is
     * left to the filesystem's own ordering rather than failing an otherwise durable commit.
     */
    private static void syncDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (var channel = java.nio.channels.FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            log.fine("Unable to fsync directory " + directory + ": " + e.getMessage());
        }
    }

    /**
     * Serialized form of an installation intent.
     *
     * <p>Terminal intents are not carried through compaction: their capacity is already released
     * and a PCC still reporting such an LSP is an orphan either way, so retaining them would grow
     * the log without changing any decision.
     */
    private static final class IntentRecord {
        public String op = OP_PUT_INTENT;
        public String taskId;
        public String lspName;
        public String state;
        public Long srpId;
        public Long plspId;
        public String pccSessionKey;
        /** Null in logs written before format 6, and in deployments without tenants. */
        public String owner;
        /**
         * The rate an outstanding update is converging to; null unless the state is UPDATING.
         *
         * <p>Persisted because a confirmation can arrive after a restart. An intent recovered in
         * UPDATING without it would know a rate change was outstanding and not what to settle it
         * at, leaving capacity held at the greater of two rates with no way to resolve to either.
         */
        public Double pendingRateBps;

        static IntentRecord from(net.dcn.pce.install.InstallationIntent intent) {
            IntentRecord record = new IntentRecord();
            record.taskId = intent.getTaskId();
            record.lspName = intent.getLspName();
            record.state = intent.getState().name();
            record.srpId = intent.getSrpId().orElse(null);
            record.plspId = intent.getPlspId().orElse(null);
            record.pccSessionKey = intent.getPccSessionKey().orElse(null);
            record.owner = intent.getOwner().orElse(null);
            record.pendingRateBps = intent.getPendingRateBps().orElse(null);
            return record;
        }
    }

    /** Serialized form of a link reservation. */
    private static final class LinkRecord {
        public String op = OP_PUT_LINK;
        public String reservationId;
        public String taskId;
        public String linkId;
        public String sourceNodeId;
        public String destNodeId;
        public double reservedBwBps;
        public double startSec;
        public double endSec;
        /**
         * Capacity weight when the reservation is overbooked; absent otherwise, so records of
         * full-weight reservations are byte-identical to those written before weights existed, and
         * those older records read back as 1.0.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Double weight;

        static LinkRecord from(LRIB.LinkReservation reservation) {
            LinkRecord record = new LinkRecord();
            record.reservationId = reservation.getReservationId();
            record.taskId = reservation.getTaskId();
            record.linkId = reservation.getLinkId();
            record.sourceNodeId = reservation.getSourceNodeId();
            record.destNodeId = reservation.getDestNodeId();
            record.reservedBwBps = reservation.getReservedBwBps();
            record.startSec = reservation.getStartSec();
            record.endSec = reservation.getEndSec();
            record.weight = reservation.getWeight() == 1.0 ? null : reservation.getWeight();
            return record;
        }
    }

    /** Serialized form of a node-buffer reservation. */
    private static final class NodeRecord {
        public String op = OP_PUT_NODE;
        public String reservationId;
        public String taskId;
        public String nodeId;
        public double reservedBufferBytes;
        public double startSec;
        public double endSec;

        static NodeRecord from(NRIB.NodeReservation reservation) {
            NodeRecord record = new NodeRecord();
            record.reservationId = reservation.getReservationId();
            record.taskId = reservation.getTaskId();
            record.nodeId = reservation.getNodeId();
            record.reservedBufferBytes = reservation.getReservedBufferBytes();
            record.startSec = reservation.getStartSec();
            record.endSec = reservation.getEndSec();
            return record;
        }
    }
}
