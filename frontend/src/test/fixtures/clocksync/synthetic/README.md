# Synthetic clock synchronization fixtures (9A.4)

**These are not captures.** Each file is a copy of a real fixture in the folder above with the fields below
changed, for a state the real backend cannot be made to produce on demand. It was written on 2026-10-17 by one
Python 3 standard-library script run with `python -I` outside the repository, which loads the source file, inserts
exactly these fields and writes compact JSON (UTF-8, no BOM). Every text put in is copied from the backend code
named beside it. Values marked "made up" were never measured.

| File | Source | Changed fields | Why synthetic |
|---|---|---|---|
| `overview-aborted-round.json` | `overview-after-sync.json` | `abortedRound` inserted after `latestRound`: `roundId` = `latestRound.roundId` + 1, `daemonNodeId` = `latestRound.daemonNodeId`, `reason` = the poll-stage sentence of `ClockNodeService.abortIfStopped`, `abortedAt` = the source's newest `simulatedTime` + 1 s (made up). `latestRound` is unchanged, as after a real abort. | Not capturable on demand: needs a daemon crash inside the poll window. |

`contract.test.js` checks that the file has exactly the keys of its source, so it cannot drift from the real shape.
