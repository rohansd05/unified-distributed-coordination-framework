# Experiment 7: A Basic MapReduce Job

Module id `mapreduce` · page `/experiments/7-mapreduce` · package `com.udcf.modules.mapreduce`

## Aim

Implementation of a basic MapReduce job (the lab names Hadoop or Apache Spark; UDCF builds the same
model itself over TCP, see "What is simulated").

## Theory

MapReduce processes a large input in parallel on many workers with two user functions.

1. **Split:** the input is cut into pieces, one per map task.
2. **Map:** each worker turns its piece into key-value pairs, for example `(word, 1)`.
3. **Combine** (optional): each worker pre-aggregates its own pairs, for example `(word, 7)` instead of
   seven `(word, 1)`, so fewer pairs cross the network.
4. **Shuffle and partition:** pairs are grouped by key, and each key goes to one reducer, chosen by
   `hash(key) mod R`.
5. **Reduce:** each reducer combines all values of its keys into the result.

A *coordinator* (the master) assigns tasks and re-runs a task on another worker if a worker fails, so a
crash does not change the result. A combiner is only correct when the operation is associative: a sum
can be combined in parts, but an average cannot. To average, carry `sum;count` pairs and divide only at
the end.

## How UDCF implements it

- **Jobs (pure).** `WordCountJob` (`word-count`), `EventCategoryJob` (`event-category-count`, counts the
  `category=` field of log lines) and `LatencyPerNodeJob` (`avg-latency-per-node`, carries `sum;count`
  and divides only in the final step). `JobRegistry.standard()` registers the three.
- **Pipeline (pure).** `MapReducePipeline`: split (`InputSplitter`, one split per worker) → map with the
  local combiner (`MapTask`) → shuffle (the coordinator groups by key) → partition (`HashPartitioner`,
  `floorMod(key.hashCode(), R)`, R = number of workers) → reduce (`ReduceTask`) → collect. A failed
  attempt (timeout of 15000 ms, I/O error or error reply) is retried on the next worker.
  `JobReport` records every stage's counts and timings.
- **Transport.** `MapReduceNodeService` (`NodeService` named `mapreduce`) is each node's worker on TCP
  `127.0.0.1:ports().mapreduce()`: 7301 to 7305 locally, 7301 to 7303 public. It runs tasks on its own
  bounded thread pool. `TcpTaskTransport` is the coordinator side; `MapReduceProtocol` sends
  `<taskType>|<senderId>|<lamportTime>|<taskId>|<jobName>|<base64Payload>`.
- **Inputs** (`RunInputLoader`): "Bundled sample text" (`backend/src/main/resources/mapreduce/sample-text.txt`),
  "Your own .txt file" (UTF-8, at most 1 MiB locally, kept in memory for the run only), and "Live cluster
  event log" (link L5).
- **Crash injection.** `WorkerCrashTrigger` crashes the chosen worker right after the first task is sent
  to it; the task fails and is retried on another worker. The worker must not be the coordinator.
- **Module and API.** `MapReduceModule` (lab 7) and `MapReduceController` under
  `/api/modules/mapreduce`: `POST /runs` (`{jobId, inputType, upload, crashWorkerId}`), `GET /runs`,
  `GET /runs/latest`, `GET /runs/{runId}`.
- **Role.** The coordinator is the cluster leader (L1); every live node is a worker. A running job keeps
  its coordinator; if the coordinator crashes mid-run, the run fails and the next run uses the new leader.
- **Events** (module `mapreduce`): `JOB_STARTED`, `JOB_COMPLETED`, `JOB_FAILED`, `WORKER_CRASH_TRIGGERED`,
  `TASK_SENT`, `TASK_ATTEMPT_FAILED`, `TASK_RECEIVED`, `TASK_COMPLETED`, `TASK_FAILED`, `REQUEST_REFUSED`,
  `COORDINATOR_CHANGED`.

## Links to other experiments

- **L1, roles follow the leader.** A run asks `RoleProvider.ensureLeader()` for its coordinator; with no
  leader the answer is 409 "No leader". After a leader change `COORDINATOR_CHANGED` is published once.
- **L4, Lamport time on every message.** The coordinator ticks its clock into every task; the worker calls
  `node.clock().update(...)` and replies with a tick, which the coordinator merges.
- **L5, the event log feeds MapReduce.** `core/events/EventLogExporter` writes every module's events as
  text lines, one per event in causal order:
  `<UTC time> | node=<id> | category=<type>[ | latency=<ms>] | seq=<n> | lamport=<t> | module=<m>[ | peer=<id>][ | msg=<text>]`.
  The "Live cluster event log" input reads a snapshot of it; the same text is served by
  `GET /api/events/export`. Latency is written only for events that measured one (for example
  Experiment 1's remote calls), never for simulated delays.
- **Scenario "Analyse the incident with MapReduce"** crashes the leader and then counts the cluster's own
  event log by type and by node, coordinated by the new leader.

## How to run it in the UI

1. In the sidebar, click **MapReduce** (`/experiments/7-mapreduce`).
2. Under **Job** choose **Word count**, under **Input** choose **Bundled sample text**, and click **Run**.
3. Choose **Events per category** with **Live cluster event log** and click **Run**.
4. Choose **Word count** and **Bundled sample text** again. Tick **Crash a worker right after the first
   task is sent to it**, choose a **Worker to crash**, and click **Run**.
5. Click **Recover node N** for the crashed worker.
6. Under **Event log file**, click **Download event log** to see the exact text MapReduce reads.

## What to observe

- **"The run through the pipeline":** the coordinator ("the elected leader when this run started"), then
  Input, Split, Map, Combine, Shuffle, Partition, Reduce and Result with their counts and measured
  timings, and chips showing how many tasks each worker ran.
- **"Combiner saving":** the percentage of pairs the combiners kept off the shuffle.
- **Crash run:** a retry arrow ("the attempt on node A failed, node B re-ran it"), "Retried tasks" above
  0, and "Every task attempt" listing the failed attempt. The result table matches the run of step 2:
  the same input gives the same counts, crash or not.
- **Average latency per node** (try it on the live event log): the job carries `sum;count` pairs and
  divides only at the end.
- **Results:** a table and bar chart of the result keys. For the event log input they are the cluster's
  own event types.

## What is simulated

Nothing in the run: "Every map and reduce task travels over a real TCP connection to the worker's own
port; nothing on this page is simulated." The page has no Simulated badge. Note that UDCF does not run
Hadoop or Spark; it implements the MapReduce model in Java. The original standalone demo in
`legacy-demos/exp07-mapreduce/` includes an optional PySpark script.

## Tests that prove it

- Backend (`backend/src/test/java/com/udcf/modules/mapreduce`): `WordCountJobTest`, `EventCategoryJobTest`,
  `LatencyPerNodeJobTest`, `InputSplitterTest`, `MapTaskTest`, `HashPartitionerTest`, `ReduceTaskTest`,
  `MapReducePipelineTest`, `MapReducePipelineOverTcpTest`, `MapReduceCrashRunTest`,
  `MapReduceNodeServiceTest`, `MapReduceProtocolTest`, `WorkerCrashTriggerTest`, `RunInputLoaderTest`,
  `MapReduceModuleTest`, `MapReduceControllerTest`, `MapReduceLeaderIntegrationTest` (L1).
- Core: `core/events/EventLogExporterTest`.
- Integration: `L5EventExportToMapReduceIntegrationTest` (the per-type counts equal the bus's own counts),
  `L4LamportOnMessagesIntegrationTest` (TCP MapReduce case).
- Scenario: `scenario/MapReduceIncidentScenarioTest`, `scenario/scenarios/IncidentScenariosEndToEndTest`.
- Frontend (`frontend/src/modules/mapreduce`): `MapReducePage.test.jsx`, `PipelineFlow.test.jsx`,
  `RunControls.test.jsx`, `ResultsPanel.test.jsx`, `TaskTable.test.jsx`, `EventLogDownload.test.jsx`,
  `useMapReduce.test.js`, `contract.test.js`, and others.

## Viva questions

1. **Name the phases of MapReduce.**
   Split, map, (combine), shuffle and partition, reduce. UDCF shows each one as a stage with its count and
   time.
2. **What does the combiner save, and when is it allowed?**
   It pre-aggregates on the map worker, so fewer pairs cross the shuffle. It is only correct for
   associative and commutative operations, such as a sum.
3. **Why does the average-latency job carry `sum;count`?**
   Averages are not associative: the average of partial averages is wrong when the parts differ in size.
   Sums and counts add up correctly, and the division happens once at the end.
4. **How does a key find its reducer?**
   `hash(key) mod R`, so all values of one key reach the same reducer.
5. **What happens when a worker crashes mid-job?**
   The coordinator's attempt fails (connection refused or timeout), and it re-runs the task on the next
   worker. Tasks are deterministic, so the result is unchanged.
6. **What happens when the coordinator crashes?**
   The running job fails and says so. The next job asks Experiment 4 for the new leader, which becomes the
   coordinator.
7. **What is the "Live cluster event log" input?**
   Every module's events exported as text lines (link L5), so the cluster can analyse its own behaviour,
   for example count events per type after an incident.
8. **Is this Hadoop or Spark?**
   No. It is the same programming model implemented in Java over real TCP between the nodes; the legacy
   demo has an optional PySpark script for comparison.
