# Track D — Rohan

- **Owner:** Rohan
- **Branch:** `rohan`
- **Reviewer:** Rohan
- **Shared rules:** [docs/tracks/README.md](README.md)

---

## Experiment 3 — Clock Synchronization (Phase 4, moduleId: clocksync)

- **Reuse:** `core/clock/LamportClock` (never duplicate it); `legacy-demos/exp03-clock-sync` (`ClockSyncDemo`, `ClockEventLog` causal check); Appendix B Exp 3.
- **Special rules:** the Berkeley drift is simulated and labelled (R11); the time daemon comes from a module-local selector (TODO(L1)).

### Steps
- [x] E3a — the causal-invariant checker, the total order (lamportTime, nodeId), Berkeley averaging with an outlier threshold, and the drift model, as pure classes. Needs: none.
- [x] E3b — clock UDP service on `ports().clock()` (600k): Lamport send and receive, Berkeley poll and adjust rounds. Needs: E3a.
- [x] E3c — `ClockSyncModule` (lab 3): a local event, send X to Y, random traffic for N seconds, a Berkeley round, verification; metric `distributed_clock_value` per node; fixtures. Needs: E3b.
- [x] E3d — the space-time diagram page and end-to-end check. Needs: E3c, E2d.

---

## Experiment 7 — MapReduce (Phase 9, moduleId: mapreduce)

- **Reuse:** `legacy-demos/exp07-mapreduce` (jobs, `JobRegistry`, `LogFields`, the pipeline); Appendix B Exp 7.
- **Special rules:** the average-latency job carries "sum;count", never an average; the coordinator comes from a module-local selector (TODO(L1)); 15 s task timeout with retry on another worker.

### Steps
- [x] E7a — the jobs and the split, map, combine, shuffle, partition and reduce pipeline as pure classes. Needs: E3d.
- [x] E7b — MapReduce workers on `ports().mapreduce()` (730k) with task retry. Needs: E7a.
- [x] E7c — `MapReduceModule` (lab 7) and its API; inputs: bundled sample, uploaded .txt (size-capped), live event log; build `EventLogExporter` and `GET /api/events/export` (link L5). Needs: E7b.
- [ ] E7d — the pipeline page and end-to-end check. Needs: E7c, E2d.

---

## Interfaces for other tracks

- **Reserved test port range:** `24100–24899` (Track D clock synchronization test sockets, Linux-safe below 32768). Base port 24100, clock UDP ports 24201–24205 (service tests), 24401–24405 (module tests), 24601–24605 (controller tests), squatter test port 24801. Avoids other tracks' reserved ranges (21xxx Nidhi, 26xxx Swanand, 28xxx Jai).
- **Service Factory API:** `ClockNodeService.on(ClusterNode node, Cluster cluster, ClockEventLog eventLog, ClockDriftModel driftModel, EventBus bus, ClockSyncProperties properties, Clock wallClock)` registers and starts or retrieves the `clock` `NodeService` bound to `127.0.0.1:ports().clock()`.
- **Wire Protocol:** Text-based UDP datagrams (`MAX_DATAGRAM_SIZE = 1024` bytes, UTF-8):
  - `LAMPORT|<senderId>|<lamportTime>|<messageId>|<payload>`
  - `BERKELEY_POLL|<senderId>|<lamportTime>|<roundId>`
  - `BERKELEY_POLL_REPLY|<senderId>|<lamportTime>|<roundId>|<offsetMillis>`
  - `BERKELEY_ADJUST|<senderId>|<lamportTime>|<roundId>|<adjMillis>|<isOutlier>`
  - `BERKELEY_ADJUST_ACK|<senderId>|<lamportTime>|<roundId>|<afterOffsetMillis>`
- **Cluster Event Shapes (`module = "clocksync"`):**
  - `CLOCK_MESSAGE_SENT`: data `{"messageId": ..., "payload": ...}`
  - `CLOCK_MESSAGE_RECEIVED`: data `{"messageId": ..., "causedByTime": ..., "payload": ...}`
  - `CLOCK_LOCAL_EVENT`: data `{"description": ...}`
  - `BERKELEY_ROUND_STARTED`: data `{"roundId": ..., "daemonId": ..., "thresholdMillis": ..., "targetNodes": [...]}`
  - `BERKELEY_NODE_ADJUSTED`: data `{"roundId": ..., "beforeOffset": ..., "adjustment": ..., "afterOffset": ..., "outlier": ..., "rttMillis": ...}`
  - `BERKELEY_ROUND_FINISHED`: data `{"roundId": ..., "targetOffset": ..., "spreadBefore": ..., "spreadAfter": ..., "participatingNodes": [...], "outlierNodes": [...], "unresponsiveNodes": [...]}`
  - `SERVICE_START_FAILED`: published on socket bind failure
- **REST Endpoints (`/api/modules/clocksync`):**
  - `GET /` -> `ClockSyncOverviewDto`: module status, live daemon node id, node states, drift snapshots, latest round, operational limits, plain-sentence notes
  - `POST /nodes/{id}/local-events` -> `LocalEventResult`: records local event advancing Lamport clock (Rule 1)
  - `POST /messages` -> `SendLamportResult`: sends point-to-point UDP message (Rule 2), supports UDP honesty (`deliveryStatus: "UNKNOWN"` when receiver is crashed)
  - `POST /traffic` -> `TrafficSessionDto`: 202 Accepted, background burst across live nodes guarded by `ModuleActionGuard`
  - `POST /berkeley-rounds` -> `BerkeleyRoundAcceptedDto`: 202 Accepted, daemon coordinates UDP Berkeley round across live nodes guarded by `ModuleActionGuard`
  - `PUT /nodes/{id}/drift` -> `NodeDriftResponseDto`: modifies simulated drift offset and rate
  - `GET /verification` -> `CausalVerificationDto`: checks Lamport Rule 3 and local monotonicity invariants over retained event window
  - `GET /timeline?limit=` -> `TimelineResponseDto`: returns retained causal events with `messageId` for space-time diagram linkage
- **Micrometer Metrics:**
  - `distributed_clock_value`: gauge per node tagged with `node_id`, reading current Lamport clock value
- **Frontend Fixtures:**
  - Real contract JSON fixtures exported to `frontend/src/test/fixtures/clocksync/` (14 JSON files + `README.md`)
- **MapReduce Pipeline & Transport API (E7a -> E7b):**
  - **TaskTransport Interface:** `@FunctionalInterface public interface TaskTransport` with `String executeTask(int targetNodeId, TaskType taskType, String jobName, String payload) throws IOException`, using enum `TaskType { MAP, REDUCE }`. E7a provides `TaskTransport.inMemory(JobRegistry)`. Step E7b implements `TaskTransport` over TCP on `ports().mapreduce()` (730k).
  - **Wire Payloads & Encoding:** Fields Base64-encoded to round-trip tabs, newlines, carriage returns, `\u0001`, `=`, `#`, empty strings, and non-ASCII text without delimiter collision:
    - MAP task payload: raw split lines joined with `\n`.
    - MAP task result: `#raw=<rawCount>\n<base64Key>\t<base64Val>\n...` (encoded/decoded via `MapTask.encode` / `MapTask.decode`).
    - REDUCE partition payload: `<base64Key>\t<base64Val1>\u0001<base64Val2>...\n` (encoded/decoded via `ReduceTask.encodePartition` / `ReduceTask.decodePartition`).
    - REDUCE result: `<base64Key>\t<base64Val>\n` (encoded/decoded via `ReduceTask.encodeResult` / `ReduceTask.decodeResult`).
  - **JobRegistry:** `JobRegistry.standard()` pre-registers `word-count` (`WordCountJob`), `event-category-count` (`EventCategoryJob`), and `avg-latency-per-node` (`LatencyPerNodeJob`).
  - **JobReport Semantics:** All accessors synchronized; stage timings (`mapMillis`, `shuffleMillis`, `reduceMillis`) are `null` when the stage did not run, while `totalMillis` measures overall pipeline elapsed time; `combinerSavingPercent` is `null` when no pairs were emitted (undefined saving per Rule R7); `mapTasks` counts only non-empty splits dispatched; `failedTasksRetried` increments once per retry. Splits made only of blank lines are not counted, so the "splits" and "mapTasks" numbers cover non-blank splits only.
  - **Task Timeout and Retry:** Default task timeout is 15 seconds (Appendix B, configurable via constructor). If a worker task times out or throws `IOException`, the coordinator cancels/interrupts and retries on `(preferredIndex + attempt) % workerIds.size()`. If all workers fail, throws `IOException`.
  - **Lamport Stamping Seam:** The pure pipeline does not instantiate or stamp a Lamport clock. Per Condition 7 and Rule L4, Lamport logical clock stamping (tick on send, update on receive via `ClusterNode.clock()`) belongs strictly to the **E7b** network transport layer when messages cross TCP sockets.
  - **Default Reducers R:** The default reducer count R equals the number of worker IDs passed to the pipeline ($R = \text{workerIds.size()}$, matching HANDOFF Appendix B: `floorMod(key.hashCode(), R)`). The pipeline does not decide which cluster nodes act as workers; E7b/E7c pass the worker list based on cluster configuration (HANDOFF Section 6.2: "Cluster holds ClusterNodes. Size comes from configuration: default 5 locally, 3 in the public profile to save memory").
- **MapReduce TCP Workers & Transport (E7b):**
  - **Reserved Test Port Range:** Service tests `24301–24305`, pipeline over TCP tests `24351–24355`, squatter test `24851` (Track D reserved block `24100–24899`, Linux-safe below 32768).
  - **Service Factory API:** `MapReduceNodeService.on(ClusterNode node, Cluster cluster, JobRegistry jobRegistry, EventBus bus, MapReduceProperties properties)` creates and attaches/retrieves the `mapreduce` `NodeService` bound to `127.0.0.1:ports().mapreduce()`.
  - **Lazy Workers Warning (for E7c):** `TcpTaskTransport` never starts services on other nodes. E7c must call `MapReduceNodeService.on(...)` (or ensureService) for every live worker before a run, otherwise a not-yet-started worker looks like a crashed one.
  - **Transport Constructor:** `public TcpTaskTransport(Cluster cluster, ClusterNode coordinatorNode, EventBus bus, MapReduceProperties properties)` implements `TaskTransport` over TCP.
  - **Wire Protocol:** Line-based TCP request/reply framed by `\n`, bounded by `maxRequestBytes` (default 4MB, 1MB in public profile; usable payload ~0.75 x maxRequestBytes):
    - Request: `<TASK_TYPE>|<senderId>|<lamportTime>|<taskId>|<jobName>|<base64Payload>\n` (where `taskId` is integer or `-` if unknown per R7).
    - Success Reply: `OK|<nodeId>|<lamportTime>|<taskId>|<base64Result>\n`
    - Error Reply: `ERROR|<nodeId>|<lamportTime>|<taskId or ->|<base64Error>\n` (error text Base64-encoded to protect against delimiter collision or newlines).
  - **Bounded Concurrency:** Worker thread pool is fixed (`workerThreads: 4`) with bounded task queue (`queueCapacity: 32`). Full queue immediately returns `ERROR` with "Worker is busy" (Base64-encoded) and closes connection.
  - **Interruptible Transport:** The coordinator reads through the `SocketChannel`'s socket adaptor stream, so `socketReadTimeoutMillis` is honoured (`SocketTimeoutException`) and cancelling the attempt still closes the channel (`ClosedByInterruptException`); the server side uses a classic `Socket` whose `SO_TIMEOUT` frees a worker thread held by an idle client. The request write and the connect call have no timeout of their own and rely on the pipeline's task cancel (harmless on loopback).
  - **One Tick per Message:** Lamport logical clock tick is shared between wire message and corresponding cluster event (`TASK_SENT` on coordinator; `TASK_COMPLETED`/`TASK_FAILED`/`REQUEST_REFUSED` on worker).
  - **Cluster Event Shapes (`module = "mapreduce"`):**
    - `TASK_SENT`: data `{"taskId": ..., "taskType": ..., "jobName": ..., "targetNodeId": ...}`
    - `TASK_RECEIVED`: data `{"taskId": ..., "taskType": ..., "jobName": ..., "senderId": ...}`
    - `TASK_COMPLETED`: data `{"taskId": ..., "taskType": ..., "jobName": ..., "executionMillis": ..., "resultBytes": ...}`
    - `TASK_FAILED`: data `{"taskId": ..., "taskType": ..., "jobName": ..., "reason": ...}` (execution failures of valid tasks)
    - `REQUEST_REFUSED`: data `{"reason": ..., "workerId": ..., "taskId": ..., "taskType": ...}` (protocol rejections: bad sender, out-of-range clock, unknown task type, oversize, busy queue)
    - `TASK_ATTEMPT_FAILED`: data `{"taskId": ..., "taskType": ..., "jobName": ..., "workerId": ..., "reason": ...}` (coordinator-side failed attempts before pipeline retry)
    - `SERVICE_START_FAILED`: published on socket bind failure
  - **Role Selector:** `MapReduceRoleSelector.selectCoordinator(cluster)` returns lowest live node ID; `MapReduceRoleSelector.selectWorkers(cluster)` returns all live nodes (`// TODO(L1)`).

  Linux (Docker, 2 CPUs): the five new test classes (40 tests) passed 5 runs in a row; full backend suite <total> tests, 0 failures, 1 skipped.

### E7c interfaces (for E7d, the MapReduce page)

- **Module:** `MapReduceModule`, id `mapreduce`, lab 7, title "MapReduce". Registered like every module (a `@Component` picked up by `ModuleRegistry`). Status: BUSY while a run is active; otherwise ERROR if the last run FAILED (cleared by the next completed run or by reset); otherwise RUNNING while any node's mapreduce service listens; otherwise IDLE. Reset clears the latest run and the history and never throws; a run still active at that moment finishes and publishes its events, but its record is dropped (its id then answers 404).
- **Roles:** coordinator = lowest live node, workers = every live node, R = number of workers (`MapReduceRoleSelector`, `// TODO(L1)`).
- **Endpoints** (errors are ProblemDetail bodies):
  - `GET /api/modules/mapreduce` -> 200 `MapReduceOverviewDto`.
  - `POST /api/modules/mapreduce/runs` -> 202 `RunDto` (RUNNING). 400 field errors (`jobId`, `inputType`, `upload`, `upload.fileName`, `upload.contentType`, `upload.contentBase64`, `crashWorkerId`); 404 unknown job (`jobId`); 409 module busy (`actionInProgress`); 409 no live worker; 413 body larger than `limits.requestBodyMaxBytes` (`limitBytes`).
  - `GET /api/modules/mapreduce/runs` -> 200 `RunSummaryDto[]`, newest first, at most `runHistorySize` (20).
  - `GET /api/modules/mapreduce/runs/latest` -> 200 `RunDto`; 404 when no run is kept (`runId: null`).
  - `GET /api/modules/mapreduce/runs/{runId}` -> 200 `RunDto`; 404 unknown run (`runId`).
  - `GET /api/events/export?module=&node=&limit=` -> 200 `text/plain; charset=UTF-8` (link L5); limit 1 to `udcf.events.buffer-size` (default = buffer size); 400 for limit 0, a limit above the buffer size, or a negative node.
- **Request body** `RunCommand`: `jobId` (`word-count`, `event-category-count`, `avg-latency-per-node`), `inputType` (`SAMPLE`, `UPLOAD`, `EVENT_LOG`), `upload` (UPLOAD only: `fileName`, `contentType` `text/plain` or empty, `contentBase64`), `crashWorkerId` (optional). The upload is JSON with Base64 so it stays in memory (R3); it is never written to disk, logged or put into an event.
- **DTO fields; the nullable ones are marked "null:".**
  - `MapReduceOverviewDto`: `status`, `currentAction` (null: none active), `jobs[] {id, title, description}`, `inputTypes[] {id, title, description}`, `coordinatorId` (null: every node down), `workerIds[]`, `limits {uploadMaxBytes, requestBodyMaxBytes, eventLogMaxBytes, eventLogMaxEvents, resultRowsMax, runHistorySize, taskTimeoutMillis}`, `latestRun` (null: none kept), `notes[]`.
  - `RunDto`: `runId`, `state` (RUNNING, COMPLETED, FAILED), `jobId`, `jobTitle`, `inputType`, `inputName` (safe display name, never a path), `inputBytes` (null: event-log run still RUNNING), `coordinatorId`, `workerIds` (planned while RUNNING, used once finished), `crash` (null: no crash plan), `startedAt`, `finishedAt` (null: RUNNING), `report` (null: RUNNING), `error` (null unless FAILED), `notice` (null: nothing to say).
  - `CrashDto`: `workerId`, `triggered`, `nodeCrashed` (null: not triggered), `taskType` MAP or REDUCE (null: not triggered).
  - `JobReportDto`: `reducers`, `inputLines`, `inputLinesDropped` (null: not an event-log run), `splits`, `mapTasks`, `pairsEmitted` and `pairsAfterCombine` (null: failed before the map stage finished), `shuffleKeys` and `partitions` (null: failed before the shuffle finished), `resultKeys` (null: FAILED), `combinerSavingPercent` (null: no pairs emitted), `retriedTasks`, `timings {mapMillis, shuffleMillis, reduceMillis, totalMillis}` (each null when that stage did not run; total null when FAILED), `mapTasksPerNode`, `reduceTasksPerNode`, `tasks[]`, `results[]`, `resultsTruncated`.
  - `TaskRowDto`: `taskType`, `taskNumber` (1-based), `completed`, `workerId` (null: no attempt succeeded), `attempts`, `failedAttempts[] {attempt, workerId, reason}`.
  - `ResultRowDto`: `key`, `display` (the job's own text, e.g. "No latency measured"), `count` (null: value is not a count), `averageMillis` (latency job only, sum / count computed once from the reduced `sum;count`, null when count is 0; never an average of averages).
  - `RunSummaryDto`: `runId`, `state`, `jobId`, `inputType`, `inputName`, `startedAt`, `finishedAt` (null: RUNNING), `totalMillis` and `resultKeys` (null unless COMPLETED), `retriedTasks` (null: RUNNING), `crashWorkerId` (null: no crash plan).
- **Events** (module `mapreduce`, node = coordinator, one tick of the coordinator's Lamport clock each, data with `runId` and `jobId`): `JOB_STARTED` {inputType, inputLines, inputBytes, coordinatorId, workerIds, reducers, crashWorkerId (null kept)}, `WORKER_CRASH_TRIGGERED` {workerId, taskType, nodeCrashed} with peer = the crashed node, `JOB_COMPLETED` {totalMillis, resultKeys, retriedTasks}, `JOB_FAILED` {error}; plus E7b's TASK_* events.
- **Crash trigger:** with `crashWorkerId`, the module crashes that node through `Cluster.crash` (every service on it, R10) right after the first task is sent to it. `TcpTaskTransport` publishes `TASK_SENT` and then asks its port resolver for the port; the module's resolver performs the crash at that moment, on the pipeline's attempt thread (never the HTTP thread or the event dispatcher), at most once per run (`AtomicBoolean`). The attempt then finds the port closed, `TASK_ATTEMPT_FAILED` follows and the task is retried on the next worker; the order TASK_SENT, WORKER_CRASH_TRIGGERED, TASK_ATTEMPT_FAILED is fixed. The report says whether a map or a reduce task was interrupted. The node stays down until recovered on the Cluster page. Validation (400): the node must be a live worker and not the coordinator, and at least two workers must be live. A chosen node that gets no task is not crashed (`triggered: false`, with a notice).
- **Export line format** (`EventLogExporter`, causal order (lamportTime, nodeId, sequence); fields in brackets only when the event has a value):
  `<yyyy-MM-dd HH:mm:ss.SSS, UTC> | node=<nodeId> | category=<type>[ | latency=<ms>] | seq=<sequence> | lamport=<lamportTime> | module=<module>[ | peer=<peerId>][ | msg=<message>]`
  Real sample: `2026-10-09 09:49:17.590 | node=1 | category=WORKER_CRASH_TRIGGERED | seq=134 | lamport=105 | module=mapreduce | peer=3 | msg=The module crashed node 3 right after sending it its first map task`.
  Escapes in category, module and msg: `\` -> `\\`, `|` -> `\p`, LF `\n`, CR `\r`, TAB `\t`, other control characters and U+2028/U+2029 `\uXXXX`, a leading or trailing space `\s`; `EventLogExporter.unescape` reverses them. `latency=` is written only from a numeric `data.latencyMillis`, and never for an event that carries `simulatedDelayMillis`.
- **Sizing arithmetic:**
  - A request line is `MAP|sender|lamport|taskId|job|<Base64 payload>`; Base64 of n bytes is 4 x ceil(n/3). With one live worker the whole input is one split.
  - Worst case for intermediate data: distinct four-character words, 5 input bytes each. Each map-reply entry is `Base64(key) TAB Base64("1") LF` = 8 + 1 + 4 + 1 = 14 bytes, Base64-encoded again on the wire: 14 x 4/3 = 18.7 bytes per 5 input bytes, about 3.73 x the input. So the startup check is `upload-max-bytes x 4 <= max-request-bytes`.
  - Public: cap 262144 (max-request-bytes 1048576). 52429 distinct words fill the cap exactly; the map reply is 52429 x 14 + 11 (`#raw=52429` line) = 734017 bytes, 978692 in Base64, under 1048576. The C1 test proves this run completes on one live worker.
  - Local: cap 1048576 (max-request-bytes 4194304), the same ratio.
  - `POST /runs` body limit = 4 x ceil(cap/3) + `request-body-allowance-bytes` (8192): local 1398104 + 8192 = 1406296; public 349528 + 8192 = 357720.
- **Fixtures** (`frontend/src/test/fixtures/mapreduce/`, 31 real captures plus README): overview-idle, overview-after-runs, run-accepted, run-sample-word-count, run-upload-avg-latency, run-event-log-event-category, run-truncated-result, run-crash-retry, run-event-log-empty-result, run-latest, runs-history, cluster-after-crash, events-mapreduce, events-export.txt, error-404-no-run-yet, error-404-unknown-run, error-404-unknown-job, error-400-missing-fields, error-400-upload-missing, error-400-upload-file-name, error-400-upload-content-type, error-400-upload-not-base64, error-400-upload-empty, error-400-upload-too-large, error-400-upload-not-utf8, error-400-upload-nul, error-400-upload-control-character, error-400-crash-worker, error-409-busy, error-409-no-live-worker, error-413-body-too-large.
- **Test ports:** 24501-24505, 24511-24512, 24521-24522, 24531-24532 (module tests), 24541-24545 (controller test), 24701-24705 (crash tests).

---

## Known issues

- `LatencyPerNodeJob.formatResult` returns "No latency measured" when there is no data or event count is zero (Rule R7: never presents an unmeasured average as a number).
- E7c fixtures: the empty-event-log notice ("No events have been recorded yet; use another page first.") cannot be captured from a running backend, because the log always holds at least `CLUSTER_STARTED` or `CLUSTER_RESET`. `run-event-log-empty-result.json` (latency job on the event log right after a reset: no result keys and an honest notice) was captured instead; the empty-log notice is covered by `RunInputLoaderTest`.
- E7c hardened E7b's `MapReduceNodeServiceTest` (test file only; no E7b main file changed). The worker flushes its reply before it publishes the matching event (`TASK_FAILED` after the error reply, `TASK_COMPLETED` after the OK reply), so a test that reads the bus right after the reply races the worker thread. `errorTextWithPipesAndNewlinesDoesNotBreakFraming` failed in the full suite for that reason. The presence checks at the former lines 253, 287, 328, 425 and 495 now wait with Awaitility (at most 5 s).
- E7c: the worst-case intermediate data is about 3.7 x the input, so `upload-max-bytes` is limited to a quarter of `max-request-bytes` at startup. If a larger cap were ever configured without that check, a run would end FAILED with a clear `JOB_FAILED`, not crash.
- E7c: a 413 from the request body limit, and every other API error, is a ProblemDetail produced inside Spring MVC. A body rejected while it is still being sent can make some clients report a connection error instead of the 413, so the page should check `limits.uploadMaxBytes` before uploading.

---

## Lessons learned

- start the listener thread only after running is true; a service whose listener thread died must not report itself as running

---

## Progress log

- 2026-10-07 track file created.
- 2026-10-07 E3a done: causal checker, total order, Berkeley averaging and drift model implemented as pure classes; backend 282 tests, frontend 126 tests; deviations: none
- 2026-10-08 E3b done: clock UDP service on ports().clock() (600k) with Lamport and Berkeley sync; backend 482 tests, frontend 232 tests; deviations: none
- 2026-10-08 E3c done: ClockSyncModule, REST API, metrics (distributed_clock_value), and contract fixtures; backend 509 tests (3 consecutive runs: 509/509/509), frontend 232 tests; deviations: none
- 2026-10-09 E3d done: Clock Synchronization page at /experiments/3-clocksync on the E2d kit: space-time diagram (horizontal lanes per node, events as circles, UDP message arrows, causal violations marked by triangle shape and text, bounded 40 events with honest retention notice), keyboard-accessible table equivalent in (lamportTime, nodeId) order, Berkeley diverging offset visual centred on 0 ms with honest Not reached reporting for crashed nodes and SimulatedBadge, causal verification panel, local event / UDP message / traffic session / Berkeley round / drift controls with focus management and polite announcements, contract test on the 14 E3c fixtures; unit and contract tests only; live check against the real backend done by hand by Rohan (the agent's browser was unavailable); backend 763 tests, frontend 453 tests (3 runs, baseline 388); deviations: none; approved shared edits: routes.test.jsx (add-only) and the lab 3 concept line in experiments.js.
- 2026-10-09 E7a done: jobs, log parser, registry, report, and the pure MapReduce pipeline (split, map, combine, shuffle, partition, reduce, retry, timeout) implemented as pure classes; backend 915 tests (3 consecutive runs: 915/915/915), frontend 453 tests; deviations: none
  Linux (Docker, 2 CPUs): 915 tests, 0 failures, 1 skipped.
  Test resource framework-events.log was excluded by .gitignore; fixed with a narrow exception.
- 2026-10-09 E7b done: MapReduce workers on ports().mapreduce() (730k) with task retry over TCP; backend 1000 tests (3 consecutive runs: 1000/1000/1000, baseline 960); new test classes: 40 tests (5 consecutive runs: 40/40/40/40/40); deviations: two package-private test accessors (workerPool(), inboundConnections()) in MapReduceNodeService so tests wait without sleeping
- 2026-10-09 E7c done: MapReduceModule (lab 7), REST API under /api/modules/mapreduce (202 runs on a module run thread, history of 20, 400/404/409/413 errors), three inputs (bundled sample, Base64 JSON upload held in memory, live event-log snapshot with byte cap), crash-a-worker run with retry and identical result, EventLogExporter and GET /api/events/export (L5), metrics, 31 real fixtures; backend 1162 tests (3 consecutive runs: 1162/1162/1162, 1 skipped, baseline 1080); new and changed test classes 113 tests (5 consecutive runs: 113/113/113/113/113); mapreduce package in reverse order 172/172; frontend 537 tests in 71 files (no frontend code changed, fixtures only); deviations: approved add-only TaskAttemptListener hook in MapReducePipeline (E7a); approved Awaitility hardening of 6 presence checks in MapReduceNodeServiceTest (E7b, test only); upload sent as JSON with Base64 instead of multipart (approved, R3); upload cap limited to a quarter of max-request-bytes (approved, C7); request body limit is a RequestBodyAdvice inside Spring MVC rather than a servlet filter (allowed by C3 as a wrapper); MapReduceControllerTest closes its context after the class (@DirtiesContext) so no udcf-mapreduce threads outlive it; the empty-event-log fixture replaced by run-event-log-empty-result.json (known issue)
  Linux (Docker, --cpus=2): 113 tests x 5 runs on the new/changed classes, 0 failures; full suite 1162 tests, 0 failures, 1 skipped; clean-clone full suite 1162 tests, 0 failures, 1 skipped; frontend lint, build and 537 tests green.


