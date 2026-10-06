package net.dcn.pce.config;

import net.dcn.pce.model.ContactRegime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration behavior is exercised through explicit environment snapshots, so these tests
 * never mutate the process environment and stay order-independent.
 */
class OperatorConfigurationTest {

    @Test
    void emptyEnvironmentResolvesToEngineDefaults() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of());

        assertEquals("LWEEF", configuration.getTaskPolicyName());
        // The route default depends on the topology's regime, so it is not resolved at parse time.
        assertEquals(OperatorConfiguration.REGIME_DEFAULT_ROUTE_POLICY, configuration.getRoutePolicyName());
        assertEquals("EAP", configuration.getPathPolicyName());
        assertEquals("DATA_FLOW_EQUILIBRIUM", configuration.getRatePolicyName());
        assertTrue(configuration.getTopologyFile().isEmpty());
        assertTrue(configuration.getStatePath().isEmpty());
    }

    @Test
    void sessionCapDefaultsToSixtyFourAndIsOperatorTunable() {
        assertEquals(64, OperatorConfiguration.parse(Map.of()).getPcepMaxSessions());
        assertEquals(500, OperatorConfiguration.parse(
                Map.of("VORTEX_PCEP_MAX_SESSIONS", "500")).getPcepMaxSessions());
    }

    @Test
    void sessionCapOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OperatorConfiguration.parse(Map.of("VORTEX_PCEP_MAX_SESSIONS", "0")));
    }

    @Test
    void everyPolicyStageIsOperatorConfigurable() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_TASK_SELECT_POLICY, "FCFS",
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "BOUNDED_CGR",
                OperatorConfiguration.ENV_PATH_SELECT_POLICY, "MIN_HOP",
                OperatorConfiguration.ENV_RATE_ASSIGN_POLICY, "LINE_RATE"));

        assertEquals("FCFS", configuration.getTaskPolicyName());
        assertEquals("BOUNDED_CGR", configuration.getRoutePolicyName());
        assertEquals("MIN_HOP", configuration.getPathPolicyName());
        assertEquals("LINE_RATE", configuration.getRatePolicyName());
    }

    @Test
    void policyNamesAreCaseInsensitiveAndTrimmed() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "  bounded_cgr  "));

        assertEquals("BOUNDED_CGR", configuration.getRoutePolicyName());
    }

    @Test
    void blankValueFallsBackToTheDefaultRatherThanFailing() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "   ",
                OperatorConfiguration.ENV_TOPOLOGY_FILE, ""));

        assertEquals(OperatorConfiguration.REGIME_DEFAULT_ROUTE_POLICY, configuration.getRoutePolicyName());
        assertEquals("BFS_MIN_HOP", configuration.forRegime(ContactRegime.R_STATIC).getRoutePolicyName());
        assertTrue(configuration.getTopologyFile().isEmpty());
    }

    @Test
    void unsetRoutePolicyResolvesToTheRegimeDefault() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of());

        assertEquals("BOUNDED_CGR", configuration.forRegime(ContactRegime.R_DET).getRoutePolicyName());
        assertEquals("BOUNDED_CGR", configuration.forRegime(ContactRegime.R_STOCH).getRoutePolicyName());
        assertEquals("BFS_MIN_HOP", configuration.forRegime(ContactRegime.R_STATIC).getRoutePolicyName());
        for (ContactRegime regime : ContactRegime.values()) {
            assertEquals(OperatorConfiguration.controllerDefaultRoutePolicyName(regime),
                    configuration.routePolicyNameFor(regime));
            configuration.validateForRegime(regime);
            OperatorConfiguration bound = configuration.forRegime(regime);
            bound.validateForRegime(regime);
            assertEquals(bound.getRoutePolicyName(), bound.describe().get("routeGenerationPolicy"),
                    "/api/v1/config must name the concrete policy in force");
        }
    }

    @Test
    void explicitRoutePolicyIsNeverReplacedByTheRegimeDefault() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "BFS_MIN_HOP"));

        for (ContactRegime regime : ContactRegime.values()) {
            assertEquals("BFS_MIN_HOP", configuration.forRegime(regime).getRoutePolicyName());
            assertEquals("BFS_MIN_HOP", configuration.routePolicyNameFor(regime));
        }
    }

    @Test
    void bindingToARegimeKeepsEveryOtherSetting() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_TASK_SELECT_POLICY, "FCFS",
                OperatorConfiguration.ENV_RATE_ASSIGN_POLICY, "LINE_RATE",
                OperatorConfiguration.ENV_TOPOLOGY_FILE, "/etc/vortex/topology.json",
                OperatorConfiguration.ENV_STATE_PATH, "/var/lib/vortex/state.json",
                "VORTEX_SOLVE_TIMEOUT_SEC", "17",
                "VORTEX_PCEP_MAX_SESSIONS", "500"));
        OperatorConfiguration bound = configuration.forRegime(ContactRegime.R_DET);

        Map<String, String> before = new java.util.LinkedHashMap<>(configuration.describe());
        Map<String, String> after = new java.util.LinkedHashMap<>(bound.describe());
        before.remove("routeGenerationPolicy");
        after.remove("routeGenerationPolicy");
        assertEquals(before, after);
        assertEquals(500, bound.getPcepMaxSessions());
        assertEquals(17, bound.getSolveTimeoutSec());
    }

    /**
     * S reaches D directly, but the S-D contact opens after the deadline; S-A-D delivers in time.
     * The time-blind minimum-hop generator proposes only S-D, so the task is refused.
     */
    private static final String LATE_DIRECT_CONTACT = """
            {"regime": "R_DET",
             "nodes": [
              {"nodeId": "S", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
              {"nodeId": "A", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9},
              {"nodeId": "D", "serviceRateBps": 1e9, "reservoirCapacityBytes": 1e9}],
             "links": [
              {"linkId": "S-D", "sourceNodeId": "S", "destinationNodeId": "D", "baseBandwidthBps": 1e6,
               "propagationDelaySec": 0, "contacts": [{"startSec": 100, "endSec": 110}]},
              {"linkId": "S-A", "sourceNodeId": "S", "destinationNodeId": "A", "baseBandwidthBps": 1e6,
               "propagationDelaySec": 0, "contacts": [{"startSec": 0, "endSec": 10}]},
              {"linkId": "A-D", "sourceNodeId": "A", "destinationNodeId": "D", "baseBandwidthBps": 1e6,
               "propagationDelaySec": 0, "contacts": [{"startSec": 20, "endSec": 30}]}]}
            """;

    private static net.dcn.pce.crp.CRPEngine.PCEComputationResult solveLateDirectContact(
            OperatorConfiguration configuration) {
        net.dcn.pce.model.BaseTopology topology =
                net.dcn.pce.topology.FileTopologyParser.parseTopologyJson(LATE_DIRECT_CONTACT);
        return configuration.applyTo(new net.dcn.pce.crp.CRPEngine()).solve(topology, java.util.List.of(
                new net.dcn.pce.model.WorkloadTask("t", "S", "D", 0.0, 50.0, 1000.0)));
    }

    @Test
    void defaultRoutePolicyDeliversOverAContactPlanWhereMinimumHopIsRefused() {
        assertEquals(1, solveLateDirectContact(OperatorConfiguration.parse(Map.of(
                        OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "BFS_MIN_HOP")))
                        .getUnadmittedTasks().size(),
                "the minimum-hop route's only contact is after the deadline");

        OperatorConfiguration unset = OperatorConfiguration.parse(Map.of());
        for (OperatorConfiguration configuration : new OperatorConfiguration[]{
                unset, unset.forRegime(ContactRegime.R_DET)}) {
            var result = solveLateDirectContact(configuration);
            assertEquals(0, result.getUnadmittedTasks().size(), configuration.getRoutePolicyName());
            assertEquals(1, result.getCommittedSchedules().size(), configuration.getRoutePolicyName());
        }
    }

    @Test
    void unknownRoutePolicyIsRejectedInsteadOfSilentlyDefaulting() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OperatorConfiguration.parse(Map.of(
                        OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "TOTALLY_MADE_UP")));

        assertTrue(error.getMessage().contains("TOTALLY_MADE_UP"));
        assertTrue(error.getMessage().contains("BOUNDED_CGR"), "error should list supported values");
    }

    @Test
    void unknownValueIsRejectedForEveryPolicyStage() {
        for (String key : new String[]{
                OperatorConfiguration.ENV_TASK_SELECT_POLICY,
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY,
                OperatorConfiguration.ENV_PATH_SELECT_POLICY,
                OperatorConfiguration.ENV_RATE_ASSIGN_POLICY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> OperatorConfiguration.parse(Map.of(key, "NOT_A_POLICY")),
                    key + " should reject unknown values");
        }
    }

    @Test
    void boundedCgrServesScheduledContactRegimesButNotStatic() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "BOUNDED_CGR"));

        // Contact-aware routing serves both scheduled-contact regimes; they share the temporal
        // topology and differ only in per-contact failure risk, which admission handles.
        configuration.validateForRegime(ContactRegime.R_DET);
        configuration.validateForRegime(ContactRegime.R_STOCH);

        // R_STATIC has no contact schedule to reason over, so bounded CGR is rejected there.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> configuration.validateForRegime(ContactRegime.R_STATIC));
        assertTrue(error.getMessage().contains("BOUNDED_CGR"));
        assertTrue(error.getMessage().contains(ContactRegime.R_STATIC.name()));
    }

    @Test
    void regimeAgnosticPoliciesValidateUnderEveryRegime() {
        OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "BOUNDED_PATH_ENUMERATION"));

        for (ContactRegime regime : ContactRegime.values()) {
            configuration.validateForRegime(regime);
        }
    }

    @Test
    void externalFileDefaultKeepsNonDeterministicRegimesRoutable() {
        assertEquals("BOUNDED_CGR",
                OperatorConfiguration.defaultRoutePolicyNameForRegime(ContactRegime.R_DET));
        assertEquals("BOUNDED_CGR",
                OperatorConfiguration.defaultRoutePolicyNameForRegime(ContactRegime.R_STOCH));
        assertEquals("BOUNDED_PATH_ENUMERATION",
                OperatorConfiguration.defaultRoutePolicyNameForRegime(ContactRegime.R_STATIC));
    }

    @Test
    void everyRegimeDefaultIsValidUnderThatRegime() {
        for (ContactRegime regime : ContactRegime.values()) {
            OperatorConfiguration configuration = OperatorConfiguration.parse(Map.of(
                    OperatorConfiguration.ENV_ROUTE_GEN_POLICY,
                    OperatorConfiguration.defaultRoutePolicyNameForRegime(regime)));
            configuration.validateForRegime(regime);
        }
    }

    @Test
    void describeReportsEffectiveConfigurationIncludingDefaults() {
        Map<String, String> effective = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_ROUTE_GEN_POLICY, "BOUNDED_CGR",
                OperatorConfiguration.ENV_TOPOLOGY_FILE, "/etc/vortex/topology.json")).describe();

        assertEquals("/etc/vortex/topology.json", effective.get("topologySource"));
        assertEquals("BOUNDED_CGR", effective.get("routeGenerationPolicy"));
        assertEquals("DATA_FLOW_EQUILIBRIUM", effective.get("rateAssignmentPolicy"));
        assertEquals("in-memory", effective.get("statePath"));
        assertEquals("built-in:OARNet",
                OperatorConfiguration.parse(Map.of()).describe().get("topologySource"));
    }

    @Test
    void aliasesAreReportedUnderTheirCanonicalPolicyName() {
        for (String alias : new String[]{"EQM", "EQUILIBRIUM", "DATA_FLOW_EQUILIBRIUM"}) {
            assertEquals("DATA_FLOW_EQUILIBRIUM", OperatorConfiguration.parse(Map.of(
                    OperatorConfiguration.ENV_RATE_ASSIGN_POLICY, alias)).getRatePolicyName());
        }
        assertEquals("FCFS", OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_TASK_SELECT_POLICY, "EWOF")).getTaskPolicyName());
        assertEquals("EAP", OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_PATH_SELECT_POLICY, "OCC")).getPathPolicyName());
    }

    @Test
    void unknownRoutePolicyLookupByNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OperatorConfiguration.routePolicyByName("NOPE"));
        assertThrows(IllegalArgumentException.class,
                () -> OperatorConfiguration.routePolicyByName(null));
    }

    @Test
    void supportedValuesCoverEveryConfigurableStage() {
        Map<String, String> supported = OperatorConfiguration.supportedValues();

        assertTrue(supported.get(OperatorConfiguration.ENV_ROUTE_GEN_POLICY).contains("BOUNDED_CGR"));
        assertTrue(supported.get(OperatorConfiguration.ENV_TASK_SELECT_POLICY).contains("LWEEF"));
        assertTrue(supported.get(OperatorConfiguration.ENV_PATH_SELECT_POLICY).contains("EAP"));
        assertTrue(supported.get(OperatorConfiguration.ENV_RATE_ASSIGN_POLICY).contains("LINE_RATE"));
        assertFalse(supported.get(OperatorConfiguration.ENV_ROUTE_GEN_POLICY).contains("NOT_A_POLICY"));
    }

    @Test
    void theLegacyPythonListenerMustBeNamedRatherThanInherited() {
        // DISABLED used to start the Python PCEP server, so an unconfigured container opened 4189
        // with an implementation that processes no PCRpt or PCUpd -- a southbound port with no
        // intent ledger behind it. Selecting it is now an explicit choice, which needs the value
        // to be accepted here as well as understood by the entrypoint.
        String listeners = OperatorConfiguration.supportedValues()
                .get(OperatorConfiguration.ENV_PCEP_LISTENER);
        assertTrue(listeners.contains("PYTHON"), "PYTHON must be selectable: " + listeners);
        assertTrue(listeners.contains("DISABLED"), "DISABLED must remain selectable: " + listeners);
        assertTrue(listeners.contains("JAVA"), "JAVA must remain selectable: " + listeners);

        OperatorConfiguration configured = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_PCEP_LISTENER, "PYTHON"));
        assertEquals("PYTHON", configured.describe().get("pcepListener"));
        assertFalse(configured.isJavaPcepListenerEnabled(),
                "selecting the legacy server must not enable the Java listener");
    }

    @Test
    void reservationBackendDefaultsToFile() {
        OperatorConfiguration c = OperatorConfiguration.parse(Map.of());
        assertEquals(false, c.isPostgresBackend());
        assertEquals("FILE", c.describe().get("reservationBackend"));
    }

    @Test
    void postgresBackendRequiresAUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> OperatorConfiguration.parse(Map.of("VORTEX_RESERVATION_BACKEND", "postgres")));
    }

    @Test
    void postgresBackendParsesAndNeverLogsThePassword() {
        OperatorConfiguration c = OperatorConfiguration.parse(Map.of(
                "VORTEX_RESERVATION_BACKEND", "postgres",
                "VORTEX_POSTGRES_URL", "jdbc:postgresql://db:5432/vortex",
                "VORTEX_POSTGRES_USER", "pce",
                "VORTEX_POSTGRES_PASSWORD", "s3cret"));
        assertEquals(true, c.isPostgresBackend());
        assertEquals("jdbc:postgresql://db:5432/vortex", c.getPostgresUrl().orElseThrow());
        assertEquals("pce", c.getPostgresUser().orElseThrow());
        assertEquals("s3cret", c.getPostgresPassword().orElseThrow());
        // The password is a secret: it must never appear in the effective-config description.
        assertEquals(false, c.describe().values().stream().anyMatch(v -> v.contains("s3cret")));
        assertEquals("jdbc:postgresql://db:5432/vortex", c.describe().get("postgresUrl"));
    }
}
