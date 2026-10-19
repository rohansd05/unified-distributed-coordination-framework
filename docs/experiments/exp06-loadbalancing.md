# Experiment 6: Load Balancing Algorithm

Module id `loadbalancing` · page `/experiments/6-loadbalancing` · package `com.udcf.modules.loadbalancing`

## Aim

Implementation of a load balancing algorithm.

## Theory

A *load balancer* is a gateway that decides which server handles each request. When servers differ in
capacity, the choice matters: an even split of *requests* is not an even split of *load*.

- **Round robin** takes servers in turn. It knows nothing about them.
- **Weighted round robin** gives each server a share proportional to a configured weight. The *smooth*
  variant (used by nginx) interleaves picks instead of sending a burst to the heaviest server.
- **Least connections** sends the request to the server with the fewest requests in flight.
- **Least response time** picks the lowest estimated cost, here the moving average of recent latency
  times (in-flight + 1).

The measure that matters is the *makespan*: the time until the whole batch is finished. A batch is done
only when its slowest server is done. A balancer must also cope with a failing server, for example with
a *circuit breaker* that takes it out of rotation and reroutes its requests.

## How UDCF implements it

- **Algorithm (pure).** `Strategy`: `ROUND_ROBIN`, `WEIGHTED_ROUND_ROBIN`, `LEAST_CONNECTIONS`,
  `LEAST_RESPONSE_TIME`. `LoadBalancer.choose` picks among healthy, untried workers in node-id order:
  - round robin: a cursor modulo the number of candidates;
  - weighted round robin: smooth weighted round robin with weight = the node's thread count (4, 2 or 1);
  - least connections: the fewest in flight;
  - least response time: the lowest `EWMA latency × (inFlight + 1)`, EWMA α = 0.3.
  Ties go to the lowest id. `WorkerInfo` is the balancer's view of a worker; `PhaseReport` gives the
  makespan, average, p95 and maximum latency, spread, reroutes and failures; `StrategyComparison` finds
  the fastest and slowest strategy and whether round robin finished last.
- **Reroute.** An I/O error marks the worker unhealthy for the run (circuit breaker). A worker that
  declines (for example a full queue) is retried elsewhere but stays healthy. Each request tries a worker
  at most once.
- **Transport.** Experiment 6 has no port of its own. `TcpWorkerTransport` sends each request through
  Experiment 2's `RequestsClient` to the target node's `ports().requests()` (7201 to 7205 locally, 7201
  to 7203 public). `BatchRunner` runs the concurrent clients on virtual threads; `CrashTrigger` crashes a
  chosen node after a given number of served requests.
- **Capacities.** Nodes 1 to 5 are FAST, MEDIUM, SLOW, MEDIUM, FAST (public: FAST, MEDIUM, SLOW):
  FAST = 4 threads, ×1 work; MEDIUM = 2 threads, ×2; SLOW = 1 thread, ×4.
- **Module and API.** `LoadBalancingModule` (lab 6) and `LoadBalancingController` under
  `/api/modules/loadbalancing`: `POST /runs` (`{strategy, requestCount, workUnits, concurrency, crash?}`)
  and `POST /comparisons` (one warm-up batch, then all four strategies). Defaults: 60 requests, 400 work
  units, 12 concurrent clients.
- **Roles.** None.
- **Events** (module `loadbalancing`): `RUN_STARTED`, `RUN_FINISHED`, `RUN_FAILED`, `CRASH_INJECTED`,
  `CRASH_SKIPPED`, `COMPARISON_STARTED`, `COMPARISON_FINISHED`, `DISPATCH_FAILED`, `DISPATCH_DECLINED`.
  Each served request also produces Experiment 2's `REQUEST_COMPLETED` on the worker.

## Links to other experiments

- **L3, Experiment 6 dispatches into Experiment 2 executors.** Every request runs on the target node's
  Experiment 2 `ThreadPoolExecutor` (threads named `udcf-worker-n<k>-<i>`). A slow node is slow because
  its executor has fewer threads and more work per request.
- **L4, Lamport time on every message.** The balancer sends with the cluster clock's tick; the worker
  calls `node.clock().update(...)` and replies with its own tick, which the balancer merges.
- **Scenario "A slow node under load"** runs the four-strategy comparison and checks that round robin
  finishes last.

## How to run it in the UI

1. In the sidebar, click **Load Balancing** (`/experiments/6-loadbalancing`).
2. Under **Strategy** keep **Round robin**, keep the default **Requests**, **Work units per request** and
   **Concurrent clients**, and click **Run**.
3. Click **Compare all four**.
4. Tick **Crash a worker during this run**, choose a **Node to crash**, keep **After this many requests
   are served**, and click **Run**.
5. Click **Recover node N** afterwards.

## What to observe

- **"Where the requests went":** with round robin every node gets about the same number of requests,
  including the slow node 3.
- **Compare all four:** the comparison table lists finish time, average, p95, slowest request, spread and
  requests per node for each strategy, and tags the fastest and slowest. The key finding: round robin has
  the most even request counts but usually the worst finish time, because the batch waits for the slow
  node. The page states the finding only when the measured flags support it.
- **Crash during a run:** "Reroutes" goes up, "Failed requests" stays 0, and the crashed node shows
  "Taken out by the breaker".

## What is simulated

Nothing. The page states "Nothing on this page is simulated: every figure is measured from real
requests." The capacity profiles are configured, not measured: all nodes share one computer, so a slow
node has fewer threads and more work per request, not slower hardware.

## Tests that prove it

- Backend (`backend/src/test/java/com/udcf/modules/loadbalancing`): `LoadBalancerTest`,
  `WorkerInfoTest`, `PhaseReportTest`, `StrategyComparisonTest`, `BatchRunnerTest`, `CrashTriggerTest`,
  `DispatchResultTest`, `TcpWorkerTransportTest`, `LoadBalancingGatewayTest`, `LoadBalancingModuleTest`,
  `LoadBalancingControllerTest`, `LoadBalancingMetricsTest`.
- Integration: `L3LoadBalancingIntoExecutorsIntegrationTest`, `L4LamportOnMessagesIntegrationTest`.
- Scenario: `scenario/ScenariosEndToEndTest` (`slowNodeUnderLoad`).
- Frontend (`frontend/src/modules/loadbalancing`): `LoadBalancingPage.test.jsx`, `BalancerFlow.test.jsx`,
  `ComparisonTable.test.jsx`, `flowModel.test.js`, `useLoadBalancing.test.js`, `contract.test.js`, and
  others.

## Viva questions

1. **Why can round robin finish last when its split is the most even?**
   Equal request counts are not equal load. The slow node gets as many requests as the fast ones but
   finishes each one much later, and the batch is done only when the slowest node is done.
2. **What is smooth weighted round robin?**
   Each pick adds every server's weight to its current score, chooses the highest, and subtracts the total
   weight from the winner. Shares follow the weights without sending bursts to one server.
3. **What does least connections assume?**
   That the number of requests in flight reflects load. It adapts to slow servers automatically, because
   their requests stay in flight longer.
4. **How is least response time computed here?**
   The exponentially weighted moving average of latency (α = 0.3) times (in-flight + 1); the lowest cost
   wins.
5. **What is a circuit breaker, and how does it give zero failed requests?**
   After an I/O error the worker is taken out of rotation for the run, and the request is retried on
   another worker. Each request tries each worker at most once.
6. **What is the makespan, and why measure it?**
   The time from the first request to the last reply. It is what a user waiting for the whole batch
   experiences, unlike the average latency.
7. **Where does the work actually run?**
   On the target node's Experiment 2 thread pool, over TCP (link L3). The balancer itself only chooses
   and forwards.
8. **Is the slow node really slow?**
   Yes, but by configuration: it has 1 thread and four times the work per request. All nodes share one
   computer, so the page labels the capacities as configured, not measured.
