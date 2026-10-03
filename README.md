# Unified Distributed Coordination Framework

UDCF brings ten distributed-systems lab experiments together as modules of one running
system. The goal is a single Spring Boot process that hosts a cluster of nodes, each owning
real sockets on 127.0.0.1, with all ten modules (client-server RMI, multithreading, clock
synchronization, leader election, replication, load balancing, MapReduce, fault tolerance,
MPI-style collectives and parallel matrix multiplication) sharing that cluster behind one
web UI. [docs/HANDOFF.md](docs/HANDOFF.md) is the plan and the single source of truth for
decisions, architecture and phases. [CLAUDE.md](CLAUDE.md) holds the instructions for
Claude Code.

---

## Problem Statement

Existing distributed systems primarily address individual challenges such as clock
synchronization, leader election, load balancing, multithreading, data replication or
fault tolerance independently. This fragmented approach limits the ability to observe
how these mechanisms interact within a common distributed environment. There is
therefore a need for a lightweight, integrated distributed framework that combines
synchronized communication, dynamic leader election, adaptive load balancing,
multithreaded request handling, data replication and fault tolerance into a single
non-cloud distributed system, and provides measurable performance and recovery
behaviour.

---

## Current Status

Phase 0 (repository repair) is complete.

- `backend/` is still the Experiment 2 multithreading application, copied from the legacy
  demo. It is not yet the integrated system: there is no shared cluster, no inter-node
  communication and no other module.
- Experiments 2–8 exist as standalone demos in `legacy-demos/`.
- Experiments 1, 9 and 10 are not started.
- There is no frontend yet, and no Docker Compose, Prometheus or Grafana setup.

---

## Repository Layout

```
.
├── .vscode/                 editor settings
├── backend/                 Spring Boot app (currently the Exp 2 application)
│   ├── .mvn/                Maven Wrapper configuration
│   ├── src/                 main and test sources
│   ├── mvnw                 Maven Wrapper (sh)
│   ├── mvnw.cmd             Maven Wrapper (Windows)
│   └── pom.xml
├── docs/
│   └── HANDOFF.md           plan and single source of truth
├── legacy-demos/            original standalone demos, Experiments 2–8
│   ├── exp02-multithreading/
│   ├── exp03-clock-sync/
│   ├── exp04-election/
│   ├── exp05-replication/
│   ├── exp06-load-balancing/
│   ├── exp07-mapreduce/
│   └── exp08-fault-tolerance/
├── .env.example             environment variables for later phases (unused by the current backend)
├── .gitattributes           line-ending rules
├── .gitignore
├── CLAUDE.md                instructions for Claude Code
├── README.md
└── requirements.txt         human-readable system requirements (not a pip file)
```

---

## Prerequisites

- JDK 21
- Git

Maven is **not** required: the Maven Wrapper in `backend/` downloads Maven 3.9.9 on first
run. Python (with `pyspark`) is needed only for the optional Spark script in
[legacy-demos/exp07-mapreduce/spark/wordcount_spark.py](legacy-demos/exp07-mapreduce/spark/wordcount_spark.py).

---

## Backend

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

`test` runs 59 tests in 11 classes. `spring-boot:run` starts the default profile `node1`
on http://localhost:8081. The exposed actuator endpoints are `/actuator/health`,
`/actuator/info`, `/actuator/metrics` and `/actuator/prometheus`. The REST API is the
Experiment 2 API documented in
[legacy-demos/exp02-multithreading/README-EXPERIMENT-2.md](legacy-demos/exp02-multithreading/README-EXPERIMENT-2.md).

Profiles `node2` (port 8082) and `node3` (port 8083) start further instances. They are
independent processes and do not communicate with each other:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=node2"   # 8082
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=node3"   # 8083
```

---

## Legacy Demos

The original standalone demos, kept runnable as the evidence behind the lab submissions.

| Folder | Experiment | Build and run | Ports |
|---|---|---|---|
| [legacy-demos/exp02-multithreading/](legacy-demos/exp02-multithreading/) | 2 · Multithreading | [README-EXPERIMENT-2.md](legacy-demos/exp02-multithreading/README-EXPERIMENT-2.md) (Maven, via the backend wrapper) | HTTP 8081–8083 |
| [legacy-demos/exp03-clock-sync/](legacy-demos/exp03-clock-sync/) | 3 · Clock synchronization (Lamport) | `javac` / `java`, see [below](#experiments-3-and-4) | UDP 6001–6003 |
| [legacy-demos/exp04-election/](legacy-demos/exp04-election/) | 4 · Bully and Ring leader election | `javac` / `java`, see [below](#experiments-3-and-4) | UDP 7001–7005 |
| [legacy-demos/exp05-replication/](legacy-demos/exp05-replication/) | 5 · Data consistency and replication | [README-EXPERIMENT-5.md](legacy-demos/exp05-replication/README-EXPERIMENT-5.md) | TCP 7101–7103 |
| [legacy-demos/exp06-load-balancing/](legacy-demos/exp06-load-balancing/) | 6 · Load balancing | [README-EXPERIMENT-6.md](legacy-demos/exp06-load-balancing/README-EXPERIMENT-6.md) | TCP 7201–7203 |
| [legacy-demos/exp07-mapreduce/](legacy-demos/exp07-mapreduce/) | 7 · MapReduce (optional PySpark script) | [README-EXPERIMENT-7.md](legacy-demos/exp07-mapreduce/README-EXPERIMENT-7.md) | TCP 7301–7303 |
| [legacy-demos/exp08-fault-tolerance/](legacy-demos/exp08-fault-tolerance/) | 8 · Fault tolerance (primary-backup) | [README-EXPERIMENT-8.md](legacy-demos/exp08-fault-tolerance/README-EXPERIMENT-8.md) | TCP 7401–7403 |

### Experiments 3 and 4

These two have no README; the commands come from each demo class's Javadoc.

```powershell
cd legacy-demos\exp03-clock-sync
javac -d target/demo-classes src/main/java/com/udcf/sync/LamportClock.java src/main/java/com/udcf/demo/*.java
java -cp target/demo-classes com.udcf.demo.ClockSyncDemo
```

```powershell
cd legacy-demos\exp04-election
javac -d target/classes src/main/java/com/udcf/sync/LamportClock.java src/main/java/com/udcf/election/*.java src/main/java/com/udcf/demo/*.java
java -cp target/classes com.udcf.demo.ElectionDemo
```

> **Port overlap.** The legacy demos use the same port ranges that the planned integrated
> backend will use (UDP 6001+ and 7001+, TCP 7101+, 7201+ and 7301+; see docs/HANDOFF.md
> Section 6.3), so they must not run at the same time as it. Legacy Exp 8 uses 7401–7403,
> which falls outside those ranges. Today, legacy Exp 2 and `backend/` are the same
> application on the same HTTP ports 8081–8083, so run only one of them at a time.

---

## Windows Notes

- On the first run of the backend or a demo, Windows Firewall may ask whether to allow
  Java. Click **Allow access** for **Private networks**.
- Line endings are handled by `.gitattributes` (LF for sources, CRLF for `.cmd`, `.bat`
  and `.ps1`). No `core.autocrlf` setup is needed.

---

## Where This Is Going

Phase 1 builds the core platform inside `backend/`: a shared cluster of nodes, a Lamport
clock, an event bus and a module registry. Later phases add the React frontend, port
Experiments 2–8 onto that cluster, build Experiments 1, 9 and 10, and add monitoring and
deployment. The step-by-step plan is in docs/HANDOFF.md Section 11.

---

## Contributors

- **Nidhi Dhyani** — `2024300050`
- **Rohan Dhumal** — `2024300049`
- **Swanand Dixit** — `2024300052`
- **Jai Desai** — `2024300041`
