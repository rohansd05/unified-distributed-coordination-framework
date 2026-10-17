# Fault tolerance contract fixtures (E8c)

Real response bodies from the running backend (`cd backend; .\mvnw.cmd spring-boot:run`, local
profile, five nodes, election ports 7001 to 7005, replication ports 7101 to 7105, shared detector
700 ms / 2500 ms, simulated asynchronous delay 450 ms), captured on 2026-10-15 with
`curl.exe -s -o <file>`, by one PowerShell script run against a freshly started backend. Request
bodies were written to a file and sent with `-H "Content-Type: application/json" --data-binary "@<body>.json"`.
Waits were polling loops on `GET .../status` or `GET .../runs/latest` with a deadline, never
fixed waits. Nothing here was edited by hand. Steps marked "not kept" were run but their
responses were not saved.

An earlier capture of the same script (same backend version, before two fixes) was discarded and
every file was captured again: the derived "expected detection window" glossary text was wrong,
and a crashed node reported `rejoin: "REJOINING"` (now `null`).

**Phase 9A.1:** the four `overview-*.json` files were captured again on 2026-10-17 (see "9A.1
recapture" below). Every other file here is still the E8c capture of 2026-10-15, made before 9A.1:
there the first primary is node 1 (the lowest live node), a failover goes from node 1 to node 2,
and the recovered node 1 stays a backup.

| File | Request | Status | Shows |
|---|---|---|---|
| `overview-before-start.json` | 9A.1 recapture (below) | 200 | see "9A.1 recapture" |
| `error-404-no-run-yet.json` | `GET .../runs/latest` | 404 | `runId: null` |
| `error-409-no-serving-primary.json` | `POST .../crash-primary` | 409 | `reason: NO_SERVING_PRIMARY`, not started |
| `error-409-nothing-to-recover.json` | `POST .../recover` | 409 | `reason: NOTHING_TO_RECOVER` |
| `error-409-stream-not-running.json` | `POST .../stream/stop` | 409 | `reason: STREAM_NOT_RUNNING` |
| `error-400-validation.json` | `POST .../stream/start` `{}` | 400 | `errors.mode`, `errors.ratePerSecond` |
| `error-400-rate-too-high.json` | `POST .../stream/start` `{"mode":"SYNCHRONOUS","ratePerSecond":11}` | 400 | `errors.ratePerSecond: "must be between 1 and 10"` |
| `error-400-unreadable.json` | `POST .../stream/start` `{"mode":"EVENTUAL","ratePerSecond":4}` | 400 | unknown model: "Failed to read request" |
| `error-400-mode-missing.json` | `PUT .../mode` `{}` | 400 | `errors.mode` |
| `error-400-bad-run-id.json` | `GET .../runs/abc` | 400 | non-numeric run id |
| `start.json` | `POST .../start` | 202 | `outcome: STARTED` |
| `start-again.json` | `POST .../start` | 200 | `outcome: ALREADY_STARTED` with a reason |
| `overview-after-start.json` | 9A.1 recapture (below) | 200 | see "9A.1 recapture" |
| `stream-start-sync.json` | `POST .../stream/start` `{"mode":"SYNCHRONOUS","ratePerSecond":4}` | 202 | stream RUNNING |
| `error-409-stream-running.json` | the same again | 409 | `reason: STREAM_RUNNING` |
| `status-stream-running.json` | `GET .../status`, once 10 updates were acknowledged | 200 | a stream running, live `dataLossNow` (lost 0) |
| `crash-primary-sync.json` | `POST .../crash-primary` | 202 | node 1 crashed at epoch 2, `crashSource: ACTION` |
| `status-during-failover.json` | `GET .../status` straight after | 200 | no primary, `dataLossNow: null`, node 1 CRASHED with role, epoch and rejoin `null` |
| `error-409-failover-in-progress.json` | `POST .../crash-primary` straight after | 409 | "A failover is in progress ..." |
| `run-sync-restored.json` | `GET .../runs/latest`, polled until `dataLoss` was set | 200 | SYNCHRONOUS failover 1 to 2 at epoch 3: `lost: 0` of 11, milestones CRASHED to SERVICE_RESTORED, `recoveryMillis: null` |
| `recover.json` | `POST .../recover` | 202 | node 1, `previousEpoch: 2` |
| `run-sync-recovered.json` | `GET .../runs/latest`, polled until `recoveryMillis` was set | 200 | the demotion: milestones OLD_PRIMARY_RECOVERED, DEMOTED, RESYNCHRONISED; `recoveryMillis` |
| `status-after-demotion.json` | `GET .../status` | 200 | node 1 BACKUP at epoch 3, node 2 PRIMARY at epoch 3 |
| `mode-async.json` | `PUT .../mode` `{"mode":"ASYNCHRONOUS"}` (stream running) | 200 | the next updates are asynchronous |
| `stream-stop.json` | `POST .../stream/stop` | 200 | `stopReason: REQUESTED` |
| `runs-history.json` | `GET .../runs` | 200 | the kept runs, newest first |
| `run-by-id.json` | `GET .../runs/1` | 200 | run 1 |
| `error-404-unknown-run.json` | `GET .../runs/999` | 404 | `runId: 999` |
| `overview-after-runs.json` | 9A.1 recapture (below) | 200 | see "9A.1 recapture" |
| `events.json` | `GET /api/events?module=faulttolerance&limit=200` | 200 | the synchronous scenario's events (E8b's and E8c's) |
| `events-export.txt` | `GET /api/events/export?module=faulttolerance&limit=50` | 200 | the same events in the MapReduce line format (link L5) |
| `error-409-busy.json` | `POST .../crash-primary` and `POST .../start` sent in parallel (`curl.exe -Z --parallel-immediate`), attempt 1 | 409 | the crash found the start holding the guard: "Module busy", `actionInProgress` |
| (not kept) | `POST .../recover` (the crash had not run, so nothing to recover) | 409 | |
| (not kept) | `POST .../stream/start` `{"mode":"SYNCHRONOUS","ratePerSecond":10}` | 202 | |
| `status-limit-reached.json` | `GET .../status`, polled until the stream stopped by itself | 200 | `stopReason: LIMIT_REACHED`, `remainingUpdates: 0`, `maxUpdates: 400` |
| `error-409-limit-reached.json` | `POST .../stream/start` | 409 | `reason: UPDATE_LIMIT_REACHED`, "A cluster reset gives a fresh budget." |
| (not kept) | `POST /api/cluster/reset` | 204 | asynchronous attempt 1 of at most 3 |
| `stream-start-async.json` | `POST .../stream/start` `{"mode":"ASYNCHRONOUS","ratePerSecond":10}` (starts Experiment 8 again after the reset) | 202 | stream RUNNING, asynchronous |
| `crash-primary-async.json` | `POST .../crash-primary`, once 3 updates were acknowledged | 202 | node 1 crashed |
| `run-async-restored.json` | `GET .../runs/latest`, polled until `dataLoss` was set | 200 | ASYNCHRONOUS failover: `lost: 4` of 5, all asynchronous, `simulated: true`, `simulatedDelayMillis: 450` |
| (not kept) | `POST .../stream/stop` | 200 | |
| `events-async.json` | `GET /api/events?module=faulttolerance&limit=200` | 200 | the asynchronous scenario's events (`DATA_LOSS_MEASURED` with `simulated: true`) |
| (not kept) | `POST /api/cluster/reset` | 204 | |
| `overview-after-reset.json` | 9A.1 recapture (below) | 200 | see "9A.1 recapture" |

The asynchronous scenario was set to be retried (cluster reset, then the whole scenario) up to 3
times until a real run showed `lost >= 1`; attempt 1 did. The busy attempt was set to be retried up
to 3 times; attempt 1 produced the 409.

Afterwards the backend was stopped and ports 8080, 5173 and 7001 to 7305 were checked free.

## States that cannot be produced for real here

Build these as labelled synthetic copies of the real files in E8d tests; never present them as captures:

- A failed no-two-primaries check (`splitBrain.passed: false`, `violations`, `violationsSeen > 0`):
  the protections exist precisely so that it never happens.
- Run outcome `INTERRUPTED` (a second failure before the service was restored) and
  `PRIMARY_RETURNED` (the old primary back before anyone was chosen). `PRIMARY_RETURNED` is
  produced for real in `FaultToleranceModuleTest.oldPrimaryReturnsBeforePromotion` (every other node
  down), but not captured here.
- `ROLE_QUERY_FAILED` events, and a stream stopped with `stopReason: ERROR`. (`NO_CANDIDATE` is no
  longer published since 9A.1: the elected leader is the only candidate. Its synthetic copy is kept
  only as a module event that triggers a refresh.)
- A stream `gaveUp > 0` (an update that outlasted the 12.5 s retry window).

## 9A.1 recapture (2026-10-17)

Captured with `curl.exe -s -o <file>` (raw bytes, UTF-8 without a BOM) by one PowerShell script, run
by Swanand against the running backend (local profile, five nodes, shared detector 700 ms / 2500 ms,
Bully ok 900 ms and coordinator 2200 ms, simulated asynchronous delay 450 ms). Waits were polling
loops with a deadline. Nothing was edited by hand. The script checked each file: no BOM,
`worstCaseFailoverMillis` 10300 and the new note 6. Steps:

1. `POST /api/cluster/reset` (204), then `GET /api/modules/faulttolerance` -> `overview-before-start.json`.
2. `POST .../start` (202); polled `GET .../status` until node 5 served and every node was READY;
   then the overview -> `overview-after-start.json`.
3. `POST .../stream/start` `{"mode":"SYNCHRONOUS","ratePerSecond":4}` (202); polled until 10 updates
   were acknowledged; `POST .../crash-primary` (202); polled `GET .../runs/latest` until `dataLoss`
   was set; `POST .../recover` (202); polled until `recoveryMillis` was set; polled until node 5 was
   primary at epoch 4; `POST .../stream/stop` (200); polled until no update was in flight; then the
   overview -> `overview-after-runs.json`.
4. `POST /api/cluster/reset` (204), then the overview -> `overview-after-reset.json`.

| File | Shows |
|---|---|
| `overview-before-start.json` | IDLE, not started, every node's role, epoch and rejoin `null`, `dataLossNow: null`, `latestRun: null`; settings with `worstCaseFailoverMillis: 10300`, `retryWindowMillis: 12500`; glossary (worst case: "Detection, one election and one promotion of the elected leader, ..."); notes (note 6: "The primary is the leader elected by Experiment 4 ...") |
| `overview-after-start.json` | RUNNING; node 5, the elected leader, PRIMARY at epoch 2; nodes 1 to 4 BACKUP at epoch 2, every node believing node 5 and READY; `dataLossNow` with `notAssessed: NOTHING_ACKNOWLEDGED` |
| `overview-after-runs.json` | `latestRun`: run 1, node 5 crashed (ACTION) at epoch 2, node 4 detected it, was elected and promoted at epoch 3, RESTORED, `lost: 0` of 11; all eight milestones, the old primary's recovery included (OLD_PRIMARY_RECOVERED, DEMOTED, RESYNCHRONISED). `current`: node 5 PRIMARY again at epoch 4 (the planned handover after its resync), nodes 1 to 4 BACKUP at epoch 4, 12 updates acknowledged, `dataLossNow` lost 0, stream STOPPED (`stopReason: REQUESTED`), the split-brain check passed |
| `overview-after-reset.json` | IDLE, not started, no run, fresh budget (400); the services still run, so every node is BACKUP at epoch 1 with `believedPrimaryId` null |

## Values that are not stable

These differ on every capture; tests should check their type and shape, not their value.

- Times: stream `startedAt`, `stoppedAt`; event `wallTime`; the export's leading timestamp.
- Every measured figure: `measurements.*`, milestone `offsetMillis`, event `data.*Millis`, `silentMillis`.
- How many updates were sent and acknowledged, `lost` and `lostKeys` in the asynchronous run, `remainingUpdates`.
- Lamport values, `sequence` and event order.
- Which node detected the crash (`detectedByNodeId`) and the role-query and resync details.

## Stable by design

- Node 1 is the first primary (epoch 2); after its crash node 2 takes over at epoch 3 (lowest live, ready node, `// TODO(L1)`).
- Synchronous runs show `lost: 0`; asynchronous losses are never synchronous (`lostSynchronous: 0`).
- The simulated delay shown is the configured `udcf.replication.async-delay-millis` (450 here).
