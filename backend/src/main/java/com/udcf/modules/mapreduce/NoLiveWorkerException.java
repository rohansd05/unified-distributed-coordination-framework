package com.udcf.modules.mapreduce;

/**
 * Thrown when a run is requested while every node is crashed. Mapped to HTTP 409 by
 * {@link MapReduceController}.
 *
 * <p>No dedicated test: a constructor only.</p>
 */
public class NoLiveWorkerException extends RuntimeException {

    public NoLiveWorkerException() {
        super("No live node is available to run a MapReduce job; recover a node on the Cluster page");
    }
}
