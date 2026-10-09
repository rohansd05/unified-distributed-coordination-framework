package com.udcf.modules.mapreduce.dto;

/**
 * A run's crash plan and what happened to it.
 *
 * <p>No dedicated test: a record; tested through MapReduceCrashRunTest.</p>
 *
 * @param workerId    the node to crash
 * @param triggered   true once a task was sent to that node and the crash was carried out
 * @param nodeCrashed true if the module's crash call crashed the node, false if it was already
 *                    down by then; {@code null} while not triggered
 * @param taskType    MAP or REDUCE: the kind of the first task sent to the node, right after which
 *                    it was crashed; {@code null} while not triggered
 */
public record CrashDto(int workerId, boolean triggered, Boolean nodeCrashed, String taskType) {
}
