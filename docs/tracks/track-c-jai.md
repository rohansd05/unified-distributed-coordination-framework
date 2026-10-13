# Track C — Jai

- **Owner:** Jai
- **Branch:** `jai`
- **Reviewer:** Rohan
- **Shared rules:** [docs/tracks/README.md](README.md)

---

## Experiment 5 — Data Consistency and Replication (Phase 6, moduleId: replication)

- **Reuse:** `legacy-demos/exp05-replication`; Appendix B Exp 5.
- **Special rules:** a single `apply()` guard using `ConcurrentHashMap.compute`; last-writer-wins on `(lamportTime, nodeId)`; epochs supported from the start (Exp 8 needs them); the 450 ms async delay is simulated and labelled; the primary comes from a module-local selector (TODO(L1)).

### Steps
- [x] E5a — `DataItem`, `DataStore`, last-writer-wins, anti-entropy, out-of-order injection, epochs and `ReplicationStats` as pure classes. Needs: none.
- [x] E5b — replication NodeService on `ports().replication()` (710k): sync and async writes, anti-entropy; document its public API under "Interfaces for other tracks" (Track B builds Exp 8 on it). Needs: E5a.
- [x] E5c — `ReplicationModule` (lab 5): write (sync/async), read per replica, crash or recover a backup, anti-entropy, inject a stale update; the health table; metrics; fixtures. Needs: E5b.
- [x] E5d — page and end-to-end check. Needs: E5c, E2d.

---

## Experiment 1 — Client-Server via Java RMI (Phase 10, moduleId: rmi)

- **Reuse:** none.
- **Special rules:** `java.rmi.server.hostname=127.0.0.1`; crash unexports the RMI objects.

### Steps
- [x] E1a — the `NodeEndpoint` remote interface (ping, nodeInfo, echo, computeHash(rounds)), its implementation, and a local-versus-remote timing helper. Needs: E5d.
- [x] E1b — an RMI registry per node on `ports().rmi()` (110k); export on start and recover, unexport on crash. Needs: E1a.
- [x] E1c — `RmiModule` (lab 1): invoke a method on a node (result, round-trip time, payload size, stub class), the local-versus-remote comparison, registry listing. Needs: E1b.
- [x] E1d — page and end-to-end check. Needs: E1c, E2d.

---

## Experiment 9 — MPI Collectives (Phase 11, moduleId: mpi)

- **Reuse:** none.
- **Special rules:** collectives run over the Exp 1 RMI layer; the root comes from a module-local selector (TODO(L1)); a crashed node is reported, and the operation never hangs.

### Steps
- [x] E9a — broadcast, scatter and gather logic. Needs: E1d.
- [x] E9b — `CollectiveEndpoint` over RMI on every node. Needs: E9a, E1b.
- [x] E9c — `MpiModule` (lab 9) and its API. Needs: E9b.
- [x] E9d — page and end-to-end check. Needs: E9c, E2d.

---

## Experiment 10 — Parallel Matrix Multiplication (Phase 12, moduleId: matrix)

- **Reuse:** none.
- **Special rules:** scatter row blocks of A, broadcast B, compute each block on that node's Exp 2 executor, gather; check the result against the sequential product (checksum); cap the size in the public profile; report speedup honestly (bounded by the machine's cores).

### Steps
- [x] E10a — matrix generation, row-block partitioning and the sequential product. Needs: E9d.
- [x] E10b — the parallel multiply over the collectives and executors. Needs: E10a, E9b, E2b.
- [ ] E10c — `MatrixModule` (lab 10) and its API; replace `web/TestModuleConfig`'s fake lab-10 module mechanism so tests no longer clash with the real lab 10. Needs: E10b.
- [ ] E10d — page and end-to-end check. Needs: E10c, E2d.

---

## Interfaces for other tracks

### E5a — replication store (`com.udcf.modules.replication`), for E5b (Track C) and Exp 8 (Track B)

Pure classes: no Spring, no sockets, no threads. All are thread safe unless noted.

**`DataItem` record** `(String key, String value, long lamportTime, int originNode, long epoch)`
- Limits (protocol limits; every violation, including null, is an `IllegalArgumentException`):
  key 1 to `MAX_KEY_LENGTH = 64` characters and not blank; value 0 to `MAX_VALUE_LENGTH = 1024`
  characters (empty allowed); `lamportTime >= 0`; `originNode >= 1`; `epoch >= 1`; no ISO control
  character (includes `\n`, `\r`, `\t`, U+0085) and no U+2028 or U+2029 in key or value.
- `';'` and `'~'` ARE allowed. **E5b rule: the wire format MUST encode items safely (escaping or
  Base64); never split on `;` or `~`** (the legacy `split(";", 4)` corrupted such values). E5b must
  test round-tripping values containing `;` and `~`.
- `isNewerThan(other)`: last-writer-wins on (epoch, then lamportTime, then originNode); null is older;
  the value is not compared. `sameVersionAs(other)`: same (epoch, lamportTime, originNode).

**`DataStore`** — `new DataStore()`; `INITIAL_EPOCH = 1`
- `ApplyResult apply(DataItem item, long senderEpoch)`: the single guard for every write (client
  write on the primary, replication, anti-entropy). `apply(item)` means `apply(item, item.epoch())`.
- `long observeEpoch(long observed)` (raise only; returns the epoch after the call), `long epoch()`,
  `Optional<DataItem> get(key)`, `SortedMap<String, DataItem> snapshot()` (detached, unmodifiable,
  key order), `size()`, `appliedCount()`, `duplicateCount()`, `staleCount()`, `staleEpochCount()`
  (the four always add up to the attempts), `clear()`.
- `clear()` is a whole-store reset (items, counters, epoch back to 1) for tests and the module reset
  only: a cluster reset restarts Lamport clocks at 0, so a raised epoch would refuse new writes. It
  is the only thing that lowers the epoch.

**`ApplyResult`**: `APPLIED` (stored); `DUPLICATE` (the store already holds exactly this item: same
epoch, Lamport time, origin AND value; nothing changes; not stale); `STALE` (lost on
last-writer-wins, OR the same version with a different value — see Known issues); `STALE_EPOCH`
(sender superseded). `stored()` is true for `APPLIED` only.

**Epoch rules (final)**
1. The item's epoch may not exceed the sender's: `item.epoch() > senderEpoch` is an
   `IllegalArgumentException` (nothing stored, nothing counted).
2. `senderEpoch` below the store's epoch: `STALE_EPOCH`; nothing stored, nothing raised.
3. `senderEpoch` above the store's epoch: the store's epoch is raised to `senderEpoch` (under the
   write lock) first, then the item is applied. A new primary's first push teaches a backup the
   new epoch.
4. An item with a LOWER epoch than the store's, from a sender at or above the store's epoch, is not
   refused: it competes by last-writer-wins, so old data is repaired, never lost.
5. The epoch is never lowered on the write path (`apply`, `observeEpoch`, `AntiEntropy.merge`).
6. Atomic guarantee: once `observeEpoch(n)` returns, or an apply that raised the epoch to n returns,
   no update from a sender with an epoch below n is ever stored. (`ReentrantReadWriteLock`: applies
   share the read lock from the fence check to the end of `ConcurrentHashMap.compute`; raising takes
   the write lock; a raising apply raises before it takes the read lock, so no lock upgrade.)

**How a recovering old primary learns the higher epoch (Exp 8):** either it queries its peers (the
legacy role query) and calls `observeEpoch(highestSeen)` before writing again, after which its own
superseded writes are `STALE_EPOCH` on every replica that saw the new epoch; or it receives a push
from the new primary, whose `senderEpoch` raises its store's epoch (rule 3). It then resynchronises
with `AntiEntropy.merge(itsStore, pushedFromNewPrimary, newPrimaryEpoch)`, which repairs old-epoch
items (rule 4). Demoting the node's role is Exp 8's job; the store only fences.

**`AntiEntropy`**: `List<DataItem> plan(DataStore source)` (whole store, key order, detached);
`AntiEntropyResult merge(DataStore target, Collection<DataItem> pushed, long senderEpoch)`: the batch
is copied and validated first (any item epoch above `senderEpoch` rejects the whole batch with
`IllegalArgumentException`, nothing applied), then each item goes through `target.apply(item,
senderEpoch)`; if the target's epoch rises above the sender's mid-merge, later items are
`STALE_EPOCH`. `AntiEntropyResult(pushed, applied, alreadyCurrent, stale, staleEpoch)`; the four
counts always equal `pushed`; `alreadyCurrent` = `DUPLICATE`.

**`ConsistencyCheck`**: `ConsistencyReport compare(int referenceNodeId, Map<Integer, Map<String,
DataItem>> replicas)` (leave unreachable replicas out). `ConsistencyReport(referenceNodeId,
comparedNodeIds, divergences)`, `consistent()`, `count(DivergenceKind)`. `ReplicaDivergence(nodeId,
key, reference, replica, kind)`; `DivergenceKind`: `MISSING`, `STALE`, `AHEAD` (newer, or a key only
the replica has), `CONFLICT` (same version, different value; never reported as STALE or AHEAD).

**`OutOfOrderInjector.staleVersionOf(DataItem current, String staleValue)`**: same key, epoch and
origin, Lamport time − 1 (strictly older); a current Lamport time of 0 is an
`IllegalArgumentException`.

**`ReplicationStats`** — `new ReplicationStats(int backupNodeId)` (>= 1); `MAX_LATENCY_MILLIS =
3_600_000`. `recordAck(ApplyResult outcome, double latencyMillis, Instant at)`: every ack is a
measured round trip, so latency and last sync are recorded whatever the outcome; latency must be
finite and 0 to the cap (no NaN or infinity). `recordFailure()` for no reply. `snapshot()` returns
`ReplicationStatsSnapshot(backupNodeId, acks, applied, duplicates, staleRejections,
staleEpochRejections, failures, OptionalDouble averageLatencyMillis, OptionalDouble
maxLatencyMillis, Optional<Instant> lastSync)`; `staleRejections` counts `STALE` only; averages and
max are empty (never 0) before the first ack. `reset()`. The caller measures latency and supplies
the time; nothing is simulated here (the 450 ms async delay belongs to E5b, labelled).

**`ReplicationRoleSelector`**: `selectPrimary(Collection<Integer> liveNodeIds)` (lowest live id) and
`backupsOf(int primaryId, Collection<Integer> allNodeIds)` (every other node, crashed included,
ascending). Module-local, marked `// TODO(L1): replaced by the shared role provider in Phase 9A`;
until then the replication primary comes from this selector, not from election.

**`ConsistencyModel`**: `SYNCHRONOUS`, `ASYNCHRONOUS`.

### E5a — deliberate differences from legacy-demos/exp05-replication
1. `DataItem` gains `epoch`; last-writer-wins orders by (epoch, lamportTime, originNode) (legacy:
   (lamportTime, originNode); the legacy tie-break is kept within an epoch).
2. `DataItem` validates its input and has no `encode`/`decode` (wire format moves to E5b).
3. `apply` returns `ApplyResult` instead of a boolean. An identical item is still not stored, but is
   `DUPLICATE`; legacy counted it as rejected and the primary recorded it as a stale rejection (so
   legacy anti-entropy inflated the stale count).
4. The same version with a different value is explicitly `STALE` (legacy: silently "not newer").
5. The epoch fence lives in the store, keyed on the sender's epoch, with an atomic guarantee
   (legacy Exp 8 kept a node-level `AtomicLong` check on the item's epoch for REPLICATE only, and
   its resync bypassed the fence).
6. A duplicate counter and `clear()`.
7. Anti-entropy is pure (`plan` + `merge`) and reports what landed; legacy pushed item by item over
   TCP. The push is the whole store, as in legacy.
8. `ReplicationStats`: running count, sum and max instead of an unbounded latency list; latency on
   every ack (legacy: only stored items); per-outcome counts; empty, not 0, before the first ack;
   time passed in by the caller (legacy read `System.currentTimeMillis` and formatted "ms ago");
   invalid latencies rejected; `ReentrantLock` instead of `synchronized`.
9. The out-of-order item is derived from the current version (Lamport − 1) instead of the legacy
   fixed `(L5, N1)`, so it is always older.
10. `ConsistencyCheck` replaces the printed final check and classifies each difference.
11. Not ported in E5a: `ReplicationNode` (TCP, E5b), `MessageType` (E5b), `ReplicationEvent` and
    `ReplicationEventLog` (replaced by the core `ClusterEventBus`), `NodeRole` (role selector and,
    later, cluster roles), the demo driver.

### E5b — replication transport (`com.udcf.modules.replication`), for E5c (Track C) and E8b (Track B)

**Ports and properties.** One TCP listener per node on 127.0.0.1:`ports().replication()` (710k:
7101–7105 locally, 7101–7103 in the public profile). `ReplicationProperties` binds
`udcf.replication.*` (no defaults in code; startup fails on a missing or invalid key):
- `async-delay-millis: 450`: **SIMULATED (R7)**, the wait before each asynchronous push, standing
  in for wide-area latency (Appendix B). Synchronous pushes are never delayed. Every place it shows
  carries the label: `WriteResult.simulatedDelayMillis` / `simulated()`, and `simulated: true` plus
  `simulatedDelayMillis` in the `WRITE_CONFIRMED` and `ACK` event data. E5c must show the Simulated
  badge for it.
- `timeout-millis: 1500`: the connect and read timeout of every exchange, on the client, and the
  server's `SO_TIMEOUT` on each accepted connection.
- `batch-size: 200`: items per anti-entropy SYNC message and per dump page; `@Min(1) @Max(1000)`.

**`ReplicationNodeService implements NodeService`** (`NAME = "replication"`, `MODULE = "replication"`)
- `static ReplicationNodeService on(ClusterNode, Cluster, ReplicationProperties, ClusterEventBus)`:
  through `ensureService`, so a crashed node throws `NodeDownException`; peer ports come from
  `cluster.node(id).ports().replication()`. `static Optional<ReplicationNodeService> find(ClusterNode)`
  never starts one. Public constructor `(ClusterNode, IntUnaryOperator peerPort, ReplicationProperties,
  ClusterEventBus, java.time.Clock wallClock)` (the clock is used only for `lastSync`, display only).
- Lifecycle: `start()`, `crash()`, `recover()`, `stop()`, `isRunning()`, `nodeId()`, `port()`.
- Role and epoch:
  - `void becomePrimary(long epoch)`: raises the store epoch to `epoch` (`observeEpoch`) and acts as
    primary at it; idempotent for the same epoch. Throws `NodeDownException` if down,
    `NotPrimaryException` if the node already knows a higher epoch, `IllegalArgumentException` for an
    epoch below 1. Exp 5 passes `epoch()`; **Exp 8 promotes with a new, higher epoch.**
  - `void stepDown()` (idempotent), `boolean isPrimary()`, `OptionalLong primaryEpoch()`,
    `long epoch()` (the store epoch: the highest epoch this node knows), `long observeEpoch(long)`
    (raise only; returns the epoch after the call). These work on a crashed node too; only
    `becomePrimary` needs the node up.
  - **Supersession:** the node stops acting as primary, and publishes `PRIMARY_SUPERSEDED` once,
    as soon as its store learns an epoch above its primary epoch: (a) a `STALE_EPOCH` reply to its
    own push (it calls `observeEpoch(replyEpoch)`), (b) a push from a newer primary (rule 3 of the
    E5a fence raises its store epoch), or (c) `observeEpoch` (for example from an Exp 8 peer
    query). **The role survives a crash**, so a recovered old primary still believes it is primary
    until (a), (b) or (c) happens. Cluster roles (`NodeDto.roles`) are not touched here.
- Writes: `WriteResult write(String key, String value, ConsistencyModel model, Collection<Integer>
  backupIds)`. Stamps `(key, value, clock().tick(), nodeId, primaryEpoch)`, applies it locally with
  `store.apply(item, primaryEpoch)`, then pushes to every backup. `backupIds` must be distinct,
  must not include this node, and are checked before anything changes (an unknown id fails with
  `UnknownNodeException`). Crashed backups are fine and end `FAILED`.
  - SYNCHRONOUS: pushes in parallel and returns after every backup replied or failed; the replication
    future is already complete.
  - ASYNCHRONOUS: returns at once; each push fires after the simulated delay; the future completes later.
  - Throws `NodeDownException` (down, or went down before a synchronous write was confirmed),
    `NotPrimaryException` (not acting as primary, or superseded), `IllegalArgumentException` (invalid
    key, value or backup list; nothing changes, the clock does not tick).
- `PushOutcome deliverOutOfOrder(int backupId, DataItem staleItem)`: demonstration only (E5c's
  "inject a stale update"); sent with this node's store epoch.
- `AntiEntropyReport antiEntropy(int targetId)`: pushes the whole store (`AntiEntropy.plan`) in
  `batch-size` chunks, at least one SYNC (so an empty store still proves reachability), with this
  node's store epoch; stops at the first failed chunk. **Not counted in `ReplicationStats`.**
- In-process reads `Optional<DataItem> get(String key)`, `SortedMap<String, DataItem> snapshot()`:
  `NodeDownException` while down (a crashed node refuses to serve).
- `SortedMap<Integer, ReplicationStatsSnapshot> stats()` (per backup this node pushed to: acks with
  measured TCP round-trip latency, outcome counts, failures, last sync) and `resetStats()`. Stats,
  the store and the role survive crash and recovery.

**`NotPrimaryException`** (`nodeId()`, `storeEpoch()`): the node is not, or no longer, acting as
primary. A superseded SYNCHRONOUS write throws it after its round. **"Not confirmed" does NOT mean
"not stored"**: the item was already applied to the old primary's own store, and possibly to
backups that had not yet seen the newer epoch. A superseded ASYNCHRONOUS write was already confirmed
to the client; the supersession shows only in its outcomes and events.

**Results**
- `WriteResult(item, model, localResult, confirmMillis, simulatedDelayMillis, backupIds,
  CompletableFuture<List<PushOutcome>> replication)`; `simulated()`. `confirmMillis` is measured.
- `PushOutcome(backupId, PushStatus status, Optional<ApplyResult> result, OptionalLong backupEpoch,
  OptionalDouble latencyMillis, Optional<String> detail)`; `acknowledged()`. Only `ACKED` has a
  result, epoch and latency (the measured TCP round trip, without the simulated delay); any other
  status only a detail. `PushStatus`: `ACKED`; `FAILED` (refused, timed out, closed without reply,
  or ERROR: counted as a backup failure, never stale); `NOT_SENT` (the sender went down before
  sending, for example a dropped async push); `ABANDONED` (the sender went down mid-flight: the
  backup may or may not have applied it). `NOT_SENT` and `ABANDONED` are not backup failures.
- `AntiEntropyReport(targetNodeId, AntiEntropyResult merged, chunksPlanned, chunksAcknowledged,
  latencyMillis, Optional<String> failure)`; `completed()`.

**`ReplicaSet`** (Exp 5 facade): `new ReplicaSet(Cluster, ReplicationProperties, ClusterEventBus)`;
`int primaryId()` (lowest live id via `ReplicationRoleSelector`, `// TODO(L1)`;
`IllegalStateException` if every node is crashed); `List<Integer> backupIds()` (every other node,
crashed ones included); `ReplicationNodeService service(int nodeId)` (starts it;
`NodeDownException` if crashed); `ReplicationNodeService primary()` (starts the service on every
live node, steps down any other live node acting as primary, and makes the selected node primary
at its current epoch, so in Exp 5 the epoch stays 1); `WriteResult write(key, value, model)`;
`AntiEntropyReport antiEntropy(int targetId)`; `PushOutcome injectStale(int backupId, String key,
String staleValue)` (`IllegalArgumentException` if the primary does not hold the key);
`ReadReply read(int nodeId, String key) throws IOException` and `ReplicaDump dump(int nodeId)
throws IOException` over TCP as the cluster-level client (sender `CLIENT_ID = 0`, the cluster
clock). Exp 8 should drive `ReplicationNodeService` directly rather than through `ReplicaSet`.

**`ReplicationClient(int timeoutMillis)`** (stateless, thread safe): `replicate`, `sync`, `read`,
`dumpPage`, `dump(port, senderId, senderClock, pageSize)`. Ticks the sender's clock before sending
and merges the reply's time (also on ERROR). Failures: `ConnectException` (crashed node),
`SocketTimeoutException` (silent node), `ProtocolException` (ERROR reply or malformed reply), any
other `IOException` (closed without a reply). Replies: `AckReply`, `SyncReply`, `ReadReply`,
`DumpPage`, `ReplicaDump` (records).

**Messages and wire format** (`ReplicationProtocol`, the single place it lives): one request and one
reply per TCP connection. Requests `REPLICATE`, `SYNC`, `READ`, `DUMP` (cursor paging); replies `ACK`,
`SYNCED`, `VALUE`, `DUMPED`, `ERROR`. Header fields are `|`-separated; messages with items are
followed by one `ITEM` line per item and an `END` line, and the count must match. Keys and values
travel as Base64 of their UTF-16 code units, so `;`, `~`, `|`, the empty value and unpaired
surrogates round-trip exactly. Lines are printable ASCII, at most 8192 characters; at most 1000
items per message; an item epoch above the sender's epoch is refused. Anything malformed gets an
`ERROR` reply and changes nothing. Every message carries the sender's Lamport time (L4).

**Events** (module `replication`, node = the publishing node, Lamport time from its shared clock):

| Type | Where, peer | Data keys |
|---|---|---|
| `WRITE` | primary, at the item's Lamport time | key, value, model, epoch, localResult, backups |
| `WRITE_CONFIRMED` | primary | key, model, confirmMillis; SYNC: acked, failed; ASYNC: simulated, simulatedDelayMillis |
| `WRITE_NOT_CONFIRMED` | primary | key, reason, storedLocally |
| `ACK` | primary, peer = backup | key, itemLamport, result, senderEpoch, backupEpoch, latencyMillis; async: simulated, simulatedDelayMillis; injected: outOfOrder |
| `REPLICATION_FAILED` | primary, peer = backup | key, reason (exception class), message |
| `REPLICA_APPLIED` / `_DUPLICATE` / `_STALE` / `_STALE_EPOCH` | receiver, peer = sender, at the reply's Lamport time | key, itemLamport, originNode, itemEpoch, senderEpoch, storeEpoch, receiveLamport |
| `ANTI_ENTROPY` / `ANTI_ENTROPY_FAILED` | sender, peer = target | pushed, applied, alreadyCurrent, stale, staleEpoch, chunksPlanned, chunksAcknowledged, latencyMillis; failed: reason |
| `ANTI_ENTROPY_MERGED` | receiver, one per chunk, peer = sender | pushed, applied, alreadyCurrent, stale, staleEpoch, senderEpoch, storeEpoch, receiveLamport |
| `PRIMARY_ACTIVE` | the node | epoch, previousEpoch (0 = none) |
| `PRIMARY_STEPPED_DOWN` | the node | epoch |
| `PRIMARY_SUPERSEDED` | the node, peer = the node it learned from (none if local) | previousEpoch, newEpoch, learnedFrom ("node N" or "local") |
| `ASYNC_PUSHES_DROPPED` | crashed primary (crash only, not stop) | pushes, keys (at most 20), keysTruncated |

Reads and dumps publish nothing.

**Close rule.** `crash()` and `stop()` return only after: the accept thread has exited (joined),
so the port is really closed (Linux keeps a listener open while a thread is blocked in `accept()`);
every connection handler has finished (`awaitTermination`; handlers re-check `running` immediately
before `store.apply` / `AntiEntropy.merge` and reply nothing once the node is down, so no request
changes the store after `crash()` returns); every outbound push and anti-entropy run has stopped;
and the async scheduler and its thread have stopped. Each wait is bounded at 5 s; if one does not
finish, an `IllegalStateException` is thrown. Pending async pushes complete as `NOT_SENT`.

**Metrics** arrive with the module in E5c (`MetricNames`, `node_id` per meter); E5b registers none.

**Added in E5c** (same classes): `ReplicationNodeService.catchUpFrom(int sourceId)` returns a
`CatchUpReport(sourceNodeId, Optional<AntiEntropyResult> merged, OptionalLong sourceEpoch,
latencyMillis, Optional<String> failure)`: it pulls the source's whole store over TCP (DUMP pages)
and merges it with `AntiEntropy.merge(store, items, the source's store epoch)`. It runs on the push
executor, so the close rule applies, and publishes `CATCH_UP` / `CATCH_UP_FAILED` (data: pulled,
applied, alreadyCurrent, stale, staleEpoch, sourceEpoch, storeEpoch, latencyMillis, reason). Each
DUMP page merges the reply's Lamport time, and a replica's clock is always at or above the Lamport
time of every item it holds, so after a catch-up the node's next write wins against everything it
pulled. `ReplicationNodeService.resetState()`: a running service is closed (close rule), cleared
(store, epoch back to 1, stats, role, pending async pushes `NOT_SENT`) and reopened; a crashed one is
only cleared and stays closed. `ReplicaSet` gained the takeover (below) plus `selectedPrimaryId()`,
`currentPrimaryId()`, `takeoverPending()`, `lastTakeover()` (all read-only) and `reset()`.

### E5c — replication module and API (`/api/modules/replication`), for E5d (Track C) and Track B

**`ReplicationModule`** (`@Component`, id `replication`, lab 5, title "Consistency and Replication").
Status: BUSY while an action holds the guard, RUNNING while asynchronous pushes are pending,
otherwise IDLE. `reset()` (called only by the cluster reset): two passes of `resetState()` over every
started service (a running service is closed, cleared and reopened; one on a crashed node is cleared
and stays closed), then the replica set forgets its primary and the module forgets its latest write,
anti-entropy and injection. It never starts a service and publishes nothing; the cluster reset clears
the event history.

**Endpoints** (all errors are ProblemDetail from the shared handler):

| Method and path | Body | Answer |
|---|---|---|
| `GET /api/modules/replication` | | `ReplicationOverviewDto` |
| `POST /writes` | `{key, value, model: SYNCHRONOUS\|ASYNCHRONOUS}` | 200 `WriteDto` (async: `replicationState` PENDING) |
| `GET /nodes/{nodeId}/values?key=` | | `ReadDto` (read over TCP) |
| `GET /replicas` | | `ReplicasDto` (every replica dumped over TCP) |
| `POST /nodes/{nodeId}/crash` | | `NodeReplicaDto`; backups only |
| `POST /nodes/{nodeId}/recover` | | `NodeReplicaDto`; any crashed node; runs a pending takeover |
| `POST /anti-entropy` | `{targetNodeId}` | `AntiEntropyDto` |
| `POST /stale-injections` | `{backupNodeId, key, staleValue}` | `InjectionDto` |

Errors:
- 400: body validation, a `DataItem` rule (control or line-break character), the primary as a
  crash, anti-entropy or injection target, or a key the primary does not hold.
- 404: unknown node.
- 409 `Node down`: the anti-entropy or injection target is crashed.
- 409 `Node state conflict`: crash of a crashed node, recover of an up node, or every node crashed
  (nodeId 0).
- 409 `Module busy`.

Every action (write, crash, recover, anti-entropy, injection) holds the module guard. Crash and
recover use `Cluster.crash` / `Cluster.recover`, as the cluster page does.

**Reads never take over.** GET `/`, `/replicas` and `/nodes/{id}/values` never start a service,
change a role or run a takeover. The overview reports:
- `primaryNodeId`: the selector's choice, read-only;
- `currentPrimaryNodeId`: the node last made primary;
- `takeoverPending`: a primary was made before and the selector now picks another node;
- `lastTakeover`.

A takeover runs only inside a write, anti-entropy, stale injection or recover.

**Takeover (Q1).** When the selector picks a node other than the one made primary last, that node:
1. catches up from every live peer (pull and merge, with each peer's store epoch);
2. acts as primary at its, possibly raised, epoch;
3. restarts its push statistics (the health table starts empty, with nulls);
4. pushes its whole store to every live backup.

On the first selection, or after a reset, both the catch-up and the push are skipped. Every
selection publishes `PRIMARY_SELECTED` on the new primary. Data: previousPrimaryId (absent on the
first selection), reason, catchUpSources, appliedFromCatchUp, pushedTo. Peer = the previous primary.

**DTOs** (`dto/`):
- `ReplicationOverviewDto`: status, actionInProgress, primaryNodeId, currentPrimaryNodeId,
  takeoverPending, lastTakeover, asyncDelayMillis, asyncDelayReason, timeoutMillis, batchSize,
  conflictRuleNote, models, nodes, healthMeasuredByNodeId, health, latestWrite, latestAntiEntropy,
  latestInjection.
- `NodeReplicaDto`: nodeId, nodeStatus, role (PRIMARY or BACKUP by the selector; null if every node
  is crashed), actingPrimary, serviceRunning, port, epoch (null if the service never started), and
  itemCount (null unless the service is running).
- `HealthRowDto`: backupNodeId, acks, applied, duplicates, staleRejections, staleEpochRejections,
  failures, averageLatencyMillis, maxLatencyMillis, lastSync. It is measured by the current primary;
  the latencies and lastSync are null before the first acknowledgement.
- `ModelDto`: model, description, guarantee, simulated, simulatedReason.
- `WriteDto`: writeId, model, primaryNodeId, item, localResult, confirmMillis, simulated,
  simulatedDelayMillis, simulatedReason, replicationState, backupNodeIds, pushes (empty while
  PENDING), takeover (non-null if this write changed the primary, including the first selection).
- `ItemDto`: key, value, lamportTime, originNode, epoch.
- `PushDto`: backupNodeId, status, result, backupEpoch, latencyMillis, detail (only ACKED has result,
  epoch and latency).
- `ReadDto`: nodeId, key, referenceNodeId, reachable, item, referenceItem, state, error.
  `ReplicaState` is CURRENT, ABSENT, MISSING, STALE, AHEAD, CONFLICT or UNREACHABLE; null when there
  is no readable reference.
- `ReplicasDto`: referenceNodeId, consistent, divergences, replicas (columns), rows. A
  `ReplicaColumnDto` holds nodeId, nodeStatus, reference, reachable, epoch, itemCount, error. A
  `KeyRowDto` holds key and cells; a `CellDto` holds nodeId, item, state.
- `AntiEntropyDto`: sourceNodeId, targetNodeId, completed, pushed, applied, alreadyCurrent, stale,
  staleEpoch, chunksPlanned, chunksAcknowledged, latencyMillis, failure.
- `InjectionDto`: primaryNodeId, backupNodeId, key, currentItem, staleItem, push, rejected.
- `TakeoverDto`: previousPrimaryNodeId, newPrimaryNodeId, appliedFromCatchUp, catchUps, pushes,
  lamportTime.
- `CatchUpDto`: sourceNodeId, completed, pulled, applied, alreadyCurrent, stale, staleEpoch,
  sourceEpoch, latencyMillis, failure (the counts are null when the pull failed).

**Simulated (R7).** Only the asynchronous delay (450 ms locally). It appears as
`asyncDelayMillis`/`asyncDelayReason`, as the ASYNCHRONOUS `ModelDto` (simulated, simulatedReason),
and as `WriteDto.simulated`/`simulatedDelayMillis`/`simulatedReason`. E5d shows the Simulated badge
next to each.

**Metrics** (`ReplicationMetrics`, `node_id` on every meter):

| Meter | Tags and meaning |
|---|---|
| `distributed_replication_latency` | timer `{node_id = backup, kind SYNCHRONOUS/ASYNCHRONOUS/OUT_OF_ORDER}`; measured round trip only |
| `distributed_replication_acks_total` | `{node_id = backup, result}` |
| `distributed_replication_failures_total` | `{node_id = backup}`; FAILED only, not NOT_SENT or ABANDONED |
| `distributed_replication_writes_total` | `{node_id = primary, model}` |
| `distributed_replication_store_items` | gauge `{node_id}`; NaN while the service is not running |
| `distributed_replication_epoch` | gauge `{node_id}`; NaN while the service is not running |

Anti-entropy and catch-up are not metered.

**Text for the E5d page ("What to notice"), also served as `conflictRuleNote`:** "Replicas settle
conflicting versions by last writer wins: the version with the higher Lamport time wins, and the
node id breaks a tie. In this experiment the epoch never changes, so nothing else is compared. An
asynchronous write that never left a primary before it crashed can therefore beat a write made
later on the new primary, if its Lamport time is higher: the write that happened later in real time
is then lost. That is what last writer wins means, not a fault; the epochs of Experiment 8 prevent
it."

**Fixtures:** `frontend/src/test/fixtures/replication/` (captured from the running backend; see its
README).

### E5d — replication page (`/experiments/5-replication`, `frontend/src/modules/replication/`)

Nothing another track needs to call. Notes for other "d" steps and for Phase 9A:
- **Kit usage.** `index.jsx` exports `{ id: 'replication', Page }`. The page passes `howItWorks`
  (four paragraphs), `controls`, `visualisation`, `measurements` and `whatToNotice` to
  `ExperimentLayout`.
  - `visualisation` holds `PendingWindow`, then `ReplicaGrid` (the bold element), then
    `TakeoverTimeline`.
  - `measurements` holds a divided `MetricCard` strip and `HealthTable`.
  - `whatToNotice` is two page strings plus the backend's `conflictRuleNote`, verbatim.

  The page reuses `usePoliteAnnouncement`, `formatMillis` and `moduleStatusLabel` from
  multithreading, `isNodeCrashed` from `lib/clusterStatus`, and `formatRelativeTime` from the
  events formatters.
- **Pure view models.** `replicaModel.js` provides `gridModel(replicasDto)` (the reference column
  first), `gridSummary`, `timelineModel(events, overview)` (PRIMARY_SELECTED events plus
  lastTakeover, with a pending marker), `pendingWindow(overview)` (open exactly while
  `latestWrite.replicationState` is PENDING, never on a timer) and `liveBackups(overview)`.
- **Live data.** `useReplication({ api })` reads the overview and `/replicas` together. It refreshes
  on any `/topic/modules/replication` event, on `/topic/cluster` and on a reconnect, coalesced to
  at most one read in flight plus one queued. It polls every 300 ms only while BUSY, RUNNING or a
  PENDING write. The timeline reads up to 500 module events through the shared `useModuleEvents`.
- **States on screen.** Every `ReplicaState` (CURRENT, STALE, MISSING, AHEAD, CONFLICT,
  UNREACHABLE, ABSENT, plus null as "Not compared") has a word and a distinct SVG shape, and a
  legend explains them. A null figure is "—" with an accessible label ("Not available" in
  `MetricCard`, "Not measured yet" in the health table), never 0.
- **Actions.**
  - Crash has no confirmation, as on the Cluster and Overview pages. After a crash or recover,
    focus moves to the counterpart button, or to the node's own label when a recovered node comes
    back as the primary (which has no crash button, only the Cluster-page link).
  - The other actions move focus to their result panel, or to the error alert.
  - Every result is announced politely. A 400's `errors` appear beside the fields.
- **Shared files touched (approved):** `frontend/src/routes.test.jsx` (add-only: one import and two
  spies on `replicationApi`) and `frontend/src/lib/experiments.js` (a `concept` line for lab 5 only).

### E1a interfaces for E1b/E1c — RMI contract and call model (`com.udcf.modules.rmi`)

Pure classes: no registry, export, socket, thread or Spring (except `RmiProperties`). The only
`java.rmi` types used are `Remote`, `RemoteException` and the exception classes the classifier
recognises.

**Contract.** `NodeEndpoint extends Remote`, `BOUND_NAME = "NodeEndpoint"`; every method `throws
RemoteException`: `PingReply ping(CallContext)`, `NodeInfo nodeInfo(CallContext)`,
`EchoReply echo(CallContext, String text)`, `HashResult computeHash(CallContext, int rounds)`.

**Lamport (L4).** `CallContext(senderNodeId >= 1, senderLamport >= 0)` is the first argument of every
call; every reply implements the sealed `EndpointReply` (`nodeId()`, `lamportTime()`). The node
validates the arguments first (an invalid call changes nothing), then `clock.update(senderLamport)`,
does the work, and stamps the reply with `clock.tick()`. The caller ticks before sending and calls
`update(reply.lamportTime())` on a success; a failed call merges nothing.

**Replies** (records, `Serializable`, `serialVersionUID = 1L`): `PingReply(nodeId, lamportTime)`;
`NodeInfo(nodeId, lamportTime, registryPort, capacityProfile, uptimeMillis, javaVersion)` (exactly these
six fields: no host, user or path; capacity is a `String`, so no enum crosses); `EchoReply(nodeId,
lamportTime, text)`; `HashResult(nodeId, lamportTime, rounds, seed, digestHex, serverNanos)`.

**`NodeEndpointLogic implements NodeEndpoint`** (the real work; its methods throw no
`RemoteException`). `new NodeEndpointLogic(nodeId, registryPort, NodeCapacity, LamportClock,
LongSupplier nanoClock, String javaVersion, RmiProperties)`; `static forNode(ClusterNode,
RmiProperties, LongSupplier nanoClock)` uses `node.id()`, `node.ports().rmi()`, `node.capacity()`,
`node.clock()` (the node's shared clock, never a second one) and `Runtime.version()`.
- `computeHash`: `d0 = UTF-8(seedFor(nodeId))` with `seedFor(n) = "udcf-rmi-node-" + n`, then
  `d(i+1) = SHA-256(d(i))` over the raw 32 bytes, `rounds` times; `digestHex` is the full digest;
  `serverNanos` is measured around the loop only. Vectors computed outside Java (sha256sum + xxd,
  openssl, Python agree) are in NodeEndpointLogicTest.
- Limits (`IllegalArgumentException`): rounds 1 to `max-hash-rounds`; echo text not null and at most
  `max-echo-length` UTF-16 characters (empty allowed); a null context.
- Uptime counts from the constructor. **For E1b:** export THIS object (so a LOCAL call and a REMOTE
  call run the same code); creating a new one on start and recover makes uptime restart with the
  "process", which is the recommended choice.

**Caller identity (for E1c).** The caller is always a real cluster node using its own shared clock
(`cluster.node(id).clock()`). **Both `CallContext` and `RmiInvoker(int senderNodeId, LamportClock
senderClock, RmiProperties, CallTimer)` require `senderNodeId >= 1`** (an `IllegalArgumentException`
otherwise), so 0 is not legal anywhere. There is no cluster-level client in Experiment 1 and **no
client clock**: E1a creates no clock. **E1c must offer a "from node" selector** and build the invoker
for that node with that node's clock; calling a node from itself is allowed. A crashed caller should
be refused by E1c before invoking.

**`RmiInvoker`**: `RmiCallResult invoke(EndpointLookup, int targetNodeId, RmiCall)` and
`LocalRemoteComparison compare(EndpointLookup local, EndpointLookup remote, int targetNodeId,
RmiCall)`. Order: validate (`IllegalArgumentException` before any lookup) → time the lookup → tick →
size the arguments (REMOTE only, untimed) → time the call → merge the reply and size it (REMOTE
only, untimed) or classify the failure. On the LOCAL path nothing is classified: an exception is
rethrown (a checked one wrapped in `IllegalStateException`). A null endpoint or a reply from another
node is an `IllegalStateException`.

**`EndpointLookup`** (E1b provides both): `NodeEndpoint lookup(int nodeId) throws RemoteException,
NotBoundException` and `CallPath path()`. REMOTE: the registry on `ports().rmi()` of the target,
`lookup(BOUND_NAME)`. LOCAL: the target's `NodeEndpointLogic` object itself; it must throw (for
example `NodeDownException`) for a crashed node, because a dead process computes nothing.

**`RmiCall(method, text, rounds)`**: `ping()`, `nodeInfo()`, `echo(text)`, `computeHash(rounds)`;
`validate(RmiProperties)`, `arguments(CallContext)`, `argumentSummary()`, `invokeOn(endpoint,
context)`, `static resultSummary(reply)`.

**`RmiCallResult`** (22 fields; factories `success`, `lookupFailure`, `callFailure`; the constructor
enforces the rules): method, targetNodeId, senderNodeId, path (LOCAL/REMOTE), argumentSummary,
resultSummary, reply, lookupNanos, roundTripNanos, timeToFailureNanos, argumentBytes, resultBytes,
stubClassName, outcome (SUCCESS/REMOTE_FAILURE), failureStage (LOOKUP/CALL), failureKind,
failureClass, failureMessage, failureCauseClass, senderLamportAtSend, replyLamport,
senderLamportAfterReply. **Null, never 0:**

| Outcome | Numbers set (all others null) |
|---|---|
| REMOTE success | lookupNanos, roundTripNanos, argumentBytes, resultBytes, the three Lamport values |
| LOCAL success | lookupNanos, roundTripNanos, the three Lamport values (nothing is marshalled) |
| failure at LOOKUP | **timeToFailureNanos** (the failed lookup's elapsed time); lookupNanos is null |
| failure at CALL | lookupNanos (the lookup completed), **timeToFailureNanos** (roundTripNanos is null: nothing came back), argumentBytes, senderLamportAtSend |

Durations (final, after the E1a follow-up): `lookupNanos` = time of a **successful** lookup only, null
if the lookup failed; `roundTripNanos` = **completed** calls only, null on any failure;
`timeToFailureNanos` = elapsed time of the stage that failed (LOOKUP or CALL), null on success. Only a
REMOTE call can fail; stubClassName is set exactly when a lookup succeeded.
- **Sizes** are `PayloadSizer.LABEL`: "Java serialization size (java.io ObjectOutputStream, stream
  header included); not the RMI wire bytes; primitive parameters are sized as primitives, as RMI
  marshals them". `PayloadSizer.sizeOf(Object...)` writes `Integer`/`Long` with `writeInt`/`writeLong`
  and everything else with `writeObject` into one stream (`rounds` costs 6 bytes, not a boxed
  Integer's descriptors).
- **Text.** argumentSummary, resultSummary and failureMessage are single-line: `\` → `\\`, newline
  `\n`, CR `\r`, tab `\t`, any other control character and U+2028/U+2029 → `\uXXXX`; `|` is kept.
  failureMessage is the exception's own `getMessage()` only (never a stack trace; the class is in
  failureClass), cut to `max-failure-message-length` characters ending in "…" (a surrogate pair is
  never split); null if the exception had no message. `EventLogExporter` already escapes `|`
  (`\p`) and newlines in `msg=`, so E1c event messages built from these strings are safe there.

**`LocalRemoteComparison(method, targetNodeId, localNanos, remoteNanos, overheadNanos, ratio,
digestsMatch)`**, `of(local, remote)`. Each side is a **single measurement** (local first, then remote,
no hidden warm-up call). The first call in a fresh JVM includes class loading and provider lookup, so
the overhead **can be negative and the ratio below 1**; nothing is clamped. A side that failed is null,
and so are overhead and ratio; ratio is also null when the local time is 0. digestsMatch is set for
COMPUTE_HASH with both sides successful. E1c/E1d must show these as they are.

**`RemoteFailureClassifier.classify(Throwable, CallStage)`**, first match wins: `ServerException` /
`ServerError` → METHOD_THREW; a `SocketTimeoutException` anywhere in the cause chain → TIMED_OUT;
`java.rmi.ConnectException` or `java.net.ConnectException` → CONNECTION_REFUSED; `ConnectIOException` →
CONNECTION_FAILED; `NoSuchObjectException` → NO_SUCH_OBJECT; `NotBoundException` → NOT_BOUND;
`MarshalException` → MARSHAL_FAILED; `UnmarshalException` → UNMARSHAL_FAILED; any other
`RemoteException` → OTHER; any other exception → METHOD_THREW at CALL (RMI rethrows the method's own
exception), OTHER at LOOKUP. `RemoteFailureKind.description()` is plain English for the page.

**`CallTimer(LongSupplier nanoClock)`**: `Measurement<T> measure(TimedAction<T>)` → `(value, failure,
elapsedNanos)`; a clock going backwards is an `IllegalStateException`, never clamped.

**`RegistrySnapshot(nodeId, registryPort, List<BoundName> names)`**, `BoundName(name,
stubClassName)`: names sorted, no duplicates, `find(name)`, `contains(name)`. E1b fills it from
`Registry.list()` plus a lookup per name.

**`RmiProperties`** (`udcf.rmi.*`, no code defaults, every value `@Min(1)`):

| Key | application.yml | application-public.yml | Used by |
|---|---|---|---|
| `max-hash-rounds` | 1000000 | 100000 | E1a (logic and invoker) |
| `max-echo-length` | 1024 | same | E1a |
| `history-size` | 50 | same | E1c |
| `call-timeout-millis` | 3000 | same | E1b (connect and response timeout) |
| `max-failure-message-length` | 300 | 300 (stated explicitly) | E1a |

**Classes that may cross RMI (for E1b's JEP 290 serial filter; no filter installed in E1a).**
Application classes: `com.udcf.modules.rmi.CallContext`, `PingReply`, `NodeInfo`, `EchoReply`,
`HashResult`. Built-in values: `java.lang.String` (written as a string, not as a class descriptor) and
the primitive `int`. No enum and no collection crosses; `EndpointReply` is an interface and writes no
descriptor. E1b must additionally allow, and prove by test, what RMI itself needs: the stub and DGC
classes the registry and lookup return (proxy, `RemoteObjectInvocationHandler`, `UnicastRef`, `ObjID`,
`Lease`, ...) and, on the client, the exception classes returned on the failure path (`Throwable`
subclasses, `StackTraceElement`, and the lists `Throwable` serializes for suppressed exceptions).

**Host name (for E1b).** E1b must set `java.rmi.server.hostname=127.0.0.1` (HANDOFF Section 7 and the
special rules above) before any object is exported, so every stub, and therefore every RMI exception
message that ends up in `failureMessage`, only ever carries the loopback address, never the machine's
host name.

#### E1a deviations from the approved plan

1. An extra package-private class `DisplayText` (single-line escaping and the cut, needed for C3 and
   C4), with `DisplayTextTest`.
2. `CallContext` requires `senderNodeId >= 1` (the plan allowed 0 for a cluster-level client), so 0 is
   not legal anywhere (C6).
3. The classifier checks `ServerException` / `ServerError` before the timeout chain (the plan had the
   timeout first), so a call that reached the node is METHOD_THREW even if the node's own nested call
   timed out.
4. A backslash is escaped as `\\` as well (beyond the C3 list), so the escapes read back unambiguously.
5. `failureMessage` is the exception's `getMessage()` only (the class is in `failureClass`), and null
   when the exception has no message.
6. Durations: first delivered with `lookupNanos` holding the time of a failed lookup; changed in the
   E1a follow-up (F1) to the final semantics above: `lookupNanos` only for a successful lookup,
   `timeToFailureNanos` for the stage that failed (LOOKUP or CALL), `roundTripNanos` only for a
   completed call.
7. The `forNode` tests use a Mockito mock of `ClusterNode`, because a real one needs a
   `ClusterEventBus`, which starts a dispatcher thread, and E1a tests start none.
8. The editing tool turned printable unicode escapes into raw characters in two test files; they were
   restored as escapes, and since the follow-up (F2) the rmi main and test sources contain no
   non-ASCII character at all (literals use escapes such as `\u2026`; comments are ASCII).


### E1b interfaces for E1c — per-node RMI transport (`com.udcf.modules.rmi`)

**`RmiNodeService implements NodeService`** (`NAME = "rmi"`), one per node.
- `static RmiNodeService on(ClusterNode, RmiProperties, RmiTransportProperties)`: through `ensureService`
  (a crashed node throws `NodeDownException`); every start and recovery exports a new
  `NodeEndpointLogic.forNode(node, properties, System::nanoTime)` on the node's shared clock.
  `static Optional<RmiNodeService> find(ClusterNode)` never starts one. Public constructor
  `(ClusterNode, RmiProperties, RmiTransportProperties, Supplier<? extends NodeEndpoint>)` (tests inject
  a blocking endpoint through the supplier).
- `start()`, `crash()`, `recover()`, `stop()`, `isRunning()`, `nodeId()`, `port()` (= `ports().rmi()`),
  `NodeEndpoint localEndpoint()` (the exported object; `IllegalStateException("node N is not running")`
  while not running). Lifecycle methods are synchronized; the class creates no thread.
- start/recover: hostname helper, new endpoint from the supplier, `LocateRegistry.createRegistry(port, f, f)`,
  five-argument `exportObject(endpoint, port, f, f, RmiSerialFilter)` on the SAME port with the SAME
  factory instance, `bind(BOUND_NAME, stub)`; `running` only after all succeeded. A failure unexports
  what was exported, waits for the listener, closes accepted connections and throws
  `IllegalStateException("Node N cannot start RMI on port P: ...")`. Starting a running service does
  nothing (the Experiment 5 pattern).
- crash/stop: `running = false`; unexport the endpoint, then the registry (`force=true`; RMI then closes
  the listener); wait up to 5 s until no thread is inside the closed listener's `accept()`; then, in
  `finally`, close every accepted connection and drop the references. If the wait expires, an
  `IllegalStateException` naming node and port is thrown AFTER the cleanup. Never touches the
  `LamportClock`. The service publishes nothing (`ClusterNode` publishes `NODE_CRASHED`).
- One `LoopbackSocketFactory` per service, created in the constructor and used for every start and
  recovery (see Known issues: RMI keeps the first equal factory per port).

**`RemoteEndpointLookup implements EndpointLookup`** (`path()` REMOTE):
`new RemoteEndpointLookup(IntUnaryOperator rmiPort, LoopbackSocketFactory clientFactory)`,
`static of(Cluster, RmiProperties, RmiTransportProperties)`. `lookup(nodeId)` does a fresh
`LocateRegistry.getRegistry("127.0.0.1", port, clientFactory).lookup("NodeEndpoint")` on every call
(no caching, so `lookupNanos` is honest and a crash shows at the LOOKUP stage); the result is a
`java.lang.reflect.Proxy` with a `RemoteObjectInvocationHandler`. `RegistrySnapshot snapshot(nodeId)
throws RemoteException, NotBoundException`: `registry.list()`, then `lookup(name).getClass().getName()`
per name; a node that is down throws the `RemoteException` (never an empty snapshot). Thread safe.
E1c should keep ONE instance (one client channel per node) rather than one per request.

**`LocalEndpointLookup implements EndpointLookup`** (`path()` LOCAL): `new LocalEndpointLookup(Cluster)`;
returns the running service's exported object; a node whose service is not running (never started,
stopped, crashed) gives `IllegalStateException("node N is not running")`, an unknown id
`UnknownNodeException`. E1c checks the node's state first and answers 409.

**`LoopbackSocketFactory`** (RMIServerSocketFactory + RMIClientSocketFactory, Serializable):
`(connectTimeoutMillis, callTimeoutMillis, listenBacklog)`, `static from(RmiProperties,
RmiTransportProperties)`, `LOOPBACK` (built like `ReplicationProtocol.LOOPBACK`). Server: unbound
`ServerSocket` bound to 127.0.0.1 with the backlog, never `setReuseAddress`; remembers accepted
sockets (transient) and counts threads inside `accept()`. Client: ignores the host, connects to
127.0.0.1 with the connect timeout, `SO_TIMEOUT = call-timeout-millis`. `equals`/`hashCode` on
`instanceId` (an AtomicLong counter) plus the three settings; `toString()` shows them (it appears in
stub strings and in RMI thread names).

**`RmiSerialFilter`** (`ObjectInputFilter`, per exported endpoint): pattern
`maxdepth=5;maxrefs=20;maxbytes=16384;maxarray=16;com.udcf.modules.rmi.CallContext;!*`, plus: every
array class is REJECTED (the JDK pattern matches an array by its element type, so it would allow
`CallContext[]`), and a class left UNDECIDED (primitive arrays) is REJECTED. No process-wide filter,
factory or `jdk.serialFilter`; the registry keeps the JDK's built-in registry filter. **The reply
direction (a caller reading a result) is unfiltered**: acceptable because both ends are in this one
JVM and every server is our own `NodeEndpointLogic` exported by `RmiNodeService`, whose replies are
the four E1a records; nothing outside the process can answer a call (loopback only).

**`RmiHostName`** (package-private): `ensureLoopback()` sets `java.rmi.server.hostname=127.0.0.1` if
unset, does nothing if already `127.0.0.1`, else `IllegalStateException` naming both values;
synchronized, called by every start and recovery, so no test depends on order.

**Properties** (`RmiTransportProperties`, `@ConfigurationProperties("udcf.rmi")`, separate from the E1a
`RmiProperties` on the same block; no code defaults; `@Min(1)`; `@Valid` nested record):

| Key | application.yml | public | Meaning |
|---|---|---|---|
| `connect-timeout-millis` | 1000 | same | connect timeout of every RMI client socket |
| `listen-backlog` | 50 | same | backlog of each node's listener |
| `serial-filter.max-depth` | 5 | same | filter limit (a real call: depth 1) |
| `serial-filter.max-refs` | 20 | same | filter limit (a real call: 2 references) |
| `serial-filter.max-bytes` | 16384 | same | filter limit (a real call: 74 bytes at its last checkpoint) |
| `serial-filter.max-array` | 16 | same | defence in depth (every array is rejected anyway) |

`call-timeout-millis` (E1a, 3000) is the client sockets' `SO_TIMEOUT`.

**Test ports (E1b):** sub-range 28800-28899 of Jai's block. RMI bases: 28800 (RmiNodeServiceTest,
28801-28803), 28810 (RmiCrashCycleTest, 28811-28813), 28820 (RmiFailureCharacterisationTest,
28821-28826), 28830 (RmiConcurrencyTest, 28831-28833), 28840 (RmiLookupsTest, 28841-28843); the
other five bases 28850-28890 are never bound (only the rmi service starts). LoopbackSocketFactoryTest
binds port 0 only.

**New failure kinds (E1a classifier change, for E1c's DTO and E1d's UI):** `CONNECTION_LOST` and
`ARGUMENTS_REJECTED` (the DTO carries `failureKind` and its `description()`; the page needs a word and
a sign for both). Full order in `RemoteFailureClassifier`'s Javadoc:
1. `ServerException` whose cause is an `UnmarshalException` -> ARGUMENTS_REJECTED ("The node could not
   read the call's arguments, for example because its serial filter refused them; the method never
   ran.");
2. other `ServerException` / `ServerError` -> METHOD_THREW;
3. `SocketTimeoutException` anywhere in the chain -> TIMED_OUT;
4. `java.rmi.ConnectException` / `java.net.ConnectException` -> CONNECTION_REFUSED;
5. `ConnectIOException` -> CONNECTION_FAILED; 6. `NoSuchObjectException` -> NO_SUCH_OBJECT;
7. `NotBoundException` -> NOT_BOUND;
8. `UnmarshalException` or `MarshalException` whose cause is an `EOFException` or a `SocketException`
   that is not a `ConnectException` -> CONNECTION_LOST;
9. `MarshalException` -> MARSHAL_FAILED; 10. `UnmarshalException` -> UNMARSHAL_FAILED (description now
   "The reply could not be read: its data could not be deserialized.");
11. other `RemoteException` -> OTHER; 12. anything else: METHOD_THREW at CALL, OTHER at LOOKUP.

**Observed failure matrix** (`RmiFailureCharacterisationTest`, logged as `E1b failure matrix | ...`;
class <- innermost cause | message | verdict):

| Scenario | Stage | Windows (JDK 21.0.12.1) | Linux (Docker, same JDK) | Verdict asserted |
|---|---|---|---|---|
| (a) node never started | LOOKUP | `java.rmi.ConnectException <- java.net.ConnectException` "Connection refused to host: 127.0.0.1; ... Connection refused: getsockopt" | same classes, "... Connection refused" | CONNECTION_REFUSED |
| (b) crashed, no earlier connection | LOOKUP | as (a) | as (a) | CONNECTION_REFUSED |
| (c) crashed after a successful call (pooled connection) | LOOKUP | `java.rmi.UnmarshalException <- java.net.SocketException` "Error unmarshaling return header; ... An established connection was aborted by the software in your host machine" | `java.rmi.UnmarshalException <- java.io.EOFException` "Error unmarshaling return header; ... java.io.EOFException" | CONNECTION_REFUSED or CONNECTION_LOST (observed: LOST in all 10 runs) |
| (d) stale stub after crash | CALL | as (c) | as (c) | CONNECTION_REFUSED or CONNECTION_LOST (observed: LOST in all 10 runs) |
| (e) stale stub after recover (pools drained while down) | CALL | `java.rmi.NoSuchObjectException` "no such object in table" | same | NO_SUCH_OBJECT; a fresh lookup then succeeds |
| (f) latch-blocked endpoint, call timeout 500 ms | CALL | `java.rmi.UnmarshalException <- java.net.SocketTimeoutException` "Error unmarshaling return header; ... Read timed out" | same | TIMED_OUT |
| filter refusal (RmiNodeServiceTest) | CALL | `ServerException <- UnmarshalException("error unmarshalling arguments") <- InvalidClassException("filter status: REJECTED")` | same (Stage A probe) | ARGUMENTS_REJECTED |

**Facts proved on this JDK** (item 6 of the step):

| Fact | Result | Proved by |
|---|---|---|
| (i) unexporting the last object frees the port synchronously | **False on Linux** (Stage A Docker probe: `crash: port bindable right after unexport=false, accept thread alive then=false`); true in every Windows probe run. The service therefore waits for the accept thread. | The service guarantee: `RmiNodeServiceTest.stopLeavesNothing`, `crashFreesThePort`, `exportFailureRollsBack`; `RmiCrashCycleTest.crashRecoverCycles` (bind on return, 60 times a run) and `stopStartCycles` (20 times) |
| (ii) the factory's SO_TIMEOUT survives the handshake and bounds the reply wait | True (`TCPChannel` restores a non-zero factory timeout) | `RmiFailureCharacterisationTest.timeoutAgainstABlockedEndpoint` |
| (iii) a crashed node with a pooled connection fails visibly | True: CONNECTION_REFUSED or CONNECTION_LOST, never an answer or a hang. Without closing accepted connections the "dead" node answered `NoSuchObjectException` over the pooled connection (Stage A probe (c')), so the crash closes them. | `RmiFailureCharacterisationTest.crashedAfterASuccessfulCall`, `staleStubAfterCrash`; `RmiCrashCycleTest.crashRecoverCycles`; `RmiNodeServiceTest.crashClosesAcceptedConnections` |
| (iv) a re-exported object has a new ObjID | True | `RmiFailureCharacterisationTest.staleStubAfterRecover` |

**Stage A probe evidence** (throwaway `Probe.java`/`FilterProbe.java`, run outside the repository;
quoted verbatim):
```
(e) recover with a NEW equal factory instance; stale stub then fresh lookup
    [factory n1-a] createServerSocket(28891) reuseAddress=false
    accepted tracked by OLD instance=2, by NEW instance=0
    after crash: old open=1
```
```
(f) timeout: blocked method, SO_TIMEOUT 500 ms, port 28894        (Linux, Docker)
    crash: port bindable right after unexport=false, accept thread alive then=false, accepted closed=1
```
```
Windows: java.rmi.UnmarshalException: Error unmarshaling return header; nested exception is:
         java.net.SocketException: An established connection was aborted by the software in your host machine
Linux:   java.rmi.UnmarshalException: Error unmarshaling return header; nested exception is: java.io.EOFException
```
```
byte[] len 4: UNDECIDED
byte[] len 17: REJECTED
```
```
    filter saw: maxDepth=1 maxRefs=2 maxBytes=74 classes=[Probe$Ctx]
    raw invoke echo(HashMap,..) (8 ms): java.rmi.ServerException[RemoteException occurred in server thread; nested exception is:  	java.rmi.UnmarshalException: error unmarshalling arguments; nested exception is:  	java.io.InvalidClassException: filter status: REJECTED] <- java.rmi.UnmarshalException[error unmarshalling arguments; nested exception is:  	java.io.InvalidClassException: filter status: REJECTED] <- java.io.InvalidClassException[filter status: REJECTED]
(c') crash after successful call WITHOUT closing accepted connections, port 28893
    stale stub ping (1 ms): java.rmi.NoSuchObjectException[no such object in table]
```
JDK source (21.0.12.1 `src.zip`): `TCPConnection`: `private long roundtrip = 5; // round-trip time for ping`
and `if ((roundtrip > 0) && (start < lastuse + roundtrip)) return (false);` (no ping inside the
window); `TCPChannel`: `sock.setSoTimeout((originalSoTimeout != 0 ? originalSoTimeout : responseTimeout));`.

#### E1b deviations from the approved plan

1. Q1: one `LoopbackSocketFactory` per `RmiNodeService` (not per start), with `instanceId` in `equals`.
2. Q2/A1: crash and stop wait (5 s) for the accept thread to leave `accept()`; cleanup in `finally`.
3. Q3/A2: E1a classifier changes (CONNECTION_LOST, ARGUMENTS_REJECTED, new UNMARSHAL_FAILED text).
   E1a files changed: `RemoteFailureKind.java`, `RemoteFailureClassifier.java`,
   `RemoteFailureClassifierTest.java` (the old case `UnmarshalException <- EOFException` = UNMARSHAL_FAILED
   now expects CONNECTION_LOST; UNMARSHAL_FAILED is tested with `InvalidClassException` and
   `ClassNotFoundException` causes; new cases from real chains; two new tests),
   `RmiInvokerTest.java` (`callFailures`: the EOF case now CONNECTION_LOST, plus an
   `InvalidClassException` case for UNMARSHAL_FAILED). `everyKindIsDescribed` enumerates
   `values()` and passes unchanged.
4. Q5: transport settings in a new record `RmiTransportProperties` on `udcf.rmi` (nested
   `serial-filter` block); `RmiProperties` untouched.
5. Found by `RmiSerialFilterTest`: the JDK pattern allows `CallContext[]` (element-type match), so the
   filter rejects every array class explicitly.
6. Q6: `LoopbackSocketFactory.LOOPBACK`, built like `ReplicationProtocol.LOOPBACK`.
7. `RmiNodeService` takes no `ClusterEventBus` (no events until E1c).
8. `LocalEndpointLookup` throws `IllegalStateException` ("node N is not running", as the step asked),
   not `NodeDownException` as the E1a Javadoc of `EndpointLookup` suggested.
9. An extra test-only helper `RmiTestSupport` (settings, cluster, probes, RMI method hash, and
   `drainUntilRefused`, which empties a channel's pooled connections while a node is down so a later
   call is deterministic).
10. Linux runs: the `maven:3.9-eclipse-temurin-21` image has no `unzip`, and the only-script `mvnw`
    then downloads the `.tar.gz` and checks it against the `.zip` checksum (fails). `unzip` was
    installed inside each container; no repository change.

### E1c interfaces for E1d — RMI module and API (`/api/modules/rmi`)

**`RmiModule`** (`@Component`, id `rmi`, lab 1, title "Client-Server via Java RMI"). It owns the
per-node `RmiNodeService`s:
- **Lazy start.** Services start on `POST /start` or before the first call or comparison, on every
  node that is up, never on a crashed node.
- **Live reads.** Crash and recover reach the services through the core (`ClusterNode`), and the
  module always reads live state from them (R10).
- **Status.** BUSY while a start, call or comparison holds the guard; RUNNING while any endpoint is
  exported; otherwise IDLE. ERROR is never used, because a remote failure is data.
- **Reset** (called by the cluster reset): forgets the history (ids restart at 1) and every start
  failure, then stops every RMI service. It never throws, never takes the guard and publishes
  nothing.
- **Shutdown.** `close()` (Spring, before `Cluster.close`) stops every service, so no exported
  object, listener or accept thread survives.
- **Concurrency.** Start, call and comparison each hold the guard for their whole duration and run
  on the request thread. GET requests never take the guard and never start a service.

**Endpoints.** Every error is a ProblemDetail. Examples are from `frontend/src/test/fixtures/rmi/`.

| Method and path | Body | Answer |
|---|---|---|
| `GET /api/modules/rmi` | | 200 `RmiOverviewDto` (no remote call). `overview-before-start.json`, `overview-after-start.json`, `overview-node-crashed.json` |
| `POST /start` | | 200 `RmiOverviewDto`; idempotent; 409 busy. `start.json` |
| `GET /registries` | | 200 `RegistryDto[]`, one per node, read over the network. `registries-before-start.json`, `registries-all-up.json`, `registries-node-3-crashed.json` |
| `GET /registries/{nodeId}` | | 200 `RegistryDto`; 404 unknown node. `registry-node-2.json` |
| `POST /calls` | `{fromNode, targetNode, method, text?, rounds?}` | 200 `RmiCallDto` for SUCCESS **and** REMOTE_FAILURE. `call-ping.json`, `call-node-info.json`, `call-echo.json`, `call-compute-hash.json`, `call-self.json`, `call-crashed-target.json`, `call-timed-out.json` |
| `POST /comparisons` | same body | 200 `ComparisonDto`. `comparison-compute-hash.json`, `comparison-ping.json` |
| `GET /calls` | | 200 `HistoryEntryDto[]`, newest first, at most 50. `calls-history.json` |
| `GET /calls/latest` | | 200 `HistoryEntryDto`; 404 when there are none. `call-latest.json`, `error-404-no-call-yet.json` |
| `GET /calls/{id}` | | 200 `HistoryEntryDto`; 404 for an unknown id. `call-by-id.json`, `error-404-unknown-call.json` |

Example call (`call-ping.json`, abridged):
`{"id":1,"method":"PING","fromNode":1,"targetNode":2,"path":"REMOTE","outcome":"SUCCESS","failureKind":null,"lookupNanos":1734900,"roundTripNanos":7237200,"timeToFailureNanos":null,"argumentBytes":96,"resultBytes":86,"stubClassName":"jdk.proxy2.$Proxy111","senderLamportAtSend":3,"replyLamport":5,"senderLamportAfterReply":6,"reply":{"method":"PING","nodeId":2,"lamportTime":5,...}}`

**Errors.**
- **409 "Node down"** (`nodeId`): the caller is crashed (a dead node cannot call), or a comparison's
  target is crashed (the local half cannot run). Fixtures: `error-409-caller-crashed.json`,
  `error-409-compare-target-down.json`.
- **409 "Node state conflict"**: a comparison's target is up, but RMI could not start on it.
- **409 "Module busy"** (`moduleId`, `actionInProgress`), for example
  `"actionInProgress":"COMPUTE_HASH from node 1 to node 2"`. Fixture: `error-409-busy.json`.
- **404 "Unknown node"** (`nodeId`, `error-404-unknown-node.json`) and **404 "Unknown call"**
  (`callId`, which is null when nothing has been called yet).
- **400, two shapes. E1d must handle both.**
  1. The module's own rules, `title` "Invalid request parameters", with every offending field under
     `errors`:
     `{"type":"about:blank","title":"Invalid request parameters","status":400,"detail":"Invalid request body: text, rounds","instance":"/api/modules/rmi/calls","errors":{"text":"text must be left out for PING","rounds":"rounds must be left out for PING"}}`
     (`error-400-field-not-taken.json`; also `error-400-missing-fields.json` and
     `error-400-rounds.json`). Messages: "fromNode/targetNode/method is required";
     "text is required for ECHO"; "text must be at most 1024 characters, was N";
     "rounds is required for COMPUTE_HASH"; "rounds must be 1-1000000, was N";
     "text/rounds must be left out for PING" (or whichever method was sent). A call from a node to itself is valid.
  2. Spring's default, with **no `errors` map**, for an unreadable body (for example an unknown
     `method`) or a non-numeric id. These exact bodies were captured:
     - `error-400-unreadable.json`:
       `{"type":"about:blank","title":"Bad Request","status":400,"detail":"Failed to read request","instance":"/api/modules/rmi/calls"}`
     - `error-400-bad-id.json`:
       `{"type":"about:blank","title":"Bad Request","status":400,"detail":"Failed to convert 'id' with value: 'abc'","instance":"/api/modules/rmi/calls/abc"}`

**DTOs** (`dto/`). An unknown or inapplicable value is null, never 0 (a reflection test checks every
numeric component):
- `RmiOverviewDto`: status, actionInProgress, boundName, nodes, methods, limits, payloadSizeBasis,
  lookupBasis, coHosting, comparisonNote, failureKinds, latest.
- `RmiNodeDto`: nodeId, nodeStatus (UP/CRASHED), state (`RmiNodeState`), registryPort,
  startFailure.
- `MethodDto`: method, description, parameters.
- `ParameterDto`: name, type (`string` or `int`), description, minimum, maximum. For `string` the
  bounds are a length in UTF-16 characters.
- `RmiLimitsDto`: maxHashRounds 1000000 (public 100000), maxEchoLength 1024, historySize 50,
  callTimeoutMillis 3000, connectTimeoutMillis 1000, maxFailureMessageLength 300.
- `CoHostingDto`: simulated (true), reason. **This is the Simulated badge for the node map. Every
  measured figure is real and carries no badge.**
- `FailureKindDto`: kind, description (all 11 kinds, in `RemoteFailureKind` order).
- `RegistryDto`: nodeId, registryPort, state, reachable, names (null, never `[]`, when
  unreachable), failureKind, failureDescription, failureClass, failureMessage, failureCauseClass.
- `BoundNameDto`: name, stubClassName.
- `RmiCallDto`:
  - identity: id, calledAt (display only), method, fromNode, targetNode, path
  - text and outcome: argumentSummary, resultSummary, outcome
  - failure: failureStage, failureKind, failureDescription, failureClass, failureMessage,
    failureCauseClass
  - times: lookupNanos, roundTripNanos (completed calls only), timeToFailureNanos (failures only)
  - sizes: argumentBytes, resultBytes, payloadSizeBasis (set exactly when a size is set; never on
    the LOCAL path)
  - the rest: stubClassName, senderLamportAtSend, replyLamport, senderLamportAfterReply, reply
- `ReplyDto`: method, nodeId, lamportTime; NODE_INFO adds registryPort, capacityProfile,
  uptimeMillis, javaVersion; ECHO adds text; COMPUTE_HASH adds rounds, seed, digestHex,
  serverNanos. The other methods' fields are null.
- `ComparisonDto`: id, calledAt, method, fromNode, targetNode, local, remote, localNanos,
  remoteNanos, overheadNanos, ratio, digestsMatch, note. **The overhead can be negative and the
  ratio below 1.** `comparison-compute-hash.json` captured exactly that: overhead -4563400 ns,
  ratio 0.949. Show it as measured, together with `note`.
- `HistoryEntryDto`: id, kind (CALL/COMPARISON), call, comparison (exactly one is set). Both halves
  of a comparison carry the comparison's id.

**Enumerations.**
- `RmiNodeState`: NOT_STARTED, EXPORTED, CRASHED.
- `HistoryKind`: CALL, COMPARISON.
- `RmiMethod`: PING, NODE_INFO, ECHO, COMPUTE_HASH.
- `CallPath`: LOCAL, REMOTE.
- `CallOutcome`: SUCCESS, REMOTE_FAILURE.
- `CallStage`: LOOKUP, CALL.
- `RemoteFailureKind`: CONNECTION_REFUSED, CONNECTION_FAILED, CONNECTION_LOST, TIMED_OUT,
  NO_SUCH_OBJECT, NOT_BOUND, MARSHAL_FAILED, UNMARSHAL_FAILED, ARGUMENTS_REJECTED, METHOD_THREW,
  OTHER.

**What `lookupNanos` contains (verified).** RMI's distributed-GC "dirty" call for the returned stub
happens inside `registry.lookup()`, so it is part of `lookupNanos`, not `roundTripNanos`:
- **JDK 21.0.12.1 source:** `RegistryImpl_Stub.lookup` ends with `ref.done(call)`, then
  `StreamRemoteCall.releaseInputStream`, then `ConnectionInputStream.registerRefs`, then
  `DGCClient.EndpointEntry.registerRefs`. That last step calls `makeDirtyCall` (`dgc.dirty`)
  synchronously on the calling thread when the object is not yet referenced in this JVM.
- **Test:** `RmiLookupBasisTest` shows that after a bare `lookup()`, before any method call, the
  node has accepted 2 connections (the registry request, and the dirty call on the stub's own
  channel), and RMI's client DGC thread for that endpoint exists. While an earlier stub is still
  alive, a second lookup makes no new dirty call.
- A failed dirty call does not fail the lookup.

The overview serves this as `lookupBasis`, and the page must show it next to the lookup time (as it
shows `payloadSizeBasis` next to the sizes).

**Worst-case request duration (for E1d's HTTP timeout).** A remote call takes at most three
exchanges: the registry lookup, the DGC dirty call inside it, and the method call. Each is bounded
by the 1000 ms connect timeout plus the 3000 ms read timeout, so about **12 s** in theory. A
comparison adds the local half, which has no timeout: up to 1,000,000 SHA-256 rounds, measured at
about 90 ms for 100,000 rounds on this machine. A failed start can add up to 5 s per node (E1b's
listener wait), and `SO_TIMEOUT` bounds each read, not a whole call. On loopback a dead node is
refused at once (10.6 ms in `call-crashed-target.json`). **The shared axios client times out after
10000 ms (`createApi`, default `timeout = 10000`), which is below 12 s. E1d must give these POSTs a
longer timeout (recommended: at least 20000 ms for calls and 25000 ms for comparisons), or a slow
real answer will show as a client timeout.**

**Events** (module `rmi`). No event ever ticks a clock; each carries the Lamport time of the step it
reports. Messages are single-line.

| Type | Node, peer | Lamport time | Data |
|---|---|---|---|
| `ENDPOINT_EXPORTED` | the started node | its clock's current value (the core's SERVICE_STARTED/RECOVERED ticked it) | registryPort, boundName |
| `CALL_SERVED` | target, peer = caller | the reply's Lamport time | callId, method, path, senderLamportAtSend |
| `CALL_SUCCEEDED` | caller, peer = target | the caller's time after the receive rule | callId, method, path, lookupNanos, roundTripNanos, argumentBytes?, resultBytes?, senderLamportAtSend, replyLamport, latencyMillis (REMOTE only) |
| `CALL_FAILED` | caller, peer = target | stage CALL: senderLamportAtSend; stage LOOKUP: the caller's current value | callId, method, path, stage, kind, failureClass, failureCauseClass?, timeToFailureNanos, senderLamportAtSend? |
| `COMPARISON_FINISHED` | caller, peer = target | the caller's current value after both halves | callId, method, localNanos?, remoteNanos?, overheadNanos?, ratio?, digestsMatch? |

A comparison publishes, in causal order: CALL_SERVED/LOCAL, CALL_SUCCEEDED/LOCAL,
CALL_SERVED/REMOTE, CALL_SUCCEEDED/REMOTE (or CALL_FAILED), COMPARISON_FINISHED.

**Link L5.** `latencyMillis` is the measured REMOTE round trip in **whole milliseconds, rounded half
up, so 0 means under half a millisecond**; the exact value stays in `roundTripNanos`. The export
writes it as `latency=7.0` (`events-export.txt`). It is set on REMOTE successes only: never on a
LOCAL half, a failure or CALL_SERVED. Exp 7's average-latency job therefore includes real RMI round
trips per calling node.

**Metrics.** `node_id` is always the **calling node**, and the target is the `target_node` tag:

| Meter | Tags |
|---|---|
| `distributed_rmi_calls_total` | node_id, target_node, method, path, outcome |
| `distributed_rmi_failures_total` | node_id, target_node, method, stage, kind |
| `distributed_rmi_round_trip` (timer) | node_id, target_node, method, path; completed calls only, never a time to failure |
| `distributed_rmi_endpoint_exported` (gauge) | node_id = that node; 1 while exported, 0 otherwise; read live and never starts a service |

**Link L6: how Experiment 9 gets the RMI layer.** Inject the `RmiModule` bean and use
`remoteLookup()` (the module's single `RemoteEndpointLookup`: one client channel per node, as E1b
recommends), `localLookup()`, and `ensureStarted()` (starts RMI on every live node; does not take
the rmi guard). Use `RmiNodeService.on/find` and `new RmiInvoker(node.id(), node.clock(), …)` with
the calling node's own clock. Nothing is copied into Exp 9.

**Test ports (E1c):** sub-range 28750-28799 of Jai's block.

| Port base | Class | Ports |
|---|---|---|
| 28750 | RmiControllerTest, `udcf.cluster.ports.rmi-base` | 28751-28755 |
| 28760 | RmiEndToEndTest | 28761-28765 |
| 28770 | RmiModuleTest | 28771-28773 |
| 28780 | RmiMetricsTest | 28781-28783 |
| 28790 | RmiLookupBasisTest | 28791-28792 |

The two Spring classes stop the RMI services and check that the ports are free in `@AfterAll`, and
close their context (`@DirtiesContext(AFTER_CLASS)`), so no cached context keeps a listener, accept
thread or RMI's reaper thread.

#### E1c deviations from the approved plan

1. An extra exception, `InvalidCallException`, mapped in `RmiController` to the shared 400 shape,
   so every offending field is reported at once.
2. An extra test class, `RmiLookupBasisTest` (A4), and a test-only helper, `RmiResultFixtures`
   (call results built through the E1a factories, without sockets).
3. `lookupBasis` was added to the overview (A4).
4. `RmiEndToEndTest` starts each test with `POST /api/cluster/reset` rather than a module reset,
   because call ids restart at 1 while old events stay until a cluster reset (known issue below).
5. 78 new tests rather than about 76.
6. An extra fixture, `registries-before-start.json`, plus `call-self.json` (A2),
   `error-400-unreadable.json` and `error-400-bad-id.json` (A1), and `events-export.txt`.

---

### E1d — RMI page (`/experiments/1-rmi`, `frontend/src/modules/rmi/`)

Nothing another track needs to call. Frontend only; the backend is unchanged since E1c.

**Page.** `index.jsx` exports `{ id: 'rmi', Page: RmiPage }`, so the existing `experiments/:slug` route renders it; no
router change. `RmiPage` passes `howItWorks` (four paragraphs), `controls`, `visualisation`, `measurements` and
`whatToNotice` to the shared `ExperimentLayout`, which adds the module event log.
- **Controls** (`RmiControls`): calling node (a crashed node is disabled and marked "down"), target node (a crashed
  target is allowed, so calling it shows the real failure), method with the overview's description, echo text and hash
  rounds. Every limit comes from `overview.methods[].parameters` and `overview.limits`, and the form checks them itself
  (`noValidate`, so the page's own message shows). Buttons: Invoke call, Compare local and remote, Refresh registries,
  and Crash or Recover per node through the shared `api.crashNode` / `api.recoverNode` (as the Cluster page).
- **Visualisation:** `NodeMap` (the bold element; hand-drawn SVG, node count from the overview, state as colour plus a
  glyph plus a word, the last call as a solid arrow, a dashed arrow labelled with the failure kind, or a loop for a
  self-call) and `RegistryList` (bound names and stub classes; an unreachable node shows as unreachable with its
  failure kind, description and exception class, never as an empty registry).
- **Measurements:** a MetricCard strip (last round trip, last lookup, both marshalled sizes, last comparison overhead),
  the `lookupBasis` and `payloadSizeBasis` texts, `RoundTripChart` (bars for completed remote calls, a cross for a
  failed one, never a zero bar) and `HistoryTable` (newest first; a row opens `GET /calls/{id}` in a detail panel).
- **Results** (`CallResult`, `ComparisonResult`): outcome, result, stage, kind (description from
  `overview.failureKinds`), exception and cause class, the message as plain text, every time and size, stub class,
  the three Lamport values; the comparison's direct and remote times, overhead, ratio, digests and the backend `note`.
- **What to notice** (`noticeModel`): three hedged sentences. A measured figure is added only when the history holds it:
  the first-versus-later figure needs two successful remote calls to the same node with the same method (the earliest
  and the latest kept); the crash callout uses the latest remote failure; the overhead callout the latest comparison.

**Start and reset (`useRmi`).** `POST /start` once on mount, then only on the edge "no up node is unstarted" to "some
up node is unstarted" (for example after a cluster reset). A failed start is not retried by itself; the page offers
"Try again". An overview read sent before a start answered is discarded and read again, so a stale "not started" can
never trigger a second start. Overview, history and registries are read together on every module event, cluster event
and reconnect, coalesced (one in flight, one queued); no polling. The history is replaced wholesale.

**Honest numbers.** A null is "n/a" with its reason as a tooltip and for screen readers (`NotAvailable`), never 0 or
hidden; MetricCard gets it as its value and is unchanged. Times below 1 microsecond in ns, below 1 ms in microseconds
(one decimal), otherwise ms (three decimals); the exact ns in a tooltip. Negative values keep a hyphen-minus; the ratio
has three decimals with the exact value in a tooltip. One Simulated badge, on the node map, for co-hosting
(`coHosting.reason`).

**Timeouts (deliberate choice).** `rmiApi.js` gives `POST /calls` `CALL_TIMEOUT_MS = 20000` and `POST /comparisons`
`COMPARE_TIMEOUT_MS = 25000` (E1c: about 12 s for a call; a comparison adds its local half, which has no timeout).
Every GET keeps the shared client's 10000 ms; the shared client is unchanged.

**Errors.** `describeProblem` maps each case to its own message: 400 with `errors` (also beside the fields), 400
without `errors`, 404, 409 busy (the server's message plus `actionInProgress`, read from the original response because
`ApiError` drops it; never retried), 409 "Node down", 409 "Node state conflict", a client timeout and a network error.
Buttons are disabled only while this page has a request in flight; a BUSY overview (another tab) shows a banner with
`actionInProgress` and leaves the buttons enabled.

**Files.** Created in `frontend/src/modules/rmi/`: `index.jsx`, `rmiApi.js`, `labels.js`, `rmiModel.js`, `useRmi.js`,
`RmiPage.jsx`, `RmiControls.jsx`, `CallResult.jsx`, `NodeMap.jsx`, `RoundTripChart.jsx`, `RegistryList.jsx`,
`HistoryTable.jsx`, and 11 test files (`rmiApi`, `labels`, `rmiModel`, `useRmi`, `CallResult`, `NodeMap`,
`RoundTripChart`, `RegistryList`, `HistoryTable`, `RmiPage`, `contract`). Shared, add-only (approved): the lab 1
`concept` line in `frontend/src/lib/experiments.js`, and one import plus four `rmiApi` spies in
`frontend/src/routes.test.jsx`. Reused across modules (as the Experiment 5 page already does): `usePoliteAnnouncement`
and `moduleStatusLabel` from multithreading.

**Tests.** 109 new tests in 11 files, all on the real E1c fixtures; frontend 676 -> 785 tests, 85 -> 96 files. Main
chunk 646.27 kB -> 690.50 kB (gzip 195.79 -> 208.27 kB).

Linux (Docker, node:22.11, clean clone), run by Jai: npm ci, lint, build and 785 tests (96 files) passed.

**Known issue.** Main chunk grew from 646.27 kB (gzip 195.79) to 690.50 kB (gzip 208.27) with the E1d page
(+44.23 kB, gzip +12.48 kB). Module pages are bundled in the main chunk: `frontend/src/modules/registry.js` loads
every page with `import.meta.glob('./*/index.jsx', { eager: true })`, which imports them statically. Route-level
code splitting is open for Phase 14.

#### E1d deviations from the approved plan

1. Two timeout constants (`CALL_TIMEOUT_MS` 20000, `COMPARE_TIMEOUT_MS` 25000) instead of one (Q4, approved).
2. The 409 "Node state conflict" tests import the real `fixtures/replication/error-409-node-state.json` (same shared
   `GlobalExceptionHandler`); no rmi capture of it exists (Q2, approved).
3. The BUSY-banner test uses a copy of `overview-after-start.json` with only `status` set to BUSY and
   `actionInProgress` taken from `error-409-busy.json`: no BUSY overview was captured.
4. `useRmi` discards an overview read that was sent before a start answered and reads again (found while writing the
   hook; it prevents a second start from a stale read). Tested.
5. The call form has `noValidate`: the browser's own `max` check blocked submission before the page's message (with
   the overview's limit) could show.
6. 109 new tests rather than about 85.

### E9a interfaces for E9b/E9c — MPI collectives model (`com.udcf.modules.mpi`)

Pure classes: no socket, registry, export, controller or Spring (except `MpiProperties`). Reused from Experiment 1:
`CallContext`, `EndpointReply.requireStamp`, `CallTimer`, `PayloadSizer` (no extension needed: a `double[]` is
`Serializable` and is written with `writeObject`), `RemoteFailureClassifier` and `CallStage`. Added to rmi, add-only:
`RemoteFailureDetail` (record; `of(Throwable, CallStage, maxLength)` gives stage, kind, exception class, single-line
cut message and innermost cause class by the E1a rules; `observed(stage, kind, message, maxLength)` for a failure found
without an exception). The rmi test helper `ScriptedNanoClock` is now public (class, constructor, `reads()`).

**Contract (E9b exports it).** `CollectiveEndpoint extends Remote`, `BOUND_NAME = "CollectiveEndpoint"`:
`ReceiveReceipt receive(CallContext, long collectiveId, int rank, double[] values)` (broadcast: the whole vector;
scatter: this rank's block) and `Contribution contribute(CallContext, long collectiveId, int rank, int count, long seed)`
(gather). `CollectiveEndpointLogic implements CollectiveEndpoint`: `new CollectiveEndpointLogic(nodeId, LamportClock,
MpiProperties)`, `static forNode(ClusterNode, MpiProperties)` (the node's shared clock). Every call: arguments checked
first (an invalid call changes nothing; length 1 to `max-payload-elements`), then `clock.update(senderLamport)`, the
work, and the reply stamped with `clock.tick()`. `receive` stores a copy by collective id in a bounded map
(`receive-store-size`, insertion order): a repeated id replaces its entry and becomes the newest; the oldest entry is
dropped when full. Thread-safe (one `ReentrantLock` around the store; the clock is atomic), proved by two latch-based
tests. Inspection: `received(id)`, `storedIds()` (oldest first), `storedRanks()`, `nodeId()`.

**Replies** (records, `Serializable`, `serialVersionUID = 1L`): `ReceiveReceipt(nodeId, lamportTime, collectiveId,
rank, elementCount, checksum)` (what the node actually received) and `Contribution(nodeId, lamportTime, collectiveId,
rank, values, checksum)` (values copied in and out; equality by element).

**Transport seam (E9b implements it over RMI).** `CollectiveTransport`: `ReceiveReceipt deliver(int targetNodeId,
CallContext, long collectiveId, int rank, double[] values)` and `Contribution collect(int targetNodeId, CallContext,
long collectiveId, int rank, int count, long seed)`, both `throws TransferException`. `TransferException(CallStage,
Exception)` says whether the endpoint could not be reached (`LOOKUP`) or the call failed (`CALL`) and wraps the
original, which the executor classifies. Any other exception from the transport counts as `CALL`. Each transfer must
be bounded by the transport's own timeouts: the executor cannot interrupt one.

**Entry point (E9c).** `new CollectiveExecutor(MpiProperties, LongSupplier nanoClock)`;
`CollectiveResult execute(CollectiveRequest, LamportClock rootClock, CollectiveTransport)`. `CollectiveRequest(long
collectiveId, CollectiveOperation operation, int rootNodeId, List<Integer> participantNodeIds, Set<Integer>
liveNodeIds, int count, long seed)`: the root is an explicit input (E9c chooses it; the leader via link L1 in Phase
13), the live set a snapshot taken by the caller. Single-threaded and deterministic.

**Result.** `CollectiveResult(collectiveId, operation, rootNodeId, rootRank, rankNodeIds, skippedNodeIds, count, seed,
outcome, refusal, inputChecksum, transfers, totalNanos, gathered, verification, deadlineMillis)`; `assembled()` gives
the gathered vector, or null while any block is a hole. `TransferRecord(rank, fromNodeId, toNodeId, direction
TO_RANK/FROM_RANK, status, offset, elementCount, latencyNanos, timeToFailureNanos, requestBytes, replyBytes, expectedChecksum,
reportedChecksum, verified, failure (RemoteFailureDetail), senderLamportAtSend, replyLamport, lamportAfterReply,
notAttemptedReason)`; its constructor enforces null-never-0 per status. `GatheredBlock(rank, nodeId, offset, length,
values, checksum)`: a hole has null values and checksum, never zeros. `Verification(passed, checked, matched,
mismatched, unknown, wholeVectorMatches)`. The size basis to show next to every size is `PayloadSizer.LABEL`.

**Ranks.** The participants that are live at the start, sorted by ascending node id, get ranks 0 to p-1. The root is
one of them; its rank need not be 0. A participant that is not live is excluded and listed in `skippedNodeIds`.
Example: participants 1 to 5, live {1, 2, 4, 5}, root 4: ranks 0 to 3 are nodes 1, 2, 4, 5, the root has rank 2,
node 3 is skipped.

**Blocks (scatter and gather).** n elements over p ranks: `base = n / p`, and the first `n % p` ranks get one more,
in rank order; block i belongs to rank i. Example: n = 10 over p = 4 gives lengths 3, 3, 2, 2 at offsets 0, 3, 6, 8.
n < p (for example 3 over 4) is refused. **Gather: rank i contributes exactly block i's length, so the assembled
vector has n elements.**

**Algorithm.** Linear: the root handles the ranks one after another in rank order (real MPI libraries use trees;
not part of this step). BROADCAST sends the whole vector to every other rank; SCATTER sends block i to rank i; GATHER
asks every other rank for its contribution and assembles the blocks in rank order. The root's own part never crosses
the network: status LOCAL, latency, sizes and Lamport times null, not verified and not counted in `checked`.

**Failure semantics.**

| Outcome | When |
|---|---|
| REFUSED | `NO_PARTICIPANTS`, `ROOT_NOT_PARTICIPANT`, `ROOT_DOWN`, `COUNT_BELOW_PARTICIPANTS` (scatter or gather with n < p), `PAYLOAD_TOO_LARGE` (n > `max-payload-elements`); nothing sent, no transfers, time or verification |
| COMPLETE | every remote transfer DELIVERED, or none was needed (the root is the only rank) |
| PARTIAL | at least one DELIVERED and at least one FAILED or NOT_ATTEMPTED |
| FAILED | at least one remote transfer was needed and none was DELIVERED |

Skipped nodes are not participants and do not change the outcome. Transfer statuses: LOCAL, DELIVERED, FAILED (stage
and kind as in Experiment 1; the collective goes on with the next rank), NOT_ATTEMPTED (the deadline had passed: once
`collective-deadline-millis` have passed since the start, no transfer starts; different from FAILED). A reply that
names another node, collective or rank, or a contribution of the wrong length, or no reply, is a FAILED transfer
(stage CALL, kind OTHER, message "reply did not match the request: ...") and the next rank is still attempted; it is
never DELIVERED or verified. `IllegalStateException` is kept for the executor's own contract (for example a clock going
backwards); a null argument or a duplicate or non-positive participant id is a programming error.

**Timing and sizes.** `latencyNanos`: measured around the transport call, DELIVERED only (a latency is a completed,
accepted exchange). `timeToFailureNanos` (F1): FAILED only, including a reply that did not match, a contribution of the
wrong length and no reply; the nanoseconds from just before the transport call until the failure or mismatch was
detected, measured by the same `CallTimer` around the same call. It is always set on a FAILED transfer (the executor
times every call itself, so no transport can leave it unknown) and null for DELIVERED, LOCAL and NOT_ATTEMPTED; the
record's constructor enforces both, and rejects a negative value. `totalNanos`: the clock just
after the last remote transfer minus the clock just before the first one, measured separately (not a sum); null when
no remote transfer was attempted. `requestBytes`: `PayloadSizer` of exactly the endpoint's arguments (context,
collective id, rank, values; or count and seed), set when something was sent (not after a LOOKUP failure);
`replyBytes`: of the reply. Both measured outside the timed window.

**Lamport (L4).** Before each transfer the root ticks and sends `CallContext(root, t)`; on a matching reply it applies
`update(reply.lamportTime())`; a failed or unmatched transfer merges nothing.

**Verification (from what arrived; unknown is null, never passed).** Broadcast: each receipt's checksum and count
equal the root's vector's. Scatter: each receipt equals its planned chunk's checksum and length; `wholeVectorMatches`
is true when every chunk is verified and the blocks tile [0, n), false on any mismatch, null while a chunk is
unverified. Gather: the root recomputes the checksum over the values it received; it must equal the node's reported
checksum and the independently generated expected block; `wholeVectorMatches` compares the assembled vector with the
expected one, null while a block is a hole. `passed`: false on any mismatch, null on any unknown or when nothing was
checked, else true.

**Data (reproducible).** `PayloadGenerator.vector(seed, count)`: SplitMix64. For element i (from 0):
`state = seed + (i + 1) * 0x9E3779B97F4A7C15` (mod 2^64); `z = state; z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9;
z = (z ^ (z >>> 27)) * 0x94D049BB133111EB; z = z ^ (z >>> 31)`; value `((z >>> 11) * 2^-53) * 2000.0 - 1000.0`, in
[-1000, 1000). Gather contribution of node k: `vector(seed ^ (k * 0x9E3779B97F4A7C15) mod 2^64, count)`.
`VectorChecksum.of(values)`: SHA-256 over each value's `Double.doubleToLongBits` as 8 big-endian bytes, lower-case
hex. The reference constants in PayloadGeneratorTest, VectorChecksumTest and CollectiveEndpointLogicTest come from this
Python 3 script (run with `python -I`):

```
import struct, hashlib
M = (1 << 64) - 1; G = 0x9E3779B97F4A7C15
def mix(z):
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M
    return z ^ (z >> 31)
def vector(seed, count):
    s = seed & M; v = []
    for _ in range(count):
        s = (s + G) & M; v.append((mix(s) >> 11) * 2.0**-53 * 2000.0 - 1000.0)
    return v
def checksum(v): return hashlib.sha256(b''.join(struct.pack('>d', x) for x in v)).hexdigest()
print(hex(mix(G)), hex(mix((2 * G) & M)))     # e220a8397b1dcdaf 6e789e6aa1b965f4
print(vector(42, 3), checksum(vector(42, 3)))  # [483.1297575436465, -680.1792142461597, -442.79773948972263] 5f1b64cf...
cs = (42 ^ ((2 * G) & M)) & M                  # contribution seed of node 2: 0x3c6ef372fe94f800
print(vector(cs, 2), checksum(vector(cs, 2)))  # [-251.37171903782416, -208.8065498773675] 4056f0d1...
print(checksum([1.0, -2.5, 0.1]), checksum([]))  # 6f832def... e3b0c442...
```

**Properties** (`MpiProperties`, `udcf.mpi`, no code defaults, every value `@Min(1)`, registered by scan like
`RmiProperties`):

| Key | application.yml | application-public.yml | Use |
|---|---|---|---|
| `max-payload-elements` | 100000 | 10000 | vector length; also E9b's filter array limit |
| `collective-deadline-millis` | 10000 | same | no new transfer after it |
| `history-size` | 20 | same | E9c |
| `receive-store-size` | 8 | same | payloads each node keeps (local worst case 8 x 800 KB per node) |
| `max-failure-message-length` | 300 | 300 (as `udcf.rmi`) | cut of a transfer's failure message |

**Open items for later steps.**
1. **E9b serial filter.** E9b must give each exported `CollectiveEndpoint` its own per-object serial filter that allows
   `double[]` up to `max-payload-elements` elements, plus `CallContext` (the E1b `RmiSerialFilter` rejects every array).
   The reply direction stays unfiltered (the E1b known issue: both ends are our own objects in this JVM).
2. **Experiment 10 sizes.** One message carries at most `max-payload-elements` doubles, so an N x N matrix broadcast in
   one message needs N <= floor(sqrt(max-payload-elements)): 316 locally, 100 in the public profile.
3. **HTTP budget.** One collective can last up to the deadline plus one worst-case transfer (connect, then lookup with
   RMI's DGC call, then the method: about 12 s per E1c), about 22 s. E9c/E9d must give the collective POST a
   per-request timeout of at least 25 s in the module's own API file (the shared client's 10 s is too short).
4. **Reduce and allgather** are not built. Reduce: a new `CollectiveOperation` value whose executor case collects every
   rank's contribution with the existing `collect` and combines them element-wise at the root with an operator (sum,
   max). Allgather: gather to the root, then broadcast the assembled vector, both with the existing transport methods.
   Neither needs a new transport method or endpoint method.
5. **Time to failure.** Resolved in F1: a FAILED transfer carries `timeToFailureNanos` (see "Timing and sizes"), and
   `totalNanos` still includes it, measured separately.

**No dedicated test (R6):** the enums `CollectiveOperation`, `CollectiveOutcome`, `TransferStatus`, `TransferDirection`
and `RefusalReason` (no behaviour); the interfaces `CollectiveEndpoint` and `CollectiveTransport`; `TransferException`
(a carrier with null checks); `Refusal` and `BlockPartition.Block` (records with checks only, covered by
RankAssignmentTest and BlockPartitionTest). Records with rules (`CollectiveRequest`, `ReceiveReceipt`, `Contribution`,
`GatheredBlock`, `TransferRecord`, `Verification`, `CollectiveResult`) are covered by MpiRecordsTest.

**Tests:** 75 new tests in 9 classes, no socket and no port (`PayloadGeneratorTest` 6, `VectorChecksumTest` 4,
`BlockPartitionTest` 5, `RankAssignmentTest` 6, `CollectiveEndpointLogicTest` 8, `CollectiveExecutorTest` 31,
`MpiPropertiesTest` 4, `MpiRecordsTest` 8, rmi `RemoteFailureDetailTest` 3), plus the test helper
`ScriptedCollectiveTransport` (real `CollectiveEndpointLogic` per node; scripted failures and rewritten replies).

Linux (Docker, --cpus=2), run by Jai: 75 mpi and RemoteFailureDetail tests x 5 runs plus 1 reverse-order run, 0 failures; full suite 1521 tests, 0 failures, 1 skipped; clean-clone full suite 1521 tests, 0 failures, 1 skipped.

#### E9a deviations from the approved plan

1. Q4 (approved): a reply that does not match its request is a FAILED transfer (CALL, OTHER), not an exception. A
   contribution of the wrong length and a null reply are treated the same way.
2. `RemoteFailureDetail` also has `observed(...)` (the plan named only `of`), for those mismatches, which have no
   exception; its message is cut by the same rule.
3. Small accessors beyond the listed signatures: `VectorChecksum.isChecksum`, `Contribution.length`,
   `GatheredBlock.hole`, `CollectiveResult.assembled`, `BlockPartition.Block.end`, `Verification.of`, and the
   inspection methods of `CollectiveEndpointLogic`.
4. The first final report gave a FAILED transfer no time of its own; superseded by F1 (item 6).
5. 71 tests rather than about 69 at the first final report (`CollectiveEndpointLogicTest` 8 instead of 10,
   `CollectiveExecutorTest` 28 instead of 24, `RemoteFailureDetailTest` 3); 75 after F1.
6. F1: timeToFailureNanos added after the first final report.

### E9b interfaces for E9c/E9d — RMI transport for collectives (`com.udcf.modules.mpi`, `com.udcf.modules.rmi`)

Backend only: no controller, events or metrics (E9c). No new `NodeService`: RMI keeps the first equal socket factory per
port (E1b), so the registry and every object on a node's port must share the one factory its `RmiNodeService` owns.

**Seam (rmi, add-only).** `RmiExtraExport`: `String boundName()`, `Class<? extends Remote> remoteType()`,
`Remote newEndpoint(ClusterNode node)` (called on EVERY start and recovery: a fresh object), `ObjectInputFilter
filter()`. `RmiNodeService` has a new constructor and a new `on(node, properties, transport, extras)` taking
`List<? extends RmiExtraExport>`; the old constructor and `on` delegate with no extras, so Experiment 1 alone behaves
exactly as in E1b. A bound name must be non-blank, unique, and never `NodeEndpoint`. The rmi package never imports mpi.
- **Start / recover:** loopback host name check, new `NodeEndpoint`, `createRegistry`, export and bind `NodeEndpoint`
  (unchanged), then for each extra in order: `newEndpoint(node)` (type checked against `remoteType`), export on the
  SAME port with the SAME factory instance and `extra.filter()`, bind under its name; `running = true` last. Any failure
  unexports the extras (reverse order), then `NodeEndpoint`, then the registry, then the same bounded listener wait and
  the same `finally` that closes accepted connections, and throws `IllegalStateException("Node N cannot start RMI on
  port P: ...")`.
- **Crash / stop:** `running = false`; unexport the extras (reverse order, force), then `NodeEndpoint`, then the
  registry (no explicit unbind, as in E1b: the registry's bindings go with it); the 5 s listener wait; in `finally`
  close accepted connections and drop the references.
- **Thread safety (A2):** lifecycle methods are synchronized. The fields read outside the lock (`running`, `endpoint`,
  `exportedExtras`, an unmodifiable map by bound name) are volatile and published in order: an open sets the objects
  before `running`; a close clears `running` before the objects. `localExport(name)` reads `running` first and then the
  map, so it never returns an object of a closed service.
- `RmiNodeService.localExport(String boundName)`: the current exported object itself; `IllegalStateException("node N is
  not running")` while down; `IllegalArgumentException` for a name no extra export has.
- **Spring:** `mpi/CollectiveEndpointExport` is a `@Component implements RmiExtraExport` (`MpiProperties`,
  `RmiTransportProperties`); `newEndpoint(node)` is `CollectiveEndpointLogic.forNode(node, mpiProperties)`, so every
  start and recovery has a fresh logic with an empty store on the node's own clock. `RmiModule` has an `@Autowired`
  constructor taking `ObjectProvider<RmiExtraExport>` (ordered; none means no extras), a constructor taking the list,
  and the unchanged 6-argument constructor (no extras). `ensureStarted` passes the extras to `RmiNodeService.on`. Lazy
  start is unchanged (E1c).

**Lookup (rmi, add-only).** `RemoteEndpointLookup.lookup(int nodeId, String boundName, Class<T> type)`: a fresh
`getRegistry` + `lookup(name)` every time (no caching), `IllegalStateException` "... not a <Type>" for the wrong type,
`NotBoundException` for a missing name. `lookup(nodeId)` delegates to it (same message as before).

**Transport (mpi).** `RmiCollectiveTransport implements CollectiveTransport`: `new RmiCollectiveTransport(
RemoteEndpointLookup)`, `static of(RmiModule)` (the module's single lookup, one client channel per node). Each transfer:
the typed lookup of `CollectiveEndpoint` (any exception: `TransferException(LOOKUP, cause)`), then the call (any
exception, including the method's own, which RMI rethrows: `TransferException(CALL, cause)`). No retry, nothing
swallowed, no new timeouts or sockets (Experiment 1's connect 1000 ms and call 3000 ms through the shared factory).
Root-agnostic: the caller is the `CallContext` sender, which the executor sets from the root; one instance serves every
root.

**Latency basis (A1).** `RmiCollectiveTransport.LATENCY_BASIS` is the text to show next to every `latencyNanos`: the
whole transfer, i.e. a fresh registry lookup (including RMI's DGC "dirty" call when this process holds no other live
stub for that endpoint) plus the method call. **This differs from Experiment 1**, where the lookup and the call are
separate figures (`lookupNanos`, `roundTripNanos`).

**Serial filter.** Per exported CollectiveEndpoint (`CollectiveEndpointExport.filterFor`), built with E1b's helper
(`RmiSerialFilter.allowing`, add-only; `RmiSerialFilter.from` is unchanged and still rejects every array):
- allowed classes: `com.udcf.modules.rmi.CallContext`; allowed arrays: `double[]` only; everything else ends in `!*`,
  and every other array type (`long[]`, `int[]`, `Object[]`, `CallContext[]`, `double[][]`, ...) is rejected;
- `maxarray` = `max-payload-elements` (100000 local, 10000 public);
- `maxbytes` = 8 x `max-payload-elements` + `udcf.rmi.serial-filter.max-bytes` (16384): local 816384, public 96384.
  **`udcf.rmi.serial-filter.max-bytes` therefore has a second meaning:** besides E1b's limit for a NodeEndpoint call,
  it is the overhead on top of the payload for a CollectiveEndpoint call;
- `maxdepth` and `maxrefs` from `udcf.rmi.serial-filter` (5 and 20). No new keys, no YAML change.
The reply direction stays unfiltered (the E1b known issue). The filter never sees a String (known issue below).

**Access for E9c.** (a) `RmiCollectiveTransport.of(rmiModule)`. (b) `CollectiveEndpointExport.current(ClusterNode)`:
`Optional<CollectiveEndpointLogic>`, the node's CURRENT local object (no stub), empty while its RMI service is not
running or exports no CollectiveEndpoint; use its `received`, `storedIds` and `storedRanks` to show what each node
received.

**Notes for E9c and E9d (A3).**
1. E9c must call `RmiModule.ensureStarted()` (RMI starts lazily) before running a collective, and treat a node whose
   RMI is not running as a participant that fails at LOOKUP with CONNECTION_REFUSED, shown, never hidden.
2. E9d: `frontend/src/test/fixtures/rmi/registries-*.json` and their README were captured before E9b and list one name
   per node; a live backend now lists two (`CollectiveEndpoint` and `NodeEndpoint`). The E1d page
   (`RegistryList.jsx`) maps over every name and handles this, but E9d must not rely on the old fixtures for the
   registry's shape, and the README needs a one-line note (not changed here).
3. The Experiment 1 page now shows two registry entries per node. That is correct.
4. The HTTP budget of E9a open item 3 is unchanged (deadline plus one worst-case transfer, about 22 s; at least 25 s
   per request in the module's API file).

**Observed failure matrix** (Windows 11, JDK 21.0.12.1; logged as `E9b failure matrix | ...` and from the Stage B probe;
class <- cause):

| Case | Stage | Observed (Windows) | Asserted |
|---|---|---|---|
| (a) node never started | LOOKUP | CONNECTION_REFUSED: `java.rmi.ConnectException <- java.net.ConnectException` | CONNECTION_REFUSED |
| (b) crashed, no pooled connection | LOOKUP | CONNECTION_REFUSED: `java.rmi.ConnectException <- java.net.ConnectException` | CONNECTION_REFUSED |
| (c) crashed after a pooled call | LOOKUP | CONNECTION_LOST: `java.rmi.UnmarshalException <- java.net.SocketException` | {CONNECTION_LOST, CONNECTION_REFUSED} |
| (d) latch-blocked endpoint, call timeout 300 ms | CALL | TIMED_OUT: `java.rmi.UnmarshalException <- java.net.SocketTimeoutException`; entered once (no retry) | TIMED_OUT |
| (e) oversize, local limit + 1 (100001 doubles) | CALL | CONNECTION_LOST: `java.rmi.MarshalException <- java.net.SocketException` "An established connection was aborted by the software in your host machine" (3 of 3 probe runs, also 150000) | {ARGUMENTS_REJECTED, CONNECTION_LOST} + barrier proof |
| (e) oversize, public limit + 1 (10001 doubles) | CALL | ARGUMENTS_REJECTED: `ServerException <- UnmarshalException <- InvalidClassException("filter status: REJECTED")` (3 of 3; also 20000); 50000 gave CONNECTION_LOST | {ARGUMENTS_REJECTED, CONNECTION_LOST} + barrier proof |
| forged `long[]`, `Object[]`, `int[]` in the `double[]` slot | CALL | ARGUMENTS_REJECTED (filter) | ARGUMENTS_REJECTED + barrier proof |
| forged `String` in the `double[]` slot | CALL | METHOD_THREW: `java.lang.IllegalArgumentException` "argument type mismatch" (RMI's dispatch; the filter never sees it) | METHOD_THREW + barrier proof |
| (f) Experiment 1 only (nothing bound under CollectiveEndpoint) | LOOKUP | NOT_BOUND: `java.rmi.NotBoundException` | NOT_BOUND |
| (g) the method threw (count 0) | CALL | METHOD_THREW: `java.lang.IllegalArgumentException` | METHOD_THREW |
| end to end, participant crashed after the start | LOOKUP | CONNECTION_REFUSED: `java.rmi.ConnectException <- java.net.ConnectException`; PARTIAL, later rank delivered | {CONNECTION_REFUSED, CONNECTION_LOST} |
| recovery | | success, a fresh logic with an empty store (after E1b's pool drain) | success |

**Oversize size dependence.** The node's filter refuses a too-long `double[]` at the array's header. If the caller has
finished writing by then (small payloads: up to 20000 doubles in the probe), it reads the node's refusal:
ARGUMENTS_REJECTED. If not (large payloads on Windows: from 50000 doubles), the node replies and closes while the caller
is still writing, and the caller's write fails: CONNECTION_LOST. The cut-over depends on socket buffers and may differ
on Linux. **Barrier proof** (all refusal rows): after the refused call, one valid call to the same node with sender
Lamport time 0, waiting for its reply; then the store holds only the valid ids and the node's clock moved by exactly 2,
what the valid call alone does. A refused call that had run, early or late, would have stored its id and moved the
clock by 2 more.

**Test ports (E9b): 28500-28599.** RMI bases 28560 (`RmiNodeServiceExtrasTest`), 28570 (`RmiCollectiveTransportTest`),
28580 (`CollectiveFilterTest`), 28590 (`CollectiveEndToEndTest`), each binding base + 1 to base + 3; the other five
bases 28505, 28515, 28525, 28535 and 28545 are never bound (only the rmi service starts). Grep of `backend/src/test`
for 28500-28599 (no test YAML or properties exist): only the replication tests' requests bases 28500 (ReplicationNodeServiceTest),
28510 (ReplicaSetTest), 28540 (ReplicationModuleTest) and 28550 (ReplicationMetricsTest), never bound by those tests;
their ranges end at 28559, below every port bound here (28561-28595). All below 32768.

**Tests.** New: `RmiNodeServiceExtrasTest` 7 (rmi; a test-only `Probe` remote interface, so rmi tests never depend on
mpi), `CollectiveEndpointExportTest` 5, `RmiCollectiveTransportTest` 11, `CollectiveFilterTest` 5,
`CollectiveEndToEndTest` 6, and the test-only helper `CollectiveRmiFixture` (mpi; a three-node cluster with the
collective export, plus the barrier). Added: `RmiSerialFilterTest` +4, `RmiModuleTest` +1. Changed: one assertion in
`RmiControllerTest` (two names in the live context). `RmiTestSupport`: the class, `PROPERTIES`, `TRANSPORT`, `bus`,
`NODE_GONE`, `RemoteAction`, `drainUntilRefused`, `canBind`, `acceptThreadAlive` and `methodHash` made public
(visibility only). 39 new tests. No sleeps: latches and Awaitility only.

Linux (Docker, --cpus=2), run by Jai: 354 mpi and rmi tests x 5 runs plus 1 reverse-order run, 0 failures; full suite 1560 tests, 0 failures, 1 skipped; clean-clone full suite 1560 tests, 0 failures, 1 skipped.

#### E9b deviations from the approved plan

1. Q4 (approved): test ports 28500-28599 instead of 28300-28399; the grep there was not empty (the replication tests'
   requests bases, see above), but no bound port collides.
2. Findings 1 and 2 (approved after Stage B evidence): an oversized payload fails with a kind in
   {ARGUMENTS_REJECTED, CONNECTION_LOST} (size dependent), not ARGUMENTS_REJECTED alone; a forged String gives
   METHOD_THREW ("argument type mismatch", refused by RMI's dispatch, not the filter); every refusal is proved by the
   barrier.
3. Q-C (approved): the test-only helper `CollectiveRmiFixture`, not in the plan's file list.
4. `RmiTestSupport.cluster()` stayed package-private: the mpi tests need their own never-bound bases (28505-28545).
5. `RmiModule` has three constructors (the unchanged 6-argument one, one taking the extras list, and the `@Autowired`
   one taking `ObjectProvider<RmiExtraExport>`).
6. 39 new tests rather than about 37 (`RmiCollectiveTransportTest` also covers the method's own exception and the
   root-agnostic call).

### E9c interfaces for E9d — MPI module and API (`/api/modules/mpi`)

**`MpiModule`** (`@Component`, id `mpi`, lab 9, title "MPI Collectives"). Every cluster node takes part; nothing in the
request selects participants.
- **RMI.** Experiment 1 owns the services. Every action first calls `RmiModule.ensureStarted()` (no rmi guard). Reads
  never start anything. The transport is `RmiCollectiveTransport.of(rmiModule)`.
- **Who is in the collective.**
  - A node crashed at the start is not a rank: it is listed in `skippedNodeIds`.
  - A live node whose RMI is not running (its start failed) stays a rank and fails at LOOKUP, as `CONNECTION_REFUSED`
    on Windows.
  - A node that crashes during the run is a FAILED transfer, and the collective is PARTIAL.
- **Root rule** (`MpiRoleSelector`, module-local, `// TODO(L1)`):
  1. the requested `rootNode` (**REQUESTED**);
  2. otherwise the live leader, read-only from `Cluster.leaderId()` (**LEADER**, link L1);
  3. otherwise the lowest live node id (**LOWEST_LIVE_ID**).

  The response says which rule chose the root (`rootSource`). No election or role code was built. **Rohan should
  agree in the pull request:** HANDOFF 6.4 schedules the MPI-root part of L1 for Phase 13, and README section 6 says a
  module-local lowest-id selector until then. This step reads the leader early (Q1, approved by Jai).
- **Concurrency.** Start and collective each hold the module's `ModuleActionGuard` for their whole duration, on the
  request thread. It is released by try-with-resources. A second request gets 409; it is never queued, and it
  publishes `ACTION_REJECTED_BUSY`. GET requests never take the guard. The mpi and rmi guards are independent, so an
  Experiment 1 call can run during a collective.
- **History.** At most `history-size` (20) entries, newest first. Ids start at 1 and restart after a reset; the id is
  also the collective id sent to the nodes. If the executor throws unexpectedly, the reserved id is given back
  (`MpiHistory.cancel`), so no id stays reserved without an entry. **The history holds no vectors**, only checksums.
- **Status.** BUSY while the guard is held; otherwise RUNNING while any node exports `CollectiveEndpoint`; otherwise
  IDLE.
- **Reset** (called by the cluster reset): forgets the history only. It never throws, never takes the guard and
  publishes nothing. `RmiModule.reset()` stops RMI.

**Endpoints.** Every error is a ProblemDetail. Examples are in `frontend/src/test/fixtures/mpi/` (see its README).

| Method and path | Body | Answer |
|---|---|---|
| `GET /api/modules/mpi` | | 200 `MpiOverviewDto` (no remote call). `overview-before-start.json`, `overview-after-start.json`, `overview-node-crashed.json` |
| `POST /start` | | 200 `MpiOverviewDto`; idempotent; 409 busy. `start.json`, `start-with-failure.json` |
| `POST /collectives` | `{operation, count, seed?, rootNode?}` | 200 `CollectiveDto` for COMPLETE, PARTIAL, FAILED **and** REFUSED. `collective-broadcast.json`, `collective-scatter.json`, `collective-gather.json`, `collective-requested-root.json`, `collective-node-crashed-skipped.json`, `collective-partial.json`, `collective-refused.json` |
| `GET /collectives` | | 200 `CollectiveDto[]`, newest first. `collectives-history.json` |
| `GET /collectives/latest` | | 200; 404 "Unknown collective" with `collectiveId: null` when none. `collective-latest.json`, `error-404-no-collective-yet.json` |
| `GET /collectives/{id}` | | 200; 404 "Unknown collective" (`collectiveId`). `collective-by-id.json`, `error-404-unknown-collective.json` |

**Errors.**
- **400 "Invalid request parameters"**, every field at once under `errors`:
  - "operation is required"
  - "count is required"
  - "count must be 1-M, was N" (M = `max-payload-elements`)
  - "seed must be 0-9007199254740991, was N"

  Fixtures: `error-400-missing-fields.json`, `-count`, `-seed`. Spring's default 400 (no `errors`) for an unknown
  `operation` or a non-numeric id: `error-400-unreadable.json`, `error-400-bad-id.json`. No field is
  operation-specific, so there is no "field not taken" case (Q5).
- **200 REFUSED, not 400**, for scatter or gather with fewer elements than live nodes (`COUNT_BELOW_PARTICIPANTS`).
  The same request succeeds after a recover. It is kept in the history, publishes `COLLECTIVE_REFUSED` and is counted
  in the metric (Q2).
- **404 "Unknown node"**: a `rootNode` that does not exist (`error-404-unknown-node.json`).
- **409 "Node down"** (`nodeId`): the requested root is crashed (`error-409-root-down.json`).
- **409 "Node state conflict"** (`nodeId: 0`): no root was requested and every node is crashed
  (`error-409-no-live-node.json`).
- **409 "Module busy"** (`moduleId`, `actionInProgress`, for example "BROADCAST of 100000 values"):
  `error-409-busy.json`, captured live.

**Seed.** An omitted seed becomes the collective id (Q6). A seed is at most 2^53 - 1, so a JavaScript number holds it
exactly.

**DTOs** (`dto/`). An unknown or inapplicable value is null, never 0.
- `MpiOverviewDto`: status, actionInProgress, boundName, operations, limits, participants, defaultRootNodeId,
  defaultRootSource, latencyBasis (= `RmiCollectiveTransport.LATENCY_BASIS`), payloadSizeBasis (= `PayloadSizer.LABEL`),
  algorithmNote, coHosting (the only Simulated badge), latest.
- `MpiNodeDto`: nodeId, nodeStatus, rmiState (`RmiNodeState`), registryPort, startFailure, collectiveEndpointExported,
  leader, storedCollectiveIds (from E9b's `CollectiveEndpointLogic.storedIds()`; **null while the endpoint is not
  exported**, `[]` when exported and empty).
- `OperationDto`: operation, description, minimumCount (1 for BROADCAST; the live node count for SCATTER and GATHER;
  null when every node is crashed).
- `MpiLimitsDto`: maxPayloadElements, collectiveDeadlineMillis, historySize, maxSeed, connectTimeoutMillis,
  callTimeoutMillis, worstCaseTransferMillis, recommendedMinClientTimeoutMillis.
- `CollectiveDto` (also the history entry):
  - identity: id, startedAt (display only), operation
  - root and ranks: rootNodeId, rootSource, rootRank, rankNodeIds, skippedNodeIds
  - input: count, seed
  - result: outcome, refusal, inputChecksum, totalNanos, deadlineMillis
  - parts: transfers, gathered, verification
  - bases: payloadSizeBasis (set exactly when a transfer has a size), latencyBasis (set exactly when a transfer has a
    latency)
- `TransferDto`:
  - placement: rank, fromNodeId, toNodeId, direction, status, offset, elementCount
  - times: latencyNanos (DELIVERED only), timeToFailureNanos (FAILED only)
  - sizes: requestBytes, replyBytes
  - checks: expectedChecksum, reportedChecksum, verified
  - failure: failureStage, failureKind, failureDescription, failureClass, failureMessage, failureCauseClass
  - Lamport: senderLamportAtSend, replyLamport, lamportAfterReply
  - notAttemptedReason

  The root's LOCAL part has no number at all. A FAILED part at LOOKUP has only `timeToFailureNanos`.
- `GatheredBlockDto`: rank, nodeId, offset, length, checksum, hole. **No values on the wire.**
- `VerificationDto`: passed, checked, matched, mismatched, unknown, wholeVectorMatches.
- `RefusalDto`: reason, message (E9a's text).
- `RootSource`: REQUESTED, LEADER, LOWEST_LIVE_ID.

**HTTP budget (for E9d's API file).** Both figures are **bounds derived from the configured timeouts, not
measurements** (Q3):
- `worstCaseTransferMillis` = 3 x (`udcf.rmi.connect-timeout-millis` + `udcf.rmi.call-timeout-millis`). The three
  exchanges are the registry lookup, RMI's DGC dirty call and the method call: 12000 locally.
- `recommendedMinClientTimeoutMillis` = `collective-deadline-millis` + `worstCaseTransferMillis`, exactly: 22000
  locally.

E9d adds its own margin (for example 25000 ms for `POST /collectives`) and reads the figure from the overview. The
bound leaves out CPU time (known issues).

**Events** (module `mpi`). No event ticks a clock; each carries the Lamport time of the step it reports. Messages are
single-line, and operation and outcome names in them are lower case.

| Type | Node, peer | Lamport time | Data |
|---|---|---|---|
| `COLLECTIVE_ENDPOINT_EXPORTED` | the node a start exported | its current clock | registryPort, boundName |
| `COLLECTIVE_STARTED` | root | root's clock before the first transfer | collectiveId, operation, count, seed, rootSource, liveNodeIds |
| `COLLECTIVE_FINISHED` | root | root's clock after the run | collectiveId, operation, outcome, delivered, failed, notAttempted, totalNanos?, passed? |
| `COLLECTIVE_REFUSED` | root | root's clock | collectiveId, operation, count, reason |
| `ACTION_REJECTED_BUSY` | 0 | the cluster clock | requested, actionInProgress |

No event carries `latencyMillis`, so link L5 never reads a collective as one round trip. There is no per-transfer
event (Q4).

**Metrics.** `node_id` is always the **root** (it makes every call), and the called node is the `target_node` tag:

| Meter | Tags |
|---|---|
| `distributed_mpi_collectives_total` | node_id, operation, outcome (REFUSED included) |
| `distributed_mpi_messages_total` (existing HANDOFF name) | node_id, target_node, operation, direction, status (DELIVERED, FAILED, NOT_ATTEMPTED; never LOCAL) |
| `distributed_mpi_transfer_latency` (timer) | node_id, target_node, operation, direction; DELIVERED only |
| `distributed_mpi_collective_duration` (timer) | node_id, operation, outcome; only when `totalNanos` is set |

**Test ports (E9c): 28660-28699.** Exact binds:

| Class | Binds | Notes |
|---|---|---|
| `MpiControllerTest` (Spring) | TCP 28661-28665 (`rmi-base=28660`), UDP 28671-28675 (`clock-base=28670`) | the clock service is started by the Experiment 3 module when the context starts (known issues); the test checks that only `clock` and `rmi` run |
| `MpiEndToEndTest` (Spring) | TCP 28676-28680 (`rmi-base=28675`), UDP 28686-28690 (`clock-base=28685`) | no blocker socket any more |
| `MpiNeverStartedNodeTest` (Spring) | TCP 28695, 28696, 28698, 28699 (`rmi-base=28694`; RMI never binds node 3's 28697), plain `java.net.Socket` blocker on 28697 (bound, never listening, no socket option set, closed in `finally`), UDP 28666-28670 (`clock-base=28665`) | one test; RMI never accepts a connection on 28697, so no TIME_WAIT is left there |
| `MpiCrashDuringCollectiveTest` | TCP 28691-28693 (RMI base 28690) | no Spring; other bases (production values) never bound |
| `MpiModuleTest`, `MpiRoleSelectorTest`, `MpiHistoryTest`, `MpiDtoMapperTest`, `MpiMetricsTest` | none | `MpiModuleTest`'s cluster names RMI base 28695 (nodes 28696-28698) but binds nothing (Mockito `RmiModule`) |

No port that any class binds is bound by another class, and nothing is outside 28660-28699. One numeric overlap is
never bound: `MpiModuleTest`'s nominal ports 28696-28698 fall inside `MpiNeverStartedNodeTest`'s 28695-28699 (left
unchanged; that test may not be changed in the fix). The other bases of the three Spring contexts (election 7000,
replication 7100, requests 7200, mapreduce 7300) are never bound (checked by `onlyRmiStarts`). After the full suite no
TCP or UDP socket was left on 28660-28699. The Spring classes stop RMI, check the ports free in `@AfterAll`, and close
their context.

**Observed outcome matrix** (Windows 11, JDK 21.0.12.1; logged as `E9c outcome matrix | ...`):

| Case | Outcome | Failed part | Proved by |
|---|---|---|---|
| scatter of 2 over 3 live nodes (unit), of 4 over 5 (controller), of 3 over 4 (fixture) | REFUSED, `COUNT_BELOW_PARTICIPANTS`, no transfers | none | MpiModuleTest, MpiControllerTest, `collective-refused.json` |
| live node whose RMI never started (port occupied by a bound, non-listening socket) | PARTIAL | LOOKUP, `CONNECTION_REFUSED`, `java.rmi.ConnectException <- java.net.ConnectException`, 630100 to 1397700 ns (10 runs of the test), 21944300 ns (fixture) | MpiNeverStartedNodeTest, `collective-partial.json` |
| node 3 crashed when the root starts node 2's transfer, BROADCAST / SCATTER / GATHER | PARTIAL, node 3 not skipped | LOOKUP, `CONNECTION_REFUSED`, `java.rmi.ConnectException <- java.net.ConnectException`, 1212200 / 24689000 / 423600 ns | MpiCrashDuringCollectiveTest (asserts {CONNECTION_REFUSED, CONNECTION_LOST}) |
| node crashed before the collective | COMPLETE, `skippedNodeIds: [3]` | none | MpiEndToEndTest, `collective-node-crashed-skipped.json` |

**Tests.** 63 new tests in 9 classes:
- `MpiRoleSelectorTest` 5
- `MpiHistoryTest` 5
- `MpiDtoMapperTest` 5
- `MpiMetricsTest` 6
- `MpiModuleTest` 21 (one parameterised over the three operations)
- `MpiControllerTest` 13
- `MpiEndToEndTest` 4
- `MpiNeverStartedNodeTest` 1
- `MpiCrashDuringCollectiveTest` 3 (parameterised)

Plus the test-only helper `MpiTestResults`: real `CollectiveResult`s from E9a's executor over `ScriptedCollectiveTransport`.

No existing test changed and no line was added to `CoreContextTest` or `ModuleControllerTest`: both find modules by
id, and `MpiControllerTest.listedAsLab9` proves the registration. The only existing source changed is `MetricNames`
(add-only). No E9a or E9b class changed. No YAML change: every `udcf.mpi` key already existed.

The new classes ran 5 times plus once in reverse alphabetical order (sequentially): 63 tests, 0 failures each time.
Full suite 1623 tests, 0 failures, 1 skipped (baseline 1560). Scans of mpi main and test: no `setReuseAddress`,
`ServerSocket` or `Thread.sleep`; no non-ASCII character (sources and fixtures); no `\u000a` or `\u000d`.

Linux (Docker, --cpus=2), run by Jai: 417 mpi and rmi tests x 5 runs plus 1 reverse-order run, 0 failures; full suite 1623 tests, 0 failures, 1 skipped; clean-clone full suite 1623 tests, 0 failures, 1 skipped.

#### E9c deviations from the approved plan

1. Test ports: the plan's 28660/28670/28680 RMI bases became 28660/28675/28690, and the two Spring classes also set
   `clock-base` (28670, 28685). The Experiment 3 module starts its UDP clock service on every node when a Spring
   context starts, so without this the Spring tests bound the production UDP ports 6001-6005 (found by the
   `onlyRmiStarts` check; condition 6).
2. An extra test-only helper, `MpiTestResults`, and an extra fixture, `overview-node-crashed.json`.
3. The history entry is `CollectiveDto` itself (no separate record), as stated in the plan.
4. `COLLECTIVE_STARTED` is published before the run, so a refused collective publishes STARTED then REFUSED (one
   collective, two events), and FINISHED is never published for it.
5. 63 tests rather than the plan's estimate (none was given per class).
6. CI on Linux exposed an order/timing dependent bind in neverStartedNode; the test now has its own class and ports.
   It moved from `MpiEndToEndTest` to `MpiNeverStartedNodeTest` (rmi-base 28694, clock-base 28665). Its last step,
   which released the blocker and ran a COMPLETE broadcast so that RMI started and accepted connections on node 3's
   port, was dropped. That step would leave TIME_WAIT connections on the blocked port, and a later run within the
   TIME_WAIT period could then fail the same way on Linux.

### E9d — MPI page (`/experiments/9-mpi`, `frontend/src/modules/mpi/`)

Nothing another track needs to call. Frontend only; the backend is unchanged since E9c.

**Page.** `index.jsx` exports `{ id: 'mpi', Page: MpiPage }`, so the existing `experiments/:slug` route renders it; no
router change and no lazy loading (`registry.js` loads every page eagerly, README section 7). `MpiPage` passes
`howItWorks` (three paragraphs plus the overview's `algorithmNote`, verbatim), `controls`, `visualisation`,
`measurements` and `whatToNotice` to the shared `ExperimentLayout`, which adds the module event log (`ModuleEventLog`,
`moduleId="mpi"`: every COLLECTIVE_* event and `ACTION_REJECTED_BUSY`; a refused collective shows STARTED then REFUSED).
- **Controls** (`MpiControls`): operation (with the overview's description), element count (1 to
  `limits.maxPayloadElements`; the hint gives the operation's `minimumCount`, n/a when null), optional seed (0 to
  `limits.maxSeed`; empty means the backend uses the collective id), optional root (the first option names the
  default from `defaultRootNodeId` and `defaultRootSource` in plain words, with the Phase 13 note; a crashed node stays
  selectable, marked "(down)", so the real 409 shows). Count and seed are checked with `Number.isSafeInteger` in the
  backend's own words; **a count below `minimumCount` is sent** (the backend's 200 REFUSED is the demonstration). Crash
  and recover through the shared `api.crashNode` / `api.recoverNode`, as on the Experiment 1 page.
- **Visualisation:** `CollectiveMap` (the bold element; the root in the centre, an arrow per remote transfer, outward
  for broadcast and scatter, inward for gather; delivered solid, failed dashed with its kind, not attempted dotted;
  skipped nodes marked with no arrow; arrows fade in in rank order, not under reduced motion; the page's only
  Simulated badge, rendered only when `overview.coHosting` is non-null) and `NodeStrip` (status, RMI state, registry
  port, CollectiveEndpoint exported, leader marker, kept collective ids, start failure).
- **Measurements:** a MetricCard strip (last total time, remote transfers delivered, checksum verification), the
  total-time explanation, `LatencyChart` (one slot per rank: a bar for DELIVERED only, a cross for FAILED, a dashed
  outline for NOT_ATTEMPTED, a dot for the root's LOCAL part; never a zero bar; the time to failure only in words,
  marked "not a latency"; the collective's `latencyBasis` under it) and `HistoryTable` (newest first, rows keyed by id
  plus `startedAt`; a row shows the list entry itself in a detail panel, no further request).
- **Results** (`CollectiveResult`): outcome badge (icon plus word), refusal reason and message, root and rank, seed,
  input checksum, total time with its explanation, deadline, rank table (root, participant, skipped), transfer table
  (direction, status, offset and element count, latency, time to failure, request and reply bytes, expected and
  reported checksum with verified / mismatch / not checked, the three Lamport values; a FAILED row has a sub-row with
  stage, kind, description, exception and cause class and message; a NOT_ATTEMPTED row a sub-row with its reason),
  `latencyBasis` and `payloadSizeBasis` under the table whenever latencies or sizes are shown, verification, gathered
  blocks (checksums and holes; no values exist on the wire).

**Start and reset (`useMpi`, adapted from `useRmi` without registries).** `POST /start` once on mount, then only on the
edge "no up node lacks CollectiveEndpoint" to "some up node lacks it" (`needsStart`: `nodeStatus === 'UP' &&
collectiveEndpointExported === false`). `start-with-failure` keeps the condition true, so there is no loop; the page
offers "Try again". An overview read sent before a start answered is discarded and read again. Overview and history
are read together on every module event, cluster event and reconnect, coalesced (one in flight, one queued); no
polling.

**Timeouts.** `POST /collectives` waits `collectiveTimeoutMs(overview)` =
`limits.recommendedMinClientTimeoutMillis` + `COLLECTIVE_TIMEOUT_MARGIN_MS` (3000), so 25000 ms locally; before the overview has loaded,
`COLLECTIVE_TIMEOUT_FALLBACK_MS` (25000). Every other request keeps the shared 10000 ms. The help text under the form
takes every figure from the overview (deadline, worst-case transfer, connect, call, the bound); the only page literal
is the margin constant.

**Errors.** Experiment 1's `describeProblem`, re-exported: 400 with `errors` (also beside the fields), 400 without
`errors`, 404, 409 "Module busy" (with `actionInProgress`), 409 "Node down", 409 "Node state conflict", client timeout,
network error. Run is disabled only while this page's own request is in flight; a BUSY overview (another tab) shows a
banner and leaves Run enabled.

**Null handling as implemented** (a null is "n/a" with its reason as tooltip and screen-reader text, never 0):

| Field | Where | Shown for null |
|---|---|---|
| `storedCollectiveIds` | NodeStrip | n/a, "The endpoint is not exported, so it keeps nothing"; `[]` is "none" |
| `startFailure` | NodeStrip | no sub-row |
| `minimumCount` | count hint | n/a, "Every node is down, so there is no live participant" |
| `defaultRootNodeId` | root select | "Default: none, every node is down" |
| `latencyNanos` | transfer table, chart | LOCAL "The root's own part never crosses the network"; FAILED "No latency: the transfer did not complete"; NOT_ATTEMPTED "Not attempted: the deadline had passed"; chart: no bar |
| `timeToFailureNanos` | own column; chart's words | n/a "The transfer did not fail"; never in the latency column |
| `requestBytes` / `replyBytes` | transfer table | LOCAL as above; FAILED at LOOKUP "Nothing was sent: the registry lookup failed" / "No reply came back" |
| `reportedChecksum` / `verified` | transfer table | n/a with the reason / "not checked" |
| Lamport values | transfer table | n/a (local part, nothing sent, no reply) |
| `failureMessage` | failure sub-row | n/a "The exception had no message" |
| `notAttemptedReason` | not-attempted sub-row | n/a "No reason was sent" |
| `rootRank` | result | n/a "Refused before ranks were used" |
| `inputChecksum` | result | n/a "Refused: no input was generated" |
| `totalNanos` | result, MetricCard, history | n/a "Refused: nothing was sent" or "No remote transfer was attempted"; the explanation is hidden with it |
| `verification` | result | n/a and "Nothing was sent, so nothing was checked" |
| `passed` / `wholeVectorMatches` | verification | n/a (unknown, or not compared), never "passed" |
| `latencyBasis` / `payloadSizeBasis` | under the transfer table and chart | not shown (they are null exactly when no such figure exists) |
| gathered `checksum` (hole) | gathered blocks | "hole: nothing arrived" |

**Synthetic test states** (Q3, approved): minimal copies of real fixtures made inside the test, every changed field
commented, "synthetic" in the test name; `contract.test.js` uses real fixtures only.
1. A NOT_ATTEMPTED transfer (from `collective-partial.json`, rank 4): `mpiModel`, `CollectiveResult`, `CollectiveMap`,
   `LatencyChart`.
2. A checksum mismatch (from `collective-broadcast.json`): `CollectiveResult`.
3. Outcome FAILED and a gathered hole (from `collective-gather.json`): `CollectiveResult`.
4. A leader (`leader: true`; and `defaultRootSource: LEADER` with `defaultRootNodeId: 2`): `CollectiveMap`,
   `NodeStrip`, `MpiPage`.
5. `minimumCount: null` and `defaultRootNodeId: null`: `MpiPage`.
6. A BUSY overview (`status`, `actionInProgress` from `error-409-busy.json`): `MpiPage`.
7. Other limits for the timeout help text and for `collectiveTimeoutMs`, a smaller `maxSeed`, and `coHosting: null`:
   `MpiPage`, `mpiApi`, `mpiModel`, `CollectiveMap`.

**Files.** Created in `frontend/src/modules/mpi/`: `index.jsx`, `mpiApi.js`, `labels.js`, `mpiModel.js`, `useMpi.js`,
`MpiPage.jsx`, `MpiControls.jsx`, `CollectiveResult.jsx`, `CollectiveMap.jsx`, `NodeStrip.jsx`, `LatencyChart.jsx`,
`HistoryTable.jsx`, and 11 test files. Shared, add-only (approved): the lab 9 `concept` in `frontend/src/lib/experiments.js`;
one import and three `mpiApi` spies in `frontend/src/routes.test.jsx`; one line in
`frontend/src/test/fixtures/rmi/README.md` (the registries fixtures predate E9b). Reused: `describeProblem`,
the number formats and labels and `NotAvailable` from rmi; `usePoliteAnnouncement` and `moduleStatusLabel` from
multithreading.

**Tests.** 131 new tests in 11 files (mpiApi 10, labels 4, mpiModel 18, useMpi 10, CollectiveResult 12, CollectiveMap 8,
NodeStrip 5, LatencyChart 4, HistoryTable 3, MpiPage 28, contract 29); frontend 785 -> 916 tests, 96 -> 107 files.
Main chunk 690.50 kB -> 738.64 kB (gzip 208.27 -> 220.77 kB). Whole suite under `--sequence.shuffle`: the final run
passed (seed 1791907336687); some earlier shuffled runs had one failure, and every one whose failing test was listed
was Track A's `useMultithreading` test (Known issues). The mpi and routes files passed under 5 shuffle seeds.

**For Phase 13.** The default root already reads the leader (`defaultRootSource: LEADER`), but only synthetic tests
cover it (no capture has a leader). When L1 makes the leader the MPI root, change the root hint in `MpiControls`
("The leader becomes the MPI root fully in Phase 13") and recapture an overview with a leader.

**For Experiment 10.** If E10c returns `CollectiveDto`s for its scatter of A, broadcast of B and gather of C, E10d can
render them with `CollectiveResult`, `OutcomeBadge`, `LatencyChart` + `chartModel` and `CollectiveMap` + `fanModel`
as they are (exported from this folder), with the same null rules.

#### E9d deviations from the approved plan

1. Crash and recover (Q1): the block is written in `MpiControls` with the same JSX pattern and the shared
   `api.crashNode` / `api.recoverNode`, not extracted from `RmiControls`: extracting it would change an rmi file, which
   condition 5 excludes. The crash logic itself is the shared api; only about 20 lines of markup repeat.
2. `experiments.js`: the one-line lab 9 entry became a seven-line object like lab 1's; the only content added is the
   `concept` key.
3. The synthetic states listed above (Q3, approved).
4. 131 new tests rather than about 90 to 110.

### E10a interfaces for E10b/E10c — matrix model (`com.udcf.modules.matrix`)

Pure classes: no socket, registry, thread, executor, controller or Spring (except `MatrixProperties`). Time comes
only from an injected nanosecond clock. Reused from Experiment 9 unchanged: `PayloadGenerator.vector`,
`VectorChecksum.of`, `BlockPartition.of`, `MpiProperties.maxPayloadElements` and `CollectiveOutcome`; from
Experiment 1: `CallTimer` (and `ScriptedNanoClock` in tests). No existing class changed.

**Data.** Square N x N, row-major `double[]` (element (i, j) at `i * n + j`). `MatrixGenerator.a(seed, n)` =
`PayloadGenerator.vector(seedA(seed), n * n)`, `b(seed, n)` the same with `seedB(seed)`; `seedA = seed ^
0xA5A5A5A5A5A5A5A5`, `seedB = seed ^ 0x5A5A5A5A5A5A5A5A` (XOR is a bijection and the salts differ, so the two seeds
differ for every seed). Values in [-1000, 1000), so every element of C is below `n * 1e6` (at most about 4.6e10): no
NaN or infinity. An Experiment 9 collective given `seedB(seed)` and count `n * n` regenerates exactly B.

**Sizes** (`MatrixLimits(MatrixProperties, MpiProperties)`). The maximum is derived, never configured:
`floorSqrt(max-payload-elements)` in exact integer arithmetic (316 locally, 100 public), because B (N x N) is the
largest single vector sent. The constructor refuses a series size above it or a min-size above it.
`check(n, liveParticipants)` gives an `Optional<MatrixRefusal>`, tested in this order: `SIZE_ABOVE_MAXIMUM`,
`SIZE_BELOW_MINIMUM`, `NO_LIVE_PARTICIPANT`, `SIZE_BELOW_PARTICIPANTS` (n < p).

| Key (`MatrixProperties`, `udcf.matrix`, no code defaults) | application.yml | application-public.yml |
|---|---|---|
| `min-size` | 2 | (inherited) 2 |
| `series-sizes` (at least 3, strictly ascending, each >= min-size; within the maximum, checked by `MatrixLimits`) | [50, 100, 200, 316] | [25, 50, 100] |

**Partition.** `RowPartition.of(n, p)` is `BlockPartition.of(n, p)` applied to rows: `RowBlock(rank, rowStart,
rowCount, n)` with `rowEnd()`, `elementOffset()` = `rowStart * n` and `elementLength()` = `rowCount * n`; n < p is
refused. Example: n = 10 over p = 4 gives rows 3, 3, 2, 2, element offsets 0, 30, 60, 80.
`RowPartition.rows(matrix, block)` copies the block's rows.

**Multiply and exactness.** `MatrixMultiply.multiplyRows(aRows, b, n)`; `multiply(a, b, n)` is the same method over
all rows, so the sequential product and every block run one code path. Each C[i][j] is `s = +0.0; for k = 0..n-1:
s = s + a[i][k] * b[k][j]` (loops in i-k-j order, which does not change the order of additions into an element); no
`Math.fma`, no reordering, no reduction across ranks. Since Java 17 (JEP 306, JLS 15.4) all floating point is strict
IEEE 754 binary64, round-to-nearest, never fused or widened, so the same operand bits in the same order give the same
result bits on every JVM, thread and node. `ParallelProductTest` proves bitwise equality for N in {1, 2, 3, 7, 16,
50, 101}, every p from 1 to 7 (p <= N) and three seeds, against an independent i-j-k reference too, and shows that a
k-descending order does change bits (the check is not vacuous). `timedRows(nanoClock, aRows, b, n)` returns
`Timed(values, nanos)`, measured with `CallTimer` around the multiply only (arguments checked before the clock is
read).

**Assembly and verification.** `ResultBlock(RowBlock block, int nodeId, double[] values, String reportedChecksum)`:
a hole has both null, never zeros; values copied in and out. `MatrixAssembly.assemble(n, blocks, rootRank,
sequential)` (blocks in rank order, tiling rows [0, n), else `IllegalArgumentException`) returns `Assembled(product,
verification, outcome)`; `product` is null while any block is missing.
`MatrixVerification(rowsExpected, rowsReceived, blocksMissing, blocksWithBadChecksum, elementsCompared,
mismatchedElements, parallelChecksum, sequentialChecksum, checksumsMatch, passed)`:
- mismatches are counted bitwise (`doubleToLongBits`) over the arrived rows only; a bad checksum is a reported
  checksum that differs from the root's recomputation over what arrived;
- `parallelChecksum` and `checksumsMatch` are null while a block is missing;
- `passed`: false on any known mismatch (element, bad checksum, checksums differ), otherwise null while a block is
  missing, otherwise true. A missing block is never "passed".

**Outcomes** (as Experiment 9; REFUSED comes from `MatrixLimits.check`, never from assembly): COMPLETE when no block
is missing (also a root-only run); FAILED when no block arrived at all, or a remote rank was needed and no remote
block arrived; PARTIAL otherwise, including only the root's own block missing. A failed compute or transfer is a hole,
so it is data, not an exception.

**Timing** (`MatrixTiming(n, warmupProducts, sequentialNanos, scatterNanos, broadcastNanos, computePhaseNanos,
gatherNanos, parallelTotalNanos, rankComputes)`; every time nullable, null = unknown, never 0, never negative).
`warmupProducts` (int, >= 0, never null) counts the untimed products done before the timed ones.

| Figure | Kind | Definition |
|---|---|---|
| `sequentialNanos` | measured on the root | around the multiply of the whole matrix only, run separately from the parallel run |
| `scatterNanos`, `broadcastNanos` | measured on the root | Experiment 9's `totalNanos` of that collective; **includes the root's payload sizing and checksum work between transfers**, not only time on the wire |
| `computePhaseNanos` | measured on the root | just before the first compute is dispatched to just after the last block (remote or the root's own) is computed; the computation time |
| `gatherNanos` | measured on the root | first fetch of C to the last |
| `parallelTotalNanos` | measured on the root | just before the scatter to just after the last gathered block; one interval, not a sum |
| `RankCompute(rank, nodeId, rowCount, computeNanos)` | **measured on the node** | around that rank's multiply only (all nodes share one process and one `System.nanoTime`); null when the compute failed |
| `communicationNanos()` | derived | scatter + broadcast + gather; null if any is null |
| `otherNanos()` | derived | total - communication - compute phase; **can be negative, never clamped** |
| `communicationShare()` | derived | communication / total |
| `speedup()` | derived | sequential / parallel total, the measured speedup; null if either is null or the total is 0 |
| `computeBoundSpeedup()` | derived, **never the measured speedup** | sequential / slowest rank compute: what the multiply alone would allow if communication cost nothing; null if any rank is null |
| `multiplyAdds()` | derived, exact | n cubed (316 gives 31554496) |

**Series and honesty.** `SpeedupSeries(points)`: `Point(n, sequentialNanos, parallelTotalNanos, communicationNanos,
computePhaseNanos, speedup)` from `Point.of(MatrixTiming)`, sorted by n, a repeated n refused, `sizesWithSpeedup()`
counts the known speedups (done when needs at least three). `SpeedupBasis(IntSupplier availableProcessors)`
(production: `Runtime.getRuntime()::availableProcessors`): `processors()`, `ceiling(ranks)` = min(ranks,
processors), `text(ranks, warmupProducts)`: all nodes share one process and the **available processors reported by
the JVM** (never "physical cores"; can be lower inside a container); the speedup cannot exceed the ceiling;
communication dominates small sizes (speedup below 1 possible); node compute times are measured on the node; and
either "no warm-up products were run, so the figures include JVM compile effects" or the warm-up count.

**For E10b (decided at the E10a review).**
1. (a) Row-aligned scatter: an add-only block unit in mpi (`BlockPartition.of(n, p, unit)`, `of(n, p)` = unit 1; a
   `blockUnit` component in `CollectiveRequest` with the old constructor kept as unit 1; scatter and gather partition
   by unit; the count check becomes n / unit < p). Default 1, so every E9 test is unchanged. A test proves
   `BlockPartition.of(n * n, p, n)` equals `RowPartition`'s element ranges.
2. (b) Gather of the computed block: a separate `MatrixEndpoint` (matrix package) exported through a new
   `RmiExtraExport` bean, filter `RmiSerialFilter.from(limits)` (no array argument). A third bound name on every
   node: `RmiControllerTest`'s two-name assertion (lines 287-293) changes in E10b, and the rmi registry fixtures go
   stale (README note).
3. (c) The block multiply runs on the node's own Experiment 2 executor (`RequestsNodeService.execute(Callable)`).
4. (d) In E10c, `MpiRoleSelector` becomes public (a one-word visibility change) instead of copying the rule.
5. (i) The compute calls are dispatched **concurrently** from the root, one caller per remote rank on a small
   module-owned pool. Otherwise the nodes compute one after another and parallel can never beat sequential, by
   construction.
6. (ii) The Experiment 2 executor only runs the multiply. Any simulated-work multiplier of the capacity profile (FAST
   x1, MEDIUM x2, SLOW x4) must NOT be part of the measured compute (R7).
7. (iii) Warm-up policy: decide how many untimed products run before the timed ones (sequential and blocks) and pass
   the count as `warmupProducts`; with 0 the basis text says the figures include JVM compile effects.
8. (iv) Label the scatter and broadcast times: they are E9 `totalNanos` and include the root's payload sizing and
   checksum work. E10b decides whether to separate that out.
9. `MatrixRunResult` (the record of a whole run) is defined in E10b.

**No dedicated test (R6):** the enum `MatrixRefusal.Reason` (no behaviour). Every record with rules is covered by
`MatrixRecordsTest` or its class's test.

**Tests:** 81 new tests in 11 classes, no socket, thread or port (`MatrixLimitsTest` 21, `MatrixGeneratorTest` 9,
`MatrixMultiplyTest` 8, `ParallelProductTest` 8, `MatrixAssemblyTest` 7, `MatrixTimingTest` 7, `RowPartitionTest` 6,
`MatrixPropertiesTest` 4, `MatrixRecordsTest` 4, `SpeedupBasisTest` 4, `SpeedupSeriesTest` 3).

Linux (Docker, --cpus=2), run by Jai: 81 matrix tests x 5 runs plus 1 reverse-order run, 0 failures; full suite 1704 tests, 0 failures, 1 skipped; clean-clone full suite 1704 tests, 0 failures, 1 skipped.

#### E10a deviations from the approved plan

1. `SpeedupBasis.cores()` is named `processors()` and `text` takes `(ranks, warmupProducts)` (conditions 1 and 2).
2. `MatrixTiming` has `warmupProducts` (condition 1).
3. Outcome: a root-only run whose own block is missing is FAILED (nothing arrived), not PARTIAL; the plan's rule
   did not cover that case.
4. `MatrixVerification.of(...)` derives `checksumsMatch` and `passed`, and `ResultBlock.hole(block, nodeId)` builds a
   hole; small helpers beyond the listed signatures.
5. 81 tests (the plan gave no total).

### E10b interfaces for E10c — parallel run over RMI (`com.udcf.modules.matrix`, add-only edits in `mpi`)

Backend only: no controller, events, metrics or UI (E10c, E10d). The root scatters row blocks of A and broadcasts B
with Experiment 9's collectives, every rank multiplies its rows on its own node's Experiment 2 executor at the same
time, the root fetches the blocks of C one by one, assembles C and compares it bit for bit with the sequential product
(link L6: RMI -> collectives -> matrix).

**Verified facts (read in the code).**
- Experiment 2 executor (`RequestsNodeService.execute(Callable)`): `requireRunning()` throws `NodeDownException` unless
  `running`, and `running = true` is set only in `open()`, after `bind()` of 127.0.0.1:`ports().requests()`. So the
  executor cannot be used without the node's request listener: the first matrix compute on a node starts its requests
  service through `RequestsNodeService.on` -> `ClusterNode.ensureService` and binds its 720k port (7201-7205 in
  production). On a CRASHED node `ensureService` throws `NodeDownException`; a stopped service is restarted. Executor:
  `NodeExecutorFactory.forNode`, core = max = `NodeCapacity.threads()` (FAST 4, MEDIUM 2, SLOW 1), bounded
  `LinkedBlockingQueue(queue-capacity)` (200 local, 100 public), `AbortPolicy`: a full queue fails the future with
  `RejectedExecutionException` (or `NodeDownException` if the executor is shut down). `execute` runs `task.call()` as
  given: **no work multiplier** (`workMultiplier` goes only to `RequestProcessingService`, Experiment 2's own requests).
  A crash fails every pending `execute` future with `NodeDownException` and interrupts the workers.
- Stored inputs: `CollectiveEndpointLogic.receive` keeps a copy per collective id, at most `udcf.mpi.receive-store-size`
  (8), oldest dropped, a repeated id replaces. Existing read accessors are enough: `received(id)`, `storedRanks()`, via
  `CollectiveEndpointExport.current(node)` (empty while the node's RMI is down). No change to E9's logic. The root's own
  scatter block is LOCAL and never stored, so the root takes its rows from the A it generated.
- **Seeds (condition 1).** `CollectiveRequest` has no seed check; `CollectiveExecutor` only passes the seed to
  `PayloadGenerator.vector(long seed, int count)`, which accepts any long. The derived seeds (`seedA(seed)` and
  `seedB(seed)`, full 64-bit, negative for seed 42 because both salts set the top bit) are therefore accepted. E9's only
  seed limit is `MpiModule.MAX_SEED` = 2^53 - 1 at its HTTP API, which the matrix runner never goes through.
- **Startup check (condition 2).** Grep of `backend/src/test` and both YAML files for `call-timeout-millis`: only
  application.yml sets it (3000); public inherits it. Tests that build `RmiProperties` with a call timeout below 2000
  (300 in `RmiCollectiveTransportTest`, 500 in `RmiFailureCharacterisationTest`, 700 in `MpiModuleTest`) are plain
  unit tests that never load `MatrixEndpointExport`; every Spring context uses application.yml. So the check
  `compute-timeout-millis < call-timeout-millis` lives in `MatrixEndpointExport`'s constructor (startup);
  `MatrixRunPropertiesTest` proves both YAML files satisfy it.
- **Lamport (condition 5).** `LamportClock` is an `AtomicLong`: `tick()` = `incrementAndGet`, `update(t)` =
  `updateAndGet(max(local, t) + 1)`; both atomic, so concurrent pool threads lose no update.
  `ParallelMatrixExecutorTest.concurrentMergesLoseNothing` proves it (5 threads x 2000 ticks and merges after a merge of
  1000000: final value exactly 1000000 + 1 + 20000).
- **Several extras (condition 6).** `RmiNodeService(node, properties, transport, endpoints, extras)` takes a list and
  refuses a duplicate or blank bound name; `RmiNodeService.open()` loops `for (RmiExtraExport extra : extras)` and does
  `UnicastRemoteObject.exportObject(object, port(), factory, factory, extra.filter())` and
  `created.bind(extra.boundName(), extraStub)`: same registry, same port, same factory instance. `RmiModule`'s
  `@Autowired` constructor takes `ObjectProvider<RmiExtraExport>` (`orderedStream().toList()`). Proved by
  `MatrixEndToEndTest.registryListsThreeNames` and `RmiControllerTest` (Spring: CollectiveEndpoint, MatrixEndpoint,
  NodeEndpoint).
- **Call timeout.** Every RMI client socket (lookup and stub) gets `SO_TIMEOUT` = `udcf.rmi.call-timeout-millis` (3000)
  and connect timeout `udcf.rmi.connect-timeout-millis` (1000). The node waits for its multiply at most
  `compute-timeout-millis` (2000, queue wait included) and then answers with its reason, so a slow multiply is
  `COMPUTE_TIMED_OUT`, never a hang and never the client's TIMED_OUT.

**Add-only edits outside the matrix package** (every E9 and E1 test passes unchanged, except `RmiControllerTest`):
`BlockPartition.of(int n, int p, int unit)` added (the old `of(n, p)` untouched); `CollectiveRequest` gains the
component `int blockUnit` (header line changed; a 7-argument constructor keeps unit 1; checks unit >= 1 and count %
unit == 0); `CollectiveExecutor`: the count check is `n / blockUnit < p`, and scatter and gather partition with the unit
(3 changed lines; a broadcast ignores the unit); `MpiModule.EXCHANGES_PER_TRANSFER` is `public`. In the matrix
package, `MatrixRefusal.Reason` gains `ROOT_UNAVAILABLE`.

**Remote object.** `MatrixEndpoint` (bound name `MatrixEndpoint`, a third name on every node's registry):
`ComputeAck computeBlock(CallContext, long runId, int rank, int n, long scatterId, long broadcastId)` and
`BlockReply fetchBlock(CallContext, long runId, int rank)`, both `throws RemoteException, MatrixComputeException`.
Only primitives and `CallContext` are arguments, so the filter is E1b's `RmiSerialFilter.from(limits)` (every array
rejected). `MatrixEndpointLogic`: arguments checked first (an invalid call changes nothing), then `clock.update`, then
the work, then the reply stamped with `clock.tick()`; it reads A and B from the node's collective store, multiplies on
the node's executor, keeps C by run id (at most `result-store-size`, oldest dropped, a repeated run id replaces),
`keptRunIds()` lists them. A new logic on every start and recovery (`MatrixEndpointExport.newEndpoint`), so stop, crash
and recover drop the kept blocks. `MatrixComputeException.Reason`: INPUT_MISSING, INPUT_MISMATCH,
EXECUTOR_UNAVAILABLE, EXECUTOR_REJECTED, COMPUTE_TIMED_OUT, COMPUTE_FAILED, BLOCK_MISSING; RMI carries it back as
the method's own exception (stage CALL, kind METHOD_THREW) and the root reads the reason.

**Executor seam.** `NodeExecutor` (functional: `submit(Callable<Timed>)`); `NodeExecutor.experimentTwo(node,
MultithreadingProperties, MeterRegistry, ClusterEventBus)` is the production one. Its default `multiply(aRows, b, n,
timeoutMillis, nanoClock)` times the multiply inside the task (`MatrixMultiply.timedRows`), so queue wait is excluded,
and maps failures to reasons. **Abandoned tasks (condition 4):** on a timeout or interrupt an `AtomicBoolean` is set
and the future cancelled; a task still queued then returns at once without multiplying (and Experiment 2's runnable
also skips a done future). A task that had already started runs to the end (the multiply ignores interrupts) and its
result is discarded. Proved by `NodeExecutorTest`.

**Transport and runner.** `MatrixTransport` (`compute`, `fetch`, both `throws TransferException` with stage LOOKUP or
CALL); `RmiMatrixTransport` (fresh typed lookup per call, no retry, no new sockets or timeouts; `of(RmiModule)`).
`MatrixDispatchPool(int threads)`: prestarted daemon threads `udcf-matrix-dispatch-<i>`, `submit(Callable)`,
`close()` = `shutdownNow` + wait 5 s (else `IllegalStateException`). `ParallelMatrixExecutor(MatrixLimits,
MatrixRunProperties, MpiProperties, MatrixRunBudget, MatrixDispatchPool, LongSupplier nanoClock)` with
`MatrixRunResult run(MatrixRunRequest, LamportClock rootClock, CollectiveTransport, MatrixTransport, NodeExecutor
rootExecutor)`; it throws `IllegalStateException` only if the pool has fewer threads than ranks (configuration).
`MatrixRunRequest(runId, n, seed, rootNodeId, participantNodeIds, liveNodeIds)`; its collectives use ids
`1_000_000_000 + 2 x runId` (scatter) and `+ 1` (broadcast), never meeting E9's ids, which count from 1 per reset.

**One run.**
1. Refusals (data; nothing generated or sent; the clock not read): `MatrixLimits.check(n, live participants)`, then the
   root must be a live participant (`ROOT_UNAVAILABLE`, with E9's RankAssignment message).
2. Generate A and B (measured: `payloadGenerationNanos`).
3. `warmup-products` untimed full products, then the timed sequential product, on the calling thread.
4. Parallel start (clock read) = start of the run deadline (`udcf.mpi.collective-deadline-millis`).
5. Scatter of A (E9 SCATTER, count n x n, seed `seedA(seed)`, block unit n) and broadcast of B (E9 BROADCAST, count
   n x n, seed `seedB(seed)`), each with the time left of the run deadline, rounded up to whole ms, as its E9 deadline.
6. A rank computes only if its rows of A and B were DELIVERED and verified (the root always). Before dispatch the
   deadline is checked.
7. Compute phase: every compute dispatched at once on the pool (remote: `compute` with a Lamport tick, merge on a
   matching ack; the root: its own executor). The root waits at most `computeWaitMillis`; a compute still running is
   FAILED with an observed TIMED_OUT.
8. Gather: linear in rank order; before each fetch the deadline is checked; the root recomputes the checksum over what
   arrived and compares it with the ack's and with the sequential rows'.
9. Assemble (`MatrixAssembly`), build `MatrixTiming` and `MatrixRunResult`. C itself is not kept.

**Deadline (condition 3):** if no time is left before the broadcast, the compute dispatch or a fetch, that step is not
started and it and every later step are NOT_ATTEMPTED with the reason "The run's deadline of D ms had passed before
this <step>.". A collective that is not started is still reported as an E9 `CollectiveResult` built by the runner (the
public constructor): the root's part LOCAL, every other rank NOT_ATTEMPTED, `totalNanos` null, `Verification.of(0, 0,
p - 1, null)`, outcome FAILED (COMPLETE if the root is the only rank), the run deadline as `deadlineMillis`.

**Timing (extends the E10a table).**

| Figure | Kind | Definition |
|---|---|---|
| `payloadGenerationNanos` (MatrixRunResult) | measured on the root, **informational** | around generating A and B before the timed parts; it approximates the generation that the scatter and the broadcast repeat from the seeds inside the parallel run (that part shows up in `otherNanos`); never subtracted from any figure |
| `sequentialNanos` | measured on the root | after the warm-up, around the multiply only |
| `scatterNanos`, `broadcastNanos` | measured on the root | E9 `totalNanos` (first to last remote transfer), including the root's sizing and checksum work between transfers; no sum of per-transfer latencies is added (the transfers are in the result) |
| `computePhaseNanos` | measured on the root | just before the first dispatch to when the last compute (ack or the root's own) is known |
| `ComputeRecord.roundTripNanos` | measured on the root | around one remote compute call: lookup, call, the node's queue wait and multiply, reply |
| `RankCompute.computeNanos` | measured on the node | around the multiply only (queue wait excluded) |
| gather `TransferRecord.latencyNanos` | measured on the root | around one fetch (lookup and call) |
| `gatherNanos` | measured on the root | just before the first fetch to just after the last; null when no fetch was made |
| `parallelTotalNanos` | measured on the root | parallel start to the end of the last phase that ran |

**Records.** `ComputeAck(nodeId, lamportTime, runId, rank, rowCount, computeNanos, checksum)`; `BlockReply(nodeId,
lamportTime, runId, rank, double[] values)` (copied, array equality); `ComputeRecord(rank, nodeId, rowStart, rowCount,
local, Status status, Long roundTripNanos, Long timeToFailureNanos, Long computeNanos, String checksum,
RemoteFailureDetail failure, MatrixComputeException.Reason nodeReason, String nodeMessage, Long senderLamportAtSend,
Long replyLamport, Long lamportAfterReply, String notAttemptedReason)` with Status DONE, FAILED, NOT_ATTEMPTED (each
field set exactly when it applies; unknown is null). **The two timeouts stay distinguishable (Q7, accepted):** the
node's own (`failure.kind` METHOD_THREW + `nodeReason` COMPUTE_TIMED_OUT) versus the root's wait bound (`failure.kind`
TIMED_OUT, observed, no class, no `nodeReason`). `MatrixRunResult(runId, n, seed, rootNodeId, Integer rootRank,
rankNodeIds, skippedNodeIds, rowBlocks, outcome, MatrixRefusal refusal, deadlineMillis, Long payloadGenerationNanos,
CollectiveResult scatter, CollectiveResult broadcast, List<ComputeRecord> computes, List<TransferRecord> gather,
MatrixTiming timing, MatrixVerification verification, Boolean inputsDelivered)`: for REFUSED everything run-related is
null or empty; otherwise rootRank, payloadGenerationNanos, scatter, broadcast, timing and verification are set and the
lists have one entry per rank. After PARTIAL or FAILED, `MatrixVerification` keeps E10a's rules (rows received and
blocks missing filled; `parallelChecksum` and `checksumsMatch` null; `passed` null, or false on a known mismatch; never
true) (Q1). `inputsDelivered`: false if a check of A or B failed, null if unknown or there is no remote rank.

**Failure matrix** (all data; p = 3, one remote rank affected unless stated; proved in `ParallelMatrixExecutorTest`,
the socket rows also in `RmiMatrixTransportTest` and `MatrixEndToEndTest`).

| Case | Where | Kind / node reason | Hole | Outcome |
|---|---|---|---|---|
| Node down at the live snapshot | not a participant | skipped | no | COMPLETE over the others |
| Never started, or down after the snapshot, before the scatter | scatter, LOOKUP | CONNECTION_REFUSED (or LOST); its compute and fetch NOT_ATTEMPTED | yes | PARTIAL |
| Crash between scatter and compute | compute, LOOKUP | CONNECTION_REFUSED or CONNECTION_LOST (nothing sent) | yes | PARTIAL |
| Crash and recovery between scatter and compute | compute, CALL | METHOD_THREW / INPUT_MISSING (fresh store) | yes | PARTIAL |
| Crash during the compute | compute, CALL | CONNECTION_LOST, or METHOD_THREW / EXECUTOR_UNAVAILABLE | yes | PARTIAL |
| Crash after the compute, before the fetch | gather, LOOKUP | CONNECTION_REFUSED or LOST; the measured compute time is kept | yes | PARTIAL |
| Stored A or B evicted | compute, CALL | METHOD_THREW / INPUT_MISSING | yes | PARTIAL |
| Stored input of another rank or size | compute, CALL | METHOD_THREW / INPUT_MISMATCH | yes | PARTIAL |
| Kept C evicted | gather, CALL | METHOD_THREW / BLOCK_MISSING | yes | PARTIAL |
| Corrupted block (recomputed checksum differs from the ack) | gather | DELIVERED, verified false | no | COMPLETE, passed **false** |
| Executor unavailable / rejecting / too slow | compute, CALL | METHOD_THREW / EXECUTOR_UNAVAILABLE, EXECUTOR_REJECTED, COMPUTE_TIMED_OUT | yes | PARTIAL |
| The root's own executor fails | the root's compute (local) | `nodeReason` set, no `failure` | the root's block | PARTIAL (FAILED if the root is the only rank) |
| The root's wait bound passes | compute, CALL | TIMED_OUT, observed | yes | PARTIAL |
| Reply does not match (node, run, rank, rows, length) | compute or gather, CALL | OTHER, observed | yes | PARTIAL |
| Deadline passes during the scatter | broadcast not started; every compute and fetch NOT_ATTEMPTED | none | all | FAILED |
| Deadline passes during the gather | the remaining fetches NOT_ATTEMPTED | none | yes | PARTIAL |
| Every remote compute fails | none | none | none | FAILED |
| Size, participants or root invalid | none | none | none | REFUSED (timing and verification null) |

**Budget** (`MatrixRunBudget.of(RmiProperties, RmiTransportProperties, MpiProperties, MatrixRunProperties)`; bounds and
allowances, never measured):
- `transferWorstMillis` = `MpiModule.EXCHANGES_PER_TRANSFER` (3) x (connect + call) = 3 x (1000 + 3000) = 12000;
- `computeWaitMillis` = `transferWorstMillis` (the root's bound for the compute phase);
- `productAllowanceMillis` = (warmup-products + 1) x compute-timeout-millis = 2 x 2000 = 4000. **The warm-up and the
  sequential product are allowances, not enforced bounds** (calling thread, never interrupted);
- `worstCaseRunMillis` = allowance + deadline + transferWorst + 1 = 4000 + 10000 + 12000 + 1 = **26001 ms** (local and
  public). The + 1 is the whole-millisecond rounding of the deadline handed to each collective.

**Properties** (`MatrixRunProperties`, `udcf.matrix`, no code defaults; public inherits all three):

| Key | application.yml | application-public.yml |
|---|---|---|
| `warmup-products` | 1 | (inherited) 1 |
| `compute-timeout-millis` | 2000 (below `udcf.rmi.call-timeout-millis`, checked at startup) | (inherited) 2000 |
| `result-store-size` | 4 | (inherited) 4 |

The dispatch pool has no property: E10c sizes it with `udcf.cluster.size` (the most ranks a run can have).

**Experiment 2 side effect (condition 7).** The first compute on a node starts its requests service (port 720k,
7201-7205 in production). The matrix never stops it; Experiment 2 owns its lifecycle. **Correction to the review's
wording:** a cluster reset does not stop it either: `ClusterResetService.reset` recovers crashed nodes and calls each
module's `reset`, and `MultithreadingModule.reset` only clears the request history; the listener closes on a crash or at
shutdown (`Cluster.close` -> `ClusterNode.stop`). What the start produces: one event, `SERVICE_STARTED` (module
`cluster`, the node's id, data `{service: requests}`), published by `ClusterNode.ensureService`; no meter is registered
by the start (`ThreadPoolMetrics` registers counters and the timer only when Experiment 2's own requests finish, and
`execute` records none); the executor gauges bound once per node by `MultithreadingMetrics` switch from NaN to live
values because they read `RequestsNodeService.find(node)` and `snapshot()`. The matrix work appears in that executor's
live stats (active, queue, `completedTasks`) but not in Experiment 2's request history or its `REQUEST_*` events
(`MatrixEndToEndTest.runsOnExperimentTwo`). `MultithreadingModule.status()` is IDLE unless one of its own batches is
draining, so it reports IDLE while the listener runs and matrix blocks execute on its executors.

**For E10c.**
1. Run one size per HTTP request (a whole series would take up to 4 x 26001 ms); the client timeout of a run must be at
   least `worstCaseRunMillis` + 3000 = 29001 ms; serve the budget in the overview, as E9c does for its limits.
2. Busy lock: recommendation, **not** shared with the MPI module. Each module keeps its own `ModuleActionGuard`; the
   runs use disjoint collective ids, and the only interaction is eviction from a node's collective store (8 slots),
   which needs about 7 MPI collectives between a matrix scatter and its compute (milliseconds) and is then reported as
   INPUT_MISSING, never hidden. Sharing a lock would couple two modules' 409 behaviour for a rare, already-visible case.
3. `runId`: a module counter from 1, held with the module's history and reset to 1 by the module's reset. A repeated run
   id after a reset replaces the node's stored inputs and kept blocks (both stores replace a repeated id), and runs never
   overlap under the guard.
4. Create `MatrixDispatchPool(cluster.size())` when the module starts or on the first run, and close it in the module's
   `close()` (5 s bound).
5. Call `RmiModule.ensureStarted()` before each run; use `RmiCollectiveTransport.of(rmiModule)`,
   `RmiMatrixTransport.of(rmiModule)`, `NodeExecutor.experimentTwo(root, ...)` for the root and
   `MatrixRunBudget.of(...)`; make `MpiRoleSelector` public (one word) as decided in E10a.
6. **The derived seeds must never be put on the wire as JSON numbers** (they are full 64-bit values, often negative or
   above 2^53 - 1, which JavaScript cannot hold). Only the user's seed goes on the wire, limited to 0..2^53 - 1 as E9's
   `MAX_SEED`. The scatter and broadcast `CollectiveResult.seed` are derived seeds: map them out of the DTO or as
   strings.
7. Fairness note: put it in the basis text served with the figures (see Known issues).
8. The Experiment 2 side effect above: say it on the page (E10d) and in the module overview.
9. `frontend/src/test/fixtures/rmi/registries-*.json` list one name per node, the live backend three; the README says
   so; they are not recaptured until Phase 13. Collective ids >= 1_000_000_000 belong to matrix runs and appear in the
   MPI page's kept ids per node.
10. The assembled C is not in `MatrixRunResult`; only checksums, counts and times go on the wire.

**Test ports (E10b): 28706-28749 bound, 28600-28659 numbers only.** A cluster needs six bases at least 10 apart, and
bases in 28706-28749 fit at most five, so the never-bound bases live in 28600-28659 (approved). Grep of
`backend/src/test` before E10b: no use of 28600-28659 or 28706-28749.

| Class | Bound ports | Own range | Never-bound bases |
|---|---|---|---|
| `RmiMatrixTransportTest` | RMI 28706-28708 | 28706-28715 | clock 28600, election 28610, replication 28620, mapreduce 28630, requests 28640 |
| `MatrixFilterTest` | RMI 28716-28718 | 28716-28725 | as above |
| `MatrixEndToEndTest` | RMI 28726-28728, requests 28736-28738 | 28726-28749 | clock 28600, election 28610, replication 28620, mapreduce 28630 |
| `MatrixEndpointExportTest` | none | none | all six (rmi 28650, requests 28640) |

**No dedicated test (R6):** the interfaces `MatrixEndpoint` and `MatrixTransport`; the enums
`MatrixComputeException.Reason` and `ComputeRecord.Status`; `MatrixComputeException` (a carrier with null checks,
covered by `MatrixEndpointLogicTest` and `RmiMatrixTransportTest`). Test helpers: `InMemoryMatrixNodes` (real node
logics, no sockets, scripted failures and hooks) and `MatrixRmiFixture` (a real three-node cluster).

**Tests:** 76 new tests in 13 classes: `ParallelMatrixExecutorTest` 22, `BlockUnitTest` (mpi) 7,
`MatrixEndpointLogicTest` 7, `RmiMatrixTransportTest` 6, `NodeExecutorTest` 5, `MatrixRunRecordsTest` 5,
`MatrixDispatchPoolTest` 4, `MatrixEndpointExportTest` 4, `MatrixEndToEndTest` 4, `MatrixRunPropertiesTest` 4,
`MatrixFilterTest` 3, `MatrixRunBudgetTest` 3, `RowAlignedScatterTest` 2. Changed: one assertion block in
`RmiControllerTest` (three names). No sleeps: latches and Awaitility only; no speedup threshold is asserted (only
non-null). Windows: new tests x 5 runs plus 1 reverse-order run, 76 each, 0 failures; packages mpi, rmi, matrix and
multithreading 728 tests, 0 failures; full suite 3 runs, 1780 tests, 0 failures, 1 skipped each (1704 before).

#### E10b deviations from the approved plan

1. `NodeExecutorTest` (5 tests) is a class of its own, for condition 4's cancellation proofs; the plan had them under the
   logic test.
2. The root's wait-bound test uses a budget of 3 x (200 + 200) = 1200 ms rather than a few ms, so a slow CI thread
   cannot make the other ranks miss it.
3. `MatrixRunProperties.warmupProducts` is an `Integer` (with `@NotNull`), so a missing key fails startup although 0 is
   a valid value.
4. Small helpers beyond the plan's signatures: `MatrixEndpointLogic.keptRunIds()`,
   `MatrixRunBudget.productAllowanceMillis()`, `MatrixRunRequest.MAX_RUN_ID` and `COLLECTIVE_ID_BASE`,
   `MatrixEndpointExport.requireComputeBelowCall(...)` (public, used by the YAML test).
5. `RmiControllerTest`: the comment line above the assertion changed too (4 lines replaced by 5).
6. `MatrixDispatchPool` uses an unbounded queue (at most one task per rank per run, runs never overlap under E10c's guard).
7. Condition 7's statement that a cluster reset stops the requests listener does not hold in the code (see "Experiment 2
   side effect"); recorded as found, nothing changed.

## Housekeeping H1 (after Experiment 1)

**Found.** `ReplicationNodeService.bind()` (E5b) called `setReuseAddress(true)` unconditionally on an
unbound `java.net.ServerSocket` before binding 127.0.0.1, and the E5b test `ReplicationClientTest`
(FakeNode) did the same. The rule comes from two places:
- `track-b-swanand.md`, E8b note: "`ReplicationNodeService.java:806` calls
  `socket.setReuseAddress(true)` on the replication server socket unconditionally, with no guard for
  Windows or any other OS. Not changed (Track C's file). On Windows the JDK binds exclusively by
  default, so a second bind still fails".
- `LoopbackSocketFactory` Javadoc (E1b): "It never calls {@code setReuseAddress}: the JDK default is
  kept (off on Windows, so no other socket can take over the port; on on Linux, so a port with
  TIME_WAIT connections can be bound again)."

**Proved by a throwaway probe** (`ReuseProbe.java`, outside the repository, ports 28720-28725, JDK
21.0.12.1 on Windows 11 and on Linux in Docker `maven:3.9-eclipse-temurin-21`, `--pull never
--network none`):

| Case | Windows | Linux |
|---|---|---|
| Default `getReuseAddress()` of an unbound `ServerSocket` | false | true |
| Second bind on a live listener's port, both with `setReuseAddress(true)` | refused (`BindException`) | refused |
| The same with `-Dsun.net.useExclusiveBind=false` | **succeeds: the port is taken over** | (not applicable) |
| Rebind right after close, with 3 TIME_WAIT connections, default setting | succeeds | succeeds |
| The same with SO_REUSEADDR explicitly OFF | succeeds | **fails** (`BindException`) |

On Windows with the JDK default (exclusive bind) the call is harmless: the JDK only records the flag,
and a second bind is still refused. The hazard appears only with `-Dsun.net.useExclusiveBind=false`,
where the call really sets SO_REUSEADDR and another socket can take the port. On Linux the default is
already on, so the call did nothing, and that default is what allows the immediate rebind after a
crash. Leaving the call out is therefore correct on both OSes.

**Changed** (4 files):
- `ReplicationNodeService.java`: the call removed; a two-line ASCII comment in its place.
  `new ServerSocket()` plus `bind` unchanged; no import became unused.
- `ReplicationClientTest.java`: the FakeNode call removed; a one-line ASCII comment in its place.
- `RmiNodeServiceTest.java` line 138: the literal euro sign replaced by the escape `\u20ac` (by
  script). The file now has no non-ASCII character; `longestEchoPassesTheFilter` passes.
- This file.

No new test: the call was removed rather than guarded, so there is no decision to test. The existing
replication tests, which crash and rebind on real sockets, are the proof.

**Findings for other owners (not changed):**
- `clocksync/ClockNodeService.java:467` (Rohan, Track D): `DatagramSocket` (UDP),
  `setReuseAddress(true)` unconditional. Not probed (H1 probed TCP `ServerSocket` only).
- `multithreading/RequestsNodeService.java:303` (Nidhi, Track A): `ServerSocket`, unconditional.
- `test/.../loadbalancing/TcpWorkerTransportTest.java:70` (Nidhi, Track A): test `ServerSocket`,
  unconditional.
- `mapreduce/MapReduceNodeService.java:207` (Rohan, Track D): already guarded by `if (!isWindows())`.
  No action.
- **For Rohan:** `docs/HANDOFF.md` contains no SO_REUSEADDR rule (`grep -i reuse` finds none). The
  rule exists only in the two track notes quoted above.

Linux (Docker, --cpus=2), run by Jai: 227 replication and rmi tests x 5 runs plus 1 reverse-order run, 0 failures; full suite 1446 tests, 0 failures, 1 skipped; clean-clone full suite 1446 tests, 0 failures, 1 skipped.

---

## Known issues

- E5a: the same version (epoch, Lamport time, origin) with a different value is refused as `STALE`
  and the stored value is kept; it is never repaired. A correct node never stamps two values with
  one version, so this only arises from a protocol fault; `ConsistencyCheck` reports it as
  `CONFLICT`.
- E5a: `DataStore` has a package-private test seam (`DataStore.Probe`, `DataStore(Probe)`) so tests
  can pause the write path and force interleavings; production code uses the public constructor.
- E5a: the fence tests check the guarantee on every run, and one forces the critical interleaving
  through the probe, but no test can enumerate every possible interleaving.
- E5b, for Track B (E8b): **orphan local item.** A recovered old primary still believes it is
  primary, so its first write is applied to its own store (at the old epoch) before its pushes come
  back `STALE_EPOCH` and supersede it; that write throws `NotPrimaryException` (not confirmed) but
  the item stays in the old primary's store. Resynchronising it from the new primary
  (`antiEntropy` from the new primary) overwrites keys the new primary also holds, by
  last-writer-wins on the newer epoch, but a key only the old primary wrote stays there. Exp 8 must
  either query the cluster's epoch (`observeEpoch`) before the old primary writes again, or treat
  such keys as lost. Accepted (Q4).
- E5b: a push in flight when its sender crashes ends `ABANDONED`: the backup may or may not have
  applied it. A synchronous write interrupted by its primary's crash throws `NodeDownException`,
  but the item is in the primary's store and possibly on some backups.
- E5b: `SO_TIMEOUT` bounds each read, not a whole request. A client trickling one byte just inside
  the timeout can hold a handler for longer than `timeout-millis`, bounded by the message limits
  (8192 characters a line, 1000 items). Loopback only, so accepted.
- E5b: a dump of more than `batch-size` items is several exchanges, so it is not one atomic
  snapshot while writes arrive (each key is as its page saw it).
- E5b, for E5c: in Exp 5 the primary moves to node 2 when node 1 crashes (same epoch, 1); if node 1
  recovers it is primary again but has missed node 2's writes. Anti-entropy runs from the primary,
  so it does not repair the primary itself. **Resolved in E5c** by the takeover: catch-up from every
  live peer, then a push to every live backup (see the E5c interfaces).
- E5b: the close waits are bounded at 5 s; reaching a bound throws `IllegalStateException` from
  `crash()`/`stop()`, which `ClusterNode` records as a crash failure. The tests prove the waits on
  every run but cannot prove a bound is never reached under extreme load.
- E5b: the Linux accept/close behaviour is checked by five Docker runs
  (`maven:3.9-eclipse-temurin-21`, `--cpus=2`) and CI, not on a real Linux host; Docker wrote
  `backend/target` during those runs.
- E5c: **last-writer-wins can lose the newer-in-real-time write.** In Exp 5 the epoch never changes,
  so conflicts are settled purely by (Lamport time, origin). Say an asynchronous write never left a
  primary that then crashed, and its Lamport time is higher than a write made later on the new
  primary. After a catch-up or anti-entropy, the old write wins and the later one is lost. That is
  correct last-writer-wins, not a fault, and exactly what Exp 8's epochs fix. It is served as
  `conflictRuleNote` for the E5d page ("What to notice") and must not be hidden.
- E5c: a catch-up only pulls from live peers. A crashed replica's writes, for example in-flight async
  writes held only by a crashed old primary, reach the others only when that replica recovers and
  a later takeover or anti-entropy pushes from a node that holds them.
- E5c: the takeover runs only inside a guarded action (write, anti-entropy, injection, recover).
  After the cluster page crashes or recovers node 1, the overview says `takeoverPending: true` until
  the next such action; reads never repair anything.
- E5c: the health table is measured by the current primary and is restarted on every takeover, so
  it starts empty (counts 0, latencies and lastSync null) after a primary change.
- E5c: `error-409-module-busy` is covered by MockMvc only (ReplicationControllerTest holds the guard).
  No replication action runs long enough to capture it from the live backend (Q6); its body shape is
  the shared one already captured by the other modules.
- E5c: `ReplicationModule.reset()` takes the module guard. If an action starts between the cluster
  reset's BUSY check and this module's reset, the reset throws `ModuleBusyException` (409) after the
  nodes were recovered and earlier modules were reset. That window is very small.
- E5d: the kit's event log shows the backend's own event messages verbatim, and E5b's `WRITE` and
  `WRITE_CONFIRMED` messages end in "[SYNCHRONOUS]" or "[ASYNCHRONOUS]" (all caps). The page's
  all-caps test therefore covers every section except the event log. Changing the message text is
  a backend follow-up (E5b's events), not done here.
- E5d: the derived states ABSENT, AHEAD and CONFLICT are tested on copies of a real fixture with
  only `state` and `item` changed (Q4). The captured fixtures contain CURRENT, STALE, MISSING and
  UNREACHABLE.
- E5d: the browser check was run in Chrome with the tab in the background, where timers are
  throttled to about 1 s. The window, STALE cells and convergence were still seen through a
  MutationObserver. The Chrome window could not be resized below the screen size, so the 375×740
  and 1280×800 measurements were taken in a same-origin iframe of exactly that size.
- E5d: `npm run build` warns that the main chunk is above 500 kB (530.29 kB). Code-splitting is a
  shared-build decision, not part of this step.
- E1a: payload sizes are Java serialization sizes of the argument and reply values, measured by
  serializing them again on the caller, outside the timed window. They are not the RMI wire bytes (the
  call header, object identifier, method hash and class annotations are not counted). The page must
  show `PayloadSizer.LABEL` next to them.
- E1a: a failed stage's elapsed time is reported only as `timeToFailureNanos`; `lookupNanos` and
  `roundTripNanos` are reported only for a lookup or a call that completed (follow-up F1).
- E1a: the local-versus-remote comparison is one measurement per side, local first, with no warm-up.
  The first comparison in a fresh JVM pays class loading and provider lookup, so the overhead can be
  negative and the ratio below 1. This is shown as measured, never clamped.
- E1a: `NodeInfo.javaVersion` is `Runtime.version()` (the JVM version number only). RMI exception
  messages, and so `failureMessage`, name the host in the stub, which is 127.0.0.1 only once E1b sets
  `java.rmi.server.hostname=127.0.0.1`.
- E1a: the `forNode` tests use a Mockito mock of `ClusterNode`; a real one needs a `ClusterEventBus`,
  which starts a dispatcher thread, and E1a tests start none.

- E1b: **RMI keeps the first equal socket factory per port for the life of the JVM**
  (`TCPEndpoint.getLocalEndpoint` caches by `equals` and never removes an entry), and creates every
  later listener on that port with that first instance. Hence one factory per `RmiNodeService` with a
  unique `instanceId`. Each service instance adds one small cache entry per port (tests create many;
  production one per node).
- E1b: **fact (i) is false on Linux**: when the last object on a port is unexported, the port can
  stay bound while RMI's accept thread is still inside `accept()`. `crash()`/`stop()` therefore wait
  (bounded 5 s) for that thread; the wait has a code comment so it is not removed.
- E1b: **the ~5 ms pooled-connection reuse window**: RMI reuses a pooled connection without a check if
  it was used less than about 5 ms before (`TCPConnection.roundtrip = 5`), so a call right after a
  crash (or a fast crash and recovery) fails as CONNECTION_LOST instead of CONNECTION_REFUSED, and a
  call right after a recovery can fail once. RMI does not retry. Humans in the UI are far slower than
  5 ms; tests drain the pools (`drainUntilRefused`) before asserting a success after a recovery.
- E1b: the call timeout (`SO_TIMEOUT`) bounds each read, not a whole call; a peer trickling bytes
  can hold a call longer. Loopback only, our own servers.
- E1b: the reply direction (a caller reading a result) is not filtered; see "E1b interfaces for E1c"
  for why that is acceptable.
- E1b: a call already running on an RMI thread when its node crashes runs to its end (RMI threads
  cannot be stopped from here) and ticks the node's clock; its reply cannot be delivered (connection
  closed). At most about one `computeHash`.
- E1b: the serial filter does not bound a String argument's size: the JDK reads a String without a
  filter checkpoint (only later checkpoints count its bytes), so an oversized echo text is bounded
  only by `max-echo-length` in `NodeEndpointLogic` after it was read. The longest allowed echo (1024
  x U+20AC, 3 bytes each) passes end to end (`RmiNodeServiceTest.longestEchoPassesTheFilter`).
- E1b: RMI's client-side DGC thread ("RMI RenewClean-[127.0.0.1:port,factory]") of a service from an
  earlier test lingers until its stubs are garbage-collected; leak checks therefore match the thread
  of the service under test by factory id.
- E1b: the connect timeout is not exercised by a test (a loopback connect is accepted or refused at
  once); only its value and the read timeout are. The factory's wait cannot be forced to time out in a
  test (no way to keep a thread in `accept()` after its listener closed), so that branch is covered by
  code review only.

- E1c (Q5): **after a reset, the next start is logged as `SERVICE_RECOVERED`, not `SERVICE_STARTED`.**
  `RmiModule.reset()` stops each RMI service directly, but the core (`ClusterNode`) keeps a stopped
  service registered. The next `ensureService` therefore recovers it, and publishes
  `SERVICE_RECOVERED` (module `cluster`). The module's own `ENDPOINT_EXPORTED` is published either
  way. No core change (accepted).
- E1c (Q6): **call ids restart at 1 after a reset, so `callId` values repeat in older events.** A
  module reset clears the history, but old `rmi` events with the same `callId` stay in the event
  log until a cluster reset clears it (the cluster reset does both). E1d must refetch the history
  after a reset (`CLUSTER_RESET`) and key rows by kind + id + `calledAt`, never by id alone; it must
  not join events to history entries by `callId` across a reset.
- E1c (A7 item 3, confirmed by Rohan): **`CALL_SERVED` is published only for successful calls.** A
  call that reached the target and then failed or lost its reply leaves a clock tick on the target
  node and no served event. Examples: `TIMED_OUT`, where the node ran the method and ticked its
  clock while the caller had given up; or `CONNECTION_LOST`. The module never learns the reply's
  Lamport time, so it has nothing to publish. E1d must not suggest that the target did nothing.
- E1c (A7 item 4): **lookup and call are each bounded per read, not as a whole** (the E1b issue:
  `SO_TIMEOUT` = `call-timeout-millis` bounds each read, and a peer trickling bytes can hold a call
  longer). Worst case computed for this step:
  - A remote call is at most three exchanges: the registry lookup, RMI's DGC dirty call inside
    `lookup()`, and the method call. Each exchange is at most `connect-timeout-millis` (1000 ms)
    plus `call-timeout-millis` (3000 ms) per read, so about **12 s** for a call that reads once per
    exchange.
  - A comparison adds its local half (item 6).
  - A failed start adds up to 5 s per node (E1b's listener wait).
  - Measured on loopback: a dead node is refused at once (10.6 ms in `call-crashed-target.json`);
    the 50 ms timeout fired after 51.3 ms (`call-timed-out.json`).
- E1c (A7 item 5): **the overview never starts anything.** `GET /api/modules/rmi` and
  `GET /registries` make no start and change nothing. Until a start, every node is `NOT_STARTED`
  and every registry unreachable (`registries-before-start.json`). E1d must call `POST /start` (it
  is idempotent) when the page mounts, and again after a `CLUSTER_RESET` (a reset stops the
  services), before showing registries.
- E1c (A7 item 6): **the local half of a comparison has no timeout.** It is a direct Java call on
  the target's endpoint object, on the request thread, bounded only by the work. The maximum is
  1,000,000 SHA-256 rounds (100,000 in the public profile); 100,000 rounds measured 90.1 ms
  locally in `comparison-compute-hash.json`. The request then also makes the remote half (item 4).
- E1c: **the shared HTTP client's 10 s timeout is below the worst case of a call (about 12 s; more
  for a comparison).** E1d must raise the timeout for `POST /calls` and `/comparisons` (see "E1c
  interfaces for E1d").
- E1c: listing registries publishes no event, and calls `registry.list()` plus one lookup per name
  on each node in turn, without the guard. A crashed node is refused at once on loopback. A
  registry read while a node is restarting can therefore mix states between nodes.
- E1c: an unreadable body (for example an unknown `method`) and a non-numeric id get Spring's
  default 400 (`title` "Bad Request", no `errors`), because changing that would need the shared
  `GlobalExceptionHandler`. Both shapes are captured and documented for E1d.
- E1c: a module reset cannot stop an RMI call already running on the target node's RMI thread (E1b
  known issue). That call's history entry is dropped, through the generation check, but its events
  are still published, because they really happened.
- E1c: `RmiNodeServiceTest.java` (E1b) contains one literal non-ASCII character (`€` on line 138),
  despite the E1a follow-up F2 rule. It is not changed here (not an E1c file, not a defect).
  **Resolved in H1** (now the escape `\u20ac`).
- E1d: the live check against the real backend is run by Jai (the agent did not start the backend). The page was
  verified by lint, build and 785 tests on the real fixtures only.
- E1d: in development React's StrictMode runs the mount effect twice, so the page sends `POST /start` twice on mount
  (idempotent: a running service does nothing). A production build sends it once.
- E1d: every refresh reads the registries over the network (one `list()` plus one lookup per node), triggered by each
  module event; refreshes are coalesced, so a call's three events cost at most two reads.
- E1d: the history detail panel checks that `GET /calls/{id}` returned the same kind and `calledAt` as the row (ids
  restart after a reset); on a mismatch it says so and refreshes the list instead of showing another call.
- E1d: the failure message is shown exactly as the backend escaped it (a literal `\n\t` stays as text).
- E1d: `npm run build` warns that the main chunk is above 500 kB (690.50 kB); code-splitting is a shared-build
  decision, not part of this step.
- E9a: the deadline stops new transfers but cannot interrupt one already running, so a collective can overrun
  `collective-deadline-millis` by one transfer, bounded by E9b's RMI timeouts (about 22 s in all; see open item 3).
- E9a: the transport seam does the lookup inside `deliver`/`collect`, so the root ticks its Lamport clock before the
  lookup. A LOOKUP failure therefore consumes one tick (a local event) although nothing was sent; the record shows
  `senderLamportAtSend` and `requestBytes` as null.
- E9a: a FAILED transfer recorded no time to failure at the first final report (open item 5); only DELIVERED transfers
  have a latency. Resolved in F1: a FAILED transfer carries `timeToFailureNanos`.
- E9a: scatter's whole-vector check relies on the receivers' chunk checksums (SHA-256) and on the blocks tiling
  [0, n); the root never gets the chunks back to compare bytes.
- E9a: with the root as the only rank there is nothing to verify, so `passed` is null for a COMPLETE collective.
- E9a: every request is sized by serializing it once more (outside the timed window), which costs CPU for large
  payloads (100000 doubles, about 800 KB, per remote rank).
- E9b: a payload longer than `max-payload-elements` is refused by the node's filter, but what the caller sees depends on
  its size: ARGUMENTS_REJECTED for a small one, CONNECTION_LOST for a large one on Windows (the node closes while the
  caller is still writing; the cut-over depends on socket buffers and may differ on Linux). It cannot happen through
  `CollectiveExecutor`, which refuses count > `max-payload-elements` (PAYLOAD_TOO_LARGE) before sending anything; only
  a call that bypasses the executor reaches the filter with an oversized payload.
- E9b: the filter never sees a String (the E1b known issue): a String forged into the `double[]` slot is read into the
  node's memory first and only then refused by RMI's dispatch ("argument type mismatch", METHOD_THREW); the method never
  runs.
- E9b: a transfer's `latencyNanos` includes the registry lookup and RMI's DGC dirty call (`LATENCY_BASIS`), unlike
  Experiment 1's separate lookup and round-trip figures.
- E9b: the E1c registry fixtures (`frontend/src/test/fixtures/rmi/registries-*.json`) and their README list one name per
  node; since E9b a live backend lists two. Not changed here (finding for E9d).
- E9c (risk 1, accepted): **a root that crashes during its own collective is not noticed.** The root's sending runs on
  the request thread, and its Lamport clock still works, so the collective goes on. This is the same as E1c's crashed
  caller. A requested root that is already crashed is refused with 409 before anything is sent.
- E9c (risk 2): the 409 busy fixture could only be captured from one Python process: a 100000-value broadcast holds
  the module for about 50-65 ms, which is shorter than a curl process start. It was captured live on the first such
  attempt (see the fixture README). The rejection's `ACTION_REJECTED_BUSY` event is covered by MpiModuleTest, not by a
  fixture.
- E9c (risk 4, accepted): **`recommendedMinClientTimeoutMillis` is a bound from configured timeouts, not a
  measurement**, and it leaves out three things:
  - CPU time outside the transfers (generating the vector, SHA-256 checksums, and `PayloadSizer` serialising every
    request again);
  - RMI's 5 s listener wait per node when an RMI start fails inside `ensureStarted` (E1b; fixed in code, not
    configured);
  - `SO_TIMEOUT` bounds each read, not a whole exchange.

  E9d must add its own margin.
- E9c: a plain-Socket blocker (no SO_REUSEADDR, as it must be, or RMI could bind the port on Linux) is sensitive to
  earlier connections on the same port: on Linux its bind fails while any connection on that port is in TIME_WAIT
  (H1 probe), and earlier tests in the same class had connected to it. Such a test needs its own ports, and must never
  let anything accept connections on the blocked port.
- E9c (risk 5, accepted, RmiModule unchanged): `RmiModule.ensureStarted` checks "was running" before starting, without
  a lock. If Experiment 1's start and an Experiment 9 action run it at the same moment, `ENDPOINT_EXPORTED` (rmi) may be
  published twice or not at all. MPI's own `COLLECTIVE_ENDPOINT_EXPORTED` is computed under the mpi guard, from MPI's
  own before and after view.
- E9c: if the executor throws unexpectedly (its own contract, for example a time source going backwards), the guard is
  released and the history id is given back. The `COLLECTIVE_STARTED` event already published stays, with no
  FINISHED, and the next collective reuses that id.
- E9c, **finding for Track D (Rohan), not changed:** `ClockSyncModule`'s constructor starts the UDP clock service on
  every live node (`ensureService` at lines 102-112) when the Spring context starts. Every Spring test that does not
  set `udcf.cluster.ports.clock-base` therefore binds the production UDP ports 6001-6005 (local profile), including
  E1c's `RmiControllerTest` and `RmiEndToEndTest`. E9c's Spring tests set `clock-base` inside Jai's block. This is
  safe while test classes run one at a time, but it is outside the README's "lazy start" rule and the test-port rule.
- E9d: `useMpi` is an adapted copy of `useRmi`, and `MpiPage`'s `ProblemAlert` a copy of `RmiPage`'s (Q6, accepted).
  A shared hook and alert are a later refactor.
- E9d: main chunk grew from 690.50 kB (gzip 208.27) to 738.64 kB (gzip 220.77) with the E9d page (+48.14 kB, gzip
  +12.50 kB). Pages are bundled in the main chunk (eager `registry.js`); route-level code splitting is open for Phase 14.
- E9d, **finding for Track A (Nidhi), not changed:**
  `src/modules/multithreading/useMultithreading.test.js > useMultithreading > reloads after a reconnect, but not on the
  first connect` fails under `npx vitest run --sequence.shuffle` for some seeds (for example 1791907079925), also
  when that file runs alone (4 of 6 shuffled runs of the file alone failed). It is an order dependence inside that
  file; the default (unshuffled) order passes.
- E10a (Q3, accepted): the scatter and broadcast times are Experiment 9's `totalNanos`, which include the root's
  payload sizing (PayloadSizer re-serialises every request) and checksum work between transfers, so they overstate the
  time on the wire. They must be labelled so; E10b decides whether to separate that out.
- E10a: no warm-up policy exists yet (`warmupProducts` is only carried and stated). Until E10b/E10c set one, figures
  with `warmupProducts = 0` include JVM compile effects, and `SpeedupBasis.text` says so.

- E10b (condition 8), **fairness:** the timed parallel run executes after W + 1 full products (the warm-up and the
  sequential product), the timed sequential product after W. With warmup-products 1 the parallel run therefore meets a
  slightly warmer JIT and cache than the sequential product. E10c puts this sentence in the basis text it serves;
  `SpeedupBasis` is unchanged.
- E10b, **side effect on Experiment 2:** the first matrix compute on a node starts its requests service and binds its
  requests port (7201-7205); the matrix never stops it and a cluster reset does not either (only a crash or shutdown).
  It produces one `SERVICE_STARTED` event (module `cluster`); Experiment 2's executor stats show the matrix work, its
  request history and `REQUEST_*` events do not, and `MultithreadingModule.status()` stays IDLE meanwhile.
- E10b: collective ids >= 1_000_000_000 belong to matrix runs (scatter 1_000_000_000 + 2 x runId, broadcast + 1); they
  are stored on the nodes like any collective and appear in the MPI page's kept ids per node.
- E10b: a node's collective store holds 8 payloads; MPI collectives run between a matrix scatter and its compute can
  evict the matrix input, which is then reported as INPUT_MISSING (a hole), never hidden.
- E10b: a timed-out multiply that had already started runs to the end on its Experiment 2 worker (the multiply ignores
  interrupts) and its result is discarded; until then it occupies that worker. A queued one never starts.
- E10b: `payloadGenerationNanos` is informational: the scatter and the broadcast regenerate A and B from the seeds
  inside the parallel run (that time falls in `otherNanos`); nothing is subtracted.
- E10b: each collective gets the run deadline's remaining time rounded up to whole milliseconds, so a transfer can start
  up to 1 ms after the run deadline (the + 1 in `worstCaseRunMillis`).
- E10b: the warm-up policy is set (warmup-products 1, local and public), which settles the E10a item above for runs made
  with the configured value.
- E10b: `frontend/src/test/fixtures/rmi/registries-*.json` are stale (one name per node; the live backend lists three);
  the README says so; not recaptured until Phase 13.
---

## Progress log

- 2026-10-07 track file created.
- 2026-10-08 E5a done: DataItem, DataStore (single apply guard with a sender-epoch fence on a
  ReentrantReadWriteLock), ApplyResult (APPLIED, DUPLICATE, STALE, STALE_EPOCH), AntiEntropy,
  ConsistencyCheck, OutOfOrderInjector, ReplicationStats and ReplicationRoleSelector as pure classes
  in com.udcf.modules.replication; 8 new test classes, 94 tests; backend 619 tests (3 runs, all
  green, identical totals; baseline 525), frontend not run (no frontend change); deviations: none
  from the approved plan except 3 extra tests added after the first run so that every requirement
  has its own test method (see the list of deliberate differences from legacy above).
- 2026-10-08 E5b done: ReplicationNodeService on ports().replication() (sync and async writes, the
  simulated async delay from YAML, anti-entropy in batches, out-of-order delivery, role and epoch
  supersession, crash waits for accept thread, handlers, pushes and scheduler), ReplicationProtocol
  (Base64 UTF-16 fields, END-terminated item blocks, 8192-character lines, 1000 items), ReplicationClient,
  ReplicaSet (selector, TODO(L1)), ReplicationProperties (udcf.replication block in application.yml)
  and result records; 6 new test classes, 66 tests on ports 28401–28429; backend 687 tests, 1
  skipped (3 Windows runs, identical; baseline 621), replication classes 66 tests in 5 Linux Docker
  runs, all green; frontend not run (no frontend change); deviations: (1) a package-private
  `ReplyChecks` helper for the reply records; (2) `PushStatus.ABANDONED` added beside ACKED, FAILED
  and NOT_SENT (a push in flight when the sender crashed is not "not sent"); (3) an extra
  `WRITE_NOT_CONFIRMED` event; (4) item-carrying messages end with an `END` line, so a count mismatch
  in either direction is detected; SYNCED also carries `pushed`; (5) anti-entropy and
  `deliverOutOfOrder` run on the push executor, so crash() waits for them too; (6) some test methods
  are named or grouped differently from the plan (see the E5b report).
- 2026-10-09 E5c done: ReplicationModule (lab 5) and ReplicationController under
  /api/modules/replication (sync/async writes, read per replica and side by side over TCP, crash a
  backup and recover through Cluster.crash/recover, anti-entropy, stale injection, health table
  measured by the current primary, clean-slate reset in two passes), the takeover with catch-up
  (ReplicationNodeService.catchUpFrom, ReplicaSet takeover, PRIMARY_SELECTED/CATCH_UP events),
  ReplicationMetrics (4 MetricNames constants added), 24 contract fixtures captured from the
  running backend; 3 new test classes and new tests in 3 existing ones, 49 tests; backend 736
  tests, 1 skipped (3 Windows runs, identical; baseline 687); the 6 new or changed replication test
  classes, 88 tests, green in 5 Linux Docker runs; frontend not run (no frontend code changed; the
  fixtures are data); deviations: (1) an extra `ReplicaColumnDto` for the side-by-side view's
  columns, and an `ABSENT` state (neither replica holds the key); (2) the latency timer's second tag
  is `kind` (SYNCHRONOUS, ASYNCHRONOUS or OUT_OF_ORDER) instead of `model`, so injected stale
  updates are labelled honestly; (3) `PRIMARY_SELECTED` is also published on the first selection;
  (4) the recover action runs a pending takeover only when one is pending, and crash never changes
  the selection; (5) ReplicationRecordsTest gained tests for the two new records.
- 2026-10-09 E5d done: Replication page at /experiments/5-replication on the E2d kit: replica grid
  (every state as a word plus a shape), takeover timeline (catch-up per peer, pending marker),
  asynchronous window opened and closed by the backend's replicationState with the Simulated
  badge, health table with honest dashes, write/read/crash/recover/anti-entropy/stale-update
  controls with focus management and polite announcements, contract test on the 24 E5c fixtures;
  checked against the real backend at 1280x800 and 375x740 (done-when 1-3 seen live), servers
  stopped, 8080 and 5173 free; backend not changed (736 tests at E5c), frontend 388 tests (3 runs,
  baseline 296); deviations: (1) the focus target after a recover falls back to the node's label
  when it comes back as the primary (found in the live check, test added); (2) the all-caps test
  excludes the kit's event log (backend message text); (3) approved shared edits:
  routes.test.jsx (add-only) and the lab 5 concept line in experiments.js.
- 2026-10-11 E1a done: pure RMI contract (NodeEndpoint with a CallContext first argument for L4,
  four Serializable reply records), NodeEndpointLogic (real chained SHA-256, checked against vectors
  computed outside Java), RmiInvoker with EndpointLookup seam, RmiCallResult (null-never-0, separate
  timeToFailureNanos), PayloadSizer (primitives sized as primitives), LocalRemoteComparison (single
  measurements, unclamped), RemoteFailureClassifier, CallTimer, RegistrySnapshot, RmiProperties
  (udcf.rmi block in application.yml and application-public.yml); 10 new test classes, 86 tests (5
  runs of the rmi classes, all green); backend 1292 tests, 1 skipped (3 runs, identical; baseline
  1206), frontend not run (no frontend change); deviations: the eight listed under "E1a deviations
  from the approved plan" (Interfaces for other tracks), and 86 tests instead of about 68
  (parameterised cases and DisplayTextTest).
  Linux (Docker, --cpus=2): 86 rmi tests x 5 runs, 0 failures; full suite 1292 tests, 0 failures, 1 skipped; clean-clone full suite 1292 tests, 0 failures, 1 skipped.
- 2026-10-11 E1a follow-up: (F1) final duration semantics in RmiCallResult and RmiInvoker
  (lookupNanos for a successful lookup only, timeToFailureNanos for the failed stage, LOOKUP or CALL,
  roundTripNanos for completed calls only); (F2) no non-ASCII character left in the rmi main or test
  sources; (F3) this file completed (caller identity, host name, deviations); rmi classes 86 tests
  (5 runs, all green), backend 1292 tests, 1 skipped (1 run); deviations: none.
- 2026-10-11 E1b done: per-node RMI transport: RmiNodeService (registry and NodeEndpoint on one
  listener on ports().rmi(), new endpoint per start/recover, crash/stop unexport with force, close
  accepted connections and wait for the accept thread), LoopbackSocketFactory, RmiSerialFilter (per
  export), RmiHostName, RemoteEndpointLookup (no caching, snapshot), LocalEndpointLookup,
  RmiTransportProperties (udcf.rmi block, add-only); classifier gains CONNECTION_LOST and
  ARGUMENTS_REJECTED; 9 new test classes (67 tests) plus 9 tests in 2 E1a test classes; rmi classes
  162 tests, 5 Windows runs and 5 Linux Docker runs (run 5 of each in reverse alphabetical order),
  all green; crash-cycle verdict tally per run {CONNECTION_LOST=60}, or 58-59 LOST with 1-2
  CONNECTION_REFUSED; backend 1368 tests, 1 skipped (3 runs, identical; baseline 1292), frontend not
  run (no frontend change); deviations: the ten listed under "E1b deviations from the approved plan".
  Linux (Docker, --cpus=2), run by Jai: 162 rmi tests x 5 runs plus 1 reverse-order run, 0 failures; full suite 1368 tests, 0 failures, 1 skipped; clean-clone full suite 1368 tests, 0 failures, 1 skipped.
- 2026-10-12 E1c done: RmiModule (lab 1) and RmiController under /api/modules/rmi (overview,
  idempotent start, network registry listing, synchronous calls with a remote failure as 200 data,
  local-versus-remote comparisons, one bounded history with sequential ids), 14 DTO records and 2
  enums (null never 0, proved by reflection), 5 event types with no extra Lamport tick (CALL_SUCCEEDED carries
  whole-ms latencyMillis for link L5), RmiMetrics (4 MetricNames constants, node_id = caller),
  lookupBasis verified in the JDK source and by RmiLookupBasisTest, 34 contract fixtures and a README
  captured from two real backends (including a real TIMED_OUT and a real 409 busy); 7 new test
  classes, 78 tests (5 runs, run 5 in reverse alphabetical order, all green), plus one added line
  in CoreContextTest and three in ModuleControllerTest; backend 1446 tests, 1 skipped (3 runs plus
  1 reverse-alphabetical run, identical; baseline 1368), no listener left on 8080, 1101-1105 or
  28750-28799 after each run; frontend not run (no frontend code changed; the fixtures are data);
  deviations: the six listed under "E1c deviations from the approved plan".
  Linux (Docker, --cpus=2), run by Jai: 78 new tests x 5 runs plus 1 reverse-order run, 0 failures; full suite 1446 tests, 0 failures, 1 skipped; clean-clone full suite 1446 tests, 0 failures, 1 skipped; clean-clone frontend lint, build and tests pass.
- 2026-10-13 H1 done: no SO_REUSEADDR in ReplicationNodeService or ReplicationClientTest (JDK default
  kept, proved by a Windows and Linux probe), ASCII-only RmiNodeServiceTest (`\u20ac`); replication
  package 209 tests x 5 runs plus 1 reverse-alphabetical run, RmiNodeServiceTest 18 tests x 5 runs,
  all green; backend 1446 tests, 1 skipped (1 run; baseline 1446), frontend not run (no frontend
  change); deviations: none (ReplicationClientTest added by approval, Q1).
- 2026-10-13 E1d done: Experiment 1 page at /experiments/1-rmi on the E2d kit (node map with the last call and its
  failure kind, registry list, call and comparison results with n/a for nulls and the backend's notes, round-trip
  chart with failure markers, history with details, start on mount and after a reset, per-request timeouts 20 s and
  25 s, every 400/404/409, timeout and network error with its own message); 11 new test files, 109 tests on the real
  fixtures; backend not changed (1446 tests at H1), frontend 785 tests (baseline 676), lint and build pass; deviations:
  the six listed under "E1d deviations from the approved plan".
- 2026-10-13 E9a done: pure MPI collectives model in com.udcf.modules.mpi (CollectiveEndpoint contract and its thread-safe
  CollectiveEndpointLogic with a bounded store, CollectiveTransport seam with TransferException, CollectiveExecutor for
  linear broadcast, scatter and gather with ranks from live participants, block distribution, LOCAL root part, failures
  and unmatched replies as data, deadline with NOT_ATTEMPTED, measured latency and separate total, PayloadSizer sizes,
  Lamport send and receive rules, verification from received data, reproducible SplitMix64 data and SHA-256 checksums),
  MpiProperties (udcf.mpi, add-only YAML blocks), rmi RemoteFailureDetail (add-only), ScriptedNanoClock public; follow-up
  F1 added TransferRecord.timeToFailureNanos for FAILED transfers; 9 new test classes, 75 tests (5 runs plus 1
  reverse-alphabetical run, all green); backend 1521 tests, 1 skipped (1 run; baseline 1446), frontend not run (no
  frontend change); deviations: the six listed under "E9a deviations from the approved plan".
- 2026-10-13 E9b done: CollectiveEndpoint exported by each node's RmiNodeService beside NodeEndpoint on the same
  registry, port and factory through the add-only RmiExtraExport seam (CollectiveEndpointExport bean, fresh logic on
  every start and recovery, rollback and crash cleanup as in E1b), per-object filter (CallContext and double[] up to
  max-payload-elements), RmiCollectiveTransport (fresh typed lookup per transfer, LOOKUP/CALL stages, LATENCY_BASIS),
  access for E9c (RmiCollectiveTransport.of, CollectiveEndpointExport.current), observed failure matrix and end-to-end
  broadcast, scatter and gather at 100000 doubles over real RMI; 5 new test classes and 5 added tests, 39 tests; mpi and
  rmi packages 354 tests (5 runs plus 1 reverse-alphabetical run, all green); backend 1560 tests,
  1 skipped (1 run; baseline 1521), frontend not run (no frontend change); deviations: the six listed under "E9b
  deviations from the approved plan".
- 2026-10-13 E9c done: MpiModule (lab 9) and MpiController under /api/modules/mpi (overview, idempotent start, one
  collective per request with COMPLETE/PARTIAL/FAILED/REFUSED as 200 data, bounded history without vectors), root rule
  REQUESTED > LEADER (read-only Cluster.leaderId, TODO(L1)) > LOWEST_LIVE_ID with rootSource, 10 DTO records and the
  RootSource enum (null never 0), 5 event types with no extra Lamport tick, MpiMetrics (3 MetricNames constants added,
  existing MPI_MESSAGES_TOTAL used, node_id = root), timeout bound computed from properties, 28 contract fixtures and a
  README captured from the real backend (including a real PARTIAL for a never-started node and a real 409 busy); 8 new
  test classes, 63 tests (5 runs plus 1 reverse-alphabetical run, all green); backend 1623 tests, 1 skipped (1 run;
  baseline 1560), frontend not run (no frontend code changed; the fixtures are data); deviations: the five listed under
  "E9c deviations from the approved plan".
- 2026-10-13 E9d done: Experiment 9 page at /experiments/9-mpi on the E2d kit (fan-out and fan-in map of the shown
  collective with failed, not-attempted and skipped nodes, node table with RMI state and kept ids, full collective
  result with n/a for every null, latency per rank with no zero bars, history from the list itself, start on mount and
  on the edge, POST timeout = recommendedMinClientTimeoutMillis + 3000 ms, crash and recover, every 400/404/409,
  timeout and network error with its own message); 11 new test files, 131 tests on the real fixtures plus listed
  synthetic copies; backend not run (no backend change; 1623 tests at E9c), frontend 916 tests (baseline 785), lint and build pass;
  deviations: the four listed under "E9d deviations from the approved plan".
- 2026-10-13 E10a done: pure Experiment 10 model in com.udcf.modules.matrix (MatrixGenerator on E9's PayloadGenerator
  with derived seeds for A and B, MatrixLimits with the exact floor-sqrt maximum from udcf.mpi.max-payload-elements and
  four refusal reasons, RowPartition on E9's BlockPartition, one MatrixMultiply code path for sequential and blocks with
  bitwise equality proved, MatrixAssembly and MatrixVerification with holes as null never passed, MatrixTiming with
  measured and derived figures and warmupProducts, SpeedupSeries, SpeedupBasis on the JVM's available processors),
  MatrixProperties (udcf.matrix, add-only YAML blocks); 11 new test classes, 81 tests; backend 1704 tests, 1 skipped
  (1 run; baseline 1623), frontend not run (no frontend change); deviations: the five listed under "E10a deviations
  from the approved plan".
- 2026-10-14 E10b done: parallel matrix run over RMI in com.udcf.modules.matrix (MatrixEndpoint/MatrixEndpointLogic
  exported by MatrixEndpointExport as a third RmiExtraExport, compute on each node's Experiment 2 executor through
  NodeExecutor with abandoned-task cancellation, RmiMatrixTransport, MatrixDispatchPool for concurrent computes,
  ParallelMatrixExecutor with one run deadline, failures as holes, MatrixRunResult, MatrixRunBudget 26001 ms,
  MatrixRunProperties warmup-products/compute-timeout-millis/result-store-size); add-only block unit in mpi
  (BlockPartition.of(n, p, unit), CollectiveRequest.blockUnit, CollectiveExecutor 3 lines), MpiModule.EXCHANGES_PER_TRANSFER
  public, MatrixRefusal.ROOT_UNAVAILABLE; 13 new test classes, 76 tests; backend 1780 tests, 1 skipped (3 runs; baseline
  1704), frontend not run (only a fixtures README line changed); deviations: the seven listed under "E10b deviations from
  the approved plan".
