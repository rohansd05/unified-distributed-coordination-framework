# Experiment 8 — Fault Tolerance with Primary-Backup Replication

The fault: losing the node that accepts every system update. Without protection that one
failure stops all updates and destroys whatever the primary alone was holding.

## Run it

```powershell
cd legacy-demos\exp08-fault-tolerance
javac -d target\classes src\main\java\com\udcf\sync\LamportClock.java src\main\java\com\udcf\fault\*.java src\main\java\com\udcf\demo\*.java
java -cp target\classes com.udcf.demo.FaultToleranceDemo
```

Windows Firewall will prompt because Java binds TCP ports 7401-7403 — click **Allow
access** for Private networks. The run takes roughly 30 seconds.

## The cluster

| Node | Role at start | TCP port |
|---|---|---|
| Node 1 | BACKUP | 7401 |
| Node 2 | BACKUP | 7402 |
| Node 3 | PRIMARY | 7403 |

The highest identifier leads, the same rule used by the Bully algorithm in Experiment 4.

## What the run does

1. **Synchronous run.** A client streams system updates. The primary is killed *while
   updates are still flowing*. Detection, promotion and recovery times are measured, and
   every confirmed update is checked against the surviving cluster.
2. **Asynchronous run.** The identical crash with the identical traffic, so the data loss
   each model causes can be compared directly.
3. **Recovery.** In both runs the dead primary is restarted. It comes back still believing
   it is primary, discovers a higher epoch, demotes itself and resynchronises.

## The three mechanisms

**Heartbeats.** Backups ping the primary every 300 ms. Silence beyond 1200 ms is treated
as failure. Nothing announces a crash, so failure can only ever be inferred.

**Promotion.** On detection, a backup probes every node with a higher identifier. If none
answers, it promotes itself. This is the Bully rule reused, and it guarantees exactly one
node promotes without a full election round.

**Epochs.** The counter that makes the whole thing safe. It increases by one on every
promotion, and every update carries the epoch of the primary that accepted it. A backup
refuses any update whose epoch is older than the newest it has seen, and a recovering node
that sees a higher epoch steps down. This is the same mechanism Raft calls a *term*.

## Design decisions worth defending

**Split-brain is prevented by the backups, not by the old primary.** The protection does
not rely on the failed node noticing it was replaced. Even a primary that never realises
anything happened cannot write, because nobody will accept its stale epoch. Security that
depends on a failed component behaving well is not security.

**Data loss is measured, not described.** The client records every update it was *told*
had succeeded. At the end those keys are checked against the surviving primary's store.
Anything confirmed but absent is real, silent data loss — the worst kind, because the
client already moved on believing it was safe.

**The crash happens mid-stream.** Killing an idle primary proves nothing. The outage is
only measurable against live traffic, and in-flight updates are exactly what asynchronous
replication loses.

**The asynchronous delay is simulated and labelled.** On one machine replication finishes
in well under a millisecond, so a crash would almost never catch anything in flight and
the comparison would show no difference. `ASYNC_REPLICATION_DELAY_MS` stands in for real
network latency, and this is stated in the code rather than hidden.

**Detection and failover times do not depend on the replication model.** They are driven
by heartbeat timing. Only the data loss differs — which is the point of running the same
crash twice.
