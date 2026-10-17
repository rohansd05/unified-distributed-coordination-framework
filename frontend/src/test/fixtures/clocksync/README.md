# Clock Synchronization Contract Fixtures (E3c, recaptured in 9A.4)

Real response bodies from the running backend (`cd backend; .\mvnw.cmd spring-boot:run`,
local profile, five nodes), first captured on 2026-10-08 (E3c) and recaptured in full on
2026-10-17 (9A.4), when the time daemon became the leader elected by Experiment 4. Each file was
written with `curl.exe -o <file>` (UTF-8, no BOM). Nothing here was edited by hand; the one
labelled synthetic file lives in `synthetic/` (see its README). Captured in this order, after a
cluster reset on a freshly started backend:

| File | Request | Status | Description |
|---|---|---|---|
| `overview-initial.json` | `GET /api/modules/clocksync` | 200 | Before any round: `timeDaemonNodeId: null` (no leader yet; reading never starts an election), `latestRound` and `abortedRound` null, nodes, drift parameters, limits, notes (including "The time daemon is the leader elected by Experiment 4. ...") |
| `local-event.json` | `POST /api/modules/clocksync/nodes/1/local-events` | 200 | Records local event advancing node 1 Lamport clock |
| `message-live.json` | `POST /api/modules/clocksync/messages` | 200 | Point-to-point UDP message between live nodes (1 to 2) with `deliveryStatus: "SENT"` |
| `error-409-node-down.json` | `POST /api/modules/clocksync/messages` | 409 | Attempt to send from crashed node 3 (`NodeDownException`) |
| `message-crashed-receiver.json` | `POST /api/modules/clocksync/messages` | 200 | UDP honesty: message sent to crashed node 3 returns 200 with `deliveryStatus: "UNKNOWN"` |
| `traffic.json` | `POST /api/modules/clocksync/traffic` | 202 | Asynchronous burst of random traffic between live nodes accepted (node 3 recovered first) |
| `berkeley-round.json` | `POST /api/modules/clocksync/berkeley-rounds` | 202 | Round accepted with `daemonNodeId: 5`: the round asked Experiment 4 for a leader, and Bully elected the highest live node |
| `overview-after-sync.json` | `GET /api/modules/clocksync` | 200 | After the round: `timeDaemonNodeId: 5`, `latestRound` (daemon 5), `abortedRound: null`, updated clocks and adjusted drift offsets |
| `drift-updated.json` | `PUT /api/modules/clocksync/nodes/2/drift` | 200 | Explicit update of node 2 simulated drift offset and rate |
| `verification.json` | `GET /api/modules/clocksync/verification` | 200 | Causal invariant verification (Lamport Rule 3 and local monotonicity) |
| `timeline.json` | `GET /api/modules/clocksync/timeline?limit=10` | 200 | Retained causal events with `messageId` for space-time diagram |
| `error-400-validation.json` | `POST /api/modules/clocksync/traffic` | 400 | Validation error when request parameters exceed configured limits |
| `error-404-unknown-node.json` | `POST /api/modules/clocksync/nodes/99/local-events` | 404 | Error when referencing non-existent node |
| `events.json` | `GET /api/events?module=clocksync&limit=50` | 200 | Bus events published during the sequence; `BERKELEY_ROUND_STARTED` carries `daemonId: 5` |
| `error-409-no-leader.json` | `POST /api/modules/clocksync/berkeley-rounds` with every node crashed | 409 | New in 9A.4: title "No leader", `reason: "NO_LEADER"`, `moduleId: "clocksync"`, answered after the election bound (about 3 s); then the cluster was reset |

`error-409-node-down.json`, `verification.json`, `error-400-validation.json` and
`error-404-unknown-node.json` came back byte-identical to the E3c captures (checked by Rohan with
git): none of them carries an id, a time or a role, and the same sequence produces the same event
counts.

## 9A.4 capture procedure

One PowerShell block (recorded in `docs/tracks/track-d-rohan.md`, 9A.4) ran the sequence above and
the MapReduce recapture. Its cluster resets go through a `Reset-Cluster` step that waits until
`GET /api/modules` shows no BUSY module and retries on HTTP 409: `POST /api/cluster/reset` refuses
while any module is busy, and right after a node recovery Experiment 4 holds an election round open
for about a second (see Known issues in the track file).

## Values that are not stable

These differ on every capture; frontend tests should verify type and structure rather than exact values:

- Ids: `sessionId` (UUID), `messageId`, `roundId`.
- Timestamps: `wallTime`, `simulatedTime`, event `timestamp`.
- Measured quantities: `rttMillis` in round adjustments, elapsed physical drift (so offsets and
  spreads depend on how long the backend had been running; the 9A.4 capture ran on a fresh backend).
- Lamport values: `lamportTime`, `causedByTime`, `lamportValue` depending on event counts.

## Stable by design

- DTO schema structure, JSON property names, and data types.
- Configuration limits (`defaultTrafficSeconds: 5`, `maxTrafficSeconds: 30`, `retainedEventsCapacity: 2000`).
- Honesty flags: `simulated: true` and `simulatedReason` on drift snapshots and Berkeley rounds.
- UDP delivery status semantics: `"SENT"` for live targets, `"UNKNOWN"` for crashed targets.
- The time daemon is the elected leader: null before the first election, otherwise the leader's id.
