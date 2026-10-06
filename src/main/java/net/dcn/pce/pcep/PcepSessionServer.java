package net.dcn.pce.pcep;

import net.dcn.pce.util.LogSanitizer;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A stateful PCE listener: accepts PCC sessions, delivers PCInitiate, and hands reports back.
 *
 * <p>Sessions are keyed on the peer's RFC 8232 SPEAKER-ENTITY-ID when it advertises one, so a
 * reconnecting PCC is recognised as itself and supersedes its own prior session. Without that, a
 * returning peer is indistinguishable from a new one and the LSPs it has already reported cannot
 * be re-associated with it — which is the association reconciliation depends on.
 *
 * <p>Sessions are bounded and a peer that does not complete the OPEN exchange within the handshake
 * timeout is dropped. A listener that accepted unbounded connections and let each hold a thread
 * until the dead timer expired would be a cheap denial of service on an unauthenticated port.
 *
 * <p>Without PCEPS (RFC 8253, enabled by {@code VORTEX_PCEP_TLS_KEYSTORE} and
 * {@code VORTEX_PCEP_TLS_TRUSTSTORE}) this transport carries no authentication or confidentiality.
 * It is then safe only on a trusted management network or bound to loopback, which is what the
 * Compose deployment does.
 */
public final class PcepSessionServer implements AutoCloseable {

    private static final Logger log = Logger.getLogger(PcepSessionServer.class.getName());

    private static final int COMMON_HEADER_LENGTH = 4;
    private static final int MSG_TYPE_OPEN = 1;
    private static final int MSG_TYPE_KEEPALIVE = 2;
    private static final int MSG_TYPE_PCERR = 6;
    private static final int MSG_TYPE_PCRPT = 10;
    private static final int PCEP_PROTOCOL_VERSION = 1;

    // RFC 5440 section 7.15 / IANA PCEP-ERROR registry.
    private static final int ERROR_SESSION_FAILURE = 1;
    private static final int ERROR_INVALID_OR_NON_OPEN = 1;   // Error-Type 1, value 1
    private static final int ERROR_VERSION_UNSUPPORTED = 8;   // Error-Type 1, value 8
    private static final int ERROR_UNKNOWN_OBJECT = 3;       // Error-Type 3
    private static final int ERROR_UNRECOGNISED_OBJECT_CLASS = 1;  // value 1
    private static final int ERROR_SECOND_SESSION = 9;       // Error-Type 9
    private static final int ERROR_SECOND_SESSION_ATTEMPT = 1;  // value 1

    /**
     * Highest PCEP Object-Class IANA has assigned (VENDOR-INFORMATION and the SR/association
     * objects sit at or below this). A mandatory object above it is one no conformant peer should
     * be sending, so it is the conservative line for "unrecognised": it never rejects a registered
     * object a real peer might legitimately include, and it catches the clearly out-of-range.
     */
    private static final int MAX_ASSIGNED_OBJECT_CLASS = 44;
    private static final int OBJECT_HEADER_LENGTH = 4;
    private static final int OBJECT_PROCESSING_FLAG = 0x02;   // the P flag, bit 1 of the second octet
    private static final int MAX_MESSAGE_LENGTH = 0xFFFF;

    /**
     * Names a single synchronisation may report before this side stops believing it.
     *
     * <p>The set is accumulated from an unauthenticated peer and is what reconciliation treats as
     * the complete picture of the network. A peer that never stops naming LSPs would grow it
     * without bound, so past this point the sync is abandoned rather than truncated: reconciling
     * against a partial view is how live capacity gets released.
     */
    private static final int MAX_SYNCHRONISED_LSPS = 65_536;

    private final int requestedPort;
    private final String speakerEntityId;
    private final int keepaliveSec;
    private final int deadTimerSec;
    private final int maxSessions;
    /**
     * Receives (reporting session key, report). The key is passed rather than dropped so the
     * installation layer can require a report to come from the PCC the operation was sent to.
     */
    private final BiConsumer<String, ReportedLsp> reportListener;
    private volatile BiConsumer<String, PcepErrorDecoder.PcepError> errorListener =
            (session, error) -> { };
    private volatile BiConsumer<String, Set<String>> synchronisationListener = (session, names) -> { };

    /**
     * Session keys this listener will accept, or empty to accept any peer.
     *
     * <p>Not authentication, and it must not be mistaken for it: a session key is derived from a
     * peer-supplied Speaker Entity Identifier or, failing that, the source address, and PCEP here
     * is plain TCP. An attacker who can reach the port and knows a permitted identity still gets
     * in, and one on the path can observe or forge anything.
     *
     * <p>What it does remove is the sharpest edge introduced by automatic dispatch. Selection is
     * "the single established session", so before this, whichever peer connected first -- or
     * superseded the real one by reusing its name -- became the device this controller programmed
     * paths onto. Naming the permitted peers means an unexpected one is refused at the handshake
     * instead of being handed LSPs. RFC 8253 transport is the real fix and is not this.
     */
    private volatile Set<String> permittedPeerAddresses = Set.of();

    /**
     * TLS with mutual certificate authentication, when the operator configured it.
     *
     * <p>With this set the allowlist is checked against the certificate subject the peer proved
     * possession of, not its source address. That is the difference between restricting where a
     * connection may come from and knowing who is at the other end.
     */
    private volatile PcepTransportSecurity transportSecurity;

    /**
     * Restricts which peers may establish a session.
     *
     * <p>Set before {@link #start}. An empty or null set accepts any peer, which is the behaviour
     * every deployment had before this existed.
     */
    /**
     * Restricts which source addresses may open a session. An empty set permits any peer.
     *
     * <p><strong>The address, not the speaker entity ID.</strong> An earlier version of this
     * checked the durable session key, which is {@code "speaker:" + <id>} whenever the peer
     * supplies one — and the peer supplies it, in its own OPEN message. Anything naming a
     * permitted speaker was admitted, from anywhere, and then superseded the genuine session for
     * that key. The control was bypassed by typing the right name, which is worse than having no
     * control at all, because its presence invites an operator to believe the listener is
     * guarded.
     *
     * <p>A source address is not authentication either: it is forgeable by anyone on-path or able
     * to occupy that address, and it is a network-layer control rather than a cryptographic one.
     * What it does provide is that an attacker must complete a TCP handshake from a permitted
     * address rather than merely assert a name. Real peer authentication uses PCEPS (RFC 8253)
     * with client certificates; enable it with {@link #withTransportSecurity}.
     */
    /**
     * Enables PCEPS (RFC 8253) on the listener.
     *
     * <p>Must be called before {@link #start(String)}: the transport is chosen when the socket is
     * created, and a listener already accepting plain TCP cannot retroactively have authenticated
     * the peers on it.
     */
    public synchronized PcepSessionServer withTransportSecurity(PcepTransportSecurity security) {
        if (running) {
            throw new IllegalStateException(
                    "PCEP listener is already running; TLS must be configured before it starts");
        }
        this.transportSecurity = security;
        return this;
    }

    /**
     * Receives errors a peer reports, so a refused request is learned from the peer rather than
     * from a timer.
     */
    public PcepSessionServer onError(BiConsumer<String, PcepErrorDecoder.PcepError> listener) {
        this.errorListener = listener == null ? (session, error) -> { } : listener;
        return this;
    }

    public PcepSessionServer onlyAccepting(Set<String> peerAddresses) {
        this.permittedPeerAddresses = peerAddresses == null ? Set.of() : Set.copyOf(peerAddresses);
        return this;
    }

    private final Map<String, Socket> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger threadSequence = new AtomicInteger();

    /**
     * Connections occupying a worker thread, whether handshaking or established.
     *
     * <p>The cap previously counted only established sessions, so peers could sit in the handshake
     * indefinitely without counting toward it: each held a thread, none was visible to the check,
     * and an unauthenticated port could be turned into an unbounded thread sink by never
     * completing an OPEN exchange. A permit is taken at accept and released on every exit path.
     */
    private final AtomicInteger occupiedPermits = new AtomicInteger();

    private volatile boolean running;
    private ServerSocket listener;
    private int boundPort = -1;

    public PcepSessionServer(
            int port, String speakerEntityId, BiConsumer<String, ReportedLsp> reportListener) {
        this(port, speakerEntityId, reportListener, 30, 120, 64);
    }

    public PcepSessionServer(
            int port, String speakerEntityId, BiConsumer<String, ReportedLsp> reportListener,
            int keepaliveSec, int deadTimerSec, int maxSessions) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be in [0, 65535]");
        }
        if (keepaliveSec < 1 || deadTimerSec <= keepaliveSec || deadTimerSec > 255) {
            throw new IllegalArgumentException("timers must satisfy 1 <= keepalive < deadTimer <= 255");
        }
        if (maxSessions < 1) {
            throw new IllegalArgumentException("maxSessions must be positive");
        }
        this.requestedPort = port;
        this.speakerEntityId = speakerEntityId;
        this.reportListener = reportListener == null ? (session, report) -> { } : reportListener;
        this.keepaliveSec = keepaliveSec;
        this.deadTimerSec = deadTimerSec;
        this.maxSessions = maxSessions;
    }

    /** Binds the listener and begins accepting sessions. */
    public synchronized void start(String bindAddress) throws IOException {
        if (running) {
            throw new IllegalStateException("PCEP listener is already running");
        }
        PcepTransportSecurity security = transportSecurity;
        listener = security == null ? new ServerSocket() : security.newServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(bindAddress, requestedPort));
        boundPort = listener.getLocalPort();
        running = true;

        Thread acceptor = new Thread(this::acceptLoop, "vortex-pcep-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        if (security == null) {
            // Said plainly at startup rather than left to be discovered. Everything downstream --
            // report processing, capacity release, automatic dispatch -- trusts what arrives on
            // this socket, and without TLS nothing here establishes who sent it.
            log.warning("PCEP listener bound to " + bindAddress + ":" + boundPort
                    + " WITHOUT transport security: peers are not authenticated. Configure PCEPS "
                    + "(RFC 8253) or keep this port on a trusted management network.");
        } else {
            log.info("PCEP listener bound to " + bindAddress + ":" + boundPort
                    + " with mutual TLS; peers must present a trusted client certificate");
        }
    }

    public int getBoundPort() {
        return boundPort;
    }

    /** Session keys with an established peer. */
    /**
     * Called when a PCC finishes RFC 8231 state synchronisation, with the names it reported.
     *
     * <p>Set before {@link #start}. Without it the end-of-synchronisation marker is decoded and
     * dropped, which is what the controller did for as long as reconciliation existed.
     */
    public PcepSessionServer onSynchronisationComplete(BiConsumer<String, Set<String>> listener) {
        this.synchronisationListener = listener == null ? (session, names) -> { } : listener;
        return this;
    }

    public Set<String> establishedSessions() {
        return Set.copyOf(sessions.keySet());
    }

    /**
     * What happened to a message handed to {@link #send}.
     *
     * <p>The two failures are not interchangeable, which is why this is not a boolean. A caller
     * that has durably recorded an operation before sending it can only undo that record if it
     * knows for certain nothing left the process. {@link #NO_SESSION} is that certainty;
     * {@link #WRITE_FAILED} is its opposite, because a write that throws part-way may already
     * have put bytes on the wire.
     */
    public enum SendOutcome {
        /** The bytes reached the socket. */
        SENT,
        /** No established session for that key. Nothing was written and nothing could have been. */
        NO_SESSION,
        /** A write was attempted and failed. Some of it may have reached the peer. */
        WRITE_FAILED
    }

    /** Sends one encoded message to an established peer. */
    public SendOutcome send(String sessionKey, byte[] message) {
        Socket peer = sessions.get(sessionKey);
        if (peer == null || peer.isClosed()) {
            return SendOutcome.NO_SESSION;
        }
        try {
            OutputStream out = peer.getOutputStream();
            synchronized (peer) {
                out.write(message);
                out.flush();
            }
            // Not recorded as outbound liveness: an extra KEEPALIVE is harmless, whereas missing
            // one is not, so the session loop keeps its own schedule rather than depending on
            // dispatch traffic that may stop at any time.
            return SendOutcome.SENT;
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to send to PCC " + sessionKey, e);
            closeSession(sessionKey, peer);
            return SendOutcome.WRITE_FAILED;
        }
    }

    private static String addressOf(Socket peer) {
        return peer.getInetAddress() == null ? "unknown" : peer.getInetAddress().getHostAddress();
    }

    /**
     * Whether this peer may open a session.
     *
     * <p>With TLS the allowlist names certificate subjects, which a peer cannot claim without the
     * matching private key. Without it the allowlist names source addresses, which is a weaker
     * control and is documented as such. The check is deliberately not "address or subject":
     * accepting either would let a plain-TCP peer bypass a certificate allowlist by connecting
     * from a permitted address.
     */
    /**
     * Address admission, decided on the accept loop because it costs nothing.
     *
     * <p>Only consulted when TLS is off. With TLS the allowlist names certificate subjects, and
     * an address entry must not also grant access — accepting either would let a peer bypass a
     * certificate allowlist by connecting from a permitted address.
     */
    private boolean isAddressPermitted(Socket peer) {
        Set<String> permitted = permittedPeerAddresses;
        return permitted.isEmpty() || permitted.contains(addressOf(peer));
    }

    /**
     * Certificate admission, decided on the session thread.
     *
     * <p>Deliberately not on the accept loop. Reading the peer's certificate requires the TLS
     * handshake to have completed, and asking for it drives the handshake — on the accept thread
     * that would serialise every connection behind one peer's negotiation, so a slow or
     * deliberately stalling client could hold the listener closed to everyone else.
     */
    private boolean isAuthenticatedPeerPermitted(Socket peer) {
        Set<String> permitted = permittedPeerAddresses;
        Optional<String> subject = PcepTransportSecurity.authenticatedPeer(peer);
        if (subject.isEmpty()) {
            return false;
        }
        return permitted.isEmpty() || permitted.contains(subject.get());
    }

    /** Durable identity for a session, matching the Python service's rule. */
    public static String sessionKeyFor(String peerAddress, String speakerEntityId) {
        if (speakerEntityId != null && !speakerEntityId.isBlank()) {
            return "speaker:" + speakerEntityId;
        }
        return "addr:" + peerAddress;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket peer = listener.accept();
                // Refused before a single PCEP byte is exchanged. There is no reason to run an
                // OPEN handshake with a host that can never be permitted, and refusing here means
                // an unpermitted peer never occupies a session permit either.
                if (transportSecurity == null && !isAddressPermitted(peer)) {
                    log.warning("Refusing PCEP connection from unpermitted address "
                            + LogSanitizer.singleLine(addressOf(peer)));
                    peer.close();
                    continue;
                }
                // Claim the permit before starting a thread, and check the claim rather than the
                // established count, so a peer that never finishes its handshake still occupies
                // exactly one slot.
                if (occupiedPermits.incrementAndGet() > maxSessions) {
                    occupiedPermits.decrementAndGet();
                    log.warning("Refusing PCC connection: session cap of " + maxSessions + " reached");
                    peer.close();
                    continue;
                }
                Thread worker = new Thread(() -> {
                    try {
                        runSession(peer);
                    } finally {
                        occupiedPermits.decrementAndGet();
                    }
                }, "vortex-pcep-" + threadSequence.incrementAndGet());
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (running) {
                    log.log(Level.FINE, "PCEP accept loop ended", e);
                }
                return;
            }
        }
    }

    /**
     * The dead-timer interval to apply to this peer, from the DeadTimer it advertised in its OPEN.
     *
     * <p>RFC 5440 s7.3: that field is how long the peer expects us to wait before declaring it
     * down, so it governs our detection of its silence. Two cases fall back to our own configured
     * dead timer: a peer advertising Keepalive 0 has said it will not send keepalives, and the RFC
     * requires its DeadTimer be ignored, so tearing it down for being quiet would be wrong; and a
     * value we cannot read from a short or malformed body leaves us nothing to honour.
     */
    private long negotiatedDeadTimerNanos(byte[] openPayload) {
        int peerKeepalive = OpenObject.keepaliveSec(openPayload);
        int peerDeadTimer = OpenObject.deadTimerSec(openPayload);
        if (peerKeepalive <= 0 || peerDeadTimer <= 0) {
            return TimeUnit.SECONDS.toNanos(deadTimerSec);
        }
        return TimeUnit.SECONDS.toNanos(peerDeadTimer);
    }

    /**
     * Best-effort PCErr on an error path. The peer may already be gone -- that is often why we are
     * here -- so a failure to write is swallowed; the caller closes the socket regardless.
     */
    private void trySendError(Socket peer, int errorType, int errorValue) {
        try {
            synchronized (peer) {
                peer.getOutputStream().write(PcepEncoder.pcerr(errorType, errorValue));
                peer.getOutputStream().flush();
            }
        } catch (IOException ignored) {
            // The connection is already unusable; nothing to add.
        }
    }

    private void runSession(Socket peer) {
        String peerAddress = addressOf(peer);
        String sessionKey = null;
        try {
            // The TLS handshake happens here, on this peer's own thread, and the certificate it
            // proved is what admission is checked against. Before any PCEP byte is exchanged: a
            // peer that cannot authenticate is never asked to open a session.
            if (transportSecurity != null && !isAuthenticatedPeerPermitted(peer)) {
                log.warning("Refusing PCEP session from " + LogSanitizer.singleLine(peerAddress)
                        + ": no permitted client certificate");
                return;
            }
            // Bounded until the peer proves itself: an idle connection must not hold a thread for
            // the full dead timer before anything is established.
            peer.setSoTimeout(Math.max(1, keepaliveSec) * 1000);
            peer.getOutputStream().write(
                    PcepEncoder.open(keepaliveSec, deadTimerSec, 1, speakerEntityId));
            peer.getOutputStream().flush();

            DataInputStream in = new DataInputStream(peer.getInputStream());
            Frame open;
            try {
                open = readFrame(in);
            } catch (MalformedFrameException malformed) {
                // A malformed first frame is a session establishment failure: RFC 5440 s7.15 puts
                // it under Error-Type 1, value 1. Answer before the connection drops so the peer
                // sees a protocol error rather than a bare reset it cannot tell from a crash.
                trySendError(peer, ERROR_SESSION_FAILURE, ERROR_INVALID_OR_NON_OPEN);
                throw malformed;
            }
            if (open.messageType != MSG_TYPE_OPEN) {
                // RFC 5440 s7.15: reception of a non-Open message here MUST draw Error-Type 1,
                // value 1. This previously threw straight to a silent close.
                trySendError(peer, ERROR_SESSION_FAILURE, ERROR_INVALID_OR_NON_OPEN);
                throw new IOException("first peer message must be OPEN");
            }
            // The version lives in the top three bits of the common header's first octet. The
            // OPEN object body carries one too; a conformant peer sets both to 1, and checking the
            // header catches the case a peer actually speaks a version we do not.
            int peerVersion = (open.wholeMessage[0] & 0xFF) >> 5;
            if (peerVersion != PCEP_PROTOCOL_VERSION) {
                // RFC 5440 IANA PCEP-ERROR registry: Error-Type 1, value 8, "PCEP version not
                // supported". Previously a version-2 Open was accepted without complaint.
                trySendError(peer, ERROR_SESSION_FAILURE, ERROR_VERSION_UNSUPPORTED);
                throw new IOException("unsupported PCEP version " + peerVersion);
            }
            String peerSpeaker = OpenObject.speakerEntityId(open.payload);
            // RFC 5440 s7.3: the peer's advertised DeadTimer is the interval WE apply to detect it
            // as dead, not our own configured value -- ours tells the peer how long to wait for us.
            long peerDeadTimerNanos = negotiatedDeadTimerNanos(open.payload);

            peer.getOutputStream().write(PcepEncoder.keepalive());
            peer.getOutputStream().flush();

            Frame ack = readFrame(in);
            if (ack.messageType != MSG_TYPE_KEEPALIVE) {
                throw new IOException("peer must acknowledge OPEN with KEEPALIVE");
            }

            // Admission was decided by source address at accept time. The speaker entity ID
            // still names the session durably, because a PCC must keep the same identity across
            // reconnects, but it decides nothing about who is allowed in.
            sessionKey = sessionKeyFor(peerAddress, peerSpeaker);
            Socket superseded = sessions.put(sessionKey, peer);
            if (superseded != null && superseded != peer) {
                // A speaker reconnecting replaces itself. Keeping both would leave messages going
                // to a dead socket while the live one sat unused.
                log.info("Superseding stale PCEP session for " + LogSanitizer.singleLine(sessionKey));
                closeQuietly(superseded);
            }
            log.info("PCEP session established with " + LogSanitizer.singleLine(sessionKey)
                    + " from " + LogSanitizer.singleLine(peerAddress));

            // Read in slices well shorter than the keepalive interval. The outbound schedule can
            // only be checked when this loop wakes, so a timeout equal to the interval let the
            // send drift by up to a full interval -- a capture against FRR's pathd showed a
            // 30-second keepalive going out at 33 seconds, and a peer configured with a tighter
            // dead timer than FRR's 4x default would have been entitled to tear the session down.
            // A third of the interval bounds the drift at a third rather than at double.
            // Wake often enough both to pace our own keepalives and to honour the peer's dead
            // timer: a read slice larger than the peer's dead timer would let a silent peer linger
            // past the interval it asked for.
            int deadTimerSliceSec =
                    (int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(peerDeadTimerNanos) / 2);
            int readSliceSec = Math.max(1, Math.min(Math.max(1, keepaliveSec / 3), deadTimerSliceSec));
            peer.setSoTimeout(readSliceSec * 1000);
            long lastHeardFromPeer = System.nanoTime();
            long lastSentToPeer = System.nanoTime();
            // Names this peer reports while synchronising, handed to reconciliation when it says
            // it is done. Per session: one PCC's account of the network says nothing about
            // another's.
            Set<String> synchronising = new java.util.LinkedHashSet<>();
            boolean synchronisationAbandoned = false;
            long keepaliveIntervalNanos = TimeUnit.SECONDS.toNanos(keepaliveSec);

            while (running && !peer.isClosed()) {
                try {
                    Frame frame = readFrame(in);
                    lastHeardFromPeer = System.nanoTime();
                    int unknownObjectClass = unrecognisedMandatoryObjectClass(frame.payload);
                    if (unknownObjectClass >= 0) {
                        // RFC 5440 s7.15: an unrecognised object with the P flag set MUST draw
                        // Error-Type 3, value 1. The session is kept: one unprocessable object is
                        // not grounds to tear down a peer that is otherwise conformant.
                        log.fine("PCEP peer " + LogSanitizer.singleLine(sessionKey)
                                + " sent an unrecognised mandatory object class " + unknownObjectClass);
                        trySendError(peer, ERROR_UNKNOWN_OBJECT, ERROR_UNRECOGNISED_OBJECT_CLASS);
                    } else if (frame.messageType == MSG_TYPE_OPEN) {
                        // RFC 5440 s7.15: a second OPEN on an established session MUST draw
                        // Error-Type 9, value 1, and the existing session MUST be preserved and the
                        // tentative second establishment silently ignored -- so this errors and
                        // continues rather than tearing anything down.
                        log.fine("PCEP peer " + LogSanitizer.singleLine(sessionKey)
                                + " sent a second OPEN on an established session");
                        trySendError(peer, ERROR_SECOND_SESSION, ERROR_SECOND_SESSION_ATTEMPT);
                    } else if (frame.messageType == MSG_TYPE_PCRPT) {
                        synchronisationAbandoned |=
                                dispatchReports(sessionKey, frame, synchronising, synchronisationAbandoned);
                    } else if (frame.messageType == MSG_TYPE_PCERR) {
                        for (PcepErrorDecoder.PcepError error
                                : PcepErrorDecoder.decode(frame.wholeMessage())) {
                            log.warning("PCEP peer " + LogSanitizer.singleLine(sessionKey)
                                    + " reported " + LogSanitizer.singleLine(error.describe()));
                            errorListener.accept(sessionKey, error);
                        }
                    }
                    // KEEPALIVE and anything else are read and ignored; a conformant peer may send
                    // messages this implementation has no use for.
                } catch (MalformedFrameException malformed) {
                    // RFC 5440 s7.15: a malformed message MUST draw Error-Type 1, value 1. This
                    // previously broke straight to a silent close, indistinguishable from a crash.
                    log.fine("PCEP session with " + LogSanitizer.singleLine(sessionKey)
                            + " sent a malformed frame: " + malformed.getMessage());
                    trySendError(peer, ERROR_SESSION_FAILURE, ERROR_INVALID_OR_NON_OPEN);
                    break;
                } catch (EOFException closed) {
                    // The peer closed the connection; there is nothing to answer.
                    break;
                } catch (SocketTimeoutException quiet) {
                    if (System.nanoTime() - lastHeardFromPeer >= peerDeadTimerNanos) {
                        log.fine("PCEP session with " + LogSanitizer.singleLine(sessionKey)
                                + " exceeded the peer's advertised dead timer");
                        break;
                    }
                }

                // Outbound liveness is timed from the last thing this side sent, not from receive
                // silence. Sending only on a read timeout meant a chatty peer -- one reporting or
                // keepaliving faster than the timeout -- stopped this branch from ever running,
                // so the PCE could stay silent past the negotiated interval and be torn down as
                // dead by a peer that was talking to it the whole time.
                if (System.nanoTime() - lastSentToPeer >= keepaliveIntervalNanos) {
                    synchronized (peer) {
                        peer.getOutputStream().write(PcepEncoder.keepalive());
                        peer.getOutputStream().flush();
                    }
                    lastSentToPeer = System.nanoTime();
                }
            }
        } catch (SocketTimeoutException timeout) {
            log.fine("PCEP session with " + LogSanitizer.singleLine(peerAddress)
                    + " timed out during its handshake");
        } catch (IOException | RuntimeException e) {
            log.log(Level.FINE, "PCEP session with " + LogSanitizer.singleLine(peerAddress)
                    + " ended", e);
        } finally {
            if (sessionKey != null) {
                closeSession(sessionKey, peer);
            } else {
                closeQuietly(peer);
            }
        }
    }

    /**
     * Applies one PCRpt, and signals reconciliation when the peer says synchronisation is done.
     *
     * @param synchronising names reported so far by this session, accumulated across messages
     * @param abandoned     whether this session's synchronisation has already been given up on
     * @return true when synchronisation must be abandoned from here on
     */
    private boolean dispatchReports(
            String sessionKey, Frame frame, Set<String> synchronising, boolean abandoned) {
        try {
            PcepReportDecoder.PcRpt decoded = PcepReportDecoder.decodePcRpt(frame.wholeMessage);
            for (ReportedLsp report : decoded.reports()) {
                if (report.synchronising() && !abandoned) {
                    if (synchronising.size() >= MAX_SYNCHRONISED_LSPS) {
                        log.warning("Abandoning state synchronisation for "
                                + LogSanitizer.singleLine(sessionKey)
                                + ": more than " + MAX_SYNCHRONISED_LSPS + " LSPs reported");
                        synchronising.clear();
                        abandoned = true;
                    } else {
                        synchronising.add(report.lspName());
                    }
                }
                reportListener.accept(sessionKey, report);
            }
            if (decoded.endOfSynchronisation() && !abandoned) {
                log.info("PCC " + LogSanitizer.singleLine(sessionKey)
                        + " completed state synchronisation with "
                        + synchronising.size() + " LSP(s)");
                synchronisationListener.accept(sessionKey, Set.copyOf(synchronising));
                synchronising.clear();
            }
        } catch (PcepDecodeException e) {
            // A malformed report from one peer must not take down the session or the listener.
            // It does invalidate the sync in progress: a message this side could not read may
            // have named LSPs, and reconciling as though it had not is how live capacity gets
            // released for an LSP that was reported.
            log.warning("Discarding unreadable report from " + LogSanitizer.singleLine(sessionKey)
                    + ": " + LogSanitizer.singleLine(e.getMessage()));
            synchronising.clear();
            return true;
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Report handler failed for "
                    + LogSanitizer.singleLine(sessionKey), e);
        }
        return abandoned;
    }

    private void closeSession(String sessionKey, Socket peer) {
        sessions.remove(sessionKey, peer);
        closeQuietly(peer);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already going away.
        }
    }

    /**
     * A frame whose common header cannot be parsed -- distinct from EOF (peer gone, nothing to
     * answer) and a socket timeout (quiet peer), so the caller can answer it with a PCErr before
     * closing rather than dropping the connection silently.
     */
    private static final class MalformedFrameException extends IOException {
        MalformedFrameException(String message) {
            super(message);
        }
    }

    /**
     * Reads {@code count} bytes that belong to a frame already begun. A timeout here is not a
     * quiet peer -- it announced a frame and stalled partway -- so it is surfaced as a malformed
     * frame the caller answers with a PCErr, rather than a silence the caller waits out. PCEP
     * frames are small and their bytes follow the header in the same TCP flight, so one read slice
     * is ample; a peer that cannot deliver the rest in that window is stalling.
     */
    private static void readCommitted(DataInputStream in, byte[] buffer, int offset, int count)
            throws IOException {
        try {
            in.readFully(buffer, offset, count);
        } catch (SocketTimeoutException stalled) {
            throw new MalformedFrameException(
                    "PCEP frame stalled after its header; " + count + " byte(s) never arrived");
        }
    }

    /**
     * The class of the first object carrying the P flag whose class IANA has not assigned, or -1.
     *
     * <p>RFC 5440 s7.15: a message carrying an object with the P (processing-rule) flag set that
     * the PCE does not recognise MUST draw a PCErr with Error-Type 3, value 1. The P flag is what
     * makes it mandatory to process; an unrecognised object with P clear is legitimately ignored,
     * so only P-flagged ones are reported. The walk is defensive: a malformed object structure
     * stops it rather than throwing, because a truly malformed frame is caught at the message layer
     * and answered as 1/1 there.
     */
    private static int unrecognisedMandatoryObjectClass(byte[] payload) {
        int offset = 0;
        while (offset + OBJECT_HEADER_LENGTH <= payload.length) {
            int objectClass = payload[offset] & 0xFF;
            boolean processingRequired = (payload[offset + 1] & OBJECT_PROCESSING_FLAG) != 0;
            int objectLength = ((payload[offset + 2] & 0xFF) << 8) | (payload[offset + 3] & 0xFF);
            if (objectLength < OBJECT_HEADER_LENGTH || offset + objectLength > payload.length) {
                return -1;   // malformed object framing; not our error to name here
            }
            if (processingRequired
                    && (objectClass == 0 || objectClass > MAX_ASSIGNED_OBJECT_CLASS)) {
                return objectClass;
            }
            offset += objectLength;
        }
        return -1;
    }

    private static Frame readFrame(DataInputStream in) throws IOException {
        byte[] header = new byte[COMMON_HEADER_LENGTH];
        // The first byte is read under the socket's own timeout, and a timeout waiting for it is
        // an ordinary quiet peer -- the caller uses it for liveness, not as an error. Everything
        // after it is read as "committed": once a frame has begun, a peer that announced a length
        // and then stalled is malformed, and blocking on the missing bytes indefinitely is the
        // difference between answering it and hanging with it. Previously readFully(header) made
        // no such distinction, so a truncated frame read as silence.
        int first = in.read();
        if (first < 0) {
            throw new EOFException("peer closed the connection before a frame began");
        }
        header[0] = (byte) first;
        readCommitted(in, header, 1, COMMON_HEADER_LENGTH - 1);
        int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (length < COMMON_HEADER_LENGTH || length > MAX_MESSAGE_LENGTH) {
            throw new MalformedFrameException("PCEP frame declares an impossible length " + length);
        }
        byte[] payload = new byte[length - COMMON_HEADER_LENGTH];
        readCommitted(in, payload, 0, payload.length);

        byte[] whole = new byte[length];
        System.arraycopy(header, 0, whole, 0, COMMON_HEADER_LENGTH);
        System.arraycopy(payload, 0, whole, COMMON_HEADER_LENGTH, payload.length);
        return new Frame(header[1] & 0xFF, payload, whole);
    }

    @Override
    public synchronized void close() {
        running = false;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
                // Nothing useful to do while shutting down.
            }
        }
        sessions.values().forEach(PcepSessionServer::closeQuietly);
        sessions.clear();
    }

    private record Frame(int messageType, byte[] payload, byte[] wholeMessage) {}

    /** Reads the peer's OPEN far enough to learn who it says it is. */
    private static final class OpenObject {
        private static final int TLV_SPEAKER_ENTITY_ID = 24;

        /** The Keepalive the peer advertises (seconds), or -1 if the payload is too short. */
        static int keepaliveSec(byte[] payload) {
            return payload.length >= 6 ? (payload[5] & 0xFF) : -1;
        }

        /**
         * The DeadTimer the peer advertises (seconds), or -1 if the payload is too short.
         *
         * <p>RFC 5440 section 7.3: this is the interval after which the peer may declare *us* down
         * if it hears nothing, so it is the value we must apply to detect the peer as dead -- not
         * our own configured dead timer, which describes how long the peer should wait for us.
         */
        static int deadTimerSec(byte[] payload) {
            return payload.length >= 7 ? (payload[6] & 0xFF) : -1;
        }

        static String speakerEntityId(byte[] payload) {
            if (payload.length < 8) {
                return null;
            }
            int objectLength = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
            if (objectLength > payload.length) {
                return null;
            }
            int offset = 8;
            while (offset + 4 <= objectLength) {
                int type = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
                int length = ((payload[offset + 2] & 0xFF) << 8) | (payload[offset + 3] & 0xFF);
                int padded = (length + 3) & ~3;
                if (offset + 4 + padded > objectLength) {
                    return null;
                }
                if (type == TLV_SPEAKER_ENTITY_ID && length > 0) {
                    return new String(payload, offset + 4, length, StandardCharsets.UTF_8);
                }
                offset += 4 + padded;
            }
            return null;
        }
    }

    /** The listener's own identity, for peers that track us across our restarts. */
    public Optional<String> speakerEntityId() {
        return Optional.ofNullable(speakerEntityId);
    }
}
