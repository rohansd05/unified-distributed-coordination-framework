# Multithreading contract fixtures (E2c)

Real response bodies from the running backend (`cd backend; .\mvnw.cmd spring-boot:run`,
local profile, five nodes), captured on 2026-10-07 with `curl.exe -o <file>`. Nothing here was
edited by hand. Captured in this order:

| File | Request | Status |
|---|---|---|
| `overview-before.json` | `GET /api/modules/multithreading` (no node used yet) | 200 |
| `batch.json` | `POST /api/modules/multithreading/nodes/1/batches` `{"count":100,"type":"MIXED","payloadSize":50}` | 202 |
| `overview-after-batch.json` | `GET /api/modules/multithreading`, about 3 s later | 200 |
| `requests.json` | `GET /api/modules/multithreading/nodes/1/requests?limit=10` | 200 |
| `backpressure.json` | `POST /api/modules/multithreading/nodes/3/backpressure` | 202 |
| `error-409-module-busy.json` | `POST /api/modules/multithreading/nodes/1/backpressure` while the node 3 demo ran | 409 |
| `error-409-node-down.json` | `POST .../nodes/2/batches` `{"count":1,"type":"CPU_HASH","payloadSize":5}` after `POST /api/cluster/nodes/2/crash` | 409 |
| `error-400-validation.json` | `POST .../nodes/1/batches` `{"count":0,"type":"CPU_HASH","payloadSize":5001}` | 400 |
| `error-404-unknown-node.json` | `POST .../nodes/9/batches` `{"count":1,"type":"CPU_HASH","payloadSize":5}` | 404 |
| `events.json` | `GET /api/events?module=multithreading&limit=50`, once the module was IDLE again | 200 |

## Values that are not stable

These differ on every capture; tests should check their type and shape, not their value.

- Ids: `batchId`, `requestIds`, request `id`, event `data.batchId` (random 8-character hex).
- Times: `submittedAt`, event `wallTime` (wall clock); `queueWaitMillis`, `processingMillis`,
  `totalMillis`, `elapsedMillis`, `averageResponseTimeMillis`, `p95ResponseTimeMillis`,
  `requestsPerSecond` (measured).
- Thread names: which `udcf-worker-n1-<i>` ran which request; event `data.threads` for node 1.
- Event `sequence` and `lamportTime` depend on what else happened on the node first.
- `resultSummary` digests are stable for the same workload and payload; `waited=` depends on
  the payload.
- Error `detail` text is stable; the `errors` map key order in the 400 body is not.

## Stable by design

Field names and types, enum values (`status`, `kind`, `workload`, `nodeStatus`, `capacity`),
node capacities, threads, work multipliers, ports (7201 to 7205 under the local profile), the
workload labels and the capacity note, the backpressure size on node 3 (1 thread + 200 queue +
50 extra = 251 requested, exactly 50 rejected), and `stats: null` for a node whose requests
service has never started.
