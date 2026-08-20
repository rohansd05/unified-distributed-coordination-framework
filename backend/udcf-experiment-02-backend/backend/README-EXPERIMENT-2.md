# Experiment 2 — Multithreading in the Distributed System

This module is the request-processing core of the Unified Distributed Coordination
Framework. It is **not** a standalone threading demo: every later experiment
(load balancing, replication, MapReduce, matrix multiplication) submits its work through
this same executor, which is why the node-level thread metrics stay meaningful
throughout the project.

## Run it

```powershell
cd backend
mvn clean install
mvn test
mvn spring-boot:run "-Dspring-boot.run.profiles=node1"
```

Three nodes, three terminals:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=node1"   # 8081
mvn spring-boot:run "-Dspring-boot.run.profiles=node2"   # 8082
mvn spring-boot:run "-Dspring-boot.run.profiles=node3"   # 8083
```

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/multithreading/requests` | Generate a batch of concurrent requests (202) |
| POST | `/api/multithreading/requests/sync` | Run one request and wait for the result |
| GET | `/api/multithreading/requests?limit=50` | Recent requests, newest first |
| GET | `/api/multithreading/requests/{id}` | One request by id |
| GET | `/api/multithreading/stats` | Live executor snapshot |
| DELETE | `/api/multithreading/requests` | Clear history (204) |

Example:

```powershell
curl.exe -X POST http://localhost:8081/api/multithreading/requests `
  -H "Content-Type: application/json" `
  -d '{\"count\":100,\"type\":\"MIXED\",\"payloadSize\":50}'

curl.exe http://localhost:8081/api/multithreading/stats
curl.exe http://localhost:8081/actuator/prometheus | Select-String "distributed_"
```

## Design decisions worth defending in the report

**The queue is bounded.** An unbounded queue would mean `maxPoolSize` is never reached
and rejection never occurs, so backpressure could not be demonstrated at all.

**The rejection policy is AbortPolicy, not CallerRunsPolicy.** CallerRuns would silently
execute work on the Tomcat HTTP thread, corrupting both the throughput figures and the
thread names shown in the UI. Rejection is surfaced as a `REJECTED` request status
rather than an exception, because refusing work under load is correct behaviour, not a
fault.

**A raw `ThreadPoolExecutor` is exposed, not Spring's `ThreadPoolTaskExecutor`.** The
dashboard reads `getActiveCount()`, `getQueue().size()` and `getCompletedTaskCount()`
directly from the live executor at request time. Nothing is cached, estimated, or
hard-coded — this satisfies rule 10 of the project context.

**Workloads do real computation.** `CPU_HASH` performs repeated SHA-256 rounds rather
than sleeping. If every request slept for a fixed interval, "throughput" and "response
time" would measure only that constant, and the experiment would prove nothing about
concurrency.

**Throughput uses a sliding window.** A cumulative average flattens within a minute and
hides load spikes, which is exactly what the demonstration needs to show.

**Node identity comes from configuration only.** `udcf.node.id` is injected; no Java
file knows which node it is. This is what makes the single-machine and LAN deployment
modes the same code.

## Test coverage

| Production file | Test file |
|---|---|
| `DistributedRequest.java` | `DistributedRequestTest.java` |
| `NamedThreadFactory.java` | `NamedThreadFactoryTest.java` |
| `WorkloadExecutor.java` | `WorkloadExecutorTest.java` |
| `RequestRegistry.java` | `RequestRegistryTest.java` |
| `ThroughputTracker.java` | `ThroughputTrackerTest.java` |
| `ThreadPoolConfig.java` | `ThreadPoolConfigTest.java` |
| `RequestProcessingService.java` | `RequestProcessingServiceTest.java` |
| `ThreadPoolStatsService.java` | `ThreadPoolStatsServiceTest.java` |
| `ThreadPoolMetrics.java` | `ThreadPoolMetricsTest.java` |
| `MultithreadingController.java` | `MultithreadingControllerTest.java` |
| whole context | `UdcfBackendApplicationTests.java` |

**Deliberately untested, with reasons** (per Section 24 of the project context):

- `ThreadPoolProperties`, `CorsConfig`, `MetricsConfig` — declarative configuration with
  no branching logic. Their effects are asserted in `ThreadPoolConfigTest`,
  `UdcfBackendApplicationTests` and `ThreadPoolMetricsTest`.
- `RequestStatus`, `WorkloadType` — enums with no behaviour.
- `RequestResult`, `ThreadPoolStats`, `BatchSubmissionResponse`, `GenerateRequestsCommand`
  — records that carry data only. Their mapping and validation constraints are asserted
  through `DistributedRequestTest` and `MultithreadingControllerTest`.
- `LoggingRequestEventPublisher` — a temporary logging stub, replaced by the STOMP
  implementation in Phase 1A.
- `UdcfBackendApplication` — generated bootstrap class.
