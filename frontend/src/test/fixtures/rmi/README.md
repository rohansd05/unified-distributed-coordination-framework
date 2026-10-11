# RMI contract fixtures (E1c)

Real response bodies from the running backend (`cd backend; .\mvnw.cmd spring-boot:run`, local
profile, five nodes, RMI registries on ports 1101 to 1105), captured on 2026-10-12 with
`curl.exe -s -o <file>`. Each request body was written to a file and sent with
`-H "Content-Type: application/json" --data-binary "@<body>.json"`. Nothing here was edited by
hand. The capture script ran outside the repository and was deleted afterwards. Steps marked
"not kept" were run, but their responses were not saved.

## Backend 1 (default settings), in capture order

| File | Request | Status |
|---|---|---|
| `overview-before-start.json` | `GET /api/modules/rmi` (fresh backend: every node `NOT_STARTED`, status `IDLE`) | 200 |
| `error-404-no-call-yet.json` | `GET .../calls/latest` (nothing called yet; `callId: null`) | 404 |
| `registries-before-start.json` | `GET .../registries` (no registry exists yet: every node unreachable, `CONNECTION_REFUSED`, `names: null`) | 200 |
| `start.json` | `POST .../start` (every node `EXPORTED`, status `RUNNING`) | 200 |
| `overview-after-start.json` | `GET /api/modules/rmi` | 200 |
| `registries-all-up.json` | `GET .../registries` (each node lists `NodeEndpoint` and its stub class) | 200 |
| `registry-node-2.json` | `GET .../registries/2` | 200 |
| `call-ping.json` | `POST .../calls` `{"fromNode":1,"targetNode":2,"method":"PING"}` | 200 |
| `call-node-info.json` | `POST .../calls` `{"fromNode":1,"targetNode":3,"method":"NODE_INFO"}` | 200 |
| `call-echo.json` | `POST .../calls` `{"fromNode":1,"targetNode":4,"method":"ECHO","text":"hello, RMI"}` | 200 |
| `call-compute-hash.json` | `POST .../calls` `{"fromNode":1,"targetNode":5,"method":"COMPUTE_HASH","rounds":100000}` | 200 |
| `call-self.json` | `POST .../calls` `{"fromNode":2,"targetNode":2,"method":"PING"}` (a node calling itself through its own registry) | 200 |
| `comparison-compute-hash.json` | `POST .../comparisons` `{"fromNode":1,"targetNode":2,"method":"COMPUTE_HASH","rounds":100000}`: `digestsMatch: true`; in this capture the overhead is **negative** and the ratio below 1 (single measurements, as the `note` says) | 200 |
| `comparison-ping.json` | `POST .../comparisons` `{"fromNode":1,"targetNode":3,"method":"PING"}`: `digestsMatch: null` | 200 |
| (not kept) | `POST /api/cluster/nodes/3/crash` (the cluster API, as the Cluster page does) | 200 |
| `overview-node-crashed.json` | `GET /api/modules/rmi` (node 3 `CRASHED`) | 200 |
| `registries-node-3-crashed.json` | `GET .../registries` (node 3: `reachable: false`, `names: null`, `CONNECTION_REFUSED`) | 200 |
| `call-crashed-target.json` | `POST .../calls` `{"fromNode":1,"targetNode":3,"method":"PING"}`: `outcome: REMOTE_FAILURE` at stage `LOOKUP`, kind `CONNECTION_REFUSED` | 200 |
| `error-409-caller-crashed.json` | `POST .../calls` `{"fromNode":3,"targetNode":1,"method":"PING"}` (a dead node cannot call) | 409 |
| `error-409-compare-target-down.json` | `POST .../comparisons` `{"fromNode":1,"targetNode":3,"method":"PING"}` (the local half cannot run) | 409 |
| (not kept) | `POST /api/cluster/nodes/3/recover` | 200 |
| `error-400-missing-fields.json` | `POST .../calls` `{}` | 400 |
| `error-400-rounds.json` | `POST .../calls` `{"fromNode":1,"targetNode":2,"method":"COMPUTE_HASH","rounds":0}` | 400 |
| `error-400-field-not-taken.json` | `POST .../calls` `{"fromNode":1,"targetNode":2,"method":"PING","text":"x","rounds":5}` (both fields at once) | 400 |
| `error-400-unreadable.json` | `POST .../calls` `{"fromNode":1,"targetNode":2,"method":"FOO"}`: Spring's default 400, **no `errors`** | 400 |
| `error-400-bad-id.json` | `GET .../calls/abc`: Spring's default 400, **no `errors`** | 400 |
| `error-404-unknown-node.json` | `POST .../calls` `{"fromNode":1,"targetNode":9,"method":"PING"}` | 404 |
| `error-404-unknown-call.json` | `GET .../calls/999` | 404 |
| `calls-history.json` | `GET .../calls` (newest first; calls and comparisons share one id sequence) | 200 |
| `call-latest.json` | `GET .../calls/latest` | 200 |
| `call-by-id.json` | `GET .../calls/1` | 200 |
| `events.json` | `GET /api/events?module=rmi&limit=200` | 200 |
| `events-export.txt` | `GET /api/events/export?module=rmi&limit=40` (`latency=` on REMOTE `CALL_SUCCEEDED` lines only) | 200 |
| (not kept) | `POST /api/cluster/reset` | 204 |
| `error-409-busy.json` | captured separately, see below | 409 |

**`error-409-busy.json`.** In the main script run, the attempts to capture it failed because of a
quoting mistake in the script: the background request's `Content-Type` header was split, so it got
an immediate 415 and never held the module. In a second attempt, the background request went to the
wrong path (405). Nothing from those attempts was kept. It was then captured against the same
backend, after the cluster reset above: a background `POST .../calls`
`{"fromNode":1,"targetNode":2,"method":"COMPUTE_HASH","rounds":1000000}` held the module (its
measured round trip was 431810200 ns, about 432 ms). The script polled `GET /api/modules/rmi` until
the status said `BUSY`, then sent `POST .../calls` `{"fromNode":3,"targetNode":4,"method":"PING"}`,
which got this 409 on the first try. The cluster was then reset again (204, not kept).

Then backend 1 was stopped, and no listener was left on 8080 or 1101 to 1105.

## Backend 2 (`call-timeout-millis` 50), for a real timeout

Backend 2 was started only after backend 1 had stopped and those ports were free:
`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--udcf.rmi.call-timeout-millis=50"`.
The overview confirmed `limits.callTimeoutMillis: 50`.

| File | Request | Status |
|---|---|---|
| `overview-timeout-backend.json` | `POST .../start` on backend 2 (shows `callTimeoutMillis: 50`) | 200 |
| `call-timed-out.json` | `POST .../calls` `{"fromNode":1,"targetNode":2,"method":"COMPUTE_HASH","rounds":1000000}` | 200 |

The result was taken as it came, with nothing tuned: `REMOTE_FAILURE` at stage **`CALL`**, kind
`TIMED_OUT`, `java.rmi.UnmarshalException` caused by `java.net.SocketTimeoutException`, after
51309900 ns. The lookup itself completed (23815400 ns), even though it was the first call on a
fresh backend. The node kept hashing after the caller gave up (a known E1b issue: an RMI thread
cannot be stopped from here). Backend 2 was then stopped, and no listener was left on 8080 or 1101
to 1105.

## Values that are not stable

These differ on every capture. Tests should check their type and shape, not their value.

- Every measured figure: `lookupNanos`, `roundTripNanos`, `timeToFailureNanos`, `serverNanos`,
  `uptimeMillis`, `localNanos`, `remoteNanos`, `overheadNanos` (may be negative), `ratio` (may be
  below 1), event `data` timings, and the export's `latency=`.
- Times: `calledAt`, event `wallTime`, and the export's leading timestamp.
- Lamport values (`senderLamportAtSend`, `replyLamport`, `senderLamportAfterReply`, reply
  `lamportTime`, event `lamportTime`) and event `sequence`.
- The stub class number (`jdk.proxy2.$Proxy111` here). Only the `$Proxy` part is stable.
- `javaVersion` (the JVM that ran the backend).
- Failure messages that quote an operating-system exception. This capture, on Windows, says
  `Connection refused: getsockopt`; Linux says `Connection refused`.
- Whether a call to a crashed node fails at `LOOKUP` as `CONNECTION_REFUSED` (as here) or as
  `CONNECTION_LOST` (RMI reuses a pooled connection used less than about 5 ms before).

## Stable by design

- Field names and types, and `null` (never 0, never `[]`) for everything not measured or not
  applicable: sizes on the LOCAL path, the round trip on a failure, an unreachable registry's
  `names`, and reply fields belonging to another method.
- Enum values: `status`, `state`, `nodeStatus`, `method`, `path`, `outcome`, `failureStage`,
  `failureKind`, `kind`.
- The texts: `payloadSizeBasis`, `lookupBasis`, `coHosting.reason`, `comparisonNote`, each failure
  kind's `description`, and the method descriptions.
- The limits under the local profile: 1000000 hash rounds, 1024 echo characters, 50 kept calls,
  call timeout 3000 ms, connect timeout 1000 ms, 300-character failure messages.
- The digest of a given node and round count (chained SHA-256 over `udcf-rmi-node-<n>`).
- The error `title`s, and the `errors` keys of the module's own 400s.
