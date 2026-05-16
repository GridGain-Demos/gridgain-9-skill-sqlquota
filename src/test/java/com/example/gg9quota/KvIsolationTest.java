package com.example.gg9quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gg9quota.support.Gg9Container;
import com.example.gg9quota.support.Gg9TestCluster;
import com.example.gg9quota.support.Gg9TestCluster.QuotaConfig;
import com.example.gg9quota.support.IgniteClients;
import com.example.gg9quota.support.KvWorkload;
import com.example.gg9quota.support.SchemaFixture;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.sql.ResultSet;
import org.apache.ignite.sql.SqlRow;
import org.apache.ignite.tx.Transaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Headline demonstration: with quotas in place, runaway SQL fails fast and K-V {@code get}/{@code put}
 * keep running with healthy latency. This is the "protect the key-value system from a bad SQL workload"
 * use case that motivated the entire feature.
 *
 * <p>Setup: 512 MB heap, {@code statementMemoryQuota="1M"} (same as {@link PerQueryQuotaTest} —
 * each greedy query is rejected almost immediately). Tables: {@code t_data} (10 000 × 1 KB)
 * for the SQL workload, {@code kv_data} (10 000 entries) for the K-V workload.</p>
 *
 * <p>Tagged {@code "perf"} because tail-latency thresholds are environment-sensitive. To
 * exclude on a loaded laptop: {@code ./gradlew test -PexcludeTags=perf}.</p>
 */
@Testcontainers
@Tag("perf")
final class KvIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(KvIsolationTest.class);

    private static final int T_DATA_ROWS = 10_000;
    private static final int T_DATA_PAYLOAD = 1_024;
    private static final int KV_KEYSPACE = 10_000;
    private static final String STATEMENT_QUOTA = "1M";

    private static final Duration BASELINE_DURATION  = Duration.ofSeconds(8);
    private static final Duration UNDER_LOAD_DURATION = Duration.ofSeconds(15);
    private static final int SQL_PRESSURE_THREADS = 3;

    // Tail-latency thresholds. Locally on Docker Desktop / macOS we see p99 < 10 ms, p99.9 < 50 ms
    // even under sustained SQL pressure. Generous ceilings so the test holds up on busy laptops.
    private static final double MAX_P99_MS = 100.0;
    private static final double MAX_P99_9_MS = 300.0;
    // Observed locally: 2.7–3.1x degradation. 8x ceiling leaves headroom for busier machines
    // while still catching a real regression (K-V slowed 10x is clearly impaired).
    private static final double MAX_DEGRADATION_FACTOR = 8.0;

    @Container
    static final Gg9Container GG9 = new Gg9Container();

    private static IgniteClient client;

    @BeforeAll
    static void initClusterAndLoadData() throws Exception {
        new Gg9TestCluster(GG9).initialize(QuotaConfig.tightStatement(STATEMENT_QUOTA));
        client = IgniteClients.connect(GG9);
        SchemaFixture.createAndLoadTData(client, T_DATA_ROWS, T_DATA_PAYLOAD);
        SchemaFixture.createAndLoadKvData(client, KV_KEYSPACE);
    }

    @AfterAll
    static void closeClient() {
        if (client != null) client.close();
    }

    @Test
    void kv_latency_stays_healthy_under_runaway_sql() throws Exception {
        log.info("--- Phase 1: baseline ({}s, no SQL pressure) ---", BASELINE_DURATION.toSeconds());
        KvWorkload.Result baseline = KvWorkload.run(client, KV_KEYSPACE, BASELINE_DURATION);
        log.info("Baseline:     {}", baseline.summary());
        assertThat(baseline.failures)
            .as("baseline K-V workload must not have any failures")
            .isZero();

        log.info("--- Phase 2: under SQL pressure ({}s, {} runaway SQL threads) ---",
            UNDER_LOAD_DURATION.toSeconds(), SQL_PRESSURE_THREADS);

        ExecutorService sqlPool = Executors.newFixedThreadPool(SQL_PRESSURE_THREADS);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong sqlAttempts = new AtomicLong();
        AtomicLong sqlExpectedFailures = new AtomicLong();
        AtomicLong sqlUnexpected = new AtomicLong();

        for (int i = 0; i < SQL_PRESSURE_THREADS; i++) {
            sqlPool.submit(() -> {
                while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                    sqlAttempts.incrementAndGet();
                    try (ResultSet<SqlRow> rs = client.sql().execute(
                            (Transaction) null,
                            "SELECT id, bucket, payload FROM t_data ORDER BY payload")) {
                        while (rs.hasNext()) rs.next();
                        sqlUnexpected.incrementAndGet(); // workload was supposed to bust the quota
                    } catch (IgniteException e) {
                        if ("GG-MEMQUOTA-3".equals(e.codeAsString())) {
                            sqlExpectedFailures.incrementAndGet();
                        } else {
                            sqlUnexpected.incrementAndGet();
                        }
                    }
                }
            });
        }

        try {
            KvWorkload.Result underLoad = KvWorkload.run(client, KV_KEYSPACE, UNDER_LOAD_DURATION);
            log.info("Under load:   {}", underLoad.summary());
            log.info("SQL pressure: attempts={} expected_failures(GG-MEMQUOTA-3)={} unexpected={}",
                sqlAttempts.get(), sqlExpectedFailures.get(), sqlUnexpected.get());

            assertThat(sqlExpectedFailures.get())
                .as("background SQL pressure must actually exercise the quota path")
                .isGreaterThan(0);
            assertThat(sqlUnexpected.get())
                .as("background SQL must not unexpectedly succeed or throw a different code")
                .isZero();

            assertThat(underLoad.failures)
                .as("K-V operations must not fail under SQL pressure")
                .isZero();

            assertThat(underLoad.p99Ms())
                .as("K-V p99 under SQL pressure must stay under %.1f ms", MAX_P99_MS)
                .isLessThan(MAX_P99_MS);
            assertThat(underLoad.p99_9Ms())
                .as("K-V p99.9 under SQL pressure must stay under %.1f ms", MAX_P99_9_MS)
                .isLessThan(MAX_P99_9_MS);

            double degradation = underLoad.p99Ms() / Math.max(baseline.p99Ms(), 0.1);
            log.info("p99 degradation factor (under-load / baseline): {}x", String.format("%.2f", degradation));
            assertThat(degradation)
                .as("K-V p99 must not degrade more than %.1fx under SQL pressure", MAX_DEGRADATION_FACTOR)
                .isLessThan(MAX_DEGRADATION_FACTOR);
        } finally {
            stop.set(true);
            sqlPool.shutdownNow();
            sqlPool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
