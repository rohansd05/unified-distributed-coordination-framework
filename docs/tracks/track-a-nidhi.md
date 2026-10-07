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
- [x] E2c — `MultithreadingModule` (lab 2) and `/api/modules/multithreading` endpoints (submit a batch to a node, per-node stats, a backpressure demo); events and metrics; retire the old `/api/multithreading` controller, the old Exp 2 packages and `udcf.node.id`, and list these removals in the Progress log for Rohan; fix registry-dependent tests if this is the first module to register; contract fixtures. Needs: E2b.
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
- **Multithreading API (E2c, for E2d).** Under `/api/modules/multithreading`:
  `GET` returns the overview `{status, actionInProgress, capacityNote, workloads[], nodes[]}`;
  `POST /nodes/{nodeId}/batches` with `{count 1-1000, type, payloadSize 1-5000}` returns 202 and
  a batch `{batchId, nodeId, kind, workload, payloadSize, requested, accepted, rejected,
  requestIds[]}`; `POST /nodes/{nodeId}/backpressure` returns 202 and a batch (kind
  BACKPRESSURE), or 409 "Module busy" while a demo runs; `GET /nodes/{nodeId}/requests?limit=`
  (1 to 500, default 50) returns `RequestResult[]` newest first `{id, nodeId, type, status,
  threadName, submittedAt, queueWaitMillis, processingMillis, totalMillis, resultSummary,
  errorMessage}`. Workload `{type, description, simulated, simulatedReason}` (IO_SIMULATED and
  MIXED simulated, CPU_HASH not); node `{nodeId, nodeStatus, capacity, capacityConfigured,
  threads, workMultiplier, port, serviceRunning, stats}`, where `stats` is the ThreadPoolStats
  object or null while the node's service is not running (never NaN). Enums are their names.
  Errors are ProblemDetail: 404 "Unknown node" (+nodeId), 409 "Node down" (+nodeId), 409
  "Module busy" (+moduleId, actionInProgress), 400 "Invalid request parameters" (+errors
  {field: message}). Real JSON for each: `frontend/src/test/fixtures/multithreading/`.
- **Batch events (E2c).** Per HTTP burst, never per request, module `multithreading`, the
  node's own Lamport clock, no peer: `BATCH_SUBMITTED` data `{batchId, kind, requested,
  accepted, rejected, workload, payloadSize}`; `BATCH_FINISHED` when every accepted request has
  ended, data `{batchId, kind, completed, failed, threads, elapsedMillis}`, and not published
  if the node is down by then. With the TCP `REQUEST_*` events (E2b) these are all
  `module=multithreading` events; Phase 9A's global timeline gets them like any other module's.
- **Getting a node's requests service (for Jai's E10b).** Inject `Cluster`,
  `MultithreadingProperties`, `MeterRegistry` and `ClusterEventBus`, then
  `RequestsNodeService.on(cluster.node(k), properties, meterRegistry, bus).execute(task)`.
  `RequestsNodeService.find(node)` returns the service only if it has started, and never starts it.
- **Exception handler (E2c, shared web code).** `web/GlobalExceptionHandler` now advises every
  controller under `com.udcf` (was `com.udcf.web`), so each module's controller in
  `com.udcf.modules.<moduleId>` gets the same ProblemDetail bodies. An invalid `@Valid`
  request body now gets the same 400 shape as a bad parameter: title "Invalid request
  parameters", `errors: {field: message}`. Existing `com.udcf.web` behaviour is unchanged.

---

## Known issues

- Socket binding was checked on Windows only (probe: a second bind on a port in use is refused
  with SO_REUSEADDR on, and an immediate rebind after a crash succeeds). Linux behaviour is
  verified by CI running RequestsNodeServiceTest (port-in-use and crash-then-recover tests).
- Resolved in E2c (kept here for the record): per-node gauges now bind once per node to the
  `ClusterNode` and read the current executor; in-process batches publish BATCH_* events; a
  request rejected because the node is down now says "Node is down"; the legacy wiring and both
  old Exp 2 test classes are retired or replaced (see the E2c Progress log line).
- `web/TestModuleConfig`'s fake module uses lab 10, which will clash with the real Matrix module
  (Track C, E10c). `ModuleControllerTest` (shared context with that fake) now finds modules by id,
  so other tracks' modules do not break it.
- The fixtures under `frontend/src/test/fixtures/multithreading/` were captured under the local
  profile (5 nodes, ports 7201 to 7205); their README lists the values that change per capture.
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
- 2026-10-07 E2c done: MultithreadingModule (lab 2) with batches, per-node overview and a
  deterministic backpressure demo (bursts held at a gate until fully submitted; guard released
  when the demo ends, also on a crash), MultithreadingController under
  /api/modules/multithreading, BATCH_SUBMITTED/BATCH_FINISHED events, MultithreadingMetrics
  (6 gauges per node bound once to the ClusterNode, NaN without an executor), reset clears
  history only, GlobalExceptionHandler widened to com.udcf plus the @Valid body errors map,
  registry tests now find modules by id, contract fixtures captured; backend 339 tests, frontend
  126 tests; deviations: (1) CorsIntegrationTest's probe endpoint changed from the retired
  /api/multithreading/stats to /api/cluster (one line; not in the plan); (2) ThreadPoolMetrics
  also lost its now-unused executor and tracker constructor arguments; (3) RequestRegistry
  register/clear made atomic so a reset during a batch cannot leave it inconsistent; (4) all HTTP
  batches, not only the demo, are held at the gate, so their accepted and rejected counts are
  exact. Removed for Rohan: com.udcf.config.LegacyMultithreadingConfig,
  com.udcf.config.ThreadPoolProperties, com.udcf.controller.MultithreadingController (and the
  empty config/ and controller/ packages), the old /api/multithreading endpoints (including
  requests/sync), udcf.node.id and udcf.threadpool (application.yml, application-public.yml),
  ThreadPoolMetrics.bindGauges(), and test/com/udcf/controller/MultithreadingControllerTest
  (replaced by modules/multithreading/MultithreadingControllerTest). Note for Rohan: the CLAUDE.md
  Known-issues lines about the Exp 2 app beside core/ (com.udcf.threadpool, udcf.node.id: 1) and
  anything naming LegacyMultithreadingConfig, ThreadPoolProperties, udcf.threadpool or
  udcf.node.id are now obsolete.
