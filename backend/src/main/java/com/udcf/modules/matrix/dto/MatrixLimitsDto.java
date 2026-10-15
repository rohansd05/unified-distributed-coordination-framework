package com.udcf.modules.matrix.dto;

import java.util.List;

/**
 * The configured limits of Experiment 10 and the run budget derived from them (E10b's MatrixRunBudget).
 * Every budget figure is a BOUND or allowance derived from configured timeouts, not a measurement.
 *
 * <p>No dedicated test: a record; tested through MatrixModuleTest and MatrixControllerTest.</p>
 *
 * @param maxSize                           floor(sqrt(udcf.mpi.max-payload-elements)), derived
 * @param maxSeed                           the largest seed (2^53 - 1)
 * @param runDeadlineMillis                 no new phase step starts after this, from just before the scatter
 * @param productAllowanceMillis            the warm-up and sequential products: an allowance, not an enforced bound
 * @param worstCaseRunMillis                allowance + deadline + one transfer + 1
 * @param recommendedMinClientTimeoutMillis worstCaseRunMillis, exactly; a client adds its own margin
 */
public record MatrixLimitsDto(int minSize, int maxSize, List<Integer> seriesSizes, int historySize, long maxSeed,
                              int warmupProducts, int computeTimeoutMillis, int resultStoreSize, int runDeadlineMillis,
                              int connectTimeoutMillis, int callTimeoutMillis, long transferWorstMillis,
                              long computeWaitMillis, long productAllowanceMillis, long worstCaseRunMillis,
                              long recommendedMinClientTimeoutMillis) {
}
