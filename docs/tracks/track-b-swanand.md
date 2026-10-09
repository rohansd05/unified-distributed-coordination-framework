# Track B — Swanand

- **Owner:** Swanand
- **Branch:** `swanand`
- **Reviewer:** Rohan
- **Shared rules:** [docs/tracks/README.md](README.md)

---

## Experiment 4 — Bully and Ring Election (Phase 5, moduleId: election)

- **Reuse:** `legacy-demos/exp04-election` (`ElectionNode`, split into algorithm and transport); Appendix B Exp 4.
- **Special rules:** hard rule 7 (ring election messages are never handled on the listener thread; keep the comment explaining the deadlock); `compareAndSet` prevents overlapping elections.

### Steps

- [X] E4a — Bully and Ring as pure classes, plus the consensus check. Needs: none.
- [X] E4b — election UDP service on `ports().election()` (700k), and the SHARED `core/failure/FailureDetector` (heartbeats on the election channel, 700 ms interval, 2500 ms timeout; publishes suspect and alive events; link L2). Document its API under "Interfaces for other tracks". Needs: E4a.
- [X] E4c — `ElectionModule` (lab 4): start Bully or Ring from node X, the current leader, the consensus check, automatic re-election when the leader crashes; add the cluster roles API (roles on `ClusterNode`, `NodeDto.roles`, "LEADER" shown in the top bar); metrics `distributed_leader_elections_total` and `distributed_election_duration`; fixtures. Needs: E4b.
- [X] E4d — page and end-to-end check. Needs: E4c, E2d.

---

## Experiment 8 — Fault Tolerance (Phase 7, moduleId: faulttolerance)

- **Reuse:** `legacy-demos/exp08-fault-tolerance`; Appendix B Exp 8.
- **Special rules:** built on Track C's replication service and the shared `FailureDetector` (L2); promotion through the election module (no second Bully); epochs; backups refuse stale epochs; a recovering old primary demotes itself; the 220 ms async delay is simulated and labelled; data loss is measured against the acknowledged keys. The legacy demo's own timings (300/1200/800 ms) versus the shared detector's (700/2500 ms) are decided in the E8a plan.

### Steps

- [ ] E8a — epoch rules, `SystemUpdate`, `UpdateStore` and `FailoverMetrics` as pure classes. Needs: E4d.
- [ ] E8b — failover on the replication service plus the `FailureDetector`, promoting through election. Needs: E5b (Track C), E4b, E4c.
- [ ] E8c — `FaultToleranceModule` (lab 8): start and stop an update stream, crash the primary, sync/async toggle, recover the old primary, the four measurements. Needs: E8b.
- [ ] E8d — page and end-to-end check. Needs: E8c, E2d.

---

## Interfaces for other tracks

### E4b APIs: election transport and the shared FailureDetector (link L2)

**`com.udcf.modules.election.ElectionNodeService`** (`NodeService` named `"election"`, also an `ElectionParticipant`)

- `ElectionNodeService.on(ClusterNode node, Cluster cluster, ElectionProperties properties, ClusterEventBus bus)`: the node's service, created and started on first use through `ClusterNode.ensureService`. `ElectionNodeService.find(node)` returns it only if registered.
- One UDP socket on `127.0.0.1:ports().election()` (700k) carries Bully, Ring and heartbeats.
- `startBully()`, `startRing()`: queued on the node's election worker; `NodeDownException` if the service is not running.
- `coordinatorId()` (also `getCoordinatorId()`): the coordinator this node most recently learned from either algorithm (`ELECTED` or `COORDINATOR_ACCEPTED`); `null` if none, and after a crash. `ConsensusChecker.check(services)` works directly on a list of services (a crashed service counts as crashed).
- `addElectionListener(ElectionEventListener)` returns a `Registration` (`close()` removes it): every Bully and Ring event of this node.
- `failureDetector()`: this node's `FailureDetector`.
- **Threading (hard rule 7):** the listener thread only reads, decodes and hands off. Every algorithm step (Bully, Ring, `PROBE`/`PROBE_ACK`), every `ElectionTimer` task and the heartbeat tick run on one worker thread per node, `udcf-election-n<k>-worker`. The transport never calls `onReceive` on the listener thread. Listeners run on the worker: they must not block and must not crash or recover this node (crash joins the worker).
- **Lifecycle:** crash and stop close the socket, then join the listener and the worker (5 s each, `IllegalStateException` if one is still alive), then crash both algorithms (`CRASH` events, crash only). Recover rebinds and calls both algorithms' `recover()`; Bully's starts an election (E4a), so a recovered highest node reclaims leadership.
- **Events** (module `election`, node-clock Lamport):
  - every `ElectionEventType` by name, with data `{algorithm: BULLY|RING}`;
  - `MESSAGE_SENT` and `MESSAGE_RECEIVED` (peer, data `{messageType}`, and on receive `causedByTime`), only for `ELECTION`, `OK`, `COORDINATOR`, `RING_ELECTION` and `RING_COORDINATOR`; never for `PROBE`, `PROBE_ACK` or `HEARTBEAT`.
- **Lamport (L4):** election messages and probes carry `node.clock().tick()` on send; the receiver calls `node.clock().update(t)`. Heartbeats are exempt (see Deviations).
- **Config:** `udcf.election.*` (`ElectionProperties`): `ok-timeout-millis` 900, `coordinator-timeout-millis` 2200, `probe-timeout-millis` 300, `ring-completion-timeout-millis` 5000, `heartbeat-interval-millis` 700, `heartbeat-timeout-millis` 2500. `toElectionConfig()` and `toFailureDetectorConfig()` convert them.
- **Wire format:** `TYPE|senderId|lamportTime|payload`, UTF-8, at most 1024 bytes. Oversize, malformed, unknown-sender and self-sent datagrams are dropped and logged.

**`com.udcf.core.failure.FailureDetector`** (one per node, owned by that node's election service; Exp 8 uses it in E8b)

- Get it with `ElectionNodeService.on(node, cluster, properties, bus).failureDetector()`.
- `addListener(FailureListener)` returns a `Registration`. `FailureListener.onSuspected(int peerId, long silentMillis)` and `onAlive(int peerId, long silentMillis)` are called on the election worker thread and must not block.
- `isSuspected(peerId)`, `suspectedPeers()`, and `peers()`, a list of `PeerHealth(peerId, suspected, Long millisSinceLastHeartbeat)`, where `millisSinceLastHeartbeat` is `null` if never heard since start.
- Every interval it sends a heartbeat to every peer. A peer silent for more than the timeout, counted from its last heartbeat or from start, is suspected; a heartbeat from it makes it alive again.
- Each node has its own view. Only changes are published: `PEER_SUSPECTED` and `PEER_ALIVE` (module `election`, peer, data `{silentMillis, timeoutMillis}`).
- A node's detector stops on crash and restarts with a fresh view (no suspicions) on recover.
- It has no threads of its own: the owner drives `tick()` and `onHeartbeat(peerId)`. A new owner would build it with `new FailureDetector(node, peerIds, config, module, heartbeatSender, bus, nanoClock)`.

### E4c APIs: ElectionModule, the cluster roles API, and the REST endpoints

**Cluster roles (core; only the election module assigns them)**

- `NodeRole` enum: `LEADER` (PRIMARY/BACKUP join in Phase 9A, L1).
- `ClusterNode.roles()` (snapshot), `hasRole(NodeRole)`. Grant and revoke are package-private: only `Cluster.assignLeader` changes roles. A crashed node holds no role: `crash()` clears them, a crashed node is never granted one, and recovery does not restore them.
- `Cluster.leaderId()` returns `Optional<Integer>`; `Cluster.assignLeader(Integer nodeId)` makes that node the only `LEADER`, or removes the leader when `null` or crashed.
- `LEADER_CHANGED` (module `cluster`, node 0, cluster clock, peer = new leader, data `{leaderId, previousLeaderId}`) is published on every change, including when `Cluster.crash(id)` takes the leader down. It goes to `/topic/cluster`, so the frontend's `ClusterProvider` refetches `GET /api/cluster` and the top bar updates.
- `NodeDto.roles`: role names, `["LEADER"]` on the leader, `[]` elsewhere and on every node before the first election. Frontend helper: `isLeader(node)` in `frontend/src/lib/clusterStatus.js` (case-insensitive).
- Locks: roles use their own lock per node, never the node's lifecycle lock; `assignLeader` takes `leaderLock`, then roles locks, then the bus publish lock, and none of them is ever held while a thread is joined. An election worker may therefore assign the leader while `Cluster.crash` is joining it.

**`ElectionModule`** (id `election`, lab 4, "Bully and Ring Election")

- **Lazy start.** Nothing starts the election services at boot, so the FailureDetector does not run until the first election request. E8b, and any module that needs the detector, must start the services itself with `ElectionNodeService.on(node, cluster, properties, bus)` on every node, then use `failureDetector().addListener(...)`.
- Leader rule: when every live node agrees on one live coordinator (after an ELECTED or COORDINATOR_ACCEPTED event), that node becomes the cluster leader. Otherwise the leader stays as it is (sticky); only a crash of the leader or a reset removes it.
- Automatic re-election (L2): a node whose detector suspects the leader it knows starts Bully; exactly one `LEADER_FAILURE` round opens however many nodes detect it. A recovered node runs Bully (E4a), a `RECOVERY` round. A node that was down when the services first started gets its service when it recovers (bus `NODE_RECOVERED` handler on the event dispatcher thread, which delivers asynchronously).
- Rounds: one open at a time. They end `ELECTED` (measured) or `TIMED_OUT` after `udcf.election.round-timeout-millis` (10000 ms; unmeasured, no metric). Events `ELECTION_ROUND_STARTED` and `ELECTION_ROUND_FINISHED` (module `election`, data `{roundId, algorithm, trigger, initiatorNodeId, outcome, leaderId, durationMillis}`).
- Status: BUSY while a round is open or an action runs; RUNNING once election services run; IDLE before the first election.
- `reset()`: halts every election service (joins all threads, drops pending timers), clears the rounds and the leader, then gives every service fresh algorithms and a fresh detector view and reopens those that were running. Starts no election; ignores `NODE_RECOVERED` events published before it.
- `ElectionNodeService.halt()` returns whether the service was running; `resetState(boolean reopen)` rebuilds it (both E4c additions).

**REST** (`/api/modules/election`; errors are the shared ProblemDetail)

- `GET` returns `{status, leaderId, servicesStarted, nodes[{nodeId, status, serviceRunning, coordinatorId, suspectedPeers, port}], consensus, currentRound, lastRound, settings}`. It never starts a service.
- `POST /elections` with `{"algorithm":"BULLY"|"RING","nodeId":k}` answers **202** with the round `{roundId, algorithm, trigger, initiatorNodeId, startedAt, outcome, leaderId, durationMillis}`. 400 for invalid input, 404 for an unknown node, 409 for a node that is down or a module that is busy (`moduleId`, `actionInProgress`).
- `GET /consensus` returns `{reached, coordinatorId, coordinatorAlive, passed, disagreeingNodes}`. `passed = reached && coordinatorAlive`; a live node without a running election service counts as disagreeing.
- Crash and recover use the cluster API (`POST /api/cluster/nodes/{id}/crash|recover`).
- Contract fixtures: `frontend/src/test/fixtures/election/` (see its README).

**Metrics** (R5): `distributed_leader_elections_total{node_id = elected leader, algorithm, trigger}` and `distributed_election_duration{node_id = initiating node, algorithm, trigger}` (Timer). Timed-out rounds record nothing.

Linux (Docker, --cpus=2): FailureDetectorTest, Election*Test and ClusterRolesTest (82 tests) passed 5 runs in a row; full backend suite 893 tests, 0 failures, 1 skipped.

### E4d APIs: additions to the overview and the event messages

- `GET /api/modules/election` `nodes[]` also has `electionsWon` (rounds the node won: the sum of `distributed_leader_elections_total{node_id}`, whose node_id is the **elected leader**; 0 is a real count), `roundsTimed` (rounds started from the node that ended ELECTED: the count of `distributed_election_duration{node_id}`, whose node_id is the **initiating node**; TIMED_OUT rounds are not recorded) and `meanDurationMillis` (their mean, `null` when `roundsTimed` is 0; no max, because a Micrometer timer's max decays). All since the backend started; read with `registry.find(...)` only, so reading never creates a meter.
- Election event messages are sentence case with no all-capitals word (for example "Election message to node 4", "Answer from node 5", "Accepted node 5 as coordinator"). Event types and `data` are unchanged; E4a is unchanged (the transport writes the text).
- `LEADER_CHANGED` stays only in the cluster log (module `cluster`); the election page reads it from `GET /api/events?module=cluster` and `/topic/cluster`. It is not copied into the election log, so the Phase 9A timeline and MapReduce see it once.
- Frontend: `frontend/src/modules/election/` (`electionApi`, `useElection`, `useLeaderChanges`, `electionModel`, `labels`). `isLeader` stays in `lib/clusterStatus.js`.
- Linux (Docker, --cpus=2): election subset (<n></n> tests) passed 5 runs in a row; full backend suite <total></total> tests, 0 failures, 1 skipped.

### E4a: Election Events

The `ElectionEventType` enum exposes these algorithm-level statuses:

- `ELECTION_START`: The node began its own election.
- `ELECTION_RESTART`: The node restarted an ongoing election.
- `OK_RECEIVED`: Bully node received an OK and is waiting for a coordinator.
- `WAITING_FOR_COORDINATOR`: The node is waiting for the result.
- `ELECTED`: The node elected itself as coordinator.
- `COORDINATOR_ACCEPTED`: The node accepted another node as coordinator.
- `TOKEN_FORWARDED`: Ring node forwarded an election token.
- `DEAD_NODE_SKIPPED`: Ring node successfully skipped an unreachable successor.
- `ELECTION_TIMEOUT`: An election timed out without concluding.
- `CRASH`: The node was explicitly crashed.
- `RECOVER`: The node recovered from a crash.
- `UNKNOWN_SENDER`: An unrecognized message was received.
- `LATE_MESSAGE`: A message for a stale election was safely ignored.

---

## Deliberate differences from the legacy code

- **Legacy deviation (Lower-ID Coordinator Acceptance):** When a Bully node receives a `COORDINATOR` announcement, it unconditionally accepts it, even if the announcing node has a lower ID than itself. This faithfully ports a quirk from the legacy `exp04-election` demo.
- **Callback-Driven Probing (No Executor, flat package, no threads):** To prevent deadlocks, the Ring liveness prober is entirely asynchronous. Unlike the legacy demo which blocked the listener thread, it uses an exactly-once callback mechanism. This allows the removal of executors entirely, making the algorithms completely unthreaded. We use a flat package (`com.udcf.modules.election`) to easily share common interfaces among these highly-cohesive primitives.

---

## Requirements Coverage (E4a)

| Requirement                                 | Implementation / Rule                                                      | Test                                                                                                 |
| ------------------------------------------- | -------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Bully election start & message sequence     | Sends`ELECTION` to higher nodes, schedules OK timeout                    | `BullyAlgorithmTest.testLegacyMessageSequence`                                                     |
| Bully output order preserved                | Emits`ELECTION_START` before sending outbound messages                   | `BullyAlgorithmTest.testOutputOrderPreserved`                                                      |
| Bully highest node self-promotes            | Immediate victory when no higher nodes exist                               | `BullyAlgorithmTest.testHighestNodeStartsAndWins`                                                  |
| Bully OK response handling                  | Stand down from self-promotion, wait for coordinator                       | `BullyAlgorithmTest.testOkMeansStandDownAndCoordinatorTimeoutRestarts`                             |
| Bully OK timeout expiration                 | Self-promotes to coordinator if no OK received                             | `BullyAlgorithmTest.testNoOkMeansSelfPromotion`                                                    |
| Bully coordinator timeout restart           | Restarts election if coordinator announcement times out                    | `BullyAlgorithmTest.testOkMeansStandDownAndCoordinatorTimeoutRestarts`                             |
| Bully lower-ID coordinator quirk            | Unconditionally accepts lower ID coordinator (legacy demo quirk)           | `BullyAlgorithmTest.testLegacyLowerIdCoordinator`, `testLowerIdCoordinatorQuirk`                 |
| Bully late OK ignored                       | Late OK when not in progress emits`LATE_MESSAGE`                         | `BullyAlgorithmTest.testLateOkIgnored`                                                             |
| Bully stale OK timer does nothing           | Old OK timeout fired after receiving OK or after new election does nothing | `BullyAlgorithmTest.testStaleTimers`, `testOldOkTimeoutFiredAfterNewElectionStartedDoesNothing`  |
| Bully timer after crash does nothing        | OK timer and coordinator timer do nothing after crash                      | `BullyAlgorithmTest.testTimerAfterCrashDoesNothing`                                                |
| Bully crash & recovery                      | Crash clears state; recover starts election clean                          | `BullyAlgorithmTest.testCrashClearsState`, `testHighestNodeRecovers`                             |
| Bully crash between collecting and sending  | Actions loop aborts if crashed; sends nothing further                      | `BullyAlgorithmTest.testCrashBetweenCollectingAndSendingSendsNothingFurther`                       |
| Bully Lamport clock usage                   | Lamport clock ticks on send/event and updates on receive                   | `BullyAlgorithmTest.testLamportUsage`                                                              |
| Bully concurrent election guard             | `compareAndSet` prevents overlapping elections                           | `BullyAlgorithmTest.testConcurrentStartElection`                                                   |
| Bully in-progress flag release              | Flag released on completion, timeout, crash, and exceptions                | `BullyAlgorithmTest.testInProgressFlagReleased`                                                    |
| Bully unknown sender                        | Unknown message type handled safely without crashing                       | `BullyAlgorithmTest.testUnknownSenderIgnored`                                                      |
| Bully lock reentrancy & action collection   | Collect actions under lock, execute outside lock                           | `ReentrancyTest.testReentrancyAndCrashDuringExecution`                                             |
| Ring single node ring                       | Ring of one node times out safely without infinite loop                    | `RingAlgorithmTest.testRingOfOne`                                                                  |
| Ring two node token exchange                | Token passed between two nodes elects coordinator                          | `RingAlgorithmTest.testRingOfTwo`                                                                  |
| Ring legacy token forwarding                | Appends node ID and forwards to successor                                  | `RingAlgorithmTest.testLegacyTokenSequence`                                                        |
| Ring full circle completion                 | Full circle detected when ID in token; highest wins                        | `RingAlgorithmTest.testHighestWinsAfterFullCircle`, `testTokenAlreadyContainsReceiver`           |
| Ring coordinator circulation stop           | Result token circulating stops at originator node                          | `RingAlgorithmTest.testRingCoordinatorStopsAtOriginator`                                           |
| Ring concurrent originators                 | Multiple concurrent tokens resolve to highest node                         | `RingAlgorithmTest.testTwoOriginatorsHighestWins`                                                  |
| Ring dead successor skip                    | Probe timeout skips dead node and tries next in ring                       | `RingAlgorithmTest.testSingleThreadedListenerCompletesWithDeadSuccessor`, `testAllOtherDead`     |
| Ring callback liveness prober (Hard rule 7) | Non-blocking probe completes callback exactly once                         | `DefaultLivenessProberTest.testAckBeforeTimeout`, `testTimeoutBeforeAck`                         |
| Ring no event for PROBE/PROBE_ACK           | PROBE and PROBE_ACK emit no election events                                | `RingAlgorithmTest.testNoEventForProbeOrProbeAck`                                                  |
| Ring lost token completion timeout          | Completion timeout clears in-progress flag and allows restart              | `RingAlgorithmTest.testLostToken`                                                                  |
| Ring stale completion timer does nothing    | Old completion timer fired after new election does nothing                 | `RingAlgorithmTest.testOldCompletionTimeoutFiredAfterNewElectionStartedDoesNothing`                |
| Ring timer after crash does nothing         | Completion timer fired after crash does nothing                            | `RingAlgorithmTest.testTimerAfterCrashDoesNothing`                                                 |
| Ring in-progress flag release               | Flag released on coordinator, timeout, crash, and exceptions               | `RingAlgorithmTest.testInProgressFlagReleased`                                                     |
| Ring Lamport clock tracking                 | Lamport clock ticks on forward and updates on receive                      | `RingAlgorithmTest.testLamportClock`                                                               |
| Ring crash between collecting and sending   | Actions loop aborts if crashed; sends nothing further                      | `RingAlgorithmTest.testCrashBetweenCollectingAndSendingSendsNothingFurther`                        |
| Ring reentrancy safety                      | Reentrant delivery does not deadlock                                       | `ReentrancyTest.testRingReentrancyNoDeadlock`                                                      |
| Consensus checker                           | Agreement, disagreement, dead leader, unassigned, all crashed              | `ConsensusCheckerTest` (8 tests)                                                                   |
| Message wire format & limit                 | Pipe-delimited; datagrams > 1024 characters rejected                       | `ElectionMessageTest.testWireFormat`, `testMalformed`, `testMessageOver1024CharactersRejected` |
| Election config validation                  | Positive timeouts required, probe timeout < ok timeout                     | `ElectionConfigTest` (3 tests)                                                                     |

---

## Requirements Coverage (E4b)

| Requirement                                                                       | Implementation / Rule                                                                        | Test                                                                                                                                                          |
| --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Election UDP service on`ports().election()`, bound to 127.0.0.1                 | `ElectionNodeService.open()`; no `SO_REUSEADDR`, so a collision fails loudly             | `ElectionNodeServiceTest.startBindsLoopbackElectionPort`, `portCollisionFailsStartLoudly`                                                                 |
| Crash and stop join every thread and free the port                                | Close socket, join listener,`shutdownNow` and await the worker, join its thread (5 s each) | `ElectionNodeServiceTest.crashClosesSocketJoinsListenerAndFreesPort`, `stopJoinsThreadsAndReleasesPort`                                                   |
| Recover rebinds; lifecycle idempotent; follows the node (R10)                     | `recover()` reopens and recovers both algorithms                                           | `ElectionNodeServiceTest.recoverRebindsAndRunsAgain`, `crashAndRecoverAreIdempotent`, `lifecycleFollowsClusterNodeCrashAndRecover`                      |
| Elections refused on a node that is down                                          | `NodeDownException`                                                                        | `ElectionNodeServiceTest.startElectionOnCrashedServiceThrowsNodeDown`                                                                                       |
| Listener survives malformed, oversize, unknown-sender datagrams and ICMP refusals | Log and continue while the socket is open                                                    | `ElectionNodeServiceTest.listenerSurvivesMalformedAndOversizeDatagrams`, `listenerSurvivesPortUnreachable`                                                |
| Hard rule 7: no algorithm work on the listener thread                             | Listener reads, decodes, hands off to`udcf-election-n<k>-worker`                           | `ElectionNodeServiceTest.algorithmWorkRunsOnWorkerNeverOnListenerThread`                                                                                    |
| L4 on election messages                                                           | Send stamps`node.clock().tick()`, receive calls `update(t)`                              | `ElectionNodeServiceTest.receivedMessageAdvancesReceiverClockPastSenderStamp`, `ElectionOverUdpTest.everyElectionMessageEmitsSentAndReceivedEvents`       |
| Heartbeats exempt from L4 (deviation)                                             | Heartbeat sent with 0, never applied                                                         | `ElectionNodeServiceTest.heartbeatsDoNotTouchNodeClock`, `ElectionMessageTest.testHeartbeatRoundTrip`                                                     |
| Bully over real UDP; highest live node wins; recovered highest reclaims           | E4a`BullyAlgorithm` on the transport                                                       | `ElectionOverUdpTest.bullyElectsHighestLiveNodeAndConsensusHolds`, `bullyWithHighestCrashedElectsNextHighest`, `recoveredHighestNodeReclaimsLeadership` |
| Ring over real UDP skips a dead node                                              | E4a`RingAlgorithm` and callback prober                                                     | `ElectionOverUdpTest.ringElectsHighestAndSkipsCrashedSuccessor`                                                                                             |
| MESSAGE_SENT/RECEIVED only for the five election types                            | `PUBLISHED_TYPES`                                                                          | `ElectionOverUdpTest.everyElectionMessageEmitsSentAndReceivedEvents`                                                                                        |
| Failure detector: heartbeats, suspect after timeout, alive again (L2)             | `FailureDetector.tick()` / `onHeartbeat()`                                               | `FailureDetectorTest` (12 tests), `ElectionOverUdpTest.detectorOnEveryLiveNodeSuspectsCrashedPeerThenSeesItAlive`                                         |
| Config from YAML (Appendix B, ring completion 5000 ms)                            | `ElectionProperties`, `udcf.election` block                                              | `ElectionPropertiesTest` (3 tests)                                                                                                                          |

---

## Requirements Coverage (E4c)

| Requirement                                                                        | Implementation / Rule                                                                        | Test                                                                                                                                                                                                                                                                   |
| ---------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Start Bully or Ring from node X (202), lazy start of every service                 | `ElectionModule.startElection`, `ElectionController`                                     | `ElectionModuleTest.manualBullyElectsHighestAssignsLeaderAndRecordsMetrics`, `idleBeforeFirstElectionAndOverviewStartsNothing`, `ElectionControllerTest.postStartsBullyReturns202ThenLeaderAppearsInClusterApi`                                                  |
| Current leader as the cluster role LEADER; one leader; none on a crashed node      | `Cluster.assignLeader`, `ClusterNode` roles, `NodeDto.roles`                           | `ClusterRolesTest` (8 tests)                                                                                                                                                                                                                                         |
| LEADER_CHANGED on every change, including a leader crash                           | `Cluster.assignLeader`, `Cluster.crash`                                                  | `ClusterRolesTest.assignLeaderGrantsOnlyOneLeaderAndPublishesChange`, `crashClearsRolesAndPublishesLeaderLost`                                                                                                                                                     |
| No deadlock between leader assignment and a crash joining election threads         | Roles lock separate from the lifecycle lock                                                  | `ClusterRolesTest.crashRacingWithLeaderAssignmentDoesNotDeadlock`                                                                                                                                                                                                    |
| Consensus check: live nodes agree on one live leader                               | `ElectionModule.consensus` (E4a checker + liveness)                                        | `ElectionModuleTest.consensusPassesThenFailsWhenLeaderCrashes`, `ElectionControllerTest.consensusEndpointShape`                                                                                                                                                    |
| Automatic re-election when the leader crashes (L2)                                 | Detector listener starts Bully; LEADER_FAILURE round                                         | `ElectionModuleTest.crashingLeaderTriggersAutomaticReElection`                                                                                                                                                                                                       |
| Exactly one round for simultaneous detections                                      | `ElectionRoundTracker.open` (atomic)                                                       | `ElectionRoundTrackerTest.concurrentOpenersOpenExactlyOneRound`, `ElectionModuleTest.concurrentLeaderSuspicionsOpenExactlyOneRound`                                                                                                                                |
| Ring skips a dead node                                                             | E4a Ring over E4b transport                                                                  | `ElectionModuleTest.ringSkipsDeadNodeAndElectsHighest`                                                                                                                                                                                                               |
| Recovered highest node reclaims leadership                                         | E4a Bully on recovery; NODE_RECOVERED handler                                                | `ElectionModuleTest.recoveredHighestNodeReclaimsLeadership`, `nodeCrashedBeforeFirstElectionJoinsWhenRecovered`                                                                                                                                                    |
| 409 busy, 409 node down, 404 unknown node, 400 invalid                             | Guard + open round; shared handler                                                           | `ElectionModuleTest.secondStartWhileRoundOpenIsBusy`, `startFromCrashedOrUnknownNodeFails`, `ElectionControllerTest` (4 tests)                                                                                                                                   |
| Metrics with node_id, algorithm, trigger; timed-out rounds record nothing (R5, R7) | `ElectionMetrics`                                                                          | `ElectionMetricsTest` (3 tests), `ElectionModuleTest.overdueRoundTimesOutWithoutMetrics`                                                                                                                                                                           |
| Round timeout                                                                      | `ElectionRoundTracker.expireIfOverdue`, `round-timeout-millis`                           | `ElectionRoundTrackerTest.expiresOverdueRoundAsTimedOutWithNullDuration`, `ElectionPropertiesTest.rejectsRoundTimeoutNotAboveElectionTimeouts`                                                                                                                     |
| Clean-slate reset; earlier recoveries ignored                                      | `ElectionModule.reset`, `ElectionNodeService.halt/resetState`, `FailureDetector.reset` | `ElectionModuleTest.resetClearsLeaderRoundAndCoordinatorsAndIgnoresEarlierRecoveries`, `ElectionNodeServiceTest.haltJoinsThreadsAndReportsWasRunning`, `resetStateClearsCoordinatorAndReopens`, `FailureDetectorTest.resetGivesFreshViewWithoutChangingActive` |
| LEADER shown in the top bar and on the topology                                    | `isLeader` in `clusterStatus.js`, `TopBar.jsx`, `ClusterGraph.jsx`                   | `clusterStatus.test.js`, `TopBar.test.jsx` (real fixtures), `ClusterGraph.test.jsx`                                                                                                                                                                              |
| Contract fixtures                                                                  | Captured live with curl.exe                                                                  | `frontend/src/test/fixtures/election/README.md`                                                                                                                                                                                                                      |

---

## Requirements Coverage (E4d)

| Requirement                                                                                                  | Implementation / Rule                             | Test                                                                                                                 |
| ------------------------------------------------------------------------------------------------------------ | ------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| Page on the shared kit, sections in order, registered                                                        | `ElectionPage`, `index.jsx`                   | `ElectionPage.test.jsx` (registered, sections in order)                                                            |
| Start Bully or Ring from node X; crash and recover with no confirmation                                      | `ElectionControls`, cluster API                 | `ElectionControls.test.jsx`                                                                                        |
| Leader from cluster roles via`isLeader`, never page state                                                  | `leaderIdFrom`, `ElectionRing`                | `ElectionRing.test.jsx`, `ElectionPage.test.jsx` (leader from cluster roles)                                     |
| Messages on the ring with real Lamport values; motion only when allowed                                      | `roundMessages`, `ElectionRing`               | `electionModel.test.js`, `ElectionRing.test.jsx`                                                                 |
| Detector idle until the first election; Ring with one live node; TIMED_OUT shown with the configured timeout | `RoundPanel`, `ElectionControls`              | `RoundPanel.test.jsx`, `ElectionControls.test.jsx`                                                               |
| LEADER_FAILURE duration labelled "measured from detection"                                                   | `DETECTION_NOTE`                                | `labels.test.js`, `RoundPanel.test.jsx`                                                                          |
| Per-node figures (R5), "since the backend started"; unmeasured shows "—"                                    | `ElectionNodeDto` fields, measurements          | `ElectionMetricsTest`, `ElectionModuleTest.overviewReportsPerNodeWinsAndMeanDuration`, `ElectionPage.test.jsx` |
| Leader changes (cluster log), real Lamport values, not via ModuleEventLog                                    | `useLeaderChanges`, `LeaderChanges`           | `useLeaderChanges.test.js`, `ElectionPage.test.jsx`, `contract.test.js`                                        |
| Coalesced refresh, no polling; one timer at the round timeout                                                | `useElection`                                   | `useElection.test.js` (fake timers)                                                                                |
| Announcements and focus after start                                                                          | `usePoliteAnnouncement`, `RoundPanel` heading | `ElectionPage.test.jsx`                                                                                            |
| No all-capitals words (page and event messages)                                                              | sentence-case labels and backend texts            | `ElectionPage.test.jsx`, `contract.test.js`, `ElectionOverUdpTest.eventMessagesHaveNoAllCapsWords`             |
| Contract against real captures                                                                               | `contract.test.js`                              | 23 fixtures in`frontend/src/test/fixtures/election/`                                                               |

---

## Deviations

- L4: heartbeats carry no Lamport stamp and do not advance the clock. Reason: at about 11 ticks per second per node (5 nodes) the Lamport values shown by Experiment 3 would change without any user action. Awaiting Rohan's sign-off at PR review.

---

## Known issues

- `MultithreadingModuleTest.backpressureIsDeterministicAndGuarded` (Track A's test) failed once in one of three runs (expected 5, was 9). Not edited, as it is unrelated to the election module and our tests are completely pure without static state, threads, or sleep loops.
- E4b: nothing in production starts `ElectionNodeService` yet; E4c's `ElectionModule` will start it on every node. A node whose election service is not running is indistinguishable from a dead one, so every node's service must start together, or the started ones suspect the others after 2500 ms.
- E4b: the E4a algorithms still keep a private Lamport counter. The transport replaces it on the wire and in every event with the node clock, so the private value is never published (no E4a edit, by decision).
- E4b: E4a's `getCoordinatorId()` reads a non-volatile field. The transport only reads it on the worker thread and exposes its own volatile `coordinatorId()`; other code should use that (no E4a edit, by decision).
- E4b: a Ring election started when every other node is dead never elects anyone (E4a ring-of-one behaviour): it ends in `ELECTION_TIMEOUT` after 5000 ms. E4c should use Bully for automatic re-election.
- E4b: verified on Windows only (three full runs). The five Linux (Docker, 2 CPUs) runs of the new classes are still to be done by Swanand.
- E4c: a Ring election with only one live node never elects anyone and its round ends TIMED_OUT after `round-timeout-millis` (10000 ms), unmeasured. Automatic re-election therefore always uses Bully.
- E4c: the duration of a LEADER_FAILURE round is measured from the detection (the first node's failure detector suspecting the leader, about 2500 ms after the crash), not from the crash itself.
- E4c: nothing starts the election services at boot, so the FailureDetector is idle until the first election request. E8b and any other module that needs the detector must start the services itself with `ElectionNodeService.on(...)`.
- E4c: between a leader crash and the agreement on a new leader (about 2.5 to 3 s with the default timings) the cluster has no LEADER; the top bar shows "None elected" during that gap.
- E4c: verified on Windows only (three backend and three frontend runs). The Linux (Docker) runs of the new classes are still to be done by Swanand.
- E4d: `GET /api/modules/election` closes a timed-out round lazily, only when it is read (also by `status()`). The page therefore refreshes once at the overview's `roundTimeoutMillis` + 250 ms while a round is open; nothing else polls.
- E4d: the per-node figures count since the backend started; a cluster reset does not reset them (Prometheus counter semantics), and the page says so.
- E4d: the 1280x800 and 375x740 checks were run in same-origin iframes of exactly those CSS sizes (the Chrome window could not be set to an exact viewport at 125 % display scaling). Keyboard-only use and reduced motion were checked by the component tests, not by hand.

---

## Progress log

- 2026-10-07 track file created.
- 2026-10-08 E4a completed (baseline 525, total 579; 54 election tests covering all scenarios including stale timers, crash safety, 1024-char limit, probe event absence, and action loop crash guards).
- 2026-10-09 E4b done: election UDP transport (`ElectionNodeService`, worker-only algorithm work, joined lifecycle) and the shared `core/failure/FailureDetector` (700/2500 ms heartbeats, PEER_SUSPECTED/PEER_ALIVE); backend 852 tests (baseline 817 + 35), frontend not run (no frontend change); deviations: heartbeats exempt from L4 (awaiting Rohan's sign-off), `HEARTBEAT` added to E4a's `ElectionMessageType` (approved).
- 2026-10-09 E4c done: `ElectionModule` (lab 4: Bully/Ring from any node, sticky LEADER role, consensus check, automatic Bully re-election on leader failure, rounds with timeout, clean-slate reset), cluster roles API (`NodeRole`, `ClusterNode.roles`, `Cluster.assignLeader`, `LEADER_CHANGED`, `NodeDto.roles`), REST, metrics, 22 live contract fixtures, `isLeader` in top bar and topology; backend 893 tests (baseline 852 + 41), frontend 391 tests (baseline 388 + 3); deviations: none beyond the approved plan (added `ElectionRound` record; `ElectionNodeService.halt/resetState` and `FailureDetector.reset` as approved in Q7).
- 2026-10-09 E4d done: Election page (node ring with live message arrows, result panel with honest states, leader changes from the cluster log, per-node figures, crash and recover without confirmation), sentence-case election event messages, per-node `electionsWon`/`roundsTimed`/`meanDurationMillis` in the overview, fixtures recaptured plus `overview-ring-timed-out.json`; backend 897 tests (baseline 893 + 4), frontend 537 tests (baseline 464 + 73); deviations: extra fixture `overview-ring-timed-out.json`; shared edits `registry.test.js` and `ExperimentPage.test.jsx` (election to matrix, both approved), `experiments.js` concept and `routes.test.jsx` spies (add-only).
