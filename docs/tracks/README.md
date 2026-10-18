# Parallel tracks: shared rules

## 1. Tracks and owners

- **Track A — Nidhi** (branch `nidhi`): Exp 2 -> Exp 6, plus the shared experiment-page kit.
- **Track B — Swanand** (branch `swanand`): Exp 4 -> Exp 8, plus `core/failure/FailureDetector` and the cluster roles API.
- **Track C — Jai** (branch `jai`): Exp 5 -> Exp 1 -> Exp 9 -> Exp 10.
- **Track D — Rohan** (branch `rohan`): Exp 3 -> Exp 7, plus `EventLogExporter` and `GET /api/events/export`. Rohan reviews and merges every pull request.

**Step ids:** `E<experiment><letter>`, for example `E4b` = Experiment 4, step b. (This avoids confusion with Phase 9A.)

---

## 2. Branches and pull requests

- Everyone works only on their own branch; nobody commits to `main` directly (`main` is protected).
- One step = one pull request into `main`. Rohan merges with **"Create a merge commit"** (never squash or rebase, so long-lived branches stay in sync and authorship is kept). Branches are never deleted after merging.
- Before starting a step: merge the latest `origin/main` into your branch.
- While your pull request is open, push only review fixes to your branch. You may run the next step's Stage A (read-only) while waiting.
- The CI checks `backend` and `frontend` must pass before merging.
- **Pull-request description:** step id, summary, files changed, the backend and frontend test totals, and (for step d) the manual-check results.
- Rohan's own pull requests: another teammate looks at them before Rohan merges.

---

## 3. Agents and git

AI agents never run any git command, not even read-only ones. People run every git command. Agents never edit `CLAUDE.md`, `docs/HANDOFF.md`, `README.md` or another track's file; they record notes, deviations and known issues in their own track file.

---

## 4. Step anatomy (every experiment has steps a, b, c, d)

- **a Algorithm:** pure classes (no sockets, no Spring) under `com.udcf.modules.<moduleId>`, ported from the legacy demo where one exists, with unit tests.
- **b Transport:** the module's `NodeService`(s) on the shared cluster (R1, R2), started lazily via `ClusterNode.ensureService`, using only `ClusterNode.ports()`; crash closes sockets (or unexports RMI objects) and recover rebinds; every inter-node message carries the sender's Lamport time and the receiver calls `clock.update` (link L4); events are published with `module = <moduleId>`. Integration tests on real 127.0.0.1 sockets.
- **c Module and API:** `<Name>Module` implements `ExperimentModule` (a Spring bean, its lab number, status, reset, `ModuleActionGuard` for long actions -> HTTP 409); `<Name>Controller` under `/api/modules/<moduleId>/...`; DTO records in `dto/`; metrics using `MetricNames` with a `node_id` tag per meter (R5); MockMvc tests; and contract fixtures (real JSON captured from the running backend) saved under `frontend/src/test/fixtures/<moduleId>/`.
- **d Page and end-to-end check:** the module page built with the shared experiment-page kit (section 7 below), live through `/topic/modules/<moduleId>`, tested against the contract fixtures; then the module's "done when" from HANDOFF Section 7, run manually by the person from a checklist the agent writes.

---

## 5. File ownership

- **Rohan only:** `CLAUDE.md`, `docs/HANDOFF.md`, `README.md`, `.github/`, `render.yaml`, `frontend/vercel.json`, `backend/Dockerfile`, `pom.xml`, `frontend/package.json` dependencies (any new dependency needs Rohan's approval in the pull request; R15).
- **Shared, add-only** (conflicts expected and resolved by keeping both sides): `application.yml` and `application-public.yml` (each module adds its own block under `udcf.<moduleId>`, placed in lab-number order; the public profile gets smaller values if needed for 512 MB); `core/metrics/MetricNames` (add constants, never rename).
- **Assigned shared code** (only the named track builds it; others reuse it):
  - `core/failure/FailureDetector` -> Track B, step E4b.
  - Cluster roles API (roles on `ClusterNode`, `NodeDto.roles`, the "LEADER" role) -> Track B, step E4c.
  - Frontend experiment-page kit and module registry -> Track A, step E2d.
  - `EventLogExporter` and `GET /api/events/export` (link L5) -> Track D, step E7c.
  - Fixing tests that assume an empty or exact module registry (`CoreContextTest`, `ModuleControllerTest`, frontend sidebar or catalog tests) -> whichever track's "c" step registers the first real module; others then find it fixed.
  - `web/TestModuleConfig` (fake module with lab number 10) -> Track C, step E10c.
- Never change another track's module package.

---

## 6. Backend conventions

- Package `com.udcf.modules.<moduleId>`, with module ids from HANDOFF Section 3 (`rmi`, `multithreading`, `clocksync`, `election`, `replication`, `loadbalancing`, `mapreduce`, `faulttolerance`, `mpi`, `matrix`).
- Role selection until Phase 9A: a module needing a leader, primary, coordinator, daemon or root uses a module-local `<Name>RoleSelector` that picks the lowest-id live node, marked `// TODO(L1): replaced by the shared role provider in Phase 9A`. Only the election module publishes cluster roles. From step 9A.1 the shared role provider exists; the owning tracks remove their selectors in 9A.1 (Exp 8), 9A.3 (Exps 5, 9, 10) and 9A.4 (Exps 3, 7).
- Anything simulated is flagged in its DTO (for example `simulated: true` plus a reason) and shown with the Simulated badge (R7).
- Tests that crash nodes or reset the cluster use their own Spring context, so they never disturb other tests.
- Config only from YAML (hard rule 6); no hard-coded ports, sizes or origins.
- Every fixed port used in a TEST (Java tests, test YAML/properties, test configs) must be below 32768, because Linux hands out outgoing ports from 32768-60999 (Windows 49152-65535) and a test that binds inside that range can collide with the OS. Production ports (1101.., 6001.., 7001..) are already fine. Port blocks: Nidhi 21000-21999, Rohan 24100-24899, Swanand 26100-26899, Jai 28100-28899.

---

## 7. Frontend conventions (the shared experiment-page kit, built once in E2d)

- Each module page lives in `frontend/src/modules/<moduleId>/`. Its `index.jsx` does:
  ```javascript
  export default { id: '<moduleId>', Page };
  ```
- `frontend/src/modules/registry.js`, exactly:
  ```javascript
  // Registers every experiment module page automatically. Each module folder's
  // index.jsx default-exports { id, Page }.
  const loaded = import.meta.glob('./*/index.jsx', { eager: true });
  export const modulePages = Object.fromEntries(
    Object.values(loaded).map((m) => [m.default.id, m.default.Page]),
  );
  ```
- The existing `ExperimentPage` renders `modulePages[experiment.id]` (passing `experiment`) when it exists; otherwise it keeps today's placeholder.
- `frontend/src/components/experiment/` holds the kit:
  - `ExperimentLayout` (props: `experiment`, `howItWorks`, `controls`, `visualisation`, `measurements`, `whatToNotice`; renders the HANDOFF 8.4 sections in order and adds the module event log automatically)
  - `MetricCard` (`label`, `value`, `unit`, `hint`, `simulated`)
  - `SimulatedBadge` (`reason`, shown as text and tooltip)
  - `ModuleEventLog` (`moduleId`; loads `GET /api/events?module=<id>&limit=50`, appends `/topic/modules/<id>`, Lamport values in monospace, clears on `CLUSTER_RESET`)
- Every page follows the R17 design brief: one bold element (the module's live visualisation); no grid of identical cards; no ALL-CAPS labels; no "A · B · C" strings; no "→" on buttons; monospace only for Lamport values, ports, ids and timestamps; motion only in response to actions, and none under `prefers-reduced-motion`; plain sentence-case copy; responsive to 375 px; visible keyboard focus; colour never the only signal.

---

## 8. Cross-track dependencies (must be merged into main before starting the step)

- Every "d" step except E2d needs E2d.
- E6b needs E2b.
- E8b needs E5b, E4b and E4c.
- E10b needs E9b and E2b.
- Phase 9A starts when E2d, E6d, E3d, E7d, E4d, E8d and E5d are all merged (met on 2026-10-16). Phase 9A steps and their dependencies: Section 11.

---

## 9. Reporting rule

Every number and claim in an agent's report must come from command output it actually ran, pasted verbatim.

---

## 10. Test ports (Linux safety rule)

Every fixed port used in a TEST (Java tests, test YAML/properties, test configs) must be below 32768, because Linux hands out outgoing ports from 32768-60999 (Windows 49152-65535) and a test that binds inside that range can collide with the OS. Production ports (1101.., 6001.., 7001..) are already fine.

Assigned test port blocks:
- **Nidhi:** `21000-21999`
- **Rohan:** `24100-24899`
- **Swanand:** `26100-26899`
- **Jai:** `28100-28899`

---

## 11. Phase 9A — system integration (R19, from 2026-10-16)

Phase 13 is merged into Phase 9A (HANDOFF R19), so Phase 9A wires all six links and covers all ten experiments. Each step is one pull request from its owner's branch, run with the same loop as the module steps: Stage A plan, approval, build, checks, docs, report; then local checks, a manual checklist on the real backend for anything a person sees, a clean-clone Linux run, commit, push and PR.

| Step | Owner | Work | Needs | Status |
|---|---|---|---|---|
| 9A.0 | Rohan | Docs: R19, this plan, docs/tracks/README.md Section 11, CLAUDE.md | — | complete |
| 9A.1 | Swanand | Shared role provider under `com.udcf.core`: LEADER from the election, PRIMARY and BACKUP join `NodeRole`. Experiment 8 promotes through the election (`FailoverRoleSelector` removed, `EpochAuthority.onLeaderElected` fed from leader changes, a planned-handover target resynchronised first). L2 verified: Experiments 4 and 8 act on the same `FailureDetector` suspect signal | 9A.0 | complete |
| 9A.2 | Nidhi | Link audit and backend integration tests for L3 (Experiment 6 dispatches into Experiment 2 executors), L4 (Lamport time on every inter-node message, including the RMI calls of Experiments 1, 9 and 10), L5 (event export to MapReduce) and L6 (Experiment 10 uses the Experiment 9 collectives over Experiment 1 RMI). Gaps in another track's package are recorded and reported to its owner, not fixed | 9A.0 | complete |
| 9A.3 | Jai | Experiment 5 primary, Experiment 9 MPI root and Experiment 10 root come from the role provider; `TODO(L1)` selectors removed; the Experiment 5 / Experiment 8 interference removed; fixtures recaptured; page hints updated | 9A.1 | complete |
| 9A.4 | Rohan | Experiment 3 Berkeley time daemon and Experiment 7 coordinator come from the role provider; `TODO(L1)` selectors removed; fixtures recaptured; page hints updated | 9A.1 | complete |
| 9A.5 | Jai | Timeline page `/timeline`: events from every module ordered by `(lamportTime, nodeId)`, module and node filters, live updates | 9A.0 | complete |
| 9A.6 | Swanand | `com.udcf.scenario`: scenario engine, API and narration; scenarios "The leader dies mid-write", "A slow node under load" and "Split-brain that never happens"; the leader-crash end-to-end test | 9A.3, 9A.4 | complete |
| 9A.7 | Jai | Scenarios "Analyse the incident with MapReduce" and "The leader dies during a matrix multiply"; the Scenarios page `/scenarios` | 9A.6 | complete |
| 9A.8 | Nidhi | Overview integration map showing the six links live; demo polish (R17) of Overview, Cluster, the ten experiment pages and Timeline; nothing shown as "planned" | 9A.3, 9A.4, 9A.5 | complete |
| 9A.9 | Rohan | Demo package: `scripts/start-all.ps1` and `scripts/stop-all.ps1` (dev mode), `docs/demo/udcf-demo.md`, a refreshed public lite deployment | 9A.7, 9A.8 | complete; the refreshed public lite redeploy moves to Phase 16 |

**Phase 9A is done when** crashing the leader triggers an election, then a new time daemon, replication failover, a new MapReduce coordinator and a new MPI root, all visible in one timeline; all five scenarios run end to end with narration; events from all ten modules interleave correctly by `(lamportTime, nodeId)`; one command starts the local demo; and a full walkthrough shows no placeholder content.

Rules for Phase 9A:
- In their first 9A step, each owner adds a "Phase 9A (R19)" section to their own track file, with ticks for their own steps, and logs progress there as before.
- Ownership: the shared role provider -> Swanand (9A.1), and every other track calls it. `com.udcf.scenario` -> Swanand (9A.6); Jai adds his two scenarios in 9A.7 without changing the engine's API (add-only; anything more is agreed in the PR). `backend/src/test/java/com/udcf/integration/` -> Nidhi (9A.2). The Timeline page and its components -> Jai (9A.5). The Scenarios page -> Jai (9A.7). The Overview integration map -> Nidhi (9A.8). `scripts/` and `docs/demo/` -> Rohan (9A.9).
- 9A.8 may change presentation only (styles, copy, loading/empty/error states) in another track's page; behaviour changes go to that page's owner. The owner of every page touched reviews the 9A.8 pull request.
- Test ports stay in each person's block (section 10).
