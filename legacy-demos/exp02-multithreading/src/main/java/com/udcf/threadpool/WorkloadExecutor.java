package com.udcf.threadpool;

import com.udcf.model.WorkloadType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Performs the actual work for a request.
 *
 * <p>This is intentionally real computation, not a fixed sleep. If every request simply
 * slept, the "throughput" and "response time" panels would measure nothing but the
 * sleep constant, and the multithreading demonstration would be hollow. Hashing work
 * genuinely competes for CPU, so increasing the pool size on a multi-core machine
 * produces a measurable — and explainable — change.</p>
 */
@Component
public class WorkloadExecutor {

    /** Hash rounds performed per unit of payload size. */
    private static final int ROUNDS_PER_UNIT = 40;

    /** Milliseconds of simulated IO wait per unit of payload size. */
    private static final double IO_MILLIS_PER_UNIT = 0.4d;

    /**
     * Runs the workload and returns a short human-readable summary for the UI.
     *
     * @throws InterruptedException if the worker thread is interrupted during an IO wait;
     *                              propagated so shutdown is not swallowed
     */
    public String execute(WorkloadType type, int payloadSize) throws InterruptedException {
        int size = Math.max(1, payloadSize);

        return switch (type) {
            case CPU_HASH -> "hash=" + cpuWork(size);
            case IO_SIMULATED -> "waited=" + ioWork(size) + "ms";
            case MIXED -> {
                String digest = cpuWork(size / 2 + 1);
                long waited = ioWork(size / 2 + 1);
                yield "hash=" + digest + " waited=" + waited + "ms";
            }
        };
    }

    /** Repeated SHA-256 over a growing digest. Returns the first bytes of the result. */
    private String cpuWork(int size) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = ("udcf-" + size).getBytes(StandardCharsets.UTF_8);

            int rounds = size * ROUNDS_PER_UNIT;
            for (int i = 0; i < rounds; i++) {
                buffer = digest.digest(buffer);
            }
            return HexFormat.of().formatHex(buffer, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec; reaching here means a broken runtime.
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }

    /** Blocking wait, standing in for a network or disk round trip. */
    private long ioWork(int size) throws InterruptedException {
        long millis = Math.max(1L, (long) (size * IO_MILLIS_PER_UNIT));
        Thread.sleep(millis);
        return millis;
    }
}
