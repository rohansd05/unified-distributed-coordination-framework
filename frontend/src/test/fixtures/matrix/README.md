# Matrix contract fixtures (E10c)

Real response bodies from the running backend (`cd backend; ./mvnw spring-boot:run`, local profile, five
nodes, RMI registries on ports 1101 to 1105, request listeners on 7201 to 7205), captured on 2026-10-16 by one
Python 3 standard-library script run with `python -I` outside the repository. The script made every kept
request with `curl -s -o <file>`, held the blocker socket itself, and made the 409 busy request in-process (see
below). Nothing here was edited by hand. Steps marked "not kept" were run, but their responses were not saved.
Every run used seed 42 (except the busy holder, seed 7, not kept).

## One backend, default settings, in capture order

| File | Request | Status |
|---|---|---|
| `overview-before-start.json` | `GET /api/modules/matrix` (fresh backend: every node `NOT_STARTED`, `keptRunIds` and `storedCollectiveIds` `null`, status `IDLE`) | 200 |
| `error-404-no-run-yet.json` | `GET .../runs/latest` (`runId: null`) | 404 |
| `series-before-runs.json` | `GET .../series`: no points, the four sizes as gaps with `newestRunId: null`, `done: false`, `basis: null` | 200 |
| (not kept) | A socket bound to 127.0.0.1:1105, node 5's registry port, that never listens (Python `socket.bind` only, no socket option set), before any RMI start | |
| `start-with-failure.json` | `POST .../start`: nodes 1 to 4 `EXPORTED`; node 5 `UP` but `NOT_STARTED` with the real `startFailure` ("Port already in use: 1105"); node 5's request listener runs anyway (the prepare step only needs the node up) | 200 |
| `run-partial.json` | `{"size":100,"seed":42}`: **PARTIAL**. Node 5 is live, so it is a rank; its scatter transfer `FAILED` at `LOOKUP`, `CONNECTION_REFUSED` (11152800 ns to failure); its compute and fetch `NOT_ATTEMPTED`; `blocksMissing: 1`, `passed: null`; speedup withheld with its reason | 200 |
| (not kept) | `POST /api/cluster/nodes/{2,3,4}/crash` | 200 |
| `run-failed.json` | `{"size":100,"seed":42}` with live nodes 1 and 5 only: **FAILED** (the one remote rank, node 5, never got its input, so no remote block arrived); `skippedNodeIds: [2, 3, 4]` | 200 |
| (not kept) | `POST /api/cluster/nodes/{2,3,4}/recover`; the socket on 1105 closed | 200 |
| `start.json` | `POST .../start` (all five `EXPORTED`; status `RUNNING`) | 200 |
| `overview-after-start.json` | `GET /api/modules/matrix`: every node exports MatrixEndpoint, `keptRunIds: []`, `requestListenerRunning: true`; `processors` as the JVM reported them (16 on this machine) | 200 |
| `run-complete-50.json`, `-100`, `-200`, `-316` | `{"size":N,"seed":42}`: COMPLETE over five ranks, `passed: true`, `mismatchedElements: 0`, parallel checksum equal to the sequential one; a measured speedup for each | 200 |
| `series.json` | `GET .../series` right after those four: four points (run ids 3 to 6, five ranks each), no gap, `done: true` | 200 |
| `run-requested-root.json` | `{"size":100,"seed":42,"rootNode":3}`: `rootSource: REQUESTED`, root rank 2 | 200 |
| (not kept) | `POST /api/cluster/nodes/3/crash` | 200 |
| `overview-node-crashed.json` | node 3 `CRASHED` with no listener; `minimumSizeNow` and `speedupCeiling` 4 | 200 |
| `run-node-crashed-skipped.json` | `{"size":100,"seed":42}`: COMPLETE over the four live nodes, `skippedNodeIds: [3]` | 200 |
| `error-409-root-down.json` | `{"size":100,"rootNode":3}`: "Node down", `nodeId: 3` | 409 |
| `run-refused.json` | `{"size":3,"seed":42}` with 4 live nodes: **REFUSED**, `SIZE_BELOW_PARTICIPANTS`, no ranks, phases, timing or verification | 200 |
| (not kept) | `POST /api/cluster/nodes/{1,2,4,5}/crash` | 200 |
| `error-409-no-live-node.json` | `{"size":100}` with every node crashed: "Node state conflict", `nodeId: 0` | 409 |
| (not kept) | `POST /api/cluster/nodes/{1..5}/recover` | 200 |
| `error-400-size-above-max.json` | `{"size":317}`: "size must be 2-316, was 317" | 400 |
| `error-400-size-below-min.json` | `{"size":1}`: "size must be 2-316, was 1" | 400 |
| `error-400-seed.json` | `{"size":50,"seed":-1}`: "seed must be 0-9007199254740991, was -1" | 400 |
| `error-400-missing-fields.json` | `{}`: `errors` has `size` | 400 |
| `error-400-unreadable.json` | `{"size":"abc"}`: Spring's default 400, **no `errors`** | 400 |
| `error-400-bad-id.json` | `GET .../runs/abc`: Spring's default 400, **no `errors`** | 400 |
| `error-404-unknown-node.json` | `{"size":50,"rootNode":9}`: "Unknown node", `nodeId: 9` | 404 |
| `error-404-unknown-run.json` | `GET .../runs/999`: "Unknown run", `runId: 999` | 404 |
| `series-after-more-runs.json` | `GET .../series` at this point: size 100's point now comes from run 8 (four ranks, node 3 skipped), so `ranksDiffer: true` with `ranksNote` | 200 |
| `runs-history.json` | `GET .../runs` (newest first: ids 9 to 1) | 200 |
| `run-latest.json` | `GET .../runs/latest` (id 9, the REFUSED run) | 200 |
| `run-by-id.json` | `GET .../runs/3` (the COMPLETE size-50 run) | 200 |
| `events.json` | `GET /api/events?module=matrix&limit=200`: 5 `MATRIX_ENDPOINT_EXPORTED`, 9 `MATRIX_RUN_STARTED`, 8 `MATRIX_RUN_FINISHED`, 1 `MATRIX_RUN_REFUSED` | 200 |
| `events-export.txt` | `GET /api/events/export?module=matrix&limit=40` (23 lines; no `latency=`: matrix events carry no `latencyMillis`) | 200 |
| `error-409-busy.json` | captured separately, see below | 409 |
| (not kept) | `POST /api/cluster/reset` | 204 |

**`error-409-busy.json`.** One Python process: a thread posted `{"size":316,"seed":7}`, the main thread polled
`GET /api/modules/matrix` until it said `BUSY` (1 poll), then posted `{"size":50}`, which got this 409 on the
first attempt while the size-316 run held the module. The holding run is not kept, and the rejection's
`ACTION_REJECTED_BUSY` event was not captured (`events.json` was taken before).

The backend was then stopped. No listener was left on 8080, 5173, 1101 to 1105 or 7201 to 7205, no UDP socket on
6001 to 6005, and no capture process was left running.

## Values that are not stable

These differ on every capture. Tests should check their type and shape, not their value.

- Every measured figure: every `...Nanos` field, `communicationShare`, `speedup`, `computeBoundSpeedup`,
  `otherNanos` (which can be negative).
- `processors` (what this JVM reported) and so `speedupCeiling` and the basis texts that quote it.
- Times: `startedAt`, event `wallTime`, and the export's leading timestamp.
- Lamport values and event `sequence`.
- `keptRunIds` and `storedCollectiveIds` (each node keeps 4 blocks of C and 8 collective payloads).
- `startFailure` and `failureMessage` text that quotes the operating system (this capture is Windows).

## Stable by design

- Field names and types, and `null` (never 0, never `[]`) for everything not measured or not applicable.
- Enum values: `status`, `rmiState`, `nodeStatus`, `rootSource`, `outcome`, `operation`, compute and transfer
  `status`, `failureStage`, `failureKind`, `nodeReason`, `refusal.reason`, glossary `kind`.
- The texts: every basis and note in the overview, the glossary, `coHosting.reason`, `speedupWithheldReason`,
  `refusal.message`, the series `rule` and `ranksNote`.
- The limits under the local profile: sizes 2 to 316, series [50, 100, 200, 316], 20 kept runs, seed at most
  9007199254740991, warm-up 1, compute timeout 2000 ms, run deadline 10000 ms, connect 1000 ms, call 3000 ms,
  `transferWorstMillis` 12000, `worstCaseRunMillis` and `recommendedMinClientTimeoutMillis` 26001 (bounds derived
  from configured timeouts, not measurements).
- With the same size and seed: every checksum (`inputChecksum`, `expectedChecksum`, `reportedChecksum`, compute
  `checksum`, `parallelChecksum`, `sequentialChecksum`) and every `requestBytes` and `replyBytes`.
- The error `title`s, and the `errors` keys of the module's own 400s.

## States that cannot be produced for real (build them as labelled synthetic copies)

1. A checksum mismatch: a block whose bits differ (`blocksWithBadChecksum` or `mismatchedElements` above 0,
   `passed: false`, speedup withheld with "did not match the sequential product").
2. A hole from an evicted block: `nodeReason` `INPUT_MISSING` (stored A or B evicted) or `BLOCK_MISSING` (kept C
   evicted).
3. The two timeouts: the node's own `COMPUTE_TIMED_OUT` (`failureKind` `METHOD_THREW`) and the root's wait bound
   (`failureKind` `TIMED_OUT`, no `nodeReason`).
4. Steps `NOT_ATTEMPTED` because the run's deadline had passed.
5. A leader as the default root (`rootSource: LEADER`, `leader: true`): no election runs in this capture.
