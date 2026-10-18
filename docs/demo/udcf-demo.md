# UDCF live demo — Friday 2026-10-23 (15 minutes)

This is the script for the live demo. Read it once from start to finish before the day. Every page,
button and message named here exists on `main`; see "Known limitations" at the end for what does not.

---

## a) What UDCF is, and what the demo shows

UDCF (Unified Distributed Coordination Framework) brings ten distributed-systems lab experiments
together as modules of one system. A single Spring Boot process hosts a cluster of five nodes, and
each node owns real sockets on 127.0.0.1 (RMI, UDP and TCP). One React UI shows and drives all ten
experiments. The demo shows that the experiments work together, not just side by side. Experiment 4
elects one leader, and that leader also becomes the Berkeley time daemon (Exp 3), the replication
primary (Exps 5 and 8), the MapReduce coordinator (Exp 7) and the MPI and matrix-multiply root
(Exps 9 and 10). When we crash the leader, a new election runs and every one of those roles follows
the new leader, which you can watch on one timeline. Five guided scenarios then run real failures on
the real cluster, narrate each step and report what they measured.

---

## b) Before the demo (T-30 to T-0)

| When | Do this | Check |
|---|---|---|
| T-30 | **Computer:** plug in the charger. Java 21 and Node 22 must be installed (`start-all.ps1` checks both). Nothing else may listen on ports 8080 or 5173 (`start-all.ps1` names the process if one does). | |
| T-28 | In PowerShell, at the repository root: `git checkout main`, then `git pull origin main`. | "Already up to date" or a clean fast-forward. |
| T-27 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all.ps1` | "Java 21 OK", "Node 22 OK", "ready after n s" twice, then "UDCF demo is running." The browser opens http://localhost:5173. In the dry run on 2026-10-19 the backend was ready after 6 s and the frontend after 0 s. A first start on a fresh machine can take much longer, because Maven downloads dependencies. |
| T-25 | **Browser tabs** (one window, in this order): Overview http://localhost:5173/ · Cluster http://localhost:5173/cluster · Timeline http://localhost:5173/timeline · Scenarios http://localhost:5173/scenarios · Exp 3 http://localhost:5173/experiments/3-clocksync · Exp 7 http://localhost:5173/experiments/7-mapreduce · Exp 9 http://localhost:5173/experiments/9-mpi · Exp 8 http://localhost:5173/experiments/8-faulttolerance · Exp 5 http://localhost:5173/experiments/5-replication | The top bar shows the live connection and five nodes. |
| T-24 | **Dry run:** on Scenarios, run "The leader dies mid-write". | The verdict reads "Passed: all 6 steps met their expectations." |
| T-20 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\reset-cluster.ps1` | "Cluster reset: HTTP 204 on try n of 40." |
| T-19 | **Warm-up** (after a reset nobody holds a role until a module asks for the leader): on Exp 3 click **Run synchronization round**; on Exp 7 click **Run** (default job and input); on Exp 9 click **Run collective** (leave the root on "Default"); on Exp 8 click **Start experiment 8**. Do not start the update stream: it has a budget of 400 updates per reset. | Each action succeeds. The first one elects node 5, because Bully picks the highest live node. |
| T-15 | **Check:** on Cluster, all five nodes read "Up", and node 5 shows LEADER, PRIMARY. On Overview, the section "How the ten experiments work as one system" shows "leader: node 5" in the middle, and every role holder shows "node 5 matches" (a tick). | If a row reads "not taken yet", repeat that experiment's warm-up action. Do **not** reset again: a reset clears the warm-up. |
| T-5 | Close other apps (chat, mail, updates, other browsers). Turn on Do Not Disturb. Zoom the browser so the Overview map fills the projector. | |
| T-0 | Go to the Overview tab. | |

---

## c) Run order

Timings: no full walkthrough has been timed yet (the 2026-10-19 dry run covered only `start-all`),
so every duration below is an **estimate**. Replace them with your own times after one full rehearsal.
Speakers are suggestions, based on who built each part.

**Resets between parts.** No manual reset is needed during the demo. Parts 1 to 3 use the warm-up
state from section b. Every scenario starts with its own clean cluster reset: the Scenarios page says
"Each run starts with a clean cluster reset and leaves the cluster as it ends". It retries for up to
20 s while a module is busy. If a scenario fails before its first step (the reset could not happen),
run `scripts\reset-cluster.ps1` and click Run again. Never reset while a scenario is running: the run
fails with "The cluster was reset during the scenario".

| # | Part | Speaker | Page | What to click | What to say | What the audience should see | Duration |
|---|---|---|---|---|---|---|---|
| 1 | Integration map | Nidhi | Overview | Nothing at first. Then click link **L1**, and one more link (for example L5). | "These are ten lab experiments, but one system: one cluster of five nodes with real sockets. Experiment 4 elects a leader, and five other roles follow it." | The map: ten experiment boxes, the cluster bus with five node dots, six links L1–L6. In the middle "leader: node 5", and every role reads "node 5 matches". The side panel explains the link and its evidence ("Live", or "Wired, verified by <test>"). | 2:00 (estimate) |
| 2 | Crash the leader | Swanand | Cluster | **Crash node 5** on node 5's card. | "We kill the leader on every protocol at once. The other nodes stop hearing its heartbeats and elect a new leader by themselves." | Node 5 turns "Crashed". After a few seconds (3.6 s outage in the 9A.6 test run; allow up to about 10 s) node 4 shows LEADER, and then PRIMARY. | 1:30 (estimate) |
| 3 | The chain on the Timeline | Rohan | Timeline | Choose **Arrival order (bus sequence)**. Click the ringed LEADER_CHANGED marker. | "Every module logs to one event bus. Here is the election, and right after it the new time daemon, MapReduce coordinator, MPI root and replication primary, all node 4." | The leader-chain line: "Node 4 became leader…", then "Then n roles followed it to node 4: time daemon, MapReduce coordinator, MPI root, …". The chain is ringed and linked on the lanes. In arrival order every role change sits after LEADER_CHANGED. | 1:30 (estimate) |
| 4 | "The leader dies mid-write" (6 steps) | Swanand | Scenarios | **Presenter view** on. Then **Run** next to "The leader dies mid-write". | "Updates are being written to the leader when it crashes. We will see detection, election and promotion, and then we count whether any confirmed update was lost." | Six steps go from waiting to running to passed, each with its narration and an observed message: the crash, detection after about 2.8 s of silence, the Bully election, promotion at a higher epoch, and "0 of n confirmed updates were lost". Verdict: "Passed: all 6 steps met their expectations." | 2:00 (estimate) |
| 5 | "Split-brain that never happens" (7 steps) | Swanand | Scenarios | **Run** next to "Split-brain that never happens". | "Now the old leader comes back while node 4 is primary. Watch it step down, catch up and take over again at a higher epoch, with never two primaries at once." | The old leader steps down, copies the primary's store, is elected again, then a planned handover at a higher epoch. The last step reports how many checks were taken, "at most 1 primary at a time and no violation". | 2:00 (estimate) |
| 6 | "A slow node under load" (3 steps) | Nidhi | Scenarios | **Run** next to "A slow node under load". | "Node 3 is deliberately slow. The same batch goes through four load-balancing strategies on real thread pools. Round robin should finish last, and we measure whether it does." | The node capacities, the four measured finishing times, then "Round robin finished last after n ms", with how many requests each strategy sent to the slow node. This result is measured: on a busy machine it can fail honestly (see e). | 1:30 (estimate) |
| 7 | "Analyse the incident with MapReduce" (7 steps) | Rohan | Scenarios | **Run** next to "Analyse the incident with MapReduce". | "The cluster analyses its own incident. After the leader crash, MapReduce, coordinated by the new leader, counts the cluster's own event log by type and by node." | A first job on leader 5, the crash, a new leader and coordinator (node 4), the export of the event log, two counts coordinated by node 4, and the largest counts, including how many events the crashed leader logged. | 1:30 (estimate) |
| 8 | "The leader dies during a matrix multiply" (5 steps) | Jai | Scenarios | **Run** next to "The leader dies during a matrix multiply". Afterwards, click **See in Timeline** if there is time. | "A parallel matrix multiply runs from the leader. We crash it mid-run, report honestly what happened to that run, and run it again from the new leader." | Step 3 states the first run's real outcome (for example partial, with blocks missing). Step 5: the new leader is the root and the result "matches the sequential product". | 1:30 (estimate) |
| 9 | Back to Overview | Rohan | Overview, then Exp 8 | Presenter view off. On Overview, point at the middle and the role marks. Then on Exp 8, **Start experiment 8**, and back to Overview. | "After all of that, one leader, node 4, and every role sits on it." | "leader: node 4". Exps 3, 7, 9 and 10 read "node 4 matches". Exps 5 and 8 read "not taken yet" (dash) until Start experiment 8, then "node 4 matches" too. | 1:00 (estimate) |

---

## d) Time budget: 15 minutes

| Parts | Minutes (estimate) |
|---|---|
| 1 Overview | 2:00 |
| 2 Cluster crash | 1:30 |
| 3 Timeline | 1:30 |
| 4–8 Scenarios | 8:30 |
| 9 Overview again | 1:00 |
| Buffer | 0:30 |
| **Total** | **15:00** |

If time runs short, cut in this order:
1. "A slow node under load" (part 6). It is the least connected to the leader story, and it can fail honestly on a busy machine.
2. "The leader dies during a matrix multiply" (part 8). Part 7 already shows a role following the new leader.
3. "Split-brain that never happens" (part 5). Mention the epoch rule in one sentence instead (see Q&A).

Never cut parts 1–4 or 9: they are the core of the story.

---

## e) Recovery: when something goes wrong

| Problem | What to do | What to say | How long |
|---|---|---|---|
| **A scenario step fails, or the verdict is "Failed at step n: …"** | Leave it on screen and read the failed step's message aloud. Optionally run it once more. Each run resets the cluster first. | "That is an honest result. The scenario checks what really happened and reports it; it never assumes success." For "A slow node under load": "On a busy laptop round robin is not always last; here are the measured times." | 0:30, or the run time again if repeated |
| **A reset answers 409** (the cluster is busy, for example the recovery election right after a node recovers) | `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\reset-cluster.ps1`. It waits until no module is busy (up to 60 s), then retries the reset (up to 40 tries, 500 ms apart). | "A module is still busy, for example with an election. The reset waits for it to finish." | Usually a few seconds; at most about 80 s |
| **"Live updates disconnected, retrying"** (Timeline or Scenarios), or the top bar shows "Reconnecting…" | Refresh the page (F5). If the top bar then says "Backend unreachable", treat it as the next row. | "The browser lost its live connection; a refresh reconnects it." | 0:10 |
| **A scenario seems stuck** | Wait; do not reset (a reset during a run fails it). Every wait of a step is bounded: 30 s by default, 60 s for the two matrix-run steps. The crash call itself can block up to about 55 s in the worst case while the node's services close. A run always ends as Passed or Failed by itself. | "It is waiting for the cluster to confirm the next step. Each step has a deadline, so it will tell us the outcome either way." | Usually seconds; worst case about 1.5 min for one step |
| **The backend or frontend died** (top bar "Backend unreachable" after a refresh, or a blank page) | `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-all.ps1`, then `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all.ps1`. All state is in memory, so redo the warm-up from section b if you are before part 4; the scenarios need none. | "We restart the cluster; it holds no database, so it starts clean." | About 30 s (estimate; start-all alone took 6 s in the dry run). Up to 2 min per server before start-all gives up and shows the log. |
| **The browser or laptop fails** | Fallback: the tag `demo-2026-10-23` on a second laptop that went through section b the same morning (`git checkout demo-2026-10-23`, then start-all). If no laptop works, walk through section c and the Q&A on the slides or this file. | "We switch to the backup machine." | 1–2 min if the second laptop is already running; otherwise skip to Q&A |

Logs for a post-mortem: `%TEMP%\udcf-demo\backend.log` and `%TEMP%\udcf-demo\frontend.log` (overwritten
at every start).

---

## f) Q&A crib

- **Why one JVM with real sockets?** Each node is a separate component with its own ports, clocks and threads, co-hosted in one process. Every message between nodes still goes through the operating system's network stack on 127.0.0.1 (RMI, UDP, TCP), so crashes and timeouts are real; one process just keeps the demo to one command.
- **Why UDP for clock sync and election, TCP for the rest?** Clock polls, election messages and heartbeats are small and periodic, and the protocols already handle a lost message with timeouts. Replication, load-balanced requests, MapReduce tasks and Experiment 8's failover updates need reliable, ordered delivery and replies, so they use TCP.
- **How is split-brain prevented?** With epochs. Each promotion raises the epoch, and a backup refuses writes from a primary with a lower epoch than its own. A returning old leader learns the higher epoch, steps down, catches up, and can only take over again through a handover at an even higher epoch.
- **Why Lamport ordering on the Timeline?** Nodes share no global clock, so events are ordered by (Lamport time, node id), which respects cause and effect between messages. The "after LEADER_CHANGED" guarantee comes from the bus sequence, not from Lamport order: LEADER_CHANGED carries the cluster clock and each role change the new holder's clock. That is why we switch to "Arrival order (bus sequence)" in part 3.
- **What is simulated, and why?** The Berkeley clock drift (Experiment 3): the five nodes run on one machine with one physical clock, so the drift is modelled and carries a "Simulated" badge on the page. The I/O-wait workload type in Experiment 2 (a sleep) carries the same badge. Everything else (messages, crashes, elections, timings) is real.
- **Why does MapReduce read the cluster's own event log?** It is real data the system produces itself (link L5): every module's events become text lines that Experiment 7 counts. It turns the incident we just caused into something to analyse.
- **Why does Experiment 10 gather results by RMI fetch, not Experiment 9's GATHER?** Scatter and broadcast go through Experiment 9's collectives over Experiment 1's RMI. The computed blocks of C are collected by an RMI fetch from each node, a design choice made when Experiment 10 was built. It is still RMI end to end.
- **What is not part of this demo yet?** The public deployment (Vercel and Render) is refreshed in Phase 16. Docker Compose with Prometheus and Grafana comes in Phase 15. Polish, such as the About page, is Phase 14.

---

## g) If everything goes wrong

From the repository root, in Windows PowerShell:

```powershell
# 1. Stop everything (safe to run twice)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-all.ps1

# 2. Start backend and frontend; opens http://localhost:5173
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all.ps1

# 3. Clean-slate reset of the cluster (waits while a module is busy)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\reset-cluster.ps1
```

Then redo the warm-up (section b, T-19) and go back to the Overview.

---

## Known limitations

- **About page:** it still shows "Arrives in Phase 14". Do not open it during the demo. The Monitoring page is hidden unless `VITE_GRAFANA_URL` is set.
- **Roles are empty after any reset.** No node holds a role until a module asks for the leader, and a role-change event appears only for a module that acted since the last reset. Hence the warm-up in section b.
- **"A slow node under load" is measured,** so on a heavily loaded laptop round robin may not finish last, and the step fails honestly with the measured times.
- **The first matrix run's outcome depends on timing** (partial, failed, or already finished before the crash). Step 3 reports whichever really happened.
- **A crash can take long:** in the worst case `crashPrimary` blocks for up to about 55 s while the node's services close. The usual time is milliseconds.
- **Scenario runs are kept in memory only** (the last 10) and are lost when the backend restarts.
- **Timings in section c are estimates** until a full rehearsal is timed.
- **The tag `demo-2026-10-23`** must be created after this file is merged. Whether it exists was not checked here.
