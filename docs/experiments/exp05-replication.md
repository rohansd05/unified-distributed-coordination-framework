# Experiment 5: Data Consistency and Replication Models

Module id `replication` · page `/experiments/5-replication` · package `com.udcf.modules.replication`

## Aim

Implementation of data consistency and replication models.

## Theory

*Replication* keeps copies of the same data on several nodes, for availability and for reads close to
the client. The hard part is keeping the copies consistent. In *primary-backup* replication one primary
accepts every write and pushes it to the backups.

- **Synchronous replication:** the primary confirms a write only after the backups have answered. Every
  confirmed write is on every live backup, but each write waits for the slowest backup.
- **Asynchronous replication:** the primary confirms at once and pushes later. Writes are fast, but for a
  short window a backup can return an *old* (stale) value. The copies converge afterwards; this is
  *eventual consistency*.

Conflicts between versions are resolved by a deterministic rule. *Last-writer-wins* keeps the version
with the larger timestamp; using the Lamport time with the node id as tie-breaker makes every replica
choose the same winner. *Anti-entropy* repairs replicas that missed updates (for example while crashed):
one replica sends its whole store, and the other keeps only what is newer. An old, out-of-order update
that arrives late must be rejected, not applied.

## How UDCF implements it

- **Store (pure).** `DataItem` (key, value, lamportTime, originNode, epoch); `isNewerThan` compares
  (epoch, lamportTime, originNode). `DataStore.apply(item, senderEpoch)` is the single guard for every
  update (`ConcurrentHashMap.compute` plus an epoch fence) and answers `APPLIED`, `DUPLICATE`, `STALE` or
  `STALE_EPOCH`. In this experiment the epoch never changes, so the rule is last-writer-wins on
  (lamportTime, nodeId).
- **Repair and tests (pure).** `AntiEntropy` (whole-store push, merged through `apply`),
  `OutOfOrderInjector` (the same key at Lamport time − 1), `ConsistencyCheck` (per key: missing, stale,
  ahead or conflict), `ReplicationStats` (the health table).
- **Transport.** `ReplicationNodeService` listens on TCP `127.0.0.1:ports().replication()`: 7101 to 7105
  locally, 7101 to 7103 public. TCP gives a definite acknowledgement and an immediate error when a peer
  is gone. `ReplicationProtocol` has REPLICATE, SYNC, READ and DUMP requests; `ReplicationClient` opens
  one connection per exchange.
- **Models.** SYNCHRONOUS pushes to all backups in parallel and confirms after every reply or failure.
  ASYNCHRONOUS confirms at once and pushes each backup after `async-delay-millis` (450 ms).
- **Primary.** `ReplicaSet` makes the leader elected by Experiment 4 the primary (L1); every other node
  is a backup. When the leader changes, the new primary first catches up from each live peer, then pushes
  its store to every live peer, and publishes `PRIMARY_SELECTED` and the cluster's
  `REPLICATION_ROLES_CHANGED`.
- **Module and API.** `ReplicationModule` (lab 5) and `ReplicationController` under
  `/api/modules/replication`: `POST /writes`, `GET /nodes/{nodeId}/values`, `GET /replicas`,
  `POST /nodes/{nodeId}/crash` (backups only), `POST /nodes/{nodeId}/recover`, `POST /anti-entropy`,
  `POST /stale-injections`.
- **Events** (module `replication`): `WRITE`, `WRITE_CONFIRMED`, `WRITE_NOT_CONFIRMED`, `ACK`,
  `REPLICATION_FAILED`, `REPLICA_APPLIED`, `REPLICA_DUPLICATE`, `REPLICA_STALE`, `REPLICA_STALE_EPOCH`,
  `ANTI_ENTROPY`, `ANTI_ENTROPY_MERGED`, `CATCH_UP`, `PRIMARY_ACTIVE`, `PRIMARY_SELECTED`,
  `ASYNC_PUSHES_DROPPED`, and others in `ReplicationNodeService`.

## Links to other experiments

- **L1, roles follow the leader.** A write asks `RoleProvider.ensureLeader()` for the primary. If no
  node can be elected in time the answer is HTTP 409 "No leader"; it never falls back to another node.
- **L4, Lamport time on every message.** `ReplicationClient` ticks the sender's clock into each request
  and merges the reply's time; `ReplicationNodeService` calls `node.clock().update(...)` on receipt and
  stamps the reply with `tick()`. Each write's Lamport time is also the version used by last-writer-wins.
- **Experiment 8** is built on this service: it promotes backups at higher epochs through
  `ReplicationNodeService.becomePrimary(epoch)`, and the `STALE_EPOCH` result is its split-brain guard.

## How to run it in the UI

1. In the sidebar, click **Consistency and Replication** (`/experiments/5-replication`).
2. Under **Write through the primary**, enter a **Key** and a **Value**, keep **Synchronous**, and click
   **Write**. The first write asks Experiment 4 for a leader if there is none.
3. Write the same key with a new value, choose **Asynchronous**, click **Write**, and look at the replica
   grid straight away.
4. Under **Nodes**, click **Crash node N** for a backup. Write the key again, then click **Recover node
   N**.
5. Under **Repair and test**, choose that node in **Push the primary's store to** and click **Run
   anti-entropy**.
6. Choose a backup in **Deliver to**, enter the same **Key** and an **Old value**, and click **Deliver
   stale update**.

## What to observe

- **Replica grid** ("Every replica, compared with the primary"): after the asynchronous write, backups
  briefly show **Stale** while "Replication window open" is shown, then turn **Current**.
- **"Latest write confirmed after":** much shorter for an asynchronous write, because it does not wait
  for the backups.
- **Crashed backup:** it misses the write, and after recovery the grid shows it as stale or missing until
  anti-entropy brings it back to Current ("Latest anti-entropy").
- **Stale update:** rejected; "Stale rejections" and the health table count it. The event log shows
  `REPLICA_STALE`.
- **Replication health:** acknowledgements, average and maximum latency, failures and last sync per
  backup, all measured round trips over TCP.

## What is simulated

Only the asynchronous push delay of 450 ms. The page's reason: "On one computer a replication round trip
takes well under a millisecond, so the moment when a backup still returns the old value would be too
short to see. Each asynchronous push therefore waits this long first, standing in for the network delay
of a real deployment. The wait is simulated; every latency shown is measured without it." The badge
appears on the Asynchronous option, the "Asynchronous push delay" metric and the replication window.
Synchronous pushes are never delayed.

## Tests that prove it

- Backend (`backend/src/test/java/com/udcf/modules/replication`): `DataItemTest`, `DataStoreTest`,
  `AntiEntropyTest`, `OutOfOrderInjectorTest`, `ConsistencyCheckTest`, `ReplicationStatsTest`,
  `ReplicationProtocolTest`, `ReplicationClientTest`, `ReplicationNodeServiceTest`, `ReplicaSetTest`,
  `LegacyScenarioTest`, `ReplicationModuleTest`, `ReplicationControllerTest`, `LeaderRolesIntegrationTest`
  (L1 for Experiments 5, 9 and 10).
- Integration: `L4LamportOnMessagesIntegrationTest` (TCP replication case).
- Frontend (`frontend/src/modules/replication`): `ReplicationPage.test.jsx`, `ReplicaGrid.test.jsx`,
  `HealthTable.test.jsx`, `PendingWindow.test.jsx`, `TakeoverTimeline.test.jsx`, `replicaModel.test.js`,
  `useReplication.test.js`, `contract.test.js`, and others.

## Viva questions

1. **What is the difference between synchronous and asynchronous replication?**
   Synchronous confirms only after the backups answer, so confirmed data is on every live backup.
   Asynchronous confirms at once and pushes later, so it is faster but a backup can be briefly stale.
2. **What is eventual consistency?**
   If no new writes arrive, all replicas eventually hold the same value. The asynchronous model and
   anti-entropy show it on the page.
3. **How does last-writer-wins decide between two versions?**
   By the larger (lamportTime, nodeId) pair (epoch first, but the epoch is constant here). Every replica
   applies the same rule, so they all keep the same winner.
4. **Why use Lamport time rather than wall-clock time as the version?**
   Wall clocks on different nodes drift (Experiment 3), so a later write could carry an earlier time.
   Lamport time respects cause and effect between messages.
5. **What is anti-entropy?**
   A repair pass: the primary sends its whole store to a backup, and the backup keeps only the items
   newer than its own. It fixes a replica that missed writes while crashed.
6. **What happens to a stale, out-of-order update?**
   `DataStore.apply` compares versions and answers STALE, so the older value is never applied. The page
   counts it under "Stale rejections".
7. **Why TCP for replication?**
   A write needs a definite acknowledgement, and the primary must learn at once when a backup is gone.
   TCP gives both; UDP would need its own acknowledgements and retries.
8. **Who is the primary, and what if there is none?**
   The leader elected by Experiment 4 (link L1). If no leader can be elected in time, the write is
   refused with 409 "No leader" rather than sent to some other node.
