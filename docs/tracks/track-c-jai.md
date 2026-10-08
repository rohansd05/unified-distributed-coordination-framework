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
- [ ] E5c — `ReplicationModule` (lab 5): write (sync/async), read per replica, crash or recover a backup, anti-entropy, inject a stale update; the health table; metrics; fixtures. Needs: E5b.
- [ ] E5d — page and end-to-end check. Needs: E5c, E2d.

---

## Experiment 1 — Client-Server via Java RMI (Phase 10, moduleId: rmi)

- **Reuse:** none.
- **Special rules:** `java.rmi.server.hostname=127.0.0.1`; crash unexports the RMI objects.

### Steps
- [ ] E1a — the `NodeEndpoint` remote interface (ping, nodeInfo, echo, computeHash(rounds)), its implementation, and a local-versus-remote timing helper. Needs: E5d.
- [ ] E1b — an RMI registry per node on `ports().rmi()` (110k); export on start and recover, unexport on crash. Needs: E1a.
- [ ] E1c — `RmiModule` (lab 1): invoke a method on a node (result, round-trip time, payload size, stub class), the local-versus-remote comparison, registry listing. Needs: E1b.
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
  so it does not repair the primary itself; E5c must decide how the page shows or repairs that (for
  example, anti-entropy from the most up-to-date replica).
- E5b: the close waits are bounded at 5 s; reaching a bound throws `IllegalStateException` from
  `crash()`/`stop()`, which `ClusterNode` records as a crash failure. The tests prove the waits on
  every run but cannot prove a bound is never reached under extreme load.
- E5b: the Linux accept/close behaviour is checked by five Docker runs
  (`maven:3.9-eclipse-temurin-21`, `--cpus=2`) and CI, not on a real Linux host; Docker wrote
  `backend/target` during those runs.

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
