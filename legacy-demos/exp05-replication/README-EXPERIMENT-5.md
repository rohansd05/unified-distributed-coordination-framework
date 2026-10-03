# Experiment 5 — Data Consistency and Replication Models

Primary-backup replication over TCP, demonstrating synchronous (strong) and
asynchronous (eventual) consistency on a three-node cluster.

## Run it

```powershell
cd backend\udcf-exp5-replication-demo
javac -d target\classes src\main\java\com\udcf\sync\LamportClock.java src\main\java\com\udcf\replication\*.java src\main\java\com\udcf\demo\*.java
java -cp target\classes com.udcf.demo.ReplicationDemo
```

Windows Firewall will prompt on first run because Java binds TCP ports 7101-7103.
Click **Allow access** for Private networks.

## Cluster

| Node | Role | TCP port |
|---|---|---|
| Node 1 | PRIMARY | 7101 |
| Node 2 | BACKUP | 7102 |
| Node 3 | BACKUP | 7103 |

## The five phases

1. **Synchronous replication** — the primary blocks until both backups ACK. Strong
   consistency, higher client latency.
2. **Asynchronous replication** — the primary confirms immediately. The demo reads all
   three replicas straight away to show the backups still holding the old value, then
   reads again after a pause to show convergence.
3. **Backup failure** — Node 3 closes its TCP port. The next write is refused at the
   connection level, recorded as a real replication failure, and the replicas diverge.
4. **Recovery and anti-entropy** — Node 3 restarts stale. The primary pushes its whole
   store; items already current are refused as not newer, so only the missed updates land.
5. **Conflict resolution** — a stale update is delivered out of order and rejected by the
   last-writer-wins rule, leaving the newer value intact.

## Design decisions worth defending

**TCP, not the UDP of Experiment 4.** Replication needs a real acknowledgement: the
primary must know whether a backup stored the update. TCP guarantees delivery and
ordering, and a connection to a crashed backup is refused immediately rather than
vanishing into silence. Experiment 4 wanted the opposite property.

**Conflict resolution uses (lamportTime, nodeId), not wall-clock time.** Two nodes' system
clocks can disagree, so a wall-clock comparison could let a stale update silently overwrite
a newer one. The Lamport timestamp from Experiment 3 is derived from real message
causality; the node id breaks ties so every replica reaches the same verdict independently.

**One apply() method guards every write.** Local client writes on the primary and
replication messages on the backups both go through `DataStore.apply`, which refuses
anything not newer. The read-compare-write is done inside `ConcurrentHashMap.compute` so
two threads cannot interleave and let a stale value win.

**Anti-entropy needs no bookkeeping.** The primary does not track which updates a backup
missed. It pushes everything, and the backup's own staleness rule discards what it already
has. This is why recovery is a few lines rather than a replication-log replay engine.

**The asynchronous delay is simulated and labelled.** On one machine a replication round
trip takes well under a millisecond, so the eventual-consistency window would be invisible.
`ASYNC_NETWORK_DELAY_MS` stands in for wide-area latency. This is stated in the code
comment rather than hidden, because claiming a naturally visible stale window on localhost
would be false.

## Files

| File | Role |
|---|---|
| `LamportClock.java` | Reused unchanged from Experiment 3 |
| `ConsistencyModel.java` | SYNCHRONOUS / ASYNCHRONOUS |
| `NodeRole.java` | PRIMARY / BACKUP |
| `MessageType.java` | TCP wire protocol |
| `DataItem.java` | Versioned value with the last-writer-wins comparison |
| `DataStore.java` | Replicated key-value store; the consistency rule lives here |
| `ReplicationStats.java` | Measured latency, failures, last sync per backup |
| `ReplicationEvent.java`, `ReplicationEventLog.java` | Causally ordered event log |
| `ReplicationNode.java` | TCP server, primary write path, anti-entropy |
| `ReplicationDemo.java` | Five-phase driver |
