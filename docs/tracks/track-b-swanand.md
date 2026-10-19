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
- [x] E8c — `FaultToleranceModule` (lab 8): start and stop an update stream, crash the primary, sync/async toggle, recover the old primary, the four measurements. Needs: E8b.
- [x] E8d — page and end-to-end check. Needs: E8c, E2d.

---

## Phase 9A (R19) — system integration

- **Plan:** [docs/tracks/README.md](README.md) Section 11 (steps, owners, dependencies).

### Steps

- [x] 9A.1 — Shared role provider under `com.udcf.core` (`core/cluster/RoleProvider`): LEADER from the election, PRIMARY and BACKUP join `NodeRole`; Experiment 8 promotes the elected leader (`FailoverRoleSelector` removed, `EpochAuthority.onLeaderElected` fed from leader changes, a planned-handover target resynchronised first); L2 verified: one FailureDetector suspect signal drives the Experiment 4 re-election and the Experiment 8 failover. Needs: 9A.0.
- [x] 9A.6 — `com.udcf.scenario`: scenario engine, API and narration; scenarios "The leader dies mid-write", "A slow node under load" and "Split-brain that never happens"; the leader-crash end-to-end test. Needs: 9A.3, 9A.4.

---

## Phases 14-17

### Steps

- [x] 15.1 — Prometheus, Grafana and local Docker Compose: `infra/docker-compose.yml` (backend, frontend on nginx, Prometheus, Grafana), the "UDCF cluster" dashboard, `scripts/compose-up.ps1` and `scripts/compose-down.ps1`. Smoke check 5/5 (2026-10-20).
- [x] 15.1b — 15.1b: two flaky tests made order- and clock-independent; CI failure on Linux. Test files only (`ElectionNodeServiceTest`, `ScenarioEngineTest`).

---

## Interfaces for other tracks

### 9A.6 APIs: the scenario engine (`com.udcf.scenario`), for 9A.7 (Jai), 9A.5 (timeline) and 9A.9

JSON blocks below are **shape examples with illustrative values, not captures**. Errors are the shared ProblemDetail (`status`, `title`, `detail`, `instance`).

**Endpoints** (`/api/scenarios`)

| Verb, path | Success | Errors |
|---|---|---|
| `GET /api/scenarios` | 200 every scenario in `order`, with its steps' narration | - |
| `POST /api/scenarios/{id}/runs` | 202 `{runId, scenarioId}`; the run starts in the background with a clean cluster reset | 404 `{title: "Unknown scenario", scenarioId}`; 409 `{title: "Scenario busy", reason: "SCENARIO_BUSY", runId}` (the run in progress) |
| `GET /api/scenarios/runs/latest` | 200 the most recently started run (running or finished); 204 if none is kept | - |
| `GET /api/scenarios/runs/{runId}` | 200 one kept run (the last 10 are kept) | 404 `{title: "Unknown scenario run", runId}`; 400 non-numeric id |

```json
GET /api/scenarios
[{"id": "leader-dies-mid-write", "title": "The leader dies mid-write",
  "summary": "Updates are being written to the leader when it crashes. ...",
  "modules": ["election", "replication", "faulttolerance"],
  "steps": [{"index": 1, "narration": "We start Experiment 8 and send it a steady stream of synchronous updates. ..."},
            {"index": 2, "narration": "We crash the leader, which is also the primary, while updates are still being written."}]}]

POST /api/scenarios/leader-dies-mid-write/runs   -> 202
{"runId": 3, "scenarioId": "leader-dies-mid-write"}

GET /api/scenarios/runs/latest
{"runId": 3, "scenarioId": "leader-dies-mid-write", "state": "FAILED",
 "startedAt": "2026-10-18T12:00:00Z", "finishedAt": "2026-10-18T12:00:14Z",
 "verdict": "Failed at step 6: Node 4 lacks 1 of 9 confirmed updates.",
 "endState": {"description": "Nodes 1, 2, 3 and 4 are up; node 5 is down. Node 4 is the leader and the replication primary. The update stream is stopped after 40 updates (39 confirmed).",
              "leaderId": 4, "primaryId": 4,
              "nodes": [{"nodeId": 1, "status": "UP", "roles": ["BACKUP"]}, {"nodeId": 5, "status": "CRASHED", "roles": []}]},
 "steps": [{"index": 2, "narration": "We crash the leader, ...", "state": "PASSED", "startedAt": "2026-10-18T12:00:02Z",
            "durationMs": 12, "facts": {"crashedNodeId": 5, "crashedEpoch": 2, "acknowledgedBeforeCrash": 7, "crashedSequence": 140},
            "message": "Node 5 went down on every protocol at epoch 2, after 7 confirmed updates."},
           {"index": 7, "narration": "...", "state": "SKIPPED", "startedAt": null, "durationMs": null, "facts": {},
            "message": "Skipped: step 6 failed."}]}
```

- Run `state`: `RUNNING`, `PASSED`, `FAILED`; `finishedAt`, `verdict` and `endState` are null while RUNNING. A run never stays RUNNING: any error ends it FAILED.
- Step `state`: `PENDING`, `RUNNING`, `PASSED`, `FAILED`, `SKIPPED`. `index` is **1-based**. `startedAt` is null while PENDING and for SKIPPED; `durationMs` is null until the step ends and for SKIPPED; `message` is null while PENDING or RUNNING; `facts` is `{}` if none.
- `narration` is static and has no numbers. Every number is in `message` and `facts`, from observation.
- `endState` is read from the cluster when the run ends (no automatic reset). Its description includes the scenario's cleanup sentence.
- If the cluster could not be reset first (a module stayed BUSY for `reset-timeout-millis`), the run is FAILED, every step SKIPPED, and the verdict starts "Failed before the first step: ...".
- There is no cancel. A run of "Split-brain that never happens" takes about half a minute.

**Events** (module `scenario`, node 0, one tick of the cluster clock; on `/topic/events` and `/topic/modules/scenario`)

| Type | Data | Message |
|---|---|---|
| `SCENARIO_STARTED` | `runId, scenarioId, title, steps` (count) | "Scenario started: <title>" |
| `STEP_STARTED` | `runId, scenarioId, stepIndex, narration` | "Step 2 of 6: <narration>" |
| `STEP_PASSED` / `STEP_FAILED` | `runId, scenarioId, stepIndex, durationMs, facts` | "Step 2 passed: <message>" / "Step 6 failed: <message>" |
| `STEP_SKIPPED` | `runId, scenarioId, stepIndex` | "Step 7 skipped: step 6 failed" |
| `SCENARIO_FINISHED` | `runId, scenarioId, title, outcome` (`PASSED`/`FAILED`), `verdict` | the verdict |

`SCENARIO_STARTED` follows the run's own `CLUSTER_RESET`. When the reset fails, only `SCENARIO_FINISHED` is published.

**Add a scenario (recipe for Jai, add-only)**

```java
@Component
public class MatrixLeaderCrashScenario implements Scenario {
    private final FailoverSteps failover;          // optional: reusable Experiment 8 steps (below)
    private final MatrixModule matrix;
    private final List<ScenarioStep> steps;

    public MatrixLeaderCrashScenario(FailoverSteps failover, MatrixModule matrix) {
        this.failover = failover;
        this.matrix = matrix;
        this.steps = List.of(
                ScenarioStep.of("We start a matrix multiply rooted at the elected leader.", this::startRun),
                ScenarioStep.of("We crash the leader while the multiply runs.", this::crashLeader));
    }

    private String startRun(StepContext step) throws InterruptedException {
        long anchor = step.anchor("run");                 // the bus sequence just BEFORE the action
        var run = matrix.startRun(...);                   // call the module bean, never HTTP
        ClusterEvent started = step.awaitEvent(anchor, "matrix", "RUN_STARTED", e -> true, "the run to start");
        step.fact("rootNodeId", started.nodeId());        // observed values only
        step.memory().remember("root", started.nodeId()); // shared with later steps
        return "Node " + started.nodeId() + " is the root.";   // the step's message, from observation
    }
    // id(), title(), summary(), modules(), order() (scenarios use 10, 20, 30; take 40 and 50), steps(),
    // cleanup(RunMemory) (optional, always runs in a finally, returns one sentence for endState or null)
}
```

- `StepContext`:
  - `anchor(name)` / `anchorOf(name)`: wait for consequences of an action from its anchor, not from your step's start. Events of one action can arrive while an earlier step still waits.
  - `awaitEvent(after, module, type, match, what)` and `awaitEvent(after, module, predicate, what)`: bus subscription plus polling fallback; the earliest match by sequence wins.
  - `awaitState(supplier, done, what)`: polling.
  - `requireOrder(earlierName, earlier, laterName, later)`: checks bus order and records both sequences as facts.
  - `fact(key, value)`, `memory()` (`remember`, `recall`, `find`), and `throw step.fail("observed message")`.
- Every wait shares the step's deadline: `udcf.scenario.step-deadline-millis` (30000), or `new ScenarioStep(narration, Duration, body)`. A timeout fails the step with "Waited N s for <what>, but it did not happen." A cluster reset by someone else fails the waiting step.
- Steps run on the daemon thread `udcf-scenario-runner`, never a listener or dispatcher, so module actions that call `RoleProvider.ensureLeader()` are allowed. Never reset the cluster in a step: the engine does it before every run.
- Helpers: `Narration.nodes(ids)` ("nodes 1 and 2"), `Narration.millis(x)`, `EventData.integer/longValue/decimal/is(event, key, ...)`.

**`FailoverSteps`** (`com.udcf.scenario.scenarios`, a Spring `@Component`; inject it). These are the Experiment 8 steps, each as a step with default narration and as a public body you can wrap with your own narration:

| Step / body | What it does |
|---|---|
| `startSynchronousStream()` / `startStream(step)` | synchronous stream at Experiment 8's default rate; waits for the leader to be promoted and 3 confirmed updates; remembers `LEADER`, `EPOCH` |
| `crashLeader()` / `crash(step)` | `FaultToleranceModule.crashPrimary()`, which crashes the whole node (`Cluster.crash`); anchor `CRASH` |
| `detection()`, `election()`, `promotion()`, `serviceRestored()` | `FAILURE_DETECTED`, `LEADER_CHANGED` (remembers `NEW_LEADER`, `LEADER_CHANGED`), `PRIMARY_PROMOTED` at a higher epoch after the election (remembers `NEW_EPOCH`), `SERVICE_RESTORED` plus `DATA_LOSS_MEASURED` with 0 lost |
| `crashLeaderAndFailOver()` / `crashAndFailOver(step)` | crash, election, promotion and restored service in one step |
| `stopStream(memory)` | cleanup: stops the stream; returns the end-state sentence |

### 9A.1 APIs: the shared role provider (link L1), for 9A.3 (Jai) and 9A.4 (Rohan)

**`com.udcf.core.cluster.RoleProvider`** (Spring `@Component`; inject it through your constructor). The single source of truth for cluster roles. Only Experiment 4's election decides the leader; nothing falls back to "the lowest live node".

| Method | What it does | Threads |
|---|---|---|
| `Optional<Integer> leaderId()` | the node holding LEADER; never a crashed node; empty before the first election and from a leader crash until the re-election agrees (about 2.5 to 4 s with the local timings) | any; never waits |
| `Optional<Integer> ensureLeader()` | the leader; if there is none, asks Experiment 4 for one election and waits at most `ok-timeout + coordinator-timeout` (900 + 2200 = 3100 ms locally and in the public profile); empty if nobody was elected in time, or if the context has no election module (then at once) | **blocks**: call it on a request thread only (see the rules below) |
| `Registration addLeaderListener(LeaderListener)` | every later leader change, in announcement order, as `LeaderChange(Integer leaderId, Integer previousLeaderId, Cause cause)` with `Cause` `ELECTED`, `LEADER_CRASHED` or `CLEARED`; `Registration.close()` removes it | delivered only on `udcf-roles-dispatcher` |
| `void afterPendingChanges(Runnable)` | runs the task on the dispatcher after every change announced before the call (follow-up work stays in order with the changes) | never waits |
| `Optional<Integer> primaryId()` | the node holding PRIMARY | any |
| `Optional<Integer> publishReplicationRoles(Integer primaryId, Collection<Integer> backupIds)` | the replication owner only: `primaryId` the only PRIMARY (null: none), the listed live nodes BACKUP, every other node neither; a crashed node is never granted a role; returns the primary afterwards | any; never waits |

**Side effect of `ensureLeader()` when there is no leader:** Experiment 4 starts its election service, and with it the heartbeats of the shared FailureDetector, on every live node, and runs Bully from the lowest live node as a round with trigger `REQUESTED` (shown on the Experiment 4 page as "Requested"; it elects the highest live node).

**Threading rules**

1. `ensureLeader()` only on a request thread (an HTTP handler, a module action). Never on `udcf-roles-dispatcher` (it throws `IllegalStateException`), never on a socket listener thread, never on an election worker (the election it waits for runs there), and never while holding a lock the election path can need: a node's lifecycle lock, a service's monitor, or a lock your own leader listener takes.
2. A `LeaderListener` runs on the one shared dispatcher: it must not block, must not call `ensureLeader()`, and should hand blocking work to its own executor.
3. A `LeaderChange` is a hint: re-read `leaderId()` when you act, so late or repeated notifications are harmless.
4. Locks inside: `Cluster.leaderLock` (announcement) only notifies; `primaryLock`, then each node's roles lock, then the bus publish lock; the two are never nested, and none is held while a thread is joined.

**The no-leader rule.** Empty means "no leader could be elected": answer it honestly, as an HTTP 409 with your module's conflict reason (Experiment 8 uses `reason: NO_LEADER`, "No leader could be elected, so Experiment 8 has no primary: no live node won an election in time. Recover a node or reset the cluster, then try again."). Never substitute another node.

**Recipe (copy-ready)**

```java
// 1. Constructor injection (com.udcf.core.cluster.RoleProvider).
public MyModule(..., RoleProvider roles) { this.roles = roles; ... }

// 2. On the request thread of an action that needs the leader (Berkeley daemon, MapReduce coordinator,
//    MPI root, matrix root, replication primary):
int leader = roles.ensureLeader().orElseThrow(() -> new MyConflictException(Reason.NO_LEADER,
        "No leader could be elected: no live node won an election in time. Recover a node or reset the cluster."));

// 3. Optional: follow changes (for example to re-point a long-running role). Close it in @PreDestroy.
RoleProvider.Registration leaderChanges = roles.addLeaderListener(change -> {
    Optional<Integer> now = roles.leaderId();   // re-read; never block here, never ensureLeader()
    myExecutor.execute(() -> reconcile(now));
});

// 4. Jai only (Experiment 5, 9A.3): after appointing the replication primary,
roles.publishReplicationRoles(leader, liveBackupIds);
```

**Roles and events (served JSON)**

- `NodeRole`: `LEADER`, `PRIMARY`, `BACKUP`. `NodeDto.roles` lists them in that order, for example `["LEADER","PRIMARY"]` on node 5 and `["BACKUP"]` on nodes 1 to 4 while Experiment 8 runs; the Cluster and Overview pages show `roles.join(', ')`; `isLeader` ignores the new roles.
- `LEADER_CHANGED` (unchanged since E4c): module `cluster`, node 0, cluster clock, peer = new leader, data `{leaderId, previousLeaderId}`.
- **`REPLICATION_ROLES_CHANGED`** (new; for the 9A.5 timeline): module `cluster`, node 0, Lamport = cluster clock, `peerId` = the primary (null if none), data `{primaryId, previousPrimaryId, backupIds}` (`backupIds` ascending, `[]` if none), message `"Node 5 is the replication primary; backups 1, 2, 3, 4"` or `"No replication primary (node 5 is no longer primary); backups 1, 2, 3, 4"` (`"; no backups"` when empty). Published only when the primary or the backups change, including when `Cluster.crash` takes the primary or a backup down. Goes to `/topic/events` and `/topic/cluster` (the `ClusterProvider` refetches).
- A node holds no role while crashed, and recovery restores none; the owner publishes again.

**For Jai (9A.3), the Experiment 5 fix this step needs** (see Known issues, "Experiment 5 regression"): `ReplicaSet.selectedPrimaryId()` returns `roles.ensureLeader()` instead of `ReplicationRoleSelector` (empty: the Experiment 5 action answers 409, no fallback); when the leader already acts as primary (Experiment 8 made it primary at a higher epoch), `primary()` changes nothing (no step-down of it, no takeover); after appointing, Experiment 5 calls `publishReplicationRoles(leader, backups)`. Both experiments then follow the same leader and never move the primary away from each other.

**For Rohan (9A.4):** the Berkeley time daemon and the MapReduce coordinator are `ensureLeader()` on the action's request thread (empty: 409); a leader crash during a run is the run's own failure handling (the next run asks again), or follow `addLeaderListener` if the page must show the new daemon or coordinator live.

**Experiment 8 changes other tracks may see:** `FailoverCluster(Cluster, RoleProvider, ...)` and `FaultToleranceModule(Cluster, RoleProvider, ...)` take the provider; `FailoverCluster.start()` returns `false` when no leader could be elected; `POST /api/modules/faulttolerance/start` and `/stream/start` answer 409 `reason: NO_LEADER` then; new event `HANDOVER_STARTED` (module `faulttolerance`, node = the old primary, peer = the leader, data `{epoch, leaderId}`); `NO_CANDIDATE` is no longer published.

Covered by `RoleProviderTest` (12), `ElectionModuleTest` (3 added), `LeaderFailoverIntegrationTest` (5), `FailoverClusterTest`, `FaultToleranceModuleTest`.

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

### E8c interfaces: the Experiment 8 module and REST API (`com.udcf.modules.faulttolerance`), for E8d

**Module.** `FaultToleranceModule` (id `faulttolerance`, lab 8, title "Fault Tolerance"), a Spring `@Component` that `ModuleRegistry` collects; `GET /api/modules` lists `{id: "faulttolerance", labNumber: 8, title: "Fault Tolerance", status}`. It owns one E8b `FailoverCluster`, the `UpdateStream` (thread `udcf-faulttolerance-stream`, one update at a time), the data loss captured per run, the distinct split-brain violations seen, and the `ModuleActionGuard`. Status: BUSY only while the guard is held (start, stream start, crash, recover); RUNNING while Experiment 8 is started or the stream runs; else IDLE.

**Endpoints** (`/api/modules/faulttolerance`; errors are RFC 7807 ProblemDetail)

| Verb, path | Success | Errors |
| --- | --- | --- |
| `GET` | 200 overview `{current, latestRun, settings, glossary, notes}`; starts, records and publishes nothing | - |
| `GET /status` | 200 the live part only (`current` of the overview) | - |
| `POST /start` | 202 `{outcome: "STARTED", reason: null, status}`; the first primary is appointed asynchronously | 409 busy |
| (again) | 200 `{outcome: "ALREADY_STARTED", reason, status}` | - |
| `POST /stream/start` body `{mode, ratePerSecond}` | 202 stream; starts Experiment 8 first if needed (one guard) | 400 fields; 409 busy, `STREAM_RUNNING`, `UPDATE_LIMIT_REACHED` |
| `POST /stream/stop` | 200 stream, `stopReason: REQUESTED` | 409 `STREAM_NOT_RUNNING` |
| `PUT /mode` body `{mode}` | 200 stream; applies from the next update, running or not | 400 |
| `POST /crash-primary` | 202 `{nodeId, epoch, crashSource: "ACTION", status}` | 409 busy, `NO_SERVING_PRIMARY` |
| `POST /recover` | 202 `{nodeId, previousEpoch, status}`; recovers the latest run's old primary, which rejoins on its own | 409 busy, `NOTHING_TO_RECOVER` |
| `GET /runs` | 200 kept runs, newest first, `[]` when none | - |
| `GET /runs/latest` | 200 run | 404 `{title: "Unknown run", runId: null}` |
| `GET /runs/{runId}` | 200 run | 404 `runId`; 400 non-numeric id |

There is no separate timeline endpoint (Q5): the timeline is `milestones` of a run (`GET /runs/latest`, `GET /runs/{id}`).

**Failure and conflict matrix**

| Request | State | Answer |
| --- | --- | --- |
| any guarded action | another guarded action runs | 409 "Module busy", `moduleId`, `actionInProgress` |
| `POST /stream/start` | `mode` or `ratePerSecond` missing, rate < 1, unknown mode | 400 `errors.mode` / `errors.ratePerSecond`; unknown enum: 400 "Failed to read request" |
| `POST /stream/start` | rate above `max-rate-per-second` | 400 `errors.ratePerSecond: "must be between 1 and <max>"` |
| `POST /stream/start` | a stream runs | 409 `STREAM_RUNNING` |
| `POST /stream/start` | the session budget is used | 409 `UPDATE_LIMIT_REACHED`, "This session has used its budget of N updates. A cluster reset gives a fresh budget." |
| `POST /stream/stop` | no stream runs | 409 `STREAM_NOT_RUNNING` |
| `PUT /mode` | `mode` missing | 400 `errors.mode` |
| `POST /crash-primary` | not started | 409 `NO_SERVING_PRIMARY`, "Experiment 8 has not started yet, ..." |
| `POST /crash-primary` | a failover is in progress (latest run IN_PROGRESS) | 409 `NO_SERVING_PRIMARY`, "A failover is in progress: wait until the new primary serves before crashing it." |
| `POST /crash-primary` | started, first primary not serving yet | 409 `NO_SERVING_PRIMARY`, "No primary serves yet: the first primary is still being appointed." |
| `POST /recover` | no run since the reset | 409 `NOTHING_TO_RECOVER`, "No primary has failed since the last reset, ..." |
| `POST /recover` | the old primary is already up | 409 `NOTHING_TO_RECOVER`, "Node k, the old primary, is already up." |
| `POST /start` | already started | 200 `ALREADY_STARTED` with a reason (not a mistake) |
| `GET /runs/latest` | no run since the reset | 404 `runId: null` |
| `GET /runs/{id}` | id not kept (evicted or never existed) | 404 `runId` |

**Response shapes** (records in `dto/`; nulls kept on the wire; no nano-clock value is ever sent, so every number stays below 2^53 - 1)

- Status: `{status, actionInProgress, started, phase, currentPrimaryId, primaryEpoch, highestEpoch, nodes[], stream, splitBrain, dataLossNow, acknowledgedUpdates, rejectedEventCount}`. `currentPrimaryId`/`primaryEpoch` null while no primary serves; `dataLossNow` null while no primary store exists (before start, during a failover).
- Node: `{nodeId, nodeStatus, role, serving, epoch, believedPrimaryId, rejoin}`. `role` (PRIMARY/BACKUP), `epoch`, `believedPrimaryId` and `rejoin` are null while the node is down; `role`/`epoch` also null without a replication service.
- Stream: `{state RUNNING|STOPPED, mode, ratePerSecond, sent, acknowledged, gaveUp, inFlightSequence, maxUpdates, remainingUpdates, startedAt, stoppedAt, stopReason REQUESTED|LIMIT_REACHED|ERROR}`; counts are the current or last stream's; `maxUpdates`/`remainingUpdates` are always known (the session budget).
- Split brain: `{passed, highestEpoch, livePrimaries, violations[{kind, nodeIds, epoch, highestEpoch, message}], violationsSeen}`.
- Data loss: `{acknowledged, lost, lostKeys, lostSynchronous, lostAsynchronous, simulated, simulatedDelayMillis, simulatedReason, notAssessed}`; counts null when not assessed; delay and reason null when no simulated delay applied.
- Run: `{runId, oldPrimaryId, oldEpoch, crashSource, detectedByNodeId, newPrimaryId, newEpoch, outcome, milestones[{kind, nodeId, offsetMillis}], measurements{detectionMillis, failoverMillis, serviceRestoredMillis, outageMillis, recoveryMillis}, dataLoss}`. Milestone kinds: CRASHED, DETECTED, ELECTED, PROMOTED, SERVICE_RESTORED, OLD_PRIMARY_RECOVERED, DEMOTED, RESYNCHRONISED (recorded ones only, in time order, offsets in ms from the run's first instant on the backend's monotonic clock). `dataLoss` null until the service is restored.
- Settings: `{defaultRatePerSecond, maxRatePerSecond, maxUpdates, historyLimit, heartbeatIntervalMillis, heartbeatTimeoutMillis, retryWindowMillis, worstCaseFailoverMillis, asyncDelayMillis, asyncDelaySimulated, asyncDelayReason, coHostingSimulated, coHostingReason}`.

**Glossary** (served verbatim in the overview; the measured intervals keep the E8a names, Q3)

| id | label | kind | definition |
| --- | --- | --- | --- |
| detection | Detection time | measured | From the crash of the primary to the first moment any live node's failure detector suspected it. |
| failover | Failover time | measured | From that detection to the moment the new primary started acting as primary, after its catch-up and term record. |
| serviceRestored | Time to first update | measured | From the promotion to the first client update the new primary accepted. |
| outage | Outage | measured | From the crash of the primary to the first client update the new primary accepted, measured directly rather than added up. |
| recovery | Recovery time | measured | From the old primary coming back to the moment it had demoted itself and copied the new primary's store. |
| dataLoss | Data loss | measured | Updates the client was told had succeeded that the new primary does not hold at that version or newer, checked when the service is restored. |
| expectedDetection | Expected detection window | derived | Between the heartbeat timeout minus one interval and the timeout plus one interval after the crash: the last heartbeat may arrive up to one interval before the crash, and silence is checked once per interval. From the failure detector's settings; not a measurement. |
| worstCaseFailover | Worst-case failover | derived | Detection plus two promotion attempts, from the configured timeouts. The client keeps retrying at least this long. |

The four HANDOFF measurements are detection, failover, recovery and data loss; outage (crash to service) and time to first update are shown beside them.

**Notes** (served verbatim; `{d}` is `udcf.replication.async-delay-millis`, never typed, Q2)

1. Every update, heartbeat, role query and copy crosses a real socket on 127.0.0.1. The nodes share one process, so the network between them is the loopback interface.
2. In asynchronous mode the primary waits {d} ms before pushing each update to the backups. That delay is simulated: it stands in for network latency, and it is the window in which a crash loses updates the client was told had succeeded.
3. Synchronous mode confirms an update only after every backup push has finished, and the new primary catches up from the other live backups before taking over, so a crash of the primary loses no confirmed update.
4. Data loss counts only updates the client was told had succeeded. An update still being retried when the primary crashed is not lost; it is sent again to the new primary.
5. All intervals come from one monotonic clock in the backend. A crash started on the Cluster page is stamped as the node goes down, slightly late, and is marked so.
6. For now the new primary is the lowest-numbered live node that is ready; from Phase 9A it will be the leader elected by Experiment 4.
7. Backups refuse any update stamped with an older epoch than the newest they know, so a returning old primary cannot overwrite newer data even if it misbehaves.
8. Until Phase 9A this experiment and Experiment 5 share each node's replication service, so a write on the Experiment 5 page can move the primary.

Simulated badges: `settings.asyncDelaySimulated` with `asyncDelayReason`, `dataLoss.simulated` with `simulatedReason`, and `settings.coHostingSimulated` with `coHostingReason`. Nothing else is simulated.

**Events** (module `faulttolerance`; node events carry the node's Lamport tick, node 0 the cluster clock, the same convention as `ClusterResetService` and `LoadBalancingModule`; sentence case; nulls kept). E8b's events are unchanged. New in E8c, none per update:

| Type | Node, peer | Data |
| --- | --- | --- |
| `STREAM_STARTED` | 0 | mode, ratePerSecond, maxUpdates, remainingUpdates |
| `STREAM_STOPPED` | 0 | reason, sent, acknowledged, gaveUp, remainingUpdates |
| `MODE_CHANGED` | 0 | mode, previousMode |
| `OLD_PRIMARY_RECOVERED` | old primary | runId, previousEpoch |
| `FAILOVER_MEASURED` | new primary, peer = old | runId, oldPrimaryId, newPrimaryId, newEpoch, the five intervals |
| `DATA_LOSS_MEASURED` | new primary | runId, oldPrimaryId, newPrimaryId, newEpoch, acknowledged, lost, lostSynchronous, lostAsynchronous, simulated, simulatedDelayMillis, notAssessed |
| `RECOVERY_MEASURED` | old primary | runId, oldPrimaryId, newPrimaryId, newEpoch, recoveryMillis |
| `SPLIT_BRAIN_CHECKED` | 0 | checkedWhen, passed, livePrimaries, highestEpoch, violations |

`SPLIT_BRAIN_CHECKED` is published when the stream stops, when the service is restored, when the old primary has recovered, and when a new violation is first seen (the check also runs, unpublished, at every stream outcome). Reads (status, overview, runs) never publish an event and never change a counter (C1). No event on a busy rejection (Q7). Exp 5 publishes about 10 events per update on 5 nodes and about 6 on 3 (measured: 10.15 and 6.15 per update, `FaultToleranceModuleTest.eventVolumePerUpdateFitsTheBuffer`), which is why the stream has a budget.

**Metrics** (R5). Reused unchanged: `distributed_failures_total{node_id}` (failures detected), `distributed_recovery_duration{node_id, interval}` (the intervals; the count of `interval=outage` is the number of restored failovers), Exp 5's `distributed_replication_acks_total{node_id, result="STALE_EPOCH"}` (stale-epoch refusals). New in `MetricNames`: `distributed_failover_updates_total{node_id = accepting primary, or "0" if it gave up; model; status = acknowledged | gave_up}` (at most 24 series) and `distributed_failover_lost_updates_total{node_id = new primary; model}` (grows by the measured counts at each capture, a measured 0 included; at most 10 series). No new gauge; reads use `registry.find` only.

**Configuration** (`udcf.faulttolerance.stream`, `FaultToleranceModuleProperties`; `history-limit` 10 is reused for the kept runs, Q6)

| Key | Local | Public | Meaning |
| --- | --- | --- | --- |
| `default-rate-per-second` | 4 | 4 | the rate the page offers first |
| `max-rate-per-second` | 10 | 5 | highest rate a stream may ask for |
| `max-updates` | 400 | 200 | budget of one reset session: 400 x 10.15 = 4060 events (buffer 5000); 200 x 6.15 = 1230 (buffer 1500) |

**Link hooks.** L1: `FailoverCluster.tryPromote` -> `FailoverRoleSelector` (lowest live, ready node) -> `EpochAuthority.onLeaderElected(nodeId, at)`, the `// TODO(L1)` seam (Q1: there is no role provider; E8c does not call the election module). L2: E8b's `FailureListener` on the shared `FailureDetector`, registered by `FaultToleranceNodeService.start`; E8c's start starts the election service on every node. Left for Phase 9A.1: feed `onLeaderElected` from `LEADER_CHANGED` / `Cluster.leaderId()` and remove the selector; add PRIMARY/BACKUP to `NodeRole`; resynchronise a planned-handover target first; remove the Exp 5 interference.

**Reset and shutdown.** `reset()` stops the stream without an event (the cluster reset clears the history anyway), restores the update budget, forgets the captured data loss, the published recoveries and the violations, then calls `FailoverCluster.reset()`. Sequence numbers are not reset. A cluster reset (lab order: Exp 4, Exp 5, Exp 8) recovers every node; Exp 5's reset clears every store, so every node is at epoch 1 with no acting primary (role BACKUP where the replication service runs); Exp 8 is not started and has no runs; the next start appoints node 1 at epoch 2. `close()` (Spring, `AutoCloseable`): bus subscription, stream thread, then the engine's client thread; each wait at most 5 s. The cluster reset is refused only while the guard is held.

**Test ports** (Track B block; grep of the whole test tree before and after this step; no class shares a range)

| Class | Range | What binds |
| --- | --- | --- |
| `UpdateStreamTest`, `FaultToleranceDtoMapperTest`, `FaultToleranceModulePropertiesTest`, `FaultToleranceUpdateMetricsTest` | none | nothing |
| `FaultToleranceModuleTest` (`FaultToleranceModuleHarness`, no Spring) | 26460-26495 | election 26473-26477, replication 26479-26483; RMI, clock, requests and MapReduce never start |
| `FaultToleranceControllerTest` (own context, MockMvc) | 26500-26555 | election 26521-26525, replication 26531-26535; all six bases overridden, the rest lazy and never started |
| `FaultToleranceEndToEndTest` (own context, random HTTP port) | 26700-26755 | election 26721-26725, replication 26731-26735; HTTP on an ephemeral port |

Ranges taken before E8c: 26100-26199, 26200-26255, 26300-26355, 26360-26395, 26400-26455, 26600-26655, 26801-26856. Still free after E8c: 26456-26459, 26496-26499, 26556-26599, 26656-26699, 26756-26800, 26857-26899. No test binds a plain blocker socket.

**For E8d**

1. Keys do not restart at `setting-0001` after a reset: sequence numbers go on, because an update in flight at a reset may still be accepted later and must not collide with a new key.
2. The timeline is the run's `milestones`: offsets in ms from the run's first instant on the backend's monotonic clock, not wall times. Line them up with events by kind, not by time; event `wallTime` is display only.
3. Exp 5 interference until Phase 9A.1 (E8b known issue): an Exp 5 action can move the primary; the page shows note 8.
4. Stream budget: `stream.maxUpdates` and `stream.remainingUpdates` (status and overview). At 0, `POST /stream/start` answers 409 `UPDATE_LIMIT_REACHED` with "A cluster reset gives a fresh budget."; show that text and offer the cluster reset.
5. Polling: one status read is in-process only (no socket): it walks the nodes, runs the split-brain check over each node's replication service, and copies the acknowledged ledger and the primary's store for the live data-loss check (both bounded by `max-updates`); it publishes nothing. Refresh on `/topic/modules/faulttolerance` events; while a stream runs or a failover is in progress, polling `GET /status` every 500 ms is affordable. Measured over HTTP during the capture (curl `time_total`): 4 status reads 0.0025 to 0.0037 s; 4 overview reads 0.0018 to 0.0031 s, except the first request after startup (0.036 s).
6. `POST /crash-primary` blocks while the node's services close. Count from the code (sequential waits, each bounded at 5 s): election 3 (`ElectionNodeService.java:577-585`), faulttolerance 3 (`FaultToleranceNodeService.java:643-649`), replication 5 (`ReplicationNodeService.java:830-837`): 11 waits, worst case 55 s for a node running only Experiment 8's services; with every service on the node also mapreduce 2, requests 1, RMI 1, clock 0: 15 waits, 75 s. Usual duration, measured over HTTP during the capture (curl `time_total`): 0.0087 s (synchronous scenario) and 0.0050 s (asynchronous scenario); 0.0082 s in the discarded first capture. Both worst cases exceed the page's 10000 ms HTTP timeout; a timeout there does not mean the crash failed: refetch the status.
7. Synthetic fixture states (not producible for real; build as labelled synthetic copies): see the fixture README; at least a split-brain violation (`passed: false`), run outcomes `INTERRUPTED` and `PRIMARY_RETURNED` (the latter is real in `FaultToleranceModuleTest.oldPrimaryReturnsBeforePromotion` but not captured), and `ROLE_QUERY_FAILED` / `NO_CANDIDATE` states.
8. Recover while a failover is still in progress, C8: if the old primary comes back before anyone was chosen (no live node detected it, or no candidate), the run ends `PRIMARY_RETURNED` with `newPrimaryId: null`, milestones CRASHED and OLD_PRIMARY_RECOVERED, every interval null, `dataLoss` null; the node rejoins through its role query. Tested deterministically with every other node down (`oldPrimaryReturnsBeforePromotion`). If a node was already chosen, the run continues and the old primary demotes as usual.
9. Recovery time: read `recoveryMillis` from `GET /runs` or `GET /runs/latest`; never wait for the `RECOVERY_MEASURED` event, which can be missing when no stream runs and no later action happens (see the recovery event race in Known issues).
10. A failover only completes with a running stream: the run ends RESTORED at the new primary's first accepted update, so without a stream it stays IN_PROGRESS (phase PROMOTING) and `dataLoss` stays null. `POST /crash-primary` answers 409 "A failover is in progress" only until the new primary serves (detection plus promotion, about 2.25 s in the capture); from then on the new primary counts as serving (its term record is written, `FaultToleranceNodeService.promote`), so a second crash is accepted (202): the earlier run ends INTERRUPTED and a new run opens (`FailoverStateMachine.open`). The page must start a stream before offering "Crash primary" (or disable the button with a hint), so every run can be restored and measured.

---

## E8d: the Experiment 8 page (frontend only)

Route `/experiments/8-faulttolerance` (the catalog slug; no router or sidebar change: the sidebar status comes from `GET /api/modules`, which lists `faulttolerance` since E8c). Built on the E2d kit (`ExperimentLayout`, `MetricCard`, `SimulatedBadge`, `ModuleEventLog`), the shared axios client, `useStomp`, `useCluster`, `useToast`, Exp 1's `describeProblem` and Exp 2's `usePoliteAnnouncement`. No backend file, `package.json` or lock file changed; no new dependency.

**Files created** (`frontend/src/modules/faulttolerance/`): `index.jsx`, `FaultTolerancePage.jsx`, `FaultToleranceControls.jsx` (with the module-specific `ProblemAlert`), `ReplicaRail.jsx`, `FailoverTimeline.jsx` (hand-written SVG), `MeasurementsPanel.jsx` (with `GlossaryTerm`, `NotMeasured`), `DataLossPanel.jsx`, `SplitBrainPanel.jsx`, `useFaultTolerance.js`, `faultToleranceApi.js`, `faultToleranceModel.js`; tests `faultToleranceApi.test.js` (17), `faultToleranceModel.test.js` (45), `useFaultTolerance.test.js` (17), `ReplicaRail.test.jsx` (8), `FailoverTimeline.test.jsx` (9), `MeasurementsPanel.test.jsx` (8), `DataLossPanel.test.jsx` (6), `SplitBrainPanel.test.jsx` (4), `FaultToleranceControls.test.jsx` (13), `FaultTolerancePage.test.jsx` (12), `contract.test.js` (53). Synthetic fixtures in `frontend/src/test/fixtures/faulttolerance/synthetic/` (below).

**Files changed (add-only):** `frontend/src/lib/experiments.js` (the lab 8 entry gets `concept`; its one-line object becomes the multi-line form the other entries use: 1 line removed, 7 added); `frontend/src/routes.test.jsx` (one import of the api, one fixture import, three spies so the route test never reaches the network: 5 lines added, 0 removed).

**UI states** (`faultToleranceModel.uiState`, most specific first) and the enabled controls (`controlsModel`; every control is also disabled while a request from the page is pending, and after a crash or recover timeout until the backend is idle, C2):

| State | Detected by | Start | Mode | Rate, Start stream | Stop stream | Crash primary | Recover old primary |
| --- | --- | --- | --- | --- | --- | --- | --- |
| not-started | `!started` | on | on | on (also starts Exp 8) | off | off: "Start the update stream first ..." | off: "No primary has failed since the last reset." |
| failover | run IN_PROGRESS, or phase SUSPECTED/PROMOTING, stream running | off | on | off (running) | on | off: "A failover is in progress ..." (Q3) | on while the old primary is down (C8) |
| failover-waiting (C3) | as failover, stream stopped | off | on | on, unless the budget is 0 | off | off | on while the old primary is down |
| appointing | started, `currentPrimaryId` null | off | on | per stream | per stream | off: "No primary serves yet ..." | per run |
| recovering | run RESTORED, OLD_PRIMARY_RECOVERED without RESYNCHRONISED | off | on | per stream | per stream | per stream | off: "Node k is rejoining." |
| restored | run RESTORED, old primary down | off | on | per stream | per stream | per stream | on |
| streaming | stream RUNNING | off | on | off | on | on | per run |
| demoted | run has DEMOTED, stream stopped | off | on | on | off | off: start the stream first | off: "Node k, the old primary, is already up." |
| no-stream | otherwise | off | on | on | off | off: start the stream first | per run |

Budget (Q1): at `remainingUpdates` 0, "Start stream" is disabled with "Budget used: N of N updates this session. A cluster reset gives a fresh budget." (N = `maxUpdates`) and a link to `/cluster`. A 409 `UPDATE_LIMIT_REACHED` (a race) still shows the backend text and the link.

**Data flow.** Mount and STOMP reconnect: `GET /api/modules/faulttolerance` (its overview carries `current`, `latestRun`, settings, glossary, notes, so a reload mid-failover restores everything, C5). Refresh: `GET /status` and `GET /runs/latest` (404 = no run, the empty state), coalesced, on every `/topic/modules/faulttolerance` and `/topic/cluster` message and after each action. Polling (`useFaultTolerance`):

| Constant | Value | When |
| --- | --- | --- |
| `STATUS_POLL_INTERVAL_MS` | 500 | `GET /status` while the stream runs, a failover is in progress, the old primary is rejoining, or after a timeout until idle (C2); `GET /runs/latest` joins it while a failover or a recovery is open, while no primary serves and after a timeout |
| `FALLBACK_POLL_INTERVAL_MS` | 2000 | status and latest run while the STOMP connection is not `connected` (no events arrive); the existing "backend is not reachable" banner shows for `reconnecting`/`error` |

Nothing is polled otherwise. Timeouts: the shared 10000 ms for every call (Q2). A timeout of crash-primary or recover says "The request timed out after 10 s. The crash (recovery) may still have happened; reading the status again." and keeps every action disabled until a status read started after the timeout shows `actionInProgress` null, then reads status and run again (C2).

**Honesty.** A null is "Not measured", "Not assessed" or "Unknown" with its reason, never 0; a measured 0 is "0 lost". Simulated badges only for `settings.asyncDelaySimulated` (on the asynchronous mode), `dataLoss.simulated` (data loss panel and card) and `settings.coHostingSimulated` (the rail), each with its served reason. Labels, kinds and definitions are the served glossary; the explanation is the eight served notes. The expected detection window is computed only from `heartbeatTimeoutMillis` minus and plus `heartbeatIntervalMillis` (C4), shown as a dashed band labelled derived and in a separate "Derived" box; hidden or "Not available" if a setting is missing. `recoveryMillis` is read from the run only.

**Synthetic fixtures** (`synthetic/README.md`; copies of real captures, each test that uses one says "synthetic"): `status-split-brain-failed.json` (from `status-stream-running.json`), `run-in-progress.json`, `run-interrupted.json`, `run-primary-returned.json` (from `run-sync-restored.json`), `run-recovering.json` (from `run-sync-recovered.json`), `status-stream-error.json` (from `status-stream-running.json`), `status-rejoin-stalled.json` and `status-restored-old-primary-down.json` (from `status-after-demotion.json`), `status-failover-stream-stopped.json` (from `status-during-failover.json`), `events-synthetic.json` (ROLE_QUERY_FAILED and NO_CANDIDATE, from the first event of `events.json`). The 39 real fixtures are unchanged.

**End-to-end checklist** (run by the person; result column left empty):

| # | Action | Expected | Result |
| --- | --- | --- | --- |
| 1 | `cd backend; .\mvnw.cmd spring-boot:run` and `cd frontend; npm run dev`; open `http://localhost:5173/experiments/8-faulttolerance` with the browser console open | Lab badge 8, title, concept; "Experiment 8 has not started ..."; every node's epoch "Unknown"; timeline "No failover yet"; sidebar Exp 8 not "Planned"; console clean | PASS |
| 2 | Click "Start experiment 8" | Toast; node 1 Primary at epoch 2, nodes 2 to 5 Backup at epoch 2; the start button disabled | PASS |
| 3 | Look at "Crash primary" | Disabled, hint "Start the update stream first: ..." | PASS |
| 4 | Synchronous, rate 4, "Start stream" | Stream running; sent and acknowledged rise; budget falls from 400; shield "No two primaries: check passed"; "Crash primary" enabled | PASS |
| 5 | "Crash primary" | Node 1 Down, epoch "Unknown"; "Failover in progress"; timeline fills CRASHED, DETECTED (inside the derived band), ELECTED, PROMOTED, SERVICE_RESTORED; node 2 Primary at epoch 3; "Crash primary" disabled during the failover | PASS |
| 6 | Read Measurements and Data loss | Detection about 1.8 to 3.2 s and "inside the expected window"; failover, time to first update, outage in ms; recovery "Not measured"; data loss "0 of N", "0 lost", no Simulated badge on the loss | PASS |
| 7 | "Recover old primary" | Node 1 "Demoted itself: primary at epoch 2, now backup at epoch 3"; recovery rows filled; recovery time measured; shield passed; recover disabled ("already up") | PASS |
| 8 | "Stop stream"; Cluster page, reset the cluster; back to the page | Not started; no run; "400 of 400 updates left" | PASS |
| 9 | Asynchronous (Simulated badge, reason on hover or focus), rate 10, "Start stream"; after a few acknowledged updates "Crash primary" | After the restore: at least 1 lost (if 0, repeat once from step 8 and note it), lost keys listed, all asynchronous, Simulated badge with its reason, delay 450 ms | PASS (4 lost, all asynchronous, Simulated badge, 450 ms) |
| 10 | "Stop stream" | "Crash primary" disabled again with its hint | PASS |
| 11 | C3: "Start stream", "Crash primary", then at once (within 2 s) "Stop stream" | "The new primary only starts serving when the next update arrives. Start the update stream to finish the failover."; crash disabled, "Start stream" enabled; "Start stream" again: SERVICE_RESTORED appears and the measurements fill | PASS |
| 12 | C5: "Crash primary" with the stream running and reload the page (F5) within 2 s | The page comes back with the open run on the timeline (recorded rows filled, the rest hollow) and keeps filling by itself; controls as in the failover state | PASS |
| 13 | 409: open the page in two tabs with the stream running; click "Crash primary" in both at the same moment | One tab gets the toast; the other shows a calm warning (busy, or "No primary to crash right now. A failover is in progress ..."); no broken page | PASS |
| 14 | Q1: reset the cluster; "Start stream" at rate 10 and let it run out (400 updates) | "Stopped: the session budget is used."; "0 of 400 updates left"; "Start stream" disabled with "Budget used: 400 of 400 updates this session. A cluster reset gives a fresh budget." and the Cluster page link (the 409 text itself is covered by the tests) | PASS |
| 15 | Throughout; then devtools at 375 px; keyboard only; OS reduced motion | Shield never "Two primaries seen"; console clean; usable at 375 px (the timeline scrolls inside its own box only); every control reachable with visible focus; no animation with reduced motion | PASS |
| 16 | Stop both servers; `Get-NetTCPConnection -LocalPort 8080,5173 -State Listen` | Nothing listening | PASS (ports free) |

Run by Swanand on 2026-10-16 against the real backend; browser console clean.

Playwright: not installed (not in `package.json`, no specs or config); adding it is a new dependency (R15), so the end-to-end check is this manual list.

**For Phase 9A**

1. When L1 and L2 are wired, served note 6 (lowest-numbered ready node) and note 8 (Exp 5 interference) change or disappear: update `NOTE` in `faultToleranceModel.js`; `contract.test.js` pins eight notes and note 8's wording, so it fails on purpose when the served notes change.
2. The rail must then show the elected leader as the primary. It reads `role` from the status nodes, so no page change is needed if the backend reports it; the `exp5-note` line goes away with note 8.
3. Polling needs no change: it follows the stream, the run and the phase, not the selector.

---

## 9A.1: the shared role provider, Experiment 8 on the elected leader, and L2

**Leader crash with the update stream running** (5 nodes; node 5 is leader and primary at epoch 2):

1. HTTP thread: `POST .../crash-primary` stamps the crash (ACTION) and calls `Cluster.crash(5)`: node 5's services close, its roles clear; `LEADER_CHANGED` (leader none) and `REPLICATION_ROLES_CHANGED` (primary none) are published; the provider queues a `LEADER_CRASHED` change. Experiment 8's leader listener (dispatcher) finds no leader and waits.
2. About 1.8 to 3.2 s later, on the election worker of the first node k whose detector passes the timeout: `PEER_SUSPECTED` is published, then the detector's listeners run in registration order: Experiment 8's (registered first, at its start) records DETECTED (`FAILURE_DETECTED`) and only queues a follow-up on the dispatcher; Experiment 4's opens the `LEADER_FAILURE` round (once) and queues Bully on node k. Neither waits. One suspect event, one detector, one timeout (L2).
3. Election workers: node 4, the highest live node, elects itself after its ok-timeout; every live node accepts it; the leader rule calls `Cluster.assignLeader(4)`: `LEADER_CHANGED` (4), and the provider queues `ELECTED`.
4. Dispatcher (`udcf-roles-dispatcher`): `FailoverCluster.followLeader()` sees leader 4, ready: `EpochAuthority.onLeaderElected(4)` issues epoch 3 (milestone ELECTED), the state machine applies the choice, and the promotion goes to node 4's worker.
5. `udcf-faulttolerance-n4-worker`: catch-up from the ready peers except node 5, `becomePrimary(3)` (PROMOTED), the term record, serving, `PRIMARY_PROMOTED`, then PRIMARY on 4 and BACKUP on 1 to 3 (`REPLICATION_ROLES_CHANGED`).
6. Client thread: the next retry finds node 4; the accepted update restores the service (SERVICE_RESTORED).

**Planned handover** (the recovered node 5 wins Bully again): node 5 rejoins as a backup (role query, demote, resynchronise); once it is READY, and once any open failover run has ended (its first accepted update), the dispatcher issues epoch e + 1 for node 5 (recorded as a pending handover; no run opens); on the client thread (so no update is in flight) the old primary stops serving and steps down (`HANDOVER_STARTED`, PRIMARY cleared); node 5's worker catches up from every ready peer including the fenced old primary, calls `becomePrimary(e + 1)`, writes the term record and serves.

**Milestones:** CRASHED, DETECTED, PROMOTED, SERVICE_RESTORED and the recovery milestones are unchanged. ELECTED is the instant Experiment 8 receives the leader elected by Experiment 4 (on the dispatcher, stamped with Experiment 8's nano clock); it always follows the election's `LEADER_CHANGED` and the detection.

**Worst-case failover** = detection (heartbeat timeout + interval) + election (ok + coordinator timeout) + one promotion (catch-up + 2 x replication timeout) = 3200 + 3100 + 4000 = 10300 ms, locally and in the public profile (it overrides none of these timings); the retry window 50 x 250 = 12500 ms is 21.4 % above it (`FaultTolerancePropertiesTest.yamlWindowCoversWorstCaseWithMargin` checks the 10 % margin in both profiles).

**`TODO(L1)` seams after 9A.1** (grep of `backend/src`; Experiment 8's are gone):

| Owner | Step | Files (lines) |
|---|---|---|
| Jai | 9A.3 | `replication/ReplicaSet.java` (24, 86), `replication/ReplicationRoleSelector.java` (18, 27), `mpi/MpiRoleSelector.java` (30), `matrix/MatrixModule.java` (67) |
| Rohan | 9A.4 | `clocksync/berkeley/ClockSyncRoleSelector.java` (15, 26), `mapreduce/MapReduceRoleSelector.java` (23, 41), `mapreduce/MapReduceModule.java` (51, 402), `mapreduce/dto/MapReduceOverviewDto.java` (13), `mapreduce/dto/RunDto.java` (18) |

**Files.** New: `core/cluster/RoleProvider.java`, `LeaderChange.java`, `LeaderListener.java`, `LeaderElector.java`; tests `RoleProviderTest`, `LeaderFailoverIntegrationTest`. Deleted: `FailoverRoleSelector.java`, `FailoverRoleSelectorTest.java`. Changed: `NodeRole`, `Cluster`, `ClusterNode` (Javadoc), `ElectionModule`, `RoundTrigger`, `FailoverCluster`, `FaultToleranceNodeService`, `FaultToleranceModule`, `FaultToleranceProperties`, `FaultToleranceConflictException`, `EpochAuthority`, `application.yml` (comment); tests `ElectionModuleTest`, `FailoverFixture`, `FaultToleranceModuleHarness`, `FailoverClusterTest`, `FaultToleranceNodeServiceTest`, `FaultToleranceModuleTest`, `FaultToleranceControllerTest`, `FaultToleranceEndToEndTest`, `FaultTolerancePropertiesTest`; frontend `faultToleranceModel.js`, `FaultTolerancePage.jsx`, tests `faultToleranceModel.test.js`, `ReplicaRail.test.jsx`, `MeasurementsPanel.test.jsx`; fixtures `overview-before-start.json`, `overview-after-start.json`, `overview-after-runs.json`, `overview-after-reset.json` (recaptured by Swanand on 2026-10-17) and the fixture READMEs.

**Test ports:** `LeaderFailoverIntegrationTest` 26560-26595 (election 26573-26577, replication 26579-26583); `RoleProviderTest` names bases 26760-26790 and binds nothing. Still free in the Track B block: 26456-26459, 26496-26499, 26556-26559, 26596-26599, 26656-26699, 26756-26759, 26791-26800, 26857-26899.

**Frontend (R5).** The page reads roles from the status, so it shows the elected leader as primary without change. Three fixes: the "demoted" state now applies only while the old primary is not primary again (`faultToleranceModel.js:106`); with no serving primary after a primary was ever appointed, the text is "No primary serves right now: Experiment 8 waits for the elected leader to take over." (`faultToleranceModel.js:117-130`, and the crash hint at line 162); the second "what to notice" item adds that the old primary then wins the election again and takes over at a higher epoch. `HANDOVER_STARTED` shows in the module event log as "Handover started"; a handover opens no run, so it has no timeline row.

**Manual checklist** (run by Swanand on the real backend; Result left empty):

| # | Action | Expected | Result |
|---|---|---|---|
| 1 | `cd backend; .\mvnw.cmd spring-boot:run` (fresh) and `cd frontend; npm run dev`; open `http://localhost:5173/experiments/8-faulttolerance` with the browser console open | "Experiment 8 has not started ..."; the Cluster page shows no role on any node | PASS |
| 2 | "Start experiment 8" | node 5 Primary at epoch 2, nodes 1 to 4 Backup at epoch 2; Experiment 4 page: a finished round "Requested" started from node 1, leader node 5; Cluster page: node 5 "LEADER, PRIMARY", nodes 1 to 4 "BACKUP"; top bar leader node 5 | PASS |
| 3 | Synchronous, rate 4, "Start stream" | sent and acknowledged rise; "No two primaries: check passed" | PASS |
| 4 | "Crash primary" | node 5 Down; timeline CRASHED, DETECTED, ELECTED, PROMOTED, SERVICE_RESTORED; Experiment 4 page: a round "After the leader failed" elected node 4; node 4 Primary at epoch 3; Cluster page node 4 "LEADER, PRIMARY"; data loss "0 lost" | PASS |
| 5 | With the stream still running, "Recover old primary" | node 5 demotes itself and resynchronises (event log: role query, old primary demoted, resynchronised), wins the election (Experiment 4 page: round "After a recovery", leader node 5), then "Handover started" in the log and node 5 Primary at epoch 4, node 4 Backup at epoch 4; never two nodes shown Primary on the rail or the Cluster page; data loss still 0 | PASS |
| 6 | "Stop stream"; on the Cluster page crash node 5; back on Experiment 8 | node 4 elected (Experiment 4 page) and promoted at epoch 5; the page shows "The new primary only starts serving when the next update arrives. Start the update stream to finish the failover." | PASS |
| 7 | "Recover old primary" (no stream) | node 5 demotes and resynchronises and is leader again (Cluster page: node 5 "LEADER", node 4 "PRIMARY"): Known issue (a), the failover run waits for an update | PASS (node 5 LEADER while node 4 stayed PRIMARY at epoch 5 until the stream restarted: Known issue (a)) |
| 8 | "Start stream" | the run ends SERVICE_RESTORED, then the handover completes: node 5 Primary at epoch 6, node 4 Backup; never two primaries | PASS |
| 9 | "Stop stream"; Cluster page: reset the cluster | no role on any node; Experiment 8 not started, no run | PASS |
| 10 | Cluster page: crash every node; Experiment 8: "Start experiment 8" | after about 3 s a calm error with "No leader could be elected, so Experiment 8 has no primary: no live node won an election in time. Recover a node or reset the cluster, then try again."; no broken page; then reset the cluster | PASS |
| 11 | Experiment 8 page, under the replica rail | note 8 still shown ("Until Phase 9A this experiment and Experiment 5 share each node's replication service, ...") | PASS |
| 12 | Experiment 8 "What to notice" | the second item ends "As the highest node it then wins the election again, copies the store of the primary and takes over at a higher epoch." | PASS |
| 13 | Throughout; then stop both servers and run `Get-NetTCPConnection -LocalPort 8080,5173 -State Listen` | browser console clean; nothing listening | PASS |

Run by Swanand on 2026-10-17 against the real backend; all 13 steps passed; browser console clean.

---

## 9A.6 (fast mode)

**What changed.**
- **New package `com.udcf.scenario`:**
  - The engine: `Scenario`, `ScenarioStep`, `StepContext`, `RunMemory`, `ScenarioEngine`, `ScenarioRun`.
  - The API: `ScenarioController`.
  - Helpers and configuration: `Narration`, `EventData`, `ScenarioProperties`.
  - Exceptions: `StepFailedException`, `ScenarioBusyException`, `UnknownScenarioException`, `UnknownScenarioRunException`.
  - DTOs in `dto/`.
  - In `scenarios/`: `FailoverSteps`, the three scenarios and `SplitBrainSampler`.
- **How a run works:**
  - One run at a time, on the daemon thread `udcf-scenario-runner`. The last 10 runs are kept in memory.
  - It starts with a clean cluster reset, retried while a module is BUSY.
  - Each step acts on module beans (never HTTP to itself), then waits for its expectation by bus subscription with a 250 ms polling fallback, under the step's deadline.
  - A failed step makes the remaining steps SKIPPED and the run FAILED.
  - The cleanup always runs, in a finally.
  - The end state is read from the cluster, and there is no automatic reset afterwards.
- **R1:** consequences of an action are awaited from the action's anchor, the bus sequence just before it, not from the waiting step's start. Order is checked by bus sequence (`requireOrder`), and both sequence numbers go into the facts.
- **R3:** scenario a crashes the leader through `FaultToleranceModule.crashPrimary()`, which calls `FailoverCluster.crashPrimary()` -> `Cluster.crash(primary)` (`FailoverCluster.java:204`). That crashes the whole node on every protocol, so Experiment 4 elects a new leader. The step first checks that the crashed node is the leader that step 1 recorded.
- **Note 8 removed:** Experiment 8's served note 8 ("Until Phase 9A ... a write on the Experiment 5 page can move the primary") was obsolete after 9A.3. It is removed from `FaultToleranceModule.notes`, and the Experiment 8 page no longer shows the `exp5-note` line (`ReplicaRail.jsx`, `FaultTolerancePage.jsx`, `NOTE` in `faultToleranceModel.js`). The four `overview-*.json` fixtures are recaptured by the block below.
- **Configuration:** a new `udcf.scenario` block in `application.yml` (`history-limit` 10, `reset-timeout-millis` 20000, `step-deadline-millis` 30000, `poll-interval-millis` 250, `slow-node.work-units` 900). `application-public.yml` sets `slow-node.work-units` 400, which keeps a comparison within its load balancing limits: 5 x 60 x 400 = 120000.
- **Observed on real sockets** (`ScenariosEndToEndTest`, Windows, this session):
  - "The leader dies mid-write": node 1 detected the crash after 2796 ms of silence; node 4 was elected (event 542) and promoted at epoch 3 (event 559); the outage was 3578 ms and 0 of 5 confirmed updates were lost.
  - "Split-brain that never happens": node 5 demoted at epoch 3, resynchronised from node 4, was re-elected, and node 4 was fenced before node 5 became primary at epoch 4. The sampler took 49 checks over 5298 ms, saw at most 1 primary and 0 violations.
  - "A slow node under load": round robin took 129 ms, weighted round robin 67 ms, least connections 48 ms and least response time 84 ms. Round robin sent 12 of 60 requests to node 3; least connections sent it 4.

**Interfaces for other tracks:** see "9A.6 APIs" under Interfaces for other tracks (endpoints, run shape, events, the add-a-scenario recipe, `FailoverSteps`).

**Tests** (32 new):
- `ScenarioEngineTest` (17): deadline, busy, failure leads to SKIPPED, cleanup after a failure and after an exception, an anchor accepting an event published before the waiting step (R1), subscription wake-up, polling fallback, a cluster reset by someone else, reset retry and reset timeout, history, end state.
- `NarrationTest` (3), `EventDataTest` (2), `SplitBrainSamplerTest` (2).
- `ScenarioControllerTest` (4): MockMvc with every status and body shape; narration contains no numbers apart from lab names.
- `ScenariosEndToEndTest` (3): each scenario once on real sockets. For scenario c the test accepts PASSED, or FAILED only on the measured step; either way it checks the four makespans and the per-strategy requests to the slow node (R4).
- `LeaderCrashEndToEndTest` (1): Experiments 3, 7, 9 and 8 used, the leader node crashed; `TIME_DAEMON_CHANGED`, `COORDINATOR_CHANGED`, `MPI_ROOT_CHANGED`, `PRIMARY_PROMOTED` and `REPLICATION_ROLES_CHANGED` all name the new leader and all come after its `LEADER_CHANGED` by bus sequence. Each change event appears exactly once, and the next round and collective use the new leader.

**Test ports** (a search of the test sources found these decades free before this step; nothing else uses them):

| Class | Bound | Bases only (never bound) |
|---|---|---|
| `ScenariosEndToEndTest` | election 26661-26665, replication 26671-26675, requests 26681-26685 | RMI 26690, clock 26260, MapReduce 26270 |
| `LeaderCrashEndToEndTest` | RMI 26861-26865, clock 26871-26875, election 26881-26885, replication 26891-26895, MapReduce 26161-26165 | requests 26170 |
| `ScenarioControllerTest`, `ScenarioEngineTest` | nothing | the `ScenariosEndToEndTest` bases, as numbers |

Still free in the Track B block: 26280-26299, 26456-26459, 26496-26499, 26556-26559, 26596-26599, 26756-26759, 26791-26800.

**Known issues.**
- **Waits versus module calls:** the step deadline bounds waits, not the module calls themselves. For example, `crashPrimary` joins the node's service threads, at worst 55 s (E8c Known issues). A run still ends, and the slow step's `durationMs` shows it.
- **Resets from elsewhere:** the engine is not an `ExperimentModule`, so a cluster reset from the Cluster page during a run is not refused. The waiting step then fails with "The cluster was reset during the scenario (event N), so the scenario cannot continue." and the run is FAILED.
- **Sampler reads are not atomic:** the split-brain sampler uses Experiment 8's own check, which reads each node's role in turn (9A.1 Known issues (c)). In principle a read that spans the handover fence could report a stale primary for one sample; the fence comes first and the catch-up sits in between, and this was never seen.
- **Scenario c is measured:** on a heavily loaded machine round robin may not finish last. The step then fails honestly with the measured makespans.
- **Runs are in memory only** and are lost on a backend restart. There is no cancel (ruling (a)).
- **The `facts` object** in `STEP_PASSED` and `STEP_FAILED` event data is nested. The event export for MapReduce (L5) does not export `data` (only `latencyMillis`), so it is unaffected.
- **Contract tests until the recapture (resolved):** before the four overview fixtures were recaptured, `contract.test.js` failed its four "seven notes" cases on purpose (the stale fixtures still carried note 8). The recapture came after the first commit; afterwards all 1294 frontend tests passed (reported by Swanand).

**Gaps for owners.**
- **Rohan (app shell, Steps 2.2 and 2.3):** `frontend/src/components/layout/AppLayout.test.jsx` makes real XHR requests that jsdom cannot connect. They print 38 `Error: AggregateError` stack traces (jsdom `xhr-utils.js:62`, `dispatchError`) to stderr during `npm test`. Every test still passes. Found with a sequential verbose run (`npx vitest run --reporter=verbose --no-file-parallelism`): all 38 traces print right after that file's last test and before the next file. Not fixed.
- Nidhi's 9A.2 listed no gaps for Track B. Her optional note on the unused `lamportTime` counter in `BullyAlgorithm` and `RingAlgorithm` is left as it is (E4a unchanged, ruling (g)).

**Fixture capture** (the four Experiment 8 overview files; run once against a freshly started backend, then close the terminal):

<details>
<summary>The 9A.6 capture block</summary>

```powershell
# 9A.6 fixture recapture: the four Experiment 8 overview files (served note 8 removed), with the 9A.1 steps.
# Needs the backend running on 8080 (cd backend; .\mvnw.cmd spring-boot:run). Writes files with curl.exe -o.
$Api  = 'http://localhost:8080'
$Ft   = "$Api/api/modules/faulttolerance"
$Fx   = 'C:\Users\rohan\Desktop\DC\PROGRAM\unified-distributed-coordination-framework\frontend\src\test\fixtures\faulttolerance'
$Body = Join-Path $env:TEMP 'udcf-9a6-stream.json'
$Rows = New-Object System.Collections.Generic.List[object]

function Wait-Until([scriptblock]$Condition, [int]$Seconds, [string]$What) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try { if (& $Condition) { Write-Host "  ok: $What"; return $true } } catch { }
        Start-Sleep -Milliseconds 500
    }
    Write-Host "  TIMEOUT after $Seconds s: $What" -ForegroundColor Red
    return $false
}

function Reset-Cluster {
    $null = Wait-Until { $busy = @((Invoke-RestMethod "$Api/api/modules") | Where-Object { $_.status -eq 'BUSY' } | ForEach-Object { $_.id }); $busy.Count -eq 0 } 30 'no module is BUSY'
    for ($try = 1; $try -le 20; $try++) {
        $code = & curl.exe -s -o NUL -w '%{http_code}' -X POST "$Api/api/cluster/reset"
        if ($code -eq '204') { Write-Host "  reset: 204 (try $try)"; return $true }
        Write-Host "  reset: $code (try $try), retrying"
        Start-Sleep -Milliseconds 500
    }
    Write-Host '  reset failed 20 times' -ForegroundColor Red
    return $false
}

function Post([string]$Url, [string]$Json) {
    if ($Json) {
        [IO.File]::WriteAllText($Body, $Json)
        $code = & curl.exe -s -o NUL -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary "@$Body" $Url
    } else {
        $code = & curl.exe -s -o NUL -w '%{http_code}' -X POST $Url
    }
    Write-Host "  POST $Url -> $code"
    return $code
}

function Capture([string]$Name) {
    $path = Join-Path $Fx $Name
    $code = & curl.exe -s -o $path -w '%{http_code}' $Ft
    $bytes = (Get-Item $path).Length
    $text = [IO.File]::ReadAllText($path)
    $notes = -1
    try { $notes = @(($text | ConvertFrom-Json).notes).Count } catch { }
    $ok = ($code -eq '200') -and ($notes -eq 7) -and ($text -notmatch 'Until Phase 9A') -and ($bytes -gt 0)
    $Rows.Add([pscustomobject]@{ File = $Name; HTTP = $code; Bytes = $bytes; Notes = $notes; OK = $ok })
    Write-Host "  captured $Name ($code, $bytes bytes, $notes notes)"
}

$healthy = $false
try { $health = Invoke-RestMethod "$Api/api/system/health"; $healthy = $true; Write-Host "Backend health: $($health.status)" }
catch { Write-Host "Backend not reachable at $Api. Start it first: cd backend; .\mvnw.cmd spring-boot:run" -ForegroundColor Red }

if ($healthy) {
    Write-Host '1. Clean slate, before start'
    $null = Reset-Cluster
    Capture 'overview-before-start.json'

    Write-Host '2. Start Experiment 8'
    $null = Post "$Ft/start" $null
    $null = Wait-Until { $s = Invoke-RestMethod "$Ft/status"; $s.currentPrimaryId -eq 5 -and @($s.nodes | Where-Object { $_.rejoin -ne 'READY' }).Count -eq 0 } 30 'node 5 serves and every node is READY'
    Capture 'overview-after-start.json'

    Write-Host '3. Stream, crash, recover, handover'
    $null = Post "$Ft/stream/start" '{"mode":"SYNCHRONOUS","ratePerSecond":4}'
    $null = Wait-Until { (Invoke-RestMethod "$Ft/status").stream.acknowledged -ge 10 } 30 '10 updates acknowledged'
    $null = Post "$Ft/crash-primary" $null
    $null = Wait-Until { $null -ne (Invoke-RestMethod "$Ft/runs/latest").dataLoss } 60 'the failover run has its data loss'
    $null = Post "$Ft/recover" $null
    $null = Wait-Until { $null -ne (Invoke-RestMethod "$Ft/runs/latest").measurements.recoveryMillis } 60 'the recovery is measured'
    $null = Wait-Until { $s = Invoke-RestMethod "$Ft/status"; $s.currentPrimaryId -eq 5 -and $s.primaryEpoch -eq 4 } 60 'node 5 is primary at epoch 4'
    $code = & curl.exe -s -o NUL -w '%{http_code}' -X POST "$Ft/stream/stop"
    Write-Host "  POST $Ft/stream/stop -> $code"
    $null = Wait-Until { $null -eq (Invoke-RestMethod "$Ft/status").stream.inFlightSequence } 30 'no update in flight'
    Capture 'overview-after-runs.json'

    Write-Host '4. Clean slate again'
    $null = Reset-Cluster
    Capture 'overview-after-reset.json'

    Remove-Item $Body -ErrorAction SilentlyContinue
    $Rows | Format-Table File, HTTP, Bytes, Notes, OK -AutoSize
}
```

</details>

**Smoke check** (running app; Windows PowerShell 5.1, against `http://localhost:8080`; no git; the commands are in the final report of 9A.6):

| # | Action | Expected | Result |
|---|---|---|---|
| 1 | List the scenarios (`Invoke-RestMethod "$Api/api/scenarios"`) | 3 rows: `leader-dies-mid-write` (6 steps), `split-brain-never-happens` (7), `slow-node-under-load` (3) | |
| 2 | Start "The leader dies mid-write" (`POST .../leader-dies-mid-write/runs`) | `runId` and `scenarioId` (202) | |
| 3 | Poll `GET .../runs/latest` every 2 s until it is not RUNNING; print each step's state and message, and the verdict | 6 steps PASSED with observed messages (node 5 crashed, node 4 elected and primary at epoch 3, "0 of N confirmed updates were lost"); "Passed: all 6 steps met their expectations."; end state "Nodes 1, 2, 3 and 4 are up; node 5 is down. Node 4 is the leader and the replication primary. The update stream is stopped ..." | |
| 4 | Open `http://localhost:5173/timeline` | The Scenarios lane shows "Scenario started" ... "Scenario finished: passed", interleaved with the leader change, the promotion and the other modules' events | |
| 5 | Open `http://localhost:5173/experiments/8-faulttolerance` | "How it works" lists 7 notes; nothing on the page says "Until Phase 9A" (no note under the replica rail) | |

Smoke check run by Swanand on 2026-10-19: steps 1 to 3 passed (3 scenarios listed with 6, 7 and 3 steps; run 1 "The leader dies mid-write" PASSED, 6 of 6 steps: node 5 crashed at epoch 2, node 1 detected it after 2791 ms, node 4 elected (event 612) and primary at epoch 3 (event 629), outage 3566 ms, 0 of 5 confirmed updates lost); steps 4 and 5 (Timeline page, Experiment 8 page) not yet reported.

---

## 15.1 (fast mode)

**What changed** (new files only; no backend, frontend, `backend/Dockerfile`, `start-all` or `stop-all` change):
- `infra/docker-compose.yml` (project name `udcf`), four services with pinned images:
  - `backend`: built from `backend/Dockerfile` as on main after 16.1; the `local` profile (five nodes, exposes `/actuator/prometheus`); port 8080.
  - `frontend`: `infra/frontend.Dockerfile` (`node:22.11.0-alpine` build, as in CI, then `nginx:1.27.2-alpine`); port **5173**. `infra/nginx.conf` is mounted in. It serves `dist/` with an `index.html` fallback and proxies `/api/` and `/ws` (WebSocket upgrade headers, 1 h timeout) to `backend:8080`.
    - The image is built with `VITE_API_BASE_URL=http://localhost:5173`, `VITE_WS_URL=ws://localhost:5173/ws` and `VITE_GRAFANA_URL=http://localhost:3000/d/udcf-cluster/udcf-cluster`. The browser talks to one origin, and the Monitoring page links straight to the dashboard.
    - `infra/frontend.Dockerfile.dockerignore` (BuildKit reads it next to the Dockerfile) keeps the Windows `node_modules`, `dist` and any `.env` out of the build, so `frontend/` needs no new file.
  - `prometheus`: `prom/prometheus:v2.55.1`, `infra/prometheus/prometheus.yml`, scrapes `backend:8080/actuator/prometheus` every 5 s, keeps 1 day; port 9090.
  - `grafana`: `grafana/grafana:11.3.0`, anonymous Viewer on, the UDCF dashboard as home; port 3000. The admin login comes from `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` in the shell (defaults `admin` / `change_me_locally`, as in `.env.example`; compose reads no `.env` here, because its project folder is `infra/`).
- Grafana provisioning: `infra/grafana/provisioning/datasources/prometheus.yml` (uid `udcf-prometheus`) and `infra/grafana/provisioning/dashboards/udcf.yml`. The dashboard is `infra/grafana/dashboards/udcf.json` (uid `udcf-cluster`, title "UDCF cluster", refresh 5 s, last 15 min, read-only), laid out as in HANDOFF 10.1.
- `scripts/compose-up.ps1`:
  1. Checks the Docker engine and that ports 8080, 5173, 9090 and 3000 are free (skipped when the `udcf` project already runs).
  2. Runs `docker compose -f infra/docker-compose.yml up -d --build`.
  3. Waits, up to 180 s each, for the backend health, the same health through the nginx proxy, Prometheus reporting the target `up`, and Grafana `/api/health`.
  4. Opens the UI and the dashboard (`-NoBrowser` to skip) and prints the URLs.
- `scripts/compose-down.ps1`: runs `down -v --remove-orphans`, then fails if any `udcf` container is left. Built images are kept.

**Interfaces for other tracks**
- URLs: UI http://localhost:5173, API http://localhost:8080, Prometheus http://localhost:9090, Grafana http://localhost:3000/d/udcf-cluster/udcf-cluster.
- A new meter shows up in Prometheus with no change here. To chart it, add a panel to `infra/grafana/dashboards/udcf.json`. Timers are scraped as `<name>_seconds_count|_sum|_max`, counters keep `_total`, and gauges keep their name.
- Rohan (16.1 and later): compose builds `backend/Dockerfile` with no build args, and its `JAVA_TOOL_OPTIONS` is not set here.

**Known issues**
- There is no leader gauge in code. "Leader" is shown as `distributed_leader_elections_total` by winning node (`node_id` is the winner): after a leader crash, the new leader's bar and a `LEADER_FAILURE` step appear.
- Bar panels ("since start") show raw counter totals rather than `increase()`. A counter created by the event itself (for example the new leader's first win) would show as 0 under `increase()`.
- "Recent max" panels use Micrometer's `_max`, which decays after about 2 minutes. The value is measured, but it is not a mean.
- Experiment 6 dispatch counters are recorded when a run ends, so "Requests served per worker" moves after the run, not during it. "In flight per worker" is the live panel.
- The JVM heap panel is process-wide: all nodes share one JVM (R1), so it has no `node_id`.
- Panels show "No data" until their experiment has run once since the backend started. Executor and replication gauges report NaN, shown as no value, until the node's service has started. That is real, not a fault.
- Ports 8080 and 5173 are shared with the dev-mode demo: run `scripts\stop-all.ps1` before `compose-up` (the script tells you).
- Not run here (fast mode): no `docker compose up`, no image build, no Grafana render. Checked here: `docker compose config` (exit 0, 4 services), the PowerShell parser on both scripts (0 errors), and every metric name in the dashboard against `MetricNames` plus `jvm_*` (28 references, 0 unknown).

**Gaps in other tracks' packages:** none found.

**Smoke check** (Swanand, Docker Desktop running, dev-mode demo stopped):

| # | Action | Expected | Result |
|---|---|---|---|
| 1 | `powershell -ExecutionPolicy Bypass -File scripts\compose-up.ps1` | Ends with "UDCF is running in Docker." and the four URLs (the first build takes several minutes) | |
| 2 | Open http://localhost:5173, then the Cluster page | Five nodes up; the top bar shows the live connection; event stream updates arrive | |
| 3 | Open http://localhost:3000 (no login) | "UDCF cluster" opens; Node status shows nodes 1-5 Up; Events per second, JVM heap and Lamport clock have data | |
| 4 | On the Cluster page, crash the leader node | Within about 10 s: Node status shows that node Down; "Elections won per node" gains a bar or count for the new leader; "Elections won by trigger" steps up with `LEADER_FAILURE`; Election duration shows a value | |
| 5 | `powershell -ExecutionPolicy Bypass -File scripts\compose-down.ps1`, then `docker ps` | "Done: no UDCF containers are left."; `docker ps` lists no `udcf-` containers | |

Smoke check run by Swanand on 2026-10-20: 5/5 passed.

---

## 15.1b (fast mode)

**What changed** (test files only, no production code):
- `ElectionNodeServiceTest`: `liveElectionThreads` now counts only threads created during the test. `setUp` snapshots every live `Thread` object, and threads in that snapshot are ignored (compared by identity, not name). Every lifecycle assertion is unchanged: start, stop, join, port freed, rebind on recover, idempotent crash/recover, reset reopens.
- `ScenarioEngineTest.deadlineFailsTheStep`: the duration must be at least 295 ms instead of 300 ms. `StepContext.remainingMillis()` and `elapsedMillis()` truncate nanoseconds to whole milliseconds, so a 300 ms deadline can read as 299 ms. The step message must still match "Waited … but it did not happen", which proves the deadline ended the step.

**Finding (item 2):** the leftover `udcf-election-n1-*` threads come from `backend/src/test/java/com/udcf/modules/replication/ReplicationControllerTest.java:56`. That class has its own context (distinct properties) and starts election services on all five nodes through `ensureLeader` (line 115). It has no `@DirtiesContext`, so Spring caches the context until the JVM exits. This is expected test-context caching, not a production leak. On context close, `Cluster.close` (`ClusterConfig`, `destroyMethod = "close"`) → `ClusterNode.stop` → `ElectionNodeService.stop` joins both threads.

**Interfaces for other tracks:** none.

**Known issues:** none.

**Gaps in other tracks' packages:** `ReplicationControllerTest` (Jai, Track C) crashes nodes and runs elections in a context it calls its own, but without `@DirtiesContext(classMode = AFTER_CLASS)` that context, its sockets and its threads live until the JVM ends. Optional cleanup, not a fault.

**Smoke check:** none. No app behaviour changed; the Linux gate is the GitHub CI run of the PR.

---

## Monitoring

**How to run** (Windows PowerShell 5.1, repository root, Docker Desktop running):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\compose-up.ps1     # build, start, wait, open the UI and Grafana
powershell -ExecutionPolicy Bypass -File scripts\reset-cluster.ps1  # clean slate during the demo (port 8080)
powershell -ExecutionPolicy Bypass -File scripts\compose-down.ps1   # stop and remove the containers
```

Grafana: http://localhost:3000/d/udcf-cluster/udcf-cluster (anonymous viewer). Every query reads a meter registered in the backend, and every `distributed_*` series carries `node_id` (R5).

**What each panel shows** (dashboard "UDCF cluster", rows as in HANDOFF 9):

| Row | Panel | Query reads | Shows |
|---|---|---|---|
| Cluster overview | Node status | `distributed_node_status` | Up / Down per node (text and colour) |
| | Events per second | `distributed_events_published_total`, `distributed_event_notifications_dropped_total` | Bus events per second, dropped WebSocket notifications |
| | JVM heap | `jvm_memory_used_bytes`, `jvm_memory_committed_bytes` (heap) | Heap of the one JVM (process-wide) |
| Multithreading (2) | Completed requests per second | `distributed_request_throughput` | Per node, 30 s sliding window |
| | Active worker threads | `distributed_active_threads` | Per node |
| | Queued requests | `distributed_queued_requests` | Per node |
| | Rejected requests per second | `distributed_requests_total{outcome="rejected"}` | Per node |
| Clock sync (3) | Lamport clock per node | `distributed_clock_value` | Each node's Lamport clock |
| Election (4) | Elections won per node | `distributed_leader_elections_total` | Wins since start, by winner |
| | Elections won by trigger | same | Steps per finished round, by winner and trigger |
| | Election duration (recent max) | `distributed_election_duration_seconds_max` | By algorithm and trigger |
| Replication and fault tolerance (5, 8) | Replicated writes per second | `distributed_replication_writes_total` | Per primary and model |
| | Store items per node | `distributed_replication_store_items` | A lagging backup has fewer items |
| | Replication round trip (recent max) | `distributed_replication_latency_seconds_max` | Measured TCP round trip per backup |
| | Experiment 8 updates per second | `distributed_failover_updates_total` | By accepting primary and status |
| | Primary failures detected | `distributed_failures_total` | By failed primary |
| | Failover intervals (recent max) | `distributed_recovery_duration_seconds_max` | Detection, failover, service restored, outage, recovery |
| Load balancing (6) | In flight per worker | `distributed_balancer_in_flight` | Live during a run |
| | Requests served per worker | `distributed_balancer_dispatches_total{outcome="served"}` | Since start, recorded at run end |
| | Makespan by strategy (recent max) | `distributed_balancer_makespan_seconds_max` | Cluster level, by strategy |
| MapReduce (7) | Map tasks per node | `distributed_map_tasks_total` | Since start |
| | Reduce tasks per node | `distributed_reduce_tasks_total` | Since start |
| | Jobs per coordinator | `distributed_mapreduce_jobs_total` | Coordinator follows the leader |
| RMI, MPI and matrix (1, 9, 10) | RMI calls per second | `distributed_rmi_calls_total` | Per calling node |
| | MPI messages per root | `distributed_mpi_messages_total` | Since start; root follows the leader |
| | Matrix compute time per node (recent max) | `distributed_matrix_compute_duration_seconds_max` | Each rank's measured multiply |

**Two-minute demo script**

1. (0:00) Grafana, "UDCF cluster": "Prometheus scrapes the one backend every 5 seconds; every series is tagged with the node it belongs to." Point at Node status (five nodes Up) and Events per second.
2. (0:20) In the UI (http://localhost:5173), start a run on the Experiment 6 (or Experiment 2) page. Back in Grafana, point at Completed requests per second, Active worker threads and Queued requests, per node: thread caps follow capacity (FAST 4, MEDIUM 2, SLOW 1).
3. (0:50) In the UI, open the Cluster page and crash the node marked Leader.
4. (1:00) In Grafana, within about 10 s: Node status turns that node Down, "Elections won by trigger" steps up with `LEADER_FAILURE` for the new leader, and Election duration shows the measured round. The Lamport clocks of the live nodes jump with the election messages.
5. (1:30) Optional: run the Experiment 8 update stream before the crash. Primary failures detected and Failover intervals then fill, and Store items per node shows whether a backup lagged.
6. (1:50) Recover the node on the Cluster page; Node status returns to Up. "The UI never depends on Grafana: its charts read the REST API, so they also work in the public deployment."

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

## Requirements Coverage (E8c)

| Requirement | Implementation | Test |
| --- | --- | --- |
| Module lab 8, registered; IDLE/RUNNING/BUSY (BUSY only under the guard) | `FaultToleranceModule` | `FaultToleranceModuleTest.statusAndIdempotentStart`, `FaultToleranceControllerTest.listedInModules`, `busyIs409` |
| Start (idempotent), stream start/stop, mode, crash, recover, runs | `FaultToleranceController` | `FaultToleranceControllerTest` (10), `FaultToleranceModuleTest` |
| Stream one update at a time, budget per session, sequences go on after a reset | `UpdateStream` | `UpdateStreamTest` (10, scripted submitter) |
| C4: stream start on a never-started module starts it inside one guard | `startStream` + `startServices` (takes no guard) | `FaultToleranceModuleTest.streamStartStartsTheModule`, `FaultToleranceControllerTest.streamLifecycle` |
| Synchronous: zero loss (a measured 0); recovered old primary demotes | `captureRestore`, E8b rejoin | `FaultToleranceModuleTest.synchronousFailoverLosesNothingAndOldPrimaryDemotes`, `FaultToleranceEndToEndTest.synchronousCrashFailoverRecover` |
| Asynchronous: in-flight loss, labelled simulated, deterministic (C2) | `DataLoss` against the ledger; delay 5000 ms in the test | `FaultToleranceModuleTest.asynchronousFailoverShowsInFlightLoss`, `FaultToleranceEndToEndTest.asynchronousCrashShowsInFlightLoss` |
| No two primaries at any observation | `FailoverCluster.splitBrain` on every status read | `FaultToleranceEndToEndTest` (every status read asserts it) |
| C1: reads publish nothing and change no counter | GETs record nothing | `FaultToleranceControllerTest.readsPublishNothing` (100 GET /status), `FaultToleranceModuleTest.readsPublishNothing` |
| C5: null rules (dataLossNow, role, epoch, rejoin) | `currentState`, `FaultToleranceDtoMapper.node` | `FaultToleranceDtoMapperTest.nodeNullRules`, `dataLossNullRules`, `FaultToleranceModuleTest.synchronousFailover...`, `FaultToleranceControllerTest.overviewBeforeStart` |
| C8: old primary back before anyone is chosen | E8a `PRIMARY_RETURNED` | `FaultToleranceModuleTest.oldPrimaryReturnsBeforePromotion` |
| No number above 2^53 - 1; nulls kept | offsets instead of nanos | `FaultToleranceDtoMapperTest.fullRunAsOffsets`, `crashedOnlyKeepsNulls` |
| History bounded by history-limit; evicted run 404 | E8a state machine, `capturedLoss` pruned | `FaultToleranceModuleTest.historyIsBounded` |
| Budget 409 names the cluster reset; reset gives a fresh budget | `limitReached` | `FaultToleranceModuleTest.budgetAndReset`, `FaultToleranceControllerTest.budgetIs409UntilReset` |
| Exp 5 events per update fit the buffer in both profiles | `max-updates` 400 / 200 | `FaultToleranceModuleTest.eventVolumePerUpdateFitsTheBuffer` (10.15 and 6.15 per update) |
| Metrics with node_id; measured 0 recorded; unassessed not | `FaultToleranceMetrics.updateFinished`, `dataLossMeasured` | `FaultToleranceUpdateMetricsTest` (3) |
| Config from both YAML files | `FaultToleranceModuleProperties` | `FaultToleranceModulePropertiesTest` (4) |
| Contract fixtures | captured live with curl.exe | `frontend/src/test/fixtures/faulttolerance/README.md` (39 files) |

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
- E8c (Q1): the new primary still comes from the module-local `FailoverRoleSelector` feeding `EpochAuthority.onLeaderElected` (`// TODO(L1)`). The E8c brief asked for "the election module's role provider", which does not exist; docs/tracks/README.md line 59 rules until Phase 9A (approved).
- E8c (Q2): the simulated asynchronous delay shown is `udcf.replication.async-delay-millis` (450 ms locally) as configured and as the replication service reports it, not HANDOFF 6.11's 220 ms; no number is typed into code or texts (approved, as E8a).
- E8c (Q3): the measurements keep the E8a names: failover = detection to promoted; outage = crash to first accepted update (the brief's "crash to new primary serving"). The glossary carries both (approved).
- E8c (Q4): the module status is BUSY only while the guard is held. This replaces E8b's "Notes for E8c" item 1 sentence "Status: BUSY while an action holds the guard or a run is SUSPECTED/PROMOTING; RUNNING while the stream runs; else IDLE." Reason: PROMOTING lasts until the first write and SUSPECTED with no candidate never ends, so either would block the cluster reset indefinitely (approved).
- E8c (Q5): no timeline endpoint and no alias; the timeline is a run's `milestones` (approved).
- E8c (Q6): no new history property; `udcf.faulttolerance.history-limit` (10) bounds the kept runs (approved).
- E8c (Q7): no event on a busy rejection; the 409 is enough (approved).
- E8c (Q8): the update budget is per reset session (400 local, 200 public), not per stream (approved with conditions: the 409 text names the cluster reset; status and overview serve `maxUpdates` and `remainingUpdates`).
- E8c (Q9): the module is tested on real sockets through `FaultToleranceModuleHarness`; only `UpdateStream` has a scripted submitter; `FailoverCluster` unchanged (approved).
- E8c (C1): `SplitBrainDto` has no `checks` counter (the plan had one): reads must not change counters. "SPLIT_BRAIN_CHECKED at a stream outcome" is implemented as: the check runs (unpublished) at every update outcome and is published when a new violation is first seen; it is published when the stream stops, at restore and at recovery. Publishing it after every update would add one event per update to the ring buffer.
- E8c (C5): `dataLossNow` is null whenever no primary serves, also when nothing was acknowledged yet (E8a's `DataLoss.measure` reports NOTHING_ACKNOWLEDGED before NO_PRIMARY_STORE). `rejoin` is also null for a node that is down (E8b reports REJOINING for a crashed node; found in the first capture).
- E8c: no recommended client timeout in the overview. No call waits on the network or a failover; the only blocking is `Cluster.crash`'s thread joins, bounded by the 5 s code constant of each service, not by a property (see "For E8d" item 6).
- E8c: the derived "expected detection window" text was corrected during Stage C from "between the heartbeat timeout and the timeout plus one interval" to "between the timeout minus one interval and the timeout plus one interval" (1.8 to 3.2 s with the shared timings, as E8a recorded): the first capture measured a detection of 2204.75 ms, below the 2500 ms timeout. The fixtures were recaptured after the fix.
- E8d (Q1): at a used budget "Start stream" is disabled with a hint built from the served numbers ("Budget used: N of N updates this session. A cluster reset gives a fresh budget.") and a link to the Cluster page, instead of showing the backend's 409 text; the 409 `UPDATE_LIMIT_REACHED` path is kept for a race and tested (approved).
- E8d (Q2): crash-primary and recover keep the shared 10000 ms timeout; a timeout is followed by a status read, never reported as a failure (approved).
- E8d (Q3): "Crash primary" is disabled while a failover is in progress, stricter than the conflict matrix (which accepts a crash once the new primary serves and ends the run INTERRUPTED), so every run started from the page can be measured; a crash from the Cluster page can still interrupt a run (synthetic `run-interrupted.json`) (approved).
- E8d (Q4): a module-specific `ProblemAlert` in `FaultToleranceControls.jsx`; Track C's files are not edited (approved).
- E8d (C2): after a timeout of crash-primary or recover, every action stays disabled until a status read started after the timeout shows `actionInProgress` null; then status and run are read again.
- E8d (C3): a new UI state "failover-waiting" (a failover in progress with the stream stopped) with its own message; "Start stream" stays enabled unless the budget is 0, "Crash primary" stays disabled.
- E8d: the initial load is the overview alone (it already carries status and latest run), not three separate reads; status and runs/latest are read on refresh.
- E8d: runs/latest is also read with each poll while no primary serves, during an open recovery and after a timeout (beyond "status only"), so the timeline grows live and `recoveryMillis` comes from the run.
- E8d: while the WebSocket is not connected the page polls status and latest run every 2000 ms (`FALLBACK_POLL_INTERVAL_MS`); no earlier page does this.
- E8d: timeline rows are linked to the event log by naming their event kind ("In the event log: Failure detected"); no Lamport value is shown per milestone (that would need a second event subscription); the log below shows them.
- E8d: five synthetic fixtures beyond the README's list (`run-in-progress`, `run-recovering`, `status-restored-old-primary-down`, `status-failover-stream-stopped`, `status-rejoin-stalled`), because those states were not captured.
- E8d: `GET /runs` and `GET /runs/{id}` are not used (no run history on the page).

- 9A.1: `ElectionModule`'s `NODE_RECOVERED` handler also wires a recovered node whose election service another module (Experiment 8) started first; before, it returned as soon as a service was registered, so such a node never ran Bully and a recovered highest node could not reclaim leadership (`ElectionModuleTest.recoveredNodeStartedByAnotherModuleIsWired`).
- 9A.1: `RoleProvider.afterPendingChanges(Runnable)` was added (not in the Stage A API) so that every promotion starts on the dispatcher (R4): the suspect callback and `nodeReady` only queue a follow-up.
- 9A.1: a planned handover waits while a failover run whose new primary is already chosen is still open (it ends at the first accepted update, which looks at the leader again), so the run is measured and the epoch authority never issues an epoch the state machine ignores.
- 9A.1: the old-primary-crash double failure (the old primary crashes during a planned handover and opens a run) is handled, not left as a known issue: the leader's promotion in force is chosen again at the same epoch (`FailoverCluster.followLeader`, "renewed choice"). No test drives it (it needs a crash inside the handover window).
- 9A.1: `Cluster.assignReplicationRoles` revokes in a first pass and grants in a second, so a direct switch never holds two PRIMARY roles.
- 9A.1: `NO_CANDIDATE` is no longer published: the elected leader is the only candidate; Experiment 8 waits for the next leader change or for the leader to become ready.
- 9A.1: the client window in `FaultToleranceControllerTest` and `FaultToleranceEndToEndTest` properties is 50 x 100 ms (was 40 x 100), as in `FailoverFixture` (approved): the worst case with their timings is 3450 ms.
- 9A.1: `LeaderFailoverIntegrationTest`'s update stream pauses 50 ms between updates: unpaced, Experiment 5's about 10 events per update overflowed the fixture's 5000-event buffer and evicted the events the test checks.
- 9A.1: `RoleProviderTest` has 12 tests, not the planned 13: the concurrent "never two primaries" sampler was dropped, because a reader that reads node roles one node after another cannot see an atomic snapshot (Known issues (c)); the revoke-before-grant rule and a direct switch are checked instead.
- 9A.1: the worst-case failover is detection + election + one promotion = 10300 ms, locally and in the public profile (approved, R7); before, detection + 2 x promotion = 11200 ms.
- 9A.6: step indexes are 1-based (`index` in the API, `stepIndex` in events), so a verdict reads "Failed at step 4" as the audience counts.
- 9A.6: the clean-slate reset is not a numbered step. It runs before `SCENARIO_STARTED`; when it fails, every step is SKIPPED and only `SCENARIO_FINISHED` is published.
- 9A.6: `SCENARIO_FINISHED` is published before the run reads as finished, so a client that sees the end also finds the event on the bus.
- 9A.6: `endState` is an object `{description, leaderId, primaryId, nodes[{nodeId, status, roles}]}`, not only a sentence. The addendum asked only that it "states" the cluster state; the fields make it usable by the 9A.7 page.
- 9A.6: `FailoverSteps` is a public Spring `@Component` (not package-private), so Jai's 9A.7 scenarios can reuse the Experiment 8 steps add-only.
- 9A.6: "never two primaries" is sampled through Experiment 8's own check (`currentState().splitBrain()`, by each node's acting replication role and epoch) every 100 ms, not through the cluster roles. Those are read node by node and can show a revoke-before-grant switch non-atomically (9A.1 Known issues (c)).
- 9A.6: the public profile runs "A slow node under load" with 400 work units, not 900, because of its load balancing limits (`max-work-units` 500, `max-total-work` 120000).
- 9A.1: in the frontend tests, the "demoted" state is now taken from the E8c captures `status-after-demotion.json` and `run-sync-recovered.json` (with the stream stopped, built inline and labelled synthetic), because the 9A.1 capture `overview-after-runs.json` shows the state after the handover (node 5 primary again at epoch 4).

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
- E8c, **recovery event race:** E8b publishes `RESYNCHRONISED` just before it records the instant in the state machine (`FaultToleranceNodeService.java:461-463`). The module publishes `RECOVERY_MEASURED` from that event and again at every stream outcome and module action, whichever first sees the recovery interval recorded. With no stream running and no further action, the event can therefore be missing; `GET /runs` always has `recoveryMillis`. E8b unchanged.
- E8c, **update in flight at a reset:** the one update the stream may have in flight keeps retrying for up to the retry window (12.5 s locally) after a reset; if a primary is appointed meanwhile it may be accepted and is then genuinely acknowledged in the new session (it is not counted by the new stream). Its key never collides: sequence numbers are not reset.
- E8c: `POST /crash-primary` can exceed the page's 10000 ms HTTP timeout in the worst case (55 s for a node running only Experiment 8's services, 75 s with every service); see "For E8d" item 6.
- E8c: the live checks on a status read copy the acknowledged ledger and the primary's store (each at most `max-updates` entries per session) on the HTTP thread; cheap, but not free.
- E8c: verified on Windows and on Linux (Docker, run by Swanand); see the 2026-10-16 progress-log line.
- E8d: `ProblemAlert` now exists in three private copies (Exp 1 `RmiPage.jsx`, Exp 9 `MpiPage.jsx`, Exp 8 `FaultToleranceControls.jsx`); a candidate for one shared component in Phase 14.
- E8d: the eager main chunk (warning above 500 kB, carried over) grew from 738.64 kB to 783.50 kB (gzip 220.77 to 233.74 kB); code splitting is out of scope.
- E8d: at 375 px the timeline's SVG keeps a minimum width of 36rem and scrolls inside its own box (never the page); the milestone list under it is the readable version on a phone.
- E8d: if a rejoin stalls (ROLE_QUERY_FAILED after the last attempt, the node stays REJOINING), the run stays "recovering" and the page keeps reading status and latest run every 500 ms until the node crashes and recovers again or the cluster is reset. Both reads are cheap and publish nothing.
- E8d: a 409 busy is hard to provoke from one tab, because the page sends one action at a time; it is covered by the tests and checklist step 13 uses two tabs.
- E8d: the manual checklist was run by Swanand on 2026-10-16 against the real backend; see the Result column of the checklist.
- 9A.1 (a), **without an update stream:** a new leader can lead while the old primary stays primary until the next accepted update. The failover run, and any planned handover waiting behind it, only complete on an accepted update; until then the Cluster page shows LEADER and PRIMARY on different nodes, honestly.
- 9A.1 (b), **double failure during a handover:** if the handover target crashes mid-handover and the old primary is elected again, Experiment 8 has no serving primary until the next leader change or a cluster reset. It is shown honestly: `currentPrimaryId` and `primaryEpoch` are null (`FailoverCluster.java:267-270`, `FaultToleranceModule.java:215-218`); the fenced old primary acts as a backup (`FaultToleranceNodeService.java:310-312`; node roles come from the acting replication role, `FailoverCluster.java:279-281`); no PRIMARY role is published (`FailoverCluster.java:622-623`); the page says "No primary serves right now." (`ReplicaRail.jsx:118`) and "No primary serves right now: Experiment 8 waits for the elected leader to take over." (`faultToleranceModel.js:118`). No test drives it.
- 9A.1 (c), **non-atomic role reads:** `GET /api/cluster` reads each node's roles one node after another, so a reader can see a role switch non-atomically. Experiment 8 never switches the primary directly (a crash or the handover fence clears PRIMARY first, a separate publish), and `assignReplicationRoles` revokes before it grants; `Cluster.assignLeader` (E4c, unchanged) grants and revokes LEADER in one pass.
- 9A.1 (d), **Experiment 5 regression until 9A.3 (for Jai):** Experiment 8's primary is now the elected leader, the highest live node (node 5), while every Experiment 5 action still runs `ReplicaSet.primary()`, which steps down every other live acting primary (`ReplicaSet.java:140-141`) and makes the lowest live node (node 1) primary at its current epoch. So the first Experiment 5 write while Experiment 8 runs moves the replication primary from node 5 to node 1 at the same epoch: Experiment 8's updates get "not primary" and retry until they give up, and `/api/cluster` names node 5 PRIMARY while node 1 acts. Before 9A.1 both picked the lowest node, so this only happened after a failover. Fix (9A.3, Jai's package): `ReplicaSet.selectedPrimaryId()` returns `RoleProvider.ensureLeader()` (empty: 409, no fallback); `primary()` changes nothing when the leader already acts as primary; after appointing, Experiment 5 calls `RoleProvider.publishReplicationRoles(leader, backups)` (see "9A.1 APIs" under Interfaces for other tracks). Note 8 on the Experiment 8 page still warns about it.
- 9A.1: the manual checklist was run by Swanand on 2026-10-17 against the real backend (all 13 steps passed; see the Result column) and the clean-clone Linux run passed the same day (see the progress log). GitHub CI is still the Linux gate.

---

## Progress log

- 2026-10-07 track file created.
- 2026-10-08 E4a completed (baseline 525, total 579; 54 election tests covering all scenarios including stale timers, crash safety, 1024-char limit, probe event absence, and action loop crash guards).
- 2026-10-09 E4b done: election UDP transport (`ElectionNodeService`, worker-only algorithm work, joined lifecycle) and the shared `core/failure/FailureDetector` (700/2500 ms heartbeats, PEER_SUSPECTED/PEER_ALIVE); backend 852 tests (baseline 817 + 35), frontend not run (no frontend change); deviations: heartbeats exempt from L4 (awaiting Rohan's sign-off), `HEARTBEAT` added to E4a's `ElectionMessageType` (approved).
- 2026-10-09 E4c done: `ElectionModule` (lab 4: Bully/Ring from any node, sticky LEADER role, consensus check, automatic Bully re-election on leader failure, rounds with timeout, clean-slate reset), cluster roles API (`NodeRole`, `ClusterNode.roles`, `Cluster.assignLeader`, `LEADER_CHANGED`, `NodeDto.roles`), REST, metrics, 22 live contract fixtures, `isLeader` in top bar and topology; backend 893 tests (baseline 852 + 41), frontend 391 tests (baseline 388 + 3); deviations: none beyond the approved plan (added `ElectionRound` record; `ElectionNodeService.halt/resetState` and `FailureDetector.reset` as approved in Q7).
- 2026-10-09 E4d done: Election page (node ring with live message arrows, result panel with honest states, leader changes from the cluster log, per-node figures, crash and recover without confirmation), sentence-case election event messages, per-node `electionsWon`/`roundsTimed`/`meanDurationMillis` in the overview, fixtures recaptured plus `overview-ring-timed-out.json`; backend 897 tests (baseline 893 + 4), frontend 537 tests (baseline 464 + 73); deviations: extra fixture `overview-ring-timed-out.json`; shared edits `registry.test.js` and `ExperimentPage.test.jsx` (election to matrix, both approved), `experiments.js` concept and `routes.test.jsx` spies (add-only).
- 2026-10-09 E8a done: Experiment 8 pure classes in `com.udcf.modules.faulttolerance` (epoch rules and `EpochAuthority` with the `onLeaderElected` TODO(L1) seam, `FailoverStateMachine` with rejected-event counter and `InstantSource`, `FailoverMeasurements` incl. outage, `AcknowledgedLedger` and `DataLoss` with the simulated marker, bounded `UpdateRetryPolicy`, strict `SplitBrainChecker`), reusing Track C's `DataStore`, `DataItem`, `ConsistencyModel` and `WriteResult`; backend 1040 tests (baseline 960 + 80), frontend not run (no frontend change); deviations: step class names changed (`UpdateStore` not ported, `FailoverMetrics` renamed `FailoverMeasurements`), async delay as reported (450 ms, not 220), shared detector timings, redefined intervals, first promotion epoch 2.
- 2026-10-09 E8b done: failover on the shared cluster (`FailoverCluster`, per-node `FaultToleranceNodeService`, role query over the replication READ channel with the `failover.term` term record, lowest-id `FailoverRoleSelector` behind TODO(L1), catch-up before promotion, rejoin with demote/adopt and resync, client updates with the E8a retry policy feeding the ledger, metrics, `udcf.faulttolerance` with a shared worst-case formula); backend 1084 tests (baseline 1040 + 44; new classes 5 sequential runs, full suite 3 sequential runs, all green; 1083 before the review test `promotionWithSeveralSilentAndDeadPeersIsBounded` was added), frontend not run (no frontend change); deviations: lowest-id selector (README), step-down at crash and OBSERVED stamped in `crash()` (Q4), catch-up before promotion and resync on STAY-as-backup (Q6), window formula 11200 ms (default 12500 ms), event names per the addendum.
- 2026-10-15 E8c done: `FaultToleranceModule` (lab 8) and REST API under `/api/modules/faulttolerance` (start, stream start/stop with a per-session budget, mode, crash primary, recover old primary, runs with milestones, measurements and data loss), DTOs, 8 events, 2 metrics, `udcf.faulttolerance.stream`, 39 live contract fixtures; backend 1826 tests (46 new; new tests 5 runs plus reverse order, replication + election + faulttolerance 492, full suite 3 runs, all 0 failures, 1 skipped), frontend not run (no frontend code change); deviations: Q1 to Q9 as approved, C1 (no `checks` counter; SPLIT_BRAIN_CHECKED at stream stop, restore, recovery and a new violation), C5 (`dataLossNow` and a down node's `rejoin` null), no recommended client timeout, expected-detection text corrected and fixtures recaptured.
- 2026-10-16 E8c Linux (Docker), run by Swanand: replication, election and faulttolerance packages 492 tests x 5 runs plus 1 reverse-order run at 2 CPUs, 0 failures; the 7 new E8c test classes 46 tests x 10 runs at 1 CPU, 0 failures; full suite 1826 tests, 0 failures, 1 skipped; clean-clone full suite 1826 tests, 0 failures, 1 skipped.
- 2026-10-16 E8d done: Experiment 8 page (replica rail with the epoch on every node, live SVG failover timeline with the derived detection band, the four measurements plus outage and time to first update, data loss with the Simulated badge, no-two-primaries check, stream, mode, crash and recover controls from the conflict matrix, polling only while something moves, 10 synthetic fixtures); backend not run (no backend change), frontend 1108 tests (baseline 916 + 192; new tests 3 runs and 1 shuffled run, full suite 2 runs, all green); deviations: Q1 to Q4 and C2, C3 as approved, overview-only initial load, runs/latest polled during open runs, 2000 ms fallback poll, timeline linked to events by kind, five extra synthetic fixtures.
- 2026-10-16 E8d Linux check: clean clone of 8b67a21 on Node 22 in Docker, npm ci, vitest (118 files, 1108 tests), lint and build all exit 0; main chunk 782.67 kB. GitHub CI is still the Linux gate.
- 2026-10-17 9A.1 done: shared `RoleProvider` in `core/cluster` (LEADER from Experiment 4 only; PRIMARY and BACKUP; ordered leader listeners on `udcf-roles-dispatcher`; `ensureLeader` with a REQUESTED Bully round, 409 `NO_LEADER` when none; `REPLICATION_ROLES_CHANGED`), Experiment 8 promotes the elected leader (selector removed) and hands over to a recovered leader after its resync, L2 verified on real sockets, worst case 10300 ms, four overview fixtures recaptured by Swanand; backend 1912 tests (baseline 1893 - 3 + 22; 0 failures, 1 skipped; `RoleProviderTest`, `ElectionModuleTest`, `FailoverClusterTest`, `LeaderFailoverIntegrationTest`, `FaultToleranceEndToEndTest` 3 runs, 54 tests each, all green), frontend 1244 tests (lint and build exit 0); Windows only, Linux and the manual checklist not yet run; deviations: see the 9A.1 entries under Deviations.
- 2026-10-17 9A.1 Linux (Docker), run by Swanand on a clean clone of 9fe7a82: backend with Maven 3.9.9 / Java 21.0.7 (maven:3.9.9-eclipse-temurin-21, --cpus=2) full suite 1912 tests, 0 failures, 1 skipped, BUILD SUCCESS, and the five 9A.1 classes x3 (54 tests each, 0 failures); ./mvnw not used because its SHA-256 check failed inside eclipse-temurin:21-jdk although the downloaded distribution matched the pinned hash (wrapper quirk, no repository change); frontend on node:22: npm ci, vitest (128 files, 1244 tests), lint and build exit 0, main chunk 833.08 kB.
- 2026-10-18 9A.6 done: scenario engine `com.udcf.scenario` (one run at a time on `udcf-scenario-runner`, clean reset with BUSY retry, steps with deadlines observed by bus subscription plus 250 ms polling from action anchors, FAILED then SKIPPED, cleanup in a finally, end state from the cluster, last 10 runs), `/api/scenarios` API and six `scenario` events, scenarios "The leader dies mid-write", "Split-brain that never happens" and "A slow node under load" (all PASSED on real sockets here), the leader-crash end-to-end test (time daemon, coordinator, MPI root, replication primary all follow LEADER_CHANGED by bus sequence), Experiment 8 note 8 removed; backend 1971 tests (baseline 1939 + 32; 0 failures, 1 skipped), frontend 1294 tests passed after the fixture recapture, which came after the first commit (reported by Swanand; before it 1290 of 1294, the 4 failures being the stale overview fixtures' note count), lint 0 problems, build exit 0 (main chunk 859.48 kB); fast mode: no Docker run; CI is the Linux gate.
- 2026-10-20 15.1 done: local Docker Compose in `infra/` (backend from `backend/Dockerfile`, frontend on `node:22.11.0-alpine` then `nginx:1.27.2-alpine` on port 5173 proxying `/api` and `/ws`, `prom/prometheus:v2.55.1` scraping every 5 s, `grafana/grafana:11.3.0` with anonymous Viewer), the provisioned "UDCF cluster" dashboard (26 panels in 8 rows, every query on a meter registered in code), `scripts/compose-up.ps1` and `scripts/compose-down.ps1`; backend and frontend unchanged (suites not run); `docker compose config` exit 0, both scripts 0 parse errors, 28 metric references 0 unknown; smoke check not yet reported; fast mode: no Docker run; CI is the Linux gate.
- 2026-10-20 15.1b done: `ElectionNodeServiceTest` ignores node 1 election threads left by `ReplicationControllerTest`'s cached context (identity snapshot); `ScenarioEngineTest.deadlineFailsTheStep` allows 5 ms of millisecond truncation; the two classes 15 + 17 tests, 0 failures; backend 1976 tests, 0 failures, 1 skipped, BUILD SUCCESS; fast mode: no Docker run; CI is the Linux gate.
