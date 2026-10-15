package com.udcf.modules.matrix;

import com.udcf.modules.matrix.dto.GlossaryEntryDto;
import com.udcf.modules.matrix.dto.GlossaryEntryDto.Kind;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.rmi.dto.CoHostingDto;

import java.util.List;
import java.util.Locale;

/**
 * Every honesty text of Experiment 10 (step E10c), in one place: served in the overview and used by the
 * DTO mapper and the series, so the page never words a basis itself (R7). ASCII only.
 */
final class MatrixTexts {

    static final String OTHER_ACTIVITY = "Nothing else on the cluster is paused during a run: Experiment 1 calls, MPI "
            + "collectives, Experiment 2 requests and other experiments can use the same nodes and processors at the same "
            + "time, and any such work is included in these figures.";

    static final String PHASE_TIME_LABEL = "The scatter and broadcast times are Experiment 9's total collective times, "
            + "measured on the root from just before the first remote transfer to just after the last. They include the "
            + "root's own work between transfers (serializing each request again to size it, and computing checksums), so "
            + "they are longer than the time on the wire.";

    static final String PAYLOAD_GENERATION_NOTE = "Generating A and B is timed once on the root, before the timed parts, "
            + "for information only. The scatter and the broadcast generate A and B again from the seed inside the parallel "
            + "run; that time falls into the other time and nothing is subtracted for it.";

    static final String FIRST_RUN_NOTE = "Before every run, outside the timed parts, the module makes sure that each live "
            + "node's request listener is running, so no listener start is timed. The first run after a start or a reset "
            + "still pays first-use costs that cannot be moved out of the run: loading RMI stub classes, opening the first "
            + "connections to each node, starting the first worker thread of each node's executor, and the first use of the "
            + "collective and checksum code; these fall into the scatter, broadcast, compute-phase and gather times, never "
            + "into a node's own compute time. The multiply itself is warmed up by the warm-up products. Run a size again to "
            + "see it without these costs; the series uses the newest complete run of each size.";

    static final String COMPUTE_ROUND_TRIP_BASIS = "Measured on the root around one remote compute call: a fresh lookup of "
            + "MatrixEndpoint in the node's RMI registry (including RMI's distributed garbage collection \"dirty\" call when "
            + "this process holds no other live stub for it), the call, the node's queue wait and multiply on its Experiment 2 "
            + "executor, and the reply. The node's compute time, around the multiply only, is measured on the node.";

    static final String GATHER_LATENCY_BASIS = "Measured on the root around one fetch of a block of C: a fresh lookup of "
            + "MatrixEndpoint in the node's RMI registry (including RMI's distributed garbage collection \"dirty\" call when "
            + "this process holds no other live stub for it), then the call and its reply over a real loopback socket.";

    static final String COLLECTIVE_IDS_NOTE = "A run's scatter of A and broadcast of B use collective ids "
            + MatrixRunRequest.COLLECTIVE_ID_BASE + " + 2 x run id and that + 1, so they never meet Experiment 9's own ids, "
            + "which count from 1. The nodes keep them like any collective, so they also appear among each node's kept ids "
            + "on the MPI page.";

    static final String ALGORITHM_NOTE = "The root splits A into blocks of whole rows, one block per live node in rank "
            + "order, and scatters them; it broadcasts the whole of B; every node multiplies its rows by B at the same time on "
            + "its own Experiment 2 executor; then the root fetches the blocks of C one by one in rank order, assembles C and "
            + "compares it bit for bit with the sequential product. The scatter and the broadcast are Experiment 9's linear "
            + "collectives over Experiment 1's RMI (link L6). A failed transfer or compute leaves a hole that is reported, "
            + "never filled in.";

    static final CoHostingDto CO_HOSTING = new CoHostingDto(true, "All nodes run inside one backend process on one "
            + "computer, so they share its processors and memory: they are separate nodes sharing a machine, not separate "
            + "machines. Every scatter, broadcast, compute call and fetch still goes through the target node's own RMI "
            + "registry and a stub over a real loopback socket, so those times are real and measured; only the network "
            + "distance between the nodes is missing.");

    static final String SERIES_RULE = "For each configured series size, the newest kept run of that size that completed "
            + "and matched the sequential product. A size without such a run is listed as not run, never as 0. Runs of other "
            + "sizes are only in the history.";

    static final String RANKS_NOTE = "These points were measured with different numbers of nodes, so they are not "
            + "directly comparable.";

    static final String WITHHELD_MISMATCH = "No speedup: the parallel result did not match the sequential product.";

    static final List<GlossaryEntryDto> GLOSSARY = List.of(
            entry("sequentialNanos", "Sequential time", Kind.MEASURED_ON_ROOT, "Around the multiply of the whole matrix "
                    + "only, on the root, after the warm-up products and before the parallel run."),
            entry("payloadGenerationNanos", "Generating A and B", Kind.MEASURED_ON_ROOT, "Around generating A and B once, "
                    + "before the timed parts. Informational: the collectives generate them again inside the parallel run, "
                    + "and nothing is subtracted for it."),
            entry("scatterNanos", "Scatter of A", Kind.MEASURED_ON_ROOT, "Experiment 9's total time of the scatter, from "
                    + "just before the first remote transfer to just after the last; it includes the root's sizing and "
                    + "checksum work between transfers."),
            entry("broadcastNanos", "Broadcast of B", Kind.MEASURED_ON_ROOT, "Experiment 9's total time of the broadcast, "
                    + "from just before the first remote transfer to just after the last; it includes the root's sizing and "
                    + "checksum work between transfers."),
            entry("computePhaseNanos", "Compute phase", Kind.MEASURED_ON_ROOT, "From just before the first compute is "
                    + "dispatched to when the last compute (a node's reply or the root's own block) is known: the "
                    + "computation time of the parallel run."),
            entry("gatherNanos", "Gather of C", Kind.MEASURED_ON_ROOT, "From just before the first fetch to just after the "
                    + "last; unknown when no fetch was made."),
            entry("parallelTotalNanos", "Parallel time", Kind.MEASURED_ON_ROOT, "From just before the scatter to the end "
                    + "of the last phase that ran; one interval, not a sum."),
            entry("roundTripNanos", "Compute round trip", Kind.MEASURED_ON_ROOT, COMPUTE_ROUND_TRIP_BASIS),
            entry("latencyNanos", "Fetch latency", Kind.MEASURED_ON_ROOT, GATHER_LATENCY_BASIS),
            entry("timeToFailureNanos", "Time to failure", Kind.MEASURED_ON_ROOT, "From just before a call until its "
                    + "failure was known; not a latency."),
            entry("computeNanos", "Node compute time", Kind.MEASURED_ON_NODE, "Around that rank's multiply only, on the "
                    + "node; queue wait excluded."),
            entry("communicationNanos", "Communication time", Kind.DERIVED, "Scatter + broadcast + gather; unknown if any "
                    + "of them is unknown."),
            entry("otherNanos", "Other time", Kind.DERIVED, "Parallel time - communication time - compute phase: the root's "
                    + "own work between the phases. It can be negative because the parts are measured separately, and it is "
                    + "never clamped."),
            entry("communicationShare", "Communication share", Kind.DERIVED, "Communication time / parallel time."),
            entry("speedup", "Speedup", Kind.DERIVED, "Sequential time / parallel time: the measured speedup, only for a "
                    + "complete run whose result matched the sequential product."),
            entry("computeBoundSpeedup", "Compute-bound speedup", Kind.DERIVED, "Sequential time / the slowest node compute "
                    + "time: what the multiply alone would allow if communication cost nothing; never the measured speedup."),
            entry("multiplyAdds", "Multiply-adds", Kind.DERIVED, "n cubed, exact."));

    private MatrixTexts() {
    }

    /** The speedup basis for a run with {@code ranks} ranks: E10a's text, then the fairness and other-activity notes. */
    static String speedupBasis(SpeedupBasis basis, int ranks, int warmupProducts) {
        return basis.text(ranks, warmupProducts) + " " + fairness(warmupProducts) + " " + OTHER_ACTIVITY;
    }

    /** The fairness note (E10b known issue): the parallel run meets a slightly warmer JIT than the sequential product. */
    static String fairness(int warmupProducts) {
        String bracket = warmupProducts == 0 ? "(the sequential product)" : "(the warm-up and the sequential product)";
        int before = warmupProducts + 1;
        return "The timed sequential product runs after " + warmupProducts + " warm-up product"
                + (warmupProducts == 1 ? "" : "s") + " and the timed parallel run after " + before + " full product"
                + (before == 1 ? "" : "s") + " " + bracket + ", so the parallel run meets a slightly warmer JIT and processor "
                + "cache than the sequential product did.";
    }

    /** The Experiment 2 side effect, with the configured requests ports. */
    static String experimentTwoSideEffect(int firstPort, int lastPort) {
        return "Each node multiplies its rows on its own Experiment 2 executor, which runs only while that node's request "
                + "listener is running. Starting this experiment, or running it, therefore starts the request listener of every "
                + "live node that does not have one yet, on its requests port (" + firstPort + " to " + lastPort + " here). The "
                + "matrix never stops these listeners and a cluster reset does not either; a listener closes only when its node "
                + "crashes or the backend stops. The matrix work shows in Experiment 2's live executor statistics, but not in "
                + "its request history or its events, and Experiment 2's status stays idle meanwhile.";
    }

    /** Why a run's speedup is withheld: it was not complete. */
    static String withheld(CollectiveOutcome outcome) {
        return "No speedup: the run was " + outcome.name().toLowerCase(Locale.ROOT)
                + ", so its parallel time is not the time of the whole product.";
    }

    private static GlossaryEntryDto entry(String id, String label, Kind kind, String definition) {
        return new GlossaryEntryDto(id, label, kind, definition);
    }
}
