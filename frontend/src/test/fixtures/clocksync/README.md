# Clock Synchronization Contract Fixtures (E3c)

Real response bodies from the running backend (`cd backend; .\mvnw.cmd spring-boot:run`,
local profile, five nodes), captured on 2026-10-08 with `curl.exe -o <file>`. Nothing here was
edited by hand. Captured in this order:

| File | Request | Status | Description |
|---|---|---|---|
| `overview-initial.json` | `GET /api/modules/clocksync` | 200 | Initial state before any events: nodes, logical clocks, drift parameters, limits, notes |
| `local-event.json` | `POST /api/modules/clocksync/nodes/1/local-events` | 200 | Records local event advancing node 1 Lamport clock |
| `message-live.json` | `POST /api/modules/clocksync/messages` | 200 | Point-to-point UDP message between live nodes (1 to 2) with `deliveryStatus: "SENT"` |
| `error-409-node-down.json` | `POST /api/modules/clocksync/messages` | 409 | Attempt to send from crashed node 3 (`NodeDownException`) |
| `message-crashed-receiver.json` | `POST /api/modules/clocksync/messages` | 200 | UDP honesty: message sent to crashed node 3 returns 200 with `deliveryStatus: "UNKNOWN"` |
| `traffic.json` | `POST /api/modules/clocksync/traffic` | 202 | Asynchronous burst of random traffic between live nodes accepted |
| `berkeley-round.json` | `POST /api/modules/clocksync/berkeley-rounds` | 202 | Asynchronous Berkeley synchronization round accepted |
| `overview-after-sync.json` | `GET /api/modules/clocksync` | 200 | Overview after round: `latestRound` details, updated clocks, adjusted drift offsets |
| `drift-updated.json` | `PUT /api/modules/clocksync/nodes/2/drift` | 200 | Explicit update of node 2 simulated drift offset and rate |
| `verification.json` | `GET /api/modules/clocksync/verification` | 200 | Causal invariant verification (Lamport Rule 3 and local monotonicity) |
| `timeline.json` | `GET /api/modules/clocksync/timeline?limit=10` | 200 | Retained causal events with `messageId` for space-time diagram |
| `error-400-validation.json` | `POST /api/modules/clocksync/traffic` | 400 | Validation error when request parameters exceed configured limits |
| `error-404-unknown-node.json` | `POST /api/modules/clocksync/nodes/99/local-events` | 404 | Error when referencing non-existent node |
| `events.json` | `GET /api/events?module=clocksync&limit=50` | 200 | Bus events published during the test run |

## Values that are not stable

These differ on every capture; frontend tests should verify type and structure rather than exact values:

- Ids: `sessionId` (UUID), `messageId`, `roundId`.
- Timestamps: `wallTime`, `simulatedTime`, event `timestamp`.
- Measured quantities: `rttMillis` in round adjustments, elapsed physical drift.
- Lamport values: `lamportTime`, `causedByTime`, `lamportValue` depending on event counts.

## Stable by design

- DTO schema structure, JSON property names, and data types.
- Configuration limits (`defaultTrafficSeconds: 5`, `maxTrafficSeconds: 30`, `retainedEventsCapacity: 2000`).
- Honesty flags: `simulated: true` and `simulatedReason` on drift snapshots and Berkeley rounds.
- UDP delivery status semantics: `"SENT"` for live targets, `"UNKNOWN"` for crashed targets.
