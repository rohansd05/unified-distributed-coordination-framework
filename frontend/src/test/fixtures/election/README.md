# Election contract fixtures (E4c, recaptured in E4d)

Real response bodies from the running backend (`cd backend; .\mvnw.cmd spring-boot:run`,
local profile, five nodes, election ports 7001 to 7005, Appendix B timings), captured with
`curl.exe -s -o <file>`, one script run against a freshly started backend. First captured in E4c;
recaptured in E4d (2026-10-09) with the same script, after the backend gained sentence-case event
messages and the per-node `electionsWon`, `roundsTimed` and `meanDurationMillis` fields. Then one
extra file was captured from the same backend, `overview-ring-timed-out.json` (see the end of the table). Each
request body was written to a file and sent with `-H "Content-Type: application/json" -d "@<body>.json"`.
Waits (for an election to finish) were polling loops on `GET /api/modules/election` with a
deadline, never fixed waits. Nothing here was edited by hand. Steps marked "not kept" were run
but their responses were not saved.

| File | Request | Status |
|---|---|---|
| `overview-initial.json` | `GET /api/modules/election` (fresh backend: no service started, no leader) | 200 |
| `consensus-initial.json` | `GET /api/modules/election/consensus` | 200 |
| `start-bully.json` | `POST .../elections` `{"algorithm":"BULLY","nodeId":1}` | 202 |
| `overview-after-bully.json` | `GET /api/modules/election`, once the leader was 5 and no round was open | 200 |
| `consensus-passed.json` | `GET .../consensus` | 200 |
| `cluster-with-leader.json` | `GET /api/cluster`: node 5 has `"roles": ["LEADER"]` | 200 |
| (not kept) | `POST /api/cluster/nodes/5/crash` (the cluster API, as the cluster page does) | 200 |
| `overview-leader-crashed.json` | `GET /api/modules/election`, straight after the crash (before detection) | 200 |
| `consensus-dead-leader.json` | `GET .../consensus`: reached on 5, `coordinatorAlive` false, `passed` false | 200 |
| `cluster-leader-crashed.json` | `GET /api/cluster`: no node holds a role | 200 |
| `overview-after-reelection.json` | `GET /api/modules/election`, once leader 4 was agreed in a `LEADER_FAILURE` round (no request made) | 200 |
| (not kept) | `POST /api/cluster/nodes/3/crash` | 200 |
| `start-ring.json` | `POST .../elections` `{"algorithm":"RING","nodeId":2}` (nodes 3 and 5 down) | 202 |
| `error-409-module-busy.json` | `POST .../elections` `{"algorithm":"BULLY","nodeId":1}`, sent straight after, while the Ring round was open | 409 |
| `overview-after-ring.json` | `GET /api/modules/election`, once the Ring round finished (leader 4) | 200 |
| (not kept) | `POST /api/cluster/nodes/3/recover`, then a wait for node 3's `RECOVERY` round | 200 |
| (not kept) | `POST /api/cluster/nodes/5/recover` | 200 |
| `overview-after-recovery.json` | `GET /api/modules/election`, once node 5 was leader again after its `RECOVERY` round | 200 |
| `error-400-validation.json` | `POST .../elections` `{"algorithm":"BULLY","nodeId":0}` | 400 |
| `error-400-unreadable.json` | `POST .../elections` `{"algorithm":"PAXOS","nodeId":1}` (unknown algorithm) | 400 |
| `error-404-unknown-node.json` | `POST .../elections` `{"algorithm":"RING","nodeId":9}` | 404 |
| (not kept) | `POST /api/cluster/nodes/2/crash` | 200 |
| `error-409-node-down.json` | `POST .../elections` `{"algorithm":"BULLY","nodeId":2}` (node 2 crashed) | 409 |
| (not kept) | `POST /api/cluster/nodes/2/recover`, then a wait for node 2's `RECOVERY` round | 200 |
| `events.json` | `GET /api/events?module=election&limit=200` | 200 |
| `events-cluster.json` | `GET /api/events?module=cluster&limit=100` (includes every `LEADER_CHANGED`) | 200 |
| (not kept) | `POST /api/cluster/reset` | 204 |
| `overview-after-reset.json` | `GET /api/modules/election`: services still running, no leader, no coordinators, no rounds | 200 |
| `cluster-after-reset.json` | `GET /api/cluster`: no roles | 200 |
| (not kept) | E4d extra script: `POST /api/cluster/nodes/{2,3,4,5}/crash` | 200 |
| (not kept) | `POST .../elections` `{"algorithm":"RING","nodeId":1}` (only node 1 up) | 202 |
| `overview-ring-timed-out.json` | `GET /api/modules/election`, polled until the round was closed `TIMED_OUT` (the backend closes an overdue round when it is read, after `roundTimeoutMillis`) | 200 |
| (not kept) | `POST /api/cluster/nodes/{2,3,4,5}/recover`, a wait for leader 5, then `POST /api/cluster/reset` | 200, 204 |

`events-cluster.json` is the source for the page's "Leader changes (cluster log)" list: the page reads
the cluster log and keeps its `LEADER_CHANGED` events; they are never copied into the election log.

Afterwards the backend was stopped and ports 8080 and 7001 to 7005 checked free.

## Values that are not stable

These differ on every capture; tests should check their type and shape, not their value.

- Times: round `startedAt` and event `wallTime` (wall clock).
- Every measured figure: round `durationMillis` and event `data.durationMillis`.
- Lamport values and ordering: event `lamportTime`, `sequence`, `data.causedByTime`. They depend on
  what else happened first.
- How many messages and automatic elections ran: Bully's message count, which nodes started a
  re-election (only one `LEADER_FAILURE` round opens), and therefore `roundId` values and the
  round number quoted in the 409 `detail` and `actionInProgress`.
- `suspectedPeers` right after a crash or recovery (the detector needs 2500 ms of silence).
