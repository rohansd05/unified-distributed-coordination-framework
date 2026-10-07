# Track D — Rohan

- **Owner:** Rohan
- **Branch:** `rohan`
- **Reviewer:** Rohan
- **Shared rules:** [docs/tracks/README.md](README.md)

---

## Experiment 3 — Clock Synchronization (Phase 4, moduleId: clocksync)

- **Reuse:** `core/clock/LamportClock` (never duplicate it); `legacy-demos/exp03-clock-sync` (`ClockSyncDemo`, `ClockEventLog` causal check); Appendix B Exp 3.
- **Special rules:** the Berkeley drift is simulated and labelled (R11); the time daemon comes from a module-local selector (TODO(L1)).

### Steps
- [x] E3a — the causal-invariant checker, the total order (lamportTime, nodeId), Berkeley averaging with an outlier threshold, and the drift model, as pure classes. Needs: none.
- [ ] E3b — clock UDP service on `ports().clock()` (600k): Lamport send and receive, Berkeley poll and adjust rounds. Needs: E3a.
- [ ] E3c — `ClockSyncModule` (lab 3): a local event, send X to Y, random traffic for N seconds, a Berkeley round, verification; metric `distributed_clock_value` per node; fixtures. Needs: E3b.
- [ ] E3d — the space-time diagram page and end-to-end check. Needs: E3c, E2d.

---

## Experiment 7 — MapReduce (Phase 9, moduleId: mapreduce)

- **Reuse:** `legacy-demos/exp07-mapreduce` (jobs, `JobRegistry`, `LogFields`, the pipeline); Appendix B Exp 7.
- **Special rules:** the average-latency job carries "sum;count", never an average; the coordinator comes from a module-local selector (TODO(L1)); 15 s task timeout with retry on another worker.

### Steps
- [ ] E7a — the jobs and the split, map, combine, shuffle, partition and reduce pipeline as pure classes. Needs: E3d.
- [ ] E7b — MapReduce workers on `ports().mapreduce()` (730k) with task retry. Needs: E7a.
- [ ] E7c — `MapReduceModule` (lab 7) and its API; inputs: bundled sample, uploaded .txt (size-capped), live event log; build `EventLogExporter` and `GET /api/events/export` (link L5). Needs: E7b.
- [ ] E7d — the pipeline page and end-to-end check. Needs: E7c, E2d.

---

## Interfaces for other tracks

(none yet)

---

## Known issues

(none yet)

---

## Progress log

- 2026-10-07 track file created.
- 2026-10-07 E3a done: causal checker, total order, Berkeley averaging and drift model implemented as pure classes; backend 282 tests, frontend 126 tests; deviations: none
