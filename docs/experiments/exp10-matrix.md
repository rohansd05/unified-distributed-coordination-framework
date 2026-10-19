# Experiment 10: Parallel Matrix Multiplication using MPI

Module id `matrix` · page `/experiments/10-matrix` · package `com.udcf.modules.matrix`

## Aim

Parallel matrix multiplication using MPI.

## Theory

To compute C = A × B for n × n matrices in parallel, the classic MPI method is *row-block
decomposition*:

1. The root **scatters** the rows of A in blocks, one block per rank.
2. The root **broadcasts** all of B, because every row of C needs all of B.
3. Each rank multiplies its rows of A by B, giving the same rows of C.
4. The root **gathers** the blocks of C back in rank order.

*Speedup* = sequential time / parallel time. In theory it approaches p for p ranks, but communication
(sending A and B, collecting C) costs time that the sequential product does not pay, so small matrices
are often *slower* in parallel. Speedup is also bounded by the number of processor cores actually
available. A parallel result must be checked against the sequential one.

## How UDCF implements it

- **Model (pure).** `MatrixGenerator` makes A and B reproducibly from a seed. `RowPartition` splits rows
  with Experiment 9's block distribution (n = 10 over p = 4 gives 3, 3, 2, 2 rows). `MatrixMultiply` uses
  one i-k-j loop for both the sequential product and each block, so the parallel result is bitwise equal
  to the sequential one. `MatrixAssembly` and `MatrixVerification` compare every element and a checksum.
  `MatrixLimits` sets the sizes: at least 2, at most `floor(sqrt(max-payload-elements))` = 316 locally,
  100 public.
- **Run** (`ParallelMatrixExecutor`): generate A and B; one warm-up product; the timed sequential product;
  **scatter** the row blocks of A and **broadcast** B with Experiment 9's `CollectiveExecutor`;
  `computeBlock` RMI calls to every rank at once, each node multiplying on its **Experiment 2 executor**
  (`NodeExecutor`); then a `fetchBlock` RMI call to each rank, in rank order, to collect its rows of C.
  One deadline (10000 ms) covers the communication.
- **Transport over RMI.** Each node exports a `MatrixEndpoint` beside `NodeEndpoint` and
  `CollectiveEndpoint` in its Experiment 1 registry (1101 to 1105 locally, 1101 to 1103 public), through
  `MatrixEndpointExport`. Note: the blocks of C come back by these RMI fetches, not by Experiment 9's
  gather collective. This was a design decision when Experiment 10 was built.
- **Outcomes.** `COMPLETE` (no block missing), `PARTIAL` (some blocks missing, reported as holes, never
  zeros), `FAILED`, `REFUSED` (for example a size above the maximum). Speedup is reported only for a
  COMPLETE run whose result matched; otherwise the page says why it is withheld.
- **Speedup series.** `GET /series` takes, for each configured size (50, 100, 200, 316 locally; 25, 50,
  100 public), the newest run of that size that completed and matched. A size not yet run is shown as not
  run, never as 0. The ceiling shown is min(ranks, available processors).
- **Module and API.** `MatrixModule` (lab 10) and `MatrixController` under `/api/modules/matrix`:
  `POST /start`, `POST /runs` (`{size, seed?, rootNode?}`), `GET /runs`, `GET /series`.
- **Root.** A root named in the request, otherwise the leader (`MpiModule.defaultRoot`).
- **Events** (module `matrix`): `MATRIX_ENDPOINT_EXPORTED`, `MATRIX_RUN_STARTED`, `MATRIX_RUN_FINISHED`,
  `MATRIX_RUN_REFUSED`. The scatter and broadcast inside a run publish no separate collective events.

## Links to other experiments

- **L6, Experiment 10 over Experiment 9 over Experiment 1.** A and B travel with Experiment 9's scatter
  and broadcast, which are Experiment 1 RMI calls; the compute and fetch calls are RMI calls too.
- **L1, roles follow the leader.** The default root is the leader elected by Experiment 4; with no
  leader in time, HTTP 409 "No leader".
- **L4, Lamport time on every message.** Every compute and fetch call carries a `CallContext` with the
  root's ticked clock; the node merges it and replies with a tick, which the root merges.
- **Experiment 2.** Each block is multiplied on its node's Experiment 2 executor, so the work shows in
  Experiment 2's live executor statistics.
- **Scenario "The leader dies during a matrix multiply"** crashes the root mid-run, reports what really
  happened to that run, and repeats it from the new leader.

## How to run it in the UI

1. In the sidebar, click **Parallel Matrix Multiplication** (`/experiments/10-matrix`). The page starts
   the matrix endpoints and request listeners on every live node by itself.
2. Keep **Matrix size (n)** at its default (50 locally) and **Root node (optional)** on "Default", and
   click **Run**.
3. Click **Run all sizes (50, 100, 200, 316)** (the sizes shown are the configured ones).
4. Look at "Speedup by size".
5. To see a partial run, crash a node on the **Cluster** page (this page links there), run again, and
   recover the node afterwards.

## What to observe

- **"Rows of C, by rank":** one band per rank, sized by its row count, with its node, its rows, where it
  was computed and its node compute time. A missing block is a hatched "Hole".
- **Phase views:** "Scatter of A", "Broadcast of B" and "Gather of C" reuse Experiment 9's transfer map
  and latency chart.
- **Measurements:** "Sequential time", "Parallel time", "Communication share" and "Verification".
  Verification passes when the parallel result equals the sequential product exactly.
- **"Speedup by size":** bars for each size against the 1.0 line ("as fast as sequential") and the
  ceiling line. Small matrices are often below 1.0 because communication dominates; the page shows that
  rather than hiding it.

## What is simulated

Only the co-hosting. The one Simulated badge, next to "Rows of C, by rank", gives this reason: "All nodes
run inside one backend process on one computer, so they share its processors and memory: they are
separate nodes sharing a machine, not separate machines. Every scatter, broadcast, compute call and fetch
still goes through the target node's own RMI registry and a stub over a real loopback socket, so those
times are real and measured; only the network distance between the nodes is missing." Because the nodes
share one machine's cores, speedup is bounded by the core count.

## Tests that prove it

- Backend (`backend/src/test/java/com/udcf/modules/matrix`): `MatrixMultiplyTest`, `ParallelProductTest`
  (for every p from 1 to 7 the assembled blocks equal the sequential product bitwise), `RowPartitionTest`,
  `RowAlignedScatterTest`, `MatrixGeneratorTest`, `MatrixAssemblyTest`, `MatrixLimitsTest`,
  `MatrixTimingTest`, `SpeedupBasisTest`, `SpeedupSeriesTest`, `ParallelMatrixExecutorTest`,
  `MatrixEndpointLogicTest`, `NodeExecutorTest`, `MatrixEndToEndTest`, `MatrixEndToEndHttpTest`,
  `MatrixUnreachableNodeTest` (an unreachable node is a reported hole, the run PARTIAL),
  `MatrixModuleTest`, `MatrixControllerTest`.
- Integration: `L6MatrixOverCollectivesOverRmiIntegrationTest` (a size-40 run is COMPLETE and verified;
  every scatter, broadcast and fetch is an RMI call with Lamport times increasing),
  `modules/replication/LeaderRolesIntegrationTest` (L1).
- Scenario: `scenario/scenarios/IncidentScenariosEndToEndTest` (`matrixLeaderCrash`).
- Frontend (`frontend/src/modules/matrix`): `MatrixPage.test.jsx`, `RowBlocks.test.jsx`,
  `PhaseViews.test.jsx`, `RunResult.test.jsx`, `SpeedupChart.test.jsx`, `useMatrix.test.js`,
  `contract.test.js`, and others.

## Viva questions

1. **Why scatter A but broadcast B?**
   Row i of C needs only row i of A but all of B. So each rank gets its own rows of A and a full copy of B.
2. **How is the work split when n is not divisible by p?**
   Block distribution over rows: the first `n mod p` ranks get one extra row, for example 3, 3, 2, 2
   rows for n = 10, p = 4.
3. **How do you know the parallel result is correct?**
   The same product is computed sequentially, and every element is compared bit for bit, plus a checksum.
   One multiply routine is used for both, so they must agree exactly.
4. **Why is speedup below 1 for small matrices?**
   Sending A and B and fetching C costs more than the multiplication saves. Computation grows as n³ and
   communication as n², so larger sizes benefit more.
5. **What limits the speedup here?**
   All nodes share one computer's processors, so the ceiling is min(ranks, available processors), and
   communication overhead lowers it further.
6. **What happens if a node crashes during a run?**
   Its block cannot be computed or fetched; the run is PARTIAL with a hole, verification is not passed,
   and no speedup is claimed.
7. **How are the blocks of C collected?**
   By one RMI fetch per rank in rank order, not by Experiment 9's gather collective; scatter and
   broadcast do use Experiment 9 (link L6).
8. **Where does each node do its multiplication?**
   On its own Experiment 2 thread pool, so the work appears in Experiment 2's live executor statistics.
