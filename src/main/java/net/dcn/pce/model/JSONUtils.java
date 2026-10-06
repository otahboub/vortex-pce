package net.dcn.pce.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dcn.pce.crp.CRPEngine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural JSON Serialization & Deserialization Utilities for VortexPCE.
 * Provides strict JSON array validation, key-value token extraction, and schema enforcement.
 */
public class JSONUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final Set<String> WORKLOAD_FIELDS = Set.of(
            "taskId", "sourceNodeId", "destinationNodeId", "originationTimeSec",
            "deadlineSec", "taskSizeBytes", "priority");
    private static final int MAX_WORKLOADS_PER_REQUEST = 1_000;

    /**
     * Serializes a List of WorkloadTask requests into a JSON string blob.
     */
    public static String toWorkloadJson(List<WorkloadTask> tasks) {
        List<Map<String, Object>> payload = tasks.stream().map(task -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", task.getTaskId());
            item.put("sourceNodeId", task.getSourceNodeId());
            item.put("destinationNodeId", task.getDestinationNodeId());
            item.put("originationTimeSec", task.getOriginationTimeSec());
            item.put("deadlineSec", task.getDeadlineSec());
            item.put("taskSizeBytes", task.getTaskSizeBytes());
            item.put("priority", task.getPriority());
            return item;
        }).toList();
        return writeJson(payload);
    }

    /**
     * Serializes a PCEComputationResult into a detailed JSON string blob.
     */
    /**
     * The task ids a workload request names, for recording who asked for them.
     *
     * <p>Read from the request rather than the response so a caller claims exactly what it
     * submitted. Reading the response instead would leave a task the planner declined unowned,
     * and a later retry by a different tenant could claim it.
     */
    public static java.util.List<String> taskIdsIn(String workloadJsonBlob) {
        java.util.List<String> taskIds = new java.util.ArrayList<>();
        try {
            for (com.fasterxml.jackson.databind.JsonNode item
                    : OBJECT_MAPPER.readTree(workloadJsonBlob)) {
                String taskId = item.path("taskId").asText(null);
                if (taskId != null && !taskId.isBlank()) {
                    taskIds.add(taskId);
                }
            }
        } catch (IOException e) {
            // The request already parsed once to be solved, so this cannot normally fail. An
            // empty list means nothing is claimed, which loosens nothing that was not already
            // unowned.
            return java.util.List.of();
        }
        return taskIds;
    }

    public static String toResultJson(CRPEngine.PCEComputationResult result) {
        return toResultJson(result, null);
    }

    /**
     * Renders a solve result, including what became of each schedule's installation.
     *
     * <p>Without {@code installation} a 200 means only "the plan was computed and its capacity
     * reserved". It said nothing about whether the LSP reached a router, was never sent because
     * no PCC was connected, or was sent with an outcome the transport could not confirm — three
     * materially different situations a client had to discover by polling each task afterwards.
     *
     * @param installation per-task installation state after dispatch, or null when the caller has
     *                     no dispatch path wired
     */
    public static String toResultJson(CRPEngine.PCEComputationResult result,
                                      Map<String, String> installation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offeredFlowCount", result.getOfferedFlowCount());
        payload.put("committedFlowCount", result.getCommittedFlowCount());
        payload.put("unadmittedTaskCount", result.getUnadmittedTasks().size());
        payload.put("unadmittedTaskIds", result.getUnadmittedTasks().stream().map(WorkloadTask::getTaskId).toList());
        // Why each was declined. The bare id list is kept for callers that already parse it, but
        // "refused" alone conflates no route, no bandwidth and no buffer -- three different
        // problems with three different remedies.
        payload.put("unadmittedTasks", result.getUnadmittedTasks().stream().map(task -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", task.getTaskId());
            var cause = result.getRefusalCauses().get(task.getTaskId());
            item.put("reason", cause == null ? "UNSPECIFIED" : cause.name());
            return item;
        }).toList());
        payload.put("metDeadlineCount", result.getMetDeadlineCount());
        payload.put("flowAdmissionRatioPercent", result.getFlowAdmissionRatioPercent());
        payload.put("committedSuccessRatioPercent", result.getCommittedSuccessRatioPercent());
        payload.put("overallSuccessRatioPercent", result.getSuccessRatioPercent());
        payload.put("maxTransitBufferBytes", result.getMaxNetworkTransitBufferBytes());
        // Per node, against that node's capacity. The network-wide maximum above cannot say
        // whether one node is near exhaustion while the rest are idle, which is the distinction a
        // buffer claim rests on.
        payload.put("transitReservoirs", result.getReservoirUsage().stream().map(usage -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", usage.nodeId());
            item.put("peakBufferBytes", usage.peakBufferBytes());
            item.put("reservoirCapacityBytes", usage.reservoirCapacityBytes());
            item.put("utilisation", usage.utilisation());
            return item;
        }).toList());
        payload.put("totalEarlinessSec", result.getTotalEarlinessSec());
        payload.put("computationTimeMs", result.getComputationTimeMs());
        payload.put("stageTimingMicros", result.getStageTimingMicros());
        if (installation != null) {
            payload.put("installation", installation);
        }
        payload.put("committedSchedules", result.getCommittedSchedules().stream().map(schedule -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", schedule.getTask().getTaskId());
            if (installation != null) {
                item.put("installationState", installation.get(schedule.getTask().getTaskId()));
            }
            item.put("sourceNodeId", schedule.getTask().getSourceNodeId());
            item.put("destinationNodeId", schedule.getTask().getDestinationNodeId());
            item.put("committedRateBps", schedule.getCommittedRateBps());
            item.put("startSec", schedule.getStartSec());
            item.put("completionSec", schedule.getCompletionSec());
            item.put("metDeadline", schedule.isMetDeadline());
            // Per-schedule, not just the total. The total is summed only over flows that met
            // their deadline, so comparing it across arms that admitted different numbers of
            // flows compares two different populations. A distribution needs the individual
            // values -- and how much slack a schedule leaves is the quality difference between
            // two arms that both met every deadline.
            item.put("earlinessSec", schedule.getEarlinessSec());
            item.put("effectiveDeadlineSec", schedule.getTask().getEffectiveDeadlineSec());
            item.put("cosClass", schedule.getTask().getCosClass().name());
            item.put("peakTransitBufferBytes", schedule.getPeakTransitBufferBytes());
            item.put("hops", schedule.getHopSchedules().stream().map(hop -> {
                Map<String, Object> encoded = new LinkedHashMap<>();
                encoded.put("linkId", hop.getLink().getLinkId());
                encoded.put("sourceNodeId", hop.getLink().getSourceNodeId());
                encoded.put("destinationNodeId", hop.getLink().getDestinationNodeId());
                encoded.put("startSec", hop.getStartSec());
                encoded.put("endSec", hop.getEndSec());
                encoded.put("arrivalSec", hop.getArrivalSec());
                // Preserve every active interval. Aggregate start/end spans contact gaps and
                // cannot prove that transmission stayed inside a contact or within capacity.
                encoded.put("transmissionSlots", hop.getTransmissionSlots().stream()
                        .map(slot -> Map.of(
                                "startSec", slot.startSec(),
                                "endSec", slot.endSec()))
                        .toList());
                return encoded;
            }).toList());
            return item;
        }).toList());
        return writeJson(payload);
    }

    /**
     * Reads {@code {"observedBps": N}} from a link-capacity observation.
     *
     * <p>Rejects anything it cannot use rather than defaulting: an observation that silently
     * became zero, or that carried a field name nobody reads, would leave the controller planning
     * against a capacity the operator believes they corrected.
     */
    /**
     * Reads {@code {"resolution": "..."}}, rejecting anything else.
     *
     * <p>Strict for the same reason the capacity parser is: this releases reserved bandwidth on an
     * operator's word, so a body that does not say exactly what it means should fail rather than
     * be interpreted generously.
     */
    public static String parseResolution(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Request body cannot be empty.");
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON payload: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Invalid JSON payload: expected an object.");
        }
        java.util.Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!"resolution".equals(field)) {
                throw new IllegalArgumentException("Unrecognized field: " + field);
            }
        }
        JsonNode value = root.get("resolution");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "resolution is required and must name what was found on the router "
                            + "(currently only NOT_INSTALLED).");
        }
        return value.asText().trim();
    }

    public static double parseObservedCapacityBps(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Request body cannot be empty.");
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON payload: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Invalid JSON payload: expected an object.");
        }
        java.util.Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!"observedBps".equals(field)) {
                throw new IllegalArgumentException("Unrecognized field: " + field);
            }
        }
        JsonNode value = root.get("observedBps");
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("observedBps must be a JSON number.");
        }
        double observedBps = value.asDouble();
        if (!Double.isFinite(observedBps) || observedBps <= 0) {
            throw new IllegalArgumentException("observedBps must be finite and positive.");
        }
        return observedBps;
    }

    public static void saveResultToFile(CRPEngine.PCEComputationResult result, String filePath) throws IOException {
        Files.writeString(Path.of(filePath), toResultJson(result));
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize JSON", e);
        }
    }

    /**
     * Strictly parses a JSON array of WorkloadTask objects with full structural schema enforcement.
     */
    public static List<WorkloadTask> parseWorkloadJson(String jsonBlob) {
        if (jsonBlob == null || jsonBlob.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid JSON payload: request body cannot be empty.");
        }

        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(jsonBlob);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON payload: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Invalid JSON payload: expected an array of workload objects.");
        }
        if (root.isEmpty()) {
            throw new IllegalArgumentException("Invalid JSON payload: workload array cannot be empty.");
        }
        if (root.size() > MAX_WORKLOADS_PER_REQUEST) {
            throw new IllegalArgumentException(
                    "Invalid JSON payload: at most " + MAX_WORKLOADS_PER_REQUEST + " workloads are allowed.");
        }

        List<WorkloadTask> workloads = new ArrayList<>();
        for (int index = 0; index < root.size(); index++) {
            JsonNode item = root.get(index);
            if (!item.isObject()) {
                throw new IllegalArgumentException("Invalid WorkloadTask at index " + index + ": expected an object.");
            }
            rejectUnknownFields(item, index);

            String taskId = requiredText(item, "taskId", index);
            String source = requiredText(item, "sourceNodeId", index);
            String destination = requiredText(item, "destinationNodeId", index);
            double origination = requiredFiniteNumber(item, "originationTimeSec", index);
            double deadline = requiredFiniteNumber(item, "deadlineSec", index);
            double sizeBytes = requiredFiniteNumber(item, "taskSizeBytes", index);

            if (origination < 0) {
                throw fieldError(index, "originationTimeSec", "must be non-negative");
            }
            if (deadline <= origination) {
                throw fieldError(index, "deadlineSec", "must be strictly greater than originationTimeSec");
            }
            if (sizeBytes <= 0) {
                throw fieldError(index, "taskSizeBytes", "must be positive");
            }

            int priority = optionalPriority(item, index);
            WorkloadTask.ClassOfService cosClass = switch (priority) {
                case 2 -> WorkloadTask.ClassOfService.MEDIUM_SOFT_LAXITY;
                case 3 -> WorkloadTask.ClassOfService.LOW_BEST_EFFORT;
                default -> WorkloadTask.ClassOfService.STRICT_HARD_DEADLINE;
            };
            workloads.add(new WorkloadTask(
                    taskId, source, destination, origination, deadline, sizeBytes, cosClass));
        }
        return workloads;
    }

    public static List<WorkloadTask> parseWorkloadJsonFile(File file) throws IOException {
        String content = Files.readString(file.toPath());
        return parseWorkloadJson(content);
    }

    private static void rejectUnknownFields(JsonNode item, int index) {
        Iterator<String> fields = item.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!WORKLOAD_FIELDS.contains(field)) {
                throw fieldError(index, field, "is not recognized");
            }
        }
    }

    private static String requiredText(JsonNode item, String field, int index) {
        JsonNode value = item.get(field);
        if (value == null || !value.isTextual() || value.textValue().trim().isEmpty()) {
            throw fieldError(index, field, "must be a non-empty string");
        }
        return value.textValue().trim();
    }

    private static double requiredFiniteNumber(JsonNode item, String field, int index) {
        JsonNode value = item.get(field);
        if (value == null || !value.isNumber()) {
            throw fieldError(index, field, "must be a JSON number");
        }
        double result = value.doubleValue();
        if (!Double.isFinite(result)) {
            throw fieldError(index, field, "must be finite");
        }
        return result;
    }

    private static int optionalPriority(JsonNode item, int index) {
        JsonNode value = item.get("priority");
        if (value == null) {
            return 1;
        }
        if (!value.isIntegralNumber() || value.intValue() < 1 || value.intValue() > 3) {
            throw fieldError(index, "priority", "must be an integer in [1, 3]");
        }
        return value.intValue();
    }

    private static IllegalArgumentException fieldError(int index, String field, String reason) {
        return new IllegalArgumentException(
                "Invalid WorkloadTask at index " + index + ": " + field + " " + reason + ".");
    }
}
