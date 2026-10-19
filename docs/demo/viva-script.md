# UDCF viva walkthrough (10 minutes)

A word-for-word script for the viva, built from the live demo script
[udcf-demo.md](udcf-demo.md). Words to say are in plain text; what to do is in **[brackets]**. Every
page, button and message named here exists on `main`. Wherever a number appears on screen, read the
number that is shown; the script never assumes one. The timings are estimates until a full rehearsal is
timed.

Background for answers: [docs/architecture/overview.md](../architecture/overview.md) and the ten pages in
[docs/experiments/](../experiments/).

---

## Before the viva

**[Do sections b) "Before the demo" of [udcf-demo.md](udcf-demo.md) from T-30 to T-15: start the app
with `scripts\start-all.ps1`, open the tabs, reset with `scripts\reset-cluster.ps1`, then the warm-up on
Experiments 3, 7, 9 and 8.]**

**[Check: on Cluster all five nodes read "Up" and node 5 shows LEADER, PRIMARY; on Overview the middle
reads "leader: node 5". Do not reset again after the warm-up.]**

**[Open the Overview tab.]**

---

## 0:00 – 1:00 · What UDCF is

**[Overview page, the integration map filling the screen.]**

"Good morning. Our project is UDCF, the Unified Distributed Coordination Framework. The lab has ten
experiments, from Java RMI to parallel matrix multiplication. Normally those are ten separate programs.
We built them as ten modules of one running distributed system.

One Spring Boot process hosts a cluster of five nodes. Each node owns its own real sockets on the
loopback address: RMI for Experiments 1, 9 and 10, UDP for clock synchronization and election, and TCP
for replication, load balancing, MapReduce and failover. So when a node crashes, its sockets really
close, and the other nodes really stop hearing it. One React interface drives all ten experiments."

---

## 1:00 – 2:30 · The integration map

**[Point at the ten experiment boxes, then at the cluster bus with five node dots in the middle.]**

"This map is the heart of the project. Here are the ten experiments, and here is the one cluster they
share. The six lines, L1 to L6, are the links that make it one system."

**[Click link L1.]**

"L1: Experiment 4 elects a leader with the Bully algorithm, and five other roles follow it. The leader
is the Berkeley time daemon of Experiment 3, the replication primary of Experiments 5 and 8, the
MapReduce coordinator of Experiment 7, and the root of the MPI collectives and the matrix multiply in
Experiments 9 and 10. Right now the leader is node 5, and every role shows a tick: it sits on node 5."

**[Point at the role marks. Then click link L5.]**

"The panel shows the evidence for each link: either live data from this cluster, or the integration test
that verifies it. L5, for example: every module writes to one event bus, and Experiment 7's MapReduce can
read that event log as its input. The cluster can analyse its own behaviour."

---

## 2:30 – 3:45 · Crash the leader

**[Switch to the Cluster tab.]**

"Now we break it. Node 5 is the leader and the primary."

**[Click **Crash node 5** on node 5's card.]**

"We kill node 5 on every protocol at once. Nobody tells the other nodes. Each node sends a heartbeat
every 700 milliseconds on the election channel, and a node that is silent for more than 2.5 seconds is
suspected. The node that suspects the leader starts a Bully election."

**[Wait until node 4 shows LEADER and then PRIMARY. Allow up to about 10 seconds.]**

"Node 5 is crashed, and node 4, the highest live node, is now the leader and the primary. We did not
click anything to make that happen."

---

## 3:45 – 5:00 · One timeline for all ten modules

**[Switch to the Timeline tab. Choose **Arrival order (bus sequence)**. Click the ringed
LEADER_CHANGED marker.]**

"Every module logs to one event bus, and every message between nodes carries a Lamport timestamp. By
default this page orders events by Lamport time and node id, which respects cause and effect between
messages.

Here I switched to arrival order, the bus sequence. This line reads: node 4 became leader, and then the
roles followed it to node 4."

**[Read the leader-chain line aloud as shown, for example "time daemon, MapReduce coordinator, MPI root,
…".]**

"Each of those is a separate experiment reacting to the same election. We use arrival order here because
the leader-change event carries the cluster clock while each role change carries the new holder's
clock, so 'after' is guaranteed by the bus sequence, not by Lamport order."

---

## 5:00 – 7:00 · Scenario: "The leader dies mid-write"

**[Switch to the Scenarios tab. Turn **Presenter view** on. Click **Run** next to "The leader dies
mid-write".]**

"A scenario is a scripted experiment on the real cluster. It starts with a clean reset, injects a
failure, and checks what really happened at every step. Nothing is assumed.

Here updates are being written to the leader, synchronously, when we crash it."

**[As the six steps turn from waiting to running to passed, point at each.]**

"Step one: Experiment 8 starts, and the elected leader becomes the replication primary. Step two: the
leader crashes while updates are still being written. Step three: the failure detector suspects it. Step
four: a Bully election among the live nodes. Step five: the new leader copies what it is missing from
the other backups and becomes primary at a higher epoch. Step six: updates are accepted again."

**[Read the observed message of step 6 aloud, for example "0 of n confirmed updates were lost".]**

"Because replication was synchronous, no update the client was told had succeeded was lost."

**[Point at the verdict "Passed: all 6 steps met their expectations."]**

---

## 7:00 – 8:45 · Scenario: "Split-brain that never happens"

**[Click **Run** next to "Split-brain that never happens".]**

"The classic danger in primary-backup replication is split-brain: the old primary comes back, still
believes it is the primary, and now two nodes accept writes.

We prevent it with epochs. Every promotion raises the epoch, and a backup refuses any update with an
epoch lower than its own. So the safety does not depend on the failed node behaving well."

**[Point at the steps as they pass.]**

"The leader crashes and a new primary takes over. Now the old leader comes back. It asks the others for
its role, learns that it is out of date and steps down to a backup. It copies the current primary's
store. Bully prefers the highest node, so it is elected again, and it takes over through a planned
handover: the current primary stops serving first, then the old leader becomes primary at a higher
epoch."

**[Read the last step's observed message aloud: how many checks were taken and "at most 1 primary at a
time".]**

"Throughout the run the scenario checked many times a second how many nodes act as primary. Never more
than one."

---

## 8:45 – 10:00 · Honesty and close

**[Turn Presenter view off. Point at the end state shown under the verdict.]**

"Two last points.

First, what is real and what is simulated. Every message crosses a real socket, and every time, count
and latency on these pages is measured. Three things are simulated, and each is labelled on its page with
a 'Simulated' badge and the reason: the nodes share one machine; Experiment 3's clock drift, because one
machine has only one hardware clock; and the asynchronous replication delay, which stands in for network
latency.

Second, the tests. Each experiment has unit tests for its algorithm and integration tests on real
sockets, and each of the six links has a test that proves it. GitHub Actions runs the backend and
frontend suites on Linux for every pull request.

So: ten experiments, one cluster, one leader that every role follows, and failures you can cause and
measure. Thank you; we are happy to take questions."

**[If the examiner asks to see a single experiment, open its page from the sidebar and follow "How to
run it in the UI" on its page in docs/experiments/.]**

---

## If something goes wrong

Use section e) "Recovery" of [udcf-demo.md](udcf-demo.md). In short:

- **A scenario fails:** leave it on screen and read the failed step aloud. "That is an honest result: the
  scenario checks what really happened and reports it." Optionally run it again; each run resets first.
- **A reset answers 409:** run `scripts\reset-cluster.ps1`; it waits until no module is busy.
- **"Reconnecting…" or a blank page:** refresh with F5. If the backend is gone, run `scripts\stop-all.ps1`
  then `scripts\start-all.ps1` and redo the warm-up.
- **Never reset while a scenario is running:** the run fails with "The cluster was reset during the
  scenario".

---

## Likely examiner questions

1. **Why one process instead of separate machines?**
   Each node is a separate component with its own ports, clock, roles and threads, but they share one JVM
   so the whole system starts with one command. Every message still crosses the operating system's
   network stack on 127.0.0.1, so crashes, refusals and timeouts are real. The co-hosting is labelled as
   simulated.
2. **Why UDP for clock sync and election, but TCP for the rest?**
   Clock polls, election messages and heartbeats are small and periodic, and those protocols already
   treat a missing reply as information via timeouts. Replication, load-balanced requests, MapReduce tasks
   and failover updates need reliable, ordered delivery with a reply, so they use TCP.
3. **State Lamport's rules. Where are they in the code?**
   Tick before a local event or a send; on receipt set the clock to max(local, received) + 1. They are
   `tick()` and `update()` in `core/clock/LamportClock`, and every inter-node message carries the
   sender's time (link L4).
4. **Does L(a) < L(b) mean a happened before b?**
   No. Only the converse holds: if a happened before b, then L(a) < L(b). Concurrent events can have any
   order. We break ties with the node id to get one total order.
5. **How does the Berkeley algorithm work here?**
   The leader is the time daemon. It polls every node over UDP, compensates each reading by half the
   round trip, averages the offsets without outliers, and sends each node its own adjustment. The drift
   itself is simulated and labelled.
6. **Bully or Ring: which does the cluster use, and why?**
   Automatic elections use Bully: it elects the highest live node, and a recovered highest node reclaims
   leadership. Ring is available as a manual algorithm on the Experiment 4 page and skips dead nodes.
7. **How does a node detect a crash?**
   Heartbeats every 700 ms on the election UDP channel; a peer silent for more than 2500 ms is suspected.
   Experiments 4 and 8 react to the same suspicion (link L2).
8. **What deadlock did the Ring algorithm have, and how did you fix it?**
   The ring probe waited for an acknowledgement on the listener thread, which was the only thread that
   could deliver it. Now the listener only decodes messages and hands them to a worker thread.
9. **What is the difference between synchronous and asynchronous replication?**
   Synchronous confirms after the backups answer, so confirmed data survives a primary crash.
   Asynchronous confirms at once and pushes later, so it is faster but can briefly be stale and can lose
   confirmed updates in a crash.
10. **How are conflicting versions resolved?**
    Last-writer-wins on (Lamport time, node id), with the epoch compared first. Every replica applies the
    same rule, and an older out-of-order update is rejected as stale.
11. **How is split-brain prevented?**
    Epochs: each promotion raises the epoch, and backups refuse updates with a lower one. A returning old
    primary learns the higher epoch, demotes itself and resynchronises.
12. **Why did round robin finish last in load balancing?**
    It gives the slow node as many requests as the fast ones, and the batch finishes only when the slowest
    node does. Equal request counts are not equal load. The result is measured, so on a busy machine it can
    differ, and the page reports what it measured.
13. **Why does the MapReduce average job carry sum;count?**
    Averages are not associative, so combining partial averages is wrong. Sums and counts combine
    correctly, and the division happens once at the end.
14. **How do MPI broadcast, scatter and gather differ, and what is the root?**
    Broadcast sends the same data to all ranks; scatter sends each rank its own block; gather collects the
    blocks back at the root. The root is the elected leader unless the request names one; with no leader
    the request is refused with 409, never sent to a fallback node.
15. **Why is the matrix speedup sometimes below 1?**
    Communication (scattering A, broadcasting B, fetching C) costs time the sequential product does not
    pay, and it dominates small matrices. All nodes share one computer's cores, so speedup is bounded by
    the core count. Speedup is reported only for a complete run whose result matched the sequential
    product exactly.
