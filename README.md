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

Phases 0 (repository repair) and 1 (core platform) are complete; Phase 2 (frontend shell
and deployment skeleton) is in progress.

- `backend/` has the core platform: a shared cluster of nodes, the event bus with Lamport
  ordering, the REST API, STOMP over WebSocket, metrics, and the `local` and `public`
  profiles. The Experiment 2 multithreading application runs alongside it and moves onto
  the shared cluster in Phase 3.
- `frontend/` has the application shell, including live Overview and Cluster pages
  with crash, recover and reset controls connected to the backend. The remaining
  experiment pages are stubs until their corresponding modules are built.
- No experiment module is built on the shared cluster yet. Experiments 2–8 exist as
  standalone demos in `legacy-demos/`; Experiments 1, 9 and 10 are not started.
- There is no Docker Compose, Prometheus or Grafana setup yet.

---

## Repository Layout

```
.
├── .vscode/                 editor settings
├── backend/                 Spring Boot app: core platform, with the Exp 2 application alongside
│   ├── .mvn/                Maven Wrapper configuration
│   ├── src/                 main and test sources
│   ├── .dockerignore
│   ├── Dockerfile           multi-stage container image
│   ├── mvnw                 Maven Wrapper (sh)
│   ├── mvnw.cmd             Maven Wrapper (Windows)
│   └── pom.xml
├── docs/
│   └── HANDOFF.md           plan and single source of truth
├── frontend/                React 18 + Vite 6 web UI: shell and routing, placeholder pages
│   ├── public/              static files (favicon)
│   ├── src/                 layout, pages, routes, styles, tests
│   ├── components.json      shadcn/ui configuration
│   ├── index.html
│   ├── package.json         scripts and pinned dependencies
│   ├── tailwind.config.js   design tokens as Tailwind colours
│   ├── vercel.json          SPA route rewrites
│   └── vite.config.js       dev server, @ alias, Vitest
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
├── render.yaml              Render Blueprint definition
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

`test` runs 245 tests. `spring-boot:run` starts the default profile `local` on
http://localhost:8080 (set `PORT` to change it): a cluster of 5 nodes, with the actuator
endpoints `/actuator/health`, `/actuator/info`, `/actuator/metrics` and
`/actuator/prometheus`.

The `public` profile is the lite configuration for the public deployment: 3 nodes, a
1500-event buffer, a smaller thread pool, and only `/actuator/health` and `/actuator/info`:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=public"
```

The API:

- `GET /api/system/health`, `GET /api/system/info`
- `GET /api/cluster`, `GET /api/cluster/nodes/{id}`, `POST /api/cluster/nodes/{id}/crash`,
  `POST /api/cluster/nodes/{id}/recover`, `POST /api/cluster/reset`
- `GET /api/modules`
- `GET /api/events?module=&node=&limit=`
- STOMP over WebSocket on `/ws`, topics `/topic/events`, `/topic/cluster` and
  `/topic/modules/{id}`
- the Experiment 2 API under `/api/multithreading`, documented in
  [legacy-demos/exp02-multithreading/README-EXPERIMENT-2.md](legacy-demos/exp02-multithreading/README-EXPERIMENT-2.md)

The multi-process Experiment 2 demo (three instances on HTTP 8081–8083) is in
[legacy-demos/exp02-multithreading/](legacy-demos/exp02-multithreading/).

---

## Frontend

Requires Node 22.x.

```powershell
cd frontend
npm install
npm run dev      # http://localhost:5173 (strict port: fails if 5173 is taken)
npm test         # Vitest + React Testing Library, 126 tests
npm run build    # production build in frontend/dist
```

The frontend displays live cluster status and connection state. For live data, the backend must be running on port 8080:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

The root `.env` must define `VITE_API_BASE_URL` and `VITE_WS_URL` (copy [.env.example](.env.example) to `.env`). Vite reads `VITE_*` variables from the repository root. When these variables are missing or invalid, the app displays a clear configuration error.

---

## Deployment

UDCF supports a public "lite" cloud deployment pairing a Render Docker web service with a Vercel static SPA.

- **Backend (Render):** Deployed as a Docker web service using [backend/Dockerfile](backend/Dockerfile) and configured via [render.yaml](render.yaml) under the `public` Spring profile.
  - Required Environment Variables:
    - `SPRING_PROFILES_ACTIVE`: `public`
    - `UDCF_ALLOWED_ORIGINS`: exact Vercel production origin (e.g. `https://<frontend-url>`, no trailing slash)
  - Public URL: https://udcf-backend.onrender.com/actuator/health
- **Frontend (Vercel):** Deployed from `frontend/` as a static Vite build (`dist/`), with client-side SPA routing rewrites configured in [frontend/vercel.json](frontend/vercel.json).
  - Required Environment Variables:
    - `VITE_API_BASE_URL`: `https://<backend-url>`
    - `VITE_WS_URL`: `wss://<backend-url>/ws`
  - Public URL: https://unified-distributed-coordination-fr.vercel.app
- **Cold Start Behavior:** Render's free tier spins down instances after 15 minutes of inactivity. When a request arrives, the server wakes in approximately one minute. While spinning up, the frontend displays a `Reconnecting…` status badge and automatically recovers once the backend becomes live.

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
> which falls outside those ranges. `backend/` now serves HTTP on 8080, so it no longer
> shares HTTP ports with legacy Exp 2 (8081–8083).

---

## Windows Notes

- On the first run of the backend or a demo, Windows Firewall may ask whether to allow
  Java. Click **Allow access** for **Private networks**.
- Line endings are handled by `.gitattributes` (LF for sources, CRLF for `.cmd`, `.bat`
  and `.ps1`). No `core.autocrlf` setup is needed.

---

## Team Workflow

Phase 3 onwards runs across four parallel tracks with protected `main` (changes arrive through pull requests with automated backend and frontend CI checks; see [docs/tracks/README.md](docs/tracks/README.md)):

- **Track A (Nidhi):** Experiment 2 (multithreading) → Experiment 6 (load balancing), plus the shared experiment-page kit.
- **Track B (Swanand):** Experiment 4 (election) → Experiment 8 (fault tolerance), plus the shared failure detector and cluster roles API.
- **Track C (Jai):** Experiment 5 (replication) → Experiment 1 (RMI) → Experiment 9 (MPI) → Experiment 10 (matrix multiplication).
- **Track D (Rohan):** Experiment 3 (clock synchronization) → Experiment 7 (MapReduce), plus the event log exporter. Rohan reviews and merges all pull requests.

---

## Where This Is Going

The core platform (Phase 1) and the frontend shell (Phase 2) come first; Phases 3–9 port
Experiments 2–8 onto the shared cluster. After Phase 9, Phase 9A delivers an integrated demo
of Experiments 2–8 (a global timeline, guided scenarios and a demo script); Experiments 1, 9
and 10 follow, then monitoring and deployment. The step-by-step plan is in docs/HANDOFF.md
Section 11.

---

## Contributors

- **Nidhi Dhyani** — `2024300050`
- **Rohan Dhumal** — `2024300049`
- **Swanand Dixit** — `2024300052`
- **Jai Desai** — `2024300041`
