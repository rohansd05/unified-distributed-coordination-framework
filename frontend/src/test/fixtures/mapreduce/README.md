# MapReduce Contract Fixtures (E7c, role-bearing files recaptured in 9A.4)

Real response bodies from the running backend (`cd backend; .\mvnw.cmd spring-boot:run`,
local profile, five nodes), captured on 2026-10-09 with `curl.exe -o <file>`. On 2026-10-17 (9A.4)
every file that carries the coordinator, the notes or the cluster roles was recaptured, because the
coordinator became the leader elected by Experiment 4 (see "9A.4 recapture" below). Nothing here was
edited by hand; the one labelled synthetic file lives in `synthetic/` (see its README). Captured in
this order (the uploads were built from text files and Base64-encoded into the JSON request body,
as the page will do):

| File | Request | Status | Description |
|---|---|---|---|
| `overview-idle.json` | `GET /api/modules/mapreduce` | 200 | Fresh backend (9A.4): status, three jobs, three input types, `coordinatorId: null` (no leader elected yet; reading never starts an election), workers 1-5, limits, `latestRun: null`, notes (including "The coordinator is the leader elected by Experiment 4 ...") |
| `error-404-no-run-yet.json` | `GET /api/modules/mapreduce/runs/latest` | 404 | No run has been started yet (`runId: null`) |
| `run-accepted.json` | `POST /api/modules/mapreduce/runs` (word-count, SAMPLE) | 202 | The run as RUNNING, `coordinatorId: 5` (this POST asked Experiment 4 for the leader; Bully elected the highest live node): `report`, `finishedAt`, `error`, `notice` and `crash` are `null` |
| `run-sample-word-count.json` | `GET /api/modules/mapreduce/runs/{id}` | 200 | Completed word count on the bundled sample (job: word-count; input: SAMPLE) |
| `run-upload-avg-latency.json` | `GET /api/modules/mapreduce/runs/{id}` | 200 | Completed average latency per node on an uploaded log, `framework-events.txt` (job: avg-latency-per-node; input: UPLOAD); rows carry `count` and `averageMillis` |
| `run-event-log-event-category.json` | `GET /api/modules/mapreduce/runs/{id}` | 200 | Completed events per category on the live event log (job: event-category-count; input: EVENT_LOG); `inputLinesDropped: 0`; 17 categories in 9A.4, because the log now also holds the election events |
| `run-truncated-result.json` | `GET /api/modules/mapreduce/runs/{id}` | 200 | Word count on an upload with 300 distinct words: 200 rows listed, `resultsTruncated: true`, `resultKeys: 300` |
| `run-crash-retry.json` | `GET /api/modules/mapreduce/runs/{id}` | 200 | Word count on the sample with `crashWorkerId: 3` (coordinator 5): node 3 crashed right after its first map task was sent; two tasks show a failed attempt on node 3 and a retry; same 116 keys as `run-sample-word-count.json` |
| `cluster-after-crash.json` | `GET /api/cluster` | 200 | Node 3 stays crashed after the crash run (it was then recovered for the next captures); node 5 holds `LEADER` |
| `runs-history.json` | `GET /api/modules/mapreduce/runs` | 200 | Run summaries, newest first |
| `run-latest.json` | `GET /api/modules/mapreduce/runs/latest` | 200 | The latest run at that moment (the crash run) |
| `overview-after-runs.json` | `GET /api/modules/mapreduce` | 200 | Overview with `latestRun` filled in and status RUNNING (workers listening) |
| `events-mapreduce.json` | `GET /api/events?module=mapreduce&limit=50` | 200 | The last 50 JOB_*, TASK_* and WORKER_CRASH_TRIGGERED events (the crash run's `JOB_STARTED` on node 5 among them); no `COORDINATOR_CHANGED`, because the leader did not change during the sequence |
| `events-export.txt` | `GET /api/events/export?module=mapreduce&limit=25` | 200 | The event log in the MapReduce line format (link L5), `text/plain; charset=UTF-8`: the last 25 events, the tail of the crash run (from its map task completions to `JOB_COMPLETED`); its `JOB_STARTED` is older than that window. Coordinator lines are `node=5`, worker lines carry `peer=5` |
| `error-409-busy.json` | `POST /api/modules/mapreduce/runs` while a 1 MiB upload run was active | 409 | Module busy, with `actionInProgress` |
| `error-404-unknown-run.json` | `GET /api/modules/mapreduce/runs/00000000` | 404 | Unknown run id |
| `error-404-unknown-job.json` | `POST /api/modules/mapreduce/runs` (jobId `nope`) | 404 | Unknown job, with `jobId` |
| `error-400-missing-fields.json` | `POST /api/modules/mapreduce/runs` with `{}` | 400 | `errors.jobId` and `errors.inputType` |
| `error-400-upload-missing.json` | UPLOAD without `upload` | 400 | `errors.upload` |
| `error-400-upload-file-name.json` | upload named `notes.csv` | 400 | `errors["upload.fileName"]`: must be a .txt file |
| `error-400-upload-content-type.json` | upload with type `image/png` | 400 | `errors["upload.contentType"]` |
| `error-400-upload-not-base64.json` | content `not base64!` | 400 | `errors["upload.contentBase64"]`: not valid Base64 |
| `error-400-upload-empty.json` | empty content | 400 | `errors["upload.contentBase64"]`: the file is empty |
| `error-400-upload-too-large.json` | 1048577-byte file | 400 | `errors["upload.contentBase64"]`: larger than the 1048576-byte limit |
| `error-400-upload-not-utf8.json` | bytes `68 69 FF` | 400 | `errors["upload.contentBase64"]`: not valid UTF-8 text |
| `error-400-upload-nul.json` | bytes `68 00 69` | 400 | `errors["upload.contentBase64"]`: NUL character |
| `error-400-upload-control-character.json` | bytes `68 69 0A 07` | 400 | `errors["upload.contentBase64"]`: control character U+0007 on line 2 |
| `error-400-crash-worker.json` | SAMPLE with `crashWorkerId: 5` | 400 | `errors.crashWorkerId`: node 5 is the coordinator (the elected leader) |
| `error-413-body-too-large.json` | body of about 1.5 MB | 413 | Request body too large, with `limitBytes: 1406296` |
| `error-409-no-live-worker.json` | `POST /api/modules/mapreduce/runs` with every node crashed | 409 | No live worker |
| `run-event-log-empty-result.json` | `GET /api/modules/mapreduce/runs/{id}` | 200 | After `POST /api/cluster/reset`, average latency on the live event log: completed with no result keys and an honest notice; coordinator 5 again (the reset removed the leader, so this run asked for a new election) |

## 9A.4 recapture (2026-10-17)

Recaptured, in the order of the table, on a freshly started backend after a cluster reset and after
the Experiment 3 sequence (see `../clocksync/README.md`) and a second reset: `overview-idle`,
`run-accepted`, `run-sample-word-count`, `run-upload-avg-latency`, `run-event-log-event-category`,
`run-truncated-result`, `run-crash-retry`, `cluster-after-crash`, `runs-history`, `run-latest`,
`overview-after-runs`, `events-mapreduce`, `events-export.txt`, `error-400-crash-worker`, then a
third reset and `run-event-log-empty-result` (15 files). Every run has `coordinatorId: 5`. The
other 16 files carry no coordinator, notes or roles and are the 2026-10-09 captures.

- Capture procedure: the cluster resets went through a `Reset-Cluster` step that waits until
  `GET /api/modules` shows no BUSY module and retries on HTTP 409. `POST /api/cluster/reset`
  refuses while any module is busy, and right after node 3's recovery Experiment 4 holds an
  election round open for about a second (see Known issues in `docs/tracks/track-d-rohan.md`).
- Not capturable: the MapReduce 409 `NO_LEADER` (with every node down the earlier 409
  "No live worker" answers first; with any node live Experiment 4 elects it). Its body is in
  `synthetic/error-409-no-leader.json`, copied from `MapReduceControllerTest.noLeader`.

## Known limitation: the empty-event-log notice

The notice "No events have been recorded yet; use another page first." appears only when the
event log is completely empty. A running backend always has at least one event (startup records
`CLUSTER_STARTED`, and a reset records `CLUSTER_RESET`), so that case cannot be captured from a
real backend. `run-event-log-empty-result.json` is the closest real case (an event-log run that
finds nothing to report). The empty-log notice itself is covered by `RunInputLoaderTest`.

## Values that are not stable

These differ on every capture; frontend tests should check type and structure, not exact values:

- Ids: `runId`.
- Timestamps: `startedAt`, `finishedAt`, event `wallTime`, the export's leading timestamp.
- Measured quantities: every value under `timings`, `combinerSavingPercent` for event-log runs.
- Lamport values and sequence numbers in events and in the export.
- Which tasks are retried in a crash run, and the failure `reason` text (from the operating system).

## Stable by design

- DTO structure, JSON property names and types; `null` (never 0) for values not measured.
- Limits in the local profile: `uploadMaxBytes: 1048576`, `requestBodyMaxBytes: 1406296`,
  `resultRowsMax: 200`, `runHistorySize: 20`, `taskTimeoutMillis: 15000`.
- The three job ids and the three input types; result keys and counts for the sample text.
- Nothing in Experiment 7 is simulated, so no DTO carries a `simulated` flag.
