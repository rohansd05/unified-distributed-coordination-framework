# Experiment 4: Bully and Ring Election Algorithms

Module id `election` · page `/experiments/4-election` · packages `com.udcf.modules.election`, `com.udcf.core.failure`, `com.udcf.core.cluster`

## Aim

Implement the Bully and Ring election algorithms.

## Theory

Many distributed tasks need one coordinator, but no node is in charge of choosing it, and the
coordinator can crash. An *election algorithm* lets the live nodes agree on a new coordinator by
themselves. Both classic algorithms elect the live node with the highest id.

**Bully.** A node that notices the coordinator is gone sends ELECTION to every node with a higher id. If
none answers within a timeout, it wins and announces COORDINATOR to everyone. If a higher node answers
OK, that node takes over the election, and the first node waits for its COORDINATOR message. A recovered
node with the highest id starts an election and "bullies" its way back to leadership.

**Ring.** Nodes form a logical ring ordered by id. The initiator sends a token around the ring; each
node adds its id and passes it to the next *live* successor, skipping dead ones. When the token returns,
the initiator picks the highest id and circulates a COORDINATOR message.

Both need *failure detection*: nodes send periodic heartbeats, and a node silent for longer than a
timeout is *suspected* to have crashed.

## How UDCF implements it

- **Algorithms (pure).** `BullyAlgorithm` (ELECTION, OK and COORDINATOR, with OK timeout 900 ms and
  coordinator timeout 2200 ms) and `RingAlgorithm` (callback-driven token with ids collected so far,
  dead-node skip, ring completion timeout 5000 ms). `DefaultLivenessProber` probes the next ring member
  with PROBE and PROBE_ACK (probe timeout 300 ms) without blocking. `ConsensusChecker` checks that every
  live node reports the same coordinator. `ElectionRoundTracker` measures each round.
- **Transport.** `ElectionNodeService` (`NodeService` named `election`) owns one UDP socket on
  `127.0.0.1:ports().election()`: 7001 to 7005 locally, 7001 to 7003 public. `ElectionMessage` is
  `TYPE|senderId|lamportTime|payload`. The listener thread only decodes and hands each message to a
  single worker thread. **Ring messages are never handled on the listener thread**: the ring probe waits
  for an acknowledgement that only the listener can deliver, which deadlocked the original demo (hard
  rule 7; the reason is kept in the class Javadoc).
- **Failure detector (shared).** `core/failure/FailureDetector` sends a HEARTBEAT on the same election
  UDP socket every 700 ms and suspects a peer after more than 2500 ms of silence, publishing
  `PEER_SUSPECTED` and `PEER_ALIVE`.
- **Cluster leader.** `ElectionModule` implements `LeaderElector`. Whenever every live node agrees on one
  live coordinator (after a Bully or a Ring round), it calls `Cluster.assignLeader`, which gives that node
  the `LEADER` role and publishes `LEADER_CHANGED`. Automatic rounds always run Bully:
  - LEADER_FAILURE: a node's detector suspects the coordinator;
  - RECOVERY: a recovered node starts Bully, so the highest node reclaims leadership;
  - REQUESTED: `RoleProvider.ensureLeader()` asks for a leader, and Bully runs from the lowest live node;
  - MANUAL: Bully or Ring from the page.
  Only one round runs at a time; a second manual start answers 409.
- **Module and API.** `ElectionController` under `/api/modules/election`: `GET`, `POST /elections`
  (`{algorithm, nodeId}`), `GET /consensus`.
- **Events.** Module `election`: `ELECTION_START`, `ELECTION_RESTART`, `OK_RECEIVED`, `ELECTED`,
  `COORDINATOR_ACCEPTED`, `TOKEN_FORWARDED`, `DEAD_NODE_SKIPPED`, `ELECTION_TIMEOUT`, `MESSAGE_SENT`,
  `MESSAGE_RECEIVED`, `ELECTION_ROUND_STARTED`, `ELECTION_ROUND_FINISHED`, `PEER_SUSPECTED`,
  `PEER_ALIVE`. Module `cluster`: `LEADER_CHANGED`.

## Links to other experiments

- **L1, roles follow the leader.** The leader elected here is the Berkeley time daemon (Exp 3), the
  replication primary (Exps 5 and 8), the MapReduce coordinator (Exp 7) and the MPI and matrix root
  (Exps 9 and 10). They read it through `RoleProvider`; `ElectionModule` is the only `LeaderElector`.
- **L2, one failure detector.** Experiment 8 registers its own listener on the same per-node
  `FailureDetector`, so a single suspicion drives both the re-election and the failover.
- **L4, Lamport time on every message.** Every election, OK, coordinator, ring and probe message carries
  `node.clock().tick()`, and the worker calls `node.clock().update(...)` on delivery. Heartbeats are the
  one deliberate exception: they carry 0 and do not touch the clock.

## How to run it in the UI

1. In the sidebar, click **Bully and Ring Election** (`/experiments/4-election`). Nothing starts on its
   own; the detector reads "Detector idle, starts on the first election."
2. Under **Algorithm** keep **Bully**, set **Start from node** to Node 1, and click **Start election**.
3. Under **Crash or recover a node**, click **Crash node N** for the leader (the highest node, node 5
   locally). Do nothing else and wait a few seconds.
4. Click **Recover node N** for the same node and wait.
5. Choose **Ring**, start from Node 1, and click **Start election**; then crash a middle node (for
   example node 3) and start a Ring election again.

## What to observe

- **Ring of nodes:** the "Leader" badge, message arcs labelled Election, Answer, Coordinator or Ring
  token with their Lamport times, and "Suspected" on a node the others no longer hear.
- **After the crash:** with no click, the other nodes suspect the leader, run Bully, and node 4 becomes
  leader. "Election result" shows the trigger and "Duration" (measured from detection, not from the
  crash).
- **After the recovery:** the recovered highest node starts its own election and reclaims leadership.
- **Ring with a crashed node:** the event log shows `DEAD_NODE_SKIPPED`; the highest live node still wins.
- **Consensus:** "Consensus check passed: every live node agrees on node N." The "Leader changes (cluster
  log)" list shows each `LEADER_CHANGED`.

## What is simulated

Nothing. The page states "Every figure is measured; nothing on this page is simulated." Heartbeats,
suspicions and election messages are real UDP datagrams; a crash closes the node's socket.

## Tests that prove it

- Backend (`backend/src/test/java/com/udcf/modules/election`): `BullyAlgorithmTest`, `RingAlgorithmTest`,
  `DefaultLivenessProberTest`, `ConsensusCheckerTest`, `ElectionRoundTrackerTest`, `ElectionMessageTest`,
  `ElectionNodeServiceTest`, `ElectionOverUdpTest` (highest live node wins; next highest wins when the
  highest is crashed; recovered highest reclaims; ring skips a crashed successor; detector suspects and
  then sees a peer alive), `ReentrancyTest` (`testRingReentrancyNoDeadlock`), `ElectionModuleTest`,
  `ElectionControllerTest`.
- Core: `core/failure/FailureDetectorTest`, `core/cluster/RoleProviderTest`, `core/cluster/ClusterRolesTest`.
- Integration: `L4LamportOnMessagesIntegrationTest` (`udpElectionMessagesCarryTheSendersLamportTime`),
  `modules/faulttolerance/LeaderFailoverIntegrationTest` (L2), `scenario/LeaderCrashEndToEndTest`.
- Frontend (`frontend/src/modules/election`): `ElectionPage.test.jsx`, `ElectionRing.test.jsx`,
  `RoundPanel.test.jsx`, `ElectionControls.test.jsx`, `useElection.test.js`, `useLeaderChanges.test.js`,
  `contract.test.js`, and others.

## Viva questions

1. **Why is it called the Bully algorithm?**
   The highest live node always wins: a higher node answering OK takes over any election started by a
   lower one, and a recovered highest node reclaims leadership from a lower leader.
2. **How many messages does Bully need?**
   In the worst case (the lowest node starts it) O(n²), because every node may start its own election to
   all higher nodes. In the best case (the second-highest node notices) n − 2 COORDINATOR messages.
3. **How does Ring handle a dead node?**
   Before forwarding the token, the sender probes its successor. If no PROBE_ACK arrives within 300 ms, it
   skips that node and tries the next one (`DEAD_NODE_SKIPPED`).
4. **What deadlock did the original Ring code have, and how is it avoided?**
   The probe waited for an acknowledgement on the listener thread, but only that same listener thread
   could deliver the acknowledgement. UDCF's listener only decodes and hands messages to a worker thread.
5. **How does a node know the leader has crashed?**
   Heartbeats every 700 ms on the election UDP channel. A peer silent for more than 2500 ms is suspected.
   The suspicion of the known coordinator triggers a Bully election with no user action.
6. **What does the consensus check prove?**
   That every live node reports the same live coordinator. Only then is that node given the cluster's
   LEADER role.
7. **Why does the whole cluster use Bully for automatic elections?**
   It elects the highest live node quickly and lets a recovered highest node reclaim leadership, which is
   predictable to explain and to test. Ring stays available as a manual algorithm.
8. **Which other experiments depend on this one?**
   Through link L1 the leader is the time daemon, replication primary, MapReduce coordinator and MPI and
   matrix root. Through L2 Experiment 8 fails over on the same suspicion.
