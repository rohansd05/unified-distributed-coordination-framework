package com.udcf.modules.mapreduce;

/**
 * Thrown when a run names a job the registry does not have. Mapped to HTTP 404 by
 * {@link MapReduceController}.
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class UnknownJobException extends RuntimeException {

    private final String jobId;

    public UnknownJobException(String jobId) {
        super("No MapReduce job with id '" + jobId + "'");
        this.jobId = jobId;
    }

    public String jobId() {
        return jobId;
    }
}
