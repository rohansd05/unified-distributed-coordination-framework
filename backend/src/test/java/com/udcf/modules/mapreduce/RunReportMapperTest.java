package com.udcf.modules.mapreduce;

import com.udcf.modules.mapreduce.dto.JobReportDto;
import com.udcf.modules.mapreduce.dto.ResultRowDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report DTO: null-not-zero for stages a failed run never finished, the latency rule
 * (average only from the reduced sum and count, only where count > 0), sorting and truncation.
 */
class RunReportMapperTest {

    @Test
    @DisplayName("a completed run: every count, the timings, sorted rows and the total key count")
    void completedRun() throws Exception {
        WordCountJob job = new WordCountJob();
        JobReport report = new JobReport(job.name());
        Map<String, String> results = MapReducePipeline.runLocal(job, List.of("b a", "c a", "a"), 2, report);

        JobReportDto dto = RunReportMapper.map(job, report, results, List.of(), 2, null, 10);

        assertThat(dto.reducers()).isEqualTo(2);
        assertThat(dto.inputLines()).isEqualTo(3);
        assertThat(dto.inputLinesDropped()).isNull();
        assertThat(dto.pairsEmitted()).isEqualTo(5L);
        assertThat(dto.pairsAfterCombine()).isEqualTo(report.pairsAfterCombine());
        assertThat(dto.shuffleKeys()).isEqualTo(3);
        assertThat(dto.partitions()).isEqualTo(report.reduceTasks());
        assertThat(dto.resultKeys()).isEqualTo(3);
        assertThat(dto.timings().mapMillis()).isNotNull();
        assertThat(dto.timings().totalMillis()).isNotNull();
        assertThat(dto.results()).containsExactly(
                new ResultRowDto("a", "3", 3L, null),
                new ResultRowDto("b", "1", 1L, null),
                new ResultRowDto("c", "1", 1L, null));
        assertThat(dto.resultsTruncated()).isFalse();
    }

    @Test
    @DisplayName("more keys than the row limit: the first rows by key, truncated, and the full key count")
    void truncation() {
        Map<String, String> results = new TreeMap<>(Map.of("d", "1", "a", "1", "c", "1", "b", "1"));

        JobReportDto dto = RunReportMapper.map(new WordCountJob(), new JobReport("word-count"), results,
                List.of(), 1, null, 2);

        assertThat(dto.results()).extracting(ResultRowDto::key).containsExactly("a", "b");
        assertThat(dto.resultsTruncated()).isTrue();
        assertThat(dto.resultKeys()).isEqualTo(4);
    }

    @Test
    @DisplayName("a failed run: counts of unfinished stages and the result count are null, never 0")
    void failedRun() {
        JobReport beforeMap = new JobReport("word-count");
        beforeMap.setInputLines(10);
        beforeMap.setSplits(2);
        beforeMap.setMapTasks(2);

        JobReportDto dto = RunReportMapper.map(new WordCountJob(), beforeMap, null, List.of(), 2, null, 10);

        assertThat(dto.inputLines()).isEqualTo(10);
        assertThat(dto.pairsEmitted()).isNull();
        assertThat(dto.pairsAfterCombine()).isNull();
        assertThat(dto.shuffleKeys()).isNull();
        assertThat(dto.partitions()).isNull();
        assertThat(dto.resultKeys()).isNull();
        assertThat(dto.combinerSavingPercent()).isNull();
        assertThat(dto.timings().mapMillis()).isNull();
        assertThat(dto.timings().totalMillis()).isNull();
        assertThat(dto.results()).isEmpty();

        JobReport afterMap = new JobReport("word-count");
        afterMap.setMapMillis(1.5);
        afterMap.setPairsEmitted(4);
        afterMap.setPairsAfterCombine(2);
        JobReportDto mapped = RunReportMapper.map(new WordCountJob(), afterMap, null, List.of(), 2, null, 10);
        assertThat(mapped.pairsEmitted()).isEqualTo(4L);
        assertThat(mapped.shuffleKeys()).isNull();
        assertThat(mapped.combinerSavingPercent()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("a completed run on empty input: real zeros for counts, null timings and null combiner saving")
    void emptyInput() throws Exception {
        WordCountJob job = new WordCountJob();
        JobReport report = new JobReport(job.name());
        Map<String, String> results = MapReducePipeline.runLocal(job, List.of(), 2, report);

        JobReportDto dto = RunReportMapper.map(job, report, results, List.of(), 2, 0, 10);

        assertThat(dto.pairsEmitted()).isZero();
        assertThat(dto.resultKeys()).isZero();
        assertThat(dto.inputLinesDropped()).isZero();
        assertThat(dto.combinerSavingPercent()).isNull();
        assertThat(dto.timings().mapMillis()).isNull();
        assertThat(dto.timings().shuffleMillis()).isNull();
        assertThat(dto.timings().reduceMillis()).isNull();
        assertThat(dto.timings().totalMillis()).isNotNull();
    }

    @Test
    @DisplayName("latency rows: the average is sum / count from the reduced value, only where count > 0")
    void latencyRows() {
        LatencyPerNodeJob job = new LatencyPerNodeJob();

        assertThat(RunReportMapper.row(job, "node-1", "30.0;3"))
                .isEqualTo(new ResultRowDto("node-1", "10.00 ms average over 3 events", 3L, 10.0));
        assertThat(RunReportMapper.row(job, "node-2", "0.0;0"))
                .isEqualTo(new ResultRowDto("node-2", LatencyPerNodeJob.NO_LATENCY_MEASURED, 0L, null));
        assertThat(RunReportMapper.row(job, "node-3", "garbage"))
                .isEqualTo(new ResultRowDto("node-3", LatencyPerNodeJob.NO_LATENCY_MEASURED, null, null));
    }

    @Test
    @DisplayName("never an average of averages: two partial sums reduce to the true average")
    void noAverageOfAverages() throws Exception {
        LatencyPerNodeJob job = new LatencyPerNodeJob();
        List<String> input = List.of("node=1 | latency=10", "node=1 | latency=10", "node=1 | latency=40");
        // Split over 2 mappers: averages 10 and 40 would average to 25; the true average is 20.
        Map<String, String> results = MapReducePipeline.runLocal(job, input, 2, new JobReport(job.name()));

        JobReportDto dto = RunReportMapper.map(job, new JobReport(job.name()), results, List.of(), 2, null, 10);

        assertThat(dto.results()).containsExactly(new ResultRowDto("node-1", "20.00 ms average over 3 events", 3L, 20.0));
    }

    @Test
    @DisplayName("a count that is not a number is shown, with a null count")
    void nonNumericCount() {
        assertThat(RunReportMapper.row(new WordCountJob(), "k", "many"))
                .isEqualTo(new ResultRowDto("k", "many", null, null));
    }
}
