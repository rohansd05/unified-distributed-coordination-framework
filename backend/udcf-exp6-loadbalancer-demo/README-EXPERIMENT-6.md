# Experiment 6 — Load Balancing

Four load balancing algorithms compared on one cluster of deliberately unequal workers.

## Run it

```powershell
cd backend\udcf-exp6-loadbalancer-demo
javac -d target\classes src\main\java\com\udcf\sync\LamportClock.java src\main\java\com\udcf\loadbalancer\*.java src\main\java\com\udcf\demo\*.java
java -cp target\classes com.udcf.demo.LoadBalancerDemo
```

Windows Firewall will prompt because Java binds TCP ports 7201-7203. Click **Allow
access** for Private networks. The run takes roughly 30-60 seconds depending on your CPU.

## The cluster

| Node | Profile | Port | Pool threads | Work multiplier |
|---|---|---|---|---|
| 1 | FAST | 7201 | 4 | x1 |
| 2 | MEDIUM | 7202 | 2 | x2 |
| 3 | SLOW | 7203 | 1 | x4 |

Node 3 is roughly sixteen times weaker than Node 1. **This inequality is the point.** On
identical workers every algorithm produces the same distribution and the comparison would
demonstrate nothing.

## The four algorithms

| Algorithm | Information it uses |
|---|---|
| Round Robin | Nothing. Takes the next worker in turn. |
| Weighted Round Robin | A capacity weight configured in advance (4 : 2 : 1). |
| Least Connections | Live count of requests dispatched but not yet answered. |
| Least Response Time | Live outstanding count multiplied by measured recent latency. |

## What the run proves

Each algorithm gets the same 60 concurrent requests doing the same work, and the demo
reports requests per node, average and p95 latency, makespan, and the request-count
spread. A fifth phase takes the slow worker offline mid-run to show the balancer marking
it unhealthy and rerouting, finishing with zero failed requests.

## Design decisions worth defending

**The workers are unequal on purpose.** Round Robin and Least Connections behave
identically on a homogeneous cluster. The difference between them only appears when
capacity differs, which is also the situation every real deployment is in.

**The work is real computation.** Repeated SHA-256 rounds on each worker's Experiment 2
thread pool, not `Thread.sleep`. A slow worker is slow because it genuinely computes more,
so queue depth and latency are produced by the system rather than scripted.

**Requests are sent concurrently.** With one request at a time the in-flight count never
exceeds one, and Least Connections would be indistinguishable from Round Robin. Twelve
concurrent clients is what makes the live-state algorithms meaningful.

**The balancer measures, it does not ask.** In-flight counts and latency averages are
tracked by the gateway as replies arrive. Nothing is self-reported by a worker, so the
balancer reacts to a worker that has *become* slow, not only one configured as slow.

**Failure is a refused TCP connection.** A crashed worker closes its port, so the
connection is refused immediately. The balancer marks it unhealthy and retries elsewhere,
turning a worker failure into a slower request rather than a lost one.

**Even request counts are not balanced load.** Round Robin achieves the smallest
request-count spread and the worst makespan at the same time. That contradiction is the
single most important result in the experiment.
