package com.udcf.modules.mapreduce;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventLogExporter;
import com.udcf.core.events.EventProperties;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.mapreduce.dto.RunCommand;
import com.udcf.modules.mapreduce.dto.RunDto;
import com.udcf.modules.mapreduce.dto.RunState;
import com.udcf.modules.mapreduce.dto.UploadDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.awaitility.Awaitility.await;

/**
 * Test support (not a test): a real cluster on 127.0.0.1 with the MapReduce module, a bus and a
 * meter registry. Only the mapreduce ports ({@code mapreduceBase + k}) are ever bound; the other
 * bases are placeholders no service in these tests starts. Callers pick a base inside Track D's
 * test blocks (24501-24599, 24701-24799).
 */
final class MapReduceModuleHarness implements AutoCloseable {

    static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);

    final ClusterEventBus bus;
    final Cluster cluster;
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final MapReduceModule module;

    MapReduceModuleHarness(int size, int mapreduceBase, MapReduceProperties wire, MapReduceModuleProperties props) {
        this(size, mapreduceBase, wire, props, null);
    }

    MapReduceModuleHarness(int size, int mapreduceBase, MapReduceProperties wire, MapReduceModuleProperties props,
                           ExecutorService runExecutor) {
        EventProperties events = new EventProperties(5000, 4096);
        bus = new ClusterEventBus(events, Clock.systemUTC());
        cluster = new Cluster(new ClusterProperties(size,
                List.of(NodeCapacity.FAST, NodeCapacity.MEDIUM, NodeCapacity.SLOW),
                new ClusterProperties.Ports(1100, 6000, 7000, 7100, 7200, mapreduceBase)), bus);
        EventLogExporter exporter = new EventLogExporter(bus);
        module = runExecutor == null
                ? new MapReduceModule(cluster, bus, wire, props, exporter, events, meters, Clock.systemUTC())
                : new MapReduceModule(cluster, bus, wire, props, exporter, events, meters, Clock.systemUTC(), runExecutor);
    }

    static MapReduceProperties wire(int maxRequestBytes) {
        return new MapReduceProperties(15000, 15000, maxRequestBytes, 50, 4, 32);
    }

    static UploadDto upload(String fileName, String text) {
        return new UploadDto(fileName, "text/plain",
                Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
    }

    RunDto runAndWait(RunCommand command) {
        return awaitFinished(module.startRun(command).runId());
    }

    /** Waits until the run has left RUNNING and the module has released its guard. */
    RunDto awaitFinished(String runId) {
        await().atMost(RUN_TIMEOUT).until(() -> module.run(runId).state() != RunState.RUNNING);
        await().atMost(RUN_TIMEOUT).until(() -> module.status() != ModuleStatus.BUSY);
        return module.run(runId);
    }

    /** Every recorded event of the mapreduce module (causal order). */
    List<ClusterEvent> moduleEvents() {
        return bus.query(MapReduceModule.ID, null, 5000);
    }

    /** The mapreduce events published for one run (JOB_* and WORKER_CRASH_TRIGGERED carry the runId). */
    List<ClusterEvent> runEvents(String runId) {
        return moduleEvents().stream().filter(e -> runId.equals(e.data().get("runId"))).toList();
    }

    @Override
    public void close() {
        try {
            module.close();
        } finally {
            cluster.close();
            bus.close();
        }
    }
}
