package net.dcn.pce.northbound;

import net.dcn.pce.util.LogSanitizer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.dcn.pce.BuildInfo;
import net.dcn.pce.config.OperatorConfiguration;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.crp.DuplicateTaskException;
import net.dcn.pce.crp.SolveCancellation;
import net.dcn.pce.crp.SolveTimeoutException;
import net.dcn.pce.metrics.ControllerMetrics;
import net.dcn.pce.model.BaseTopology;
import net.dcn.pce.model.JSONUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hardened Embedded HTTP REST & Health Check Server for VortexPCE Controller.
 * Provides scheduling and cancellation REST endpoints, health checks, and runtime metrics.
 * Enforces 10MB payload limits, mandatory token authentication, and security headers.
 */
public class PCERestServer {

    private static final Logger log = Logger.getLogger(PCERestServer.class.getName());
    private static final int MAX_PAYLOAD_BYTES = 10 * 1024 * 1024; // 10MB Max Body Size Limit
    private static final int DEFAULT_HTTP_THREADS = 4;
    private static final int DEFAULT_QUEUE_CAPACITY = 64;
    private final int port;
    private final PCERestController controller;
    private final int httpThreads;
    private final int queueCapacity;
    private final OperatorConfiguration effectiveConfiguration;
    private final AtomicBoolean plannerBusy = new AtomicBoolean(false);
    /**
     * Fair, bounded-wait admission to the transactional planner.
     *
     * <p>The planner and reservation ledgers remain strictly single-flight. Previously callers
     * raced an atomic flag and every loser received 503 immediately, turning one short queue into
     * a client-side retry storm. HTTP admission is already bounded by {@link #queueCapacity}; this
     * lock lets admitted workers wait in arrival order while retaining a finite overload path.
     */
    private final ReentrantLock plannerAdmission = new ReentrantLock(true);
    /**
     * Tokens for solves currently in progress, so shutdown can ask them to stop. Planning is
     * single-flight, so this holds at most one entry; a set keeps that an invariant rather than
     * an assumption.
     */
    private final java.util.Set<SolveCancellation> inFlightSolves =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicInteger threadSequence = new AtomicInteger();
    private final SolveQuota solveQuota;
    private HttpServer server;
    private ThreadPoolExecutor requestExecutor;
    private volatile boolean started;

    /**
     * The southbound listener, when the operator asked for it.
     *
     * <p>Off unless {@code VORTEX_PCEP_LISTENER=JAVA}, so a deployment asks for it rather than
     * acquiring it by upgrading. Reports received here update installation intent.
     *
     * <p>Committed schedules are dispatched automatically when
     * {@code VORTEX_PCEP_AUTO_INSTALL=SINGLE_PCC} names a policy that can identify a target;
     * with no policy configured, or when selection is ambiguous, nothing is sent. Which PCC owns
     * a given task remains an unresolved policy question beyond the single-session
     * case, and guessing it would install paths on a router nobody chose.
     *
     * <p>The loop is confirmed outside this repository: an enforcing CI gate has FRRouting
     * {@code pathd} establish a session, synchronise, accept the PCInitiate, and create the
     * resulting LSP. It is not confirmed end to end — that fixture has no forwarding plane.
     */
    private net.dcn.pce.pcep.PcepSessionServer pcepListener;
    private net.dcn.pce.install.InstallationCoordinator installationCoordinator;
    private java.util.concurrent.ScheduledExecutorService acknowledgementSweeper;

    /** The single leader-guarded exit for every dispatched PCEP frame; see {@link GuardedPcepDispatch}. */
    private GuardedPcepDispatch guardedDispatch;

    /** The Postgres leadership this instance drives, or null on the file-WAL (always-active) path. */
    private final net.dcn.pce.rib.PostgresLeadership leadership;
    private net.dcn.pce.rib.LeadershipLifecycle leadershipLifecycle;

    /**
     * True once this instance's promotion callback has fully run — capacity refreshed and, if
     * configured, the listener open. The leadership flag alone is not enough: the lifecycle flips it
     * true before it runs {@code onPromote}, so a solve arriving in that window could plan against
     * capacity this instance has not yet refreshed. Gating solves on this instead closes the window.
     */
    private volatile boolean servingReady = false;

    public PCERestServer(int port, BaseTopology topology) {
        this(port, topology, OperatorConfiguration.fromProcessEnvironment());
    }

    /** Starts the server under an explicit operator configuration. */
    public PCERestServer(int port, BaseTopology topology, OperatorConfiguration configuration) {
        this(port, buildController(topology, configuration), configuredLegacyApiKey(),
                DEFAULT_HTTP_THREADS, DEFAULT_QUEUE_CAPACITY);
    }

    private PCERestServer(int port, ControllerBundle bundle, String configuredApiKey,
                          int httpThreads, int queueCapacity) {
        this(port, bundle.controller(), configuredApiKey, httpThreads, queueCapacity,
                bundle.configuration(), bundle.leadership());
    }

    PCERestServer(
            int port,
            PCERestController controller,
            String configuredApiKey,
            int httpThreads,
            int queueCapacity) {
        this(port, controller, configuredApiKey, httpThreads, queueCapacity,
                OperatorConfiguration.parse(java.util.Map.of()));
    }

    PCERestServer(
            int port,
            PCERestController controller,
            String configuredApiKey,
            int httpThreads,
            int queueCapacity,
            OperatorConfiguration effectiveConfiguration) {
        this(port, controller, configuredApiKey, httpThreads, queueCapacity, effectiveConfiguration,
                null);
    }

    PCERestServer(
            int port,
            PCERestController controller,
            String configuredApiKey,
            int httpThreads,
            int queueCapacity,
            OperatorConfiguration effectiveConfiguration,
            net.dcn.pce.rib.PostgresLeadership leadership) {
        if (port < 0 || port > 65_535 || controller == null
                || httpThreads < 2 || queueCapacity < 1 || effectiveConfiguration == null) {
            throw new IllegalArgumentException("Invalid HTTP server configuration");
        }
        this.leadership = leadership;
        this.port = port;
        this.controller = controller;
        this.httpThreads = httpThreads;
        this.queueCapacity = queueCapacity;
        this.effectiveConfiguration = effectiveConfiguration;
        this.principals = net.dcn.pce.config.ApiPrincipals.parse(
                effectiveConfiguration.getApiPrincipals(), configuredApiKey);
        if (principals.isEmpty()) {
            throw new IllegalArgumentException("At least one of VORTEX_API_KEY or "
                    + "VORTEX_API_PRINCIPALS must be configured before starting the HTTP API");
        }
        this.solveQuota = effectiveConfiguration.getApiSolveQuotaPerMinute() > 0
                ? new SolveQuota(effectiveConfiguration.getApiSolveQuotaPerMinute(),
                        java.time.Clock.systemUTC())
                : null;
    }

    private static String configuredLegacyApiKey() {
        return System.getenv("VORTEX_API_KEY");
    }

    /** A controller plus, for the Postgres backend, the leadership its failover lifecycle drives. */
    /** The controller, its leadership lease if any, and the configuration bound to its regime. */
    private record ControllerBundle(PCERestController controller,
                                    net.dcn.pce.rib.PostgresLeadership leadership,
                                    OperatorConfiguration configuration) {
    }

    private static ControllerBundle buildController(
            BaseTopology topology, OperatorConfiguration operatorConfiguration) {
        if (topology == null) {
            throw new IllegalArgumentException("topology is required");
        }
        if (operatorConfiguration == null) {
            throw new IllegalArgumentException("operator configuration is required");
        }
        // Resolve an unset route policy for this topology's regime, so the engine and
        // /api/v1/config both carry the concrete policy in force.
        OperatorConfiguration configuration = operatorConfiguration.forRegime(topology.getRegime());

        // Reject a policy that cannot produce routes under this topology's regime before the
        // controller accepts any request, rather than reporting every workload as unadmitted.
        configuration.validateForRegime(topology.getRegime());

        CRPEngine engine = configuration.applyTo(new CRPEngine().withPersistentState(true));
        net.dcn.pce.rib.PostgresLeadership leadership = null;
        String url = null;
        String user = null;
        String password = null;
        if (configuration.isPostgresBackend()) {
            // Shared transactional backend (ADR-0001). Leadership is not taken here: the failover
            // lifecycle (started in start()) acquires it, renews it, and demotes on loss. Until it
            // is leader this instance is a standby -- it can restore and read, but the store refuses
            // writes (its term is not current) and the listener stays closed.
            url = configuration.getPostgresUrl().orElseThrow();
            user = configuration.getPostgresUser().orElse(null);
            password = configuration.getPostgresPassword().orElse(null);
            leadership = new net.dcn.pce.rib.PostgresLeadership(url, user, password, 30_000);
            log.info("Reservation backend: Postgres (leadership acquired by the failover lifecycle)");
            engine.withReservationStore(
                    new net.dcn.pce.rib.PostgresReservationStore(url, user, password, leadership));
        } else {
            configuration.getStatePath().ifPresent(engine::withDurableState);
        }
        engine.validateLedgers(topology);

        configuration.describe().forEach((key, value) ->
                log.info(String.format("Operator Config: %s = %s", key, value)));
        PCERestController controller = new PCERestController(engine, topology);
        // Observations follow the reservation state: a deployment durable enough to remember its
        // commitments should remember the capacities those commitments were admitted against.
        if (configuration.isPostgresBackend()) {
            // In the shared database and fenced by the same leadership term, so a
            // promoted standby restores corrected capacities and cannot plan against a stale
            // topology. Pending dispatch is reconstructed from the shared reservations, so no
            // separate outbox is wired here.
            controller.withDurableObservedCapacity(
                    new net.dcn.pce.topology.PostgresCapacityStore(url, user, password, leadership));
        } else {
            configuration.getStatePath().ifPresent(statePath -> {
                controller.withDurableObservedCapacity(
                        net.dcn.pce.topology.ObservedCapacityStore.besideState(statePath));
                controller.withDurablePendingDispatch(
                        net.dcn.pce.install.PendingDispatchStore.besideState(statePath));
            });
        }
        return new ControllerBundle(controller, leadership, configuration);
    }


    /**
     * Creates the API server, over TLS when a keystore is configured.
     *
     * <p>Plain HTTP puts {@code X-API-Key} on the wire in cleartext on every request — the single
     * credential authorising solving, cancellation and capacity mutation. Comparing it in
     * constant time protects nothing if it was readable in transit.
     *
     * <p>Off by default, because turning it on requires a certificate every existing deployment
     * predates, and the Compose deployment publishes the port to loopback only. When it is off the
     * server says so at startup rather than leaving it to be discovered.
     */
    private HttpServer createServer() throws IOException {
        java.util.Optional<String> keyStore = effectiveConfiguration.getApiTlsKeyStore();
        if (keyStore.isEmpty()) {
            log.warning("Northbound API on plain HTTP: the API key crosses the network in "
                    + "cleartext. Set " + OperatorConfiguration.ENV_API_TLS_KEYSTORE
                    + " or keep this port on a trusted network.");
            return HttpServer.create(new InetSocketAddress(port), 0);
        }
        javax.net.ssl.SSLContext context;
        try {
            char[] password = effectiveConfiguration.getApiTlsKeyStorePassword();
            java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in =
                         java.nio.file.Files.newInputStream(java.nio.file.Path.of(keyStore.get()))) {
                store.load(in, password);
            }
            javax.net.ssl.KeyManagerFactory keyManagers =
                    javax.net.ssl.KeyManagerFactory.getInstance(
                            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(store, password);
            context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);
        } catch (java.security.GeneralSecurityException | IOException e) {
            // IOException as well as GeneralSecurityException: KeyStore.load reports a corrupt or
            // wrong-format file as an IOException, and catching only the security exceptions left
            // that surfacing as a bare failure with no message. Fail closed either way -- an
            // operator who configured TLS and silently got cleartext has the worst outcome: the
            // belief that the credential is protected, and none of the protection.
            throw new IOException("Northbound TLS is configured but could not be initialised from "
                    + keyStore.get() + "; refusing to serve the API in cleartext instead", e);
        }

        com.sun.net.httpserver.HttpsServer https =
                com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress(port), 0);
        https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(context));
        log.info("Northbound API serving HTTPS");
        return https;
    }

    public synchronized void start() throws IOException {
        if (started) {
            throw new IllegalStateException("HTTP server is already running");
        }
        try {
            server = createServer();

            server.createContext("/healthz", new LivenessHandler());
            server.createContext("/livez", new LivenessHandler());
            server.createContext("/readyz", new ReadinessHandler());
            server.createContext("/metrics", new MetricsHandler());
            server.createContext("/clusterz", new ClusterStatusHandler());
            server.createContext("/api/v1/solve", new SolveHandler());
            server.createContext("/api/v1/tasks", new TaskHandler());
            server.createContext("/api/v1/config", new ConfigHandler());
            server.createContext("/api/v1/links", new LinkCapacityHandler());

            requestExecutor = new ThreadPoolExecutor(
                    httpThreads,
                    httpThreads,
                    30L,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    runnable -> {
                        Thread thread = new Thread(
                                runnable, "vortex-http-" + threadSequence.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            server.setExecutor(requestExecutor);
            server.start();
            started = true;
            int boundPort = server.getAddress().getPort();
            startPcepListenerIfConfigured();
            startFailoverLifecycleIfLeadershipBacked();
            log.info(String.format(
                    "VortexPCE HTTP REST Server listening on http://0.0.0.0:%d", boundPort));
            System.out.printf(
                    "    [+] Live HTTP REST Server running on http://localhost:%d\n", boundPort);
        } catch (IOException | RuntimeException failure) {
            // HTTP is externally visible before the southbound listener and failover lifecycle
            // finish. A later startup failure must roll the whole controller back; otherwise
            // liveness and readiness remain green for a half-started process.
            stop();
            throw failure;
        }
    }

    /**
     * When the Postgres backend is in use, drive leadership over time: acquire it, keep it with a
     * heartbeat, open the PCEP listener on promotion, and close it on demotion. A standby stays up
     * (it can restore and answer read-only requests) but neither listens nor admits until it leads.
     */
    private void startFailoverLifecycleIfLeadershipBacked() {
        if (leadership == null) {
            return;
        }
        leadershipLifecycle = new net.dcn.pce.rib.LeadershipLifecycle(
                leadership, 10_000, this::openPcepListener, this::closePcepListener);
        leadershipLifecycle.start();
    }

    /**
     * Whether this instance may admit and dispatch: always on the file WAL; on Postgres only while
     * the lease is still <em>locally</em> live. Using {@code leaseLocallyLive()} rather than
     * {@code isLeader()} makes admission fail closed the instant a stop-the-world pause outruns the
     * lease — before the next heartbeat notices — so a resumed-but-superseded leader cannot admit
     * against state a successor already owns.
     */
    private boolean isActiveLeader() {
        return leadershipLifecycle == null || leadershipLifecycle.leaseLocallyLive();
    }

    /** Whether every subsystem the operator made mandatory is actually serving. */
    private boolean requiredSubsystemsReady() {
        if (leadership != null) {
            // A shared-backend standby may answer read-only operational endpoints, but it cannot
            // admit and must not be placed in the serving pool until promotion has refreshed
            // capacity and opened the configured listener.
            return isActiveLeader() && servingReady;
        }
        if (!effectiveConfiguration.isJavaPcepListenerEnabled()) {
            return true;
        }
        return servingReady && pcepListener != null && pcepListener.getBoundPort() > 0;
    }

    /** Starts the southbound listener when configured, wired to this controller's intents. */
    private void startPcepListenerIfConfigured() throws IOException {
        if (!effectiveConfiguration.isJavaPcepListenerEnabled()) {
            return;
        }
        installationCoordinator = new net.dcn.pce.install.InstallationCoordinator(
                controller.getIntentLedger(),
                java.time.Clock.systemUTC(),
                java.time.Duration.ofSeconds(effectiveConfiguration.getPcepAckTimeoutSec()));
        pcepListener = new net.dcn.pce.pcep.PcepSessionServer(
                effectiveConfiguration.getPcepPort(),
                "vortex-" + BuildInfo.version(),
                // Routed through the engine's transaction rather than applied to the ledger
                // directly. A report that changed only memory would be lost on restart, and a
                // confirmed removal would retire the intent while its reservations stayed
                // committed -- the ledger and the intent disagreeing about held bandwidth.
                (sessionKey, report) -> controller.applyInstallationReport(
                        () -> installationCoordinator.applyAndReturn(sessionKey, report)),
                // Keepalive and dead timer keep their defaults; the session cap is operator-tunable
                // (VORTEX_PCEP_MAX_SESSIONS), defaulting to the historical 64.
                30, 120, effectiveConfiguration.getPcepMaxSessions());
        // Every dispatched frame -- install and removal -- reaches a PCC through this one guard, so
        // the leadership check is defined once and is the last thing before the socket write.
        // The transport reads the current listener each call: it is null only while
        // demoted, and the guard fails closed before we get here in that case.
        guardedDispatch = new GuardedPcepDispatch(
                this::isActiveLeader,
                (owner, frame) -> {
                    net.dcn.pce.pcep.PcepSessionServer listener = pcepListener;
                    return listener == null
                            ? net.dcn.pce.pcep.PcepSessionServer.SendOutcome.NO_SESSION
                            : listener.send(owner, frame);
                },
                installationCoordinator::armAcknowledgementDeadline);
        // Cancellation of an installed LSP now reaches the router that owns it. Dispatch of new
        // installs stays manual: it needs a policy for choosing a PCC, whereas a removal already
        // knows its target -- the intent records which session installed it.
        controller.setRemovalDispatcher((intent, srpId) -> {
            String owner = intent.getPccSessionKey().orElse(null);
            Long plspId = intent.getPlspId().orElse(null);
            if (owner == null || plspId == null) {
                return net.dcn.pce.crp.CRPEngine.DispatchOutcome.NOT_ATTEMPTED;
            }
            byte[] frame = net.dcn.pce.pcep.PcepEncoder.pcInitiateRemoval(
                    srpId, plspId, intent.getLspName());
            return guardedDispatch.dispatch(owner, frame, intent.getTaskId());
        });
        // RFC 8231 state synchronisation. Until the decoder stopped discarding the end-of-sync
        // marker this never fired against a real PCC, so reconciliation -- whose entire premise
        // is that the report is complete -- had no way to run outside its unit tests.
        pcepListener.onSynchronisationComplete((sessionKey, reportedNames) -> {
            var findings = installationCoordinator.planReconciliation(sessionKey, reportedNames, true);
            for (var finding : findings) {
                // One transaction per finding. A crash part-way leaves the intents it already
                // reconciled durable and the rest untouched, and the next synchronisation redoes
                // them; batching them into one would mean either all or none survive a restart.
                controller.applyInstallationReport(() -> installationCoordinator.applyFinding(finding));
            }
            // A PCC has just become available, so anything planned while none was is dispatched
            // now. Reconciliation above owns the intents that already reached a router; this owns
            // the ones that never did.
            for (String outcome : controller.dispatchPending()) {
                log.info("Pending install dispatch: " + LogSanitizer.singleLine(outcome));
            }
            findings.stream()
                    .filter(finding -> finding.action() != net.dcn.pce.install.Reconciliation.Action.AGREE)
                    .forEach(finding -> log.info("Reconciliation against "
                            + LogSanitizer.singleLine(sessionKey) + ": "
                            + LogSanitizer.singleLine(finding.action()) + " "
                            + LogSanitizer.singleLine(finding.lspName()) + " -- "
                            + LogSanitizer.singleLine(finding.detail())));
        });
        // Nothing ran the acknowledgement clock. expireOverdueAcknowledgements() existed, was
        // tested, and had no caller outside those tests, so an operation that was never answered
        // sat in INSTALLING or DELETING forever, holding capacity, and the configured
        // VORTEX_PCEP_ACK_TIMEOUT_SEC described a behaviour that did not happen. UNCERTAIN is the
        // state whose whole purpose is to say "this may be installed and we cannot confirm it";
        // reaching it has to be driven by something.
        long sweepSeconds = Math.max(1, effectiveConfiguration.getPcepAckTimeoutSec() / 4);
        acknowledgementSweeper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "vortex-ack-sweeper");
                    thread.setDaemon(true);
                    return thread;
                });
        acknowledgementSweeper.scheduleWithFixedDelay(
                this::expireOverdueAcknowledgements, sweepSeconds, sweepSeconds,
                TimeUnit.SECONDS);

        // Automatic installation, when the operator has asked for it. Selection is deliberately
        // the narrowest policy that is defensible: dispatch only while exactly one PCC session is
        // established. None means there is nowhere to send; several means there is no basis for
        // choosing, and picking arbitrarily would install a client's traffic on a router nobody
        // nominated.
        if (effectiveConfiguration.isAutoInstallEnabled()) {
            controller.setInstallDispatch(
                    () -> {
                        java.util.Set<String> sessions = pcepListener.establishedSessions();
                        return sessions.size() == 1
                                ? java.util.Optional.of(sessions.iterator().next())
                                : java.util.Optional.empty();
                    },
                    (intent, srpId, route, rateBps) -> {
                        String owner = intent.getPccSessionKey().orElse(null);
                        if (owner == null) {
                            return net.dcn.pce.crp.CRPEngine.DispatchOutcome.NOT_ATTEMPTED;
                        }
                        net.dcn.pce.pcep.ScheduleInitiate.Result encoded =
                                net.dcn.pce.pcep.ScheduleInitiate.encode(
                                        route, rateBps, controller.getActiveTopology(), srpId,
                                        intent.getLspName());
                        if (!encoded.isEncoded()) {
                            // A schedule the controller cannot address is refused before anything
                            // is sent, and the reason names the node rather than the symptom.
                            log.warning("Not dispatching " + intent.getTaskId() + ": "
                                    + encoded.refusal());
                            return net.dcn.pce.crp.CRPEngine.DispatchOutcome.NOT_ATTEMPTED;
                        }
                        // Guard and send through the one dispatch point. The lease is re-checked as
                        // the last step before the write, so a lease lost between admission and here
                        // (e.g. a pause that outran it) fails closed: the intent stays PLANNED and
                        // durable and the successor inherits it.
                        return guardedDispatch.dispatch(
                                owner, encoded.frame(), intent.getTaskId());
                    });
        }

        if (effectiveConfiguration.isPcepTlsEnabled()) {
            try {
                pcepListener.withTransportSecurity(
                        net.dcn.pce.pcep.PcepTransportSecurity.fromKeyStores(
                                java.nio.file.Path.of(
                                        effectiveConfiguration.getPcepTlsKeyStore().orElseThrow()),
                                effectiveConfiguration.getPcepTlsKeyStorePassword(),
                                java.nio.file.Path.of(
                                        effectiveConfiguration.getPcepTlsTrustStore().orElseThrow()),
                                effectiveConfiguration.getPcepTlsTrustStorePassword()));
            } catch (java.security.GeneralSecurityException e) {
                // Fail closed. An operator who configured PCEPS and got a plain-TCP listener
                // because a store would not load has the weakest possible outcome: the belief
                // that peers are authenticated, and none of the protection.
                throw new IOException("PCEP TLS is configured but could not be initialised; "
                        + "refusing to start an unauthenticated listener in its place", e);
            }
        }
        // A refused request is learned from the peer that refused it, rather than by waiting out
        // an acknowledgement deadline for an answer that already arrived.
        pcepListener.onError((sessionKey, error) -> error.srpId().ifPresent(srpId ->
                controller.applyInstallationReport(
                        () -> installationCoordinator.applyPeerError(sessionKey, srpId)
                                .orElse(null))));
        pcepListener.onlyAccepting(effectiveConfiguration.getPcepPermittedPeers());
        // Under leadership the listener opens only when this instance is the leader (see start()'s
        // failover lifecycle); on the file-WAL path it is always active, so open it now.
        if (leadership == null) {
            openPcepListener();
        }
    }

    /** Opens the built listener. The leader-gated entry point for the failover lifecycle. */
    private synchronized void openPcepListener() {
        // On promotion, re-read durable capacity before serving so a standby that started before the
        // former leader's latest corrections does not plan against a stale topology
        // (failover staleness). Only meaningful on the shared backend, where another instance writes;
        // the file-WAL path is its own sole writer and has nothing newer to read.
        if (leadership != null) {
            controller.refreshDurableObservedCapacity();
        }
        if (pcepListener != null) {
            try {
                pcepListener.start(effectiveConfiguration.getPcepBindAddress());
            } catch (IOException e) {
                throw new IllegalStateException("could not open the PCEP listener", e);
            }
            log.info(String.format("Southbound PCEP listener on %s:%d; reports update installation "
                            + "intent, cancellation dispatches removals, automatic install is %s",
                    effectiveConfiguration.getPcepBindAddress(), pcepListener.getBoundPort(),
                    effectiveConfiguration.getAutoInstallMode()));
        }
        // Capacity is fresh and (if configured) the listener is up: only now may this instance admit.
        servingReady = true;
    }

    /** Closes the listener on demotion so a standby does not hold PCC sessions it must not serve. */
    private synchronized void closePcepListener() {
        // Stop admitting first: a demoted instance must refuse solves before it tears down, not
        // during. Paired with the promote gate, a solve is served only while this instance is both
        // the leader and past its promotion refresh.
        servingReady = false;
        if (pcepListener != null) {
            pcepListener.close();
            log.warning("Demoted: PCEP listener closed; this instance is now a standby");
        }
    }

    /**
     * Moves operations past their acknowledgement deadline to {@code UNCERTAIN}.
     *
     * <p>One durable transaction per task rather than one for the sweep: a crash part-way leaves
     * the tasks it already expired durable and the rest to the next pass. Capacity is deliberately
     * retained -- a missing acknowledgement is not evidence the LSP is absent.
     */
    private void expireOverdueAcknowledgements() {
        try {
            for (String taskId : installationCoordinator.overdueAcknowledgements()) {
                controller.applyInstallationReport(
                        () -> installationCoordinator.expireAcknowledgement(taskId));
                log.warning("No acknowledgement for " + taskId + " within "
                        + effectiveConfiguration.getPcepAckTimeoutSec()
                        + "s; it is now UNCERTAIN and still holds its capacity");
            }
        } catch (RuntimeException e) {
            // A sweep that throws must not kill the scheduler; the next pass retries.
            log.log(java.util.logging.Level.WARNING, "Acknowledgement sweep failed", e);
        }
    }

    /** The southbound listener, when one is running. */
    public java.util.Optional<net.dcn.pce.pcep.PcepSessionServer> pcepListener() {
        return java.util.Optional.ofNullable(pcepListener);
    }

    /** The installation coordinator, when the southbound listener is running. */
    public java.util.Optional<net.dcn.pce.install.InstallationCoordinator> installationCoordinator() {
        return java.util.Optional.ofNullable(installationCoordinator);
    }

    public synchronized void stop() {
        started = false;
        servingReady = false;
        if (leadershipLifecycle != null) {
            leadershipLifecycle.close();   // stops the heartbeat and demotes (releases nothing racy)
            leadershipLifecycle = null;
        }
        if (acknowledgementSweeper != null) {
            acknowledgementSweeper.shutdownNow();
            acknowledgementSweeper = null;
        }
        if (pcepListener != null) {
            pcepListener.close();
            pcepListener = null;
        }
        // Ask any solve in progress to stop before waiting on the executor. Previously shutdown
        // waited the full grace period and then interrupted a planner that never checked for
        // interruption, so a long solve delayed every shutdown by the whole timeout.
        inFlightSolves.forEach(solve -> solve.cancel(SolveCancellation.Reason.SHUTDOWN));
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (requestExecutor != null) {
            requestExecutor.shutdown();
            try {
                if (!requestExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    requestExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                requestExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                requestExecutor = null;
            }
            log.info("VortexPCE HTTP REST Server stopped.");
        }
    }

    int getBoundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private static class LivenessHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = String.format(
                    "{\"status\": \"UP\", \"service\": \"VortexPCE-Controller\", \"version\": \"%s\"}",
                    escapeJson(BuildInfo.version()));
            sendJsonResponse(exchange, 200, response);
        }
    }

    private class ReadinessHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            boolean queueAvailable = requestExecutor != null
                    && requestExecutor.getQueue().remainingCapacity() > 0;
            // Planner occupancy is deliberately excluded. Planning is single-flight, so an
            // ordinary in-progress solve would otherwise mark the instance NotReady and remove
            // it from a load balancer's endpoints during normal work -- and with the single
            // replica this architecture requires, that reads as the service being down whenever
            // it is doing its job. Backpressure belongs in the 503 that /api/v1/solve already
            // returns while the planner is busy; readiness reports whether this instance can
            // accept traffic at all. plannerBusy remains in the response body for observability.
            boolean subsystemsReady = requiredSubsystemsReady();
            boolean ready = started && queueAvailable && subsystemsReady;
            sendJsonResponse(exchange, ready ? 200 : 503, String.format(
                    "{\"status\": \"%s\", \"plannerBusy\": %s, "
                            + "\"requiredSubsystemsReady\": %s, \"routeGenerationPolicy\": \"%s\", "
                            + "\"rateAssignmentPolicy\": \"%s\", \"contactRegime\": \"%s\"}",
                    ready ? "READY" : "NOT_READY", plannerBusy.get(), subsystemsReady,
                    escapeJson(effectiveConfiguration.getRoutePolicyName()),
                    escapeJson(effectiveConfiguration.getRatePolicyName()),
                    escapeJson(String.valueOf(controller.getActiveTopology().getRegime()))));
        }
    }

    /**
     * Reports this instance's leadership/lease state for cluster operations:
     * holder, term, whether it is the leader, whether the lease is locally live by the
     * conservative monotonic deadline, the database-authoritative expiry, and the last demotion
     * reason. Unauthenticated like the other operational probes; it exposes no secrets. On the
     * single-active file-WAL path there is no lease, so it reports {@code clustered: false}.
     */
    private class ClusterStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed. Use GET.\"}");
                return;
            }
            if (leadershipLifecycle == null) {
                sendJsonResponse(exchange, 200,
                        "{\"clustered\": false, \"role\": \"single-active\"}");
                return;
            }
            net.dcn.pce.rib.LeadershipLifecycle.LeadershipStatus s = leadershipLifecycle.status();
            String expiry = s.leaseExpiry() == null
                    ? "null" : "\"" + escapeJson(s.leaseExpiry().toString()) + "\"";
            String reason = s.lastDemotionReason() == null
                    ? "null" : "\"" + escapeJson(s.lastDemotionReason()) + "\"";
            sendJsonResponse(exchange, 200, String.format(
                    "{\"clustered\": true, \"instanceId\": \"%s\", \"term\": %d, \"leader\": %s, "
                            + "\"leaseLocallyLive\": %s, \"servingReady\": %s, \"leaseExpiry\": %s, "
                            + "\"lastDemotionReason\": %s}",
                    escapeJson(s.instanceId()), s.term(), s.leader(), s.leaseLocallyLive(),
                    servingReady, expiry, reason));
        }
    }

    /** Reports the configuration the controller actually resolved at startup. */
    private class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed. Use GET.\"}");
                return;
            }
            if (!isAuthorized(exchange)) {
                sendJsonResponse(exchange, 401,
                        "{\"error\": \"Unauthorized. Valid Bearer Token or X-API-Key required.\"}");
                return;
            }

            BaseTopology topology = controller.getActiveTopology();
            StringBuilder body = new StringBuilder("{\"effectiveConfiguration\": {");
            body.append(jsonObjectBody(effectiveConfiguration.describe()));
            body.append("}, \"activeTopology\": {")
                    .append(String.format("\"contactRegime\": \"%s\", \"nodeCount\": %d, \"linkCount\": %d",
                            escapeJson(String.valueOf(topology.getRegime())),
                            topology.getNodeCount(), topology.getLinkCount()))
                    .append("}, \"supportedValues\": {")
                    .append(jsonObjectBody(OperatorConfiguration.supportedValues()))
                    .append("}}");
            sendJsonResponse(exchange, 200, body.toString());
        }
    }

    private static String jsonObjectBody(java.util.Map<String, String> entries) {
        StringBuilder builder = new StringBuilder();
        entries.forEach((key, value) -> {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(String.format("\"%s\": \"%s\"", escapeJson(key), escapeJson(value)));
        });
        return builder.toString();
    }

    /**
     * Prometheus exposition endpoint.
     *
     * <p>Authenticated: the payload reports ledger depth, admission outcomes, and heap state,
     * which describe both the controller's capacity commitments and its internal load. Scrapers
     * present the same credential as any other client.
     */
    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) {
                sendJsonResponse(exchange, 401,
                        "{\"error\": \"Unauthorized. Valid Bearer Token or X-API-Key required.\"}");
                return;
            }

            String body = controller.getMetrics().render(
                    controller.getLinkReservationCount(),
                    controller.getNodeReservationCount(),
                    plannerBusy.get(),
                    requestExecutor == null ? 0 : requestExecutor.getQueue().size(),
                    requestExecutor == null ? 0 : requestExecutor.getActiveCount(),
                    controller.getIntentCountsByState(),
                    pcepListener == null ? -1 : pcepListener.establishedSessions().size());

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class SolveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Ended in a finally: HTTP threads are pooled, and an id left on one would attach
            // itself to the next request that thread served -- confidently wrong correlation is
            // worse than none.
            RequestContext.begin();
            try {
                serve(exchange);
            } finally {
                RequestContext.end();
            }
        }

        private void serve(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed. Use POST.\"}");
                return;
            }
            // A standby must not admit: its store's term is not current, so a solve would commit
            // nothing, and letting it plan would only mislead the caller. A just-promoted leader is
            // held here too until its promotion refresh completes, so it never plans against capacity
            // older than the shared store. Either way the caller retries against whoever is ready.
            // servingReady gates only the shared-backend path, where a promotion must finish its
            // capacity refresh before admitting. The file-WAL path has no promotion and is its own
            // sole writer, so it is always ready once started (leadership == null).
            if (!isActiveLeader() || (leadership != null && !servingReady)) {
                sendJsonResponse(exchange, 503,
                        "{\"error\": \"this controller is not admitting requests; the leader admits requests\"}");
                return;
            }

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
                sendJsonResponse(exchange, 415, "{\"error\": \"Content-Type must be application/json.\"}");
                return;
            }

            java.util.Optional<net.dcn.pce.config.ApiPrincipals.Principal> principal =
                    writer(exchange);
            if (principal.isEmpty()) {
                return;
            }

            // Charged before the body is read, so a caller over its quota cannot make the
            // controller do 10 MB of work to be told no.
            if (solveQuota != null) {
                SolveQuota.Decision decision = solveQuota.claim(principal.get().name());
                if (!decision.allowed()) {
                    // 429, not 503: 503 says the planner is busy and any caller should retry,
                    // which would be untrue here. This caller specifically has had its share.
                    exchange.getResponseHeaders().add("Retry-After",
                            String.valueOf(decision.retryAfterSeconds()));
                    sendJsonResponse(exchange, 429, String.format(
                            "{\"error\": \"Solve quota exceeded for this principal; retry in %d "
                                    + "second(s).\"}", decision.retryAfterSeconds()));
                    return;
                }
            }

            // Bounded Stream Reading (Max 10MB) to prevent Heap Exhaustion / DoS
            InputStream is = exchange.getRequestBody();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            int totalBytes = 0;

            while ((bytesRead = is.read(buffer)) != -1) {
                totalBytes += bytesRead;
                if (totalBytes > MAX_PAYLOAD_BYTES) {
                    sendJsonResponse(exchange, 413, "{\"error\": \"Payload Too Large. Maximum allowed request size is 10MB.\"}");
                    return;
                }
                baos.write(buffer, 0, bytesRead);
            }

            String requestBody = baos.toString(StandardCharsets.UTF_8).trim();

            if (requestBody.isEmpty() || !requestBody.startsWith("[")) {
                sendJsonResponse(exchange, 400, "{\"error\": \"Invalid JSON payload. Must be a valid JSON array of workload tasks.\"}");
                return;
            }

            ControllerMetrics metrics = controller.getMetrics();
            boolean admitted;
            try {
                admitted = plannerAdmission.tryLock(
                        effectiveConfiguration.getSolveTimeoutSec(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                metrics.recordSolveOutcome(ControllerMetrics.SolveOutcome.REJECTED_BUSY);
                exchange.getResponseHeaders().add("Retry-After", "1");
                sendJsonResponse(exchange, 503,
                        "{\"error\": \"Planner admission was interrupted; retry the request later.\"}");
                return;
            }
            if (!admitted) {
                metrics.recordSolveOutcome(ControllerMetrics.SolveOutcome.REJECTED_BUSY);
                exchange.getResponseHeaders().add("Retry-After", "1");
                sendJsonResponse(exchange, 503,
                        "{\"error\": \"Planner queue wait exceeded the solve timeout; retry the request later.\"}");
                return;
            }
            plannerBusy.set(true);

            SolveCancellation cancellation = SolveCancellation.withBudgetNanos(
                    java.util.concurrent.TimeUnit.SECONDS.toNanos(
                            effectiveConfiguration.getSolveTimeoutSec()));
            inFlightSolves.add(cancellation);
            try {
                // The owner goes in with the solve rather than being claimed after it returns.
                // Claiming afterwards left a window: a crash between the committed reservation and
                // the claim produced a live task belonging to nobody. Now the tenant is recorded
                // on the intent, in the same durable transaction as the reservation it authorises
                // access to, so the two cannot disagree.
                String jsonResponse = controller.handleScheduleWorkloadsRequest(
                        requestBody, cancellation, principal.get().tenant());
                metrics.recordSolveOutcome(ControllerMetrics.SolveOutcome.SUCCESS);
                sendJsonResponse(exchange, 200, jsonResponse);
            } catch (DuplicateTaskException e) {
                metrics.recordSolveOutcome(ControllerMetrics.SolveOutcome.DUPLICATE);
                sendJsonResponse(exchange, 409, errorJson(e.getMessage()));
            } catch (PCERestController.UndispatchableBacklogException backlog) {
                // 503 with Retry-After: nothing about the request is wrong, and it becomes
                // acceptable again once a router attaches or the backlog drains. Refused before any
                // capacity was committed, so no rollback is needed.
                metrics.recordSolveOutcome(ControllerMetrics.SolveOutcome.REJECTED_BUSY);
                exchange.getResponseHeaders().add("Retry-After", "30");
                sendJsonResponse(exchange, 503, errorJson(backlog.getMessage()));
            } catch (SolveTimeoutException e) {
                // 504 rather than 500: the request exceeded its budget, the ledgers were rolled
                // back, and a smaller batch is a meaningful retry.
                metrics.recordSolveOutcome(ControllerMetrics.SolveOutcome.TIMEOUT);
                log.warning("Solve exceeded its time budget: " + e.getMessage());
                sendJsonResponse(exchange, 504, errorJson(e.getMessage()));
            } catch (IllegalArgumentException e) {
                metrics.recordSolveOutcome(ControllerMetrics.SolveOutcome.INVALID);
                String message = e.getMessage() == null ? "Request failed" : e.getMessage();
                sendJsonResponse(exchange, 400, errorJson(message));
            } catch (Exception e) {
                metrics.recordSolveOutcome(ControllerMetrics.SolveOutcome.ERROR);
                log.log(Level.SEVERE, "Schedule request failed", e);
                sendJsonResponse(exchange, 500, "{\"error\": \"Internal scheduling failure.\"}");
            } finally {
                inFlightSolves.remove(cancellation);
                plannerBusy.set(false);
                plannerAdmission.unlock();
            }
        }
    }

    private class TaskHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            boolean resolving = path.endsWith("/resolve");
            if (!"DELETE".equalsIgnoreCase(method) && !"GET".equalsIgnoreCase(method)
                    && !(resolving && "POST".equalsIgnoreCase(method))) {
                sendJsonResponse(exchange, 405,
                        "{\"error\": \"Method not allowed. Use GET or DELETE, or POST to /resolve.\"}");
                return;
            }
            java.util.Optional<net.dcn.pce.config.ApiPrincipals.Principal> principal =
                    caller(exchange);
            if (principal.isEmpty()) {
                sendJsonResponse(exchange, 401, "{\"error\": \"Unauthorized. Valid Bearer Token or X-API-Key required.\"}");
                return;
            }
            // Reading a task's state is a viewer's business; cancelling or resolving it is not.
            if (!"GET".equalsIgnoreCase(method) && !principal.get().role().canWrite()) {
                sendJsonResponse(exchange, 403, errorJson(
                        "Role " + principal.get().role() + " may not modify tasks."));
                return;
            }
            if (resolving) {
                handleResolve(exchange, path, principal.get());
                return;
            }

            String prefix = "/api/v1/tasks/";
            String rawPath = exchange.getRequestURI().getRawPath();
            if (!rawPath.startsWith(prefix) || rawPath.length() == prefix.length()) {
                sendJsonResponse(exchange, 400, "{\"error\": \"A task ID is required.\"}");
                return;
            }
            String taskId;
            try {
                taskId = URLDecoder.decode(rawPath.substring(prefix.length()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, "{\"error\": \"Invalid task ID encoding.\"}");
                return;
            }
            if (taskId.isBlank() || taskId.contains("/")) {
                sendJsonResponse(exchange, 400, "{\"error\": \"Invalid task ID.\"}");
                return;
            }
            // 404, not 403: a tenant that may not touch this task should not learn it exists.
            // Telling one tenant "that belongs to someone else" leaks the id space of another.
            // Read from the intent ledger, which is where ownership is now committed. The
            // sidecar it replaced could disagree with the reservation it guarded; the ledger
            // cannot, because they are the same transaction.
            if (!principal.get().owns(
                    controller.findIntent(taskId).flatMap(i -> i.getOwner()).orElse(null))) {
                sendJsonResponse(exchange, 404, errorJson("No such task."));
                return;
            }

            if ("GET".equalsIgnoreCase(method)) {
                // Whether a task's capacity is merely planned or actually installed is not
                // answerable from the schedule alone, and it is the question an operator asks
                // when a flow misbehaves.
                var intent = controller.findIntent(taskId);
                if (intent.isEmpty()) {
                    sendJsonResponse(exchange, 404, "{\"error\": \"No installation intent for that task.\"}");
                    return;
                }
                var found = intent.get();
                sendJsonResponse(exchange, 200, String.format(
                        "{\"taskId\": \"%s\", \"lspName\": \"%s\", \"installationState\": \"%s\", "
                                + "\"holdsCapacity\": %s, \"plspId\": %s, \"pccSession\": %s}",
                        escapeJson(found.getTaskId()), escapeJson(found.getLspName()),
                        found.getState().name(), found.holdsCapacity(),
                        found.getPlspId().map(String::valueOf).orElse("null"),
                        found.getPccSessionKey().map(k -> "\"" + escapeJson(k) + "\"").orElse("null")));
                return;
            }

            net.dcn.pce.crp.CRPEngine.CancellationOutcome cancellation =
                    controller.cancelTask(taskId);
            if (cancellation == net.dcn.pce.crp.CRPEngine.CancellationOutcome.RELEASED) {
            }
            switch (cancellation) {
                case RELEASED -> sendJsonResponse(exchange, 200, String.format(
                        "{\"taskId\": \"%s\", \"status\": \"cancelled\", \"capacityReleased\": true}",
                        escapeJson(taskId)));
                // 202, not 200: the removal is under way and the capacity is still held. A caller
                // told 200 would believe the bandwidth was free while a router may still be
                // forwarding the LSP.
                case REMOVAL_REQUESTED -> sendJsonResponse(exchange, 202, String.format(
                        "{\"taskId\": \"%s\", \"status\": \"removal-requested\", "
                                + "\"capacityReleased\": false}", escapeJson(taskId)));
                // 503, not 202: nothing was asked of any router, so no acknowledgement is coming
                // and retrying later is the correct client behaviour. The capacity stays held
                // because the LSP may still be forwarding.
                case REMOVAL_UNDELIVERABLE -> sendJsonResponse(exchange, 503, String.format(
                        "{\"taskId\": \"%s\", \"status\": \"removal-undeliverable\", "
                                + "\"capacityReleased\": false, \"error\": \"The LSP is installed "
                                + "and no removal could be sent to its PCC.\"}", escapeJson(taskId)));
                case NOT_FOUND -> sendJsonResponse(
                        exchange, 404, "{\"error\": \"Task reservation not found.\"}");
            }
        }
    }

    /**
     * Applies an operator's determination about an uncertain LSP.
     *
     * <p>{@code POST /api/v1/tasks/{taskId}/resolve} with {@code {"resolution": "NOT_INSTALLED"}}.
     * An {@code UNCERTAIN} intent is one the controller cannot resolve alone: retrying may
     * duplicate an LSP that exists, releasing may hand its bandwidth to another flow while a
     * router forwards over it. Both are worse than waiting, so it waits — and without this the
     * waiting had no end, because nothing else moves an uncertain intent to a terminal state.
     */
    private void handleResolve(
            HttpExchange exchange, String rawPath,
            net.dcn.pce.config.ApiPrincipals.Principal principal) throws IOException {
        String prefix = "/api/v1/tasks/";
        String suffix = "/resolve";
        String taskId;
        try {
            taskId = URLDecoder.decode(
                    rawPath.substring(prefix.length(), rawPath.length() - suffix.length()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            sendJsonResponse(exchange, 400, "{\"error\": \"Invalid task ID encoding.\"}");
            return;
        }
        if (taskId.isBlank() || taskId.contains("/")) {
            sendJsonResponse(exchange, 400, "{\"error\": \"Invalid task ID.\"}");
            return;
        }

        // Resolution can release capacity while the router may still be forwarding the LSP, so
        // it is at least as privileged as cancellation. Keep the existence-hiding 404 semantics
        // used by GET and DELETE: tenants must neither mutate nor enumerate one another's tasks.
        if (!principal.owns(
                controller.findIntent(taskId).flatMap(i -> i.getOwner()).orElse(null))) {
            sendJsonResponse(exchange, 404, errorJson("No such task."));
            return;
        }

        PCERestController.Resolution resolution;
        try {
            resolution = PCERestController.Resolution.valueOf(
                    JSONUtils.parseResolution(boundedBody(exchange)));
        } catch (IllegalArgumentException e) {
            sendJsonResponse(exchange, 400, String.format("{\"error\": \"%s\"}",
                    escapeJson(e.getMessage())));
            return;
        }

        try {
            var resolved = controller.resolveUncertain(taskId, resolution);
            sendJsonResponse(exchange, 200, String.format(
                    "{\"taskId\": \"%s\", \"state\": \"%s\", \"capacityReleased\": true, "
                            + "\"basis\": \"operator assertion that the LSP is absent\"}",
                    escapeJson(taskId), resolved.getState().name()));
        } catch (IllegalArgumentException missing) {
            sendJsonResponse(exchange, 404, String.format("{\"error\": \"%s\"}",
                    escapeJson(missing.getMessage())));
        } catch (IllegalStateException notUncertain) {
            // 409: the request is well formed, the task simply is not in a state an operator
            // needs to adjudicate.
            sendJsonResponse(exchange, 409, String.format("{\"error\": \"%s\"}",
                    escapeJson(notUncertain.getMessage())));
        }
    }

    /**
     * Accepts an observation of what a link's capacity actually is.
     *
     * <p>{@code POST /api/v1/links/{linkId}/observed-capacity} with {@code {"observedBps": N}}.
     * Whatever measures the network — telemetry, BGP-LS, an operator — tells the controller here,
     * and the next solve plans against it instead of the value declared at startup.
     *
     * <p><strong>Report shifts, not weather.</strong> Send a value when a link's capacity is
     * believed to have genuinely changed — a re-rated radio, a degrading fibre, a contact that
     * turned out worse than the plan. Do not wire this to a periodic telemetry poll. Measured
     * across five weather draws on a stationary link, doing so never improved on simply leaving
     * the declared capacity alone, and on one draw it cost half the delivered workload: a reading
     * taken during a dip made the planner commit a low rate for the next window while the link
     * recovered. A link that varies around a stable mean is best planned against its declared
     * long-run mean, because that is the better forecast.
     *
     * <p>Nor is a rolling average a safe default. Averaging rejects the noise but also dilutes a
     * genuine step — on a link that collapsed from 10 Mbit to 2 Mbit, a window mean straddling
     * the collapse reported 9.2 Mbit and the controller behaved as if it had been told nothing.
     * A spot reading is what detects a step; it is also what a dip fools.
     *
     * <p><strong>Use sparingly.</strong> On a link that merely varies around a stable mean, a spot
     * reading can refuse work the planner would have completed. Prefer leaving the declared capacity
     * alone unless something is known to have genuinely changed.
     */
    private class LinkCapacityHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed. Use POST.\"}");
                return;
            }
            if (!isAuthorized(exchange)) {
                sendJsonResponse(exchange, 401,
                        "{\"error\": \"Unauthorized. Valid Bearer Token or X-API-Key required.\"}");
                return;
            }

            // ADMIN only. Lowering a link's capacity changes what every tenant can be admitted
            // for, so it is not something one tenant's operator should be able to do to the
            // others.
            java.util.Optional<net.dcn.pce.config.ApiPrincipals.Principal> principal =
                    caller(exchange);
            if (principal.isPresent() && !principal.get().role().canAdminister()) {
                sendJsonResponse(exchange, 403, errorJson(
                        "Role " + principal.get().role() + " may not change link capacity."));
                return;
            }

            String prefix = "/api/v1/links/";
            String suffix = "/observed-capacity";
            String rawPath = exchange.getRequestURI().getRawPath();
            if (!rawPath.startsWith(prefix) || !rawPath.endsWith(suffix)) {
                sendJsonResponse(exchange, 404,
                        "{\"error\": \"Use POST /api/v1/links/{linkId}/observed-capacity.\"}");
                return;
            }
            String linkId;
            try {
                linkId = URLDecoder.decode(
                        rawPath.substring(prefix.length(), rawPath.length() - suffix.length()),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, "{\"error\": \"Invalid link ID encoding.\"}");
                return;
            }

            double observedBps;
            try {
                observedBps = JSONUtils.parseObservedCapacityBps(boundedBody(exchange));
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, String.format("{\"error\": \"%s\"}",
                        escapeJson(e.getMessage())));
                return;
            }

            try {
                double previousBps = controller.recordObservedCapacity(linkId, observedBps);
                sendJsonResponse(exchange, 200, String.format(
                        "{\"linkId\": \"%s\", \"observedBps\": %s, \"previousBps\": %s, "
                                + "\"appliesTo\": \"future solves; existing commitments unchanged\"}",
                        escapeJson(linkId), observedBps, previousBps));
            } catch (PCERestController.CapacityUpdateBusyException busy) {
                // 503 with Retry-After, not 409: nothing about the observation is wrong and
                // nothing was applied. A solve simply held the topology, and the same request
                // will succeed shortly.
                exchange.getResponseHeaders().add("Retry-After", "1");
                sendJsonResponse(exchange, 503, String.format("{\"error\": \"%s\"}",
                        escapeJson(busy.getMessage())));
            } catch (IllegalArgumentException unknown) {
                sendJsonResponse(exchange, 404, String.format("{\"error\": \"%s\"}",
                        escapeJson(unknown.getMessage())));
            } catch (IllegalStateException overCommitted) {
                // 409, not 400: the request is well formed and the observation may well be true.
                // What it conflicts with is state this controller has already promised.
                sendJsonResponse(exchange, 409, String.format("{\"error\": \"%s\"}",
                        escapeJson(overCommitted.getMessage())));
            } catch (java.io.UncheckedIOException persistenceFailure) {
                // Nothing was published in memory. Report a retryable service failure instead of
                // closing the connection or claiming that a volatile-only observation succeeded.
                log.log(Level.SEVERE, "Observed-capacity persistence failed", persistenceFailure);
                sendJsonResponse(exchange, 503,
                        "{\"error\": \"Capacity observation was not persisted or applied.\"}");
            }
        }
    }

    /**
     * Reads a request body, refusing anything larger than the configured maximum.
     *
     * <p>Bounded for the same reason the solve endpoint bounds its own: this parses input from an
     * authenticated but not necessarily well-behaved client, and an unbounded read is a way to
     * exhaust the process.
     */
    private String boundedBody(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody()) {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_PAYLOAD_BYTES) {
                    throw new IllegalArgumentException("Request body exceeds the maximum size.");
                }
                collected.write(buffer, 0, read);
            }
            return collected.toString(StandardCharsets.UTF_8);
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        return caller(exchange).isPresent();
    }

    /**
     * Identifies the caller from its credential.
     *
     * <p>Replaces a boolean "is this the key" with "who is this", which is what makes a role and a
     * tenant expressible at all. With only {@code VORTEX_API_KEY} configured there is exactly one
     * principal — an unrestricted ADMIN — so existing deployments behave exactly as before.
     */
    private java.util.Optional<net.dcn.pce.config.ApiPrincipals.Principal> caller(
            HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String apiKeyHeader = exchange.getRequestHeaders().getFirst("X-API-Key");
        String presented = apiKeyHeader != null ? apiKeyHeader
                : (authHeader != null && authHeader.startsWith("Bearer ")
                        ? authHeader.substring(7) : null);
        return principals.identify(presented);
    }

    /**
     * Identifies a caller that must be able to write, answering the exchange if it cannot.
     *
     * <p>403 rather than 401: the credential is valid and the caller is known, which is a
     * different thing to say than "who are you". A VIEWER told 401 would reasonably retry with
     * the same credential for ever.
     */
    private java.util.Optional<net.dcn.pce.config.ApiPrincipals.Principal> writer(
            HttpExchange exchange) throws IOException {
        java.util.Optional<net.dcn.pce.config.ApiPrincipals.Principal> principal = caller(exchange);
        if (principal.isEmpty()) {
            sendJsonResponse(exchange, 401, errorJson(
                    "Unauthorized. Valid Bearer Token or X-API-Key required."));
            return java.util.Optional.empty();
        }
        if (!principal.get().role().canWrite()) {
            sendJsonResponse(exchange, 403, errorJson(
                    "Role " + principal.get().role() + " may not modify state."));
            return java.util.Optional.empty();
        }
        return principal;
    }

    private final net.dcn.pce.config.ApiPrincipals principals;

    private static String errorJson(String message) {
        return String.format("{\"error\": \"%s\"}", escapeJson(message));
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        // Returned on every response, including errors: the request a caller needs to ask about
        // is usually the one that failed.
        String requestId = RequestContext.current();
        if (!requestId.isEmpty() && exchange.getResponseHeaders().getFirst("X-Request-Id") == null) {
            exchange.getResponseHeaders().add("X-Request-Id", requestId);
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
}
