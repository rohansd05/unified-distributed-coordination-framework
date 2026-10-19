# Experiment 3: Clock Synchronization (logical and physical)

Module id `clocksync` · page `/experiments/3-clocksync` · packages `com.udcf.modules.clocksync`, `com.udcf.core.clock`

## Aim

Implementation of clock synchronization: Lamport logical clocks and Berkeley physical clock
synchronization.

## Theory

Nodes in a distributed system have no shared clock, and their hardware clocks drift apart. Two kinds of
synchronization answer two different questions.

**Logical clocks (Lamport)** order events by cause and effect, without real time. Each node keeps a
counter. Rule 1: before a local event, add 1. Rule 2: before sending, add 1 and attach the value to the
message. Rule 3: on receiving, set the counter to `max(local, received) + 1`. Then if event a happened
before event b, `L(a) < L(b)`. Ties are broken by node id, which gives a *total order*
`(lamportTime, nodeId)`. The check that matters: every receive has a larger time than its send.

**Physical clock synchronization (Berkeley)** pulls real clocks together without an external time
source. A *time daemon* polls every node for its clock, estimates each offset (compensating for half
the round-trip time, as in Cristian's algorithm), averages the offsets while leaving out outliers
beyond a threshold, and sends each node its own adjustment rather than an absolute time.

## How UDCF implements it

- **Lamport clock.** `core/clock/LamportClock` (an `AtomicLong`): `tick()` for rules 1 and 2,
  `update(received)` = `max(local, received) + 1` for rule 3. Every node has exactly one clock, shared by
  every module on that node, and it survives crash and recovery.
- **Lamport model.** `ClockEventLog` (bounded, causally ordered), `LamportTotalOrder`
  (`(lamportTime, nodeId)`), `CausalInvariantChecker` (receive later than its send; local times
  increase).
- **Berkeley model.** `BerkeleyAveragingCoordinator` (pure averaging with outlier rejection and
  round-trip compensation `offset + round(rtt/2)`); `ClockDriftModel` (simulated physical clock:
  `offset(t) = baseOffset + driftRate × elapsed seconds`).
- **Transport.** `ClockNodeService` (`NodeService` named `clock`) owns one UDP socket on
  `127.0.0.1:ports().clock()`: 6001 to 6005 locally, 6001 to 6003 public. `ClockProtocol` encodes
  `LAMPORT`, `POLL`, `POLL_REPLY`, `ADJUST` and `ADJUST_ACK` datagrams; each carries the sender's Lamport
  time. The listener thread only completes futures; it does no protocol work that waits.
- **Berkeley round.** The daemon reads its own offset, sends `POLL` to every live node and waits up to
  `poll-timeout-millis` (1000 ms) per reply, computes the round, applies its own adjustment, sends `ADJUST`
  to the others and collects `ADJUST_ACK`s. A node that does not reply is listed as unresponsive. If the
  daemon crashes mid-round, the round is aborted and reported.
- **Module and API.** `ClockSyncModule` (lab 3) and `ClockSyncController` under
  `/api/modules/clocksync`: `POST /nodes/{id}/local-events`, `POST /messages`, `POST /traffic`,
  `POST /berkeley-rounds`, `PUT /nodes/{id}/drift`, `GET /verification`, `GET /timeline`.
- **Role.** The Berkeley time daemon is the cluster leader (link L1).
- **Events** (module `clocksync`): `CLOCK_LOCAL_EVENT`, `CLOCK_MESSAGE_SENT`, `CLOCK_MESSAGE_RECEIVED`,
  `BERKELEY_ROUND_STARTED`, `BERKELEY_NODE_ADJUSTED`, `BERKELEY_ROUND_FINISHED`, `BERKELEY_ROUND_ABORTED`,
  `TIME_DAEMON_CHANGED`.

## Links to other experiments

- **L1, roles follow the leader.** `ClockSyncModule` asks `RoleProvider.ensureLeader()` for the daemon
  when a round is requested. If no node can be elected in time the answer is HTTP 409 "No leader", never
  another node. After a leader change it publishes `TIME_DAEMON_CHANGED` once.
- **L4, Lamport time on every message.** This experiment's clock is the shared clock that every other
  module's messages also carry. All five clock datagram types carry the sender's time, and the receiver
  calls `node.clock().update(...)`.
- The cluster-wide **Timeline** page orders every module's events by `(lamportTime, nodeId)`, the total
  order defined here.

## How to run it in the UI

1. In the sidebar, click **Clock Synchronization** (`/experiments/3-clocksync`).
2. On the **Lamport logical clock** tab, under "Rule 1", choose a **Node** and click **Record local
   event**.
3. Under "Rule 2", choose a **Sender** and a different **Receiver**, then click **Send message**.
4. Under "Random traffic burst session", keep the defaults and click **Start traffic burst**.
5. In the live visualisation, click **Verify causal invariants**.
6. Switch to the **Berkeley physical sync** tab. Keep **Outlier threshold (ms)** at 400 and click **Run
   synchronization round**.
7. Optionally, under "Adjust simulated physical clock parameters", change a node's **Offset (ms)** or
   **Drift rate (ms/s)**, click **Update drift parameters**, and run another round.

## What to observe

- **Space-time diagram:** one lane per node, dots for events and arrows for messages, each labelled with
  its Lamport value. Every arrow ends at a larger value than it starts from. "Show text equivalent
  (table)" lists the events in total order (Lamport time, node id).
- **Causal invariant verification:** "0 causal violations" after the traffic burst.
- **Berkeley clock synchronization:** each node's offset before and after the round, and the "Spread
  before" and "Spread after" metrics. The spread after is smaller, which is the purpose of the round.
- **Daemon line:** "Time daemon for the next round: Node N, the leader elected by Experiment 4". After a
  reset no node holds the role yet; the first round asks Experiment 4 to elect one.

## What is simulated

The physical clock drift. All nodes run on one computer with one hardware clock, so each node's offset
and drift rate come from `ClockDriftModel` (local defaults: node 1 0 ms and 0 ms/s, node 2 40 ms and
1.5 ms/s, node 3 −30 ms and −1.0 ms/s, node 4 25 ms and 0.8 ms/s, node 5 −20 ms and −0.5 ms/s). The
backend's reason text: "Offsets and drift rates are simulated; UDP messages and Berkeley polling rounds
are real network traffic." The Simulated badge appears on the Berkeley panel, the spread and target
metrics, and the node cards. The Lamport part is not simulated.

## Tests that prove it

- Backend (`backend/src/test/java/com/udcf/modules/clocksync`): `ClockNodeServiceTest`,
  `ClockProtocolTest`, `ClockSyncModuleTest`, `ClockSyncControllerTest`, `ClockSyncLeaderIntegrationTest`
  (L1), `ClockSyncMetricsTest`; `berkeley/BerkeleyAveragingCoordinatorTest`, `berkeley/ClockDriftModelTest`;
  `lamport/CausalInvariantCheckerTest`, `lamport/ClockEventLogTest`, `lamport/LamportTotalOrderTest`;
  `core/clock/LamportClockTest`.
- Integration: `L4LamportOnMessagesIntegrationTest` (`udpClockMessageCarriesTheSendersLamportTime`),
  `scenario/LeaderCrashEndToEndTest`.
- Frontend (`frontend/src/modules/clocksync`): `ClockSyncPage.test.jsx`, `SpaceTimeDiagram.test.jsx`,
  `BerkeleyView.test.jsx`, `VerificationPanel.test.jsx`, `diagramModel.test.js`, `contract.test.js`, and
  others.

## Viva questions

1. **State Lamport's three rules.**
   Tick before a local event; tick before sending and attach the time; on receiving, set the clock to
   `max(local, received) + 1`. In UDCF these are `tick()` and `update()` in `LamportClock`.
2. **If L(a) < L(b), did a happen before b?**
   Not necessarily. Lamport clocks guarantee only the other direction: if a happened before b then
   L(a) < L(b). Concurrent events can have any order of values. Vector clocks are needed to detect
   concurrency.
3. **Why break ties by node id?**
   Two events on different nodes can have the same Lamport time. Adding the node id gives one total
   order that every observer agrees on; UDCF's Timeline uses `(lamportTime, nodeId)`.
4. **What does the causal invariant checker verify?**
   That every receive event has a larger Lamport time than the send that caused it, and that each node's
   times increase. A correct run shows 0 violations.
5. **How does the Berkeley algorithm differ from Cristian's?**
   Cristian's asks one time server for the time. Berkeley has no authoritative clock: the daemon polls
   everyone, averages, and tells each node how much to adjust. UDCF uses Cristian's half-round-trip
   compensation when reading each offset.
6. **Why send an adjustment, not an absolute time?**
   The adjustment does not age in transit the way an absolute time does, and each node can apply it
   gradually. The daemon also adjusts itself.
7. **Why discard outliers?**
   A badly wrong clock would drag the average away from the others. Readings beyond the threshold are
   left out of the average; they still receive an adjustment toward the target.
8. **What in this experiment is simulated, and why?**
   Only the clock drift: one computer has one hardware clock, so offsets and drift rates are modelled.
   The UDP polling and adjustment messages are real.
