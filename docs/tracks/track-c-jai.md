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
- [ ] E5b — replication NodeService on `ports().replication()` (710k): sync and async writes, anti-entropy; document its public API under "Interfaces for other tracks" (Track B builds Exp 8 on it). Needs: E5a.
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
