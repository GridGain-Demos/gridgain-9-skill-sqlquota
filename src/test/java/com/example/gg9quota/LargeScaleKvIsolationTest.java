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
import java.util.concurrent.atomic.AtomicReference;
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
 * Phase 2 scale-up of {@link KvIsolationTest}: same headline demo at gigabyte data scale.
 * The quota-busting workload now requires ~1 GB of working memory per query, so without
 * quotas this is the kind of workload that would tip a 4 GB node into GC death or OOM.
 * With a tight {@code statementMemoryQuota="100M"}, each greedy SQL attempt is rejected
 * almost instantly and K-V latency stays unaffected.
 *
 * <p>Tagged {@code "scale"} — excluded by default. Run with {@code ./gradlew test
 * -PincludeTags=scale}. Expected runtime ~3–5 min dominated by the 1 M row insert phase.</p>
 */
@Testcontainers
@Tag("scale")
final class LargeScaleKvIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(LargeScaleKvIsolationTest.class);

    private static final int T_DATA_ROWS = 1_000_000;
    private static final int T_DATA_PAYLOAD = 1_024;
    private static final int KV_KEYSPACE = 100_000;
    private static final String HEAP = "4g";
    private static final String STATEMENT_QUOTA = "100M";

    private static final Duration BASELINE_DURATION   = Duration.ofSeconds(10);
    private static final Duration UNDER_LOAD_DURATION = Duration.ofSeconds(30);
    private static final int SQL_PRESSURE_THREADS = 3;

    // Quota path means even a "greedy 1 GB sort" gets rejected fast, so K-V tail latency
    // should still be well-controlled. Thresholds slightly higher than Phase 1 because
    // the node has more state and more memory pressure overall.
    private static final double MAX_P99_MS = 200.0;
    private static final double MAX_P99_9_MS = 500.0;
    private static final double MAX_DEGRADATION_FACTOR = 10.0;

    @Container
    static final Gg9Container GG9 = new Gg9Container().withHeap(HEAP);

    private static IgniteClient client;

    @BeforeAll
    static void initClusterAndLoadData() throws Exception {
        log.info("Phase 2 scale: heap={} t_data_rows={} payload={}B (~{} MB raw) kv_keyspace={}",
            HEAP, T_DATA_ROWS, T_DATA_PAYLOAD,
            (long) T_DATA_ROWS * T_DATA_PAYLOAD / (1024 * 1024), KV_KEYSPACE);
        long t0 = System.nanoTime();
        new Gg9TestCluster(GG9).initialize(QuotaConfig.tightStatement(STATEMENT_QUOTA));
        client = IgniteClients.connect(GG9);
        SchemaFixture.createAndLoadTData(client, T_DATA_ROWS, T_DATA_PAYLOAD);
        SchemaFixture.createAndLoadKvData(client, KV_KEYSPACE);
        log.info("Setup complete in {} s", (System.nanoTime() - t0) / 1_000_000_000);
    }

    @AfterAll
    static void closeClient() {
        if (client != null) client.close();
    }

    @Test
    void kv_latency_stays_healthy_under_gb_scale_runaway_sql() throws Exception {
        log.info("--- Phase 1: baseline ({}s, no SQL pressure) ---", BASELINE_DURATION.toSeconds());
        KvWorkload.Result baseline = KvWorkload.run(client, KV_KEYSPACE, BASELINE_DURATION);
        log.info("Baseline:     {}", baseline.summary());
        assertThat(baseline.failures)
            .as("baseline K-V workload must not have any failures")
            .isZero();

        log.info("--- Phase 2: under SQL pressure ({}s, {} runaway 1 GB-sort threads) ---",
            UNDER_LOAD_DURATION.toSeconds(), SQL_PRESSURE_THREADS);

        ExecutorService sqlPool = Executors.newFixedThreadPool(SQL_PRESSURE_THREADS);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong sqlAttempts = new AtomicLong();
        AtomicLong sqlExpectedFailures = new AtomicLong();
        AtomicLong sqlUnexpected = new AtomicLong();
        AtomicReference<String> firstUnexpectedDetail = new AtomicReference<>();

        for (int i = 0; i < SQL_PRESSURE_THREADS; i++) {
            sqlPool.submit(() -> {
                while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                    sqlAttempts.incrementAndGet();
                    try (ResultSet<SqlRow> rs = client.sql().execute(
                            (Transaction) null,
                            "SELECT id, bucket, payload FROM t_data ORDER BY payload")) {
                        while (rs.hasNext()) rs.next();
                        sqlUnexpected.incrementAndGet();
                        firstUnexpectedDetail.compareAndSet(null,
                            "unexpected SUCCESS (1 GB sort under 100M statement quota should have failed)");
                    } catch (IgniteException e) {
                        if ("GG-MEMQUOTA-3".equals(e.codeAsString())) {
                            sqlExpectedFailures.incrementAndGet();
                        } else {
                            sqlUnexpected.incrementAndGet();
                            firstUnexpectedDetail.compareAndSet(null,
                                e.codeAsString() + ": " + e.getMessage());
                        }
                    } catch (RuntimeException e) {
                        // Wraps interruption / connection-close exceptions at shutdown boundary.
                        sqlUnexpected.incrementAndGet();
                        firstUnexpectedDetail.compareAndSet(null,
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
            });
        }

        try {
            KvWorkload.Result underLoad = KvWorkload.run(client, KV_KEYSPACE, UNDER_LOAD_DURATION);
            log.info("Under load:   {}", underLoad.summary());
            log.info("SQL pressure: attempts={} expected_failures(GG-MEMQUOTA-3)={} unexpected={}",
                sqlAttempts.get(), sqlExpectedFailures.get(), sqlUnexpected.get());

            if (firstUnexpectedDetail.get() != null) {
                log.info("first unexpected SQL outcome (of {}): {}",
                    sqlUnexpected.get(), firstUnexpectedDetail.get());
            }

            assertThat(sqlExpectedFailures.get())
                .as("background SQL pressure must actually exercise the quota path")
                .isGreaterThan(0);
            // Allow a small fraction of boundary-effect outcomes (queries in flight at shutdown).
            // The contract is "the quota fires under load", not "every single attempt is rejected
            // with exactly this code" — that would over-specify.
            double unexpectedRate = sqlUnexpected.get() / (double) Math.max(sqlAttempts.get(), 1);
            assertThat(unexpectedRate)
                .as("unexpected SQL outcomes (%d of %d) must be < 2%% of attempts",
                    sqlUnexpected.get(), sqlAttempts.get())
                .isLessThan(0.02);

            assertThat(underLoad.failures)
                .as("K-V operations must not fail under 1 GB-sort SQL pressure")
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
