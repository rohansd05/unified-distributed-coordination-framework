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
- [ ] E1d — page and end-to-end check. Needs: E1c, E2d.

---

## Experiment 9 — MPI Collectives (Phase 11, moduleId: mpi)

- **Reuse:** none.
- **Special rules:** collectives run over the Exp 1 RMI layer; the root comes from a module-local selector (TODO(L1)); a crashed node is reported, and the operation never hangs.

### Steps
- [ ] E9a — broadcast, scatter and gather logic. Needs: E1d.
- [ ] E9b — `CollectiveEndpoint` over RMI on every node. Needs: E9a, E1b.
- [ ] E9c — `MpiModule` (lab 9) and its API. Needs: E9b.
- [ ] E9d — page and end-to-end check. Needs: E9c, E2d.

---

## Experiment 10 — Parallel Matrix Multiplication (Phase 12, moduleId: matrix)

- **Reuse:** none.
- **Special rules:** scatter row blocks of A, broadcast B, compute each block on that node's Exp 2 executor, gather; check the result against the sequential product (checksum); cap the size in the public profile; report speedup honestly (bounded by the machine's cores).

### Steps
- [ ] E10a — matrix generation, row-block partitioning and the sequential product. Needs: E9d.
- [ ] E10b — the parallel multiply over the collectives and executors. Needs: E10a, E9b, E2b.
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
