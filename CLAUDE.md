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
- R16 Phase 9A (after Phase 9): seven-experiment system demo; links L1–L5, timeline,
     scenarios, demo polish, demo package. Phase 13 adds L6, the MPI root, and Exps 1, 9, 10.
- R17 Distinctive, non-template UI (frontend-design guidance); demo polish in 9A.4
     before Phase 14.

## Hard rules
1. One phase step at a time. Never generate the whole project at once.
2. State exactly which files you will create or change before changing them.
3. Separate algorithm from transport: pure algorithm classes, thin NodeService transports.
4. Constructor injection only. No Lombok. Records for DTOs.
5. Ask before deleting or rewriting working code.
6. No hard-coded ports, cluster size or origins outside configuration.
7. Ring election messages must never be handled on the listener thread (known deadlock).
8. Never print or commit secrets. .env stays gitignored.
9. After each completed step, update Current Position below. Never commit or push;
   the user does (docs/HANDOFF.md Section 12).

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
Phase: 2
Step: 2.3
Status: not started

### Completed steps
- 0.1 docs/HANDOFF.md committed (77f5704)
- 0.2 Standalone demos moved to legacy-demos/; exp03 layout repaired; exp07 sample log
  tracked (ac75bfe, bc70dfa)
- 0.3 backend/ created from the Exp 2 app; Maven Wrapper 3.3.2 (Maven 3.9.9, only-script,
  SHA-256 pinned); 59 tests in 11 classes pass (9853164)
- 0.4 Root CLAUDE.md; docs/CLAUDE.md removed; .env.example replaced; .gitattributes added
  (index was already LF; working copies refreshed)
- 0.5 README.md rewritten to describe only what exists; requirements.txt and legacy
  Exp 2 README commands fixed
- 1.1 Core platform: core/clock (LamportClock ported), core/events (ClusterEvent,
  EventRingBuffer, ClusterEventBus), core/cluster (Cluster, ClusterNode, NodeService,
  capacities, ports), core/module (ExperimentModule, ModuleRegistry,
  ModuleActionGuard); 107 core tests
- 1.2 WebSocket/STOMP on /ws (topics /topic/events, /topic/cluster, /topic/modules/{id});
  CORS and WebSocket origins from UDCF_ALLOWED_ORIGINS; 18 new tests
- 1.3 REST API: /api/system/{health,info}, /api/cluster (+ node crash/recover, reset),
  /api/modules, /api/events; ProblemDetail errors; clean-slate reset; 42 new tests
- 1.4 Per-meter node_id tagging, global tags application and mode; cluster meters
  (distributed_node_status, events published and dropped); local and public profiles;
  backend on ${PORT:8080}; node1–3 profiles retired; 19 new tests
- Phase 1 complete
- 2.1 frontend/ skeleton: Vite 5.4, React 18 (JSX), Tailwind 3.4, shadcn@2.3.0 (new-york,
  JSX), dark tokens from Section 8.3, self-hosted Inter and JetBrains Mono, Vitest + RTL +
  jsdom; 6 tests
- 2.2 Layout (sidebar, top bar), routing for all Section 8.2 pages as stubs, shared
  experiment-page skeleton (Section 8.4), static experiment catalog; react-router-dom
  6.30.6; full-height app shell (main and sidebar scroll independently), responsive
  sidebar drawer below md (backdrop, Escape, focus management), themed dark scrollbars
  (scrollbar-color, thin); 45 tests

### Known issues
- docs/HANDOFF.md Section 4.4: problems 1, 2, 3, 4, 10 and 13 are resolved; the rest are
  scheduled there.
- Stale `cd backend` in the MultithreadingDemo.java Javadoc (legacy and backend copies). The
  legacy copy stays as is (R9); the backend copy is reworked in Phase 3.
- backend/ still contains the Exp 2 app (com.udcf.threadpool etc.) beside the new core/
  and web/ packages; Exp 2 reads udcf.node.id: 1 until Phase 3, when it moves to
  modules/multithreading.
- docs/HANDOFF.md mentions docs/CLAUDE.md in Sections 1, 4 and 5 as history; left unchanged.
- Node roles (leader/primary/backup) arrive with election in Phase 5; until then the
  cluster reports none.
- web/TestModuleConfig registers a fake module with lab number 10; when the real Matrix
  module arrives (Phase 12) the test must use a different mechanism or lab number.
- public profile shows full /actuator/health details (including the disk path); set
  show-details: never in application-public.yml in Step 2.5.
- The root .env may still hold pre-Phase-0 keys (for example VITE_WS_URL=http://...).
  Refresh it from .env.example before Step 2.3.
- react-router-dom 6.30.6 carries two moderate advisories: GHSA-wrjc-x8rr-h8h6 (open
  redirect via a backslash in a Link/navigate target) and GHSA-337j-9hxr-rhxg (SSR
  hydration only; not used here). Rule: never build a Link/navigate target from untrusted
  input. Both are fixed only in react-router 7.18+, a major upgrade outside R15.

### Deviations from plan
- Step 0.2: legacy Exp 2 has no javac route. Its build check is
  .\backend\mvnw.cmd -f legacy-demos\exp02-multithreading\pom.xml test (passes, 59 tests).
- Step 0.3: the wrapper was generated by maven-wrapper-plugin 3.3.2 using a temporary,
  checksum-verified Maven 3.9.9, rather than hand-authored.
- Step 0.4: hard rule 9 amended. Claude Code updates Current Position but never commits or
  pushes (docs/HANDOFF.md Section 12).
- The legacy Exp 8 demo uses ports 7401–7403, which no target range covers. Legacy demos and
  the backend still cannot run at the same time, because the other ranges overlap.
- legacy-demos/exp07-mapreduce/data/framework-events.log was produced outside the repository,
  and nothing regenerates it. It is the committed fallback for R13 and is pinned to LF, so
  its SHA-256 (5b6e42a2…) matches on every machine.
- Step 0.5: legacy Exp 2 README now builds with ..\..\backend\mvnw.cmd (a command fix
  beyond R9's path-only rule, because mvn is not on PATH).
- Step 1.1: split into two parts (clock + events; cluster + modules). EventLogExporter
  deferred to Phase 9, where its format is defined by the MapReduce job.
- Step 1.2: com.udcf.config.CorsConfig (hard-coded origins) deleted and replaced by
  com.udcf.web.CorsConfig: same path (/api/**), methods and headers, origins from
  UDCF_ALLOWED_ORIGINS (default http://localhost:5173,http://127.0.0.1:5173, as before),
  no credentials. It carries @EnableConfigurationProperties(UdcfWebProperties.class)
  because @WebMvcTest slices filter out @ConfigurationPropertiesScan.
- Step 1.3: added web/ModuleController for GET /api/modules (not in Section 6.5).
  Reset is a clean slate (user decision): recover all nodes, reset modules, reset all
  Lamport clocks, clear event history, publish CLUSTER_RESET. Added
  EventRingBuffer.clear, ClusterEventBus.clearHistory, Cluster.resetClocks and
  Cluster.clusterClock. /api/events/export deferred to Phase 9.
- Step 1.4: node1–3 profiles retired from backend/ (user decision); the backend runs on
  ${PORT:8080}. JVM flags (-XX:MaxRAMPercentage=75 -Xss512k) deferred to the Dockerfile in
  Step 2.5. Added ClusterEventBus.publishedCount().
- Step 1.4 (Exp 2): com.udcf.monitoring.MetricsConfig deleted. It added global common tags
  node_id and role to every meter in the JVM, which R5 forbids now that one JVM hosts
  several nodes; role is dropped (roles arrive with election in Phase 5).
- Step 1.4 (Exp 2): ThreadPoolMetrics now tags every meter with node_id from udcf.node.id
  (new constructor parameter); ThreadPoolMetricsTest's setUp passes it (one line) and a new
  test checks node_id on every Exp 2 meter.
- Step 2.1: the handoff token 'accent' (#02C39A) is the 'success' colour in the
  frontend, because shadcn reserves 'accent' for hover surfaces. Vite 5.4 is kept
  (user decision) although it no longer receives security patches; the risk is
  limited to the local dev server. Fonts are self-hosted via @fontsource-variable
  (user decision).
- Step 2.2: the sidebar uses a static catalog of the ten experiments, because the module
  registry is empty until Phase 3. Step 2.3 merges live status from GET /api/modules
  (unknown modules show as Planned). Vite reads the root .env (envDir '..').
- Plan amended to HANDOFF v1.1 (2026-10-04): Phase 9A added; Phase 13 reduced to L6, the MPI
  root, and Exps 1, 9, 10 (user decision).
