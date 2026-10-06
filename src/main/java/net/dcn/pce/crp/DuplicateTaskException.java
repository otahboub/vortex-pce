package net.dcn.pce.crp;

/** Raised when a request would create a second reservation for the same task identity. */
public class DuplicateTaskException extends IllegalArgumentException {
    public DuplicateTaskException(String taskId) {
        super("Task ID is already committed or repeated in this request: " + taskId);
    }
}
