# Experiment 7 — MapReduce

A distributed MapReduce framework running across the project's own nodes, plus an
optional Apache Spark version of the same jobs.

## Run it (the project's implementation)

```powershell
cd backend\udcf-exp7-mapreduce-demo
javac -d target\classes src\main\java\com\udcf\sync\LamportClock.java src\main\java\com\udcf\mapreduce\*.java src\main\java\com\udcf\demo\*.java
java -cp target\classes com.udcf.demo.MapReduceDemo
```

Run it **from the experiment folder**, because it reads `data/sample-text.txt` and
`data/framework-events.log` using relative paths. Windows Firewall will prompt because
Java binds TCP ports 7301-7303 — click **Allow access** for Private networks.

## Optional — the same jobs in Apache Spark

```powershell
pip install pyspark
python spark\wordcount_spark.py
```

This is included because the lab statement mentions Hadoop or Spark. It is **not** the
project's implementation — the Java version is, because it runs on the framework's own
nodes and adds no external dependency, as the project's architecture requires.

**Compatibility warning:** Spark 3.5 officially supports Java 8, 11 and 17. This project
uses Java 21, which is only supported from Spark 4.0. If you hit reflection errors, install
`pyspark>=4.0.0` or point `JAVA_HOME` at a Java 17 JDK just for that script.

## What runs

| Phase | Job | Input |
|---|---|---|
| 1 | Word count | `data/sample-text.txt` |
| 2 | Events by category | `data/framework-events.log` |
| 3 | Average latency per node | `data/framework-events.log` |
| 4 | Worker failure mid-job | repeats phase 2 with a node down |

Phases 2 and 3 are what make this *our* project's MapReduce rather than a generic demo:
the framework analyses its own event log from the earlier experiments.

## Design decisions worth defending

**The user writes two functions; the framework does the rest.** A job supplies only
`map`, `combine` and `reduce`. Splitting, shipping tasks to nodes, grouping by key,
partitioning to reducers and retrying failures are all handled by the coordinator. That
division is what makes MapReduce a framework rather than an algorithm.

**Keys are partitioned to reducers by hash.** `hash(key) % reducerCount` guarantees every
value for a given key reaches the same reducer. Without that guarantee reduce would see a
partial list and produce a wrong answer.

**The combiner runs on the mapper node, before the network.** It is the single biggest
optimisation here — the demo prints how much mapper output it removes. It is valid only
because the operations are associative, which is why it is a separate method from
`reduce` rather than the same one applied automatically.

**Average latency carries a sum and a count, not an average.** You cannot average
averages. The job passes `sum;count` through the pipeline, which *is* associative, and
divides only in `formatResult`. This is one of the most common MapReduce mistakes and is
demonstrated deliberately.

**Failed tasks are simply re-run.** Map and reduce are pure functions of their input, so
executing a task twice produces the same answer. That is why the programming model forbids
side effects, and it is what lets phase 4 finish correctly with a third of the cluster gone.

**Task payloads are Base64 encoded.** The wire protocol is line-based and the data contains
newlines and pipe characters, so encoding removes any chance of a record being split in the
wrong place.
