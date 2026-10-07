# Track A — Nidhi

- **Owner:** Nidhi
- **Branch:** `nidhi`
- **Reviewer:** Rohan
- **Shared rules:** [docs/tracks/README.md](README.md)

---

## Experiment 2 — Multithreading (Phase 3, moduleId: multithreading)

- **Reuse:** the Exp 2 application inside `backend/` (`com.udcf.threadpool`, model, dto, monitoring, controller, config and its 11 test classes); HANDOFF Appendix B Exp 2.
- **Special rules:** `AbortPolicy` (not `CallerRuns`); real SHA-256 work; each node's executor is sized by its `NodeCapacity` (FAST 4 threads x1 work, MEDIUM 2 x2, SLOW 1 x4), queue 200, keep-alive 60 s, thread names `udcf-worker-n<k>-`; how the old `udcf.threadpool` settings map onto per-node capacity is decided in the E2a plan.

### Steps
- [x] E2a — move the Exp 2 engine into `com.udcf.modules.multithreading` as pure classes sized per node; the 11 existing test classes are moved (never deleted) and pass. Needs: none.
- [x] E2b — per-node "requests" NodeService on `ports().requests()` (720k) running work on that node's executor. Needs: E2a.
- [ ] E2c — `MultithreadingModule` (lab 2) and `/api/modules/multithreading` endpoints (submit a batch to a node, per-node stats, a backpressure demo); events and metrics; retire the old `/api/multithreading` controller, the old Exp 2 packages and `udcf.node.id`, and list these removals in the Progress log for Rohan; fix registry-dependent tests if this is the first module to register; contract fixtures. Needs: E2b.
- [ ] E2d — build the shared experiment-page kit exactly as in README section 7, then the Multithreading page; verify HANDOFF Section 7 Exp 2 "done when". Needs: E2c.

---

## Experiment 6 — Load Balancing (Phase 8, moduleId: loadbalancing)

- **Reuse:** `legacy-demos/exp06-load-balancing`; Appendix B Exp 6.
- **Special rules:** no private thread pool: dispatch into each node's Exp 2 executor over TCP (link L3); keep the key finding visible: Round Robin has the most even request counts and the worst finish time.

### Steps
- [ ] E6a — strategies (Round Robin, smooth Weighted Round Robin, Least Connections, Least Response Time = EWMA latency x (in-flight + 1), alpha 0.3), `WorkerInfo`, `DispatchResult`, `PhaseReport`, circuit-breaker reroute, as pure classes. Needs: E2d.
- [ ] E6b — the balancer dispatching over TCP into each node's requests service, with reroute when a worker is down. Needs: E6a, E2b.
- [ ] E6c — `LoadBalancingModule` (lab 6): run a strategy, "compare all four", crash a worker mid-run; events, metrics, fixtures. Needs: E6b.
- [ ] E6d — page and end-to-end check. Needs: E6c.

---

## Interfaces for other tracks

- **Exp 2 engine (E2a), package `com.udcf.modules.multithreading`.** Plain classes, one set per
  node: `NodeExecutorFactory.forNode(nodeId, NodeCapacity, MultithreadingProperties)` gives a
  `ThreadPoolExecutor` with core = max = `NodeCapacity.threads()` (FAST 4, MEDIUM 2, SLOW 1),
  a bounded queue (`udcf.multithreading.queue-capacity`, 200; public 100), keep-alive 60 s,
  `AbortPolicy`, and threads named `udcf-worker-n<k>-<i>`. `RequestProcessingService(executor,
  workloadExecutor, registry, tracker, metrics, events, nodeId, workMultiplier)` runs
  `payloadSize x NodeCapacity.workMultiplier()` units of real SHA-256 work per request (Exp 6
  dispatches into it, link L3).
- **Executor lifecycle (for E2b).** A `ThreadPoolExecutor` cannot be restarted after shutdown.
  A crash shuts the node's executor down; a recover calls `NodeExecutorFactory.forNode` again for
  a fresh one, and every object holding the executor (processing service, stats service,
  metrics) is rebuilt with it. Documented in the `NodeExecutorFactory` Javadoc.
- **Honesty labelling (extends HANDOFF 6.11; Rohan to confirm in the E2c pull request).** Applied
  in E2c (DTOs) and E2d (UI), not in E2a: the `IO_SIMULATED` workload type gets the Simulated
  badge, because it is a sleep; the FAST/MEDIUM/SLOW difference gets a plain note saying the
  capacity profile is configured, not measured hardware, because all nodes share one machine.
- **Requests service (E2b): `RequestsNodeService`.** `RequestsNodeService.on(node, properties,
  meterRegistry, bus)` starts a node's service lazily (`ClusterNode.ensureService`, name
  `requests`) on 127.0.0.1:`node.ports().requests()` (720k). Crash closes the port and every open
  connection and shuts the executor down; recover rebinds on a fresh executor. The request
  history (`registry()`) survives both.
- **`RequestsClient` (for E6b).** `new RequestsClient(timeoutMillis).send(port, senderId,
  senderClock, type, payloadSize)` returns a `WorkReply` (nodeId, lamportTime, requestId, status
  COMPLETED/FAILED/REJECTED, threadName, queueWaitMillis, processingMillis, totalMillis, detail).
  It ticks `senderClock` before sending and merges the reply's Lamport time after (L4). Failures:
  `ConnectException` = crashed node (nothing listening); `SocketTimeoutException` = silent node;
  any other `IOException` = connection closed without a reply (for example a crash mid-request),
  or `ProtocolException` when the node answered ERROR.
- **Wire format (`RequestsProtocol`).** One line each way per TCP connection, printable ASCII,
  LF-terminated (`\n`), at most 1024 characters (a longer request gets ERROR and the connection
  closes). Request `WORK|<senderId>|<lamport>|<TYPE>;<payloadSize>` (sender 0 = cluster-level
  client, payload 1-5000). Reply `<STATUS>|<nodeId>|<lamport>|<requestId>;<thread>;<queueWaitMs>;
  <processingMs>;<totalMs>;<detail>` (thread empty when no worker ran it). Error
  `ERROR|<nodeId>|<lamport>|<message>`.
- **`RequestsNodeService.execute(Callable<T>)` (for Jai's E10b).** Runs a task on that node's
  current Exp 2 executor and returns a `CompletableFuture<T>`; not recorded in the request
  history. Throws `NodeDownException` at once if the node is down. The future completes with the
  result or the task's exception, exceptionally with `RejectedExecutionException` if the queue
  is full, and exceptionally with `NodeDownException` if the node crashes before or during the
  task (it never hangs; a task that ignores interrupts runs on, but its result is discarded).
- **Event shape (E2b).** One event per TCP request, published when its reply is sent:
  module `multithreading`, nodeId = the serving node, peerId = the sender id (0 = cluster-level),
  lamportTime = the reply's Lamport time, type `REQUEST_COMPLETED`, `REQUEST_FAILED` or
  `REQUEST_REJECTED`; data `requestId`, `receiveLamport`, `workload`, `payloadSize`,
  `threadName` (absent when no worker ran it), `totalMillis`. A node that crashes mid-request
  sends no reply and publishes no event.

---

## Known issues

- E2b must not bind per-node metric gauges while `config/LegacyMultithreadingConfig` exists:
  Micrometer would silently return node 1's existing gauge, still bound to the legacy executor
  (R7). Gauge binding for per-node executors moves to E2c. (E2b binds none.) E2c must bind each
  gauge to something that survives recovery (for example `RequestsNodeService`, reading the
  current executor), not to an executor: after a recover the old executor is dead, and Micrometer
  would keep returning the gauge bound to it.
- Events for in-process submissions (`RequestsNodeService.processing()`, E2c's HTTP batches) are
  decided in E2c; E2b publishes events only for requests that arrive over TCP.
- A request submitted in-process in the instant a node crashes (after `processing()` returned,
  before the executor shut down) ends REJECTED with the existing message "Queue full - node at
  capacity", which is not the real reason. The message is kept because existing tests assert it;
  E2c can map it when it builds its API.
- Socket binding was checked on Windows only (probe: a second bind on a port in use is refused
  with SO_REUSEADDR on, and an immediate rebind after a crash succeeds). Linux behaviour is
  verified by CI running RequestsNodeServiceTest (port-in-use and crash-then-recover tests).
- E2c must retire `config/LegacyMultithreadingConfig`, `controller/MultithreadingController`,
  `config/ThreadPoolProperties`, `udcf.threadpool` (application.yml and application-public.yml)
  and `udcf.node.id`, and must update `PrometheusScrapeTest` and `PublicProfileTest`, which
  depend on them.
- E2c must move or replace `controller/MultithreadingControllerTest` and
  `UdcfBackendApplicationTests` (they still test the old controller and its wiring).
- E2d's manual-check table should use the FAST node (node 1) to show several distinct worker
  threads; the SLOW node has only one.

---

## Progress log

- 2026-10-07 track file created.
- 2026-10-07 E2a done: Exp 2 engine moved to com.udcf.modules.multithreading as plain classes
  (NodeExecutorFactory sizes each node core = max = NodeCapacity.threads(); work multiplier
  applied in RequestProcessingService; settings in udcf.multithreading); 9 test classes moved
  (ThreadPoolConfigTest is now NodeExecutorFactoryTest), MultithreadingPropertiesTest added;
  old /api/multithreading kept working through config/LegacyMultithreadingConfig until E2c;
  backend 256 tests, frontend not run (no frontend change); deviations: (1) MultithreadingControllerTest
  and UdcfBackendApplicationTests stay in place (imports only) until E2c, so 9 of the 11 Exp 2
  test classes moved in E2a (user decision); (2) backend copy of demo/MultithreadingDemo deleted
  (user decision; legacy-demos/exp02-multithreading keeps the demo), so the CLAUDE.md Known-issues
  line about its stale Javadoc is obsolete for the backend copy, for Rohan to update.
- 2026-10-07 E2b done: RequestsNodeService (TCP on ports().requests(), lazy via ensureService,
  virtual thread per connection, Lamport on both directions, one REQUEST_* event per TCP request,
  crash closes sockets and shuts the executor, recover rebinds on a fresh executor, history kept),
  RequestsProtocol (1024-character lines), RequestsClient, execute(Callable) for E10b; requests
  end exactly once (DistributedRequest transitions checked under one lock, shutdownNow aborts
  queued work), CPU work interruptible every 1024 rounds; backend 316 tests, frontend not run (no
  frontend change); deviations: (1) YAML key is udcf.multithreading.read-timeout-millis (plan said
  read-timeout-ms; Spring binds the key to the record component's name); (2) over-long request
  lines: after the ERROR reply the server half-closes and drains up to 64 KB before closing, so
  TCP does not reset the connection and destroy the reply; (3) SO_REUSEADDR without a bind retry,
  chosen from a Windows probe (Linux checked by CI only); (4) the exactly-once race test uses a
  gated workload, because with a warm JIT a timing-based version finished all work before the
  crash.
