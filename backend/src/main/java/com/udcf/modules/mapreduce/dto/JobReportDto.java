package com.udcf.modules.mapreduce.dto;

import java.util.List;
import java.util.Map;

/**
 * What a run measured, stage by stage. Counts of a stage a failed run never finished are
 * {@code null}, never 0.
 *
 * <p>No dedicated test: a record; built and tested by RunReportMapperTest.</p>
 *
 * @param reducers              R, the number of hash partitions (one per worker)
 * @param inputLines            lines given to the pipeline
 * @param inputLinesDropped     EVENT_LOG only: older lines left out by the byte cap; {@code null}
 *                              for other inputs
 * @param splits                non-blank input splits (one map task each)
 * @param mapTasks              map tasks dispatched
 * @param pairsEmitted          key-value pairs the mappers emitted before combining
 * @param pairsAfterCombine     pairs shipped to the shuffle after the combiner
 * @param shuffleKeys           distinct keys after the shuffle
 * @param partitions            non-empty partitions (one reduce task each)
 * @param resultKeys            keys in the final result (all of them, also when the list is truncated);
 *                              {@code null} for a failed run
 * @param combinerSavingPercent share of pairs the combiner removed; {@code null} when no pairs
 *                              were emitted
 * @param retriedTasks          tasks that needed more than one attempt and then succeeded
 * @param mapTasksPerNode       completed map tasks by worker id
 * @param reduceTasksPerNode    completed reduce tasks by worker id
 * @param tasks                 every task with its attempts (see {@link TaskRowDto})
 * @param results               result rows sorted by key, at most the configured number
 * @param resultsTruncated      true when there are more keys than rows listed
 */
public record JobReportDto(
        int reducers,
        int inputLines,
        Integer inputLinesDropped,
        int splits,
        int mapTasks,
        Long pairsEmitted,
        Long pairsAfterCombine,
        Integer shuffleKeys,
        Integer partitions,
        Integer resultKeys,
        Double combinerSavingPercent,
        int retriedTasks,
        StageTimingsDto timings,
        Map<Integer, Integer> mapTasksPerNode,
        Map<Integer, Integer> reduceTasksPerNode,
        List<TaskRowDto> tasks,
        List<ResultRowDto> results,
        boolean resultsTruncated
) {
}
