package net.dcn.pce.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dcn.pce.crp.CRPEngine;
import net.dcn.pce.crp.policy.PathSelectionPolicy;
import net.dcn.pce.crp.policy.RateAssignmentPolicy;
import net.dcn.pce.crp.policy.RouteGenerationPolicy;
import net.dcn.pce.crp.policy.TaskSelectionPolicy;
import net.dcn.pce.model.ContactRegime;

import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Pure, validated operator configuration for the VortexPCE controller.
 *
 * <p>This type is deliberately parsed from an explicit environment snapshot rather than reading
 * {@link System#getenv()} directly, so that configuration behavior can be unit tested without
 * mutating process environment variables.
 *
 * <p>Configuration errors are rejected at construction time. An unrecognized policy name is a
 * startup failure, not a warning: silently retaining a default would run the controller under a
 * policy the operator did not ask for, and admission decisions made under the wrong policy are
 * not distinguishable after the fact.
 */
public final class OperatorConfiguration {

    private static final ObjectMapper CONFIG_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static final String ENV_TOPOLOGY_FILE = "VORTEX_TOPOLOGY_FILE";
    public static final String ENV_ROUTE_GEN_POLICY = "VORTEX_ROUTE_GEN_POLICY";
    public static final String ENV_RATE_ASSIGN_POLICY = "VORTEX_RATE_ASSIGN_POLICY";
    public static final String ENV_TASK_SELECT_POLICY = "VORTEX_TASK_SELECT_POLICY";
    public static final String ENV_PATH_SELECT_POLICY = "VORTEX_PATH_SELECT_POLICY";
    public static final String ENV_STATE_PATH = "VORTEX_STATE_PATH";
    /** file (default, local WAL) | postgres (experimental shared transactional backend, ADR-0001). */
    public static final String ENV_RESERVATION_BACKEND = "VORTEX_RESERVATION_BACKEND";
    public static final String ENV_POSTGRES_URL = "VORTEX_POSTGRES_URL";
    public static final String ENV_POSTGRES_USER = "VORTEX_POSTGRES_USER";
    public static final String ENV_POSTGRES_PASSWORD = "VORTEX_POSTGRES_PASSWORD";
    public static final String ENV_SOLVE_TIMEOUT_SEC = "VORTEX_SOLVE_TIMEOUT_SEC";
    /** Largest fraction of a link's residual capacity admission may commit, in (0, 1]. */
    public static final String ENV_UTILIZATION_HEADROOM = "VORTEX_UTILIZATION_HEADROOM";
    /** Where the headroom is checked: WINDOW_PEAK (default) or TRANSMISSION_INTERVAL. */
    public static final String ENV_HEADROOM_ENFORCEMENT = "VORTEX_HEADROOM_ENFORCEMENT";
    /** How much faster than the policy's rate to send: NONE (default), RATE_FACTOR, DEADLINE_GUARD. */
    public static final String ENV_TRANSPORT_MARGIN = "VORTEX_TRANSPORT_MARGIN";
    /** The margin's size: a factor >= 1 for RATE_FACTOR, seconds >= 0 for DEADLINE_GUARD. */
    public static final String ENV_TRANSPORT_MARGIN_VALUE = "VORTEX_TRANSPORT_MARGIN_VALUE";
    public static final String ENV_PCEP_LISTENER = "VORTEX_PCEP_LISTENER";
    /**
     * Whether a committed schedule is dispatched to a router automatically.
     *
     * <p>Off by default, and deliberately so. Installing an LSP changes a production network, and
     * an operator who enables a southbound listener has not thereby asked this controller to start
     * programming their routers. Turning it on is a separate, explicit decision.
     */
    public static final String ENV_PCEP_AUTO_INSTALL = "VORTEX_PCEP_AUTO_INSTALL";
    /**
     * Comma-separated source addresses this controller will accept PCEP connections from, or
     * unset to accept any. Entries may be bare ({@code 10.0.0.5}) or prefixed ({@code addr:...}).
     *
     * <p>Addresses, because the alternative does not work. This setting previously took session
     * keys, which are {@code speaker:<entity-id>} whenever the peer sends a Speaker Entity
     * Identifier -- in its own OPEN message. An entry naming a permitted speaker admitted anyone
     * willing to send that name, from any address, and the arriving session then superseded the
     * genuine one for that key. {@code speaker:} entries are now refused at startup rather than
     * quietly ignored.
     *
     * <p>This is still not authentication. A source address is forgeable by anyone on-path or able
     * to occupy it, and nothing here is cryptographic. What changed is that an attacker must now
     * complete a TCP handshake from a permitted address rather than merely assert a name. It
     * exists because automatic dispatch selects "the single established session", so an
     * unexpected peer that established one would be handed this controller's LSPs. Real peer
     * authentication needs PCEPS (RFC 8253) with client certificates; configure the key and trust
     * stores below whenever the listener is reachable outside a trusted management network.
     */
    public static final String ENV_PCEP_PERMITTED_PEERS = "VORTEX_PCEP_PERMITTED_PEERS";
    /**
     * PKCS#12 store holding this controller's certificate and key. Enables PCEPS (RFC 8253).
     *
     * <p>Setting this turns the southbound listener into mutual TLS: a peer must present a
     * certificate the trust store vouches for, and {@link #ENV_PCEP_PERMITTED_PEERS} then names
     * certificate subjects supplied as a JSON array (for example
     * {@code ["CN=pcc-alpha,O=Example"]}) rather than source addresses. JSON is deliberate: an
     * X.500 distinguished name itself contains commas, so the plaintext address-list delimiter
     * cannot represent certificate identities safely. Subjects are canonicalized before use. That
     * is the difference between restricting where a connection may come from and establishing who
     * is at the other end — the latter is what automatic dispatch needs, since it programs paths
     * onto whichever peer holds the single established session.
     */
    /**
     * PKCS#12 store holding the certificate the northbound API serves HTTPS with.
     *
     * <p>Without it the API is plain HTTP, which means {@code X-API-Key} — the single credential
     * that authorises solving, cancellation and capacity mutation — crosses the network in
     * cleartext on every request. Anyone able to observe the traffic acquires full control of the
     * controller, and constant-time comparison of a credential that was readable in transit
     * protects nothing.
     */
    /**
     * Named API credentials as {@code name:role:tenant:secret}, comma separated.
     *
     * <p>Roles are VIEWER (read only), OPERATOR (solve and cancel its own tenant's tasks) and
     * ADMIN (everything, any tenant). Without this the single {@code VORTEX_API_KEY} remains an
     * unrestricted ADMIN, so a monitoring scraper and the system owning production traffic hold
     * the same credential and either can cancel the other's work.
     *
     * <p>Several may be configured at once, which permits an overlap during controlled rotation.
     * Configuration is startup-loaded, so the supported single-instance deployment still restarts
     * as credentials change; overlap prevents client lockout rather than eliminating that interval.
     */
    public static final String ENV_API_PRINCIPALS = "VORTEX_API_PRINCIPALS";

    /**
     * Solves per minute each API principal may run, or unset for no limit.
     *
     * <p>Planning is single-flight and concurrent requests use a bounded fair server-side queue.
     * Fairness controls execution order but not how much queued work one legitimate caller may
     * contribute. This bounds how much of the shared, serialised resource each principal may take.
     *
     * <p>Unset by default. Every existing deployment predates it, and a quota that silently
     * appears is an outage for whoever was above it.
     */
    public static final String ENV_API_SOLVE_QUOTA_PER_MINUTE = "VORTEX_API_SOLVE_QUOTA_PER_MINUTE";

    public static final String ENV_API_TLS_KEYSTORE = "VORTEX_API_TLS_KEYSTORE";
    public static final String ENV_API_TLS_KEYSTORE_PASSWORD = "VORTEX_API_TLS_KEYSTORE_PASSWORD";

    public static final String ENV_PCEP_TLS_KEYSTORE = "VORTEX_PCEP_TLS_KEYSTORE";
    public static final String ENV_PCEP_TLS_KEYSTORE_PASSWORD =
            "VORTEX_PCEP_TLS_KEYSTORE_PASSWORD";
    /** PKCS#12 store of the authority that legitimate PCC certificates are signed by. */
    public static final String ENV_PCEP_TLS_TRUSTSTORE = "VORTEX_PCEP_TLS_TRUSTSTORE";
    public static final String ENV_PCEP_TLS_TRUSTSTORE_PASSWORD =
            "VORTEX_PCEP_TLS_TRUSTSTORE_PASSWORD";
    public static final String ENV_PCEP_PORT = "VORTEX_PCEP_PORT";
    public static final String ENV_PCEP_BIND = "VORTEX_PCEP_BIND";
    public static final String ENV_PCEP_ACK_TIMEOUT_SEC = "VORTEX_PCEP_ACK_TIMEOUT_SEC";
    public static final String ENV_PCEP_MAX_SESSIONS = "VORTEX_PCEP_MAX_SESSIONS";

    /** Accepted values for the southbound listener. */
    // PYTHON names the legacy server explicitly. It used to be what DISABLED did, which meant
    // the documented default opened a southbound port running an implementation with no PCRpt or
    // PCUpd handling behind it.
    private static final Set<String> PCEP_LISTENERS = Set.of("DISABLED", "JAVA", "PYTHON");
    private static final Set<String> AUTO_INSTALL_MODES = Set.of("DISABLED", "SINGLE_PCC");
    private static final String DEFAULT_PCEP_LISTENER = "DISABLED";
    private static final int DEFAULT_PCEP_PORT = 4189;
    private static final String DEFAULT_PCEP_BIND = "127.0.0.1";
    private static final int DEFAULT_PCEP_ACK_TIMEOUT_SEC = 30;
    // The listener bounds concurrent sessions so an unauthenticated port cannot be a
    // cheap resource exhaustion. 64 is the historical hardcoded value, kept as the
    // default; an operator on a trusted management network can raise it.
    private static final int DEFAULT_PCEP_MAX_SESSIONS = 64;

    /**
     * Default wall-clock budget for a single solve. Planning is single-flight, so an unbounded
     * solve takes the controller out of service rather than merely slowing it down.
     */
    private static final int DEFAULT_SOLVE_TIMEOUT_SEC = 60;
    /** The engine's long-standing admission margin: 90% of residual capacity. */
    private static final double DEFAULT_UTILIZATION_HEADROOM = 0.90;
    private static final Set<String> HEADROOM_ENFORCEMENTS = Set.of("WINDOW_PEAK", "TRANSMISSION_INTERVAL");
    private static final Set<String> TRANSPORT_MARGINS = Set.of("NONE", "RATE_FACTOR", "DEADLINE_GUARD");
    private static final int MAX_SOLVE_TIMEOUT_SEC = 3600;

    /** Route-generation policies addressable by operators, with the regimes each supports. */
    private static final Map<String, RoutePolicyBinding> ROUTE_POLICIES = Map.of(
            "BFS_MIN_HOP", new RoutePolicyBinding(
                    RouteGenerationPolicy.BFS_MIN_HOP, EnumSetOf.allRegimes()),
            "FAST_K_SHORTEST_PATHS", new RoutePolicyBinding(
                    RouteGenerationPolicy.FAST_K_SHORTEST_PATHS, EnumSetOf.allRegimes()),
            "BOUNDED_PATH_ENUMERATION", new RoutePolicyBinding(
                    RouteGenerationPolicy.BOUNDED_PATH_ENUMERATION, EnumSetOf.allRegimes()),
            "K_MAX_EXHAUSTIVE", new RoutePolicyBinding(
                    RouteGenerationPolicy.K_MAX_EXHAUSTIVE, EnumSetOf.allRegimes()),
            // Bounded CGR expands contact-graph labels; it needs a contact plan (R_DET or R_STOCH).
            "BOUNDED_CGR", new RoutePolicyBinding(
                    RouteGenerationPolicy.BOUNDED_CGR,
                    Set.of(ContactRegime.R_DET, ContactRegime.R_STOCH)),
            "DISJOINT_CGR", new RoutePolicyBinding(
                    RouteGenerationPolicy.DISJOINT_CGR,
                    Set.of(ContactRegime.R_DET, ContactRegime.R_STOCH)),
            "CUT_ANCHORED_CGR", new RoutePolicyBinding(
                    RouteGenerationPolicy.CUT_ANCHORED_CGR,
                    Set.of(ContactRegime.R_DET, ContactRegime.R_STOCH)),
            "MESH_CGR", new RoutePolicyBinding(
                    RouteGenerationPolicy.MESH_CGR,
                    Set.of(ContactRegime.R_DET, ContactRegime.R_STOCH)));

    private static final Map<String, RateAssignmentPolicy> RATE_POLICIES = Map.of(
            "DATA_FLOW_EQUILIBRIUM", RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM,
            "EQUILIBRIUM", RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM,
            "EQM", RateAssignmentPolicy.DATA_FLOW_EQUILIBRIUM,
            "LINE_RATE", RateAssignmentPolicy.LINE_RATE,
            "FAIRCAP", RateAssignmentPolicy.FAIRCAP,
            "RESIDUAL_BOTTLENECK_HEADROOM", RateAssignmentPolicy.RESIDUAL_BOTTLENECK_HEADROOM);

    private static final Map<String, TaskSelectionPolicy> TASK_POLICIES = Map.of(
            "LWEEF", TaskSelectionPolicy.LWEEF,
            "FCFS", TaskSelectionPolicy.FCFS,
            "EWOF", TaskSelectionPolicy.EWOF,
            "LWF", TaskSelectionPolicy.LWF);

    private static final Map<String, PathSelectionPolicy> PATH_POLICIES = Map.of(
            "EAP", PathSelectionPolicy.EAP,
            "OCC", PathSelectionPolicy.OCC,
            "CGR", PathSelectionPolicy.CGR,
            "MIN_HOP", PathSelectionPolicy.MIN_HOP);

    /**
     * Accepted aliases mapped to the canonical policy name. Configuration is reported back to
     * operators under the canonical name so that {@code /api/v1/config} names the policy actually
     * in force rather than whichever spelling happened to be set.
     */
    private static final Map<String, String> CANONICAL_NAMES = Map.of(
            "EQUILIBRIUM", "DATA_FLOW_EQUILIBRIUM",
            "EQM", "DATA_FLOW_EQUILIBRIUM",
            "EWOF", "FCFS",
            "OCC", "EAP",
            "CGR", "EAP");

    /**
     * Route policy under {@link ContactRegime#R_STATIC} when none is configured. The scheduled-contact
     * regimes default to {@code BOUNDED_CGR} instead; see {@link #controllerDefaultRoutePolicyName}.
     */
    private static final String DEFAULT_STATIC_ROUTE_POLICY = "BFS_MIN_HOP";

    /**
     * Reported for a configuration that has no explicit route policy and has not been bound to a
     * topology yet. Its engine picks the regime default per solve; see {@link #applyTo}.
     */
    public static final String REGIME_DEFAULT_ROUTE_POLICY = "REGIME_DEFAULT";

    /**
     * The regime default resolved from the topology being solved. It exists for configurations that
     * are applied to an engine without being bound to a regime first; a bound configuration applies
     * the concrete policy directly.
     */
    private static final RouteGenerationPolicy REGIME_DEFAULT_ROUTES = (task, topology, lrib, nrib) ->
            ROUTE_POLICIES.get(controllerDefaultRoutePolicyName(topology.getRegime())).policy()
                    .generateCandidateRoutes(task, topology, lrib, nrib);

    private static final String DEFAULT_RATE_POLICY = "DATA_FLOW_EQUILIBRIUM";
    private static final String DEFAULT_TASK_POLICY = "LWEEF";
    private static final String DEFAULT_PATH_POLICY = "EAP";

    private final String topologyFile;
    private final String statePath;
    /** The configured route policy, or null when unset (the regime default applies). */
    private final String routePolicyName;
    private final String ratePolicyName;
    private final String taskPolicyName;
    private final String pathPolicyName;
    private final int solveTimeoutSec;
    private final double utilizationHeadroom;
    private final String headroomEnforcement;
    private final String transportMargin;
    private final double transportMarginValue;
    private final String pcepListener;
    private final String pcepAutoInstall;
    private final java.util.Set<String> pcepPermittedPeers;
    private final String apiPrincipals;
    private final int apiSolveQuotaPerMinute;
    private final String apiTlsKeyStore;
    private final String apiTlsKeyStorePassword;
    private final String pcepTlsKeyStore;
    private final String pcepTlsKeyStorePassword;
    private final String pcepTlsTrustStore;
    private final String pcepTlsTrustStorePassword;
    private final int pcepPort;
    private final String pcepBind;
    private final int pcepAckTimeoutSec;
    private final int pcepMaxSessions;
    private final String reservationBackend;
    private final String postgresUrl;
    private final String postgresUser;
    private final String postgresPassword;

    private OperatorConfiguration(
            String topologyFile,
            String statePath,
            String routePolicyName,
            String ratePolicyName,
            String taskPolicyName,
            String pathPolicyName,
            int solveTimeoutSec,
            double utilizationHeadroom,
            String headroomEnforcement,
            String transportMargin,
            double transportMarginValue,
            String pcepListener,
            String pcepAutoInstall,
            java.util.Set<String> pcepPermittedPeers,
            int pcepPort,
            String pcepBind,
            int pcepAckTimeoutSec,
            int pcepMaxSessions,
            String apiPrincipals,
            int apiSolveQuotaPerMinute,
            String apiTlsKeyStore,
            String apiTlsKeyStorePassword,
            String pcepTlsKeyStore,
            String pcepTlsKeyStorePassword,
            String pcepTlsTrustStore,
            String pcepTlsTrustStorePassword,
            String reservationBackend,
            String postgresUrl,
            String postgresUser,
            String postgresPassword) {
        this.topologyFile = topologyFile;
        this.statePath = statePath;
        this.routePolicyName = routePolicyName;
        this.ratePolicyName = ratePolicyName;
        this.taskPolicyName = taskPolicyName;
        this.pathPolicyName = pathPolicyName;
        this.solveTimeoutSec = solveTimeoutSec;
        this.utilizationHeadroom = utilizationHeadroom;
        this.headroomEnforcement = headroomEnforcement;
        this.transportMargin = transportMargin;
        this.transportMarginValue = transportMarginValue;
        this.pcepListener = pcepListener;
        this.pcepAutoInstall = pcepAutoInstall;
        this.pcepPermittedPeers = pcepPermittedPeers == null
                ? java.util.Set.of() : java.util.Set.copyOf(pcepPermittedPeers);
        this.pcepPort = pcepPort;
        this.pcepBind = pcepBind;
        this.pcepAckTimeoutSec = pcepAckTimeoutSec;
        this.pcepMaxSessions = pcepMaxSessions;
        this.apiPrincipals = apiPrincipals;
        this.apiSolveQuotaPerMinute = apiSolveQuotaPerMinute;
        this.apiTlsKeyStore = apiTlsKeyStore;
        this.apiTlsKeyStorePassword = apiTlsKeyStorePassword;
        this.pcepTlsKeyStore = pcepTlsKeyStore;
        this.pcepTlsKeyStorePassword = pcepTlsKeyStorePassword;
        this.pcepTlsTrustStore = pcepTlsTrustStore;
        this.pcepTlsTrustStorePassword = pcepTlsTrustStorePassword;
        this.reservationBackend = reservationBackend == null ? "FILE" : reservationBackend;
        this.postgresUrl = postgresUrl;
        this.postgresUser = postgresUser;
        this.postgresPassword = postgresPassword;
        if ("POSTGRES".equals(this.reservationBackend) && (postgresUrl == null || postgresUrl.isBlank())) {
            throw new IllegalArgumentException(ENV_RESERVATION_BACKEND + "=postgres requires "
                    + ENV_POSTGRES_URL);
        }
        // Half a TLS configuration is not a safer state than none: without a trust store the
        // listener has no basis to decide which client certificates are legitimate, so it would
        // present TLS while authenticating nobody.
        if ((pcepTlsKeyStore == null) != (pcepTlsTrustStore == null)) {
            throw new IllegalArgumentException(ENV_PCEP_TLS_KEYSTORE + " and "
                    + ENV_PCEP_TLS_TRUSTSTORE + " must be set together: a listener with a "
                    + "certificate but no trust store would offer TLS while authenticating no one");
        }
    }

    /** A copy of {@code base} with the route policy set to {@code routePolicyName}. */
    private OperatorConfiguration(OperatorConfiguration base, String routePolicyName) {
        this(base.topologyFile, base.statePath, routePolicyName, base.ratePolicyName,
                base.taskPolicyName, base.pathPolicyName, base.solveTimeoutSec, base.utilizationHeadroom,
                base.headroomEnforcement, base.transportMargin, base.transportMarginValue,
                base.pcepListener,
                base.pcepAutoInstall, base.pcepPermittedPeers, base.pcepPort, base.pcepBind,
                base.pcepAckTimeoutSec, base.pcepMaxSessions, base.apiPrincipals,
                base.apiSolveQuotaPerMinute, base.apiTlsKeyStore, base.apiTlsKeyStorePassword,
                base.pcepTlsKeyStore, base.pcepTlsKeyStorePassword, base.pcepTlsTrustStore,
                base.pcepTlsTrustStorePassword, base.reservationBackend, base.postgresUrl,
                base.postgresUser, base.postgresPassword);
    }

    /** Parses and validates configuration from an explicit environment snapshot. */
    public static OperatorConfiguration parse(Map<String, String> environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment snapshot is required");
        }
        return new OperatorConfiguration(
                trimmedOrNull(environment.get(ENV_TOPOLOGY_FILE)),
                trimmedOrNull(environment.get(ENV_STATE_PATH)),
                // Unset stays null: the right default depends on the topology's regime, which is not
                // known until the topology is loaded. forRegime() resolves it then.
                resolveName(environment, ENV_ROUTE_GEN_POLICY, null, ROUTE_POLICIES.keySet()),
                resolveName(environment, ENV_RATE_ASSIGN_POLICY, DEFAULT_RATE_POLICY, RATE_POLICIES.keySet()),
                resolveName(environment, ENV_TASK_SELECT_POLICY, DEFAULT_TASK_POLICY, TASK_POLICIES.keySet()),
                resolveName(environment, ENV_PATH_SELECT_POLICY, DEFAULT_PATH_POLICY, PATH_POLICIES.keySet()),
                resolveSolveTimeoutSec(environment),
                resolveUtilizationHeadroom(environment),
                resolveName(environment, ENV_HEADROOM_ENFORCEMENT, "WINDOW_PEAK", HEADROOM_ENFORCEMENTS),
                resolveName(environment, ENV_TRANSPORT_MARGIN, "NONE", TRANSPORT_MARGINS),
                resolveTransportMarginValue(environment),
                resolveName(environment, ENV_PCEP_LISTENER, DEFAULT_PCEP_LISTENER, PCEP_LISTENERS),
                resolveName(environment, ENV_PCEP_AUTO_INSTALL, "DISABLED", AUTO_INSTALL_MODES),
                parsePermittedPeers(environment.get(ENV_PCEP_PERMITTED_PEERS),
                        trimmedOrNull(environment.get(ENV_PCEP_TLS_KEYSTORE)) != null),
                resolveBoundedInt(environment, ENV_PCEP_PORT, DEFAULT_PCEP_PORT, 0, 65_535),
                java.util.Optional.ofNullable(trimmedOrNull(environment.get(ENV_PCEP_BIND)))
                        .orElse(DEFAULT_PCEP_BIND),
                resolveBoundedInt(environment, ENV_PCEP_ACK_TIMEOUT_SEC,
                        DEFAULT_PCEP_ACK_TIMEOUT_SEC, 1, 3600),
                resolveBoundedInt(environment, ENV_PCEP_MAX_SESSIONS,
                        DEFAULT_PCEP_MAX_SESSIONS, 1, 100_000),
                trimmedOrNull(environment.get(ENV_API_PRINCIPALS)),
                resolveBoundedInt(environment, ENV_API_SOLVE_QUOTA_PER_MINUTE, 0, 0, 100_000),
                trimmedOrNull(environment.get(ENV_API_TLS_KEYSTORE)),
                environment.get(ENV_API_TLS_KEYSTORE_PASSWORD),
                trimmedOrNull(environment.get(ENV_PCEP_TLS_KEYSTORE)),
                environment.get(ENV_PCEP_TLS_KEYSTORE_PASSWORD),
                trimmedOrNull(environment.get(ENV_PCEP_TLS_TRUSTSTORE)),
                environment.get(ENV_PCEP_TLS_TRUSTSTORE_PASSWORD),
                resolveName(environment, ENV_RESERVATION_BACKEND, "FILE",
                        java.util.Set.of("FILE", "POSTGRES")),
                trimmedOrNull(environment.get(ENV_POSTGRES_URL)),
                trimmedOrNull(environment.get(ENV_POSTGRES_USER)),
                environment.get(ENV_POSTGRES_PASSWORD));
    }

    private static double resolveUtilizationHeadroom(Map<String, String> environment) {
        String raw = trimmedOrNull(environment.get(ENV_UTILIZATION_HEADROOM));
        if (raw == null) {
            return DEFAULT_UTILIZATION_HEADROOM;
        }
        double parsed = parseNumber(ENV_UTILIZATION_HEADROOM, raw);
        if (parsed <= 0 || parsed > 1) {
            throw new IllegalArgumentException(String.format(
                    "%s must be in (0, 1], got %s", ENV_UTILIZATION_HEADROOM, raw));
        }
        return parsed;
    }

    /**
     * The margin's size, checked against the selected margin: a factor >= 1 for RATE_FACTOR, seconds
     * >= 0 for DEADLINE_GUARD. A size without a margin, or a margin without a size, is rejected
     * rather than silently ignored.
     */
    private static double resolveTransportMarginValue(Map<String, String> environment) {
        String margin = resolveName(environment, ENV_TRANSPORT_MARGIN, "NONE", TRANSPORT_MARGINS);
        String raw = trimmedOrNull(environment.get(ENV_TRANSPORT_MARGIN_VALUE));
        if ("NONE".equals(margin)) {
            if (raw != null) {
                throw new IllegalArgumentException(String.format(
                        "%s is set but %s is NONE; choose RATE_FACTOR or DEADLINE_GUARD",
                        ENV_TRANSPORT_MARGIN_VALUE, ENV_TRANSPORT_MARGIN));
            }
            return 0.0;
        }
        if (raw == null) {
            throw new IllegalArgumentException(String.format(
                    "%s=%s needs %s", ENV_TRANSPORT_MARGIN, margin, ENV_TRANSPORT_MARGIN_VALUE));
        }
        double parsed = parseNumber(ENV_TRANSPORT_MARGIN_VALUE, raw);
        if ("RATE_FACTOR".equals(margin) && parsed < 1.0) {
            throw new IllegalArgumentException(String.format(
                    "%s for RATE_FACTOR must be a factor >= 1, got %s", ENV_TRANSPORT_MARGIN_VALUE, raw));
        }
        if ("DEADLINE_GUARD".equals(margin) && parsed < 0.0) {
            throw new IllegalArgumentException(String.format(
                    "%s for DEADLINE_GUARD must be seconds >= 0, got %s", ENV_TRANSPORT_MARGIN_VALUE, raw));
        }
        return parsed;
    }

    private static double parseNumber(String key, String raw) {
        double parsed;
        try {
            parsed = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("%s must be a number, got '%s'", key, raw));
        }
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException(String.format("%s must be finite, got '%s'", key, raw));
        }
        return parsed;
    }

    private static int resolveBoundedInt(
            Map<String, String> environment, String key, int defaultValue, int min, int max) {
        String raw = trimmedOrNull(environment.get(key));
        if (raw == null) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format(
                    "%s must be a whole number, got '%s'", key, raw));
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(String.format(
                    "%s must be between %d and %d, got %d", key, min, max, parsed));
        }
        return parsed;
    }

    /** Source addresses permitted to establish a session; empty means any peer is accepted. */
    public java.util.Set<String> getPcepPermittedPeers() {
        return pcepPermittedPeers;
    }

    /**
     * Parses the permitted-peer allowlist, which is a set of source addresses.
     *
     * <p>{@code speaker:} entries are rejected rather than accepted-and-ignored. They used to be
     * the documented form, and they were the reason the allowlist did not work: the speaker
     * entity ID arrives in the peer's own OPEN message, so an entry naming one admitted anybody
     * willing to type that name. Silently dropping such an entry would leave an operator with a
     * configuration that looks restrictive and permits everything, so startup fails and says why.
     */
    private static java.util.Set<String> parsePermittedPeers(String raw, boolean tlsConfigured) {
        if (raw == null || raw.isBlank()) {
            return java.util.Set.of();
        }
        if (tlsConfigured) {
            return parsePermittedCertificateSubjects(raw);
        }
        java.util.Set<String> peers = new java.util.LinkedHashSet<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("speaker:")) {
                throw new IllegalArgumentException(ENV_PCEP_PERMITTED_PEERS + " no longer accepts "
                        + "'speaker:' entries: a speaker entity ID is supplied by the peer, so it "
                        + "cannot restrict who may connect. Use the PCC's source address instead, "
                        + "for example 'addr:10.0.0.5'. Found: " + trimmed);
            }
            String address = trimmed.startsWith("addr:")
                    ? trimmed.substring("addr:".length()).trim() : trimmed;
            // Refusing a malformed entry beats silently permitting nothing it was meant to name:
            // an entry that is not an address can never match a connecting peer, so a typo would
            // lock out the real PCC and read as a network fault rather than a configuration one.
            if (!isIpLiteral(address)) {
                throw new IllegalArgumentException(ENV_PCEP_PERMITTED_PEERS + " entries must be IP "
                        + "addresses, for example 'addr:10.0.0.5' or '10.0.0.5', found: " + trimmed);
            }
            peers.add(address);
        }
        return java.util.Set.copyOf(peers);
    }

    /**
     * Parses PCEPS identities without confusing the commas inside an X.500 name for list
     * separators. Every configured subject and every certificate presented at runtime use the
     * same canonical representation before exact comparison.
     */
    private static Set<String> parsePermittedCertificateSubjects(String raw) {
        final List<String> entries;
        try {
            entries = CONFIG_JSON.readValue(raw, new TypeReference<List<String>>() { });
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException(ENV_PCEP_PERMITTED_PEERS
                    + " must be a JSON array of X.500 certificate subject strings when "
                    + ENV_PCEP_TLS_KEYSTORE + " is set", invalid);
        }

        Set<String> subjects = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException(ENV_PCEP_PERMITTED_PEERS
                        + " certificate subject entries must not be blank");
            }
            try {
                subjects.add(new X500Principal(entry.trim()).getName(X500Principal.CANONICAL));
            } catch (IllegalArgumentException invalidSubject) {
                throw new IllegalArgumentException(ENV_PCEP_PERMITTED_PEERS
                        + " contains an invalid X.500 certificate subject: " + entry,
                        invalidSubject);
            }
        }
        return Set.copyOf(subjects);
    }

    /**
     * Whether a string is an IP literal, without ever resolving a name.
     *
     * <p>{@code InetAddress.getByName} would perform a DNS lookup for anything that is not a
     * literal, which turns parsing a configuration string into a network call at startup. A
     * hostname is also the wrong thing to permit here: it would be resolved once and the
     * allowlist would then silently disagree with whatever the name points at later.
     */
    private static boolean isIpLiteral(String value) {
        if (value.isEmpty()) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            try {
                java.net.InetAddress.getByName(value);
                return true;
            } catch (java.net.UnknownHostException e) {
                return false;
            }
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int index = 0; index < octet.length(); index++) {
                if (!Character.isDigit(octet.charAt(index))) {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the controller may install computed schedules itself.
     *
     * <p>SINGLE_PCC dispatches only while exactly one PCC session is established. With none there
     * is nowhere to send; with several there is no basis for choosing, and picking arbitrarily
     * would install a client's traffic on a router nobody nominated. A richer selection policy --
     * ingress node to session, say -- is a topology-model question this deliberately does not
     * pre-empt.
     *
     * <p>This javadoc previously sat above {@code getPcepPermittedPeers()} in a run of three
     * consecutive comment blocks, where only the last one documented anything. It describes this
     * method.
     */
    /** Solves per minute per principal, or 0 when unlimited. */
    public int getApiSolveQuotaPerMinute() {
        return apiSolveQuotaPerMinute;
    }

    /** Named API credentials, or null when only the single key is configured. */
    public String getApiPrincipals() {
        return apiPrincipals;
    }

    /** Whether the northbound API serves HTTPS. */
    public boolean isApiTlsEnabled() {
        return apiTlsKeyStore != null;
    }

    public java.util.Optional<String> getApiTlsKeyStore() {
        return java.util.Optional.ofNullable(apiTlsKeyStore);
    }

    public char[] getApiTlsKeyStorePassword() {
        return apiTlsKeyStorePassword == null ? new char[0] : apiTlsKeyStorePassword.toCharArray();
    }

    /** Whether PCEPS is configured, which changes what the peer allowlist means. */
    public boolean isPcepTlsEnabled() {
        return pcepTlsKeyStore != null;
    }

    public java.util.Optional<String> getPcepTlsKeyStore() {
        return java.util.Optional.ofNullable(pcepTlsKeyStore);
    }

    public java.util.Optional<String> getPcepTlsTrustStore() {
        return java.util.Optional.ofNullable(pcepTlsTrustStore);
    }

    public char[] getPcepTlsKeyStorePassword() {
        return pcepTlsKeyStorePassword == null
                ? new char[0] : pcepTlsKeyStorePassword.toCharArray();
    }

    public char[] getPcepTlsTrustStorePassword() {
        return pcepTlsTrustStorePassword == null
                ? new char[0] : pcepTlsTrustStorePassword.toCharArray();
    }

    public boolean isAutoInstallEnabled() {
        return "SINGLE_PCC".equals(pcepAutoInstall);
    }

    public String getAutoInstallMode() {
        return pcepAutoInstall;
    }

    /**
     * True when the operator asked for the Java southbound listener.
     *
     * <p>Off by default, so it is opt-in rather than something a deployment acquires by
     * upgrading. It is the implementation wired to the correctness machinery: PCRpt reports
     * decoded into the intent ledger, end-of-synchronisation reconciliation, and acknowledgement
     * expiry. The {@code PYTHON} alternative encodes OPEN, KEEPALIVE and PCInitiate but processes
     * no reports, so nothing behind it updates installation intent.
     *
     * <p>Its loop is confirmed outside this repository by an enforcing CI gate in which FRRouting
     * {@code pathd} establishes a session, synchronises, accepts a PCInitiate and creates the
     * resulting LSP -- not end to end, since that fixture has no forwarding plane.
     */
    public boolean isJavaPcepListenerEnabled() {
        return "JAVA".equals(pcepListener);
    }

    public int getPcepPort() {
        return pcepPort;
    }

    public String getPcepBindAddress() {
        return pcepBind;
    }

    public int getPcepAckTimeoutSec() {
        return pcepAckTimeoutSec;
    }

    public int getPcepMaxSessions() {
        return pcepMaxSessions;
    }

    /**
     * Parses the solve budget. {@code 0} is accepted and means unbounded, which is the historical
     * behavior and remains available for reproducing long benchmark runs.
     */
    private static int resolveSolveTimeoutSec(Map<String, String> environment) {
        String raw = trimmedOrNull(environment.get(ENV_SOLVE_TIMEOUT_SEC));
        if (raw == null) {
            return DEFAULT_SOLVE_TIMEOUT_SEC;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format(
                    "%s must be a whole number of seconds, got '%s'", ENV_SOLVE_TIMEOUT_SEC, raw));
        }
        if (parsed < 0 || parsed > MAX_SOLVE_TIMEOUT_SEC) {
            throw new IllegalArgumentException(String.format(
                    "%s must be between 0 (unbounded) and %d seconds, got %d",
                    ENV_SOLVE_TIMEOUT_SEC, MAX_SOLVE_TIMEOUT_SEC, parsed));
        }
        return parsed;
    }

    /** Parses and validates configuration from the live process environment. */
    public static OperatorConfiguration fromProcessEnvironment() {
        return parse(System.getenv());
    }

    private static String resolveName(
            Map<String, String> environment, String key, String defaultName, Set<String> permitted) {
        String raw = trimmedOrNull(environment.get(key));
        if (raw == null) {
            return defaultName;
        }
        String candidate = raw.toUpperCase(java.util.Locale.ROOT);
        if (!permitted.contains(candidate)) {
            throw new IllegalArgumentException(String.format(
                    "Unsupported %s value '%s'. Supported values: %s",
                    key, raw, sortedCsv(permitted)));
        }
        return CANONICAL_NAMES.getOrDefault(candidate, candidate);
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sortedCsv(Set<String> values) {
        StringJoiner joiner = new StringJoiner(", ");
        values.stream().sorted().forEach(joiner::add);
        return joiner.toString();
    }

    /**
     * Rejects policy selections that cannot produce routes under the active topology's regime.
     *
     * <p>Bounded CGR returns no candidate routes outside {@link ContactRegime#R_DET}. Allowing that
     * combination would report every workload as unadmitted with no indication that the cause was
     * configuration rather than capacity.
     */
    public void validateForRegime(ContactRegime regime) {
        if (regime == null) {
            throw new IllegalArgumentException("active topology regime is required");
        }
        String name = routePolicyNameFor(regime);
        Set<ContactRegime> supported = ROUTE_POLICIES.get(name).supportedRegimes();
        if (!supported.contains(regime)) {
            throw new IllegalArgumentException(String.format(
                    "%s=%s is not supported under contact regime %s. %s supports: %s",
                    ENV_ROUTE_GEN_POLICY, name, regime, name,
                    supported.stream().map(Enum::name).sorted().reduce((a, b) -> a + ", " + b).orElse("none")));
        }
    }

    /**
     * Route policy the controller uses under {@code regime} when {@link #ENV_ROUTE_GEN_POLICY} is
     * unset.
     *
     * <p>The scheduled-contact regimes need a contact-aware generator: the time-blind
     * {@code BFS_MIN_HOP} proposes one minimum-hop path, and when that path's contacts come too late
     * the task is refused with no path even though a longer route in time would deliver it. {@code
     * R_STATIC} has no contact schedule and keeps {@code BFS_MIN_HOP}, the controller's long-standing
     * default there.
     */
    public static String controllerDefaultRoutePolicyName(ContactRegime regime) {
        if (regime == null) {
            throw new IllegalArgumentException("regime is required");
        }
        return regime == ContactRegime.R_STATIC
                ? DEFAULT_STATIC_ROUTE_POLICY : defaultRoutePolicyNameForRegime(regime);
    }

    /** The route policy in force under {@code regime}: the configured one, else the regime default. */
    public String routePolicyNameFor(ContactRegime regime) {
        return routePolicyName != null ? routePolicyName : controllerDefaultRoutePolicyName(regime);
    }

    /**
     * This configuration with the route policy resolved for the active topology's regime, so that
     * {@link #describe()} and {@code /api/v1/config} report the concrete policy in force. An explicitly
     * configured policy is kept as is.
     */
    public OperatorConfiguration forRegime(ContactRegime regime) {
        if (regime == null) {
            throw new IllegalArgumentException("active topology regime is required");
        }
        if (routePolicyName != null) {
            return this;
        }
        return new OperatorConfiguration(this, controllerDefaultRoutePolicyName(regime));
    }

    /** Applies every resolved policy to the engine. */
    public CRPEngine applyTo(CRPEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine is required");
        }
        RouteGenerationPolicy routes = routePolicyName == null
                ? REGIME_DEFAULT_ROUTES : ROUTE_POLICIES.get(routePolicyName).policy();
        return engine
                .withFSelect(TASK_POLICIES.get(taskPolicyName))
                .withFGenerate(routes)
                .withFRoute(PATH_POLICIES.get(pathPolicyName))
                .withFProp(RATE_POLICIES.get(ratePolicyName))
                .withUtilizationHeadroom(utilizationHeadroom)
                .withHeadroomEnforcement(CRPEngine.HeadroomEnforcement.valueOf(headroomEnforcement))
                .withTransportMargin(CRPEngine.TransportMargin.valueOf(transportMargin), transportMarginValue)
                .withSolveTimeout(java.time.Duration.ofSeconds(solveTimeoutSec));
    }

    /** Largest fraction of residual link capacity admission may commit. */
    public double getUtilizationHeadroom() { return utilizationHeadroom; }

    /** Where the headroom is checked; see {@link #ENV_HEADROOM_ENFORCEMENT}. */
    public String getHeadroomEnforcement() { return headroomEnforcement; }

    /** How much faster than the policy's rate to send; see {@link #ENV_TRANSPORT_MARGIN}. */
    public String getTransportMargin() { return transportMargin; }

    public double getTransportMarginValue() { return transportMarginValue; }

    /** Solve budget in seconds; {@code 0} means unbounded. */
    public int getSolveTimeoutSec() {
        return solveTimeoutSec;
    }

    public Optional<String> getTopologyFile() { return Optional.ofNullable(topologyFile); }
    public Optional<String> getStatePath() { return Optional.ofNullable(statePath); }

    /** True when the operator selected the experimental shared Postgres reservation backend. */
    public boolean isPostgresBackend() { return "POSTGRES".equals(reservationBackend); }

    public Optional<String> getPostgresUrl() { return Optional.ofNullable(postgresUrl); }

    public Optional<String> getPostgresUser() { return Optional.ofNullable(postgresUser); }

    /** The Postgres password, if configured. Never returned by {@link #describe()}. */
    public Optional<String> getPostgresPassword() { return Optional.ofNullable(postgresPassword); }
    /**
     * The route policy in force, or {@link #REGIME_DEFAULT_ROUTE_POLICY} when none was configured and
     * this configuration has not been bound with {@link #forRegime}.
     */
    public String getRoutePolicyName() {
        return routePolicyName != null ? routePolicyName : REGIME_DEFAULT_ROUTE_POLICY;
    }
    public String getRatePolicyName() { return ratePolicyName; }
    public String getTaskPolicyName() { return taskPolicyName; }
    public String getPathPolicyName() { return pathPolicyName; }

    /** Ordered view of the effective configuration for operational reporting. */
    public Map<String, String> describe() {
        Map<String, String> effective = new LinkedHashMap<>();
        effective.put("topologySource", topologyFile == null ? "built-in:OARNet" : topologyFile);
        effective.put("statePath", statePath == null ? "in-memory" : statePath);
        effective.put("reservationBackend", reservationBackend);
        if (isPostgresBackend()) {
            // URL and user are operational context; the password is a secret and never logged.
            effective.put("postgresUrl", postgresUrl);
            effective.put("postgresUser", postgresUser == null ? "(default)" : postgresUser);
        }
        effective.put("taskSelectionPolicy", taskPolicyName);
        effective.put("routeGenerationPolicy", getRoutePolicyName());
        effective.put("pathSelectionPolicy", pathPolicyName);
        effective.put("rateAssignmentPolicy", ratePolicyName);
        effective.put("utilizationHeadroom", String.valueOf(utilizationHeadroom));
        effective.put("headroomEnforcement", headroomEnforcement);
        effective.put("transportMargin", "NONE".equals(transportMargin)
                ? "NONE" : transportMargin + " " + transportMarginValue);
        effective.put("solveTimeoutSec", solveTimeoutSec == 0 ? "unbounded" : String.valueOf(solveTimeoutSec));
        effective.put("pcepListener", pcepListener);
        if (isJavaPcepListenerEnabled()) {
            effective.put("pcepBind", pcepBind + ":" + pcepPort);
            effective.put("pcepAckTimeoutSec", String.valueOf(pcepAckTimeoutSec));
        }
        return effective;
    }

    /**
     * Route-generation policy the external-file CLI uses for a regime when the operator has not
     * configured one explicitly.
     *
     * <p>Bounded CGR is contact-aware and serves the scheduled-contact regimes {@link
     * ContactRegime#R_DET} and {@link ContactRegime#R_STOCH} (they share the temporal topology; R_STOCH
     * adds per-contact failure risk that admission, not routing, handles). {@code R_STATIC} has no
     * contact schedule to reason over, so it falls back to bounded simple-path enumeration.
     */
    public static String defaultRoutePolicyNameForRegime(ContactRegime regime) {
        if (regime == null) {
            throw new IllegalArgumentException("regime is required");
        }
        return regime == ContactRegime.R_DET || regime == ContactRegime.R_STOCH
                ? "BOUNDED_CGR" : "BOUNDED_PATH_ENUMERATION";
    }

    /** Resolves a route-generation policy by its operator-facing name. */
    public static RouteGenerationPolicy routePolicyByName(String name) {
        RoutePolicyBinding binding = name == null
                ? null : ROUTE_POLICIES.get(name.toUpperCase(java.util.Locale.ROOT));
        if (binding == null) {
            throw new IllegalArgumentException(String.format(
                    "Unsupported route generation policy '%s'. Supported values: %s",
                    name, sortedCsv(ROUTE_POLICIES.keySet())));
        }
        return binding.policy();
    }

    /** Supported values per configuration key, for operator diagnostics. */
    public static Map<String, String> supportedValues() {
        Map<String, String> supported = new LinkedHashMap<>();
        supported.put(ENV_TASK_SELECT_POLICY, sortedCsv(TASK_POLICIES.keySet()));
        supported.put(ENV_ROUTE_GEN_POLICY, sortedCsv(ROUTE_POLICIES.keySet()));
        supported.put(ENV_PATH_SELECT_POLICY, sortedCsv(PATH_POLICIES.keySet()));
        supported.put(ENV_RATE_ASSIGN_POLICY, sortedCsv(RATE_POLICIES.keySet()));
        supported.put(ENV_HEADROOM_ENFORCEMENT, sortedCsv(HEADROOM_ENFORCEMENTS));
        supported.put(ENV_TRANSPORT_MARGIN, sortedCsv(TRANSPORT_MARGINS));
        supported.put(ENV_PCEP_LISTENER, sortedCsv(PCEP_LISTENERS));
        return supported;
    }

    private record RoutePolicyBinding(RouteGenerationPolicy policy, Set<ContactRegime> supportedRegimes) {}

    private static final class EnumSetOf {
        private static Set<ContactRegime> allRegimes() {
            return Set.of(ContactRegime.R_STATIC, ContactRegime.R_DET, ContactRegime.R_STOCH);
        }
    }
}
