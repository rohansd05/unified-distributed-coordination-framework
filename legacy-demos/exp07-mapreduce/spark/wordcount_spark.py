"""
Experiment 7 — the same three MapReduce jobs expressed in Apache Spark.

This script exists so the experiment can also be shown using Spark, as the lab
statement allows. It is NOT the project's implementation: the real one is the
distributed Java version in src/main/java/com/udcf/mapreduce, which runs across the
framework's own nodes over TCP and needs no external dependency.

What this script is useful for is showing that the SAME map and reduce functions
express the same jobs in a production MapReduce engine. Compare reduceByKey below
with the reduce() methods in the Java jobs - they are the same operation.

------------------------------------------------------------------------------
Setup
------------------------------------------------------------------------------
    pip install pyspark

Run (from the experiment folder, so the data paths resolve):

    python spark/wordcount_spark.py

------------------------------------------------------------------------------
Important compatibility note
------------------------------------------------------------------------------
Spark 3.5 officially supports Java 8, 11 and 17. This project uses Java 21, which
is only officially supported from Spark 4.0 onward. On Java 21 you may see
reflection or "cannot access class sun.nio.ch.DirectBuffer" errors.

If that happens, either:
  * install Spark 4.0+  (pip install "pyspark>=4.0.0"), or
  * point JAVA_HOME at a Java 17 JDK just for this script, or
  * simply use the Java implementation, which is the project's real answer.
------------------------------------------------------------------------------
"""

import re
import sys

from pyspark.sql import SparkSession

TEXT_FILE = "data/sample-text.txt"
LOG_FILE = "data/framework-events.log"


def field(line, key):
    """Pull one key=value field out of a framework log line."""
    for part in line.split("|"):
        part = part.strip()
        if part.startswith(key + "="):
            return part[len(key) + 1:].strip()
    return None


def job_word_count(sc):
    """MAP: line -> (word, 1).   REDUCE: sum the ones."""
    pairs = (sc.textFile(TEXT_FILE)
               .flatMap(lambda line: re.split(r"[^a-z0-9]+", line.lower()))
               .filter(lambda w: w)
               .map(lambda w: (w, 1)))
    return pairs.reduceByKey(lambda a, b: a + b)


def job_event_categories(sc):
    """MAP: log line -> (category, 1).   REDUCE: sum the ones."""
    return (sc.textFile(LOG_FILE)
              .map(lambda line: field(line, "category"))
              .filter(lambda c: c is not None)
              .map(lambda c: (c, 1))
              .reduceByKey(lambda a, b: a + b))


def job_avg_latency(sc):
    """
    MAP: log line -> (node, (latency, 1)).
    REDUCE: add the pairs, then divide ONCE at the end.

    Exactly as in the Java version: a sum and a count are carried through the
    pipeline because they are associative, whereas an average is not.
    """
    def parse(line):
        node, latency = field(line, "node"), field(line, "latency")
        if node is None or latency is None:
            return None
        try:
            return ("node-" + node, (float(latency), 1))
        except ValueError:
            return None

    totals = (sc.textFile(LOG_FILE)
                .map(parse)
                .filter(lambda x: x is not None)
                .reduceByKey(lambda a, b: (a[0] + b[0], a[1] + b[1])))
    return totals.mapValues(lambda v: v[0] / v[1] if v[1] else 0.0)


def show(title, rdd, top=12, fmt="{:>10}"):
    print()
    print("=" * 78)
    print("  " + title)
    print("=" * 78)
    for key, value in rdd.takeOrdered(top, key=lambda kv: -kv[1]):
        if isinstance(value, float):
            print(f"    {key:<26} {value:>8.2f} ms average")
        else:
            print(f"    {key:<26} {value:>8}")


def main():
    spark = (SparkSession.builder
             .appName("UDCF-Experiment-7-MapReduce")
             .master("local[*]")          # local only - no cluster, no cloud
             .getOrCreate())
    spark.sparkContext.setLogLevel("ERROR")
    sc = spark.sparkContext

    print()
    print("UNIFIED DISTRIBUTED COORDINATION FRAMEWORK")
    print("Experiment 7 - the same jobs, expressed in Apache Spark")
    print(f"Spark version: {spark.version}   master: local[*]")

    show("JOB 1  -  WORD COUNT", job_word_count(sc))
    show("JOB 2  -  EVENTS BY CATEGORY", job_event_categories(sc))
    show("JOB 3  -  AVERAGE LATENCY PER NODE", job_avg_latency(sc), top=5)

    print()
    print("Note how reduceByKey here is the same operation as reduce() in the Java")
    print("implementation. Spark supplies the splitting, shuffling and fault tolerance,")
    print("which is precisely what our coordinator does in the Java version.")
    print()

    spark.stop()


if __name__ == "__main__":
    sys.exit(main())
