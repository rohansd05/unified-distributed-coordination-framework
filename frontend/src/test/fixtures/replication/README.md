# Replication contract fixtures (E5c)

Real response bodies from the running backend (`cd backend; .\mvnw.cmd spring-boot:run`,
local profile, five nodes, replication ports 7101 to 7105), captured on 2026-10-09 with
`curl.exe -s -o <file>`. Each request body was written to a file and sent with
`-H "Content-Type: application/json" -d "@<body>.json"`. Nothing here was edited by hand.
They were captured in this order, one script run. Steps marked "not kept" were run but their
responses were not saved.

| File | Request | Status |
|---|---|---|
| `overview-before.json` | `GET /api/modules/replication` (fresh backend) | 200 |
| `write-sync.json` | `POST .../writes` `{"key":"balance","value":"1000","model":"SYNCHRONOUS"}` | 200 |
| `write-async.json` | `POST .../writes` `{"key":"balance","value":"2000","model":"ASYNCHRONOUS"}` | 200 |
| `read-stale.json` | `GET .../nodes/2/values?key=balance`, straight after the async write (inside the 450 ms simulated delay): `STALE` | 200 |
| `overview-after-async.json` | `GET /api/modules/replication`, once the module was IDLE again | 200 |
| `read-current.json` | `GET .../nodes/2/values?key=balance`: `CURRENT` | 200 |
| `crash-backup.json` | `POST .../nodes/3/crash` | 200 |
| `write-while-backup-down.json` | `POST .../writes` `{"key":"region","value":"eu;west~1","model":"SYNCHRONOUS"}` (node 3 FAILED) | 200 |
| `read-unreachable.json` | `GET .../nodes/3/values?key=region`: `UNREACHABLE` | 200 |
| `recover-backup.json` | `POST .../nodes/3/recover` | 200 |
| `replicas-diverged.json` | `GET .../replicas` (node 3 MISSING `region`) | 200 |
| `anti-entropy.json` | `POST .../anti-entropy` `{"targetNodeId":3}` | 200 |
| `replicas-converged.json` | `GET .../replicas` (consistent) | 200 |
| `stale-injection.json` | `POST .../stale-injections` `{"backupNodeId":2,"key":"balance","staleValue":"500"}` (rejected, STALE) | 200 |
| (not kept) | `POST /api/cluster/nodes/1/crash` (the cluster API, as the cluster page does) | 200 |
| `overview-takeover-pending.json` | `GET /api/modules/replication`: `primaryNodeId` 2, `currentPrimaryNodeId` 1, `takeoverPending` true | 200 |
| `write-after-failover.json` | `POST .../writes` `{"key":"balance","value":"3000","model":"SYNCHRONOUS"}` (takeover 1 to 2) | 200 |
| (not kept) | `POST /api/cluster/nodes/1/recover` (the cluster API, so the takeover stays pending) | 200 |
| `write-after-reclaim.json` | `POST .../writes` `{"key":"balance","value":"4000","model":"SYNCHRONOUS"}` (takeover 2 to 1: catch-up from 2, 3, 4, 5, then push) | 200 |
| `overview-after-takeover.json` | `GET /api/modules/replication` | 200 |
| `error-400-validation.json` | `POST .../writes` `{"key":"","value":"v","model":"SYNCHRONOUS"}` | 400 |
| `error-400-crash-primary.json` | `POST .../nodes/1/crash` (node 1 is the primary) | 400 |
| `error-404-unknown-node.json` | `GET .../nodes/9/values?key=balance` | 404 |
| (not kept) | `POST .../nodes/4/crash` | 200 |
| `error-409-node-down.json` | `POST .../anti-entropy` `{"targetNodeId":4}` (node 4 crashed) | 409 |
| (not kept) | `POST .../nodes/4/recover` | 200 |
| `error-409-node-state.json` | `POST .../nodes/2/recover` (node 2 is up) | 409 |
| `events.json` | `GET /api/events?module=replication&limit=200` | 200 |
| (not kept) | `POST /api/cluster/reset` | 204 |

Afterwards the backend was stopped and port 8080 checked free.

**Not captured: `error-409-module-busy`.** No replication action runs long enough to hit it from
outside. ReplicationControllerTest covers it with MockMvc while the test holds the module guard.
The body shape is the shared one in the multithreading and loadbalancing fixtures, with
`moduleId: "replication"`.

## Values that are not stable

These differ on every capture; tests should check their type and shape, not their value.

- `writeId` (random 8-character hex).
- Times: `lastSync` and event `wallTime` (wall clock).
- Every measured figure: `confirmMillis`, `latencyMillis` (pushes, anti-entropy, catch-ups),
  `averageLatencyMillis`, `maxLatencyMillis`, event `data.latencyMillis` and `data.confirmMillis`.
- Lamport values: item `lamportTime`, takeover `lamportTime`, event `lamportTime`,
  `receiveLamport` and `itemLamport`, and event `sequence`. They depend on what else happened first.
- Error `detail`, `error` and `failure` texts that quote an exception message. This capture, on
  Windows, says `ConnectException: Connection refused: getsockopt`; the wording after
  "Connection refused" depends on the operating system and JDK.
- Whether `read-stale.json` shows STALE depends on the read arriving inside the 450 ms simulated
  delay. In this capture it did.

## Stable by design

- Field names and types.
- Enum values: `status`, `model`, `replicationState`, `state`, `nodeStatus`, `role`, push
  `status` and `result`.
- The settings: `asyncDelayMillis` 450 (simulated), `timeoutMillis` 1500, `batchSize` 200.
- The texts: `asyncDelayReason`, `conflictRuleNote`, each model's `description`, `guarantee` and
  `simulatedReason`.
- Ports 7101 to 7105 under the local profile, and epoch 1 everywhere (Exp 5 never changes it).
- `null`, never 0, for everything not known or not measured: latencies before the first
  acknowledgement, a never-started node's `epoch`, a down node's `itemCount`, an unreachable
  replica's `item`, a failed pull's counts, `previousPrimaryNodeId` on the first selection.
- The error `title`s and `errors` keys.
