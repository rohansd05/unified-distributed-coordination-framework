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
- Role selection until Phase 9A: a module needing a leader, primary, coordinator, daemon or root uses a module-local `<Name>RoleSelector` that picks the lowest-id live node, marked `// TODO(L1): replaced by the shared role provider in Phase 9A`. Only the election module publishes cluster roles.
- Anything simulated is flagged in its DTO (for example `simulated: true` plus a reason) and shown with the Simulated badge (R7).
- Tests that crash nodes or reset the cluster use their own Spring context, so they never disturb other tests.
- Config only from YAML (hard rule 6); no hard-coded ports, sizes or origins.

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
- Phase 9A starts when E2d, E6d, E3d, E7d, E4d, E8d and E5d are all merged.

---

## 9. Reporting rule

Every number and claim in an agent's report must come from command output it actually ran, pasted verbatim.
