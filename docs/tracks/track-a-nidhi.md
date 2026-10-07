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
- [x] E2d — build the shared experiment-page kit exactly as in README section 7, then the Multithreading page; verify HANDOFF Section 7 Exp 2 "done when". Needs: E2c.

---

## Experiment 6 — Load Balancing (Phase 8, moduleId: loadbalancing)

- **Reuse:** `legacy-demos/exp06-load-balancing`; Appendix B Exp 6.
- **Special rules:** no private thread pool: dispatch into each node's Exp 2 executor over TCP (link L3); keep the key finding visible: Round Robin has the most even request counts and the worst finish time.

### Steps
- [x] E6a — strategies (Round Robin, smooth Weighted Round Robin, Least Connections, Least Response Time = EWMA latency x (in-flight + 1), alpha 0.3), `WorkerInfo`, `DispatchResult`, `PhaseReport`, circuit-breaker reroute, as pure classes. Needs: E2d.
- [x] E6b — the balancer dispatching over TCP into each node's requests service, with reroute when a worker is down. Needs: E6a, E2b.
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

- **Experiment-page kit (E2d, for every "d" step).** Built exactly as docs/tracks/README.md
  section 7. A module page lives in `frontend/src/modules/<moduleId>/`, whose `index.jsx`
  default-exports `{ id, Page }`; `src/modules/registry.js` finds it and `ExperimentPage`
  renders `<Page experiment={catalogEntry} />` (unbuilt modules keep the placeholder). Kit, in
  `src/components/experiment/`, all generic (nothing module-specific):
  - `ExperimentLayout({ experiment, howItWorks, controls, visualisation, measurements,
    whatToNotice })`: header (lab badge, title, the catalog's optional `concept`), then the
    HANDOFF 8.4 sections as h2s in order; How it works is a disclosure button, open by
    default; the Event log is added by itself from `experiment.id`; `whatToNotice` may be an
    array of strings (rendered as a list).
  - `MetricCard({ label, value, unit, hint, simulated })`: null, undefined, NaN, Infinity or ""
    show "—" ("Not available"), never 0; `simulated` true or a reason string adds the badge.
  - `SimulatedBadge({ reason })`: visible "Simulated", reason in a tooltip on hover and keyboard
    focus, also the accessible description; no dependency.
  - `ModuleEventLog({ moduleId })` with `src/hooks/useModuleEvents({ moduleId, limit, api })`:
    loads `GET /api/events?module=<id>&limit=50`, appends `/topic/modules/<id>`, causal order,
    clears on `CLUSTER_RESET` (which arrives on `/topic/cluster`), reloads on reconnect.
  - Test ids: `experiment-layout`; `section-how-it-works`, `section-controls`,
    `section-visualisation`, `section-measurements`, `section-event-log`,
    `section-what-to-notice`; `metric-card`; `simulated-badge`; `module-event-log`.
  - A catalog entry in `src/lib/experiments.js` may add `concept: '<one line>'`.
  - Usage for a new module page (`src/modules/<moduleId>/index.jsx` plus a page file):
    ```jsx
    // src/modules/clocksync/index.jsx (example; real pages put Page in its own file)
    import { ExperimentLayout } from '@/components/experiment/ExperimentLayout'
    import { MetricCard } from '@/components/experiment/MetricCard'
    function Page({ experiment }) {
      return <ExperimentLayout experiment={experiment}
        howItWorks={<p>Plain explanation.</p>} controls={<Controls />}
        visualisation={<BoldPicture />} whatToNotice={['First.', 'Second.']}
        measurements={<MetricCard label="Rounds" value={stats?.rounds ?? null} />} />
    }
    export default { id: 'clocksync', Page }
    ```
  - App-shell rule (shared `components/layout/AppLayout.jsx`, E2d fix): the document never
    scrolls; `<main id="content">` is the only content scroller AND is `position: relative`, so it
    is the containing block of every absolutely positioned element in a page. Do not remove
    `relative` from `<main>`, do not use `position: fixed` inside a page, and give any `sr-only`
    text, `aria-live` region or absolute tooltip a positioned ancestor inside the page (an
    `sr-only` element whose containing block is the document escapes main's overflow and makes
    the whole document scroll).
  - Pattern used by the Multithreading page, reusable: module REST calls in a module-local
    `<module>Api.js` built on the shared axios client (`createApi().client`), so
    `services/api.js` is not edited; live data via one hook that refreshes on the module's
    events and polls only while something is active.

- **Load balancer (E6a), package `com.udcf.modules.loadbalancing`.** Pure classes (no sockets,
  threads or Spring). `new LoadBalancer(workers, transport[, nanoClock])` (workers kept in
  node-id order); `dispatch(requestId >= 1, workUnits >= 1, Strategy)` returns a
  `DispatchResult(requestId, nodeId, latencyMillis, succeeded, attempts)` with `rerouted()` =
  attempts > 1; latency is end to end, from the first attempt to the final answer. Choosing a
  worker and counting it in flight are one atomic step, so `dispatch` is safe from many client
  threads. Each request tries each worker at most once; `resetForRun()` makes every worker
  healthy again and clears counters, the round robin cursor and smooth weights (in-flight counts
  are live and kept). A worker the circuit breaker removed stays out until `resetForRun()`, as
  in the legacy demo, so E6b calls it at the start of each run. `WorkerInfo.of(nodeId, port,
  NodeCapacity)` weights by thread count (static 4 : 2 : 1). Null arguments throw
  NullPointerException, bad values IllegalArgumentException, throughout.
  `new PhaseReport(strategy, nodeIds, results, makespanMillis)`: `requestsPerNode()` (every node,
  zeros included), `averageLatencyByNode()`, `averageLatency()`, `p95Latency()` (nearest rank),
  `maxLatency()` (served requests only, `OptionalDouble`, empty when none, never 0),
  `loadSpread()`, `failures()`, `reroutes()`, `served()`, `total()`. The batch runner, concurrent
  clients, makespan timing and the Lamport clock belong to E6b.
- **`WorkerTransport` (E6a, for E6b).** `void send(WorkerInfo worker, int requestId, int
  workUnits) throws IOException, WorkerDeclinedException`. Mapping E6b must make from the Exp 2
  `RequestsClient`: COMPLETED reply = return normally; REJECTED and FAILED replies = throw
  `WorkerDeclinedException` (reroute, worker stays healthy); `ConnectException`,
  `SocketTimeoutException` and a connection closed without a reply (any other `IOException`) =
  throw `IOException` (reroute and the circuit breaker marks the worker unhealthy). Only
  `IOException` trips the breaker. A `RuntimeException` is a transport bug: it propagates after
  the in-flight slot is released.
- **Compare all four (E6a, rule for E6c and E6d).** `new StrategyComparison(reports)` gives
  `fastest()`, `slowest()`, `mostEven()`, `gainOverRoundRobinPercent()`,
  `roundRobinFinishedLast()` (strictly longer makespan than every other report) and
  `roundRobinMostEven()`. The page may state "Round Robin finished last" only when
  `roundRobinFinishedLast()` is true for the measured run, never as a fixed claim; likewise for
  "most even" with `roundRobinMostEven()`.
- **Exp 6 over TCP (E6b), for E6c.** `new LoadBalancingGateway(cluster, multithreadingProperties,
  meterRegistry, bus, loadBalancingProperties).run(strategy, requestCount >= 1, workUnits 1-5000,
  concurrency >= 1)` returns the measured `PhaseReport` and waits for it. Every cluster node is a
  worker (a node that is down stays in the list, is found by a refused connection, and shows 0
  requests). Each run starts the Exp 2 requests service on every UP node (lazily, never on a
  crashed one) and builds fresh workers and a fresh transport, so counters and the event cap are
  per run; `workers()` returns the current or last run's workers with live counters. One run at a
  time (a ReentrantLock, so no virtual-thread pinning); E6c's ModuleActionGuard should answer 409
  first. Exp 6 adds no NodeService and no port. `new BatchRunner([nanoClock]).run(balancer,
  strategy, requestCount, workUnits, concurrency)`: calls `resetForRun()`, runs `min(concurrency,
  requestCount)` client loops on virtual threads named `udcf-lb-client-<i>` (at most `concurrency`
  in flight), measures the makespan from the first request to the last answer, returns results in
  request-id order; a transport RuntimeException is rethrown after every client stops.
  Settings: `udcf.loadbalancing.request-timeout-millis` (10000; the public profile inherits it).
- **Transport mapping (E6b, final).** `TcpWorkerTransport` sends sender id 0 with the cluster's
  Lamport clock (`Cluster.clusterClock()`; RequestsClient ticks before sending and merges the
  reply, link L4) and always `CPU_HASH` work (real, never simulated). COMPLETED = served;
  REJECTED, FAILED and ProtocolException (the node answered ERROR, or sent a malformed reply) =
  `WorkerDeclinedException` (reroute, worker stays healthy); ConnectException,
  SocketTimeoutException and a connection closed without a reply = `IOException` (reroute, the
  circuit breaker marks the worker unhealthy for the rest of the run). Note: a crashing node sends
  no reply at all (E2b checks `running` before replying), so in-flight and queued work on a
  crashed node always shows up as a closed connection, never as a FAILED reply.
- **Load-balancing events (E6b).** Module `loadbalancing`, node 0, the cluster clock ticked for
  the event, peer = the worker. `DISPATCH_FAILED` per attempt that tripped the breaker, data
  `{requestId, reason (ConnectException, SocketTimeoutException, IOException), message}`;
  `DISPATCH_DECLINED` per declined attempt, data `{requestId, status (REJECTED, FAILED,
  PROTOCOL_ERROR), detail, workerRequestId (when the node gave one)}`. At most 20 of the two
  together per worker per run (`TcpWorkerTransport.MAX_EVENTS_PER_WORKER`); beyond that only
  `WorkerInfo.failed()` and `declined()` move, so E6c shows the true totals from those counters.
  Served attempts publish nothing here: the node publishes `REQUEST_COMPLETED` (module
  multithreading, peer 0). RUN_STARTED/RUN_FINISHED are E6c's.
- **At least once, never exactly once (E6c, E6d).** A timeout trips the breaker even though the
  work may still finish on that worker, and the request is then sent elsewhere, so a request can
  run twice. Do not claim exactly-once delivery. "Zero failed requests" holds only while some
  other worker is healthy and has queue room; if every worker is down, every request fails
  promptly (one refused attempt per worker) and nothing hangs.
- **Work units (E6c defaults).** Work units = the Exp 2 payload size, 1-5000; one unit is 40
  SHA-256 rounds times the node's work multiplier. It is NOT the legacy demo's unit (one SHA-256
  round): the legacy default of 900 rounds is about 23 Exp 2 units (measured here: payload 23
  about 0.10 ms, payload 900 about 3.7 ms per request on a x1 node).
- **Opt-in probe (E6b).** `LoadBalancingProbeTest`, skipped unless the system property
  `udcf.loadbalancing.probe=true`: `cd backend; .\mvnw.cmd test -Dtest=LoadBalancingProbeTest
  -Dudcf.loadbalancing.probe=true`. Runs all four strategies on FAST, MEDIUM, SLOW, MEDIUM, FAST
  at 60 requests and 12 clients for payloads 25, 100, 400 and 900 and prints a table; it asserts
  nothing about which strategy wins.
- **Test ports (E6b): 47100 to 47899 are Track A's Exp 6 test range.** Cluster bases 47100-47600
  (LoadBalancingGatewayTest), 47110-47610 (TcpWorkerTransportTest), 47120-47620 (the probe);
  scripted servers 47801-47806; 47809 is kept unbound. Other tracks: please avoid this range.

---

## Deviations (for Rohan to confirm)

- E6b (step anatomy "b"): Experiment 6 adds no `NodeService`. It has no port range (HANDOFF 6.1
  and 6.3); the balancer is a cluster-level client that dispatches into the Exp 2 requests
  service on 720k (link L3), whose crash and recovery already close and rebind the sockets.

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
- A node's request history keeps the newest 500 entries (`request-history-size`). In a burst
  larger than the node can hold, the rejected requests are submitted last, so a 1000-request
  burst pushes the accepted ones (and, after a crash, their "Node crashed" results) out of the
  history: the page can then show only the rejected rows. The E2d manual check uses a
  200-request batch for the crash case for this reason (checked in the browser on 2026-10-08).
- `components/layout/AppLayout.test.jsx` (Step 2.4 tests that render `/`) still mounts the real
  Overview page without an api mock, so it calls the backend (10 refused-connection lines when
  the API points at a dead port). `routes.test.jsx` is fixed (E2d fix); this one was out of scope.
- Pre-existing, below the `sm` breakpoint: the shadcn toast viewport (`components/ui/toast.jsx`)
  is `fixed top-0 w-full` with no `left-0`, so its left edge falls at its static position after
  the content column and toasts are off-screen at 375 px (measured left 372 of 372). Not caused
  by the E2d fix (same with `relative` removed); a one-class fix (`left-0`) for a later step.
- At 375 px an open SimulatedBadge tooltip can reach past the right edge of `<main>` (measured
  403 of 362), so `<main>` scrolls sideways while the tooltip is open; the document does not.
- The capacity note from GET /api/modules/multithreading still says 'a SLOW node'; a one-word
  backend change plus a fixture recapture is a separate follow-up (Track A, Rohan to schedule).
- The sidebar's module status comes from ClusterProvider, which reloads `/api/modules` only on
  start and reconnect, so it does not follow Idle/Running/Busy live; the page itself shows the
  live module status.
- The balancer's timeout also covers the worker's queue wait; on Render's 0.1 CPU a long queue
  could exceed it and trip the breaker on a live worker. Re-measure in Phase 16 and size E6c's
  defaults so the worst-case queue wait stays well below the timeout.

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
- 2026-10-08 E2d done: shared experiment-page kit (ExperimentLayout, MetricCard, SimulatedBadge,
  ModuleEventLog with useModuleEvents, modules/registry.js, ExperimentPage renders registered
  pages) and the Multithreading page (executor view with queue tank and thread lanes, SVG
  throughput chart, thread tally, request table, batch and backpressure controls, measurements,
  live through /topic/modules/multithreading with polling only while active), approved texts,
  contract test on the E2c fixtures, browser check against the real backend; backend 376 tests
  (no backend change), frontend 230 tests; deviations: (1) SimulatedBadge's closed tooltip is
  display: none instead of visibility: hidden, after the browser check found it widened the page
  at 375 px; (2) ThreadTally.jsx and contract.test.js added beside the planned files; (3) manual
  row 7 uses a 200-request batch, because a 1000-request burst evicts the crashed requests from
  the 500-entry history (see Known issues).
- 2026-10-08 E2d fix: the document scrolled on /experiments/2-multithreading (scrollHeight 1154
  vs innerHeight 631; 2298 vs 740 at 375x740) because ExecutorView's aria-live `sr-only` text
  took the document as its containing block, `<main>` not being positioned; fixed by making
  `<main id="content">` `relative` in the SHARED `components/layout/AppLayout.jsx` (plus
  `relative` on the ExecutorView figure); all 8 pages now measure scrollHeight = innerHeight at
  1280x800 and 375x740, at the top and with main scrolled to the bottom; "A SLOW node" became
  "A Slow node"; routes.test.jsx now mocks the page APIs (0 network calls); 2 regression tests in
  AppLayout.test.jsx; backend not changed, frontend 232 tests; deviations: none.
- 2026-10-08 E6a done: Strategy, WorkerInfo, DispatchResult, PhaseReport, LoadBalancer
  (selection, reroute and circuit breaker), WorkerTransport, WorkerDeclinedException and
  StrategyComparison ported from legacy-demos/exp06-load-balancing as pure classes; pick sequences
  for all four strategies checked against hand-derived legacy sequences; 5 new test classes, 53
  tests; backend 429 tests (3 runs, all green), frontend not run (no frontend change);
  deviations: none from the approved plan except `select` and the WorkerInfo mutators being
  package-private, so that production code can reserve a worker only through `dispatch`'s atomic
  pick. Deliberate differences from the legacy code: (1) choosing a worker and the in-flight
  increment are one atomic step (legacy incremented after `select`); (2) workers kept in node-id
  order; (3) ties in least response time go to the lower id (legacy kept list order, which is the
  same on an id-ordered list); (4) a request never retries a worker it already tried; (5) an
  answered-but-not-served attempt (WorkerDeclinedException) reroutes without tripping the circuit
  breaker; (6) latency is end to end from the first attempt (legacy timed the last attempt) and a
  failed request carries the time spent (legacy 0); (7) `rerouted` = attempts > 1 (legacy also
  flagged a lone failed attempt); (8) a RuntimeException from the transport releases the in-flight
  slot and propagates; (9) EWMA counts samples instead of using 0.0 as "none yet", and is updated
  under a lock; (10) `resetCounters` keeps live in-flight counts; (11) PhaseReport no longer
  counts failures under node 0, lists every node (zeros included) so loadSpread covers all, and
  returns empty instead of 0 latency figures when nothing was served; (12) no rounding in the pure
  classes; (13) WorkerNode's private pool, the Lamport clock and runBatch are not ported (L3 and
  E6b).
- 2026-10-08 E6b done: TcpWorkerTransport (Exp 2 RequestsClient, sender 0 on the cluster clock,
  CPU_HASH only, the final outcome mapping, DISPATCH_FAILED/DISPATCH_DECLINED events capped at 20
  per worker per run), BatchRunner (virtual-thread clients, measured makespan), LoadBalancingGateway
  (every node a worker, lazy start of the requests service on UP nodes, fresh transport per run,
  ReentrantLock), LoadBalancingProperties and udcf.loadbalancing.request-timeout-millis 10000;
  integration tests on real sockets (ports 47100-47899) including a crash with work in flight and
  queued and an all-crashed cluster; no virtual-thread pinning under -Djdk.tracePinnedThreads=full;
  opt-in probe run once (round robin finished last and was most even at payloads 25, 100, 400 and
  900 on this 12-core machine; the gap was small at 25 and 100); E6a classes unchanged; backend
  459 tests, 1 skipped (the opt-in probe), 3 runs all green; frontend not run (no frontend
  change); deviations: (1) no NodeService (see Deviations); (2) the crash-with-queued-work test
  asserts that the crashed worker's attempts all failed by closed connection (failed() >= 2,
  declined() == 0), not that some came back FAILED "Node crashed": E2b sends no reply after a crash,
  so such replies cannot occur without changing multithreading (out of scope); the FAILED-reply
  path is covered with a scripted server instead.
