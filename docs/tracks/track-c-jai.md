# Track C — Jai

- **Owner:** Jai
- **Branch:** `jai`
- **Reviewer:** Rohan
- **Shared rules:** [docs/tracks/README.md](README.md)

---

## Experiment 5 — Data Consistency and Replication (Phase 6, moduleId: replication)

- **Reuse:** `legacy-demos/exp05-replication`; Appendix B Exp 5.
- **Special rules:** a single `apply()` guard using `ConcurrentHashMap.compute`; last-writer-wins on `(lamportTime, nodeId)`; epochs supported from the start (Exp 8 needs them); the 450 ms async delay is simulated and labelled; the primary comes from a module-local selector (TODO(L1)).

### Steps
- [ ] E5a — `DataItem`, `DataStore`, last-writer-wins, anti-entropy, out-of-order injection, epochs and `ReplicationStats` as pure classes. Needs: none.
- [ ] E5b — replication NodeService on `ports().replication()` (710k): sync and async writes, anti-entropy; document its public API under "Interfaces for other tracks" (Track B builds Exp 8 on it). Needs: E5a.
- [ ] E5c — `ReplicationModule` (lab 5): write (sync/async), read per replica, crash or recover a backup, anti-entropy, inject a stale update; the health table; metrics; fixtures. Needs: E5b.
- [ ] E5d — page and end-to-end check. Needs: E5c, E2d.

---

## Experiment 1 — Client-Server via Java RMI (Phase 10, moduleId: rmi)

- **Reuse:** none.
- **Special rules:** `java.rmi.server.hostname=127.0.0.1`; crash unexports the RMI objects.

### Steps
- [ ] E1a — the `NodeEndpoint` remote interface (ping, nodeInfo, echo, computeHash(rounds)), its implementation, and a local-versus-remote timing helper. Needs: E5d.
- [ ] E1b — an RMI registry per node on `ports().rmi()` (110k); export on start and recover, unexport on crash. Needs: E1a.
- [ ] E1c — `RmiModule` (lab 1): invoke a method on a node (result, round-trip time, payload size, stub class), the local-versus-remote comparison, registry listing. Needs: E1b.
- [ ] E1d — page and end-to-end check. Needs: E1c, E2d.

---

## Experiment 9 — MPI Collectives (Phase 11, moduleId: mpi)

- **Reuse:** none.
- **Special rules:** collectives run over the Exp 1 RMI layer; the root comes from a module-local selector (TODO(L1)); a crashed node is reported, and the operation never hangs.

### Steps
- [ ] E9a — broadcast, scatter and gather logic. Needs: E1d.
- [ ] E9b — `CollectiveEndpoint` over RMI on every node. Needs: E9a, E1b.
- [ ] E9c — `MpiModule` (lab 9) and its API. Needs: E9b.
- [ ] E9d — page and end-to-end check. Needs: E9c, E2d.

---

## Experiment 10 — Parallel Matrix Multiplication (Phase 12, moduleId: matrix)

- **Reuse:** none.
- **Special rules:** scatter row blocks of A, broadcast B, compute each block on that node's Exp 2 executor, gather; check the result against the sequential product (checksum); cap the size in the public profile; report speedup honestly (bounded by the machine's cores).

### Steps
- [ ] E10a — matrix generation, row-block partitioning and the sequential product. Needs: E9d.
- [ ] E10b — the parallel multiply over the collectives and executors. Needs: E10a, E9b, E2b.
- [ ] E10c — `MatrixModule` (lab 10) and its API; replace `web/TestModuleConfig`'s fake lab-10 module mechanism so tests no longer clash with the real lab 10. Needs: E10b.
- [ ] E10d — page and end-to-end check. Needs: E10c, E2d.

---

## Interfaces for other tracks

(none yet)

---

## Known issues

(none yet)

---

## Progress log

- 2026-10-07 track file created.
