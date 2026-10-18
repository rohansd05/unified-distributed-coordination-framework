# Track D — Rohan

- **Owner:** Rohan
- **Branch:** `rohan`
- **Reviewer:** Rohan
- **Shared rules:** [docs/tracks/README.md](README.md)

---

## Experiment 3 — Clock Synchronization (Phase 4, moduleId: clocksync)

- **Reuse:** `core/clock/LamportClock` (never duplicate it); `legacy-demos/exp03-clock-sync` (`ClockSyncDemo`, `ClockEventLog` causal check); Appendix B Exp 3.
- **Special rules:** the Berkeley drift is simulated and labelled (R11); the time daemon came from a module-local selector (TODO(L1)) until 9A.4, and is the leader elected by Experiment 4 since then (see 9A.4).

### Steps
- [x] E3a — the causal-invariant checker, the total order (lamportTime, nodeId), Berkeley averaging with an outlier threshold, and the drift model, as pure classes. Needs: none.
- [x] E3b — clock UDP service on `ports().clock()` (600k): Lamport send and receive, Berkeley poll and adjust rounds. Needs: E3a.
- [x] E3c — `ClockSyncModule` (lab 3): a local event, send X to Y, random traffic for N seconds, a Berkeley round, verification; metric `distributed_clock_value` per node; fixtures. Needs: E3b.
- [x] E3d — the space-time diagram page and end-to-end check. Needs: E3c, E2d.

---

## Experiment 7 — MapReduce (Phase 9, moduleId: mapreduce)

- **Reuse:** `legacy-demos/exp07-mapreduce` (jobs, `JobRegistry`, `LogFields`, the pipeline); Appendix B Exp 7.
- **Special rules:** the average-latency job carries "sum;count", never an average; the coordinator came from a module-local selector (TODO(L1)) until 9A.4, and is the leader elected by Experiment 4 since then (see 9A.4); 15 s task timeout with retry on another worker.

### Steps
- [x] E7a — the jobs and the split, map, combine, shuffle, partition and reduce pipeline as pure classes. Needs: E3d.
- [x] E7b — MapReduce workers on `ports().mapreduce()` (730k) with task retry. Needs: E7a.
- [x] E7c — `MapReduceModule` (lab 7) and its API; inputs: bundled sample, uploaded .txt (size-capped), live event log; build `EventLogExporter` and `GET /api/events/export` (link L5). Needs: E7b.
- [x] E7d — the pipeline page and end-to-end check. Needs: E7c, E2d.

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
  - **Role Selector (removed in 9A.4):** `MapReduceRoleSelector.selectCoordinator(cluster)` returned the lowest live node ID and `selectWorkers(cluster)` all live nodes (`// TODO(L1)`). Since 9A.4 the coordinator is the elected leader; see "9A.4 interfaces".

  Linux (Docker, 2 CPUs): the five new test classes (40 tests) passed 5 runs in a row; full backend suite <total> tests, 0 failures, 1 skipped.

### E7c interfaces (for E7d, the MapReduce page)

- **Module:** `MapReduceModule`, id `mapreduce`, lab 7, title "MapReduce". Registered like every module (a `@Component` picked up by `ModuleRegistry`). Status: BUSY while a run is active; otherwise ERROR if the last run FAILED (cleared by the next completed run or by reset); otherwise RUNNING while any node's mapreduce service listens; otherwise IDLE. Reset clears the latest run and the history and never throws; a run still active at that moment finishes and publishes its events, but its record is dropped (its id then answers 404).
- **Roles:** until 9A.4 coordinator = lowest live node (`MapReduceRoleSelector`, `// TODO(L1)`); since 9A.4 coordinator = the leader elected by Experiment 4 (see "9A.4 interfaces"); workers = every live node, R = number of workers.
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

### E7d (the MapReduce page, /experiments/7-mapreduce)

- **Files** (all new, in `frontend/src/modules/mapreduce/`): `index.jsx`, `MapReducePage.jsx`, `RunControls.jsx`, `PipelineFlow.jsx`, `ResultsPanel.jsx`, `TaskTable.jsx`, `RunHistory.jsx`, `EventLogDownload.jsx`, `useMapReduce.js`, `mapReduceApi.js`, `uploadFile.js`, `runModel.js` (the mapping layer from DTOs to the page), `labels.js`, `motion.js`; tests `contract.test.js` (one test per E7c fixture), `mapReduceApi.test.js`, `uploadFile.test.js`, `runModel.test.js`, `labels.test.js`, `useMapReduce.test.js`, `PipelineFlow.test.jsx`, `ResultsPanel.test.jsx`, `TaskTable.test.jsx`, `RunHistory.test.jsx`, `EventLogDownload.test.jsx`, `RunControls.test.jsx`, `MapReducePage.test.jsx`, `clusterResetBusy.test.jsx`. Shared files, add-only and approved: the lab 7 `concept` line in `frontend/src/lib/experiments.js`; in `frontend/src/routes.test.jsx` two imports, three `vi.spyOn` mocks in `beforeEach` and one test.
- **Data flow:** on mount, GET overview, GET `/runs` and GET `/runs/latest` (404 = no run yet). The shown run is polled with GET `/runs/{runId}` every 500 ms only while it is RUNNING; polling stops on COMPLETED or FAILED, on a 404 (the run was cleared by a cluster reset) and on unmount. Down nodes come from the shared cluster context (GET `/api/cluster`, status CRASHED). Uploads are read in the browser, checked against `limits.uploadMaxBytes` and as UTF-8 before sending, and sent once as the JSON/Base64 body; they are never stored.
- **STOMP:** on `/topic/modules/mapreduce` the page refreshes only on `JOB_STARTED`, `JOB_COMPLETED`, `JOB_FAILED` and `WORKER_CRASH_TRIGGERED` (each string checked against the backend source); any `/topic/cluster` message and a reconnect also refresh. The shared `ModuleEventLog` keeps its own subscription.
- **Identical-result rules** (`compareResults` in `runModel.js`; the run DTO has no equality field): a crash run's result is called identical to an earlier run only when all of these hold: same `jobId`; same input type; provably the same input (the bundled sample, or an upload with the same file name and the same `inputBytes`); both runs COMPLETED; neither result list truncated; and every row equal (key, display, count, averageMillis). The baseline is the newest earlier COMPLETED run without a crash plan with the same job, input type and input name, loaded with GET `/runs/{id}`. Never for the live event log (each run adds events) and never without a baseline; in those cases the callout states only what is known: the node hit, the stage, the number of re-executed tasks, and that the run completed.
- **Chart:** a hand-drawn SVG bar chart (no chart library), `role="img"` with an `aria-label` that states the data; the results table is the full accessible alternative.

### 9A.4 interfaces (link L1): the time daemon and the MapReduce coordinator, for 9A.5 (Jai), 9A.6 (Swanand), 9A.8 (Nidhi), 9A.9

- **Who holds the role:** the Berkeley time daemon (Experiment 3) and the MapReduce coordinator (Experiment 7) are the leader elected by Experiment 4, from `RoleProvider.ensureLeader()` on the action's request thread. There is no fallback to any other node. Workers stay "every live node".
- **REST changes:**
  - `GET /api/modules/clocksync`: `timeDaemonNodeId` is `roles.leaderId()` (null: no leader yet; a read never starts an election). New field `abortedRound` {`roundId`, `daemonNodeId`, `reason`, `abortedAt`}: the most recent round when its daemon crashed during it; null once a round finishes or after a reset. `latestRound` keeps the last round that really finished.
  - `POST /api/modules/clocksync/berkeley-rounds`: 202 as before (asynchronous); 409 `{title: "No leader", moduleId: "clocksync", reason: "NO_LEADER"}` when no leader could be elected within the election bound (about 3.1 s; every node down is answered this way, it was an unhandled 500 before).
  - `GET /api/modules/mapreduce`: `coordinatorId` is `roles.leaderId()` (null: no leader yet). A run's `coordinatorId` is the leader when it started; a running job keeps it.
  - `POST /api/modules/mapreduce/runs`: 409 "No live worker" at once when every node is down (unchanged); otherwise 409 `{title: "No leader", moduleId: "mapreduce", reason: "NO_LEADER"}` when no leader could be elected. `crashWorkerId` equal to the leader is refused (400, "is the coordinator of this run").
- **Side effect:** the first Berkeley round or job with no leader starts Experiment 4's election service and the shared failure detector's heartbeats on every live node (a `REQUESTED` Bully round).
- **Events** (for the 9A.5 timeline and the 9A.6 leader-crash test). Each change event is published at most once per new leader, only after the module acted since the last reset, never for a vacancy (crash or reset), always after the `LEADER_CHANGED` that elected the new leader and before the next round's or job's STARTED event (bus sequence):
  - `TIME_DAEMON_CHANGED` (module `clocksync`, node = new daemon, one tick of its clock, peer = previous daemon), data `{nodeId, previousNodeId, cause}` with `cause` `LEADER_CRASHED` (the previous daemon is down) or `ELECTED`. Message: "Node 4 is the time daemon for the next Berkeley round: node 5 crashed and Experiment 4 elected node 4." or "Node 5 is the time daemon for the next Berkeley round: Experiment 4 elected node 5 in place of node 4."
  - `COORDINATOR_CHANGED` (module `mapreduce`, node = new coordinator, one tick, peer = previous), same data. Message: "Node 4 coordinates the next job: node 5 crashed and Experiment 4 elected node 4. A running job keeps its coordinator." (or "...: Experiment 4 elected node 5 in place of node 4. A running job keeps its coordinator.")
  - `BERKELEY_ROUND_ABORTED` (module `clocksync`, node = the daemon, its current clock), data `{roundId, reason}` (the service's round id, as in `BERKELEY_ROUND_STARTED`), message "The time daemon, node 5, crashed during the poll stage, so the Berkeley round was aborted." (or "adjustment stage"). No `BERKELEY_ROUND_FINISHED` follows.
  - `JOB_FAILED` when the coordinator crashed during a run: `error` "The coordinator, node 5, crashed during the run, so the run stopped. The next run asks Experiment 4 for the new leader." No task is sent from a crashed coordinator.
- **Ordering note for 9A.5:** the change events use the new holder's Lamport clock, `LEADER_CHANGED` uses the cluster clock (node 0). Their `(lamportTime, nodeId)` order is therefore not causal; the "after LEADER_CHANGED" guarantee is the bus sequence.
- **Test ports (9A.4):** election 24861-24865 (`ClockSyncControllerTest`), 24871-24875 (`MapReduceControllerTest`), 24881-24885 (`ClockSyncLeaderIntegrationTest`), 24891-24895 (`MapReduceLeaderIntegrationTest`); clock 24621-24625; mapreduce 24551-24555, 24571-24573; placeholders 24810-24849 are never bound.

---

## Known issues

- `LatencyPerNodeJob.formatResult` returns "No latency measured" when there is no data or event count is zero (Rule R7: never presents an unmeasured average as a number).
- E7c fixtures: the empty-event-log notice ("No events have been recorded yet; use another page first.") cannot be captured from a running backend, because the log always holds at least `CLUSTER_STARTED` or `CLUSTER_RESET`. `run-event-log-empty-result.json` (latency job on the event log right after a reset: no result keys and an honest notice) was captured instead; the empty-log notice is covered by `RunInputLoaderTest`.
- E7c hardened E7b's `MapReduceNodeServiceTest` (test file only; no E7b main file changed). The worker flushes its reply before it publishes the matching event (`TASK_FAILED` after the error reply, `TASK_COMPLETED` after the OK reply), so a test that reads the bus right after the reply races the worker thread. `errorTextWithPipesAndNewlinesDoesNotBreakFraming` failed in the full suite for that reason. The presence checks at the former lines 253, 287, 328, 425 and 495 now wait with Awaitility (at most 5 s).
- E7c: the worst-case intermediate data is about 3.7 x the input, so `upload-max-bytes` is limited to a quarter of `max-request-bytes` at startup. If a larger cap were ever configured without that check, a run would end FAILED with a clear `JOB_FAILED`, not crash.
- E7c: a 413 from the request body limit, and every other API error, is a ProblemDetail produced inside Spring MVC. A body rejected while it is still being sent can make some clients report a connection error instead of the 413, so the page should check `limits.uploadMaxBytes` before uploading.
- E7d: runs finish in milliseconds, so the page rarely shows a run as Running (it is seen in the 202 reply and for at most one 500 ms poll). The pipeline shows real counts only once the run has ended; nothing is shown as progress while it runs.
- E7d: `ApiError` (services/api.js, shared) keeps only `status`, `title`, `detail` and `errors`, so `limitBytes`, `actionInProgress` and `jobId` from the ProblemDetail are dropped; the page shows the backend's `detail` text, which contains the same information. services/api.js was not changed.
- E7d: the live empty-event-log notice ("No events have been recorded yet; use another page first.") cannot occur on a running backend (see the E7c known issue above); it is covered by `RunInputLoaderTest`. The page shows whatever `notice` the backend sends, word for word.
- E7d: the page joins the single eager bundle; the main chunk grew from 601.68 kB (182.57 kB gzip) to 646.27 kB (195.79 kB gzip). Vite's 500 kB size advice was already exceeded before this step; the bundle strategy is unchanged.
- E7d: the page was checked with unit, contract and page tests, and by hand against the real backend from the E7d checklist (21 checks, verified by Rohan).
- 9A.4, **cluster reset right after a node recovery:** `POST /api/cluster/reset` answers 409 while any module reports BUSY (`ClusterResetService.java:46-47`). Right after a node recovers, Experiment 4 opens a `RECOVERY` election round: the recovered node's Bully emits `RECOVER` (`ElectionModule.java:306`), or `ElectionModule.onClusterEvent` wires a not-yet-wired recovered node and opens the round itself (`ElectionModule.java:352`). Experiment 4 is BUSY while a round is open (`ElectionModule.java:140-141`), for about a second. Scripts and scenarios (9A.6, 9A.9) must wait or retry. The capture transcript said every module was BUSY ("rmi, multithreading, ..., matrix"). That came from the capture script, not the backend: in Windows PowerShell 5.1, `Invoke-RestMethod` passes a JSON array down the pipeline as one object, so `Where-Object { $_.status -eq 'BUSY' } | ForEach-Object { $_.id }` lists every id as soon as any one module is BUSY (reproduced offline with `ConvertFrom-Json`). Wrap the call in parentheses, `(Invoke-RestMethod ...) | Where-Object ...`, to list only the busy module(s). Not confirmed against a live backend which modules were BUSY; by the code above it is Experiment 4.
- 9A.4, **MapReduce 409 `NO_LEADER` not capturable:** with every node down, the earlier 409 "No live worker" answers first; with any node live, Experiment 4 elects it. The body is pinned by `MapReduceControllerTest.noLeader` (a module without an election module) and kept as the labelled `fixtures/mapreduce/synthetic/error-409-no-leader.json`.
- 9A.4, **aborted round needs a crash inside the poll window:** with every node replying, a Berkeley round finishes in milliseconds, so `BERKELEY_ROUND_ABORTED` and `abortedRound` are hard to produce by hand (the page notice is checked against the labelled synthetic `fixtures/clocksync/synthetic/overview-aborted-round.json`; the backend paths by `ClockNodeServiceTest.daemonCrashDuringPollAbortsRound` and `ClockSyncModuleTest.abortedRoundShownInOverview`).
- 9A.4, **crash picker before the first election:** with no leader yet the overview has `coordinatorId: null`, so the Experiment 7 page offers every live worker, including the node that will win the election (node 5). Picking it gets 400 "node 5 is the coordinator of this run; choose another live worker" once the job's election has run. Honest, but one extra step.
- 9A.4, **`cause` of a change event** is read when the event is published: `LEADER_CRASHED` if the previous holder is down at that moment, otherwise `ELECTED`. A previous holder that crashed and was already recovered before the new leader was announced would read `ELECTED`.
- 9A.4: the change events use the new holder's Lamport clock and `LEADER_CHANGED` the cluster clock (node 0), so their `(lamportTime, nodeId)` order is not causal; the bus sequence carries the "after LEADER_CHANGED" guarantee (for the 9A.5 timeline).
- 9A.4: the manual checklist was run by Rohan on 2026-10-18 (see the Result column; step 10 not reachable from the page because the round button is disabled with every node down) and the clean-clone Linux run passed the same day (see the progress log). GitHub CI is still the Linux gate.
- 9A.4 (observed, predates 9A.4): the 202 roundId of POST /berkeley-rounds and the round number in the BERKELEY_ROUND_* event messages are two different counters (`ClockSyncModule.java:99`, incremented at `:353`, returned at `:367`; and the daemon service's own `ClockNodeService.java:78`, incremented at `:320`, used in the messages at `:327` and `:429`), so the page toast ("round 5") and the event log ("round 2") can name the same round differently; abortedRound.roundId uses the module counter (`ClockSyncModule.java:362`), while the event data `roundId` of BERKELEY_ROUND_STARTED / FINISHED / ABORTED uses the service counter.

---

## Lessons learned

- start the listener thread only after running is true; a service whose listener thread died must not report itself as running

---

## Phase 9A (R19)

- [x] 9A.0 — docs: R19, the Phase 9A plan (README section 11), CLAUDE.md. Needs: none.
- [x] 9A.4 — Experiment 3 Berkeley time daemon and Experiment 7 coordinator from the shared role provider; `TODO(L1)` selectors removed; fixtures; page hints. Needs: 9A.1.
- [x] 9A.5 — Timeline page `/timeline` (reassigned from Jai for the 2026-10-23 demo). Needs: 9A.0.
- [x] 9A.9 — demo package: `scripts/start-all.ps1`, `scripts/stop-all.ps1`, `docs/demo/udcf-demo.md`; the refreshed public lite deployment moves to Phase 16. Needs: 9A.7, 9A.8.
  - [x] 9A.9a — demo scripts: `scripts/start-all.ps1`, `scripts/stop-all.ps1`, `scripts/reset-cluster.ps1`.
  - [x] 9A.9b — demo script `docs/demo/udcf-demo.md`; Phase 9A position update (CLAUDE.md, HANDOFF, README section 11).

### 9A.4: the time daemon and the MapReduce coordinator follow the elected leader (link L1)

**How both modules use `RoleProvider`** (APIs for other tracks: "9A.4 interfaces" under Interfaces for other tracks):

| Call | Where | Thread |
|---|---|---|
| `ensureLeader()` | `ClockSyncModule.daemon()` (both round paths) and `MapReduceModule.coordinator()` (`startRun`, after validation and the "No live worker" check) | the HTTP request thread, holding no lock, before the module's action guard; empty: `NoLeaderException`, HTTP 409 `NO_LEADER` |
| `leaderId()` | both `overview()` methods; the leader listeners (to skip a stale change) | any; never waits, never elects |
| `addLeaderListener` | both constructors; the registration is closed in `close()` | `udcf-roles-dispatcher`; never blocks, never calls `ensureLeader` |

`ClockSyncRoleSelector` and `MapReduceRoleSelector` (and their tests) are deleted; no `TODO(L1)` is left in either package.

**Publish rule (CAS plus a short lock).** Each module remembers the node that acted as daemon or coordinator since the last reset in an `AtomicReference` (null after a reset and before the first use). Both paths call one `follow(leader, fromAction)`:
- the leader listener, with `change.leaderId()` only when it is not null and still equals `leaderId()`;
- the round or job, right after `ensureLeader()` returned the leader and before anything is published or the guard is taken.

Inside a `synchronized` block on the reference: if nothing is remembered, only the action path remembers the leader, silently (the first holder is not a change). If something else is remembered, a compare-and-set to the new leader decides the one winner, which publishes `TIME_DAEMON_CHANGED` or `COORDINATOR_CHANGED`. The block makes the loser wait until the winner's event is on the bus, so the change event always precedes the next STARTED. It follows `LEADER_CHANGED`, because the leader was announced before it could be read. It is never published twice, and never for a vacancy or a reset (`reset()` clears the reference). The block holds no other lock and never waits on anything but the bus publish.

**Leader-crash sequence** (5 nodes, node 5 leader, both modules used since the reset):
1. HTTP thread: `POST /api/cluster/nodes/5/crash`, `Cluster.crash(5)`. Node 5's services close; `LEADER_CHANGED` (leader none) is published and `LEADER_CRASHED` is queued. Dispatcher: both listeners see no leader and publish nothing.
2. About 2.5 to 4 s later: Experiment 4's failure detector suspects node 5, Bully elects node 4, and `Cluster.assignLeader(4)` publishes `LEADER_CHANGED` (4) and queues `ELECTED`.
3. Dispatcher, or the next round or job if it comes first: `TIME_DAEMON_CHANGED` and `COORDINATOR_CHANGED` {nodeId 4, previousNodeId 5, cause `LEADER_CRASHED`}, once each.
4. The next Berkeley round and the next job get 4 from `ensureLeader()` at once. Between steps 1 and 2 they wait at most the election bound (3.1 s) for the re-election.

**In-flight endings:**
- **Berkeley round:** if the daemon's clock service stops during the round (its node crashed), `ClockNodeService` checks after the poll stage and again before finishing. It publishes `BERKELEY_ROUND_ABORTED` and throws `BerkeleyRoundAbortedException`, instead of reporting every peer as unresponsive and finishing on a crashed node (before 9A.4). `POST /berkeley-rounds` already answered 202 (asynchronous), so the abort is shown by `GET /api/modules/clocksync` `abortedRound` {roundId (the 202's id), daemonNodeId, reason, abortedAt} and in the event log. `latestRound` keeps the last round that finished. The Experiment 3 page shows `abortedRound` as a calm `role="status"` notice and clears it when the field is null.
- **MapReduce job:** a running job keeps its coordinator. If that node crashes, `TcpTaskTransport` refuses to send from it (`IOException`), every attempt fails fast, and the run ends FAILED with "The coordinator, node 5, crashed during the run, so the run stopped. The next run asks Experiment 4 for the new leader." plus `JOB_FAILED`. Before 9A.4 such a run could complete, coordinated by a crashed node.

**Served texts and pages.** Experiment 3 adds the note "The time daemon is the leader elected by Experiment 4. With no leader yet, the first Berkeley round asks Experiment 4 for an election, which starts its election service and heartbeats on every live node; if no leader can be elected, the round is refused." Experiment 7's second note says the same for the coordinator and the first job.
- Experiment 3 page: "Time daemon for the next round: Node 5, the leader elected by Experiment 4" or "No leader yet: the first Berkeley round asks Experiment 4 to elect one".
- Experiment 7 page: "Coordinator: node 5, the elected leader when this run started".
- Both pages: the 409 "No leader" is a calm amber warning (keyed on status 409 and title "No leader", because the shared `ApiError` drops `reason`).
- The new events show in the module logs as "Time daemon changed", "Coordinator changed" and "Berkeley round aborted" with their messages; both pages already refresh on every `/topic/cluster` message (`LEADER_CHANGED`).

**Fixtures.**
- Recaptured by Rohan on 2026-10-17: the full Experiment 3 sequence (14 files) plus the new `clocksync/error-409-no-leader.json`, and the 15 role-bearing Experiment 7 files. Order and contents are in the two fixture READMEs.
- Labelled synthetic files: `clocksync/synthetic/overview-aborted-round.json` (needs a daemon crash inside the poll window) and `mapreduce/synthetic/error-409-no-leader.json` (copied from `MapReduceControllerTest.noLeader`).
- Capture procedure: the block below. Rohan's first run stopped at the third reset with HTTP 409 (see Known issues). The re-run used `Reset-Cluster`, which waits for no BUSY module and retries on 409. Its "Waiting for busy modules" line lists every module in Windows PowerShell 5.1; see Known issues.

<details>
<summary>The capture block as run on 2026-10-17 (amended with Reset-Cluster)</summary>

```powershell
# 9A.4 fixture capture: Experiment 3 (full sequence + 409 NO_LEADER) and Experiment 7 (role-bearing set).
# Amended: Reset-Cluster waits for busy modules (e.g. a recovery election) and retries on 409.
$ErrorActionPreference = 'Stop'
$Repo = 'C:\Users\rohan\Desktop\DC\PROGRAM\unified-distributed-coordination-framework'
$Api = 'http://localhost:8080'
$Cs = Join-Path $Repo 'frontend\src\test\fixtures\clocksync'
$Mr = Join-Path $Repo 'frontend\src\test\fixtures\mapreduce'
$LogFile = Join-Path $Repo 'legacy-demos\exp07-mapreduce\data\framework-events.log'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$Captured = New-Object System.Collections.Generic.List[string]
$Problems = New-Object System.Collections.Generic.List[string]

function Save-Get([string]$Path, [string]$File, [string]$Expect = '200') {
    $code = curl.exe -s -o $File -w '%{http_code}' "$Api$Path"
    $Captured.Add($File)
    if ($code -ne $Expect) { $Problems.Add("$(Split-Path $File -Leaf): HTTP $code, expected $Expect") }
    "GET  $code $(Split-Path $File -Leaf)"
}
function Save-Send([string]$Method, [string]$Path, [string]$Body, [string]$File, [string]$Expect) {
    $code = $Body | curl.exe -s -o $File -w '%{http_code}' -X $Method -H 'Content-Type: application/json' --data-binary '@-' "$Api$Path"
    $Captured.Add($File)
    if ($code -ne $Expect) { $Problems.Add("$(Split-Path $File -Leaf): HTTP $code, expected $Expect") }
    "$Method $code $(Split-Path $File -Leaf)"
}
function Invoke-Post([string]$Path, [string]$Body = $null) {
    if ($Body) { Invoke-RestMethod -Method Post -Uri "$Api$Path" -ContentType 'application/json' -Body $Body }
    else { Invoke-RestMethod -Method Post -Uri "$Api$Path" }
}
function Reset-Cluster {
    $deadline = (Get-Date).AddSeconds(30)
    while ($true) {
        $busy = @(Invoke-RestMethod "$Api/api/modules" | Where-Object { $_.status -eq 'BUSY' } | ForEach-Object { $_.id })
        if ($busy.Count -eq 0) {
            $code = curl.exe -s -o NUL -w '%{http_code}' -X POST "$Api/api/cluster/reset"
            if ($code -eq '204' -or $code -eq '200') { "Cluster reset"; return }
            "Cluster reset refused (HTTP $code), retrying"
        } else { "Waiting for busy modules: $($busy -join ', ')" }
        if ((Get-Date) -gt $deadline) { throw "Cluster reset kept failing" }
        Start-Sleep -Milliseconds 500
    }
}
function Wait-Until([string]$What, [scriptblock]$Condition, [int]$Seconds = 30) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while (-not (& $Condition)) {
        if ((Get-Date) -gt $deadline) { throw "Timed out waiting for $What" }
        Start-Sleep -Milliseconds 200
    }
}
function Wait-ClockIdle { Wait-Until 'Experiment 3 idle' { (Invoke-RestMethod "$Api/api/modules/clocksync").status -ne 'BUSY' } }
function Wait-Run([string]$RunId) {
    Wait-Until "run $RunId" { (Invoke-RestMethod "$Api/api/modules/mapreduce/runs/$RunId").state -ne 'RUNNING' } 60
    Wait-Until 'Experiment 7 idle' { (Invoke-RestMethod "$Api/api/modules/mapreduce").status -ne 'BUSY' } 60
}
function Run-And-Save([string]$Body, [string]$File) {
    $run = Invoke-Post '/api/modules/mapreduce/runs' $Body
    Wait-Run $run.runId
    Save-Get "/api/modules/mapreduce/runs/$($run.runId)" $File
}
function Upload-Body([string]$JobId, [string]$FileName, [byte[]]$Bytes) {
    '{"jobId":"' + $JobId + '","inputType":"UPLOAD","upload":{"fileName":"' + $FileName +
        '","contentType":"text/plain","contentBase64":"' + [Convert]::ToBase64String($Bytes) + '"}}'
}

# 0. The backend must be up; then start from a clean slate.
$health = Invoke-RestMethod "$Api/api/system/health"
"Backend health: $($health | ConvertTo-Json -Compress)"
Reset-Cluster

# 1. Experiment 3, in the E3c order.
Save-Get '/api/modules/clocksync' (Join-Path $Cs 'overview-initial.json')
Save-Send POST '/api/modules/clocksync/nodes/1/local-events' '{"description":"Manual local event on node 1"}' (Join-Path $Cs 'local-event.json') '200'
Save-Send POST '/api/modules/clocksync/messages' '{"from":1,"to":2,"payload":"Lamport ping to node 2"}' (Join-Path $Cs 'message-live.json') '200'
Invoke-Post '/api/cluster/nodes/3/crash' | Out-Null
Save-Send POST '/api/modules/clocksync/messages' '{"from":3,"to":1,"payload":"Ping from crashed node 3"}' (Join-Path $Cs 'error-409-node-down.json') '409'
Save-Send POST '/api/modules/clocksync/messages' '{"from":1,"to":3,"payload":"Message to crashed node 3"}' (Join-Path $Cs 'message-crashed-receiver.json') '200'
Invoke-Post '/api/cluster/nodes/3/recover' | Out-Null
Save-Send POST '/api/modules/clocksync/traffic' '{"seconds":2,"messagesPerSecond":4}' (Join-Path $Cs 'traffic.json') '202'
Wait-ClockIdle
Save-Send POST '/api/modules/clocksync/berkeley-rounds' '{"outlierThresholdMillis":400}' (Join-Path $Cs 'berkeley-round.json') '202'
Wait-ClockIdle
Wait-Until 'the Berkeley round result' { $null -ne (Invoke-RestMethod "$Api/api/modules/clocksync").latestRound }
Save-Get '/api/modules/clocksync' (Join-Path $Cs 'overview-after-sync.json')
Save-Send PUT '/api/modules/clocksync/nodes/2/drift' '{"initialOffsetMillis":95,"driftRateMsPerSec":1.8}' (Join-Path $Cs 'drift-updated.json') '200'
Save-Get '/api/modules/clocksync/verification' (Join-Path $Cs 'verification.json')
Save-Get '/api/modules/clocksync/timeline?limit=10' (Join-Path $Cs 'timeline.json')
Save-Send POST '/api/modules/clocksync/traffic' '{"seconds":50,"messagesPerSecond":2}' (Join-Path $Cs 'error-400-validation.json') '400'
Save-Send POST '/api/modules/clocksync/nodes/99/local-events' '{"description":"Ghost"}' (Join-Path $Cs 'error-404-unknown-node.json') '404'
Save-Get '/api/events?module=clocksync&limit=50' (Join-Path $Cs 'events.json')
1..5 | ForEach-Object { Invoke-Post "/api/cluster/nodes/$_/crash" | Out-Null }
Save-Send POST '/api/modules/clocksync/berkeley-rounds' '{}' (Join-Path $Cs 'error-409-no-leader.json') '409'
Reset-Cluster

# 2. Experiment 7, the role-bearing files in the E7c order.
$terms = [Text.Encoding]::UTF8.GetBytes(((1..300 | ForEach-Object { "term$_" }) -join ' '))
Save-Get '/api/modules/mapreduce' (Join-Path $Mr 'overview-idle.json')
Save-Send POST '/api/modules/mapreduce/runs' '{"jobId":"word-count","inputType":"SAMPLE"}' (Join-Path $Mr 'run-accepted.json') '202'
$accepted = Get-Content -Raw -Encoding UTF8 (Join-Path $Mr 'run-accepted.json') | ConvertFrom-Json
Wait-Run $accepted.runId
Save-Get "/api/modules/mapreduce/runs/$($accepted.runId)" (Join-Path $Mr 'run-sample-word-count.json')
Run-And-Save (Upload-Body 'avg-latency-per-node' 'framework-events.txt' ([IO.File]::ReadAllBytes($LogFile))) (Join-Path $Mr 'run-upload-avg-latency.json')
Run-And-Save '{"jobId":"event-category-count","inputType":"EVENT_LOG"}' (Join-Path $Mr 'run-event-log-event-category.json')
Run-And-Save (Upload-Body 'word-count' 'terms.txt' $terms) (Join-Path $Mr 'run-truncated-result.json')
Run-And-Save '{"jobId":"word-count","inputType":"SAMPLE","crashWorkerId":3}' (Join-Path $Mr 'run-crash-retry.json')
Save-Get '/api/cluster' (Join-Path $Mr 'cluster-after-crash.json')
Invoke-Post '/api/cluster/nodes/3/recover' | Out-Null
Save-Get '/api/modules/mapreduce/runs' (Join-Path $Mr 'runs-history.json')
Save-Get '/api/modules/mapreduce/runs/latest' (Join-Path $Mr 'run-latest.json')
Save-Get '/api/modules/mapreduce' (Join-Path $Mr 'overview-after-runs.json')
Save-Get '/api/events?module=mapreduce&limit=50' (Join-Path $Mr 'events-mapreduce.json')
Save-Get '/api/events/export?module=mapreduce&limit=25' (Join-Path $Mr 'events-export.txt')
Save-Send POST '/api/modules/mapreduce/runs' '{"jobId":"word-count","inputType":"SAMPLE","crashWorkerId":5}' (Join-Path $Mr 'error-400-crash-worker.json') '400'
Reset-Cluster
Run-And-Save '{"jobId":"avg-latency-per-node","inputType":"EVENT_LOG"}' (Join-Path $Mr 'run-event-log-empty-result.json')

# 3. Per file: name, bytes, BOM, and the checks that prove the 9A.4 state.
function Read-Json([string]$File) { Get-Content -Raw -Encoding UTF8 $File | ConvertFrom-Json }
$DaemonNote = 'The time daemon is the leader elected by Experiment 4.'
$CoordinatorNote = 'The coordinator is the leader elected by Experiment 4'
$Checks = @{
    'overview-initial.json'           = { param($j) "timeDaemonNodeId=$(if ($null -eq $j.timeDaemonNodeId) {'null'} else {$j.timeDaemonNodeId}) abortedRound-key=$($j.PSObject.Properties.Name -contains 'abortedRound') daemon-note=$(@($j.notes | Where-Object { $_.StartsWith($DaemonNote) }).Count -eq 1)" }
    'berkeley-round.json'             = { param($j) "daemonNodeId=$($j.daemonNodeId) status=$($j.status)" }
    'overview-after-sync.json'        = { param($j) "timeDaemonNodeId=$($j.timeDaemonNodeId) latestRound.daemonNodeId=$($j.latestRound.daemonNodeId) abortedRound-null=$($null -eq $j.abortedRound)" }
    'events.json'                     = { param($j) "BERKELEY_ROUND_STARTED.daemonId=$((@($j | Where-Object { $_.type -eq 'BERKELEY_ROUND_STARTED' }) | ForEach-Object { $_.data.daemonId }) -join ',')" }
    'error-409-no-leader.json'        = { param($j) "status=$($j.status) title=$($j.title) reason=$($j.reason) moduleId=$($j.moduleId)" }
    'overview-idle.json'              = { param($j) "coordinatorId=$(if ($null -eq $j.coordinatorId) {'null'} else {$j.coordinatorId}) workerIds=$($j.workerIds -join ',') coordinator-note=$(@($j.notes | Where-Object { $_.StartsWith($CoordinatorNote) }).Count -eq 1)" }
    'overview-after-runs.json'        = { param($j) "coordinatorId=$($j.coordinatorId) latestRun.coordinatorId=$($j.latestRun.coordinatorId)" }
    'cluster-after-crash.json'        = { param($j) "node5.roles=$(($j.nodes | Where-Object { $_.id -eq 5 }).roles -join ',') node3.status=$(($j.nodes | Where-Object { $_.id -eq 3 }).status)" }
    'runs-history.json'               = { param($j) "runs=$(@($j).Count) states=$((@($j) | ForEach-Object { $_.state }) -join ',')" }
    'events-mapreduce.json'           = { param($j) "JOB_STARTED.nodeIds=$((@($j | Where-Object { $_.type -eq 'JOB_STARTED' }) | ForEach-Object { $_.nodeId }) -join ',')" }
    'error-400-crash-worker.json'     = { param($j) "status=$($j.status) crashWorkerId-error='$($j.errors.crashWorkerId)'" }
}
""
"{0,-34} {1,8} {2,-4} {3}" -f 'File', 'Bytes', 'BOM', 'Checks'
foreach ($file in $Captured) {
    $name = Split-Path $file -Leaf
    $bytes = [IO.File]::ReadAllBytes($file)
    $bom = if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) { 'yes' } else { 'no' }
    if ($name -eq 'events-export.txt') {
        $text = [Text.Encoding]::UTF8.GetString($bytes)
        $check = "JOB_STARTED-on-node5-lines=$(([regex]::Matches($text, 'node=5 \| category=JOB_STARTED')).Count)"
    } elseif ($name -like 'run-*.json') {
        $j = Read-Json $file
        $check = "coordinatorId=$($j.coordinatorId) state=$($j.state) resultKeys=$($j.report.resultKeys) crash=$(if ($j.crash) { "$($j.crash.workerId)/$($j.crash.triggered)/$($j.crash.taskType)" } else { 'none' })"
    } elseif ($Checks.ContainsKey($name)) {
        $check = & $Checks[$name] (Read-Json $file)
    } else {
        $check = '-'
    }
    "{0,-34} {1,8} {2,-4} {3}" -f "$(Split-Path (Split-Path $file -Parent) -Leaf)/$name", $bytes.Length, $bom, $check
}
""
if ($Problems.Count -eq 0) { "All HTTP status codes as expected." } else { "STATUS PROBLEMS:"; $Problems }
```

The check `JOB_STARTED-on-node5-lines=0` for `events-export.txt` is expected: the export holds the last 25 events, the tail of the crash run, and that run's `JOB_STARTED` is older. Every coordinator line in it is `node=5` and every worker line carries `peer=5`.

</details>

**Tests.**
- New: `ClockSyncLeaderIntegrationTest` (3) and `MapReduceLeaderIntegrationTest` (2). Both run on real sockets with the real `ElectionModule`: reads never elect; the first action elects node 5; after a leader crash, exactly one change event, after `LEADER_CHANGED` and before the next STARTED, and the next round or job on node 4.
- Added to existing classes: `ClockSyncModuleTest` +6, `ClockNodeServiceTest` +1, `ClockSyncControllerTest` +1, `MapReduceModuleTest` +5, `MapReduceControllerTest` +1.
- Deleted: the two selector tests (3 + 3).
- The unit tests use labelled test electors ("test elector, not Bully"): highest live node in `ClockSyncModuleTest`, lowest live node in `MapReduceModuleHarness`.
- Frontend: +6 (`ClockSyncPage.test` +2, `ClockSyncControls.test` +1, `RunControls.test` +1, one synthetic-fixture test in each `contract.test.js`), and the recaptured values in five test files.

**Manual checklist** (for Rohan, on the real backend; not yet run):

| # | Action | Expected | Result |
|---|---|---|---|
| 1 | Fresh backend and frontend; open `/experiments/3-clocksync` with the browser console open | "No leader yet: the first Berkeley round asks Experiment 4 to elect one"; the Experiment 4 page shows no round; the notes include "The time daemon is the leader elected by Experiment 4. ..." | PASS |
| 2 | Experiment 3: run a Berkeley round | Experiment 4 page: a finished "Requested" round electing node 5; Experiment 3: "Time daemon for the next round: Node 5, ..."; the round finishes with node 5 as daemon | PASS |
| 3 | `/experiments/7-mapreduce`: word count on the bundled sample | Completed; "Coordinator: node 5, the elected leader when this run started"; no new election round on the Experiment 4 page | PASS |
| 4 | Cluster page: crash node 5 | Both pages: the daemon line goes to "No leader yet ...", then within about 4 s to node 4 | PASS |
| 5 | Experiment 3 and Experiment 7 event logs | "Time daemon changed" (node 4 ... node 5 crashed and Experiment 4 elected node 4.) and "Coordinator changed" (... A running job keeps its coordinator.), each once, after `LEADER_CHANGED` (node 4) in the cluster events | PASS |
| 6 | Experiment 3: run a Berkeley round | Daemon node 4; node 5 shown as not reached | PASS |
| 7 | Experiment 7: run a job; then try a crash run on node 4 | The job is coordinated by node 4 (workers 1 to 4); the crash run on node 4 is refused: "node 4 is the coordinator of this run; choose another live worker" | PASS |
| 8 | Cluster page: recover node 5 (wait a second for its election) | Node 5 wins Bully again; both logs show one change event back to node 5 with "Experiment 4 elected node 5 in place of node 4." | PASS |
| 9 | Cluster page: reset the cluster (retry if it answers busy right after the recovery) | No change event; Experiment 3 shows "No leader yet ..." | PASS |
| 10 | Cluster page: crash every node; Experiment 3: run a Berkeley round | After about 3 s a calm amber "No leader" warning with "No leader could be elected, so there is no time daemon: ..."; no red error | Differs, not a defect: with every node down the page disables "Run synchronization round" (existing E3d guard), so the warning is not reachable from the page; the 409 NO_LEADER is covered by the captured fixture clocksync/error-409-no-leader.json and the ClockSyncControls test. |
| 11 | With every node still down, Experiment 7: run a job; then reset the cluster | The existing red "No live worker" alert with the Cluster page link (at once, no election wait); after the reset both pages work again | PASS |
| 12 | Throughout; then stop both servers and run `Get-NetTCPConnection -LocalPort 8080,5173 -State Listen` | Browser console clean; nothing listening | PASS |

Run by Rohan on 2026-10-18 against the real backend; 11 of 12 steps passed and step 10 differs as noted (not a defect); browser console clean.

**Deviations (9A.4):**
- Baseline correction: the Stage A baseline of 1915 backend tests was summed from every surefire report in `target/`, including a stale report of `FailoverRoleSelectorTest` (3 tests, deleted in 9A.1); the true baseline is 1912 (as in Track B's 9A.1 log). Then 1912 - 6 + 19 = 1925.
- Ruling R4: `abortedRound` was added to `ClockSyncOverviewDto`, with two new classes, `BerkeleyRoundAbortedException` and `dto/AbortedRoundDto`, and a calm notice on the Experiment 3 page.
- Beyond ruling R2: a short `synchronized` block around read, compare-and-set and publish, so the CAS loser cannot run ahead of the winner's bus publish (the change event always precedes the next STARTED).
- Extra tests beyond the plan: `ClockSyncModuleTest` +6 (plan 5, the R4 abort test) and `MapReduceModuleTest` +5 (plan 4, quiet and crash-cause split).
- The MapReduce 409 `NO_LEADER` fixture is synthetic (`synthetic/error-409-no-leader.json`, copied from `MapReduceControllerTest.noLeader`); see Known issues.
- The capture block was amended by Rohan with `Reset-Cluster` (waits for no BUSY module, retries on 409) after the first run stopped at the third reset with 409.
- Every node down on Experiment 3 now answers 409 `NO_LEADER` after the election bound instead of the old unhandled 500 (approved).

### 9A.5 (fast mode)

**What changed:** the `/timeline` stub became the Timeline page. Frontend only; no backend change, no fixture recaptured.
- History from the existing `GET /api/events?limit=500` (`EventController`; no new endpoint), reloaded on every STOMP (re)connect and merged; live from `/topic/events`. Deduplicated by `sequence`, nothing older than the latest `CLUSTER_RESET`, at most 2000 events kept (the oldest by sequence go first).
- Order `(lamportTime, nodeId)`, ties by bus sequence, or "arrival order (bus sequence)". A note on the page says why the bus sequence, not Lamport order, guarantees "after LEADER_CHANGED".
- Hand-drawn SVG: a cluster lane (node 0) plus one lane per node from `useCluster` (dashed and "(down)" for a crashed node). Positions are ranks in the chosen order, not a time scale. The latest 120 events show there; the list beneath has all of them. Shapes plus a text legend, so colour is never the only signal. Clicking a marker or row shows every field and the `data` JSON.
- Readable sentences for LEADER_CHANGED, TIME_DAEMON_CHANGED, COORDINATOR_CHANGED, MPI_ROOT_CHANGED, BERKELEY_ROUND_ABORTED, PRIMARY_PROMOTED, PRIMARY_SELECTED, REPLICATION_ROLES_CHANGED, HANDOVER_STARTED, NODE_CRASHED, NODE_RECOVERED, CLUSTER_RESET and the six scenario types. An unknown type shows its message (or its raw type) and never throws.
- Leader chain: the latest LEADER_CHANGED with a leader (or a selected one), plus every role change after it by bus sequence, before the next LEADER_CHANGED, that hands a role to the same node. It is ringed and linked on the lanes, marked in the list and summed up in one line. A vacancy (leader null) shows no chain.
- Scenario runs show as bands from SCENARIO_STARTED to SCENARIO_FINISHED (matched on `data.runId` when present). Pause live, clear view (client side only, with "Show cleared events"), a calm `role="status"` banner while STOMP is `reconnecting` or `error`, and empty states for no events, cleared and no match.
- Files: `frontend/src/components/timeline/{timelineModel.js, useTimelineEvents.js, TimelineLanes.jsx}` plus tests; `frontend/src/pages/TimelinePage.jsx` (stub replaced) plus `TimelinePage.test.jsx`. The route and the sidebar entry already existed, so they were not changed.

**Interfaces for other tracks (deep links, 9A.6 / 9A.7 / 9A.8):** `/timeline?modules=<id>,<id>&nodes=<n>,<n>&q=<text>&order=arrival`. `modules` takes module ids (`clocksync`, `cluster`, `scenario`, ...), `nodes` takes node ids (0 = cluster), `q` is a case-insensitive text search over type, message and sentence, and `order=arrival` selects the bus-sequence order (the default is Lamport order). Unknown values are ignored. Build links from literals only (routes.jsx security rule).

**Scenario events (assumed shape, for Swanand, 9A.6):** the engine does not exist yet. The page reads, when present: `data.runId` (pairs STARTED with FINISHED), a scenario name from `scenarioTitle` / `title` / `scenarioName` / `scenarioId`, a step from `stepIndex` / `step` and `stepTitle` / `title` / `stepName` / `stepId`, and an outcome from `outcome` / `result` / `status`. Anything missing falls back to the event's message.

**Known issues:**
- No real LEADER_CHANGED -> role-change capture with TIME_DAEMON_CHANGED, COORDINATOR_CHANGED or MPI_ROOT_CHANGED exists in the fixtures; the chain is tested on hand-built events with the 9A.4 payload shapes. The sentences are also checked against two real logs (`election/events-cluster.json`, `faulttolerance/events.json`).
- The lanes use rank positions, so the gap between two markers means nothing about elapsed time.
- 2000 events in the list render without virtualisation; fine for the demo, not measured beyond that.
- Main chunk 859.75 kB in this Windows build. The 834.05 kB 9A.4 figure predates the 9A.2 and 9A.3 merges, so not all of the growth is this step's; no pre-change build was run.

**Gaps in other tracks:**
- Jai (9A.7): `frontend/src/pages/ScenariosPage.jsx:11` still says "Arrives in Phase 9A (Experiments 2–8); extended in Phase 13" (rule: nothing may say "Phase 13").
- Swanand (9A.6): please confirm or adjust the scenario payload fields above; until then, bands and step sentences rely on the fallbacks.

**Smoke check (running app, /timeline):**
1. Open /timeline after a reset: the cluster and node lanes show, with the CLUSTER_RESET event in the list. A legend sits under the lanes.
2. Run a Berkeley round on Experiment 3 and a job on Experiment 7, then crash the leader on Cluster: within about 10 s, "Node N became leader" appears, followed by the time daemon and coordinator changes for the same node. The leader-chain line names them, and they are ringed on the lanes.
3. Switch to "Arrival order (bus sequence)": the URL gains `?order=arrival` and the role changes sit after LEADER_CHANGED in the list.
4. Click the "Exp 3: Clock Synchronization" and "Node 4" chips: only matching events stay and the URL shows `modules=clocksync&nodes=4`. Reload the page: the same filters still apply.
5. Click Pause live and recover a node: the list doesn't change and the button reads "Resume live (n waiting)". Resume: the events appear. Click Clear view: "View cleared", and the next event appears alone.

### 9A.9a (fast mode)

**What changed:** three new PowerShell 5.1 scripts in `scripts/` (pure ASCII, no modules to install, no `$ErrorActionPreference` at script level; failures print one red line and exit 1). No backend or frontend change.

**Scripts** (from the repository root; add `powershell -ExecutionPolicy Bypass -File` in front if script execution is blocked):
- `scripts\start-all.ps1 [-NoBrowser]`: checks Java 21 (`JAVA_HOME\bin\java.exe` when JAVA_HOME is set, because mvnw uses it; else `java`), Node 22, `frontend\node_modules`, and that ports 8080 and 5173 are free (names the owning process if not). Starts `backend\mvnw.cmd spring-boot:run` and `npm run dev` hidden, each through `cmd.exe /c` with stdout and stderr in `%TEMP%\udcf-demo\backend.log` / `frontend.log`. Saves the PIDs with their start times to `%TEMP%\udcf-demo\pids.json` before waiting. Waits up to 120 s for `GET /api/system/health` = UP, then up to 120 s for a listener on 5173, printing a dot every 2 s and the last 15 log lines on failure. Opens http://localhost:5173 and prints a summary with the log paths.
- `scripts\stop-all.ps1`: runs `taskkill /PID <pid> /T /F` on the saved PIDs, but only if the start time still matches (a reused PID is left alone). Then stops anything still listening on 8080/5173 (`Get-NetTCPConnection -State Listen`). Deletes pids.json and prints what it stopped, or "Nothing to stop". Safe to run more than once.
- `scripts\reset-cluster.ps1 [-Api http://localhost:8080]`: checks health, then waits up to 60 s until no module is BUSY (the parenthesised `$busy` line from Known issues 9A.4), printing the busy ids when they change. Then posts `POST /api/cluster/reset`, retrying on 409 up to 40 tries, 500 ms apart. Prints "Cluster reset: HTTP 204 on try n of 40."

**Checked here (Windows PowerShell 5.1.26100):** all three files parse without errors and contain 0 non-ASCII bytes. The Java and Node detection lines match on this machine (`openjdk version "21.0.12.1"`, `v22.11.0`). With a mocked `/api/modules`, the busy list returns 0, 1 and 2 ids. `reset-cluster.ps1 -Api http://127.0.0.1:1` prints the "not reachable" line and exits 1. `stop-all.ps1` with nothing running prints "Nothing to stop" and exits 0. Not run here: start-all end to end and stop-all against running servers (Rohan's smoke check below).

**Interfaces for other tracks:** the scripts are the demo entry points for `docs/demo/udcf-demo.md` (rest of 9A.9). Scenarios (9A.6/9A.7) can call `scripts\reset-cluster.ps1` before a run.

**Known issues:**
- Ports 8080/5173 are constants in the scripts that mirror `application.yml` (`${PORT:8080}`) and `vite.config.js`. If either changes, or `PORT` is set in the environment, update the scripts too.
- stop-all stops anything listening on 8080/5173, including a server that start-all did not start. The addendum asks for this; the script prints its name.
- The first `spring-boot:run` on a fresh machine can download Maven and dependencies for longer than 120 s. start-all then fails with the log tail; run stop-all, then start-all again.
- The backend and frontend logs are overwritten on every start.

**Gaps in other tracks:** none found. Note for Rohan (owner): CLAUDE.md "Layout" and docs/HANDOFF.md list `scripts/` as `start-all.ps1, stop-all.ps1`; `reset-cluster.ps1` is not listed there.

**Smoke check (Windows PowerShell 5.1, repository root):**
1. `.\scripts\start-all.ps1`: "Java 21 OK", "Node 22 OK", dots, then "the backend ... ready" and "the frontend ... ready". The browser opens http://localhost:5173 and the summary names both log files in `%TEMP%\udcf-demo`.
2. In the browser, Overview and Cluster show five nodes up. A second `.\scripts\start-all.ps1` stops at once with "port 8080 is already in use by java (PID n)".
3. On Cluster, crash node 5, recover it, and run `.\scripts\reset-cluster.ps1` immediately: "Waiting: busy module(s): election" (or a 409 retry line) appears, then "Cluster reset: HTTP 204". The UI shows five nodes up after the reset.
4. `.\scripts\stop-all.ps1`: "Stopped backend ..." and "Stopped frontend ..."; the page shows the backend as unreachable. Run it again: "Nothing to stop: the UDCF demo is not running."
5. `.\scripts\start-all.ps1 -NoBrowser`: starts cleanly again without opening a browser. Then `.\scripts\stop-all.ps1`.

**Smoke check result (2026-10-19, Rohan):** 5/5 passed, run after merging main (9A.6–9A.8 included).

### 9A.9b (fast mode)

**What changed:** documentation only; no code, test, fixture or script change.
- New `docs/demo/udcf-demo.md`: summary; T-30 to T-0 checklist with a warm-up (Exp 3 round, Exp 7 run, Exp 9 collective, Start experiment 8), because after a reset nobody holds a role until a module asks for the leader; a run order in nine parts (Overview, crash node 5 on Cluster, Timeline chain in arrival order, the five scenarios, back to Overview); the 15-minute budget with the cut order; recovery table; Q&A crib; the three-command card; known limitations. Scenario titles and step counts come from `com.udcf.scenario.scenarios` (6, 7, 3, 7, 5); button and banner texts from the pages.
- CLAUDE.md: Layout lists `reset-cluster.ps1`; Current Position says Phase 9A is complete for the 2026-10-23 demo, the public lite redeploy moves to Phase 16, and the next phase is 14.
- docs/HANDOFF.md (section 11 table, Appendix A layout) and docs/tracks/README.md section 11: the 9A table gets a Status column (it had none), 9A.0–9A.9 complete.

**Known limitations:**
- Every part duration in the demo file is an estimate: the dry run timed only `start-all` (backend 6 s, frontend 0 s).
- The tag `demo-2026-10-23` and the backup laptop are named as the fallback; neither was checked here.
- The About page still says "Arrives in Phase 14" (9A.8 gap for Rohan); the demo file says not to open it.

**Gaps in other tracks:** none new.

**Smoke check (repository root, running app):**
1. Read `docs/demo/udcf-demo.md` from start to finish: it reads as one script with sections a–g and Known limitations.
2. Follow section b exactly on this laptop: every command works as written, and at T-15 Overview shows "leader: node 5" with every role "node 5 matches".
3. Pick any three rows of section c: the clicks and expected results match the live pages.
4. The Scenarios page lists the five titles with 6, 7, 3, 7 and 5 steps, as in section c.
5. The commands in sections e and g work as written (stop-all, start-all, reset-cluster).

**Smoke check result (2026-10-19, Rohan):** 5/5 passed.

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
- 2026-10-09 E7d done: MapReduce page at /experiments/7-mapreduce on the E2d kit: job and input pickers from the overview, .txt upload checked against the cap and UTF-8 in the browser and sent as JSON/Base64, crash-a-worker control (the stage hit is shown, not chosen), node-graph pipeline with per-stage counts and timings and drawn retries, metric strip, results table and SVG chart with truncation note, every task attempt, run history, event-log download (L5), down-node Recover with a link to the Cluster page, callouts from the real latest run with the proven-only identical-result rule; polling only while RUNNING; 138 new tests in 14 files, contract test on all 31 E7c fixtures; frontend 676 tests in 85 files (3 consecutive runs: 676/676/676, baseline 537), lint exit 0, build green, main chunk 601.68 kB -> 646.27 kB; backend unchanged (1162 tests at E7c, not re-run: no backend file changed); deviations: chart hand-drawn in SVG (Recharts is not installed; approved); results, task table and download inside Measurements (approved); approved shared add-only edits: lab 7 concept line in experiments.js, routes.test.jsx (two imports, three mocks, one test); the cluster-reset 409 is covered by a test that renders ClusterPage with a mocked 409 (ClusterPage not edited); motion.js and actionErrorFrom added beyond the Stage A file list
  Linux (Docker node:22.11, clean clone): lint, build and 676 frontend tests green; Windows: lint, build and 676 tests green; manual walkthrough against the real backend (21 checks: 3 jobs x 3 inputs, crash-and-retry with identical result, event-log crash run without an identical claim, oversize/wrong-type upload refusal, no-live-worker alert, event log download) verified by Rohan.
- 2026-10-16 9A.0 done: Phase 13 merged into Phase 9A (R19, HANDOFF v1.4); Phase 9A steps 9A.0–9A.9 in README section 11; CLAUDE.md Current Position updated.
- 2026-10-17 9A.4 done: the Berkeley time daemon (Experiment 3) and the MapReduce coordinator (Experiment 7) are the leader elected by Experiment 4 (`RoleProvider.ensureLeader()` on the request thread, 409 `NO_LEADER` when none; reads use `leaderId()` and never elect); both module-local selectors and every `TODO(L1)` removed; `TIME_DAEMON_CHANGED` / `COORDINATOR_CHANGED` published exactly once per new leader (CAS plus a short lock, after `LEADER_CHANGED`, before the next STARTED); daemon crash mid-round ends as `BERKELEY_ROUND_ABORTED` with `abortedRound` on the overview and a calm page notice; coordinator crash mid-run ends FAILED with a plain reason; served notes and page texts name the elected leader; 409 "No leader" shown as a calm warning; 30 fixtures recaptured by Rohan (full Experiment 3 sequence + new 409, 15 role-bearing Experiment 7 files) and two labelled synthetic fixtures; backend 1925 tests (0 failures, 1 skipped; baseline 1912 - 6 + 19; the 10 new and changed clocksync/mapreduce classes 3 runs, 108 tests each, all green; both packages after the recapture 272 tests, all green), frontend 1250 tests in 128 files (baseline 1244 + 6), lint and build exit 0; Windows only, Linux and the manual checklist not yet run; deviations: baseline 1915 corrected to 1912 (stale surefire report), `abortedRound` with `BerkeleyRoundAbortedException` and `AbortedRoundDto` (R4), the lock beyond R2, extra tests (+6 / +5), synthetic MapReduce 409 fixture, capture block amended with Reset-Cluster.
- 2026-10-18 9A.4 Linux (Docker), run by Rohan on a clean clone of 37f87ae: backend with Maven 3.9.9 / Java 21.0.7 (maven:3.9.9-eclipse-temurin-21, --cpus=2) full suite 1925 tests, 0 failures, 1 skipped, BUILD SUCCESS, and the ten clocksync/mapreduce classes x3 (108 tests each, 0 failures); frontend on node:22: npm ci, vitest (128 files, 1250 tests), lint and build exit 0, main chunk 834.05 kB. Manual checklist run the same day: 11 of 12 PASS, step 10 differs (not a defect).
- 2026-10-18 9A.5 done: Timeline page /timeline: all modules' events from GET /api/events?limit=500 plus /topic/events, deduplicated by sequence, capped at 2000; Lamport order with bus-sequence tie-break or arrival order; cluster lane plus node lanes in SVG with the event list and details; readable sentences for the role-change, crash/recover and scenario types; leader-chain highlight; scenario bands; module/node/text filters in the URL; pause, clear view, disconnected banner; no backend change; frontend 1294 tests in 131 files (baseline 1262 in 128, +32), lint clean, build OK; fast mode: no Docker run; CI is the Linux gate
- 2026-10-19 9A.9a done: demo scripts scripts/start-all.ps1 (Java 21 / Node 22 / free-port checks, hidden backend and frontend with logs in %TEMP%\udcf-demo, PIDs with start times in pids.json, 120 s readiness waits, browser unless -NoBrowser), scripts/stop-all.ps1 (taskkill /T /F on the saved PIDs when the start time matches, then any listener on 8080/5173; idempotent) and scripts/reset-cluster.ps1 (waits for no BUSY module, POST reset with up to 40 tries on 409); checked here: PS 5.1 parse clean, ASCII only, version detection, mocked busy list, unreachable-API and nothing-running paths; start-all end to end not run here (Rohan's smoke check); no backend or frontend change, so no suites run; fast mode: no Docker run; CI is the Linux gate
- 2026-10-19 9A.9b done: demo script docs/demo/udcf-demo.md (summary, T-30 checklist with role warm-up, nine-part run order with the five scenarios and their real step counts 6/7/3/7/5, 15-minute budget and cut order, recovery table, Q&A crib, three-command card, known limitations); CLAUDE.md Current Position (Phase 9A complete for the 2026-10-23 demo, public lite redeploy to Phase 16, next phase 14) and Layout (reset-cluster.ps1); Status column in the 9A tables of README section 11 and HANDOFF section 11; HANDOFF Appendix A layout; documentation only, no suites run; fast mode: no Docker run; CI is the Linux gate
