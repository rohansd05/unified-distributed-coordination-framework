# Synthetic MapReduce fixtures (9A.4)

**These are not captures from the running backend.** They stand in for responses a real backend cannot be made
to produce on demand.

| File | Where the body comes from | Why synthetic |
|---|---|---|
| `error-409-no-leader.json` | Copied exactly (every byte, field order kept) from the response body of `MapReduceControllerTest.noLeader` on 2026-10-17: `POST /api/modules/mapreduce/runs` on a module whose role provider has no election module, so no leader can be elected while nodes are live. | Not capturable from a running backend: with every node down the earlier 409 "No live worker" answers first, and with any node live Experiment 4 elects it. |
