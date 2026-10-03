# UDCF — Master Project Handoff

> **Read this entire document before doing anything.**
> It supersedes `README.md` and `docs/CLAUDE.md` in the repository. Both are out of date:
> the README describes files that do not exist, and `docs/CLAUDE.md` still says the project
> has not started.

| | |
|---|---|
| Document version | 1.0 |
| Written | 2026-10-03 |
| Repository | `https://github.com/rohansd05/unified-distributed-coordination-framework` |
| Repository HEAD when written | `96540d4` |
| Local path | `C:\Users\NIDHI\Desktop\unified-distributed-coordination-framework` |
| Basis | A read-only Claude Code audit run on 2026-10-03, plus the full history of the previous chat |

---

## Contents

1. [How to use this document](#1-how-to-use-this-document)
2. [The project in one page](#2-the-project-in-one-page)
3. [The ten experiments and how they become one product](#3-the-ten-experiments-and-how-they-become-one-product)
4. [Current repository state (verified)](#4-current-repository-state-verified)
5. [Decision record](#5-decision-record)
6. [Target architecture](#6-target-architecture)
7. [Module specifications — Experiments 1 to 10](#7-module-specifications--experiments-1-to-10)
8. [Frontend specification](#8-frontend-specification)
9. [Monitoring](#9-monitoring)
10. [Deployment](#10-deployment)
11. [Phase plan (resumable)](#11-phase-plan-resumable)
12. [Working agreement with the user](#12-working-agreement-with-the-user)
13. [Environment pitfalls already hit](#13-environment-pitfalls-already-hit)
14. [Deliverables already produced outside the repository](#14-deliverables-already-produced-outside-the-repository)
15. [Open questions and risks](#15-open-questions-and-risks)
16. [Kickoff for the new chat](#16-kickoff-for-the-new-chat)
- [Appendix A — New CLAUDE.md for the repository root](#appendix-a--new-claudemd-for-the-repository-root)
- [Appendix B — Parameters from the existing demos](#appendix-b--parameters-from-the-existing-demos)

---

## 1. How to use this document

**For the assistant in a new chat.** This is the complete project memory. Treat every
decision in Section 5 as agreed. Do not re-open a decision without a strong technical reason,
and if you think one is wrong, say so and ask before acting. Work strictly through the phase
plan in Section 11, one step at a time. The current position is recorded in Appendix A under
"Current Position" — once that file is committed to the repository, the repository copy is the
source of truth.

**For Claude Code.** Appendix A is the new `CLAUDE.md`. Phase 0 places it at the repository
root, which is where Claude Code loads it automatically.

**For the user.** Commit this file to the repository as `docs/HANDOFF.md` during Phase 0, and
upload it to the Claude project's knowledge so every new chat starts with it.

---

## 2. The project in one page

**Name:** Unified Distributed Coordination Framework (UDCF).

**What it is.** A college distributed-systems laboratory project. Ten lab experiments, which
would normally be ten unrelated programs, are built instead as modules of **one running
distributed system** with **one professional web interface**.

**Where it stands.** Seven experiments (2 to 8) exist and work, but only as separate console
demos. Experiments 1, 9 and 10 do not exist yet. There is no frontend, no shared application, no
deployment.

**Definition of done.**

1. One backend application hosts a cluster of nodes, and all ten experiments run on that one
   cluster.
2. One web interface presents each experiment as a feature module with live controls, a live
   visualisation, real measurements and an event log.
3. Experiments are genuinely linked. For example, the leader chosen by Experiment 4 becomes the
   replication primary in Experiments 5 and 8. Section 6.4 lists every link.
4. The whole system — backend, frontend, Prometheus and Grafana — starts locally with one Docker
   Compose command.
5. A public "lite" version is deployed: frontend on Vercel, backend on Render.
6. The UI is polished enough to present in a viva, and every displayed figure is real.

**Team.** Two contributors push directly to `main`. Always pull before starting work and before
pushing.

---

## 3. The ten experiments and how they become one product

| Lab # | Official title | Module id | Status today | Becomes |
|---|---|---|---|---|
| 1 | Client-Server Communication using RPC or Java RMI | `rmi` | Not started | RMI layer on every node; the transport for 9 and 10 |
| 2 | Implementation of Multithreading in Distributed System | `multithreading` | Spring Boot app, 11 tests | Each node's request executor |
| 3 | Implementation of Clock Synchronization (logical/physical) | `clocksync` | Console demo (Lamport only) | Shared Lamport clock on every message, plus Berkeley |
| 4 | Implement Bully and Ring Election Algorithms | `election` | Console demo | Chooses the cluster leader everything else follows |
| 5 | Implementation of data consistency and replication models | `replication` | Console demo | Replicated store on every node, sync and async |
| 6 | Implementation Load Balancing Algorithm | `loadbalancing` | Console demo | Gateway dispatch into node executors |
| 7 | Implementation a basic MapReduce job using Hadoop or Apache Spark | `mapreduce` | Console demo + optional PySpark script | Distributed jobs, including over the cluster's own event log |
| 8 | Simulate simple fault tolerance with primary-backup replication | `faulttolerance` | Console demo | Failover, epochs and split-brain prevention on the replicated store |
| 9 | MPI Collective Communication: Broadcast, Scatter, Gather | `mpi` | Not started | Collectives over RMI, rooted at the leader |
| 10 | Parallel Matrix Multiplication using MPI | `matrix` | Not started | Row-block parallel multiply using the module 9 collectives |

---

## 4. Current repository state (verified)

### 4.1 Environment (Windows 11 Home, 10.0.26200)

| Tool | Version | Note |
|---|---|---|
| Java | OpenJDK 21.0.12.1 (Microsoft build) | Full JDK; `JAVA_HOME` set correctly |
| javac | 21.0.12.1 | On PATH |
| **Maven** | **NOT on PATH** | `~/.m2/repository` holds 130 jars incl. Spring Boot 3.3.5 — Maven ran once, probably through VS Code |
| Node | v22.11.0 | |
| npm | 11.16.0 | Execution policy already fixed (`RemoteSigned`) |
| Git | 2.53.0 | |
| Docker | 29.7.2, Compose v5.4.0 | |
| PostgreSQL | 18.3 | No longer needed (decision R3) |
| Python | 3.11.9 (`python`), 3.14.6 (`python3`) | Only for the optional Spark script |
| Shells | Git Bash and PowerShell 5.1 | Claude Code used Git Bash |

### 4.2 Directory tree (condensed; excludes `.git` and `target`)

```text
./
├── .env  .env.example  .gitignore  README.md  requirements.txt
├── .vscode/settings.json
├── docs/CLAUDE.md                                  ← stale; moved here in commit 88fbaf5
└── backend/
    ├── udcf-experiment-02-backend/backend/          ← DOUBLY NESTED (zip-extraction mistake)
    │   ├── pom.xml  README-EXPERIMENT-2.md
    │   └── src/main/java/com/udcf/{config,controller,demo,dto,model,monitoring,threadpool}
    │       src/main/resources/application{,-node1,-node2,-node3}.yml
    │       src/test/java/com/udcf/...               ← 11 test classes
    ├── udcf-exp3-clocksync-demo/src/main/java/com/udcf/
    │   ├── pom.xml                                  ← MISPLACED inside the source tree
    │   ├── {sync,demo}/                             ← EMPTY dir with literal braces (PowerShell artefact)
    │   ├── demo/  ClockEventLog, ClockNode, ClockSyncDemo, DemoEvent
    │   └── sync/LamportClock
    ├── udcf-exp4-election-demo/       .vscode, pom.xml, election/*, demo/ElectionDemo, sync/LamportClock
    ├── udcf-exp5-replication-demo/    .vscode, pom.xml, README, replication/*, demo/ReplicationDemo, sync/LamportClock
    ├── udcf-exp6-loadbalancer-demo/   .vscode, pom.xml, README, loadbalancer/*, demo/LoadBalancerDemo, sync/LamportClock
    ├── udcf-exp7-mapreduce-demo/      .vscode, pom.xml, README, data/, spark/, mapreduce/*, demo/MapReduceDemo, sync/LamportClock
    └── udcf-exp8-fault-tolerance-demo/ .vscode, pom.xml, README, fault/*, demo/FaultToleranceDemo, sync/LamportClock
```

### 4.3 What works

- All six console demos (Experiments 3 to 8) **compile cleanly** on JDK 21 with zero external
  dependencies.
- Experiment 2 compiles, including its 11 test classes, against the Spring jars in `~/.m2`.
  The tests were compiled but not run.
- There are no port collisions across experiments.
- Git is clean: 114 tracked files, no build output, no secrets tracked.
- The six `LamportClock` copies are identical apart from line endings.

### 4.4 Problems, in the order they must be fixed

| # | Severity | Problem | Fixed in |
|---|---|---|---|
| 1 | HIGH | Maven not on PATH; every documented backend command fails | Step 0.3 (Maven Wrapper) |
| 2 | HIGH | Experiment 2 at `backend/udcf-experiment-02-backend/backend/` | Step 0.2 |
| 3 | HIGH | `framework-events.log` (needed by the Exp 7 demo) is gitignored by `*.log`, so a clean clone crashes | Step 0.2 |
| 4 | HIGH | `CLAUDE.md` is in `docs/`, so Claude Code no longer auto-loads it | Step 0.4 |
| 5 | HIGH | Experiments 1, 9 and 10 do not exist | Phases 10–12 |
| 6 | HIGH | Architecture documents contradict the code (UDP/TCP vs "HTTP only"; multi-node single JVM vs "one JVM per node") | Resolved by the decision record, Section 5 |
| 7 | MEDIUM | `LamportClock` duplicated six times under the same fully qualified name | Phase 1 |
| 8 | MEDIUM | `NodeRole` and `ConsistencyModel` duplicated in Exp 5 and Exp 8 with different values; three incompatible `MessageType` enums; Bully promotion implemented twice | Phases 6–7 |
| 9 | MEDIUM | Exp 6 `WorkerNode` builds its own thread pool instead of reusing Exp 2's | Phase 8 |
| 10 | MEDIUM | Exp 3 project layout broken (misplaced pom, brace directory, no `.vscode`) | Step 0.2 |
| 11 | MEDIUM | README describes a structure that does not exist | Step 0.5 |
| 12 | MEDIUM | Only Experiment 2 has tests | Every module phase |
| 13 | LOW | Mixed CRLF and LF line endings; no `.gitattributes` | Step 0.4 |
| 14 | LOW | `spark/wordcount_spark.py` contradicts `requirements.txt` ("No Spark") | Decision R12 |
| 15 | LOW | Exp 2 demo Javadoc uses `exec:java`, but the pom lacks `exec-maven-plugin` | Superseded |

---

## 5. Decision record

The original decisions D1–D8 in `docs/CLAUDE.md` were written before any code existed. Several
are now revised. **This table is authoritative.**

| Id | Decision | Status | Rationale |
|---|---|---|---|
| **R1** | **One backend process hosts the whole cluster.** Nodes are separate components inside one Spring Boot application, and each owns its own **real sockets** on `127.0.0.1`. Messages genuinely cross the operating system's network stack. | REVISES D1 | Reuses nearly all existing code, ships as one container, deploys to Render. Honest framing: *separate nodes, co-hosted*. |
| **R2** | **Inter-node transport stays protocol-specific:** RMI for Exps 1, 9, 10; UDP for Exps 3 and 4; TCP for Exps 5–8. The **browser talks to the backend** through REST plus STOMP over WebSocket. | REVISES D3, D5 | Each transport was chosen for a teachable reason (see R2 notes below). Rewriting everything over HTTP would destroy those lessons. |
| **R3** | **No PostgreSQL.** All state is in memory. | REVISES D2, D4 | Every experiment's state is already in memory and genuine. A database complicates deployment and adds nothing to the ten experiments. |
| **R4** | **Two deployment targets.** Primary: full local system through Docker Compose (backend, frontend, Prometheus, Grafana). Secondary: public lite — frontend on Vercel, backend on Render, no Prometheus or Grafana. | REVISES D7 ("no cloud") | User decision. Hosting is used only to present the system; the distributed system itself runs entirely inside the backend process. |
| **R5** | Every metric carries a `node_id` tag, set **per meter**, plus global tags `application=udcf` and `mode=local|public`. | REVISES D6 | D6 used one global `node_id` tag per JVM. With R1 one JVM hosts many nodes, so the tag must be per meter. |
| **R6** | Every production class with behaviour has a test. Records, enums and declarative config are exempt, with a one-line justification. | KEEPS D8 | Unchanged. |
| **R7** | No fabricated metrics. Anything simulated is labelled in code and shown with a **"Simulated" badge** in the UI. | KEEPS hard rule 6 | See the honesty table, Section 6.11. |
| **R8** | **Maven Wrapper** (`mvnw`, `mvnw.cmd`) is the only supported way to build. | NEW | Maven is not on PATH; the wrapper removes the dependency permanently. |
| **R9** | The standalone demos move to **`legacy-demos/`** and stay runnable. They are not deleted. | NEW | They are the evidence behind lab submissions already made. |
| **R10** | **One shared cluster.** All ten modules operate on the same nodes. Crashing a node from any page affects every module running on it. | NEW | This is what makes the product "one system" rather than ten pages. |
| **R11** | Experiment 3 keeps Lamport as primary and **adds a Berkeley physical-clock module** with per-node simulated drift. | NEW | The lab title says "logical/physical"; the audit flagged physical sync as a gap. The drift is simulated and labelled — on one machine all nodes share one hardware clock. |
| **R12** | The Java MapReduce engine is the implementation. The PySpark script moves to `legacy-demos/exp07-mapreduce/spark/` as an optional illustration. | NEW | Project rules forbid heavy dependencies; the lab statement asks that the technique be applied to *this* project, which only the Java engine does. |
| **R13** | The MapReduce module can analyse **the cluster's own live event log**, exported from the event bus. A committed sample file is kept as a fallback. | NEW | Replaces the generated static log with real data. |
| **R14** | `CLAUDE.md` lives at the repository root. | NEW | Claude Code only auto-loads it from the root. |
| **R15** | Stack, unchanged: Java 21, Spring Boot 3.3.5, React 18 + Vite 5 (JSX), Tailwind 3.4, shadcn/ui, Recharts, Lucide, axios, react-router 6, @stomp/stompjs. No Lombok. Constructor injection only. | KEEPS | Ask the user before adding any dependency not listed in Section 6 or 8. |

**R2 notes — why each transport.** UDP for clock sync and election, because those algorithms are
designed to survive unreliable, unordered delivery; using a reliable transport would hide what is
being demonstrated. TCP for replication, load balancing, MapReduce and failover, because those
need a definite acknowledgement and an immediate error when a peer is gone. RMI for Experiments
1, 9 and 10, because Experiment 1 *is* RMI and the collectives are naturally remote method calls.

---

## 6. Target architecture

### 6.1 The big picture

```text
                   Browser — React + Vite
                    │ REST /api/**         │ STOMP over WebSocket /ws
                    ▼                      ▼
 ┌──────────────── UDCF backend — one Spring Boot process ─────────────────┐
 │  Web layer      controllers · STOMP broker · CORS · Actuator           │
 │  Modules        10 experiment modules, registered in a ModuleRegistry   │
 │  Core           Cluster · ClusterEventBus · LamportClock · Metrics      │
 │                                                                         │
 │  Cluster of N nodes (default 5). Every node owns its services:          │
 │    Node k   RMI        :110k   Exps 1, 9, 10                            │
 │             clock UDP  :600k   Exp 3                                    │
 │             election   :700k   Exp 4 — also carries heartbeats          │
 │             replication:710k   Exps 5 and 8                             │
 │             requests   :720k   Exp 2 executor, Exp 6 dispatch target    │
 │             mapreduce  :730k   Exp 7 worker                             │
 │    Everything binds 127.0.0.1 — real sockets, co-hosted in one process  │
 └─────────────────────────────────────────────────────────────────────────┘
          │ /actuator/prometheus                       (local mode only)
          ▼
     Prometheus  ──►  Grafana
```

### 6.2 The shared cluster model

- `Cluster` holds `ClusterNode`s. Size comes from configuration: default **5** locally, **3** in
  the public profile to save memory.
- Each `ClusterNode` owns **one `LamportClock`**, shared by every service on that node, so the
  global timeline is causally consistent across modules.
- Each `ClusterNode` owns a set of `NodeService`s, one per protocol endpoint.
- **Lifecycle.** `crash()` on a node calls `crash()` on every running service: UDP and TCP
  sockets close, RMI objects are unexported. `recover()` reverses it. A crash therefore behaves
  like a dead process to every peer, on every protocol.
- **Lazy start.** A service starts the first time its module needs it, which keeps memory low.
- **Capacity profiles** (for Experiment 6), set per node in configuration. Default for 5 nodes:
  N1 FAST, N2 MEDIUM, N3 SLOW, N4 MEDIUM, N5 FAST. FAST = 4 threads, ×1 work; MEDIUM = 2 threads,
  ×2; SLOW = 1 thread, ×4. Public profile with 3 nodes: FAST, MEDIUM, SLOW.

### 6.3 Ports

All bind `127.0.0.1` and are internal to the process. The only externally exposed port is the
backend HTTP port (8080 locally; `$PORT` on Render).

| Service | Node 1 | Node 2 | Node 3 | Node 4 | Node 5 |
|---|---|---|---|---|---|
| RMI registry | 1101 | 1102 | 1103 | 1104 | 1105 |
| Clock (UDP) | 6001 | 6002 | 6003 | 6004 | 6005 |
| Election + heartbeat (UDP) | 7001 | 7002 | 7003 | 7004 | 7005 |
| Replication (TCP) | 7101 | 7102 | 7103 | 7104 | 7105 |
| Requests (TCP) | 7201 | 7202 | 7203 | 7204 | 7205 |
| MapReduce worker (TCP) | 7301 | 7302 | 7303 | 7304 | 7305 |

These ranges match the legacy demos, so the two can never run at the same time on one machine.
Document that in the README.

### 6.4 Integration links — what makes it one system

| Link | From | To | Behaviour |
|---|---|---|---|
| **L1** | Exp 4 election | Exps 3, 5, 7, 8, 9 | The elected leader becomes the Berkeley time daemon, the replication primary, the MapReduce coordinator and the MPI root. |
| **L2** | Shared failure detector | Exps 4, 8 | One heartbeat service per node (on the election UDP channel) raises "suspect" events. Election and failover both react to the same signal. |
| **L3** | Exp 6 load balancer | Exp 2 executor | The balancer dispatches into each node's Exp 2 `ThreadPoolExecutor`. Fixes audit problem 9. |
| **L4** | Exp 3 Lamport clock | Every module | Every inter-node message carries the sender's Lamport time, so the global event timeline is causally ordered. |
| **L5** | Event bus | Exp 7 MapReduce | MapReduce can analyse the cluster's own live events. |
| **L6** | Exp 1 RMI → Exp 9 collectives → Exp 10 | | Matrix multiplication uses scatter and gather, which use RMI. |

**Build order for links.** Each module first works on the shared cluster in isolation. Links are
wired together in Phase 13.

### 6.5 Backend package layout (target)

```text
backend/
├── mvnw  mvnw.cmd  .mvn/wrapper/maven-wrapper.properties
├── pom.xml
├── Dockerfile
└── src/main/java/com/udcf/
    ├── UdcfBackendApplication.java
    ├── core/
    │   ├── clock/        LamportClock (the single copy)
    │   ├── cluster/      Cluster, ClusterNode, NodeService, NodeStatus, NodeCapacity, ClusterProperties
    │   ├── events/       ClusterEvent, ClusterEventBus, EventRingBuffer, EventLogExporter
    │   ├── failure/      FailureDetector (shared heartbeats — link L2)
    │   ├── metrics/      MetricsConfig, metric name constants
    │   └── module/       ExperimentModule, ModuleRegistry, ModuleStatus, ModuleBusyException
    ├── modules/
    │   ├── rmi/             Exp 1
    │   ├── multithreading/  Exp 2   (ported from com.udcf.threadpool)
    │   ├── clocksync/       Exp 3   (lamport/ and berkeley/)
    │   ├── election/        Exp 4   (bully/, ring/, transport/)
    │   ├── replication/     Exp 5
    │   ├── loadbalancing/   Exp 6
    │   ├── mapreduce/       Exp 7
    │   ├── faulttolerance/  Exp 8
    │   ├── mpi/             Exp 9
    │   └── matrix/          Exp 10
    ├── scenario/          guided multi-module scenarios (Phase 13)
    └── web/               WebSocketConfig, CorsConfig, GlobalExceptionHandler, SystemController, ClusterController, EventController
```

**Every module package has the same shape:** `XxxModule` (implements `ExperimentModule`), an
engine or service for the algorithm, a transport class kept separate from the algorithm,
`XxxController`, a `dto/` subpackage, and tests mirroring the package path.

**Separate the algorithm from the transport.** The audit found the legacy node classes fuse both
(`ElectionNode` 529 lines, `FaultTolerantNode` 536 lines). When porting, the algorithm becomes a
pure, unit-testable class, and the socket handling becomes a thin transport around it.

### 6.6 Core abstractions (sketches; finalise during Phase 1)

```java
public interface NodeService {
    String name();                 // "election", "replication", ...
    void start();                  // bind sockets / export RMI objects
    void crash();                  // close sockets / unexport: peers see a dead node
    void recover();                // rebind
    void stop();                   // clean shutdown
    boolean isRunning();
}

public interface ExperimentModule {
    String id();                   // "election"
    int labNumber();               // 4
    String title();                // "Bully and Ring Election"
    ModuleStatus status();         // IDLE, RUNNING, BUSY, ERROR
    void reset();                  // back to a clean demonstration state
}

public record ClusterEvent(
        long sequence,             // global, monotonic
        String module,             // "election"
        int nodeId,                // 0 = cluster-level
        String type,               // "ELECTION_START", "ACK", "CRASH" ...
        long lamportTime,
        Instant wallTime,          // display only — never used for ordering
        Integer peerId,
        String message,
        Map<String, Object> data
) { }
```

**Concurrency guard.** A module allows one long-running action at a time; a second request while
it is busy returns HTTP 409 with a clear message.

### 6.7 API conventions

| Endpoint | Purpose |
|---|---|
| `GET /api/system/health` | Liveness, also used for the cold-start banner |
| `GET /api/system/info` | Version, mode (`local`/`public`), uptime, cluster size |
| `GET /api/cluster` | Every node: id, status, roles (leader/primary/backup), capacity, running services |
| `POST /api/cluster/nodes/{id}/crash` | Crash a node across every service |
| `POST /api/cluster/nodes/{id}/recover` | Recover it |
| `POST /api/cluster/reset` | Restore the whole cluster to a clean state |
| `GET /api/modules` | List of modules for the sidebar: id, labNumber, title, status |
| `GET /api/events?module=&node=&limit=` | Recent events, causally ordered |
| `GET /api/events/export` | Event log in MapReduce input format (link L5) |
| `/api/modules/{moduleId}/...` | Module-specific endpoints, Section 7 |

Use `202 Accepted` for asynchronous actions, `204` for resets, `409` when a module is busy, and
`400` with field messages on validation failure. Validate request bodies with Jakarta Validation.

### 6.8 Events and WebSocket

- **STOMP endpoint:** `/ws` (native WebSocket; no SockJS — simpler behind Render's proxy).
- **Topics:** `/topic/events` (everything), `/topic/modules/{id}` (one module), `/topic/cluster`
  (node status changes).
- **Ring buffer:** 5000 events locally, 1500 in the public profile.
- **Allowed origins** come from the environment variable `UDCF_ALLOWED_ORIGINS`; never hard-code
  them.

### 6.9 Metrics

- Names follow `distributed_*`. Keep the names already used by Experiment 2 and the original
  plan: `distributed_requests_total`, `distributed_active_threads`, `distributed_node_status`,
  `distributed_leader_elections_total`, `distributed_election_duration`, `distributed_clock_value`,
  `distributed_replication_latency`, `distributed_replication_failures_total`,
  `distributed_failures_total`, `distributed_recovery_duration`, `distributed_map_tasks_total`,
  `distributed_reduce_tasks_total`, `distributed_mpi_messages_total`,
  `distributed_matrix_execution_duration`.
- Every meter carries `node_id` (decision R5).
- Gauges are bound to live objects, never to cached copies.

### 6.10 Resource budget (public profile)

Render's free tier has historically offered about 512 MB of RAM. **Verify the current limits in
Phase 16.** The `public` Spring profile therefore sets: cluster size 3, reduced thread caps, a
1500-event ring buffer, and JVM flags `-XX:MaxRAMPercentage=75 -Xss512k`. Services start lazily.

### 6.11 Honesty rules — what is real and what is simulated

| Real | Simulated, and labelled |
|---|---|
| Every inter-node message crosses a real socket | Nodes are co-hosted in one process (R1) |
| Every latency, throughput and count is measured | Async replication delay — 450 ms in Exp 5, 220 ms in Exp 8 — stands in for network latency |
| Crashes close real sockets, so peers get real refusals or silence | Berkeley clock drift offsets (Exp 3) — one machine has one hardware clock |
| Work done by executors is real SHA-256 computation | |

The UI shows a "Simulated" badge next to every simulated element, with a tooltip explaining why.
Never claim more than this table allows.

---

## 7. Module specifications — Experiments 1 to 10

Each module page follows the template in Section 8.4. "Done when" is the acceptance test for the
module's phase.

### Experiment 1 — Client-Server via Java RMI (`rmi`)

- **Concept:** remote procedure call. A client calls a method on an object in another process as
  if it were local; RMI handles the stub, the registry lookup and the serialisation.
- **Design:** every node starts an RMI registry on `110k` and exports a remote `NodeEndpoint`
  object. Methods: `ping()`, `nodeInfo()`, `echo(String)`, `computeHash(int rounds)`. Set
  `java.rmi.server.hostname=127.0.0.1`.
- **UI:** choose a target node and method, invoke, and show the result, round-trip time,
  marshalled payload size and the stub class name. A "local versus remote" comparison runs the
  same method directly and through RMI. Calling a crashed node shows the `RemoteException`.
- **Done when:** a remote call to each live node succeeds and is timed; a crashed node produces a
  visible remote failure; the registry contents of each node are listed.

### Experiment 2 — Multithreading (`multithreading`)

- **Reuse:** the entire `com.udcf.threadpool` package plus its model, dto, monitoring and 11
  tests. Port to `com.udcf.modules.multithreading`.
- **Design:** each node's request service owns one Exp 2 `ThreadPoolExecutor` (bounded queue,
  AbortPolicy, named threads, real SHA-256 work), sized by the node's capacity profile.
- **UI:** choose node, request count, workload type and payload size, then submit. Live gauges for
  active threads, queue depth and completed tasks; a throughput chart; a request table showing
  which worker thread ran each request; a backpressure demo that overloads the queue and shows
  REJECTED requests.
- **Done when:** a 100-request batch shows several distinct worker threads and a draining queue;
  an oversized batch produces rejections; the existing 11 tests pass in their new package.

### Experiment 3 — Clock Synchronization (`clocksync`)

- **Reuse:** `LamportClock` (now `core/clock`), the causal-invariant check and the
  `(lamportTime, nodeId)` total order from `ClockSyncDemo` and `ClockEventLog`.
- **Lamport design:** the per-node clock service sends and receives over UDP `600k`. Actions:
  local event on node X; send a message from X to Y; random traffic for N seconds.
- **Berkeley design (R11):** each node gets a configurable simulated drift offset. The leader
  (link L1) acts as time daemon: polls every node, averages, discards outliers beyond a threshold,
  and sends each node its individual adjustment. Show offsets before and after convergence.
- **UI:** a **space-time diagram** — one horizontal lane per node, events as dots, messages as
  arrows, each labelled with its Lamport value. Live counters per node. A verification panel
  confirming every receive timestamp exceeds its send timestamp. A Berkeley panel with a drift
  table and a "run sync round" control, carrying the Simulated badge.
- **Done when:** Lamport traffic produces a correct space-time diagram with zero causal
  violations; one Berkeley round visibly reduces the spread between node offsets.

### Experiment 4 — Bully and Ring Election (`election`)

- **Reuse:** the Bully and Ring logic, heartbeat and liveness probe from `ElectionNode`, split into
  an algorithm class and a UDP transport class.
- **Carry the lesson forward:** ring messages must not be handled on the listener thread — the
  probe waits for an acknowledgement only the listener can deliver, which deadlocked the original.
  Keep the fix and keep the comment explaining it.
- **Design:** election runs over UDP `700k`. Heartbeat and failure detection move to the shared
  `FailureDetector` (link L2). The elected leader is published as a cluster role.
- **UI:** a ring or graph of nodes with the leader marked; message animation; controls to start
  Bully from node X, start Ring from node X, crash the leader, and recover a node. An election log,
  election duration, and a consensus check that every live node agrees on one leader.
- **Done when:** crashing the leader triggers automatic re-election with no user action; Ring skips
  a dead node; a recovered highest-id node reclaims leadership; the consensus check passes.

### Experiment 5 — Consistency and Replication (`replication`)

- **Reuse:** `DataItem`, `DataStore` (the single `apply()` guard using `ConcurrentHashMap.compute`),
  last-writer-wins on `(lamportTime, nodeId)`, anti-entropy, out-of-order injection, and
  `ReplicationStats`.
- **Design:** one replication service per node on TCP `710k`, shared with Experiment 8. It
  supports epochs from the start, so Experiment 8 needs no second implementation. The primary is
  the elected leader (link L1).
- **UI:** a write form with a SYNCHRONOUS/ASYNCHRONOUS toggle. Each replica's value shown side by
  side, with stale values highlighted. Controls to crash and recover a backup, run anti-entropy,
  and inject a stale out-of-order update. A replication health table: acknowledgements, average
  and maximum latency, failures, stale rejections, last sync.
- **Done when:** an async write can be read stale from a backup and then converges; a crashed
  backup diverges and converges again through anti-entropy; a stale update is rejected.

### Experiment 6 — Load Balancing (`loadbalancing`)

- **Reuse:** `Strategy` (Round Robin, smooth Weighted Round Robin, Least Connections, Least
  Response Time), `WorkerInfo`, `DispatchResult`, `PhaseReport`, the circuit-breaker reroute.
- **Change:** delete `WorkerNode`'s private thread pool. The balancer dispatches over TCP `720k`
  into each node's Exp 2 executor (link L3).
- **UI:** strategy selector, batch size and concurrency; a bar chart of requests per node; average,
  p95 and maximum latency; makespan. A **"compare all four"** action producing the comparison
  table. Node capacity badges. A control to crash a worker mid-run.
- **Keep the key finding visible:** Round Robin has the most even request counts and the worst
  finish time. Equal request counts are not equal load.
- **Done when:** all four strategies run on the unequal cluster and the comparison shows Round
  Robin finishing last; a worker crashed mid-run produces reroutes and zero failed requests.

### Experiment 7 — MapReduce (`mapreduce`)

- **Reuse:** `MapReduceJob`, the three jobs, `JobRegistry`, `LogFields`, `JobReport`, and the
  split → map → combine → shuffle → hash-partition → reduce pipeline with task retry.
- **Design:** workers on TCP `730k`; the coordinator is the leader (link L1). Inputs: the bundled
  sample text, an uploaded `.txt` file (cap the size), or **the live cluster event log** (link L5).
- **Keep the lesson visible:** the average-latency job carries `sum;count`, never an average,
  because averages are not associative.
- **UI:** job and input selectors; an animated pipeline showing each stage's counts and timings;
  the combiner's network saving as a percentage; a results table and chart; a control to crash a
  worker and watch the task re-execute elsewhere.
- **Done when:** all three jobs run on all three input types; a worker crash produces a retried
  task and an identical result.

### Experiment 8 — Fault Tolerance (`faulttolerance`)

- **Reuse:** the epoch rules, `SystemUpdate`, `UpdateStore` semantics, `FailoverMetrics`,
  `UpdateClient` redirect behaviour.
- **Design:** built on the shared replication service (Exp 5) and the shared failure detector
  (link L2). The new primary comes from the election module, which removes the duplicated Bully
  logic. A recovering old primary queries the cluster, sees a higher epoch, demotes itself and
  resynchronises. Backups refuse any update carrying a stale epoch — so the protection does not
  depend on the failed node behaving well.
- **UI:** start and stop a continuous update stream; crash the primary; a live timeline marking
  crash, detection, promotion and service restored; the four measurements (detection, failover,
  recovery, data loss); a SYNCHRONOUS/ASYNCHRONOUS toggle; a "recover old primary" control showing
  the demotion; the epoch shown on every node.
- **Done when:** synchronous mode loses zero confirmed updates; asynchronous mode shows the
  in-flight loss; the recovered old primary demotes itself and no two primaries ever coexist.

### Experiment 9 — MPI Collectives (`mpi`)

- **Design:** collectives implemented over the Experiment 1 RMI layer. The root is the leader
  (link L1). Implement **broadcast**, **scatter** and **gather**; add **reduce** and **allgather**
  only if time allows. Each node exports a `CollectiveEndpoint` remote object.
- **UI:** choose the operation and payload; a fan-out and fan-in animation from the root; a
  per-message table showing sender, receiver, size and latency; total collective time.
- **Done when:** all three collectives complete on every live node with measured timings, and a
  crashed node is reported rather than hanging the operation.

### Experiment 10 — Parallel Matrix Multiplication (`matrix`)

- **Design:** generate matrices A and B. **Scatter** row blocks of A to the nodes, **broadcast** B,
  have each node compute its block on its executor, then **gather** the partial results (link L6).
  Compute the same product sequentially and compare.
- **UI:** matrix size selector (cap it for the public profile); sequential time, parallel time and
  speedup; per-node row allocation and compute time; a speedup chart across several sizes;
  verification that the parallel result equals the sequential one (checksum).
- **Be honest about speedup:** all nodes share one machine's cores, so speedup is bounded by the
  core count, and communication overhead dominates small matrices. Show that rather than hide it.
- **Done when:** results match the sequential product exactly; speedup is reported for at least
  three sizes; communication versus computation time is shown.

---

## 8. Frontend specification

### 8.1 Stack

React 18, Vite 5, **JavaScript (JSX)**, Tailwind CSS 3.4, shadcn/ui, Recharts, Lucide React, axios,
react-router-dom 6, @stomp/stompjs. Testing: Vitest, React Testing Library, jsdom, Playwright for
end-to-end. Ask before adding anything else.

### 8.2 Routes

| Route | Page |
|---|---|
| `/` | Overview — cluster health, node grid with role badges, live event stream, quick actions, integration map |
| `/cluster` | Node control — crash and recover each node, capacities, running services |
| `/experiments/1-rmi` … `/experiments/10-matrix` | One page per experiment |
| `/scenarios` | Guided multi-module demonstrations (Phase 13) |
| `/timeline` | Global causally ordered event log with module and node filters |
| `/monitoring` | Link to Grafana — shown only when `VITE_GRAFANA_URL` is set (local mode) |
| `/about` | Architecture, mapping to the lab list, the honesty table, restart onboarding |

### 8.3 Layout and design

- **Layout:** a left sidebar grouped as Overview, Cluster, *Experiments 1–10* (each with its lab
  number badge), Scenarios, Timeline, Monitoring, About. A top bar showing WebSocket connection
  state, mode (local/public), current leader, nodes up versus total.
- **Theme:** dark, matching the presentation decks already produced:

| Token | Value | Use |
|---|---|---|
| background | `#0F1730` | page |
| surface | `#16213E` | cards |
| border | `#24304F` | dividers |
| navy | `#21295C` | sidebar, headers |
| primary | `#1C7293` | buttons, links |
| accent | `#02C39A` | leader, success, live |
| warning | `#C8821C` | simulated, stale |
| danger | `#B9463A` | crashed, failure |
| text | `#E6EDF5` | body |
| muted | `#92A5C4` | secondary text |

- **Fonts:** Inter for the interface and JetBrains Mono for code, timestamps and Lamport values,
  each with a system fallback stack.
- **Motion:** subtle. Animate messages, elections and pipelines; never animate just for show.

### 8.4 Experiment page template

Every experiment page has the same structure, which is what makes ten experiments feel like one
product:

1. **Header** — lab number badge, title, one-line concept.
2. **How it works** — collapsible plain-language explanation. Reuse the explanations already
   written for the decks.
3. **Controls** — every action the module supports.
4. **Live visualisation** — the module's diagram, updating over WebSocket.
5. **Measurements** — metric cards, all real.
6. **Event log** — filtered to the module, with Lamport values.
7. **What to notice** — two or three callouts pointing at the result that matters.
8. **Simulated badges** wherever Section 6.11 requires them.

### 8.5 States

Every page handles loading (skeletons), empty (guidance on what to do first), error (message and
retry) and success (toasts). **Cold-start banner:** in public mode, if `/api/system/health` fails,
show "Waking the server — free hosting sleeps when idle, this can take about a minute" and retry
with backoff.

### 8.6 Onboarding

A four-step modal on first visit: welcome → the cluster → how a module page works → try a
scenario. It can be skipped and restarted from About. Store completion in `localStorage`.

---

## 9. Monitoring

Local mode only. Prometheus scrapes `backend:8080/actuator/prometheus` every 5 seconds. Grafana is
provisioned automatically with a datasource and one dashboard whose rows are: Cluster overview,
Multithreading, Clock sync, Election, Replication and fault tolerance, Load balancing, MapReduce,
MPI and matrix. The React UI never depends on Grafana; its charts read the backend REST API, so
they also work in public mode.

---

## 10. Deployment

### 10.1 Local — the full system

```text
infra/
├── docker-compose.yml      backend, frontend (nginx serving the build), prometheus, grafana
├── prometheus/prometheus.yml
└── grafana/provisioning/{datasources,dashboards}/  and  grafana/dashboards/udcf.json
```

One command from the repository root:

```powershell
docker compose -f infra/docker-compose.yml up --build
```

| Service | URL |
|---|---|
| Frontend | http://localhost:5173 |
| Backend | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |

**Development without Docker:** backend `cd backend; .\mvnw.cmd spring-boot:run`, frontend
`cd frontend; npm run dev`.

Provide `scripts/start-all.ps1` and `scripts/stop-all.ps1` as thin wrappers around these
commands.

### 10.2 Public lite — Vercel and Render

- **Backend on Render:** a Docker web service built from `backend/Dockerfile` (multi-stage: build
  with `mvnw`, run on `eclipse-temurin:21-jre`). `server.port=${PORT:8080}`. Health check
  `/actuator/health`. Environment: `SPRING_PROFILES_ACTIVE=public`,
  `UDCF_ALLOWED_ORIGINS=https://<vercel-domain>`. Optionally a `render.yaml` blueprint.
- **Frontend on Vercel:** root directory `frontend/`, build `npm run build`, output `dist`.
  Environment: `VITE_API_BASE_URL=https://<render-service>.onrender.com` and
  `VITE_WS_URL=wss://<render-service>.onrender.com/ws`. A `vercel.json` rewrite sends every route
  to `index.html` so client-side routing works on refresh.
- **The browser connects to Render directly** for both REST and WebSocket; Vercel only serves
  static files.
- **Verify before relying on it** (not verified when this document was written): Render's current
  free-tier RAM, sleep and cold-start behaviour, and WebSocket support; Vercel's current free-tier
  terms. Use web search at Phase 16.
- **For the viva, present from local mode.** A sleeping free server is a poor live demo. The public
  link is a bonus.

### 10.3 Environment variables (replace `.env.example` in Phase 0)

The old `.env.example` holds PostgreSQL, gateway, node-host and RMI keys that no longer apply.
Replace it with:

```text
# Backend
SPRING_PROFILES_ACTIVE=local          # local | public
UDCF_CLUSTER_SIZE=5
UDCF_ALLOWED_ORIGINS=http://localhost:5173
LOG_LEVEL_UDCF=INFO

# Frontend (only VITE_* reach the browser)
VITE_API_BASE_URL=http://localhost:8080
VITE_WS_URL=ws://localhost:8080/ws
VITE_GRAFANA_URL=http://localhost:3000

# Monitoring (local only)
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=change_me_locally
```

---

## 11. Phase plan (resumable)

**Rules.** One step at a time. A step is complete only when its "done when" holds. After every
completed step, update **Current Position** in the root `CLAUDE.md` and commit. Deploy a skeleton
early (step 2.5) so hosting problems surface while they are cheap to fix.

### Phase 0 — Repository repair

| Step | Work | Done when |
|---|---|---|
| 0.1 | Commit this document as `docs/HANDOFF.md`. | It is on `main`. |
| 0.2 | `git mv` every demo into `legacy-demos/`: `exp02-multithreading` (flattening the double nesting), `exp03-clock-sync`, `exp04-election`, `exp05-replication`, `exp06-load-balancing`, `exp07-mapreduce`, `exp08-fault-tolerance`. In exp03, move the pom to the folder root and delete the empty `{sync,demo}` directory. Add `.gitignore` exception `!legacy-demos/exp07-mapreduce/data/*.log` and commit the log. Move the Spark script per R12. Update each README's paths. | Every legacy demo compiles with its documented `javac` command from its new path, and the Exp 7 demo runs from a clean clone. |
| 0.3 | Create `backend/` from the Exp 2 application (copied, so the legacy copy stays intact). Add the Maven Wrapper. If `mvn` is unavailable, author the wrapper files directly using the `only-script` distribution type, which downloads Maven on first run. | `cd backend; .\mvnw.cmd test` runs and the 11 existing tests pass. |
| 0.4 | Write Appendix A to the repository root as `CLAUDE.md`; delete `docs/CLAUDE.md`. Add `.gitattributes` (LF for source, CRLF for `.cmd` and `.ps1`) and renormalise **in its own commit**. Replace `.env.example` per Section 10.3. | Claude Code loads the root `CLAUDE.md`; `git status` is clean after renormalising. |
| 0.5 | Rewrite `README.md` so it describes only what exists, and points to `docs/HANDOFF.md`. | Nothing in the README refers to a file that does not exist. |

### Phase 1 — Core platform

| Step | Work | Done when |
|---|---|---|
| 1.1 | `core/clock`, `core/cluster`, `core/events`, `core/module` per Section 6.6. Cluster properties for size and capacities. | Unit tests cover the clock (including a 50-thread concurrency test), event ordering and node lifecycle. |
| 1.2 | `spring-boot-starter-websocket`; STOMP on `/ws`; topics per Section 6.8; CORS from `UDCF_ALLOWED_ORIGINS`. | A STOMP test client receives a published event. |
| 1.3 | `SystemController`, `ClusterController`, `EventController`, `ModuleRegistry`. Crash and recover propagate to services. | `GET /api/cluster` lists 5 nodes; crash changes status and emits an event. |
| 1.4 | Per-meter `node_id` tagging; `local` and `public` profiles. | `/actuator/prometheus` shows `node_id` on the new meters. |

### Phase 2 — Frontend shell and walking-skeleton deployment

| Step | Work | Done when |
|---|---|---|
| 2.1 | Vite + React + Tailwind + shadcn/ui; design tokens from Section 8.3. | `npm run dev` serves the themed shell. |
| 2.2 | Layout, sidebar fed by `GET /api/modules`, top bar, routing for every page as a stub. | Every route renders. |
| 2.3 | `services/api.js`, `hooks/useStomp.js` with reconnect, `hooks/useCluster.js`. | The top bar shows live connection state and node count. |
| 2.4 | Overview and Cluster pages on real data, with crash and recover. | Crashing a node turns its card red live. |
| 2.5 | **Skeleton deployment:** backend Dockerfile to Render, frontend to Vercel. Verify REST and `wss` WebSocket end to end. | The public URL shows live cluster state. |

### Phases 3 to 12 — One phase per module

Order: **3** Exp 2 · **4** Exp 3 · **5** Exp 4 · **6** Exp 5 · **7** Exp 8 · **8** Exp 6 ·
**9** Exp 7 · **10** Exp 1 · **11** Exp 9 · **12** Exp 10.
(Experiments 2–8 already exist and are ported first; 8 follows 5 because it reuses the replication
service; 9 and 10 need 1.)

Every module phase has the same five steps:

| Step | Work |
|---|---|
| a | Port or build the algorithm as a pure class, with unit tests |
| b | Transport as a `NodeService` on the shared cluster |
| c | `XxxModule`, controller, DTOs, events, metrics, with MockMvc tests |
| d | Frontend page per the template in Section 8.4, with component tests |
| e | Verify against the module's "done when" in Section 7, then commit |

### Phase 13 — Integration links and scenarios

| Step | Work | Done when |
|---|---|---|
| 13.1 | Wire links L1–L6 from Section 6.4. | Crashing the leader triggers election, failover, a new MapReduce coordinator and a new MPI root, all visible in one timeline. |
| 13.2 | Scenarios page. Suggested: "The leader dies mid-write", "A slow node under load", "Analyse the incident with MapReduce", "Split-brain that never happens". | Each scenario runs end to end with narration. |
| 13.3 | Global causally ordered timeline page. | Events from every module interleave correctly by `(lamportTime, nodeId)`. |

### Phase 14 — Polish

Onboarding, empty and error states, cold-start banner, responsive pass, accessibility (keyboard
focus, colour never the only signal), copy review, consistent spacing.

### Phase 15 — Monitoring and local Docker Compose

Prometheus config, Grafana provisioning and dashboard, `infra/docker-compose.yml`,
`scripts/*.ps1`. **Done when** one command brings up all four services and Grafana shows live
data.

### Phase 16 — Public lite deployment, final

Verify current Render and Vercel limits by web search; tune the `public` profile; final deploy;
document the URLs. **Done when** the public site works after a cold start.

### Phase 17 — Testing, documentation, demo readiness

Coverage sweep; Playwright end-to-end flows (onboarding; crash leader → election → new leader;
load balancing comparison); `docs/architecture/`, `docs/experiments/` (one page per lab
experiment); final README; a demo script for the viva.

---

## 12. Working agreement with the user

**Environment.** Windows 11, VS Code with the Claude Code extension, PowerShell and Git Bash.

**How the user works.** The user brings prompts from this chat to Claude Code and runs commands
themselves. For every step, provide:

1. The purpose of the step, briefly.
2. The exact files to be created or changed.
3. A complete Claude Code prompt in a code block.
4. Exact PowerShell commands to build, run and verify, and what success looks like.
5. Exact git commands to commit and push (pull first — two people push to `main`).

**Ask before generating** whenever a real design choice exists. Use tappable options, keep it to
two or three questions, and always state a recommendation. Do not ask about things you can sensibly
decide; state those decisions instead.

**Honesty.** Label anything simulated. Never fabricate output or figures. If you cannot compile or
run something, say so plainly. When you catch a bug in your own earlier work, say so.

**Lab deliverables pattern** (used for every experiment so far, and likely wanted for 1, 9 and 10):

- a zip of code that extracts into the stated folder and runs with the stated commands;
- a 7-slide PowerPoint in the established design — navy/teal/mint palette, Cambria headings,
  Calibri body, dark title and closing slides, a purpose-drawn workflow diagram, speaker notes;
- an implementation explanation in plain language as a `.docx` of 3–4 pages;
- a word-for-word presentation script as a `.docx` of 2–3 pages, with stage directions in grey
  italics;
- viva questions with model answers where asked.

Do not put personal names or university IDs in deliverables unless the user supplies them for that
deliverable.

---

## 13. Environment pitfalls already hit

| Pitfall | What happened | Rule going forward |
|---|---|---|
| Zip extraction nesting | Extracting a zip *as a folder* produced `backend/udcf-experiment-02-backend/backend/` | Extract a zip's **contents** into the target folder |
| No brace expansion in PowerShell | `mkdir {sync,demo}` created one folder literally named `{sync,demo}` | Give PowerShell commands that create folders one by one |
| Maven not on PATH | `mvn` not recognised | Always use `mvnw` / `mvnw.cmd` |
| npm script policy | `npm.ps1 cannot be loaded` | Already fixed with `Set-ExecutionPolicy RemoteSigned` |
| Windows Firewall | Prompts appear the first time Java binds a port | Tell the user to click Allow access for Private networks |
| VS Code Java warnings | Dozens of null-analysis warnings in the Problems tab | `"java.compile.nullAnalysis.mode": "disabled"` in settings |
| Problems from other folders | Warnings shown from unrelated project folders | Check the file path in the Problems panel before diagnosing |
| Push rejected | The teammate had pushed first | `git pull`, accept the Vim merge message with `Esc :wq Enter`, then push |
| `*.log` gitignore | Swallowed a required data file | Add explicit `!` exceptions for required data |
| Mixed line endings | Identical files hashed differently | `.gitattributes`, Phase 0.4 |

---

## 14. Deliverables already produced outside the repository

These were produced in the previous chat. They are **not** in the repository, and a new chat
cannot see them unless the user uploads them.

| Experiment | Produced |
|---|---|
| 2 | Lab write-up docx on the college template; console demo |
| 3 | Lab write-up docx; viva preparation docx; 8-slide implementation deck |
| 4 | Lab write-up docx; 7-slide deck with flowchart; presentation guide PDF; slide-explanation docx; terms-explained docx |
| 5 | 7-slide deck; slide-explanation docx; terms-explained docx |
| 6 | 7-slide deck; implementation-explained docx; presentation speech docx |
| 7 | 7-slide deck; implementation-explained docx; presentation speech docx |
| 8 | 7-slide deck; implementation-explained docx; presentation speech docx |

The plain-language explanations in these documents are good source material for the "How it
works" panels in Section 8.4.

---

## 15. Open questions and risks

| # | Item | Plan |
|---|---|---|
| 1 | Render and Vercel free-tier limits, sleep behaviour and WebSocket support have not been verified for October 2026 | Verify by web search at steps 2.5 and 16 |
| 2 | One JVM hosting 5 nodes × several services may strain 512 MB | Public profile: 3 nodes, lazy services, capped threads (Section 6.10) |
| 3 | Faculty may expect Hadoop or Spark for Experiment 7 | The PySpark script remains in `legacy-demos` as optional evidence (R12) |
| 4 | Faculty may want physical clock sync with real clocks | Berkeley with labelled simulated drift (R11); explain that one machine has one clock |
| 5 | Free-tier cold start during a live viva | Present from local mode; public URL is a bonus |
| 6 | Matrix speedup is bounded by the machine's cores | Show it honestly, with communication versus computation time |
| 7 | Legacy demos and the integrated system use the same port ranges | Document that they cannot run simultaneously |
| 8 | Two contributors on `main` | Pull before work and before push; consider short-lived feature branches per phase |

---

## 16. Kickoff for the new chat

Paste this as the first message in the new chat, with this document attached or in the project
knowledge:

```text
This is the UDCF project. The attached UDCF_PROJECT_HANDOFF.md is the complete project context
and supersedes the README and docs/CLAUDE.md in the repo. Read all of it first.

We are starting Phase 0, Step 0.1. Follow the working agreement in Section 12: explain the step,
list the files, give me the complete Claude Code prompt and the exact PowerShell and git
commands, and ask me first if anything is genuinely undecided. One step at a time.
```

---

## Appendix A — New CLAUDE.md for the repository root

> Phase 0, step 0.4 writes this block, exactly, to `CLAUDE.md` at the repository root.

```markdown
# CLAUDE.md — UDCF

Read this file fully before any task. Full context is in docs/HANDOFF.md; if anything here
seems unclear or contradictory, that file is authoritative.

## Project
Unified Distributed Coordination Framework: ten distributed-systems lab experiments built as
modules of ONE running system with ONE professional web UI. One Spring Boot process hosts a
cluster of nodes; each node owns real sockets on 127.0.0.1. All ten modules share that cluster.

## Decisions (authoritative; see docs/HANDOFF.md Section 5)
- R1 One backend process hosts the cluster; nodes are co-hosted components with real sockets.
- R2 Inter-node: RMI (Exps 1, 9, 10), UDP (Exps 3, 4), TCP (Exps 5–8). Browser: REST + STOMP /ws.
- R3 No database. All state in memory.
- R4 Local Docker Compose (full, with Prometheus + Grafana) and public lite (Vercel + Render).
- R5 Every metric tagged node_id per meter; global tags application, mode.
- R6 Tests for every class with behaviour; records/enums/config exempt with a justification.
- R7 Never fabricate metrics. Label anything simulated in code and with a UI "Simulated" badge.
- R8 Build only with the Maven Wrapper: .\mvnw.cmd (Windows) or ./mvnw.
- R9 legacy-demos/ holds the original standalone demos. Keep them compiling. Do not edit them
     except to fix paths.
- R10 One shared cluster; crashing a node affects every module on it.
- R15 Stack fixed. Ask before adding any dependency not already listed in docs/HANDOFF.md.

## Hard rules
1. One phase step at a time. Never generate the whole project at once.
2. State exactly which files you will create or change before changing them.
3. Separate algorithm from transport: pure algorithm classes, thin NodeService transports.
4. Constructor injection only. No Lombok. Records for DTOs.
5. Ask before deleting or rewriting working code.
6. No hard-coded ports, cluster size or origins outside configuration.
7. Ring election messages must never be handled on the listener thread (known deadlock).
8. Never print or commit secrets. .env stays gitignored.
9. After each completed step, update Current Position below and commit.

## Layout
backend/        Spring Boot app (com.udcf.core, com.udcf.modules.<module>, com.udcf.web)
frontend/       React 18 + Vite 5 (JSX), Tailwind 3.4, shadcn/ui, Recharts, Lucide
infra/          docker-compose.yml, prometheus/, grafana/
legacy-demos/   original standalone demos for Experiments 2–8
scripts/        start-all.ps1, stop-all.ps1
docs/           HANDOFF.md, architecture/, experiments/

## Commands (Windows PowerShell)
cd backend; .\mvnw.cmd test
cd backend; .\mvnw.cmd spring-boot:run
cd frontend; npm install; npm run dev
cd frontend; npm run test
docker compose -f infra/docker-compose.yml up --build

## Ports
Backend 8080 · Frontend 5173 · Prometheus 9090 · Grafana 3000
Internal, per node k: RMI 110k · clock UDP 600k · election UDP 700k · replication TCP 710k ·
requests TCP 720k · mapreduce TCP 730k

## Current Position
Phase: 0
Step: 0.1
Status: not started

### Completed steps
- (none yet)

### Known issues
- See docs/HANDOFF.md Section 4.4

### Deviations from plan
- (none yet)
```

---

## Appendix B — Parameters from the existing demos

Preserve these when porting; they were tuned for visible, honest demonstrations.

**Experiment 2 — multithreading.** Core 4, max 8, bounded queue 200, keep-alive 60 s, AbortPolicy
(not CallerRuns — it would run work on the HTTP thread and corrupt measurements), thread prefix
`udcf-worker-`, 30 s metrics window, real SHA-256 work, batch limits 1–1000 requests and payload
1–5000.

**Experiment 3 — clock sync.** UDP; ports 6001–6003; Lamport rules: tick on local event, tick and
attach on send, `max(local, received) + 1` on receive via `AtomicLong.updateAndGet`; total order
by `(lamportTime, nodeId)`; causal-invariant verification at the end of a run.

**Experiment 4 — election.** UDP; 5 nodes on 7001–7005; heartbeat interval 700 ms, heartbeat
timeout 2500 ms, OK timeout 900 ms, coordinator timeout 2200 ms, probe timeout 300 ms;
`AtomicBoolean.compareAndSet` prevents overlapping elections on one node; consensus check.

**Experiment 5 — replication.** TCP; 7101–7103; one primary and two backups; SYNCHRONOUS and
ASYNCHRONOUS; async delay 450 ms (simulated, labelled); last-writer-wins on
`(lamportTime, originNode)`; anti-entropy by pushing the full store; final key-by-key
consistency check.

**Experiment 6 — load balancing.** TCP; 7201–7203; workers FAST (4 threads, ×1), MEDIUM (2, ×2),
SLOW (1, ×4); 60 requests, 900 work units, 12 concurrent clients; smooth weighted round robin
(nginx style); least response time = EWMA latency × (in-flight + 1), α = 0.3; circuit breaker
marks a refused worker unhealthy and retries elsewhere.

**Experiment 7 — MapReduce.** TCP; 7301–7303; 4 threads per worker; payloads Base64-encoded on a
line protocol; combiner on the mapper node; hash partitioning `floorMod(key.hashCode(), R)`;
average latency carried as `sum;count`; task retry on another worker; 15 s task timeout.

**Experiment 8 — fault tolerance.** TCP; 7401–7403; heartbeat 300 ms, timeout 1200 ms, peer
timeout 800 ms; async delay 220 ms (simulated, labelled); the highest live id promotes itself;
epoch increments on every promotion; backups refuse stale-epoch updates; a recovering node runs a
role query and demotes itself on seeing a higher epoch; data loss measured against the keys the
client was told had succeeded.
