package com.example.gg9quota.support;

import java.time.Duration;
import java.util.SplittableRandom;
import org.HdrHistogram.Histogram;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.table.KeyValueView;

/**
 * Tight-loop K-V workload that times every {@code get}/{@code put} into an HdrHistogram.
 * Single-threaded by design — adding threads would obscure tail-latency analysis.
 *
 * <p>Reads outnumber writes 4:1 (typical for primary K-V access). Keys are picked uniformly
 * from {@code [0, keyspaceSize)} so all reads hit pre-populated entries (no nulls).</p>
 */
public final class KvWorkload {

    private static final int READ_WRITE_RATIO = 4; // 4 gets per put

    private KvWorkload() {}

    public static final class Result {
        public final long opsTotal;
        public final long failures;
        public final Histogram latencyNs;

        public Result(long opsTotal, long failures, Histogram latencyNs) {
            this.opsTotal = opsTotal;
            this.failures = failures;
            this.latencyNs = latencyNs;
        }

        public double p99Ms()   { return latencyNs.getValueAtPercentile(99.0)   / 1_000_000.0; }
        public double p99_9Ms() { return latencyNs.getValueAtPercentile(99.9)   / 1_000_000.0; }
        public double maxMs()   { return latencyNs.getMaxValue()                / 1_000_000.0; }
        public double meanMs()  { return latencyNs.getMean()                    / 1_000_000.0; }

        public String summary() {
            return String.format(
                "ops=%d failures=%d mean=%.2fms p99=%.2fms p99.9=%.2fms max=%.2fms",
                opsTotal, failures, meanMs(), p99Ms(), p99_9Ms(), maxMs());
        }
    }

    public static Result run(IgniteClient client, int keyspaceSize, Duration duration) {
        KeyValueView<Long, String> kv = client.tables()
            .table("kv_data")
            .keyValueView(Long.class, String.class);

        // HdrHistogram: 1 ns precision, max 60 s tracked, 3 significant digits.
        Histogram hist = new Histogram(Duration.ofSeconds(60).toNanos(), 3);
        SplittableRandom rng = new SplittableRandom(42);

        long failures = 0;
        long ops = 0;
        long endNs = System.nanoTime() + duration.toNanos();
        int tick = 0;

        while (System.nanoTime() < endNs) {
            long key = rng.nextLong(keyspaceSize);
            boolean isWrite = (tick++ % (READ_WRITE_RATIO + 1)) == READ_WRITE_RATIO;

            long t0 = System.nanoTime();
            try {
                if (isWrite) {
                    kv.put(key, "v" + key + "_" + tick);
                } else {
                    String v = kv.get(key);
                    if (v == null) failures++;
                }
            } catch (Exception e) {
                failures++;
            }
            long latency = System.nanoTime() - t0;
            // Clamp to histogram range — anything over 60 s is effectively a stuck connection.
            hist.recordValue(Math.min(latency, hist.getHighestTrackableValue()));
            ops++;
        }
        return new Result(ops, failures, hist);
    }
}
