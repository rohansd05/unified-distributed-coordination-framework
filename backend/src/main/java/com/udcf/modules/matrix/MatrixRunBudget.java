package com.udcf.modules.matrix;

import com.udcf.modules.mpi.MpiModule;
import com.udcf.modules.mpi.MpiProperties;
import com.udcf.modules.rmi.RmiProperties;
import com.udcf.modules.rmi.RmiTransportProperties;

import java.util.Objects;

/**
 * How long one matrix run can last, derived from the configured timeouts (step E10b). Bounds and
 * allowances, never measurements.
 *
 * <ul>
 *   <li>{@link #transferWorstMillis()} = {@link MpiModule#EXCHANGES_PER_TRANSFER} (3: the registry lookup,
 *       RMI's DGC dirty call, the method) x (connect + call timeout), as Experiment 9: 12000 ms locally.</li>
 *   <li>{@link #computeWaitMillis()} = the same: one compute call is one transfer, and the node always
 *       answers within compute-timeout-millis, which is below the call timeout. The root stops waiting for
 *       the compute phase after this long.</li>
 *   <li>{@link #productAllowanceMillis()} = (warmup-products + 1) x compute-timeout-millis: the warm-up
 *       products and the sequential product. An <b>allowance, not an enforced bound</b>: they run on the
 *       caller's thread and are never interrupted.</li>
 *   <li>{@link #worstCaseRunMillis()} = allowance + deadline + transferWorst + 1. One run deadline
 *       ({@code udcf.mpi.collective-deadline-millis}) covers the scatter, the broadcast, the compute dispatch
 *       and every fetch; once it has passed nothing new starts, and the one operation in flight is bounded by
 *       transferWorst. The 1 ms is the rounding of the deadline handed to each collective (whole
 *       milliseconds, rounded up). Locally (1 + 1) x 2000 + 10000 + 12000 + 1 = 26001 ms.</li>
 * </ul>
 *
 * @param connectTimeoutMillis {@code udcf.rmi.connect-timeout-millis}
 * @param callTimeoutMillis    {@code udcf.rmi.call-timeout-millis}
 * @param deadlineMillis       {@code udcf.mpi.collective-deadline-millis}, the run deadline
 * @param computeTimeoutMillis {@code udcf.matrix.compute-timeout-millis}
 * @param warmupProducts       {@code udcf.matrix.warmup-products}
 */
public record MatrixRunBudget(int connectTimeoutMillis, int callTimeoutMillis, int deadlineMillis, int computeTimeoutMillis,
                              int warmupProducts) {

    public MatrixRunBudget {
        if (connectTimeoutMillis < 1 || callTimeoutMillis < 1 || deadlineMillis < 1 || computeTimeoutMillis < 1) {
            throw new IllegalArgumentException("every timeout must be >= 1 ms");
        }
        if (warmupProducts < 0) {
            throw new IllegalArgumentException("warmupProducts must be >= 0, was " + warmupProducts);
        }
    }

    /** The budget of these settings. */
    public static MatrixRunBudget of(RmiProperties rmi, RmiTransportProperties transport, MpiProperties mpi,
                                     MatrixRunProperties run) {
        Objects.requireNonNull(rmi, "rmi must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(mpi, "mpi must not be null");
        Objects.requireNonNull(run, "run must not be null");
        return new MatrixRunBudget(transport.connectTimeoutMillis(), rmi.callTimeoutMillis(), mpi.collectiveDeadlineMillis(),
                run.computeTimeoutMillis(), run.warmupProducts());
    }

    public long transferWorstMillis() {
        return MpiModule.EXCHANGES_PER_TRANSFER * ((long) connectTimeoutMillis + callTimeoutMillis);
    }

    public long computeWaitMillis() {
        return transferWorstMillis();
    }

    public long productAllowanceMillis() {
        return (warmupProducts + 1L) * computeTimeoutMillis;
    }

    public long worstCaseRunMillis() {
        return productAllowanceMillis() + deadlineMillis + transferWorstMillis() + 1;
    }
}
