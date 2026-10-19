# UDCF live demo — Friday 2026-10-23 (15 minutes)

This is the script for the live demo. We present the **deployed website**; the laptop is the
fallback. Read it once from start to finish before the day. Every page, button and message named
here exists on `main`. See "Known limitations" at the end for what does not.

| | Deployed site (main run) | Laptop (fallback) |
|---|---|---|
| Open | <https://unified-distributed-coordination-fr.vercel.app> | http://localhost:5173 after `scripts\start-all.ps1` |
| Backend | https://udcf-backend.onrender.com (Render free tier, profile `public`) | http://localhost:8080 (profile `local`) |
| Nodes | **3**: node 1 FAST, node 2 MEDIUM, node 3 SLOW | **5** |
| First leader / after crashing it | **node 3** / **node 2** | node 5 / node 4 |
| Start-up | cold start 1 to 2 minutes (measured 94.6 s; JVM start 78 s) | about 2 minutes to be safe (dry run of 2026-10-19: 6 s) |

The node numbers in this script are for the deployed site. On the laptop, read node 5 for node 3
and node 4 for node 2.

---

## a) What UDCF is, and what the demo shows

UDCF (Unified Distributed Coordination Framework) brings ten distributed-systems lab experiments
together as modules of one system. A single Spring Boot process hosts a cluster of nodes (3 on the
public site, 5 on the laptop), and each node owns real sockets on 127.0.0.1 (RMI, UDP and TCP). One
React UI shows and drives all ten experiments. The demo shows that the experiments work together,
not just side by side. Experiment 4 elects one leader, and that leader also becomes the Berkeley time
daemon (Exp 3), the replication primary (Exps 5 and 8), the MapReduce coordinator (Exp 7) and the MPI
and matrix-multiply root (Exps 9 and 10). When we crash the leader, a new election runs and every one
of those roles follows the new leader, which you can watch on one timeline. Five guided scenarios
then run real failures on the real cluster, narrate each step and report what they measured.

---

## b) Before the demo, on the deployed site (T-30 to T-0)

| When | Do this | Check |
|---|---|---|
| T-30 | **Laptop, as the fallback** (see section g): plug in the charger, `git checkout main`, `git pull origin main`. Do not start anything yet. Optionally start Monitoring now (section h), because its first build takes several minutes. | "Already up to date" or a clean fast-forward. |
| T-15 | **Open the site:** <https://unified-distributed-coordination-fr.vercel.app>. If the backend was asleep, a banner reads "The backend is waking up — free hosting sleeps after 15 minutes without visitors. This takes 1 to 2 minutes." with a seconds counter. **Keep this one tab open for the whole demo:** its live connection keeps the backend awake. | The banner goes away, and the top bar shows Mode "public", "Nodes up 3/3" and "Leader: None elected". If the site is not up after 3 minutes, switch to the laptop (section g). |
| T-14 | **Browser tabs** (same window, in this order): Overview `/` · Cluster `/cluster` · Timeline `/timeline` · Scenarios `/scenarios` · Exp 4 `/experiments/4-election` · Exp 3 `/experiments/3-clocksync` · Exp 7 `/experiments/7-mapreduce` · Exp 9 `/experiments/9-mpi` · Exp 8 `/experiments/8-faulttolerance` · Exp 5 `/experiments/5-replication` (all on the site's address). | Every tab loads; no "Backend unreachable". |
| T-12 | **Dry run:** on Scenarios, run "The leader dies mid-write". | "Passed: all 6 steps met their expectations." (It passed on the site in 6.1 s from the smoke script and 7.2 s from the UI on 2026-10-19.) |
| T-10 | **Clean slate:** on Overview click **Reset cluster** and confirm. If it answers that a module is busy, wait a few seconds and click again (or run `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\reset-cluster.ps1 -Api https://udcf-backend.onrender.com`). | The top bar shows "Leader: None elected" again. |
| T-5 | **Warm-up** (after a reset nobody holds a role until a module asks for the leader): on Exp 4 leave **Bully** selected and click **Start election**. Then on Exp 3 open the **Berkeley physical sync** tab and click **Run synchronization round**; on Exp 7 click **Run** (default job and input); on Exp 9 click **Run collective** (leave the root on "Default"); on Exp 8 click **Start experiment 8**. Do not start the Exp 8 update stream: the public profile caps it at 200 updates per reset. | Each action succeeds. The election elects **node 3**, because Bully picks the highest live node. |
| T-3 | **Check:** Overview shows 3 nodes up. In "How the ten experiments work as one system", the middle reads "leader: node 3", and every role holder shows "node 3 matches" (a tick). On Cluster, node 3 shows LEADER, PRIMARY. | If a row reads "not taken yet", repeat that experiment's warm-up action. Do **not** reset again: a reset clears the warm-up. |
| T-1 | Close other apps (chat, mail, updates). Turn on Do Not Disturb. Zoom the browser so the Overview map fills the projector. | |
| T-0 | Go to the Overview tab. | |

---

## c) Run order (deployed site)

Timings: no full walkthrough has been timed yet, so every duration below is an **estimate**. Replace
them with your own times after one full rehearsal. Speakers are suggestions, based on who built each
part. The site runs on 0.1 CPU, so everything is slower than on the laptop: wait calmly for each
step; every scenario wait has a deadline.

**Resets between parts.** No manual reset is needed during the demo. Parts 1 to 3 use the warm-up
state from section b. Every scenario starts with its own clean cluster reset: the Scenarios page says
"Each run starts with a clean cluster reset and leaves the cluster as it ends". It retries for up to
20 s while a module is busy. If a scenario fails before its first step (the reset could not happen),
wait a few seconds and click Run again. Never reset while a scenario is running: the run fails with
"The cluster was reset during the scenario".

| # | Part | Speaker | Page | What to click | What to say | What the audience should see | Duration |
|---|---|---|---|---|---|---|---|
| 1 | Integration map | Nidhi | Overview | Nothing at first. Then click link **L1**, and one more link (for example L5). | "These are ten lab experiments, but one system: one cluster of three nodes with real sockets. Experiment 4 elects a leader, and five other roles follow it." | The map: ten experiment boxes, the cluster bus with three node dots, six links L1–L6. In the middle "leader: node 3", and every role reads "node 3 matches". The side panel explains the link and its evidence ("Live", or "Wired, verified by <test>"). | 2:00 (estimate) |
| 2 | Crash the leader | Swanand | Cluster | **Crash node 3** on node 3's card. | "We kill the leader on every protocol at once. The other nodes stop hearing its heartbeats and elect a new leader by themselves." | Node 3 turns "Crashed". After a few seconds node 2 shows LEADER, and then PRIMARY. (3.6 s outage in the 9A.6 test run on a laptop; the site was not timed, so allow longer.) | 1:30 (estimate) |
| 3 | The chain on the Timeline | Rohan | Timeline | Choose **Arrival order (bus sequence)**. Click the ringed LEADER_CHANGED marker. | "Every module logs to one event bus. Here is the election, and right after it the new time daemon, MapReduce coordinator, MPI root and replication primary, all node 2." | The leader-chain line: "Node 2 became leader…", then "Then n roles followed it to node 2: time daemon, MapReduce coordinator, MPI root, …". The chain is ringed and linked on the lanes. In arrival order every role change sits after LEADER_CHANGED. | 1:30 (estimate) |
| 4 | "The leader dies mid-write" (6 steps) | Swanand | Scenarios | **Presenter view** on. Then **Run** next to "The leader dies mid-write". | "Updates are being written to the leader when it crashes. We will see detection, election and promotion, and then we count whether any confirmed update was lost." | Six steps go from waiting to running to passed, each with its narration and an observed message: the crash, detection after about 2.8 s of silence, the Bully election, promotion at a higher epoch, and "0 of n confirmed updates were lost". Verdict: "Passed: all 6 steps met their expectations." | 2:00 (estimate) |
| 5 | "Split-brain that never happens" (7 steps) | Swanand | Scenarios | **Run** next to "Split-brain that never happens". | "Now the old leader comes back while node 2 is primary. Watch it step down, catch up and take over again at a higher epoch, with never two primaries at once." | The old leader steps down, copies the primary's store, is elected again, then a planned handover at a higher epoch. The last step reports how many checks were taken, "at most 1 primary at a time and no violation". | 2:00 (estimate) |
| 6 | "A slow node under load" (3 steps) | Nidhi | Scenarios | **Run** next to "A slow node under load". | "Node 3 is the slow node. The same batch goes through four load-balancing strategies on real thread pools. Round robin should finish last, and we measure whether it does." | The node capacities, the four measured finishing times, then "Round robin finished last after n ms", with how many requests each strategy sent to the slow node. **On the site this can fail honestly** (see below). | 1:30 (estimate) |
| 7 | "Analyse the incident with MapReduce" (7 steps) | Rohan | Scenarios | **Run** next to "Analyse the incident with MapReduce". | "The cluster analyses its own incident. After the leader crash, MapReduce, coordinated by the new leader, counts the cluster's own event log by type and by node." | A first job on leader 3, the crash, a new leader and coordinator (node 2), the export of the event log, two counts coordinated by node 2, and the largest counts, including how many events the crashed leader logged. | 1:30 (estimate) |
| 8 | "The leader dies during a matrix multiply" (5 steps) | Jai | Scenarios | **Run** next to "The leader dies during a matrix multiply". Afterwards, click **See in Timeline** if there is time. | "A parallel matrix multiply runs from the leader. We crash it mid-run, report honestly what happened to that run, and run it again from the new leader." | Step 3 states the first run's real outcome (for example partial, with blocks missing). Step 5: the new leader is the root and the result "matches the sequential product". | 1:30 (estimate) |
| 9 | Back to Overview | Rohan | Overview, then Exp 8 | Presenter view off. On Overview, point at the middle and the role marks. Then on Exp 8, **Start experiment 8**, and back to Overview. | "After all of that, one leader, node 2, and every role sits on it." | "leader: node 2". Exps 3, 7, 9 and 10 read "node 2 matches". Exps 5 and 8 read "not taken yet" (dash) until Start experiment 8, then "node 2 matches" too. | 1:00 (estimate) |

**Part 6 on the site.** Swanand has not recorded a calibration of this scenario for the site (no step
in `docs/tracks/track-b-swanand.md`), so it is not reliable there. On 2026-10-19 it passed once from
the smoke script (17.2 s) and failed once from the UI at step 3: round robin 2400 ms, weighted round
robin 2006 ms, least connections 2595 ms, least response time 2398 ms. Choose one before the demo:
- **Run it on the site** and, if step 3 fails, leave it on screen and say: *"On this free server all
  three nodes share one tenth of a CPU. The shared CPU, not the slow node's single thread, is the
  bottleneck, so the four strategies converge. The scenario measures this rather than assuming it, so
  it reports a failure. On the laptop, where each node gets real CPU time, round robin finishes last."*
- **Or run it on the laptop** if the fallback is already running (section g); there node 3 is still
  the SLOW node.
- **Or cut it** (it is first in the cut order, section d).

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
1. "A slow node under load" (part 6). It is the least connected to the leader story, and it can fail honestly on the site.
2. "The leader dies during a matrix multiply" (part 8). Part 7 already shows a role following the new leader. On the site it is the slowest scenario (size 100 on 0.1 CPU).
3. "Split-brain that never happens" (part 5). Mention the epoch rule in one sentence instead (see Q&A).

Never cut parts 1–4 or 9: they are the core of the story.

Monitoring (section h) and the examiner checks (section f) are outside the 15 minutes: show them
when asked, or in place of part 6.

---

## e) Recovery: when something goes wrong

| Problem | What to do | What to say | How long |
|---|---|---|---|
| **The site shows the waking banner** (the backend was asleep) | Wait; keep the tab open. If it is not up after 3 minutes, switch to the laptop (section g). | "The free server sleeps when nobody uses it; it is starting the cluster now." | 1 to 2 minutes (measured 94.6 s) |
| **A scenario step fails, or the verdict is "Failed at step n: …"** | Leave it on screen and read the failed step's message aloud. Optionally run it once more. Each run resets the cluster first. | "That is an honest result. The scenario checks what really happened and reports it; it never assumes success." For "A slow node under load", see part 6. | 0:30, or the run time again if repeated |
| **A reset says a module is busy** (409, for example the recovery election right after a node recovers) | Wait a few seconds and reset again. From PowerShell: `scripts\reset-cluster.ps1 -Api https://udcf-backend.onrender.com` (on the laptop without `-Api`). It waits until no module is busy (up to 60 s), then retries (up to 40 tries, 500 ms apart). | "A module is still busy, for example with an election. The reset waits for it to finish." | Usually a few seconds; at most about 80 s |
| **"Live updates disconnected, retrying"**, "Reconnecting…" in the top bar, or a CORS or WebSocket error in the browser console | Refresh the page (F5) in the same tab. If it is still broken, or the top bar says "Backend unreachable", switch to the laptop (section g). | "The browser lost its live connection; a refresh reconnects it." | 0:10 |
| **A scenario seems stuck** | Wait; do not reset (a reset during a run fails it). Every wait of a step is bounded: 30 s by default, 60 s for the two matrix-run steps. The crash call itself can block up to about 55 s in the worst case while the node's services close. A run always ends as Passed or Failed by itself. | "It is waiting for the cluster to confirm the next step. Each step has a deadline, so it will tell us the outcome either way." | Usually seconds; worst case about 1.5 min for one step |
| **The laptop backend or frontend died** (fallback only) | `scripts\stop-all.ps1`, then `scripts\start-all.ps1`. All state is in memory, so redo the warm-up if you are before part 4; the scenarios need none. | "We restart the cluster; it holds no database, so it starts clean." | About 30 s (estimate). Up to 2 min per server before start-all gives up and shows the log. |
| **The site and the laptop both fail** | Walk through section c and the Q&A on the slides or this file. | | Skip to Q&A |

---

## f) Examiner checking experiments (deployed site)

One action per experiment, with default inputs. Each page has the same sections: Controls, Live
visualisation, Measurements, Event log and What to notice. Pages for Exps 1, 9 and 10 start their
service by themselves when opened ("Try again" appears only if starting fails).

| Exp | Page | Click | What it shows |
|---|---|---|---|
| 1 RMI | `/experiments/1-rmi` | **Invoke call** | A "Result" box, the diagram "Nodes and the last call", and the measured "Last round trip" and "Last registry lookup". |
| 2 Multithreading | `/experiments/2-multithreading` | **Send batch** | The live executor view and request table; "Busy threads", "Queued", "Completed tasks", "Throughput", "Average latency", "p95 latency". |
| 3 Clock sync | `/experiments/3-clocksync` | Tab **Berkeley physical sync**, then **Run synchronization round** | "Berkeley clock synchronization" with "Spread before:", "Spread after:" and "Target average:". The drift is simulated and carries the "Simulated" badge. |
| 4 Election | `/experiments/4-election` | Algorithm **Bully** (default), **Start election** | The "Election result" panel (node 3 wins on the site), the election diagram and "Leader changes (cluster log)". |
| 5 Replication | `/experiments/5-replication` | **Write** (default consistency model) | The "Write result" panel, "Who is primary" and "Replication health". |
| 6 Load balancing | `/experiments/6-loadbalancing` | **Run** (or **Compare all four**) | The balancer flow, "Finish time", "Average latency", "p95 latency", "Reroutes", "Request spread"; with Compare all four, a table and a finding sentence. |
| 7 MapReduce | `/experiments/7-mapreduce` | **Run** (default job and input) | The pipeline diagram, "Results" and "Every task attempt". |
| 8 Fault tolerance | `/experiments/8-faulttolerance` | **Start experiment 8** | "Experiment 8 started: the first primary is being appointed.", the failover timeline and the data-loss panel. Then **Crash primary** shows a failover. |
| 9 MPI | `/experiments/9-mpi` | **Run collective** (root "Default") | A "Result" box, the diagram "The root and its transfers", and its "Verification" section. |
| 10 Matrix | `/experiments/10-matrix` | **Run** | "Per node" results and "Verification against the sequential product" with "Checksums match"; "Speedup by size". |

---

## g) Fallback: the laptop

**Switch when** the site is not up 3 minutes after opening it, or when actions keep failing
(repeated errors, or "Backend unreachable" after a refresh). Do not debug on stage.

From the repository root, in Windows PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all.ps1
```

1. It checks Java 21, Node 22 and the free ports 8080 and 5173, then prints "ready after n s" twice
   and "UDCF demo is running.", and opens http://localhost:5173. Allow about 2 minutes (the dry run
   of 2026-10-19 took 6 s; a first start on a fresh machine takes longer while Maven downloads).
   If Monitoring (section h) is already running, skip this: http://localhost:5173 is the same app
   with five nodes; use `scripts\compose-down.ps1`, not `stop-all.ps1`, to stop it.
2. Open the same tabs as in section b, on http://localhost:5173.
3. Redo the warm-up (section b, T-5). Bully elects **node 5**.
4. Continue the run order (section c) where you left off, with **five** nodes: read node 5 for
   node 3 (the first leader) and node 4 for node 2 (the leader after the crash). The SLOW node is
   still node 3.

If a reset is needed on the laptop: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\reset-cluster.ps1`
("Cluster reset: HTTP 204 on try n of 40."). Logs for a post-mortem: `%TEMP%\udcf-demo\backend.log`
and `%TEMP%\udcf-demo\frontend.log` (overwritten at every start).

---

## h) Monitoring (laptop only)

The public site has no Prometheus or Grafana, so Monitoring is shown from the laptop with Docker
Compose (Swanand's 15.1). Docker Desktop must be running, and the dev-mode demo must be stopped
(`scripts\stop-all.ps1`): both use ports 8080 and 5173.

```powershell
# Build, start and wait for backend, frontend, Prometheus and Grafana (the first build takes several minutes)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\compose-up.ps1
```

It ends with "UDCF is running in Docker." and the URLs. Grafana: <http://localhost:3000/d/udcf-cluster/udcf-cluster>
(dashboard "UDCF cluster", anonymous viewer, refresh 5 s). The UI is http://localhost:5173 with
**five** nodes.

| Step | Do | Show on the dashboard |
|---|---|---|
| 1 | Open the dashboard. | Row "Cluster overview": **Node status** (nodes 1–5 Up) and **Events per second**. Say: "Prometheus scrapes the one backend every 5 seconds; every series is tagged with the node it belongs to." |
| 2 | In the UI, run the Exp 6 page (**Run**) or the Exp 2 page (**Send batch**). | Row "Multithreading (Experiment 2)": **Completed requests per second**, **Active worker threads**, **Queued requests**, per node. Thread caps follow capacity (FAST 4, MEDIUM 2, SLOW 1). |
| 3 | In the UI, Cluster page: crash the node marked LEADER (node 5). If no node is marked LEADER yet, first click **Start election** on the Exp 4 page. | Within about 10 s: **Node status** shows node 5 Down; in row "Election (Experiment 4)", **Elections won by trigger** steps up with `LEADER_FAILURE` for the new leader (node 4), and **Election duration (recent max)** shows the measured round. |
| 4 | Recover node 5 on the Cluster page. | **Node status** returns to Up. Say: "The UI never depends on Grafana: its charts read the REST API, so they also work on the public site." |

Panels show "No data" until their experiment has run once since the backend started; that is real,
not a fault. Afterwards:

```powershell
# Stop and remove the containers
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\compose-down.ps1
```

It ends with "Done: no UDCF containers are left."

---

## i) Q&A crib

- **Why one JVM with real sockets?** Each node is a separate component with its own ports, clocks and threads, co-hosted in one process. Every message between nodes still goes through the operating system's network stack on 127.0.0.1 (RMI, UDP, TCP), so crashes and timeouts are real; one process just keeps the demo to one command, and fits the free hosting.
- **Why 3 nodes on the site and 5 on the laptop?** The free server has 512 MB and 0.1 CPU, so the `public` profile runs 3 nodes with smaller limits. Nothing needs a quorum or five nodes: modules and scenarios read the live nodes and the leader at run time.
- **Why UDP for clock sync and election, TCP for the rest?** Clock polls, election messages and heartbeats are small and periodic, and the protocols already handle a lost message with timeouts. Replication, load-balanced requests, MapReduce tasks and Experiment 8's failover updates need reliable, ordered delivery and replies, so they use TCP.
- **How is split-brain prevented?** With epochs. Each promotion raises the epoch, and a backup refuses writes from a primary with a lower epoch than its own. A returning old leader learns the higher epoch, steps down, catches up, and can only take over again through a handover at an even higher epoch.
- **Why Lamport ordering on the Timeline?** Nodes share no global clock, so events are ordered by (Lamport time, node id), which respects cause and effect between messages. The "after LEADER_CHANGED" guarantee comes from the bus sequence, not from Lamport order: LEADER_CHANGED carries the cluster clock and each role change the new holder's clock. That is why we switch to "Arrival order (bus sequence)" in part 3.
- **What is simulated, and why?** The Berkeley clock drift (Experiment 3): all nodes run on one machine with one physical clock, so the drift is modelled and carries a "Simulated" badge on the page. The I/O-wait workload type in Experiment 2 (a sleep) carries the same badge. Everything else (messages, crashes, elections, timings) is real.
- **Why does MapReduce read the cluster's own event log?** It is real data the system produces itself (link L5): every module's events become text lines that Experiment 7 counts. It turns the incident we just caused into something to analyse.
- **Why does Experiment 10 gather results by RMI fetch, not Experiment 9's GATHER?** Scatter and broadcast go through Experiment 9's collectives over Experiment 1's RMI. The computed blocks of C are collected by an RMI fetch from each node, a design choice made when Experiment 10 was built. It is still RMI end to end.
- **Why does the top bar say "Leader: None elected" at first?** Elections start on demand: reading the state never starts one. The first action that needs a leader elects one.

---

## Known limitations

- **Cold start on the site:** 1 to 2 minutes after 15 minutes without visitors (measured 94.6 s; JVM start 78 s). Hence T-15 and one tab kept open.
- **Roles are empty after any reset or wake-up.** No node holds a role until a module asks for the leader, and a role-change event appears only for a module that acted since the last reset. Hence the warm-up in section b.
- **"A slow node under load" is measured,** so on the site (one shared 0.1 CPU) or a heavily loaded laptop round robin may not finish last, and step 3 fails honestly with the measured times.
- **The first matrix run's outcome depends on timing** (partial, failed, or already finished before the crash). Step 3 reports whichever really happened.
- **A crash can take long:** in the worst case `crashPrimary` blocks for up to about 55 s while the node's services close. The usual time is milliseconds.
- **Scenario runs are kept in memory only** (the last 10) and are lost when the backend restarts; Render may also restart the free service at any time.
- **Monitoring is laptop only;** the site has no Grafana, and its Monitoring page is hidden.
- **Timings in section c are estimates** until a full rehearsal is timed; failover on the site was not timed.
