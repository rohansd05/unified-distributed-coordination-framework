package com.udcf.core.metrics;

/**
 * Every {@code distributed_*} meter name (docs/HANDOFF.md 6.9) and the {@code node_id} tag.
 *
 * <p>Counter names end in {@code _total}, as Experiment 2's {@code distributed_requests_total}
 * does. The Prometheus registry strips the suffix and adds it back, so the scrape shows
 * {@code _total} exactly once.</p>
 *
 * <p>Every meter carries {@link #NODE_ID} (decision R5); cluster-level meters use
 * {@link #CLUSTER_NODE_ID}.</p>
 *
 * <p>No dedicated test: constants only.</p>
 */
public final class MetricNames {

    public static final String REQUESTS_TOTAL = "distributed_requests_total";
    public static final String ACTIVE_THREADS = "distributed_active_threads";
    public static final String NODE_STATUS = "distributed_node_status";
    public static final String LEADER_ELECTIONS_TOTAL = "distributed_leader_elections_total";
    public static final String ELECTION_DURATION = "distributed_election_duration";
    public static final String CLOCK_VALUE = "distributed_clock_value";
    public static final String REPLICATION_LATENCY = "distributed_replication_latency";
    public static final String REPLICATION_FAILURES_TOTAL = "distributed_replication_failures_total";
    /** Experiment 5: acknowledgements per backup node, by result (APPLIED, DUPLICATE, STALE, STALE_EPOCH). */
    public static final String REPLICATION_ACKS_TOTAL = "distributed_replication_acks_total";
    /** Experiment 5: client writes accepted per primary node, by consistency model. */
    public static final String REPLICATION_WRITES_TOTAL = "distributed_replication_writes_total";
    /** Experiment 5: items in each node's replicated store (NaN while its service is not running). */
    public static final String REPLICATION_STORE_ITEMS = "distributed_replication_store_items";
    /** Experiment 5: each node's store epoch (NaN while its service is not running). */
    public static final String REPLICATION_EPOCH = "distributed_replication_epoch";
    public static final String FAILURES_TOTAL = "distributed_failures_total";
    public static final String RECOVERY_DURATION = "distributed_recovery_duration";
    public static final String MAP_TASKS_TOTAL = "distributed_map_tasks_total";
    public static final String REDUCE_TASKS_TOTAL = "distributed_reduce_tasks_total";
    public static final String MPI_MESSAGES_TOTAL = "distributed_mpi_messages_total";
    public static final String MATRIX_EXECUTION_DURATION = "distributed_matrix_execution_duration";
    public static final String EVENTS_PUBLISHED_TOTAL = "distributed_events_published_total";
    public static final String EVENT_NOTIFICATIONS_DROPPED_TOTAL = "distributed_event_notifications_dropped_total";
    public static final String POOL_SIZE = "distributed_pool_size";
    public static final String QUEUED_REQUESTS = "distributed_queued_requests";
    public static final String QUEUE_REMAINING_CAPACITY = "distributed_queue_remaining_capacity";
    public static final String REQUEST_THROUGHPUT = "distributed_request_throughput";
    public static final String RESPONSE_TIME_P95_MILLIS = "distributed_response_time_p95_millis";
    public static final String REQUEST_DURATION = "distributed_request_duration";
    /** Experiment 6: dispatch attempts per worker node, by strategy and outcome (served, failed, declined). */
    public static final String BALANCER_DISPATCHES_TOTAL = "distributed_balancer_dispatches_total";
    /** Experiment 6: makespan of each run, cluster-level (node_id "0"), by strategy. */
    public static final String BALANCER_MAKESPAN = "distributed_balancer_makespan";
    /** Experiment 6: requests the balancer has in flight to each worker node. */
    public static final String BALANCER_IN_FLIGHT = "distributed_balancer_in_flight";
    /** Experiment 7: MapReduce runs per coordinator node, by job and outcome (completed, failed). */
    public static final String MAPREDUCE_JOBS_TOTAL = "distributed_mapreduce_jobs_total";
    /** Experiment 7: total time of each completed MapReduce run, per coordinator node, by job. */
    public static final String MAPREDUCE_JOB_DURATION = "distributed_mapreduce_job_duration";
    /** Experiment 7: failed task attempts per worker node, by task type (map, reduce). */
    public static final String MAPREDUCE_TASK_ATTEMPTS_FAILED_TOTAL = "distributed_mapreduce_task_attempts_failed_total";

    /** Tag key every meter carries. */
    public static final String NODE_ID = "node_id";

    /** {@link #NODE_ID} value for cluster-level meters. */
    public static final String CLUSTER_NODE_ID = "0";

    private MetricNames() {
    }
}
