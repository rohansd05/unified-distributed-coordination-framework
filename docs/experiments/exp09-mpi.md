# Experiment 9: MPI Collective Communication (Broadcast, Scatter, Gather)

Module id `mpi` · page `/experiments/9-mpi` · package `com.udcf.modules.mpi`

## Aim

MPI collective communication: broadcast, scatter and gather.

## Theory

MPI (Message Passing Interface) is the standard library for parallel programs on clusters. Processes are
numbered by *rank*, and one rank is the *root*. Besides point-to-point messages, MPI offers *collective*
operations in which every rank takes part:

- **Broadcast:** the root sends the same data to every rank.
- **Scatter:** the root cuts its data into blocks and sends block i to rank i.
- **Gather:** the reverse of scatter; every rank sends its block to the root, which assembles them in
  rank order.

When n elements do not divide evenly among p ranks, *block distribution* gives the first `n mod p` ranks
one extra element. A simple implementation is *linear*: the root talks to each rank in turn. Real MPI
libraries use trees (for example a binomial broadcast) so the root does not make every transfer itself.

## How UDCF implements it

- **Model (pure).** `CollectiveExecutor` runs one collective from the root over a `CollectiveTransport`:
  linear, in rank order. `RankAssignment` turns the live nodes, sorted by id, into ranks 0 to p − 1 and
  lists down nodes as skipped. `BlockPartition` is the block distribution (n = 10 over p = 4 gives 3, 3,
  2, 2). `PayloadGenerator` makes reproducible vectors from a seed, and `VectorChecksum` (SHA-256)
  verifies what arrived. Operations: `BROADCAST`, `SCATTER`, `GATHER` (reduce and allgather are not
  built). Outcomes: `COMPLETE`, `PARTIAL`, `FAILED`, `REFUSED`.
- **Transport over RMI.** Each node exports a `CollectiveEndpoint` (methods `receive` and `contribute`)
  in its Experiment 1 RMI registry, on the same port as `NodeEndpoint` (1101 to 1105 locally, 1101 to 1103
  public), through `CollectiveEndpointExport` (an `RmiExtraExport`). `RmiCollectiveTransport` looks the
  endpoint up afresh for every transfer. The root's own block never crosses the network.
- **Failures.** A node down at the start is skipped. A node that fails during the collective gives a
  FAILED transfer with the failure kind, and the collective continues with the next rank. After the
  deadline (`collective-deadline-millis`, 10000 ms) no new transfer starts; the rest are NOT_ATTEMPTED.
  The operation never hangs.
- **Module and API.** `MpiModule` (lab 9) and `MpiController` under `/api/modules/mpi`: `POST /start`,
  `POST /collectives` (`{operation, count, seed?, rootNode?}`), `GET /collectives`. Element count is at
  most `max-payload-elements` (100000 locally, 10000 public).
- **Root.** A root named in the request, otherwise the leader elected by Experiment 4 (`RootSource`
  `REQUESTED` or `LEADER`).
- **Events** (module `mpi`): `COLLECTIVE_ENDPOINT_EXPORTED`, `COLLECTIVE_STARTED`, `COLLECTIVE_FINISHED`,
  `COLLECTIVE_REFUSED`, `MPI_ROOT_CHANGED`.

## Links to other experiments

- **L1, roles follow the leader.** The default root comes from `RoleProvider.ensureLeader()`. With no
  leader in time, HTTP 409 "No leader". After a leader change `MPI_ROOT_CHANGED` is published once.
- **L4, Lamport time on every message.** Every transfer is an RMI call whose first argument is a
  `CallContext` with the root's ticked clock; the node calls `clock.update(...)` and replies with a tick,
  which the root merges.
- **L6, Experiment 10 over Experiment 9 over Experiment 1.** The collectives run over Experiment 1's RMI.
  Experiment 10 uses this module's scatter and broadcast to distribute its matrices.

## How to run it in the UI

1. In the sidebar, click **MPI Collectives** (`/experiments/9-mpi`). The page starts the collective
   endpoints on every live node by itself.
2. Keep **Operation** = **Broadcast**, **Element count** = 1000, **Seed (optional)** empty and **Root
   node** on "Default", and click **Run collective**.
3. Choose **Scatter**, click **Run collective**; then **Gather**, click **Run collective**.
4. Under **Crash or recover a node**, click **Crash node N** for a non-root node, and run **Scatter**
   again.
5. Click **Recover node N**.

## What to observe

- **"The root and its transfers":** the root in the centre, arrows out for broadcast and scatter, in for
  gather. Solid arrows are delivered, dashed are failed (labelled with the failure kind), dotted are not
  attempted. The root shows "leader" when it came from Experiment 4.
- **Measurements:** "Last total time", "Remote transfers delivered", "Checksum verification"; "Latency
  per rank, in rank order".
- **Result:** "Transfers, in rank order" with sender, receiver, size and latency; for scatter the block
  sizes follow block distribution; for gather "Gathered blocks" shows the assembled vector.
- **With a node crashed before the run:** it is listed as skipped, and the ranks are recomputed over the
  live nodes. A node that fails during a transfer makes the outcome PARTIAL; a missing block is reported
  as a hole, never filled with zeros.

## What is simulated

Only the co-hosting. The Simulated badge next to "The root and its transfers" gives this reason: "All
nodes run inside one backend process on one computer, so they are separate nodes sharing a machine, not
separate machines. Every transfer on this page still goes from the root through the target node's own
RMI registry and a stub over a real loopback socket, so the lookup, the marshalling and the latency are
real and measured; only the network distance between the nodes is missing."

## Tests that prove it

- Backend (`backend/src/test/java/com/udcf/modules/mpi`): `CollectiveExecutorTest`, `BlockPartitionTest`,
  `RankAssignmentTest`, `PayloadGeneratorTest`, `VectorChecksumTest`, `CollectiveEndpointLogicTest`,
  `CollectiveEndpointExportTest`, `RmiCollectiveTransportTest`, `CollectiveEndToEndTest`,
  `MpiEndToEndTest`, `MpiCrashDuringCollectiveTest` (node 3 crashes mid-run: PARTIAL, node 3 reported
  FAILED), `MpiNeverStartedNodeTest`, `MpiModuleTest`, `MpiControllerTest`.
- L1: `modules/replication/LeaderRolesIntegrationTest`, `scenario/LeaderCrashEndToEndTest`.
- L6: `integration/L6MatrixOverCollectivesOverRmiIntegrationTest`.
- Frontend (`frontend/src/modules/mpi`): `MpiPage.test.jsx`, `CollectiveMap.test.jsx`,
  `CollectiveResult.test.jsx`, `LatencyChart.test.jsx`, `NodeStrip.test.jsx`, `useMpi.test.js`,
  `contract.test.js`, and others.

## Viva questions

1. **What is the difference between broadcast and scatter?**
   Broadcast sends the same whole data to every rank. Scatter sends each rank a different block of it.
2. **What is gather?**
   The inverse of scatter: each rank sends its block to the root, which assembles them in rank order.
3. **How are 10 elements split over 4 ranks?**
   Block distribution: 3, 3, 2, 2. The first `n mod p` ranks get one extra element.
4. **Why is a linear collective slow at scale, and what do real libraries do?**
   The root makes every transfer itself, so time grows linearly with p. MPI libraries use trees, such as
   a binomial broadcast, to reach all ranks in about log p steps.
5. **Who is the root?**
   The node named in the request, otherwise the leader elected by Experiment 4 (link L1). With no leader
   in time the request answers 409 rather than choosing another node.
6. **How does a crashed node affect a collective?**
   A node down at the start is skipped and ranks are reassigned. A failure during the run is reported for
   that transfer, the rest continue, and the outcome is PARTIAL. A deadline bounds the whole operation.
7. **How is correctness checked?**
   The data is generated from a seed, so the root knows what each rank should hold, and compares SHA-256
   checksums of what arrived.
8. **Why build MPI collectives on RMI?**
   It reuses Experiment 1's real remote calls between nodes (link L6), so every transfer is a measured RMI
   call with Lamport time attached.
