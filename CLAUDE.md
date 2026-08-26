# CLAUDE.md — Project Instructions for Claude Code

You are the technical lead for this project. Read this file fully before any task.

---

## Project

**Unified Distributed Coordination Framework (UDCF)** — a non-cloud, locally
deployable distributed-systems platform that integrates ten laboratory experiments
into ONE functioning distributed system with a professional React dashboard and real
Prometheus/Grafana monitoring.

This is an academic project. Prefer a working, well-explained, academic-scale
implementation over an over-engineered production system.

---

## Locked Architectural Decisions

Do not change these without explicitly raising the issue first.

- **D1** ONE backend codebase runs as FOUR JVM instances, differentiated only by
  Spring profile: `gateway` (8080), `node1` (8081), `node2` (8082), `node3` (8083).
  Never hard-code a port, node ID, role or peer address outside profile YAML.
- **D2** PostgreSQL is installed natively. Prometheus and Grafana run in Docker.
- **D3** REST for commands and queries; STOMP WebSocket (`/ws`, broker `/topic`) for
  live event push to the dashboard.
- **D4** One database `udcf`, one schema per instance: `gateway`, `node1`, `node2`,
  `node3`. Each node reads and writes only its own schema — this is what makes
  replication real rather than simulated.
- **D5** Java RMI is used ONLY in Experiment 9 (MPI collectives) and Experiment 10
  (parallel matrix multiplication). Everything else uses HTTP.
- **D6** Every metric carries `node_id` and `role` tags via a global
  `MeterRegistryCustomizer`. Prometheus scrapes four targets; without these tags the
  series collide.
- **D7** No cloud services of any kind. No AWS, Azure, GCP, Firebase, hosted
  databases or hosted queues.
- **D8** Every production file gets a corresponding test file. If a file genuinely
  does not need one (declarative config, behaviourless DTO/record, enum, generated
  bootstrap class), say so explicitly in a comment in the file and in your summary.

---

## Hard Rules

1. Work incrementally. Never generate the whole project at once.
2. Explain the purpose of a module before implementing it.
3. State exactly which files you are creating or modifying, with full paths.
4. Provide complete file contents, not fragments.
5. Give exact PowerShell commands (this is a Windows machine).
6. **Never fabricate metrics.** Read live state from the real object —
   `ThreadPoolExecutor.getActiveCount()`, the actual `AtomicLong`, the real database.
   No placeholder numbers, no `Math.random()`, no hard-coded arrays.
7. Keep React connected to the real backend. No mock data in committed code.
8. Keep nodes genuinely communicating over the network.
9. Add tests alongside production code, in the same response.
10. Integrate each experiment into the shared framework. Nothing is a standalone demo.
11. Do not introduce a new dependency without saying why and asking first.
12. Do not use Lombok. Use plain classes and Java records.
13. Do not use browser `localStorage`/`sessionStorage` in artifacts; in the real
    frontend, use it only for onboarding-completion state.
14. Keep the UI professional and dark-themed. Do not copy Grafana's look.
15. Ask before deleting or rewriting existing working code.

---

## Tech Stack

Backend: Java 21, Spring Boot 3.3.5, Spring Web, Spring Data JPA, Actuator,
Micrometer + Prometheus registry, Spring WebSocket, Java RMI, Maven.

Frontend: React 18, Vite, Tailwind CSS v3, shadcn/ui, Recharts, Lucide React,
@stomp/stompjs, sockjs-client, axios, react-router-dom 6.

Data/Monitoring: PostgreSQL 16, Prometheus, Grafana, Docker Compose.

Testing: JUnit 5, Mockito, AssertJ, MockMvc, Awaitility, Vitest,
React Testing Library, Playwright.

---

## Conventions

**Java**
- Base package `com.udcf`. One package per experiment (`threadpool`, `sync`,
  `election`, `replication`, `loadbalancer`, `mapreduce`, `fault`, `mpi`, `matrix`).
- Constructor injection only. No field `@Autowired`.
- Records for DTOs. Enums for fixed sets.
- Node identity injected as `@Value("${udcf.node.id}")` or via `NodeProperties`.
- Javadoc on every class explaining *why* it exists, not just what it does.
- Thread-safe types where state is shared: `AtomicLong`, `AtomicReference`,
  `volatile`, `ConcurrentHashMap`. Justify the choice in a comment.

**Metrics**
- Names follow `distributed_*`.
- Register gauges bound to the live object so Prometheus reads current state at scrape.

**REST**
- `/api/{experiment}/...`. `202 Accepted` for async submissions, `204` for resets.
- Validate request bodies with `jakarta.validation`.

**React**
- Functional components with hooks. One component per file.
- API calls only through `src/services/`. Never call axios from a component.
- Every page handles loading, empty and error states.
- Tailwind utility classes; shadcn/ui primitives for cards, tables, badges, toasts.

**Tests**
- Backend test files mirror the main package path.
- Frontend: `Component.test.jsx` beside the component.
- Test the behaviour that matters (concurrency safety, rejection handling, status
  transitions), not just that a method returns non-null.

**Git**
- Branch per phase: `feature-foundation`, `feature-clock-sync`, `feature-election`, ...
- Conventional commits: `feat:`, `fix:`, `test:`, `docs:`, `chore:`.

---

## Commands

```powershell
# Backend
cd backend
mvn clean install
mvn test
mvn spring-boot:run "-Dspring-boot.run.profiles=node1"

# All four instances
.\scripts\start-all.ps1
.\scripts\stop-nodes.ps1

# Frontend
cd frontend
npm install
npm run dev
npm run test

# Monitoring
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml down

# Everything
.\scripts\run-tests.ps1
```

---

## Ports

| Service | Port |
|---|---|
| Frontend (Vite) | 5173 |
| Gateway | 8080 |
| Node 1 | 8081 |
| Node 2 | 8082 |
| Node 3 | 8083 |
| PostgreSQL | 5432 |
| Prometheus | 9090 |
| Grafana | 3000 |
| RMI Node 1 / 2 / 3 | 1101 / 1102 / 1103 |

---

## Phase Plan

Phase 0  Environment and repository scaffolding
Phase 1  Backend foundation (identity, health, node registry, WebSocket, database)
Phase 2  Frontend foundation (shell, routing, services, STOMP, dashboard)
Phase 3  Experiment 2  — Multithreading
Phase 4  Experiment 3  — Clock Synchronization (Lamport)
Phase 5  Experiment 4  — Leader Election (Bully + Ring)
Phase 6  Experiment 5  — Replication
Phase 7  Experiment 8  — Fault Tolerance
Phase 8  Experiment 6  — Load Balancing
Phase 9  Experiment 7  — MapReduce
Phase 10 Experiment 9  — MPI Collectives (RMI)
Phase 11 Experiment 10 — Parallel Matrix Multiplication (RMI)
Phase 12 Monitoring (Prometheus scrape config, provisioned Grafana dashboards)
Phase 13 Onboarding and UI polish
Phase 14 Testing, E2E and documentation

---

## Current Position

**Phase:** 0
**Step:** 0.3
**Status:** not started

> Update these three lines at the end of every completed step. This is the single
> source of truth for where the project stands, and is what allows work to resume
> in a fresh session.

### Completed Steps
- (none yet)

### Known Issues
- (none yet)

### Deviations From Plan
- (none yet)