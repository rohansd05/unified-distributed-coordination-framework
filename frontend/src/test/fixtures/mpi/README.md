# MPI contract fixtures (E9c)

## 9A.3 recapture

Recaptured on 2026-10-18 for Phase 9A.3 (link L1), with every other kept file in this folder, by one Windows PowerShell 5.1 block (`curl.exe -s -o <file>`) against a fresh backend; the block is in `docs/tracks/track-c-jai.md`, section "9A.3 (fast mode)". All 88 kept responses (across the mpi, matrix and replication folders) had the expected status; nothing was edited by hand. The order is the table below, with these differences:

- Every collective that used the default root now names `"rootNode":1` (`rootSource: REQUESTED`), because since
  9A.3 the default root is the leader elected by Experiment 4 and asking for it starts an election. No election
  runs in that part, so `defaultRootNodeId` and `defaultRootSource` are `null` in the overviews (a read never elects).
  `LOWEST_LIVE_ID` is no longer served.
- Port 1105 was blocked with a .NET socket (bound, never listening) instead of Python's; node 5 again reports
  "Port already in use: 1105".
- Measured figures quoted in the table (nanoseconds) are from the 2026-10-13 capture; the files hold
  the new ones.
- `error-409-busy.json` is kept from the 2026-10-13 capture (it holds no root field).
- New, captured at the end, after Experiment 5's writes had elected node 5: `collective-leader-root.json`
  (`{"operation":"BROADCAST","count":1000,"seed":42}`: root node 5, `rootSource: LEADER`) and `overview-leader.json`
  (`defaultRootNodeId` 5, `defaultRootSource` `LEADER`, node 5 `leader: true`).
- `synthetic/error-409-no-leader.json` is not a capture: the body `MpiControllerTest.noLeader` checks (no running
  backend can fail to elect a live node on demand).

## 2026-10-13 capture (E9c)

Real response bodies from the running backend (`cd backend; ./mvnw spring-boot:run`, local profile,
five nodes, RMI registries on ports 1101 to 1105), captured on 2026-10-13 with
`curl -s -o <file>` from a capture script and, for the 409 busy only, a Python 3 standard-library
script (`python -I`). Both scripts ran outside the repository. Nothing here was edited by hand.
Steps marked "not kept" were run, but their responses were not saved.

## One backend, default settings, in capture order

| File | Request | Status |
|---|---|---|
| `overview-before-start.json` | `GET /api/modules/mpi` (fresh backend: every node `NOT_STARTED`, `storedCollectiveIds: null`, status `IDLE`) | 200 |
| `error-404-no-collective-yet.json` | `GET .../collectives/latest` (`collectiveId: null`) | 404 |
| (not kept) | A socket bound to 127.0.0.1:1105, node 5's registry port, that never listens (Python `socket.bind` only, no socket option set), started before any RMI start | |
| `start-with-failure.json` | `POST .../start`: nodes 1 to 4 `EXPORTED`; node 5 `UP` but `NOT_STARTED`, with the real `startFailure` ("Port already in use: 1105") | 200 |
| `collective-partial.json` | `POST .../collectives` `{"operation":"BROADCAST","count":1000,"seed":42}`: **PARTIAL**. Node 5 is live, so it is a rank; its transfer is `FAILED` at stage `LOOKUP`, kind `CONNECTION_REFUSED`, after 21944300 ns; nodes 2 to 4 `DELIVERED` | 200 |
| (not kept) | The socket on 1105 closed (no listener left on 1105) | |
| `start.json` | `POST .../start` (all five `EXPORTED`; status `RUNNING`) | 200 |
| `overview-after-start.json` | `GET /api/modules/mpi`. `storedCollectiveIds` is `[1]` on nodes 2 to 4, and `[]` on node 1 (the root keeps nothing) and on node 5 (a fresh endpoint since its start) | 200 |
| `collective-broadcast.json` | `{"operation":"BROADCAST","count":1000,"seed":42}`: COMPLETE, `passed: true` | 200 |
| `collective-scatter.json` | `{"operation":"SCATTER","count":1003,"seed":42}`: COMPLETE, blocks 201, 201, 201, 200, 200, `wholeVectorMatches: true` | 200 |
| `collective-gather.json` | `{"operation":"GATHER","count":1003,"seed":42}`: COMPLETE, five `gathered` blocks with checksums (no values), `wholeVectorMatches: true` | 200 |
| `collective-requested-root.json` | `{"operation":"BROADCAST","count":1000,"seed":42,"rootNode":3}`: `rootSource: REQUESTED`, root rank 2 | 200 |
| (not kept) | `POST /api/cluster/nodes/3/crash` | 200 |
| `overview-node-crashed.json` | `GET /api/modules/mpi` (node 3 `CRASHED`; scatter and gather `minimumCount` 4) | 200 |
| `collective-node-crashed-skipped.json` | `{"operation":"BROADCAST","count":1000,"seed":42}`: COMPLETE on the live nodes, `skippedNodeIds: [3]`, four transfers | 200 |
| `error-409-root-down.json` | `{"operation":"BROADCAST","count":1000,"rootNode":3}`: "Node down", `nodeId: 3` | 409 |
| `collective-refused.json` | `{"operation":"SCATTER","count":3,"seed":42}` with 4 live nodes: **REFUSED**, `COUNT_BELOW_PARTICIPANTS`, no transfers, `verification: null` | 200 |
| (not kept) | `POST /api/cluster/nodes/{1,2,4,5}/crash` | 200 |
| `error-409-no-live-node.json` | `{"operation":"BROADCAST","count":1000}` with every node crashed: "Node state conflict", `nodeId: 0` | 409 |
| (not kept) | `POST /api/cluster/nodes/{1..5}/recover` | 200 |
| `error-400-missing-fields.json` | `{}`: `errors` has `operation` and `count` | 400 |
| `error-400-count.json` | `{"operation":"BROADCAST","count":100001}`: "count must be 1-100000, was 100001" | 400 |
| `error-400-seed.json` | `{"operation":"BROADCAST","count":5,"seed":-1}`: "seed must be 0-9007199254740991, was -1" | 400 |
| `error-400-unreadable.json` | `{"operation":"FOO","count":5}`: Spring's default 400, **no `errors`** | 400 |
| `error-400-bad-id.json` | `GET .../collectives/abc`: Spring's default 400, **no `errors`** | 400 |
| `error-404-unknown-node.json` | `{"operation":"BROADCAST","count":5,"rootNode":9}`: "Unknown node", `nodeId: 9` | 404 |
| `error-404-unknown-collective.json` | `GET .../collectives/999`: "Unknown collective", `collectiveId: 999` | 404 |
| `collectives-history.json` | `GET .../collectives` (newest first: ids 7 to 1) | 200 |
| `collective-latest.json` | `GET .../collectives/latest` (id 7, the REFUSED scatter) | 200 |
| `collective-by-id.json` | `GET .../collectives/2` (the COMPLETE broadcast) | 200 |
| `events.json` | `GET /api/events?module=mpi&limit=200`: 5 `COLLECTIVE_ENDPOINT_EXPORTED`, 7 `COLLECTIVE_STARTED`, 6 `COLLECTIVE_FINISHED`, 1 `COLLECTIVE_REFUSED` | 200 |
| `events-export.txt` | `GET /api/events/export?module=mpi&limit=40` (no `latency=`: mpi events carry no `latencyMillis`) | 200 |
| `error-409-busy.json` | captured separately, see below | 409 |
| (not kept) | `POST /api/cluster/reset` | 204 |

**`error-409-busy.json`.** The capture script's first try polled `GET /api/modules/mpi` with curl
until it said `BUSY` (on the first poll), but the holding broadcast (100000 values, 64359000 ns)
had finished by the time the second curl started, so that request got 200 and nothing was kept. The
second try used one Python process (no process start between the poll and the request): a thread
posted `{"operation":"BROADCAST","count":100000,"seed":7}`, the main thread polled the overview
until `BUSY` (2 polls), then posted `{"operation":"GATHER","count":5}`, which got this 409 on the first
attempt while the broadcast held the module (it finished in 46144100 ns). The cluster was then reset
(204, not kept). The rejection's `ACTION_REJECTED_BUSY` event was not captured (`events.json` was
taken before).

The backend was then stopped. No listener was left on 8080, 5173 or 1101 to 1105, no UDP socket on
6001 to 6005, and no capture process was left running.

## Values that are not stable

These differ on every capture. Tests should check their type and shape, not their value.

- Every measured figure: `latencyNanos`, `timeToFailureNanos`, `totalNanos`.
- Times: `startedAt`, event `wallTime`, and the export's leading timestamp.
- Lamport values (`senderLamportAtSend`, `replyLamport`, `lamportAfterReply`, event `lamportTime`) and
  event `sequence`.
- `storedCollectiveIds` (which collectives each node still keeps; each node keeps 8).
- `startFailure` and `failureMessage` text that quotes the operating system. This capture, on
  Windows, says `Connection refused: getsockopt` and `Address already in use: bind`.
- Whether a node that crashes during a collective fails as `CONNECTION_REFUSED` (as in the tests)
  or `CONNECTION_LOST` (RMI reuses a connection used less than about 5 ms before).

## Stable by design

- Field names and types, and `null` (never 0, never `[]`) for everything not measured or not
  applicable: the root's `LOCAL` part has no size, latency, Lamport value or verification; a
  `FAILED` part has only `timeToFailureNanos`; a refused collective has no `rootRank`,
  `inputChecksum`, `totalNanos`, `verification` or basis texts; `storedCollectiveIds` is `null` while
  a node's endpoint is not exported.
- Enum values: `status`, `rmiState`, `nodeStatus`, `operation`, `rootSource`, `outcome`, `direction`,
  transfer `status`, `failureStage`, `failureKind`, `refusal.reason`.
- The texts: `latencyBasis`, `payloadSizeBasis`, `algorithmNote`, `coHosting.reason`, the operation
  descriptions, `failureDescription`, `refusal.message`.
- The limits under the local profile: 100000 elements, deadline 10000 ms, 20 kept collectives, seed
  at most 9007199254740991, connect 1000 ms, call 3000 ms, `worstCaseTransferMillis` 12000 and
  `recommendedMinClientTimeoutMillis` 22000. The last two are derived from the configured timeouts
  (3 x (connect + call), then plus the deadline), not measured.
- With the same `count` and `seed`, every checksum (`inputChecksum`, `expectedChecksum`,
  `reportedChecksum`, block `checksum`) and every `requestBytes` and `replyBytes`.
- The error `title`s, and the `errors` keys of the module's own 400s.
