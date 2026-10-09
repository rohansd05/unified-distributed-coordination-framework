package com.udcf.modules.mapreduce;

/**
 * Type of task dispatched by the coordinator to a worker in the MapReduce pipeline.
 */
public enum TaskType {
    MAP,
    REDUCE
}
