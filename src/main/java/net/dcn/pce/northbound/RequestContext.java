package net.dcn.pce.northbound;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Ties every log line produced while serving one API request back to that request.
 *
 * <p>The solve path logged "Received Workload Task JSON Blob", then "Successfully computed N
 * LSPs", then a line per dispatch — with nothing connecting them. Reading a log after the fact,
 * there was no way to tell which dispatch belonged to which solve, which principal caused either,
 * or which of several interleaved requests a warning came from. Every line was true and the
 * sequence was unreconstructable.
 *
 * <p>A short id per request, carried on the serving thread and returned in {@code X-Request-Id} so
 * a caller reporting a problem can quote the thing that identifies it.
 *
 * <p><strong>Scope, deliberately narrow.</strong> This correlates the northbound request path
 * only. A PCEP report or error arrives on its own session thread with no originating request, and
 * inventing an id for it would suggest a link that does not exist — those are correlated by task
 * id and LSP name instead. Correlation across the Java and Python services does not exist either;
 * they share no request path.
 */
public final class RequestContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private RequestContext() {
    }

    /** Starts a request scope and returns its id. */
    public static String begin() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        String id = HEX.formatHex(bytes);
        CURRENT.set(id);
        return id;
    }

    /**
     * Ends the scope.
     *
     * <p>Must run in a {@code finally}: HTTP threads are pooled and reused, so an id left behind
     * would attach itself to whatever request the thread served next — worse than no correlation,
     * because it would be confidently wrong.
     */
    public static void end() {
        CURRENT.remove();
    }

    /** The current request id, or empty when not serving one. */
    public static String current() {
        String id = CURRENT.get();
        return id == null ? "" : id;
    }

    /** Prefixes a message with the current request id, or leaves it alone outside a request. */
    public static String tag(String message) {
        String id = CURRENT.get();
        return id == null ? message : "[req " + id + "] " + message;
    }
}
