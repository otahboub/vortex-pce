package net.dcn.pce.model;

/**
 * Represents a flow workload transfer request with Class of Service (CoS) and Admission Modes.
 * CoS Classes:
 * - STRICT_HARD_DEADLINE : Zero deadline violation permitted (Hard constraint)
 * - MEDIUM_SOFT_LAXITY   : Soft deadline permitted up to +20% time laxity
 * - LOW_BEST_EFFORT      : Best effort bulk transfer (No deadline constraint)
 *
 * Author: Dr. Omar Y. Tahboub
 */
public class WorkloadTask {

    public enum ClassOfService {
        STRICT_HARD_DEADLINE,
        MEDIUM_SOFT_LAXITY,
        LOW_BEST_EFFORT
    }

    private final String taskId;
    private final String sourceNodeId;
    private final String destinationNodeId;
    private final double originationTimeSec;
    private final double deadlineSec;
    private final double taskSizeBytes;
    private final ClassOfService cosClass;

    public WorkloadTask(String taskId, String sourceNodeId, String destinationNodeId,
                        double originationTimeSec, double deadlineSec, double taskSizeBytes) {
        this(taskId, sourceNodeId, destinationNodeId, originationTimeSec, deadlineSec, taskSizeBytes, ClassOfService.STRICT_HARD_DEADLINE);
    }

    public WorkloadTask(String taskId, String sourceNodeId, String destinationNodeId,
                        double originationTimeSec, double deadlineSec, double taskSizeBytes, ClassOfService cosClass) {
        if (taskId == null || taskId.isBlank() || sourceNodeId == null || sourceNodeId.isBlank()
                || destinationNodeId == null || destinationNodeId.isBlank()
                || !Double.isFinite(originationTimeSec) || originationTimeSec < 0
                || !Double.isFinite(deadlineSec) || deadlineSec <= originationTimeSec
                || !Double.isFinite(taskSizeBytes) || taskSizeBytes <= 0 || cosClass == null) {
            throw new IllegalArgumentException("Invalid workload task definition");
        }
        this.taskId = taskId.trim();
        this.sourceNodeId = sourceNodeId.trim();
        this.destinationNodeId = destinationNodeId.trim();
        this.originationTimeSec = originationTimeSec;
        this.deadlineSec = deadlineSec;
        this.taskSizeBytes = taskSizeBytes;
        this.cosClass = cosClass;
    }

    public String getTaskId() { return taskId; }
    public String getSourceNodeId() { return sourceNodeId; }
    public String getDestinationNodeId() { return destinationNodeId; }
    public double getOriginationTimeSec() { return originationTimeSec; }
    public double getDeadlineSec() { return deadlineSec; }
    public double getTaskSizeBytes() { return taskSizeBytes; }
    public double getTaskSizeBits() { return taskSizeBytes * 8.0; }
    public ClassOfService getCosClass() { return cosClass; }

    public int getPriority() {
        switch (cosClass) {
            case STRICT_HARD_DEADLINE: return 1;
            case MEDIUM_SOFT_LAXITY:   return 2;
            case LOW_BEST_EFFORT:
            default:                   return 3;
        }
    }

    public double getDemandedRateBps() {
        double dur = getEffectiveDeadlineSec() - originationTimeSec;
        if (dur <= 0) dur = 1.0;
        return getTaskSizeBits() / dur;
    }

    public double getEffectiveDeadlineSec() {
        switch (cosClass) {
            case MEDIUM_SOFT_LAXITY:
                return originationTimeSec + (deadlineSec - originationTimeSec) * 1.20; // +20% Time Laxity
            case LOW_BEST_EFFORT:
                return originationTimeSec + 86400.0; // 24 Hours Laxity
            case STRICT_HARD_DEADLINE:
            default:
                return deadlineSec;
        }
    }
}
