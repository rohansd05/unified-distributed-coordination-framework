# Track B — Swanand

- **Owner:** Swanand
- **Branch:** `swanand`
- **Reviewer:** Rohan
- **Shared rules:** [docs/tracks/README.md](README.md)

---

## Experiment 4 — Bully and Ring Election (Phase 5, moduleId: election)

- **Reuse:** `legacy-demos/exp04-election` (`ElectionNode`, split into algorithm and transport); Appendix B Exp 4.
- **Special rules:** hard rule 7 (ring election messages are never handled on the listener thread; keep the comment explaining the deadlock); `compareAndSet` prevents overlapping elections.

### Steps
- [ ] E4a — Bully and Ring as pure classes, plus the consensus check. Needs: none.
- [ ] E4b — election UDP service on `ports().election()` (700k), and the SHARED `core/failure/FailureDetector` (heartbeats on the election channel, 700 ms interval, 2500 ms timeout; publishes suspect and alive events; link L2). Document its API under "Interfaces for other tracks". Needs: E4a.
- [ ] E4c — `ElectionModule` (lab 4): start Bully or Ring from node X, the current leader, the consensus check, automatic re-election when the leader crashes; add the cluster roles API (roles on `ClusterNode`, `NodeDto.roles`, "LEADER" shown in the top bar); metrics `distributed_leader_elections_total` and `distributed_election_duration`; fixtures. Needs: E4b.
- [ ] E4d — page and end-to-end check. Needs: E4c, E2d.

---

## Experiment 8 — Fault Tolerance (Phase 7, moduleId: faulttolerance)

- **Reuse:** `legacy-demos/exp08-fault-tolerance`; Appendix B Exp 8.
- **Special rules:** built on Track C's replication service and the shared `FailureDetector` (L2); promotion through the election module (no second Bully); epochs; backups refuse stale epochs; a recovering old primary demotes itself; the 220 ms async delay is simulated and labelled; data loss is measured against the acknowledged keys. The legacy demo's own timings (300/1200/800 ms) versus the shared detector's (700/2500 ms) are decided in the E8a plan.

### Steps
- [ ] E8a — epoch rules, `SystemUpdate`, `UpdateStore` and `FailoverMetrics` as pure classes. Needs: E4d.
- [ ] E8b — failover on the replication service plus the `FailureDetector`, promoting through election. Needs: E5b (Track C), E4b, E4c.
- [ ] E8c — `FaultToleranceModule` (lab 8): start and stop an update stream, crash the primary, sync/async toggle, recover the old primary, the four measurements. Needs: E8b.
- [ ] E8d — page and end-to-end check. Needs: E8c, E2d.

---

## Interfaces for other tracks

(none yet)

---

## Known issues

(none yet)

---

## Progress log

- 2026-10-07 track file created.
