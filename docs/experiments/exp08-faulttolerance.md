# Experiment 8: Fault Tolerance with Primary-Backup Replication

Module id `faulttolerance` · page `/experiments/8-faulttolerance` · package `com.udcf.modules.faulttolerance`

## Aim

Simulate simple fault tolerance with primary-backup replication.

## Theory

In primary-backup replication, clients write to the primary, which copies each update to its backups. If
the primary crashes, the system must *fail over*:

1. **Detect** the failure (missed heartbeats, a timeout).
2. **Choose** a new primary, usually by an election.
3. **Promote** it: it first catches up on anything it missed, then starts serving.
4. **Restore service:** clients redirect and retry their updates.

The measurements are detection time, failover time, recovery time and data loss. Synchronous
replication loses no confirmed update; asynchronous replication can lose the updates that were confirmed
but not yet pushed when the primary died.

The dangerous case is *split-brain*: the old primary comes back and still believes it is the primary,
so two nodes accept writes. *Epochs* (also called terms or generations) prevent it. Every promotion
raises the epoch, and a backup refuses any update carrying an epoch lower than its own. The returning
node learns of the higher epoch, steps down and resynchronises. Safety does not rely on the failed node
behaving well.

## How UDCF implements it

- **Rules (pure).** `EpochRules` (refuse a stale epoch, accept, or adopt a higher one; how a rejoining
  node resolves its role), `EpochAuthority` (a promotion's epoch = max(last issued, highest seen) + 1;
  the store starts at epoch 1, so the first primary gets epoch 2), `FailoverStateMachine` (STEADY →
  SUSPECTED → PROMOTING → RESTORED), `SplitBrainChecker` (two primaries, or a stale primary, among live
  nodes), `DataLoss` (acknowledged updates missing or older on the new primary), `UpdateRetryPolicy`
  (client redirect and retry: 50 attempts × 250 ms).
- **Transport.** Experiment 8 has no socket of its own. Updates, catch-up and role queries use
  Experiment 5's replication service over TCP (`ports().replication()`, 7101 to 7105 locally, 7101 to
  7103 public). Failure detection is the shared heartbeat detector on the election UDP channel
  (`ports().election()`, 7001 to 7005 locally).
- **Engine.** `FaultToleranceNodeService` (one per node) listens to that node's `FailureDetector` and
  promotes, demotes or resynchronises the node's `ReplicationNodeService`. `FailoverCluster` runs the
  cluster-wide steps: start, crash the primary, submit updates, follow the leader, planned handover,
  rejoin. `UpdateStream` sends a continuous stream of updates (default 4 per second, at most 10; a budget
  of 400 updates per reset).
- **Failover sequence.** The primary is crashed → the first node whose detector suspects it records
  `FAILURE_DETECTED` → Experiment 4 elects a new leader on the same suspicion → the new leader catches up
  from the other live backups, becomes primary at a higher epoch, writes the new term synchronously to
  every backup (`PRIMARY_PROMOTED`) → the first accepted update records `SERVICE_RESTORED`. The derived
  worst case is 10300 ms: detection (2500 + 700) + election (900 + 2200) + promotion (1000 + 2 × 1500).
- **Rejoin.** A recovered node asks the others who is in charge (`RoleQuery`). If its epoch is out of
  date it publishes `OLD_PRIMARY_DEMOTED` and then `RESYNCHRONISED`. If it is later elected again (Bully
  prefers the highest node), it takes over through a *planned handover*: the current primary stops
  serving first (`HANDOVER_STARTED`), then the new primary takes over at a higher epoch.
- **Module and API.** `FaultToleranceModule` (lab 8) and `FaultToleranceController` under
  `/api/modules/faulttolerance`: `POST /start`, `POST /stream/start`, `POST /stream/stop`, `PUT /mode`,
  `POST /crash-primary`, `POST /recover`, `GET /status`, `GET /runs`.
- **Events** (module `faulttolerance`): `PRIMARY_CRASHED`, `FAILURE_DETECTED`, `PRIMARY_PROMOTED`,
  `SERVICE_RESTORED`, `PROMOTION_FAILED`, `HANDOVER_STARTED`, `CATCH_UP_SKIPPED`, `ROLE_QUERY`,
  `ROLE_QUERY_FAILED`, `OLD_PRIMARY_RECOVERED`, `OLD_PRIMARY_DEMOTED`, `PRIMARY_RESUMED`, `RESYNCHRONISED`,
  `STALE_EPOCH_REFUSED`, `FAILOVER_MEASURED`, `DATA_LOSS_MEASURED`, `RECOVERY_MEASURED`,
  `SPLIT_BRAIN_CHECKED`, `STREAM_STARTED`, `STREAM_STOPPED`, `MODE_CHANGED`.

## Links to other experiments

- **L1, roles follow the leader.** The primary is the leader elected by Experiment 4. There is no second
  election inside this experiment: on a leader change, `EpochAuthority` issues the new epoch and the
  leader is promoted.
- **L2, one failure detector.** `FaultToleranceNodeService` registers on the same per-node
  `FailureDetector` that Experiment 4 uses. One `PEER_SUSPECTED` drives both the re-election and the
  failover.
- **L4, Lamport time on every message.** Every replication request, update and role query carries the
  sender's tick and is merged on receipt, through Experiment 5's `ReplicationClient` and
  `ReplicationNodeService`.
- **Experiment 5** provides the store, the replication transport and the `STALE_EPOCH` check.
- **Scenarios** "The leader dies mid-write" and "Split-brain that never happens" run this experiment end
  to end.

## How to run it in the UI

1. In the sidebar, click **Fault Tolerance** (`/experiments/8-faulttolerance`).
2. Click **Start experiment 8**. The leader becomes the first primary at epoch 2.
3. Under **Consistency mode** keep **Synchronous** and click **Start stream**.
4. Under **Failure**, click **Crash primary**. Wait until the timeline shows "Service restored".
5. Click **Recover old primary**.
6. To see data loss, choose **Asynchronous**, click **Crash primary** again while the stream runs, and
   compare the data-loss panel. Click **Stop stream** when done.

## What to observe

- **Replica rail:** each node's role, epoch and "Believes the primary is". After the crash a backup
  becomes primary at a higher epoch.
- **Failover timeline:** "Primary crashed", "Failure detected", "New primary chosen", "New primary
  promoted", "Service restored", then "Old primary back", "Old primary demoted" and "Old primary
  resynchronised" after the recovery.
- **Measurements:** "Detection time", "Failover time", "Recovery time" and "Data loss", measured. Beside
  them, "Expected detection window" and "Worst-case failover" are derived from the settings and labelled
  "Not measurements".
- **Data loss:** 0 lost in synchronous mode. In asynchronous mode, updates that were acknowledged but
  still waiting for their push can be lost, and the panel counts them.
- **Split-brain check:** "No two primaries: check passed".

## What is simulated

- The asynchronous push delay: "Asynchronous updates wait this long before they are pushed to the
  backups. The delay is simulated: it stands in for network latency." The running value is Experiment 5's
  `udcf.replication.async-delay-millis`, 450 ms. (docs/HANDOFF.md mentions 220 ms for the legacy demo;
  the integrated module uses 450 ms.)
- The co-hosting: "All nodes run in one backend process. Their messages cross real sockets, but over the
  loopback interface, not a network."
- Both carry the Simulated badge. The measured intervals do not depend on the simulated delay.

## Tests that prove it

- Backend (`backend/src/test/java/com/udcf/modules/faulttolerance`): `EpochRulesTest`,
  `EpochAuthorityTest`, `FailoverStateMachineTest`, `SplitBrainCheckerTest`, `DataLossTest`,
  `AcknowledgedLedgerTest`, `UpdateRetryPolicyTest`, `FailoverMeasurementsTest`, `RoleQueryTest`,
  `FailoverClusterTest`, `FaultToleranceNodeServiceTest`, `UpdateStreamTest`, `FaultToleranceEndToEndTest`,
  `FaultToleranceModuleTest`, `FaultToleranceControllerTest`, `LeaderFailoverIntegrationTest` (L1 and L2:
  one suspicion drives both the re-election and the failover).
- Core: `core/failure/FailureDetectorTest`.
- Scenario: `scenario/LeaderCrashEndToEndTest`, `scenario/SplitBrainSamplerTest`.
- Frontend (`frontend/src/modules/faulttolerance`): `FaultTolerancePage.test.jsx`,
  `FailoverTimeline.test.jsx`, `ReplicaRail.test.jsx`, `DataLossPanel.test.jsx`,
  `SplitBrainPanel.test.jsx`, `MeasurementsPanel.test.jsx`, `useFaultTolerance.test.js`,
  `contract.test.js`, and others.

## Viva questions

1. **What are the steps of a failover?**
   Detect the failure, choose a new primary, promote it after it catches up, and restore service as
   clients retry. UDCF marks each step on the failover timeline.
2. **What is split-brain, and how do epochs prevent it?**
   Two nodes acting as primary at once. Each promotion raises the epoch, and backups refuse updates with a
   lower epoch, so an old primary's writes are rejected even if it still believes it is primary.
3. **Why does the protection not depend on the failed node?**
   The check is on the backups (`STALE_EPOCH`). A misbehaving old primary cannot get its stale updates
   applied, whatever it does.
4. **Why does synchronous mode lose no confirmed update?**
   The client is told "success" only after the backups answered, so the new primary already holds every
   confirmed update, and it catches up from the other backups before serving.
5. **Why can asynchronous mode lose updates?**
   The client is told "success" before the push. Updates confirmed during the delay before the push are
   lost if the primary dies in that window.
6. **How long can a failover take here, and why?**
   At most about 10.3 s from the settings: up to 3.2 s to detect, up to 3.1 s to elect, up to 4 s to
   catch up and announce. The measured times are usually shorter.
7. **What happens when the old primary recovers?**
   It asks who is in charge, sees a higher epoch, demotes itself and resynchronises. Bully may then elect
   it again, and it takes over by a planned handover at an even higher epoch.
8. **Why reuse Experiment 4's election instead of a separate one?**
   One leader for the whole cluster (link L1) and one failure detector (link L2), so failover and
   election can never disagree about who leads.
