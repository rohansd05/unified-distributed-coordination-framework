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
- [ ] E7b — MapReduce workers on `ports().mapreduce()` (730k) with task retry. Needs: E7a.
- [ ] E7c — `MapReduceModule` (lab 7) and its API; inputs: bundled sample, uploaded .txt (size-capped), live event log; build `EventLogExporter` and `GET /api/events/export` (link L5). Needs: E7b.
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
- **MapReduce Pipeline & Transport API (E7a -> E7b for Jai):**
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

---

## Known issues

- `LatencyPerNodeJob.formatResult` returns "No latency measured" when there is no data or event count is zero (Rule R7: never presents an unmeasured average as a number).

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
- 2026-10-09 E7a done: jobs, log parser, registry, report, and the pure MapReduce pipeline (split, map, combine, shuffle, partition, reduce, retry, timeout) implemented as pure classes; backend 912 tests (3 consecutive runs: 912/912/912), frontend 453 tests; deviations: none
  Linux (Docker, 2 CPUs): 915 tests, 0 failures, 1 skipped.
  Test resource framework-events.log was excluded by .gitignore; fixed with a narrow exception.


