# Synthetic fault tolerance fixtures (E8d)

**Not captures.** Each file is a copy of a real capture in the parent folder with the edits listed
here, made by a script on 2026-10-16. They stand for states the running backend cannot produce on
demand (see "States that cannot be produced for real here" in `../README.md`). A test that uses one
says "synthetic" in its name. The real fixtures in the parent folder are unchanged.

| File | Copied from | Edits |
|---|---|---|
| `status-split-brain-failed.json` | `status-stream-running.json` | node 2 also PRIMARY and serving; `splitBrain.passed: false`, `livePrimaries: [1, 2]`, one `DUPLICATE_PRIMARY` violation at epoch 2, `violationsSeen: 1` |
| `run-in-progress.json` | `run-sync-restored.json` | milestones CRASHED and DETECTED only; `newPrimaryId`, `newEpoch` null; outcome IN_PROGRESS; every interval but detection null; `dataLoss` null |
| `run-interrupted.json` | `run-sync-restored.json` | milestones CRASHED to PROMOTED; outcome INTERRUPTED; time to first update, outage and recovery null; `dataLoss` null |
| `run-primary-returned.json` | `run-sync-restored.json` | milestones CRASHED (0) and OLD_PRIMARY_RECOVERED (1500.25); `detectedByNodeId`, `newPrimaryId`, `newEpoch` null; outcome PRIMARY_RETURNED; every interval null; `dataLoss` null |
| `status-stream-error.json` | `status-stream-running.json` | stream STOPPED with `stopReason: ERROR`, `gaveUp: 1`, `stoppedAt` set |
| `status-rejoin-stalled.json` | `status-after-demotion.json` | node 1 BACKUP at its old epoch 2, `believedPrimaryId` null, `rejoin: REJOINING` (no role query answered) |
| `status-failover-stream-stopped.json` | `status-during-failover.json` | stream STOPPED with `stopReason: REQUESTED`, `stoppedAt` set (the failover cannot finish, C3) |
| `run-recovering.json` | `run-sync-recovered.json` | milestones DEMOTED and RESYNCHRONISED removed; `recoveryMillis` null (the old primary is back, recovery still open) |
| `status-restored-old-primary-down.json` | `status-after-demotion.json` | node 1 CRASHED with role, epoch, `believedPrimaryId` and rejoin null (service restored by node 2, old primary not recovered yet) |
| `events-synthetic.json` | first event of `events.json` | two events: `ROLE_QUERY_FAILED` (node 1, attempt 5 of 5) and `NO_CANDIDATE` (node 0, excluded [1]), messages in the backend's wording |
