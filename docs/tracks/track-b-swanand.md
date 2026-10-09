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

- [X] E8a — epoch rules, `SystemUpdate`, `UpdateStore` and `FailoverMetrics` as pure classes. Needs: E4d.
- [X] E8b — failover on the replication service plus the `FailureDetector`, promoting through election. Needs: E5b (Track C), E4b, E4c.
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

### E8a interfaces: fault tolerance as pure classes (`com.udcf.modules.faulttolerance`), for E8b and E8c

No sockets, threads, timers, Spring or ports. Nothing reads a clock: every instant is a `long …Nanos` parameter, stamped by the caller. **All stampers in the module must use one shared `LongSupplier` nano-clock instance**, so instants from different threads compare.

Linux (Docker, --cpus=2): the faulttolerance classes (124 tests) passed 5 runs in a row; full backend suite <total></total> tests, 0 failures, 1 skipped.

**Reused, not duplicated (Track C, `com.udcf.modules.replication`):** `DataStore` is the store and the write fence (no `UpdateStore`); `DataItem` is the stamped update; `ConsistencyModel` is the sync/async enum; `WriteResult` feeds the ledger and carries the simulated delay. `DataStore.INITIAL_EPOCH` is 1.

**Epoch rules**

- `FailoverRole { PRIMARY, BACKUP }` (module-local; liveness is separate). `EpochVerdict { REFUSE_STALE, ACCEPT, ADOPT_HIGHER }`.
- `EpochRules` (static): `EpochVerdict judge(long knownEpoch, long incomingEpoch)` (lower refused, **equal accepted as the same term**, higher adopted; matches `DataStore.apply`, checked by a test); `long afterObserving(long known, long observed)` (never decreases); `boolean mustDemote(FailoverRole role, long primaryEpoch, long observedEpoch)`; `Optional<Integer> currentPrimary(List<RoleReport>)`; `RejoinDecision resolveRejoin(int selfId, FailoverRole ownRole, long ownEpoch, List<RoleReport> replies)` (`ownRole` = the role held before the crash).
- `RoleReport(int nodeId, FailoverRole role, long epoch, Integer believedPrimaryId)`: the answer to a role query. `RejoinDecision(Action action, long epoch, Integer primaryId)` with `Action { STAY, DEMOTE_AND_RESYNC, ADOPT_AND_RESYNC, NO_ANSWER }`, `resync()`, `viewConfirmed()`.
- `EpochAuthority` (synchronized): `Optional<Promotion> onLeaderElected(int nodeId, long observedAtNanos)` (**the seam for the elected primary, `// TODO(L1)`**); `long observeEpoch(long)`; `long highestEpoch()`; `Optional<Promotion> current()`; `reset()`. Epoch = `max(last issued, highest observed) + 1`, highest observed starting at `INITIAL_EPOCH`, so the first promotion is 2 and no promotion epoch ever equals 1, an observed epoch or an issued one. A result naming the current primary, or observed before the promotion in force, returns empty. A leader change with no failure (planned handover) is epoch + 1 with no failover run.
- `Promotion(int nodeId, long epoch, Integer previousPrimaryId, long observedAtNanos)`; `epoch > INITIAL_EPOCH` is enforced.

**Failover state machine and measurements**

- `FailoverPhase { STEADY, SUSPECTED, PROMOTING, RESTORED }` (a crash leaves the phase at STEADY: the cluster has not noticed yet). `InstantSource { ACTION, OBSERVED }`.
- `FailoverStateMachine(int historyLimit)` (synchronized). Every event returns `EventResult { APPLIED, IGNORED, REJECTED_OUT_OF_ORDER }`: `onPrimaryCrashed(int nodeId, InstantSource source, long atNanos)`, `onSuspected(int observerId, int suspectedId, long atNanos)`, `onPrimaryAlive(int nodeId, long atNanos)`, `onPromotionChosen(Promotion)`, `onPromotionFailed(int nodeId, long atNanos)`, `onPromoted(int nodeId, long epoch, long atNanos)`, `onWriteAccepted(int nodeId, long epoch, long atNanos)`, `onOldPrimaryRecovered(int nodeId, long atNanos)`, `onDemoted(int nodeId, long epoch, long atNanos)`, `onResynchronised(int nodeId, long atNanos)`. Queries: `phase()`, `primaryId()`, `primaryEpoch()`, `latestRun()`, `runs()`, `rejectedEventCount()`, `reset()`.
- `FailoverRun(...)`: run id, old primary and epoch, crash instant and its `InstantSource`, detection instant and observer, elected, new primary and epoch, promoted, restored, recovered, demoted, resynced (each `Long`, null if it never happened), `Outcome { IN_PROGRESS, RESTORED, PRIMARY_RETURNED, INTERRUPTED }`, `measurements()`.
- `FailoverMeasurements(Double detectionMillis, Double failoverMillis, Double serviceRestoredMillis, Double outageMillis, Double recoveryMillis)`: crash to detection; detection to promoted; promoted to first accepted write; crash to first accepted write (directly, not a sum); old primary recovered to demoted and resynchronised (the later). Incomplete intervals are null, never 0. Detection with the shared detector (700 ms / 2500 ms) is **estimated** at 1.8 to 3.2 s after the crash until E8c measures it.

**Data loss**

- `SystemUpdate(int sequence, String key, String value)`, `numbered(seq)` gives `setting-0001` / `v1` (`Locale.ROOT`).
- `AcknowledgedLedger` (synchronized): `AcknowledgedUpdate record(int sequence, WriteResult result, long acknowledgedAtNanos)`, `snapshot()`, `size()`, `contains(key)`, `clear()`. `AcknowledgedUpdate(int sequence, DataItem item, ConsistencyModel model, long simulatedDelayMillis, long acknowledgedAtNanos)`.
- `DataLoss.measure(List<AcknowledgedUpdate>, Map<String, DataItem> newPrimaryStore)` returns `DataLossReport(int acknowledged, Integer lost, List<String> lostKeys, Integer lostSynchronous, Integer lostAsynchronous, boolean simulated, long simulatedDelayMillis, NotAssessed notAssessed)`. A key is lost if it is missing or held at an older version. Nothing acknowledged or no primary store: counts null with `NOTHING_ACKNOWLEDGED` / `NO_PRIMARY_STORE`. `simulated` and `simulatedDelayMillis` come from the reported `WriteResult.simulatedDelayMillis` (450 today), never a typed value.

**Client retry**

- `UpdateRetryPolicy(int maxAttempts, long retryDelayMillis)`, `UpdateAttempt begin(Integer knownPrimaryId)`. `UpdateAttempt` (one update, one thread): `first()`, `after(AttemptOutcome)`, `afterDiscovery(Optional<Integer>)`, `attempts()`, `last()`. `AttemptOutcome(Kind { ACCEPTED, NOT_PRIMARY, UNREACHABLE }, int nodeId, Integer primaryHint)`. `RetryDecision(Action { SEND, DISCOVER, DONE, GIVE_UP }, Integer targetNodeId, long delayMillis)`. Redirects are followed at once unless they point back at a node that already redirected this update; every send and discovery uses an attempt; then `GIVE_UP`.

**Split brain**

- `SplitBrainChecker.check(List<NodeRoleSnapshot>)` returns `SplitBrainReport(boolean passed, Long highestEpoch, List<Integer> livePrimaries, List<SplitBrainViolation> violations)`. `NodeRoleSnapshot(int nodeId, boolean alive, FailoverRole role, long epoch)`. `SplitBrainViolation(Kind { DUPLICATE_PRIMARY, STALE_PRIMARY }, List<Integer> nodeIds, long epoch, long highestEpoch)`. Strict: either kind fails `passed`; crashed nodes are ignored; no primary is not a violation. A live stale primary should never appear (see note 3 below); if it does, it is a bug.

**Concurrency and lock order:** `EpochRules`, `SplitBrainChecker`, `DataLoss` and the records are stateless; `UpdateRetryPolicy` is immutable; `EpochAuthority`, `FailoverStateMachine` and `AcknowledgedLedger` each hold one intrinsic lock and never call out (no listeners, no other E8a class, no `DataStore` lock) while holding it, so they are leaf locks: E8b may call them while holding its own lock, never the reverse. `UpdateAttempt` is confined to one thread.

**What E8b must wire**

- Start the election services itself with `ElectionNodeService.on(node, cluster, properties, bus)` on every node: the shared FailureDetector is idle until the first election request (E4c lazy start). Then `failureDetector().addListener(...)`: `onSuspected(peer == primary)` maps to `FailoverStateMachine.onSuspected`, `onAlive` to `onPrimaryAlive`. Listeners run on the election worker and must not block.
- Stamp every instant from the one shared `LongSupplier`. E8c's crash action stamps `InstantSource.ACTION` immediately before `Cluster.crash(primary)`; a crash seen only through `NODE_CRASHED` is stamped `OBSERVED` on delivery.
- Call `EpochAuthority.observeEpoch(service.epoch())` for every live node before the first promotion and on every role-query answer; then `becomePrimary(promotion.epoch())`, and `onPromoted` right after it returns.
- Feed the ledger from every confirmed `WriteResult` (`record(sequence, result, now)`), and `onWriteAccepted` from the stream.
- Events to publish (module `faulttolerance`, sentence case, no all-capital words in messages; do not repeat what the replication module already publishes, such as `PRIMARY_ACTIVE` and `PRIMARY_SUPERSEDED`): E8b `PRIMARY_SUSPECTED` (observer, peer = primary), `PRIMARY_PROMOTED` (data `{epoch, previousPrimaryId}`), `SERVICE_RESTORED`, `ROLE_QUERY` (data `{action, epoch, primaryId, replies}`), `PRIMARY_DEMOTED`, `RESYNCHRONISED`; E8c `STREAM_STARTED`, `STREAM_STOPPED`, `PRIMARY_CRASHED` (data `{source}`), `FAILOVER_MEASURED` (the five intervals, nulls kept), `DATA_LOSS_MEASURED` (data includes `simulated` and `simulatedDelayMillis`), `SPLIT_BRAIN_CHECKED`.

**Notes for E8b, E8c and Phase 9A.1**

1. E8b feeds the seam from a module-local selector (highest live id, the legacy rule) marked `// TODO(L1)`; it does not call the election module. It consults the selector only when the current primary fails.
2. Until 9A.1 the Exp 8 primary and the Exp 4 leader are independent, so crashing the Exp 4 leader can start an Exp 4 re-election while Exp 8 runs its own failover.
3. A recovering node comes back as a non-primary until its role query returns (`stepDown()` on its replication service before it can serve), and `NO_ANSWER` keeps it from writing. Before 9A a recovered old primary demotes and stays a backup (no handover).
4. E8b logs every event the state machine rejects (`REJECTED_OUT_OF_ORDER`, also counted in `rejectedEventCount()`).
5. The client retry window (`maxAttempts x retryDelayMillis`, 14 x 120 ms = 1.7 s in the legacy demo) is shorter than the estimated detection time (1.8 to 3.2 s), so E8c must size it in YAML to cover a whole failover (about 6 s at least), or the update stream gives up during every failover.
6. Phase 9A.1: a planned handover (leader change with no primary failure) must resynchronise the handover target before the handover is applied.
7. The asynchronous delay is whatever `WriteResult.simulatedDelayMillis` reports (Exp 5's `udcf.replication.async-delay-millis`, 450 today); the UI shows the reported value with the Simulated badge, never a typed 220 or 450.

**Tests:** backend baseline 960 (0 failures, 1 skipped); after E8a 1040 (0 failures, 1 skipped) in three identical sequential runs; 80 new tests in `AcknowledgedLedgerTest` (4), `DataLossTest` (7), `EpochAuthorityTest` (10), `EpochRulesTest` (11), `FailoverMeasurementsTest` (8), `FailoverStateMachineTest` (15), `FaultToleranceRecordsTest` (9), `SplitBrainCheckerTest` (7), `UpdateRetryPolicyTest` (9).

### E8b interfaces: failover on the shared cluster (`com.udcf.modules.faulttolerance`), for E8c and E8d

Nothing in production creates it yet (as E4b): E8c's module owns one `FailoverCluster`. No REST in E8b.

**`FailoverCluster implements AutoCloseable`** (one per cluster; plain class)

- `FailoverCluster(Cluster, FaultToleranceProperties, ElectionProperties, ReplicationProperties, ClusterEventBus, MeterRegistry)`; throws `IllegalStateException` if the client retry window is below the worst-case failover (formula below).
- Actions: `void start()` (idempotent: on every live node starts the election service, then the faulttolerance service, then the replication service; learns their epochs; appoints the first primary, epoch 2, asynchronously); `int crashPrimary()` (stamps ACTION, then `Cluster.crash`; `IllegalStateException` before the first appointment); `CompletableFuture<UpdateOutcome> submit(SystemUpdate, ConsistencyModel)` (one client update with the E8a retry policy, on the `udcf-faulttolerance-client` thread; accepted updates go to the ledger); `void reset()` (forgets runs, authority, ledger; steps down the primary it served; starts and stops nothing; earlier `NODE_RECOVERED` events are ignored); `void close()`.
- Reads: `Optional<Integer> currentPrimary()` (appointed, up, serving); `FailoverSnapshot snapshot()`; `Optional<FailoverMeasurements> measurements()` (latest run, nulls kept); `List<NodeRoleSnapshot> roleSnapshot()` and `SplitBrainReport splitBrain()` (from each replication service's acting role and epoch); `DataLossReport dataLoss()` (against the serving primary's store; not assessed while none serves); `List<AcknowledgedUpdate> acknowledged()`.
- Records: `UpdateOutcome(update, model, Status {ACCEPTED, GAVE_UP}, Integer nodeId, Long epoch, int attempts, WriteResult result)` (the three are null exactly when it gave up); `FailoverSnapshot(started, phase, Integer primaryId, Long primaryEpoch, highestEpoch, nodes, latestRun, runs, rejectedEventCount, acknowledgedUpdates)`; `NodeFailoverState(nodeId, nodeStatus, serviceRunning, actingPrimary, serving, Long epoch, Integer believedPrimaryId, RejoinState rejoin)` (nulls when unknown, never 0); `RejoinState {READY, REJOINING, WAITING_FOR_ANSWER}`.

**`FaultToleranceNodeService implements NodeService`** (`NAME = MODULE = "faulttolerance"`, one per node, no socket, no port)

- `static Optional<FaultToleranceNodeService> find(ClusterNode)`; `start()` (needs the node's election service, else `IllegalStateException`; registers a `FailureListener` on the shared detector), `crash()`, `recover()`, `stop()`, `isRunning()`, `nodeId()`, `isServing()`, `rejoinState()`, `wasPrimaryAtCrash()`, `Integer believedPrimaryId()` (from its own replicated term record; null if none at its store epoch or down).
- Threads: one worker `udcf-faulttolerance-n<k>-worker` (promotion, rejoin, retries) and virtual `udcf-faulttolerance-n<k>-query-*` threads (role-query READs, catch-ups). Crash and stop shut them down and wait, each wait bounded at 5 s (`IllegalStateException` if one is still alive). The detector listener runs on the election worker and only hands off.
- Crash: reports the crash (OBSERVED unless `crashPrimary` stamped it), steps the replication service down, joins its threads. Recover: reopens, marks REJOINING (never primary); the rejoin starts from `NODE_RECOVERED`.

**`RoleQuery`** (the role query, over the replication TCP channel 710k, no new port, no protocol change)

- `RoleQuery(int timeoutMillis)`; `Answers ask(int selfId, LamportClock clock, List<Integer> peers, IntUnaryOperator peerPort, ExecutorService executor) throws InterruptedException` (one READ per peer, in parallel, each bounded by the replication connect and read timeouts; L4 by `ReplicationClient`); `static RoleReport toReport(ReadReply)`; `static String termValue(int primaryId, long epoch)`; `TERM_KEY = "failover.term"`; `Answers(List<RoleReport> reports, List<Integer> silent)`, `describe()`.
- **The term record.** Every promotion writes one item, key `failover.term`, synchronously to every backup (like Raft's no-op at the start of a term); the replication service stamps it with the new primary's id and epoch, and every live backup learns the new epoch at once. A role-query answer is **the peer's replicated term record, not its in-memory role flag**: the peer's store epoch, and the primary named by the record at that epoch (PRIMARY if that is the peer itself; null if it holds none at that epoch). The record is **not a client write**: it is never in the acknowledged ledger and never counts as "service restored". It **appears as an extra row in Experiment 5's replica grid** (key `failover.term`, value "Node k is primary at epoch e").

**Other classes:** `FailoverRoleSelector.select(Collection<Candidate>, Set<Integer> excluded)` (lowest eligible id, `// TODO(L1)`; eligible = up, faulttolerance service running and READY, replication service running); `FaultToleranceProperties` (`udcf.faulttolerance`); `FaultToleranceMetrics`.

**Configuration (`udcf.faulttolerance`)** and the window formula

| Key                                   | Value | Meaning                                                           |
| ------------------------------------- | ----- | ----------------------------------------------------------------- |
| `client.max-attempts`               | 50    | sends and discoveries per update                                  |
| `client.retry-delay-millis`         | 250   | wait before a retry                                               |
| `role-query.retry-delay-millis`     | 1000  | wait before asking again after no answer                          |
| `role-query.max-attempts`           | 5     | role queries before the node stops asking (stays non-primary)     |
| `promotion.catch-up-timeout-millis` | 1000  | the chosen node's catch-up from the other live peers, in parallel |
| `history-limit`                     | 10    | failover runs kept                                                |

Worst-case failover (`FaultToleranceProperties.worstCaseFailoverMillis`, the only copy; `requireWindowCovers` applies it and `FailoverCluster` calls that at construction):

```
worst = detection + 2 x promotion attempt            (the chosen node fails; one extra candidate)
detection         = heartbeat timeout + heartbeat interval        = 2500 + 700        = 3200 ms
promotion attempt = catch-up bound + term-record bound
                  = catch-up-timeout + 2 x replication timeout   = 1000 + 2 x 1500   = 4000 ms
                    (term record: connect + read timeout; pushes to all backups in parallel)
worst = 3200 + 2 x 4000 = 11200 ms
retry window = max-attempts x retry-delay = 50 x 250 = 12500 ms = worst + 11.6 %  (>= 10 % required)
```

The upper bound of one promotion is therefore 4000 ms (catch-up 1000 + term record 3000), whatever the number of peers. The catch-up is one overall deadline over all peers, which run in parallel (`FaultToleranceNodeService.catchUp`). The term record's pushes run in parallel on Track C's thread-per-task push executor, each bounded by connect + read timeout, so the phase costs the slowest push. `becomePrimary` is in memory. No public-profile block: nothing here is sized by memory.

**Events** (module `faulttolerance`, the node's Lamport clock; node 0 uses the cluster clock; sentence case; nulls kept)

| Type                                   | Node, peer                                                                                           | Data                                                                                                    |
| -------------------------------------- | ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `PRIMARY_CRASHED`                    | crashed primary                                                                                      | source (ACTION, OBSERVED), epoch                                                                        |
| `FAILURE_DETECTED`                   | first observer, peer = primary                                                                       | silentMillis, timeoutMillis, epoch                                                                      |
| `PRIMARY_PROMOTED`                   | new primary, peer = previous                                                                         | epoch, previousPrimaryId (null for the first), caughtUpFrom, skipped                                    |
| `CATCH_UP_SKIPPED`                   | new primary, peer = skipped peer                                                                     | epoch, reason                                                                                           |
| `PROMOTION_FAILED`                   | chosen node                                                                                          | reason (or epoch, reason if its term record was refused)                                                |
| `NO_CANDIDATE`                       | 0                                                                                                    | excluded                                                                                                |
| `SERVICE_RESTORED`                   | new primary, peer = old primary                                                                      | epoch, key, sequence, outageMillis                                                                      |
| `STALE_EPOCH_REFUSED`                | the fenced sender, peer = refusing backup (null if a synchronous round, where the backup is unknown) | key, senderEpoch, backupEpoch, knownEpoch                                                               |
| `ROLE_QUERY`                         | recovering node                                                                                      | action, epoch, primaryId, ownRole, ownEpoch, replies [{nodeId, role, epoch, believedPrimaryId}], silent |
| `ROLE_QUERY_FAILED`                  | recovering node                                                                                      | attempt, maxAttempts, asked, ownRole, ownEpoch, retryInMillis (null on the last)                        |
| `OLD_PRIMARY_DEMOTED`                | recovering node, peer = new primary                                                                  | previousEpoch, epoch, primaryId                                                                         |
| `PRIMARY_RESUMED`                    | recovering node                                                                                      | epoch, ownEpoch                                                                                         |
| `RESYNCHRONISED` / `RESYNC_FAILED` | recovering node, peer = source                                                                       | pulled, applied, sourceEpoch, storeEpoch / reason, attempt, maxAttempts, retryInMillis                  |
| `SERVICE_START_FAILED`               | node                                                                                                 | service, error                                                                                          |

**Metrics** (R5; `MetricNames` unchanged, no other module registers these names): `distributed_failures_total{node_id = failed primary}`; `distributed_recovery_duration{node_id = failed primary, interval = detection | failover | service_restored | outage | recovery}` (timer; only measured intervals are recorded). Checked beside the Exp 4 and Exp 5 meters in one Prometheus registry (`FaultToleranceMetricsTest`).

**`ElectionNodeService.on(...)` is idempotent** (`ElectionNodeService.java:165` calls `ClusterNode.ensureService`, which returns the registered service at `ClusterNode.java:174-179`): Exp 4 and Exp 8 share one election service and one detector per node (`FailoverClusterTest.electionServiceOnIsIdempotent`).

**Notes for E8c (module, controller, DTOs, metrics)**

1. `FaultToleranceModule` (id `faulttolerance`, lab 8) owns one `FailoverCluster` built from the three properties beans; `@PreDestroy` closes it. `reset()` calls `FailoverCluster.reset()` (the replication stores are reset by Exp 5's module in the same cluster reset). Status: BUSY while an action holds the guard or a run is SUSPECTED/PROMOTING; RUNNING while the stream runs; else IDLE.
2. Stream: start/stop an update stream that calls `submit(SystemUpdate.numbered(n), model)` one at a time (the next after the previous completes), bounded by a configured count; publish `STREAM_STARTED`/`STREAM_STOPPED`. The SYNCHRONOUS/ASYNCHRONOUS toggle is the `model` argument.
3. Actions to expose: `start()` (on first use), `crashPrimary()`, recover the old primary through `Cluster.recover`, `snapshot()`, `measurements()`, `dataLoss()`, `splitBrain()`. Publish `FAILOVER_MEASURED`, `DATA_LOSS_MEASURED` (with `simulated`, `simulatedDelayMillis` from the report) and `SPLIT_BRAIN_CHECKED` there.
4. DTOs from `FailoverSnapshot`/`NodeFailoverState` (epoch on every node; `believedPrimaryId`; `rejoin`), `FailoverMeasurements` (nulls kept), `DataLossReport` (Simulated badge when `simulated`), `SplitBrainReport`. Mark a crash with `crashSource` OBSERVED as "seen as the node went down" (slightly late).
5. Metrics are registered by E8b; E8c only reads them (`registry.find`), never creates meters on read.

**Notes for E8d (page)**

1. Timeline from the faulttolerance events (crash, detection, promotion, service restored, role query, demotion, resync); the term record row in Exp 5's grid is expected.
2. The asynchronous delay shown is `DataLossReport.simulatedDelayMillis` / `WriteResult.simulatedDelayMillis` with the Simulated badge, never a typed number.
3. "Recover old primary" shows ROLE_QUERY then OLD_PRIMARY_DEMOTED and RESYNCHRONISED; with no peer reachable it shows ROLE_QUERY_FAILED and the node stays non-primary.

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

## Requirements Coverage (E8b)

| #  | Requirement                                                                                                             | Implementation                                                                                                                                                                      | Test                                                                                                                                                                                     |
| -- | ----------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1  | Per-node faulttolerance NodeService; starts, stops, crashes, recovers with its node (R10); restart-safe                 | `FaultToleranceNodeService` via `ClusterNode.ensureService`                                                                                                                     | `FaultToleranceNodeServiceTest` (all 7), `FailoverClusterTest.nodeDownAtStartJoinsOnRecovery`, `resetClearsState`                                                                  |
| 2  | Detection through the shared FailureDetector (L2), shared timings; who starts it                                        | `FailoverCluster.start` (`ElectionNodeService.on`), listener in `FaultToleranceNodeService.start`                                                                             | `FaultToleranceNodeServiceTest.startRegistersWithTheSharedDetector`, `startAfterFailure`, `FailoverClusterTest.electionServiceOnIsIdempotent`                                      |
| 3  | Module-local selector (lowest live id,`// TODO(L1)`) into `EpochAuthority.onLeaderElected`; first promotion epoch 2 | `FailoverRoleSelector`, `FailoverCluster.tryPromote`                                                                                                                            | `FailoverRoleSelectorTest`, `FailoverClusterTest.startAppointsFirstPrimaryAtEpochTwo`, `crashDetectedOncePromotedOnceAndMeasured`                                                  |
| 4  | Promotion on the replication service; new epoch; backups refuse stale epochs                                            | `FaultToleranceNodeService.promote` (`becomePrimary`, term record)                                                                                                              | `FailoverClusterTest.staleEpochUpdateRefusedByBackups`, `crashDetectedOncePromotedOnceAndMeasured`                                                                                   |
| 5  | Recovered old primary: non-primary until its role query returns; demote and resync; no answer keeps it non-primary      | `FaultToleranceNodeService.crash` (step down), `rejoin`, `RoleQuery`                                                                                                          | `FailoverClusterTest.recoveredOldPrimaryDemotesAndResyncs`, `noAnswerKeepsOldPrimaryNonPrimary`, `FaultToleranceNodeServiceTest.recoverRejoinsAsNonPrimary`, `RoleQueryTest` (4) |
| 6  | Instants: crash (ACTION/OBSERVED), detection, promotion, service restored, recovery                                     | `FailoverCluster.crashPrimary`, `nodeCrashed`, `onSuspected`, `accepted`, `nodeRecovered`; one nano clock                                                                 | `FailoverClusterTest.crashDetectedOncePromotedOnceAndMeasured`, `crashFromElsewhereIsObserved`, `recoveredOldPrimaryDemotesAndResyncs`                                             |
| 7  | Ledger from confirmed`WriteResult`s; Java API for E8c                                                                 | `FailoverCluster.submit`, `snapshot`, `measurements`, `splitBrain`, `dataLoss`                                                                                            | `FailoverClusterTest.synchronousModeLosesNothing`, `asynchronousModeReportsInFlightLoss`, `resetClearsState`                                                                       |
| 8  | Events under`faulttolerance`, node Lamport time, sentence case, nulls kept                                            | `FailoverCluster.publish`                                                                                                                                                         | `FailoverFixture.assertEventsWellFormed` (5 tests), `FailoverRecordsTest.roleQueryAnswers`                                                                                           |
| 9  | `udcf.faulttolerance` sized to the retry window; formula shared and fail-fast                                         | `FaultToleranceProperties.worstCaseFailoverMillis`, `requireWindowCovers`                                                                                                       | `FaultTolerancePropertiesTest` (5), `FailoverClusterTest.tooSmallWindowFailsConstruction`                                                                                            |
| 10 | Metrics with`node_id` per meter, nothing fabricated, no tag-key clash                                                 | `FaultToleranceMetrics`                                                                                                                                                           | `FaultToleranceMetricsTest` (3), `FailoverClusterTest.crashDetectedOncePromotedOnceAndMeasured`, `recoveredOldPrimaryDemotesAndResyncs`                                            |
| T  | Detection promotes once with two reporters                                                                              | `FailoverStateMachine.onSuspected` + `EpochAuthority`                                                                                                                           | `FailoverClusterTest.concurrentSuspicionsPromoteOnce`                                                                                                                                  |
| T  | No two primaries at any sampled instant                                                                                 | `SplitBrainChecker` over `roleSnapshot`                                                                                                                                         | `FailoverClusterTest.neverTwoPrimaries`                                                                                                                                                |
| T  | Listener thread does no blocking work                                                                                   | hand-off to`udcf-faulttolerance-n<k>-worker`                                                                                                                                      | `FailoverClusterTest.detectorCallbackDoesNoBlockingWork`                                                                                                                               |
| C2 | Term record with a dead backup, catch-up with a silent peer: both bounded                                               | per-push and catch-up timeouts                                                                                                                                                      | `FailoverClusterTest.termRecordWithDeadBackupIsBounded`, `catchUpWithSilentPeerIsBounded`, `FaultToleranceNodeServiceTest.crashDuringBlockedRoleQueryIsBounded`                    |
| R  | Each promotion phase costs one bound whatever the number of peers (5 nodes: 3 silent live peers + dead old primary)     | catch-up: one overall deadline (`allOf(...).get(timeout)`), peers on virtual threads; term record: Track C pushes all backups in parallel, each bounded by connect + read timeout | `FailoverClusterTest.promotionWithSeveralSilentAndDeadPeersIsBounded`                                                                                                                  |

---

## Deviations

- L4: heartbeats carry no Lamport stamp and do not advance the clock. Reason: at about 11 ticks per second per node (5 nodes) the Lamport values shown by Experiment 3 would change without any user action. Awaiting Rohan's sign-off at PR review.
- E8a: the step names `SystemUpdate`, `UpdateStore` and `FailoverMetrics` changed (approved). `UpdateStore` is not ported (the shared `DataStore` is the store and its epoch fence); `SystemUpdate` is the client's unstamped request (the stamped update is `DataItem`); `FailoverMetrics` is renamed `FailoverMeasurements`, because "Metrics" classes in this codebase are Micrometer meters.
- E8a: the asynchronous replication delay is not Appendix B's 220 ms for Experiment 8. Exp 8 reuses the replication service, whose delay is `udcf.replication.async-delay-millis` (450 ms today); E8a carries whatever `WriteResult.simulatedDelayMillis` reports and Track C's code is unchanged (approved).
- E8a: detection uses the shared FailureDetector (700 ms / 2500 ms), not the legacy demo's 300 ms / 1200 ms; there is no separate Exp 8 detector configuration (approved, link L2).
- E8a: the intervals differ from the legacy `FailoverMetrics`: failover is measured from detection (legacy: from the crash), "service restored" from the promotion (legacy "recovery": crash to first write); outage (crash to first accepted write) and recovery (old primary recovered to demoted and resynchronised) are new; incomplete intervals are null (legacy 0) and values are not rounded.
- E8a: promotion epochs start at `INITIAL_EPOCH + 1` = 2 (legacy: the initial primary held epoch 1), so no promotion ever shares the stores' initial epoch.
- E8b (Q1): the selector picks the **lowest** live, ready node (docs/tracks/README.md line 59), not the highest of E8a note 1 and Appendix B (the legacy demo, superseded). The initial appointment is the first promotion (epoch 2); the first failover is epoch 3 (Q5).
- E8b (Q4, from E8a note 3): a node's replication service is stepped down when the node **crashes**, not when it recovers. `ClusterNode.recover()` sets the node UP before it recovers its services, so stepping down on recovery would let a sampler see a recovered old primary as a second primary for a moment. This matches the core rule that a crashed node holds no role. The role it held is kept in `wasPrimaryAtCrash` for its role query. Side effect: `PRIMARY_STEPPED_DOWN` (module replication) appears just before `NODE_CRASHED`.
- E8b (Q4, from E8a note 4 and `InstantSource.OBSERVED`'s Javadoc): a crash not started by Exp 8 is stamped OBSERVED inside the faulttolerance service's `crash()` (synchronously, during `ClusterNode.crash`), not when `NODE_CRASHED` is delivered. It is earlier, so closer to the real instant; still labelled OBSERVED. The E8a Javadoc was not changed (E8a classes untouched).
- E8b (Q6): before acting as primary, the chosen node catches up from every other live, ready peer except the old primary (Exp 5's takeover rule), all in parallel and bounded by `promotion.catch-up-timeout-millis`; a peer not done in time, or unreachable, is skipped with `CATCH_UP_SKIPPED` and never aborts the promotion. The first appointment skips it.
- E8b (Q6): a recovered backup whose role query says STAY still resynchronises from the named primary (E8a's `RejoinDecision.resync()` is false for STAY): it may have missed writes at the same epoch while it was down.
- E8b: the client retry window must cover `(heartbeat timeout + interval) + 2 x (catch-up timeout + 2 x replication timeout)` = 11200 ms with the shared timings; the default is 50 x 250 = 12500 ms (Correction 1). The earlier "about 6 s" in E8a note 5 counted one replication timeout only.
- E8b: event names follow the E8b addendum, not the E8a list: `FAILURE_DETECTED` (E8a: `PRIMARY_SUSPECTED`), `OLD_PRIMARY_DEMOTED` (E8a: `PRIMARY_DEMOTED`); added `CATCH_UP_SKIPPED`, `PROMOTION_FAILED`, `NO_CANDIDATE`, `PRIMARY_RESUMED`, `RESYNC_FAILED`, `STALE_EPOCH_REFUSED`, `SERVICE_START_FAILED`. `PRIMARY_CRASHED` is published by E8b (E8a listed it for E8c).
- E8b: the role query reads the replicated term record (`failover.term`) over the existing replication READ (Q2), so no protocol change in Track C.

---

## Known issues

- `MultithreadingModuleTest.backpressureIsDeterministicAndGuarded` (Track A's test) failed once in one of three runs (expected 5, was 9). Not edited, as it is unrelated to the election module and our tests are completely pure without static state, threads, or sleep loops.
- E4b: nothing in production starts `ElectionNodeService` yet; E4c's `ElectionModule` will start it on every node. A node whose election service is not running is indistinguishable from a dead one, so every node's service must start together, or the started ones suspect the others after 2500 ms.
- E4b: the E4a algorithms still keep a private Lamport counter. The transport replaces it on the wire and in every event with the node clock, so the private value is never published (no E4a edit, by decision).
- E4b: E4a's `getCoordinatorId()` reads a non-volatile field. The transport only reads it on the worker thread and exposes its own volatile `coordinatorId()`; other code should use that (no E4a edit, by decision).
- E4b: a Ring election started when every other node is dead never elects anyone (E4a ring-of-one behaviour): it ends in `ELECTION_TIMEOUT` after 5000 ms. E4c should use Bully for automatic re-election.
- E4b: Linux (Docker, 2 CPUs): the new classes passed 5 runs in a row (41 tests each); full backend suite 852 tests, 0 failures, 1 skipped
- E4c: a Ring election with only one live node never elects anyone and its round ends TIMED_OUT after `round-timeout-millis` (10000 ms), unmeasured. Automatic re-election therefore always uses Bully.
- E4c: the duration of a LEADER_FAILURE round is measured from the detection (the first node's failure detector suspecting the leader, about 2500 ms after the crash), not from the crash itself.
- E4c: nothing starts the election services at boot, so the FailureDetector is idle until the first election request. E8b and any other module that needs the detector must start the services itself with `ElectionNodeService.on(...)`.
- E4c: between a leader crash and the agreement on a new leader (about 2.5 to 3 s with the default timings) the cluster has no LEADER; the top bar shows "None elected" during that gap.
- E4c: Linux (Docker, 2 CPUs): the election subset passed 5 runs in a row (82 tests each); full backend suite 893 tests, 0 failures, 1 skipped
- E4d: `GET /api/modules/election` closes a timed-out round lazily, only when it is read (also by `status()`). The page therefore refreshes once at the overview's `roundTimeoutMillis` + 250 ms while a round is open; nothing else polls.
- E4d: the per-node figures count since the backend started; a cluster reset does not reset them (Prometheus counter semantics), and the page says so.
- E4d: the 1280x800 and 375x740 checks were run in same-origin iframes of exactly those CSS sizes (the Chrome window could not be set to an exact viewport at 125 % display scaling). Keyboard-only use and reduced motion were checked by the component tests, not by hand.
- E8a: the 1.8 to 3.2 s detection time is an estimate from the detector settings, not a measurement; E8c measures it.
- E8a: a crash started outside Exp 8 (for example on the Cluster page) is stamped when `NODE_CRASHED` is delivered (`InstantSource.OBSERVED`), slightly late, so its detection time is slightly short; E8d labels such values.
- E8a: a write accepted by the new primary before E8b records `onPromoted` does not count as "service restored", so that interval can be overstated by up to one write interval of the stream.
- E8a: `AcknowledgedLedger` is unbounded (data loss must check every acknowledged key); E8c bounds it by bounding the update stream.
- E8a: verified on Windows only (three sequential full runs). An earlier set of three runs was started in parallel by mistake (shared `target/` and ports); all three passed (1040 tests, 0 failures, 1 skipped) but are not counted.
- E8b, **Experiment 5 interference (to be removed by Phase 9A.1):** Exp 5 and Exp 8 drive the same replication service per node with their own selectors until L1. Every Exp 5 action (write, anti-entropy, stale injection, recover on the replication page) runs `ReplicaSet.primary()`, which steps down every other live node acting as primary (`ReplicaSet.java:140-141`) and makes the lowest live node primary at its current epoch. Both selectors pick the lowest live id, so they agree while no failover has happened. After a failover from node 1 to node 2, if node 1 recovers and rejoins as a backup, the next Exp 5 action re-appoints node 1 there (at node 1's store epoch, the same as node 2's) and steps node 2 down. Exp 8 then has no serving primary: its updates get "not primary" and retry until they give up, and two nodes will have acted as primary at one epoch, one after the other. No E8b test depends on this.
- E8b, **dropped bus notifications:** a recovered node's rejoin starts from the bus `NODE_RECOVERED` handler on the event dispatcher thread, which delivers asynchronously, as for Experiment 4's recovered nodes. If the dispatch queue is full, that notification is dropped (`droppedNotifications()`), and the node stays REJOINING (never primary, safe) until it crashes and recovers again. The same exposure applies to Experiment 4: a node that was down when its services first started joins only through that notification.
- E8b, **visible to other modules:** (1) Exp 5's replica grid shows an extra row, key `failover.term` (the term record; not a client write, never in the ledger). (2) Once Exp 8 starts, the Exp 4 page shows its election services running (status RUNNING, detector `PEER_SUSPECTED`/`PEER_ALIVE` events) before any election. A recovered node runs Bully (existing E4a behaviour) without an Exp 4 round, because the election module is not activated. Exp 4's code is unchanged. (3) `PRIMARY_STEPPED_DOWN` (module replication) appears when an acting primary crashes (Q4).
- E8b: a sync write is confirmed by Track C even if a backup push FAILED; the catch-up before promotion recovers it from any live peer that holds it. An asynchronous update that never left the old primary is lost (measured, labelled simulated), and stays only in the old primary's store at the old epoch after it rejoins (Track C's "orphan local item").
- E8b, **SO_REUSEADDR (for Rohan):** `ReplicationNodeService.java:806` calls `socket.setReuseAddress(true)` on the replication server socket unconditionally, with no guard for Windows or any other OS. Not changed (Track C's file). On Windows the JDK binds exclusively by default, so a second bind still fails (seen in `FailoverClusterTest.catchUpWithSilentPeerIsBounded`, where node 5's replication service cannot bind a port held by a test socket).
- E8b: verified on Windows only (5 sequential runs of the new classes, 3 sequential full runs); Linux line left for the owner.

---

## Progress log

- 2026-10-07 track file created.
- 2026-10-08 E4a completed (baseline 525, total 579; 54 election tests covering all scenarios including stale timers, crash safety, 1024-char limit, probe event absence, and action loop crash guards).
- 2026-10-09 E4b done: election UDP transport (`ElectionNodeService`, worker-only algorithm work, joined lifecycle) and the shared `core/failure/FailureDetector` (700/2500 ms heartbeats, PEER_SUSPECTED/PEER_ALIVE); backend 852 tests (baseline 817 + 35), frontend not run (no frontend change); deviations: heartbeats exempt from L4 (awaiting Rohan's sign-off), `HEARTBEAT` added to E4a's `ElectionMessageType` (approved).
- 2026-10-09 E4c done: `ElectionModule` (lab 4: Bully/Ring from any node, sticky LEADER role, consensus check, automatic Bully re-election on leader failure, rounds with timeout, clean-slate reset), cluster roles API (`NodeRole`, `ClusterNode.roles`, `Cluster.assignLeader`, `LEADER_CHANGED`, `NodeDto.roles`), REST, metrics, 22 live contract fixtures, `isLeader` in top bar and topology; backend 893 tests (baseline 852 + 41), frontend 391 tests (baseline 388 + 3); deviations: none beyond the approved plan (added `ElectionRound` record; `ElectionNodeService.halt/resetState` and `FailureDetector.reset` as approved in Q7).
- 2026-10-09 E4d done: Election page (node ring with live message arrows, result panel with honest states, leader changes from the cluster log, per-node figures, crash and recover without confirmation), sentence-case election event messages, per-node `electionsWon`/`roundsTimed`/`meanDurationMillis` in the overview, fixtures recaptured plus `overview-ring-timed-out.json`; backend 897 tests (baseline 893 + 4), frontend 537 tests (baseline 464 + 73); deviations: extra fixture `overview-ring-timed-out.json`; shared edits `registry.test.js` and `ExperimentPage.test.jsx` (election to matrix, both approved), `experiments.js` concept and `routes.test.jsx` spies (add-only).
- 2026-10-09 E8a done: Experiment 8 pure classes in `com.udcf.modules.faulttolerance` (epoch rules and `EpochAuthority` with the `onLeaderElected` TODO(L1) seam, `FailoverStateMachine` with rejected-event counter and `InstantSource`, `FailoverMeasurements` incl. outage, `AcknowledgedLedger` and `DataLoss` with the simulated marker, bounded `UpdateRetryPolicy`, strict `SplitBrainChecker`), reusing Track C's `DataStore`, `DataItem`, `ConsistencyModel` and `WriteResult`; backend 1040 tests (baseline 960 + 80), frontend not run (no frontend change); deviations: step class names changed (`UpdateStore` not ported, `FailoverMetrics` renamed `FailoverMeasurements`), async delay as reported (450 ms, not 220), shared detector timings, redefined intervals, first promotion epoch 2.
- 2026-10-09 E8b done: failover on the shared cluster (`FailoverCluster`, per-node `FaultToleranceNodeService`, role query over the replication READ channel with the `failover.term` term record, lowest-id `FailoverRoleSelector` behind TODO(L1), catch-up before promotion, rejoin with demote/adopt and resync, client updates with the E8a retry policy feeding the ledger, metrics, `udcf.faulttolerance` with a shared worst-case formula); backend 1084 tests (baseline 1040 + 44; new classes 5 sequential runs, full suite 3 sequential runs, all green; 1083 before the review test `promotionWithSeveralSilentAndDeadPeersIsBounded` was added), frontend not run (no frontend change); deviations: lowest-id selector (README), step-down at crash and OBSERVED stamped in `crash()` (Q4), catch-up before promotion and resync on STAY-as-backup (Q6), window formula 11200 ms (default 12500 ms), event names per the addendum.
