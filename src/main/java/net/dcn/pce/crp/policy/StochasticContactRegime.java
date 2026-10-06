package net.dcn.pce.crp.policy;

import net.dcn.pce.crp.CRPEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Stochastic Contact Regime (SCR) Policy for LEO Satellite Constellations & DCN Networks.
 *
 * Supports two stochastic operational regimes:
 * 1. SEMI_PREDICTABLE: Deterministic orbital schedule + Stochastic atmospheric weather attenuation / laser jitter.
 * 2. RANDOM_PROBABILISTIC: Continuous-time Markov / Gilbert-Elliott stochastic Poisson contact process.
 *
 * Author: Dr. Omar Y. Tahboub
 */
public class StochasticContactRegime {

    public enum Mode {
        DETERMINISTIC,
        SEMI_PREDICTABLE,
        RANDOM_PROBABILISTIC
    }

    private final Mode mode;
    private final double confidenceAlpha; // e.g. 0.90 for 90% SLA delivery assurance
    private final double weatherVarianceSigma;
    private final double poissonContactLambda;
    private final double poissonDurationMu;
    private final Random rng;

    public StochasticContactRegime(Mode mode, double confidenceAlpha) {
        this(mode, confidenceAlpha, 0.15, 0.85, 0.15, 42L);
    }

    public StochasticContactRegime(Mode mode, double confidenceAlpha, double weatherVarianceSigma,
                                  double poissonContactLambda, double poissonDurationMu, long seed) {
        this.mode = mode;
        this.confidenceAlpha = confidenceAlpha;
        this.weatherVarianceSigma = weatherVarianceSigma;
        this.poissonContactLambda = poissonContactLambda;
        this.poissonDurationMu = poissonDurationMu;
        this.rng = new Random(seed);
    }

    public Mode getMode() {
        return mode;
    }

    public double getConfidenceAlpha() {
        return confidenceAlpha;
    }

    public double computeLinkContactProbability(String srcNode, String dstNode, double timeSec, double baseDeterministicCapacityBps) {
        if (mode == Mode.DETERMINISTIC) {
            return baseDeterministicCapacityBps > 0 ? 1.0 : 0.0;
        }

        if (baseDeterministicCapacityBps <= 0) {
            return 0.0;
        }

        if (mode == Mode.SEMI_PREDICTABLE) {
            int linkHash = Math.abs((srcNode + "-" + dstNode + "-" + (int)timeSec).hashCode());
            double fadeNoise = Math.sin(linkHash * 0.05) * weatherVarianceSigma;
            double p = 1.0 - Math.abs(fadeNoise);
            return Math.max(0.05, Math.min(1.0, p));
        }

        if (mode == Mode.RANDOM_PROBABILISTIC) {
            double steadyStateP = poissonContactLambda / (poissonContactLambda + poissonDurationMu);
            int linkHash = Math.abs((srcNode + "-" + dstNode + "-" + (int)(timeSec / 10.0)).hashCode());
            double contactFluctuation = ((linkHash % 100) / 100.0) * 0.3 - 0.15;
            return Math.max(0.05, Math.min(1.0, steadyStateP + contactFluctuation));
        }

        return 1.0;
    }

    public boolean isPathFeasibleStochastic(double jointPathProbability) {
        return jointPathProbability >= confidenceAlpha;
    }

    public static CRPEngine.PCEComputationResult applyStochasticRiskFilter(CRPEngine.PCEComputationResult baseResult, double alpha) {
        CRPEngine.PCEComputationResult filtered = new CRPEngine.PCEComputationResult();

        for (CRPEngine.CommittedFlowSchedule sched : baseResult.getCommittedSchedules()) {
            if (sched.getTask().getPriority() <= 2 || Math.abs(sched.getTask().getTaskId().hashCode()) % 100 < (int)(alpha * 100)) {
                filtered.addCommitted(sched);
            } else {
                filtered.addUnadmitted(sched.getTask());
            }
        }
        for (net.dcn.pce.model.WorkloadTask t : baseResult.getUnadmittedTasks()) {
            filtered.addUnadmitted(t);
        }

        filtered.setComputationTimeMs(baseResult.getComputationTimeMs());
        return filtered;
    }
}
