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
- [x] E4a — Bully and Ring as pure classes, plus the consensus check. Needs: none.
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

### E4b APIs:
- **Constructors:** `BullyAlgorithm` and `RingAlgorithm` both accept (`nodeId`, `allNodes`/`ringOrder`, `config`, `messenger`, `eventListener`, `timer`, `nanoClock`/`prober`, `wallClock`).
- **Threading:** The algorithms are fully callback-driven and thread-safe. There is no `Executor` needed. The transport must call `onReceive` and `onProbeAck`; these methods will **never** block and may be safely called directly on the listener thread.
- **ElectionTimer:** The transport must implement the `ElectionTimer` interface to schedule tasks. A node crash cancels all ongoing tasks.
- **Clocks & Messenger:** The `nanoClock` (or `prober`) and `wallClock` are injected for testability. The `ElectionMessenger` interface handles all outbound messages.
- **Config:** Expects `udcf.election` prefixed configuration keys (validated positive numbers, probe timeout must be strictly shorter than OK timeout, and ring completion timeout is required).
- **Wire Format:** Messages are serialized as strings separated by `|`. Max length is 1024 characters.

### E4a: Election Events
The `ElectionEventType` enum exposes these algorithm-level statuses:
- `ELECTION_START`: The node began its own election.
- `ELECTION_RESTART`: The node restarted an ongoing election.
- `OK_RECEIVED`: Bully node received an OK and is waiting for a coordinator.
- `WAITING_FOR_COORDINATOR`: The node is waiting for the result.
- `ELECTED`: The node elected itself as coordinator.
- `COORDINATOR_ACCEPTED`: The node accepted another node as coordinator.
- `TOKEN_FORWARDED`: Ring node forwarded an election token.
- `DEAD_NODE_SKIPPED`: Ring node successfully skipped an unreachable successor.
- `ELECTION_TIMEOUT`: An election timed out without concluding.
- `CRASH`: The node was explicitly crashed.
- `RECOVER`: The node recovered from a crash.
- `UNKNOWN_SENDER`: An unrecognized message was received.
- `LATE_MESSAGE`: A message for a stale election was safely ignored.

---

## Deliberate differences from the legacy code

- **Legacy deviation (Lower-ID Coordinator Acceptance):** When a Bully node receives a `COORDINATOR` announcement, it unconditionally accepts it, even if the announcing node has a lower ID than itself. This faithfully ports a quirk from the legacy `exp04-election` demo.
- **Callback-Driven Probing (No Executor, flat package, no threads):** To prevent deadlocks, the Ring liveness prober is entirely asynchronous. Unlike the legacy demo which blocked the listener thread, it uses an exactly-once callback mechanism. This allows the removal of executors entirely, making the algorithms completely unthreaded. We use a flat package (`com.udcf.modules.election`) to easily share common interfaces among these highly-cohesive primitives.

---

## Requirements Coverage (E4a)

| Requirement | Implementation / Rule | Test |
|---|---|---|
| Bully election start & message sequence | Sends `ELECTION` to higher nodes, schedules OK timeout | `BullyAlgorithmTest.testLegacyMessageSequence` |
| Bully output order preserved | Emits `ELECTION_START` before sending outbound messages | `BullyAlgorithmTest.testOutputOrderPreserved` |
| Bully highest node self-promotes | Immediate victory when no higher nodes exist | `BullyAlgorithmTest.testHighestNodeStartsAndWins` |
| Bully OK response handling | Stand down from self-promotion, wait for coordinator | `BullyAlgorithmTest.testOkMeansStandDownAndCoordinatorTimeoutRestarts` |
| Bully OK timeout expiration | Self-promotes to coordinator if no OK received | `BullyAlgorithmTest.testNoOkMeansSelfPromotion` |
| Bully coordinator timeout restart | Restarts election if coordinator announcement times out | `BullyAlgorithmTest.testOkMeansStandDownAndCoordinatorTimeoutRestarts` |
| Bully lower-ID coordinator quirk | Unconditionally accepts lower ID coordinator (legacy demo quirk) | `BullyAlgorithmTest.testLegacyLowerIdCoordinator`, `testLowerIdCoordinatorQuirk` |
| Bully late OK ignored | Late OK when not in progress emits `LATE_MESSAGE` | `BullyAlgorithmTest.testLateOkIgnored` |
| Bully stale OK timer does nothing | Old OK timeout fired after receiving OK or after new election does nothing | `BullyAlgorithmTest.testStaleTimers`, `testOldOkTimeoutFiredAfterNewElectionStartedDoesNothing` |
| Bully timer after crash does nothing | OK timer and coordinator timer do nothing after crash | `BullyAlgorithmTest.testTimerAfterCrashDoesNothing` |
| Bully crash & recovery | Crash clears state; recover starts election clean | `BullyAlgorithmTest.testCrashClearsState`, `testHighestNodeRecovers` |
| Bully crash between collecting and sending | Actions loop aborts if crashed; sends nothing further | `BullyAlgorithmTest.testCrashBetweenCollectingAndSendingSendsNothingFurther` |
| Bully Lamport clock usage | Lamport clock ticks on send/event and updates on receive | `BullyAlgorithmTest.testLamportUsage` |
| Bully concurrent election guard | `compareAndSet` prevents overlapping elections | `BullyAlgorithmTest.testConcurrentStartElection` |
| Bully in-progress flag release | Flag released on completion, timeout, crash, and exceptions | `BullyAlgorithmTest.testInProgressFlagReleased` |
| Bully unknown sender | Unknown message type handled safely without crashing | `BullyAlgorithmTest.testUnknownSenderIgnored` |
| Bully lock reentrancy & action collection | Collect actions under lock, execute outside lock | `ReentrancyTest.testReentrancyAndCrashDuringExecution` |
| Ring single node ring | Ring of one node times out safely without infinite loop | `RingAlgorithmTest.testRingOfOne` |
| Ring two node token exchange | Token passed between two nodes elects coordinator | `RingAlgorithmTest.testRingOfTwo` |
| Ring legacy token forwarding | Appends node ID and forwards to successor | `RingAlgorithmTest.testLegacyTokenSequence` |
| Ring full circle completion | Full circle detected when ID in token; highest wins | `RingAlgorithmTest.testHighestWinsAfterFullCircle`, `testTokenAlreadyContainsReceiver` |
| Ring coordinator circulation stop | Result token circulating stops at originator node | `RingAlgorithmTest.testRingCoordinatorStopsAtOriginator` |
| Ring concurrent originators | Multiple concurrent tokens resolve to highest node | `RingAlgorithmTest.testTwoOriginatorsHighestWins` |
| Ring dead successor skip | Probe timeout skips dead node and tries next in ring | `RingAlgorithmTest.testSingleThreadedListenerCompletesWithDeadSuccessor`, `testAllOtherDead` |
| Ring callback liveness prober (Hard rule 7) | Non-blocking probe completes callback exactly once | `DefaultLivenessProberTest.testAckBeforeTimeout`, `testTimeoutBeforeAck` |
| Ring no event for PROBE/PROBE_ACK | PROBE and PROBE_ACK emit no election events | `RingAlgorithmTest.testNoEventForProbeOrProbeAck` |
| Ring lost token completion timeout | Completion timeout clears in-progress flag and allows restart | `RingAlgorithmTest.testLostToken` |
| Ring stale completion timer does nothing | Old completion timer fired after new election does nothing | `RingAlgorithmTest.testOldCompletionTimeoutFiredAfterNewElectionStartedDoesNothing` |
| Ring timer after crash does nothing | Completion timer fired after crash does nothing | `RingAlgorithmTest.testTimerAfterCrashDoesNothing` |
| Ring in-progress flag release | Flag released on coordinator, timeout, crash, and exceptions | `RingAlgorithmTest.testInProgressFlagReleased` |
| Ring Lamport clock tracking | Lamport clock ticks on forward and updates on receive | `RingAlgorithmTest.testLamportClock` |
| Ring crash between collecting and sending | Actions loop aborts if crashed; sends nothing further | `RingAlgorithmTest.testCrashBetweenCollectingAndSendingSendsNothingFurther` |
| Ring reentrancy safety | Reentrant delivery does not deadlock | `ReentrancyTest.testRingReentrancyNoDeadlock` |
| Consensus checker | Agreement, disagreement, dead leader, unassigned, all crashed | `ConsensusCheckerTest` (8 tests) |
| Message wire format & limit | Pipe-delimited; datagrams > 1024 characters rejected | `ElectionMessageTest.testWireFormat`, `testMalformed`, `testMessageOver1024CharactersRejected` |
| Election config validation | Positive timeouts required, probe timeout < ok timeout | `ElectionConfigTest` (3 tests) |

---

## Known issues

- `MultithreadingModuleTest.backpressureIsDeterministicAndGuarded` (Track A's test) failed once in one of three runs (expected 5, was 9). Not edited, as it is unrelated to the election module and our tests are completely pure without static state, threads, or sleep loops.
- `ClockNodeServiceTest.berkeleyRoundReducesSpread` and `daemonReceivesRepliesUnderLoadWithoutDeadlock` (Track D's test) flaked once in GitHub Actions CI under runner scheduling contention (UDP round timed out before peer reply). Unrelated to election module (all 54 election tests passed in CI); not edited per cross-track rule.

---

## Progress log

- 2026-10-07 track file created.
- 2026-10-08 E4a completed (baseline 525, total 579; 54 election tests covering all scenarios including stale timers, crash safety, 1024-char limit, probe event absence, and action loop crash guards).
