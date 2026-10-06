package net.dcn.pce.metrics;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerMetricsTest {

    private static double sampleValue(String exposition, String series) {
        Matcher matcher = Pattern.compile(
                        "^" + Pattern.quote(series) + " ([0-9.eE+-]+)$", Pattern.MULTILINE)
                .matcher(exposition);
        assertTrue(matcher.find(), "series not present: " + series);
        return Double.parseDouble(matcher.group(1));
    }

    @Test
    void everyOutcomeSeriesIsPreRegisteredSoAlertsEvaluateBeforeFirstOccurrence() {
        String exposition = new ControllerMetrics().render(0, 0, false, 0, 0);

        for (ControllerMetrics.SolveOutcome outcome : ControllerMetrics.SolveOutcome.values()) {
            assertEquals(0.0, sampleValue(exposition,
                    "vortex_solve_requests_total{outcome=\"" + outcome.label() + "\"}"));
        }
    }

    @Test
    void solveResultsPopulateAdmissionAndDeadlineCounters() {
        ControllerMetrics metrics = new ControllerMetrics();
        metrics.recordSolveResult(10, 7, 3, 5, 1.5);
        metrics.recordSolveResult(4, 4, 0, 4, 0.2);

        String exposition = metrics.render(12, 8, false, 0, 1);

        assertEquals(14.0, sampleValue(exposition, "vortex_workloads_offered_total"));
        assertEquals(11.0, sampleValue(exposition, "vortex_workloads_committed_total"));
        assertEquals(3.0, sampleValue(exposition, "vortex_workloads_unadmitted_total"));
        assertEquals(9.0, sampleValue(exposition, "vortex_deadlines_met_total"));
        // 7 committed - 5 met, plus 4 committed - 4 met.
        assertEquals(2.0, sampleValue(exposition, "vortex_deadlines_missed_total"));
        assertEquals(12.0, sampleValue(exposition, "vortex_link_reservations"));
        assertEquals(8.0, sampleValue(exposition, "vortex_node_reservations"));
    }

    @Test
    void latencyHistogramIsCumulativeAndConsistent() {
        ControllerMetrics metrics = new ControllerMetrics();
        metrics.observeLatency(0.02);
        metrics.observeLatency(0.4);
        metrics.observeLatency(7.0);

        String exposition = metrics.render(0, 0, false, 0, 0);

        assertEquals(3.0, sampleValue(exposition, "vortex_solve_duration_seconds_count"));
        assertEquals(3.0, sampleValue(exposition, "vortex_solve_duration_seconds_bucket{le=\"+Inf\"}"));
        assertEquals(7.42, sampleValue(exposition, "vortex_solve_duration_seconds_sum"), 1e-6);

        // Buckets are cumulative: le=0.05 counts only the 0.02 sample; le=0.5 adds the 0.4 one.
        assertEquals(1.0, sampleValue(exposition, "vortex_solve_duration_seconds_bucket{le=\"0.05\"}"));
        assertEquals(2.0, sampleValue(exposition, "vortex_solve_duration_seconds_bucket{le=\"0.5\"}"));
        assertEquals(3.0, sampleValue(exposition, "vortex_solve_duration_seconds_bucket{le=\"10.0\"}"));
    }

    @Test
    void bucketCountsNeverExceedTheTotalObservationCount() {
        ControllerMetrics metrics = new ControllerMetrics();
        for (int i = 0; i < 25; i++) {
            metrics.observeLatency(i * 0.9);
        }

        String exposition = metrics.render(0, 0, false, 0, 0);
        double total = sampleValue(exposition, "vortex_solve_duration_seconds_count");

        Matcher matcher = Pattern.compile(
                        "vortex_solve_duration_seconds_bucket\\{le=\"[^\"]+\"\\} ([0-9.]+)")
                .matcher(exposition);
        while (matcher.find()) {
            assertTrue(Double.parseDouble(matcher.group(1)) <= total,
                    "a cumulative bucket must never exceed the observation count");
        }
    }

    @Test
    void negativeAndNonFiniteLatenciesAreIgnored() {
        ControllerMetrics metrics = new ControllerMetrics();
        metrics.observeLatency(-1.0);
        metrics.observeLatency(Double.NaN);
        metrics.observeLatency(Double.POSITIVE_INFINITY);

        assertEquals(0.0, sampleValue(metrics.render(0, 0, false, 0, 0),
                "vortex_solve_duration_seconds_count"));
    }

    @Test
    void expositionCarriesTypeMetadataForEveryFamily() {
        String exposition = new ControllerMetrics().render(0, 0, true, 2, 1);

        assertTrue(exposition.contains("# TYPE vortex_solve_requests_total counter"));
        assertTrue(exposition.contains("# TYPE vortex_solve_duration_seconds histogram"));
        assertTrue(exposition.contains("# TYPE vortex_link_reservations gauge"));
        assertTrue(exposition.contains("# TYPE vortex_build_info gauge"));
        assertEquals(1.0, sampleValue(exposition, "vortex_planner_busy"));
        assertEquals(2.0, sampleValue(exposition, "vortex_http_queue_depth"));
    }

    @Test
    void buildInfoLabelsAreEscapedAndPresent() {
        String exposition = new ControllerMetrics().render(0, 0, false, 0, 0);

        Matcher matcher = Pattern.compile(
                "vortex_build_info\\{version=\"([^\"]+)\",artifact=\"([^\"]+)\"\\} 1").matcher(exposition);
        assertTrue(matcher.find(), "build info series should be present");
        assertEquals("dcn-pce-controller", matcher.group(2));
    }
}
