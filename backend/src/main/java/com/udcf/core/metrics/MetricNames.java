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
    /** Experiment 8: client updates of the update stream, by accepting primary ("0" if given up), model and status. */
    public static final String FAILOVER_UPDATES_TOTAL = "distributed_failover_updates_total";
    /** Experiment 8: acknowledged updates the new primary lacked when the service was restored, by new primary and model. */
    public static final String FAILOVER_LOST_UPDATES_TOTAL = "distributed_failover_lost_updates_total";
    public static final String MAP_TASKS_TOTAL = "distributed_map_tasks_total";
    public static final String REDUCE_TASKS_TOTAL = "distributed_reduce_tasks_total";
    /** Experiment 9: remote transfers per root node, by target node, operation, direction and status (LOCAL excluded). */
    public static final String MPI_MESSAGES_TOTAL = "distributed_mpi_messages_total";
    /** Experiment 10: measured parallel time of each matrix run that ran (not REFUSED), per root node, by outcome. */
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
    /** Experiment 1: calls per calling node, by target node, method, path (LOCAL, REMOTE) and outcome. */
    public static final String RMI_CALLS_TOTAL = "distributed_rmi_calls_total";
    /** Experiment 1: remote failures per calling node, by target node, method, stage and kind. */
    public static final String RMI_FAILURES_TOTAL = "distributed_rmi_failures_total";
    /** Experiment 1: measured round trip of each completed call, per calling node, by target node, method and path. */
    public static final String RMI_ROUND_TRIP = "distributed_rmi_round_trip";
    /** Experiment 1: 1 while the node's NodeEndpoint is exported, 0 otherwise. */
    public static final String RMI_ENDPOINT_EXPORTED = "distributed_rmi_endpoint_exported";
    /** Experiment 9: collectives per root node, by operation and outcome (COMPLETE, PARTIAL, FAILED, REFUSED). */
    public static final String MPI_COLLECTIVES_TOTAL = "distributed_mpi_collectives_total";
    /** Experiment 9: measured latency of each DELIVERED transfer, per root node, by target node, operation and direction. */
    public static final String MPI_TRANSFER_LATENCY = "distributed_mpi_transfer_latency";
    /** Experiment 9: measured total time of each collective that attempted a remote transfer, per root node, by operation and outcome. */
    public static final String MPI_COLLECTIVE_DURATION = "distributed_mpi_collective_duration";
    /** Experiment 10: matrix runs per root node, by outcome (COMPLETE, PARTIAL, FAILED, REFUSED). */
    public static final String MATRIX_RUNS_TOTAL = "distributed_matrix_runs_total";
    /** Experiment 10: each rank's multiply, measured on the node, per computing node; DONE computes only. */
    public static final String MATRIX_COMPUTE_DURATION = "distributed_matrix_compute_duration";
    /** Experiment 10: measured scatter, broadcast and gather times per root node, by phase; known phases only. */
    public static final String MATRIX_COMMUNICATION_DURATION = "distributed_matrix_communication_duration";

    /** Tag key every meter carries. */
    public static final String NODE_ID = "node_id";

    /** {@link #NODE_ID} value for cluster-level meters. */
    public static final String CLUSTER_NODE_ID = "0";

    private MetricNames() {
    }
}
