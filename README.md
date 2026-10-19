# Unified Distributed Coordination Framework

UDCF brings ten distributed-systems lab experiments together as modules of **one running system**. A
single Spring Boot process hosts a cluster of nodes (five locally); each node owns real sockets on
127.0.0.1 (RMI, UDP and TCP), and all ten experiments share that cluster behind one React web UI.

The experiments are linked, not just placed side by side. Experiment 4 elects one leader, and that
leader is also the Berkeley time daemon, the replication primary, the MapReduce coordinator and the MPI
and matrix-multiply root. Crash the leader and every one of those roles follows the new leader, visible
on one timeline. Five guided scenarios run real failures on the real cluster, narrate each step and
report what they measured. Anything simulated is labelled "Simulated" on its page, with the reason.

- Architecture: [docs/architecture/overview.md](docs/architecture/overview.md)
- Live demo script: [docs/demo/udcf-demo.md](docs/demo/udcf-demo.md) · viva walkthrough:
  [docs/demo/viva-script.md](docs/demo/viva-script.md)
- Plan and decision record: [docs/HANDOFF.md](docs/HANDOFF.md)

---

## Live demo

**<https://unified-distributed-coordination-fr.vercel.app>**

The public site runs the lite profile: a cluster of 3 nodes on Render's free tier, with the frontend on
Vercel. The backend sleeps when unused, so the first load takes 1 to 2 minutes (measured 94.6 s) while a
banner says the server is waking. Keep the tab open: its live connection keeps the backend awake. The
top bar shows "Leader: None elected" until the first action that needs a leader; that action elects
node 3. Deployment details and measurements: [docs/deploy/public-deploy.md](docs/deploy/public-deploy.md).

To run the full five-node version on your own machine, see [Quick start (Windows)](#quick-start-windows).

---

## The ten experiments

| Lab | Experiment | Module id | Transport | Documentation |
|---|---|---|---|---|
| 1 | Client-Server Communication using Java RMI | `rmi` | RMI | [exp01-rmi.md](docs/experiments/exp01-rmi.md) |
| 2 | Multithreading in a Distributed System | `multithreading` | TCP | [exp02-multithreading.md](docs/experiments/exp02-multithreading.md) |
| 3 | Clock Synchronization (Lamport and Berkeley) | `clocksync` | UDP | [exp03-clocksync.md](docs/experiments/exp03-clocksync.md) |
| 4 | Bully and Ring Election | `election` | UDP | [exp04-election.md](docs/experiments/exp04-election.md) |
| 5 | Data Consistency and Replication | `replication` | TCP | [exp05-replication.md](docs/experiments/exp05-replication.md) |
| 6 | Load Balancing | `loadbalancing` | TCP | [exp06-loadbalancing.md](docs/experiments/exp06-loadbalancing.md) |
| 7 | MapReduce | `mapreduce` | TCP | [exp07-mapreduce.md](docs/experiments/exp07-mapreduce.md) |
| 8 | Fault Tolerance with Primary-Backup Replication | `faulttolerance` | TCP, with UDP heartbeats | [exp08-faulttolerance.md](docs/experiments/exp08-faulttolerance.md) |
| 9 | MPI Collectives: Broadcast, Scatter, Gather | `mpi` | RMI | [exp09-mpi.md](docs/experiments/exp09-mpi.md) |
| 10 | Parallel Matrix Multiplication | `matrix` | RMI | [exp10-matrix.md](docs/experiments/exp10-matrix.md) |

Each page in the UI is at `/experiments/<lab>-<module id>`, for example
<http://localhost:5173/experiments/4-election>.

---

## Requirements

- **Java 21** (JDK). Maven is not needed: the Maven Wrapper in `backend/` downloads Maven 3.9.9 on the
  first run.
- **Node.js 22**.
- Windows PowerShell 5.1 for the scripts in `scripts/`. The manual commands below also work on Linux and
  macOS with `./mvnw` instead of `.\mvnw.cmd`.
- Free ports 8080 (backend) and 5173 (frontend). Internally the nodes use 127.0.0.1 ports 1101–1105,
  6001–6005, 7001–7005, 7101–7105, 7201–7205 and 7301–7305.

The frontend reads `VITE_API_BASE_URL` and `VITE_WS_URL` from a `.env` file at the repository root:
copy [.env.example](.env.example) to `.env` once. If they are missing, the app shows a configuration
error.

---

## Quick start (Windows)

From the repository root, once:

```powershell
cd frontend; npm install; cd ..
```

Then:

```powershell
# Start the backend (8080) and the frontend (5173), then open http://localhost:5173
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all.ps1

# Clean-slate reset of the cluster (waits while a module is busy)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\reset-cluster.ps1

# Stop both (safe to run twice)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-all.ps1
```

`start-all.ps1` checks Java 21, Node 22, `frontend\node_modules` and that ports 8080 and 5173 are free,
starts both servers in the background, waits until both answer and opens the browser (add `-NoBrowser`
to skip that). Logs: `%TEMP%\udcf-demo\backend.log` and `%TEMP%\udcf-demo\frontend.log`.

### Manual alternative

```powershell
# Terminal 1: backend on http://localhost:8080 (profile "local", 5 nodes)
cd backend
.\mvnw.cmd spring-boot:run

# Terminal 2: frontend on http://localhost:5173
cd frontend
npm install
npm run dev
```

The lite profile used by the public deployment (3 nodes, smaller limits) can be run locally with
`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=public"`.

### First steps in the UI

1. **Overview:** the integration map shows the ten experiments, the cluster and the six links L1–L6.
2. **Cluster:** each node's status, capacity, ports and roles, with Crash and Recover buttons.
3. **Experiments:** one page per lab, each with How it works, Controls, Live visualisation,
   Measurements, Event log and What to notice.
4. **Timeline:** every module's events in Lamport order, or in arrival order.
5. **Scenarios:** five guided failure scenarios with a verdict.

---

## Running the tests

```powershell
# Backend: JUnit 5, unit and integration tests on real 127.0.0.1 sockets
cd backend
.\mvnw.cmd test

# Frontend: Vitest and React Testing Library, then lint and production build
cd frontend
npm test
npm run lint
npm run build
```

GitHub Actions (`.github/workflows/ci.yml`) runs the same two suites on Linux for every push and pull
request to `main`: `./mvnw -B test` with Java 21, and `npm ci`, lint, test and build with Node 22.

The tests that prove the six integration links are listed in
[docs/architecture/overview.md](docs/architecture/overview.md#2-modules-and-links); each experiment page
lists its own tests.

---

## Repository layout

```text
.
├── backend/                 Spring Boot 3.3.5 application (Java 21)
│   ├── src/main/java/com/udcf/
│   │   ├── core/            cluster, nodes, Lamport clock, event bus, roles, failure detector, metrics
│   │   ├── modules/         the ten experiments: rmi, multithreading, clocksync, election,
│   │   │                    replication, loadbalancing, mapreduce, faulttolerance, mpi, matrix
│   │   ├── scenario/        scenario engine and the five scenarios
│   │   └── web/             REST controllers, STOMP, CORS, error handling
│   ├── src/main/resources/  application.yml, application-local.yml, application-public.yml
│   ├── Dockerfile           image for the public deployment
│   └── mvnw, mvnw.cmd       Maven Wrapper
├── frontend/                React 18 + Vite 6 (JSX), Tailwind 3.4, shadcn/ui
│   └── src/
│       ├── pages/           Overview, Cluster, Timeline, Scenarios, About, ...
│       ├── modules/         one folder per experiment page
│       └── components/      layout, experiment-page kit, integration map, timeline
├── docs/
│   ├── architecture/        architecture overview
│   ├── experiments/         one page per lab experiment
│   ├── demo/                live demo script and viva walkthrough
│   ├── deploy/              public deployment notes
│   ├── tracks/              the four development tracks and their logs
│   └── HANDOFF.md           plan and decision record
├── legacy-demos/            the original standalone demos of Experiments 2–8
├── scripts/                 start-all.ps1, stop-all.ps1, reset-cluster.ps1
├── render.yaml              Render blueprint (public backend)
└── .env.example             frontend environment variables
```

---

## Legacy demos

[legacy-demos/](legacy-demos/) keeps the original standalone console demos of Experiments 2–8, as the
evidence behind the lab submissions. Experiments 3 to 7 use the same port ranges as the integrated
backend, so do not run them at the same time as it. Each folder has its own build notes (for example
[legacy-demos/exp05-replication/README-EXPERIMENT-5.md](legacy-demos/exp05-replication/README-EXPERIMENT-5.md)).

---

## Team

- **Nidhi Dhyani** — `2024300050`
- **Rohan Dhumal** — `2024300049`
- **Swanand Dixit** — `2024300052`
- **Jai Desai** — `2024300041`

<!-- Team details are maintained by Rohan. -->
