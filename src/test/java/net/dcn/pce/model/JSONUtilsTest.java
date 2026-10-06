package net.dcn.pce.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JSONUtilsTest {

    private static final String VALID = """
            [{
              "taskId": "W1",
              "sourceNodeId": "A",
              "destinationNodeId": "B",
              "originationTimeSec": 0,
              "deadlineSec": 100,
              "taskSizeBytes": 1024,
              "priority": 2
            }]
            """;

    @Test
    void parsesAValidTypedWorkload() {
        List<WorkloadTask> tasks = JSONUtils.parseWorkloadJson(VALID);

        assertEquals(1, tasks.size());
        assertEquals("W1", tasks.get(0).getTaskId());
        assertEquals(WorkloadTask.ClassOfService.MEDIUM_SOFT_LAXITY, tasks.get(0).getCosClass());
    }

    @Test
    void rejectsMalformedOrNonStructuralJson() {
        List<String> invalidPayloads = List.of(
                "[]",
                "{}",
                VALID + " trailing",
                "[1]",
                "[{\"taskId\":\"W1\"}{\"taskId\":\"W2\"}]",
                VALID.replace("1024", "\"1024\""),
                VALID.replace("\"priority\": 2", "\"priority\": 2, \"unknown\": true"),
                VALID.replace("\"taskId\": \"W1\"", "\"taskId\": \"W1\", \"taskId\": \"W2\"")
        );

        for (String payload : invalidPayloads) {
            assertThrows(IllegalArgumentException.class, () -> JSONUtils.parseWorkloadJson(payload), payload);
        }
    }

    @Test
    void validatesFieldDomains() {
        assertThrows(IllegalArgumentException.class,
                () -> JSONUtils.parseWorkloadJson(VALID.replace("\"A\"", "\"\"")));
        assertThrows(IllegalArgumentException.class,
                () -> JSONUtils.parseWorkloadJson(VALID.replace("\"deadlineSec\": 100", "\"deadlineSec\": 0")));
        assertThrows(IllegalArgumentException.class,
                () -> JSONUtils.parseWorkloadJson(VALID.replace("\"taskSizeBytes\": 1024", "\"taskSizeBytes\": -1")));
        assertThrows(IllegalArgumentException.class,
                () -> JSONUtils.parseWorkloadJson(VALID.replace("\"priority\": 2", "\"priority\": 4")));
    }

    @Test
    void boundsTheNumberOfWorkloadsPerRequest() {
        String oneTask = VALID.substring(1, VALID.length() - 2).trim();
        String oversized = "[" + String.join(",", IntStream.range(0, 1_001)
                .mapToObj(index -> oneTask.replace("\"W1\"", "\"W" + index + "\""))
                .toList()) + "]";

        assertThrows(IllegalArgumentException.class, () -> JSONUtils.parseWorkloadJson(oversized));
    }
}
