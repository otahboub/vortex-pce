package net.dcn.pce.util;

/** Prevents untrusted protocol values from forging additional log lines. */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /** Returns a printable, single-line representation suitable for structured log messages. */
    public static String singleLine(Object value) {
        if (value == null) {
            return "null";
        }
        return value.toString()
                .replace('\r', '_')
                .replace('\n', '_')
                .replace('\u2028', '_')
                .replace('\u2029', '_');
    }
}
