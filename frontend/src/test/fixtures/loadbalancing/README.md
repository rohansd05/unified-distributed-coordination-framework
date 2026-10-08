# Load balancing contract fixtures (E6c)

Real response bodies from the running backend (`cd backend; .\mvnw.cmd spring-boot:run`,
local profile, five nodes FAST, MEDIUM, SLOW, MEDIUM, FAST), captured on 2026-10-08 with
`curl.exe -s -o <file>`; request bodies were sent from small JSON files with `-d "@file.json"`.
Nothing here was edited by hand. Captured in this order:

| File | Request | Status |
|---|---|---|
| `overview-before.json` | `GET /api/modules/loadbalancing` (no run yet) | 200 |
| `run-accepted.json` | `POST /api/modules/loadbalancing/runs` `{"strategy":"ROUND_ROBIN","requestCount":60,"workUnits":400,"concurrency":12}` | 202 |
| `overview-after-run.json` | `GET /api/modules/loadbalancing`, once the module was IDLE again | 200 |
| `comparison-accepted.json` | `POST /api/modules/loadbalancing/comparisons` `{"requestCount":60,"workUnits":400,"concurrency":12}` | 202 |
| `overview-after-comparison.json` | `GET /api/modules/loadbalancing`, once IDLE | 200 |
| `crash-run-accepted.json` | `POST .../runs` `{"strategy":"ROUND_ROBIN","requestCount":60,"workUnits":400,"concurrency":12,"crash":{"nodeId":3,"afterServed":20}}` | 202 |
| `overview-after-crash-run.json` | `GET /api/modules/loadbalancing`, once IDLE (node 3 still crashed) | 200 |
| `error-400-validation.json` | `POST .../runs` with `"requestCount":0`, after `POST /api/cluster/nodes/3/recover` | 400 |
| `error-404-unknown-node.json` | `POST .../runs` with `"crash":{"nodeId":9,"afterServed":20}` | 404 |
| `error-409-node-down.json` | `POST .../runs` with `"crash":{"nodeId":2,"afterServed":20}` after `POST /api/cluster/nodes/2/crash` (then recovered) | 409 |
| `error-409-module-busy.json` | `POST .../comparisons` (as above) while a long run (`"requestCount":200,"workUnits":2000,"concurrency":1`) was executing | 409 |
| `events.json` | `GET /api/events?module=loadbalancing&limit=200`, once IDLE | 200 |

Afterwards the cluster was reset (`POST /api/cluster/reset`, 204) and the backend stopped.

## Values that are not stable

These differ on every capture; tests should check their type and shape, not their value.

- Ids: `runId`, `comparisonId`, event `data.runId` and `data.comparisonId` (random 8-character
  hex; the warm-up's DISPATCH events would use `<comparisonId>-warm-up`).
- Times: `startedAt`, `finishedAt`, event `wallTime` (wall clock).
- Every measured figure: `makespanMillis`, `averageLatencyMillis`, `p95LatencyMillis`,
  `maxLatencyMillis`, worker `ewmaLatencyMillis` and `averageLatencyMillis`, event
  `data.makespanMillis`, `gainOverRoundRobinPercent`.
- Anything decided by live load: the requests per node of least connections and least response
  time, their `loadSpread`; which strategy is `fastest`, `slowest`, `mostEven`, and the two
  `roundRobin*` booleans of the finding. This capture happened to show round robin last and most
  even; another capture may not, and a page must say so only when the flag is true.
- The crash run: how many requests were rerouted (`reroutes`, `failedAttempts`), how many node 3
  served before the crash, and the number of DISPATCH_FAILED events (at most 20 per worker per run).
- Event `sequence` and `lamportTime` depend on what else happened first.

## Stable by design

Field names and types; enum values (`status`, `state`, `strategy`, `nodeStatus`, `capacity`);
the defaults (60, 400, 12) and local limits (1000, 5000, 50, total work 500000); the texts
(`capacityNote`, `workUnitsNote`, `deliveryNote`, `warmUpNote`, `crashNote`, each strategy's
`description` and `informationUsed`); ports 7201 to 7205 under the local profile; weights 4, 2, 1,
2, 4; round robin's exact 12/12/12/12/12 split of 60 requests with no crash, and weighted round
robin's 19/9/5/9/18; `warmUpRequests` = `requestCount`; `null` for every result and latency that
does not exist yet (`latestRun`, `latestComparison`, `report`, `finding`, latencies of a worker
that served nothing); the error `title`s, `errors` keys and `detail` texts.
