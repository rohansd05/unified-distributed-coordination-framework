# Experiment 2: Multithreading in a Distributed System

Module id `multithreading` · page `/experiments/2-multithreading` · package `com.udcf.modules.multithreading`

## Aim

Implementation of multithreading in a distributed system: each node serves many requests concurrently
with a pool of worker threads.

## Theory

A server that creates a new thread per request wastes time creating threads and can run out of memory
under load. A *thread pool* keeps a fixed number of worker threads and a *queue* of waiting tasks: a
request is queued, and the next free worker takes it. This bounds the work in progress and reuses
threads.

A bounded queue also gives *backpressure*. When every thread is busy and the queue is full, the server
refuses new work straight away instead of accepting more than it can finish. In Java this is a
`ThreadPoolExecutor` with a bounded `LinkedBlockingQueue` and the `AbortPolicy` rejection handler. The
caller sees the refusal at once and can retry elsewhere, which is what a load balancer (Experiment 6)
relies on.

## How UDCF implements it

- **Executor per node.** `NodeExecutorFactory` builds each node's `ThreadPoolExecutor`: core = max =
  the node's thread count, a bounded queue (`udcf.multithreading.queue-capacity`: 200 locally, 100 in
  the public profile) and `AbortPolicy`. `NamedThreadFactory` names threads `udcf-worker-n<k>-<i>`.
- **Capacities.** From `NodeCapacity`: FAST = 4 threads and ×1 work, MEDIUM = 2 threads and ×2,
  SLOW = 1 thread and ×4. The local cluster is FAST, MEDIUM, SLOW, MEDIUM, FAST (nodes 1 to 5); the
  public cluster is FAST, MEDIUM, SLOW.
- **Work.** `WorkloadExecutor`: `CPU_HASH` computes 40 SHA-256 rounds per work unit; `IO_SIMULATED`
  sleeps 0.4 ms per unit; `MIXED` does both.
- **Request lifecycle.** `RequestProcessingService` submits requests; `DistributedRequest` tracks
  QUEUED, PROCESSING, COMPLETED, FAILED or REJECTED; `RequestRegistry` keeps recent requests;
  `ThroughputTracker` gives sliding-window throughput and latency.
- **Transport.** `RequestsNodeService` (`NodeService` named `requests`) listens on TCP
  `127.0.0.1:ports().requests()`: 7201 to 7205 locally, 7201 to 7203 public. `RequestsProtocol` is one
  line in and one line out: `WORK|<senderId>|<lamport>|<TYPE>;<payloadSize>`. `RequestsClient` is the
  matching client. The page's own batches are submitted in-process to the same executor; the TCP port is
  what Experiment 6 uses.
- **Module and API.** `MultithreadingModule` (lab 2) and `MultithreadingController` under
  `/api/modules/multithreading`: `POST /nodes/{nodeId}/batches`, `POST /nodes/{nodeId}/backpressure`,
  `GET /nodes/{nodeId}/requests`.
- **Roles.** None; you choose the node.
- **Events** (module `multithreading`): `BATCH_SUBMITTED`, `BATCH_FINISHED`, and for requests that
  arrive over TCP `REQUEST_COMPLETED`, `REQUEST_FAILED` and `REQUEST_REJECTED`.

## Links to other experiments

- **L3, Experiment 6 dispatches into Experiment 2 executors.** The load balancer sends every request over
  TCP to a node's `RequestsNodeService`, so it runs on that node's Experiment 2 executor.
- **L4, Lamport time on every message.** `RequestsClient` ticks the sender's clock into the request;
  `RequestsNodeService` calls `node.clock().update(...)` on receipt and replies with `node.clock().tick()`;
  the client merges the reply's time.
- **Experiment 10** also multiplies each node's matrix block on that node's Experiment 2 executor
  (`NodeExecutor` calls `RequestsNodeService.execute`). This is used, but it is not one of the six named
  links.

## How to run it in the UI

1. In the sidebar, click **Multithreading** (`/experiments/2-multithreading`). Nothing starts until you
   send work; a node card shows "Not started yet" before its first batch.
2. Under **Node**, keep **Node 1** (Fast, 4 threads).
3. Keep **Requests in the batch** = 100, **Work units per request** = 50 and **Workload** = **Hashing
   (CPU)**. Click **Send batch**.
4. Select **Node 3** (Slow, 1 thread) and click **Send batch** again.
5. Click **Run backpressure demo**.

## What to observe

- **Executor view:** the "Bounded queue" bar fills and drains; the worker thread lanes turn busy and
  idle.
- **"Requests per worker thread"** and the request table's **Thread** column: one batch on node 1 is
  shared between several `udcf-worker-n1-*` threads; on node 3 every request runs on the one thread.
- **Backpressure:** the demo sends threads + queue capacity + 50 requests in one burst (1 + 200 + 50 =
  251 on node 3 locally), so the status line reports some requests rejected. Those requests show the status
  Rejected. They were refused immediately, not left waiting.
- **Measurements:** "Busy threads", "Queued", "Completed tasks", "Throughput", "Average latency" and
  "p95 latency" are measured by the backend.

## What is simulated

- The **Waiting (IO)** workload: "The wait is a sleep standing in for a real network or disk delay, so
  this waiting time is simulated."
- The **Mixed** workload: "The waiting half is a sleep standing in for a real network or disk delay, so
  that part is simulated; the hashing half is real work."
- Both carry the Simulated badge on the page. The default **Hashing (CPU)** workload is real SHA-256 work.
- The capacity profiles are configured, not measured; the page says so: a SLOW node is slow because it
  has fewer threads and more work per request, not slower hardware.

## Tests that prove it

- Backend (`backend/src/test/java/com/udcf/modules/multithreading`): `NodeExecutorFactoryTest`,
  `NamedThreadFactoryTest`, `WorkloadExecutorTest`, `RequestProcessingServiceTest`,
  `RequestRegistryTest`, `ThroughputTrackerTest`, `ThreadPoolStatsServiceTest`,
  `RequestsNodeServiceTest`, `RequestsClientTest`, `RequestsProtocolTest`, `MultithreadingModuleTest`,
  `MultithreadingControllerTest`, `MultithreadingMetricsTest`, `ThreadPoolMetricsTest`, and others.
- Integration: `L3LoadBalancingIntoExecutorsIntegrationTest`, `L4LamportOnMessagesIntegrationTest`
  (`tcpRequestCarriesTheSendersLamportTime`).
- Frontend (`frontend/src/modules/multithreading`): `MultithreadingPage.test.jsx`,
  `ExecutorView.test.jsx`, `ThreadTally.test.jsx`, `RequestTable.test.jsx`, `useMultithreading.test.js`,
  `contract.test.js`, and others.

## Viva questions

1. **Why use a thread pool instead of a new thread per request?**
   Creating threads is expensive and unbounded threads can exhaust memory. A pool reuses a fixed number
   of threads and queues the rest, so the load on the node is bounded.
2. **What are the core size, maximum size and queue of a `ThreadPoolExecutor`?**
   Core threads are kept alive; extra threads up to the maximum are created only when the queue is full.
   UDCF sets core = max = the node's thread count (4, 2 or 1), so the queue absorbs bursts.
3. **What does `AbortPolicy` do?**
   When all threads are busy and the bounded queue is full, it throws `RejectedExecutionException` for
   the new task. UDCF marks that request Rejected.
4. **What is backpressure, and why is it useful?**
   Refusing work the node cannot finish, immediately, instead of queueing it forever. The caller learns
   at once and can retry elsewhere; Experiment 6 reroutes on such a refusal.
5. **Why is the queue bounded?**
   An unbounded queue never rejects, so latency grows without limit and memory runs out under overload.
6. **How do you show that several threads served one batch?**
   Each request records its worker thread name. The page tallies requests per thread, and the request
   table shows the `udcf-worker-n<k>-<i>` name for each request.
7. **Which workload is real and which is simulated?**
   Hashing is real SHA-256 work on the processor. Waiting (IO) is a sleep standing in for disk or
   network delay, so it carries the Simulated badge.
8. **How does Experiment 6 use this experiment?**
   The balancer sends requests over TCP to each node's request port, and they run on that node's
   executor (link L3). A slow node is one with fewer threads and more work per request.
