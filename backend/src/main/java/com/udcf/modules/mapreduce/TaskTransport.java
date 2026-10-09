package com.udcf.modules.mapreduce;

import java.io.IOException;

/**
 * Transport contract for shipping tasks to workers and receiving replies.
 *
 * <p>This interface is the clean seam between the pure algorithm pipeline
 * ({@link MapReducePipeline}) and the network: step E7a tests use in-memory
 * implementations, while step E7b implements it over TCP sockets on port
 * {@code ports().mapreduce()} (730k).</p>
 */
@FunctionalInterface
public interface TaskTransport {

    /**
     * Executes {@code taskType} on {@code targetNodeId} with the given {@code payload}.
     *
     * @param targetNodeId node ID of the destination worker
     * @param taskType MAP or REDUCE
     * @param jobName name of the job looked up in {@link JobRegistry}
     * @param payload encoded input split or partition data
     * @return encoded result from the worker
     * @throws IOException if the target worker fails or cannot be reached
     */
    String executeTask(int targetNodeId, TaskType taskType, String jobName, String payload)
            throws IOException;

    /**
     * Creates an in-memory transport executing tasks purely in the JVM via
     * {@link MapTask} and {@link ReduceTask}.
     */
    static TaskTransport inMemory(JobRegistry registry) {
        return (targetNodeId, taskType, jobName, payload) -> {
            MapReduceJob job = registry.get(jobName);
            if (taskType == TaskType.MAP) {
                return MapTask.execute(job, payload);
            } else if (taskType == TaskType.REDUCE) {
                return ReduceTask.execute(job, payload);
            } else {
                throw new IllegalArgumentException("Unsupported task type: " + taskType);
            }
        };
    }
}
