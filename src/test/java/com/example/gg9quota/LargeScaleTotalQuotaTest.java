package com.example.gg9quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gg9quota.support.Gg9Container;
import com.example.gg9quota.support.Gg9TestCluster;
import com.example.gg9quota.support.Gg9TestCluster.QuotaConfig;
import com.example.gg9quota.support.IgniteClients;
import com.example.gg9quota.support.SchemaFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * Phase 2 scale-up of {@link TotalQuotaTest}: same scenario with 100× the data so the
 * interim sort buffer hits real GB-scale memory, not toy MB-scale.
 *
 * <p>Setup: 4 GB heap, {@code t_data} loaded with 1 000 000 × 1 KB rows (~1 GB raw).
 * {@code statementMemoryQuota="2G"} (loose — single sort fits with headroom);
 * {@code nodeMemoryQuota="2G"} (tight — second concurrent sort overflows).
 * 4 concurrent {@code ORDER BY payload} queries collectively bust the cap.</p>
 *
 * <p>Tagged {@code "scale"} — excluded by default. Run with {@code ./gradlew test
 * -PincludeTags=scale}. Expected runtime ~3–5 min (image already cached; insert phase
 * dominates at ~30–60 s; queries themselves are fast since most are admission-rejected).</p>
 */
@Testcontainers
@Tag("scale")
final class LargeScaleTotalQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(LargeScaleTotalQuotaTest.class);

    private static final int ROW_COUNT = 1_000_000;
    private static final int PAYLOAD_BYTES = 1_024;
    private static final String HEAP = "4g";
    private static final String STATEMENT_QUOTA = "2G";
    private static final String NODE_QUOTA = "2G";
    private static final int CONCURRENCY = 4;

    @Container
    static final Gg9Container GG9 = new Gg9Container().withHeap(HEAP);

    private static IgniteClient client;

    @BeforeAll
    static void initClusterAndLoadData() throws Exception {
        log.info("Phase 2 scale: heap={} rows={} payload={}B (~{} MB raw)",
            HEAP, ROW_COUNT, PAYLOAD_BYTES, (long) ROW_COUNT * PAYLOAD_BYTES / (1024 * 1024));
        long t0 = System.nanoTime();
        new Gg9TestCluster(GG9).initialize(QuotaConfig.tightNode(NODE_QUOTA, STATEMENT_QUOTA));
        client = IgniteClients.connect(GG9);
        SchemaFixture.createAndLoadTData(client, ROW_COUNT, PAYLOAD_BYTES);
        log.info("Setup complete in {} s", (System.nanoTime() - t0) / 1_000_000_000);
    }

    @AfterAll
    static void closeClient() {
        if (client != null) client.close();
    }

    @Test
    void single_large_sort_succeeds_in_isolation() {
        long t0 = System.nanoTime();
        long rows = 0;
        try (ResultSet<SqlRow> rs = client.sql().execute(
                (Transaction) null, "SELECT id, bucket, payload FROM t_data ORDER BY payload")) {
            while (rs.hasNext()) { rs.next(); rows++; }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("isolated 1M-row sort: {} rows in {} ms (~{} rows/s)",
            rows, ms, rows * 1000 / Math.max(ms, 1));
        assertThat(rows).isEqualTo(ROW_COUNT);
    }

    @Test
    void concurrent_large_sorts_collectively_exceed_node_quota() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<CompletableFuture<Outcome>> futures = new ArrayList<>(CONCURRENCY);
            for (int i = 0; i < CONCURRENCY; i++) {
                final int idx = i;
                futures.add(CompletableFuture.supplyAsync(() -> runSort(idx), pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(5, TimeUnit.MINUTES);

            List<Outcome> outcomes = futures.stream().map(CompletableFuture::join).toList();
            int successes = (int) outcomes.stream().filter(o -> o.success).count();
            List<Outcome> rejections = outcomes.stream().filter(o -> !o.success).toList();

            log.info("Large-scale outcomes: {} success, {} rejection(s) out of {}",
                successes, rejections.size(), CONCURRENCY);
            for (Outcome o : outcomes) log.info("  query #{}: {}", o.idx, o.summary());

            assertThat(rejections)
                .as("at GB-scale, concurrent sorts must collectively bust the %s node quota", NODE_QUOTA)
                .isNotEmpty();
            assertThat(rejections)
                .as("all rejections should carry GG-MEMQUOTA-4")
                .allSatisfy(o -> assertThat(o.codeAsString).isEqualTo("GG-MEMQUOTA-4"));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Outcome runSort(int idx) {
        long t0 = System.nanoTime();
        try (ResultSet<SqlRow> rs = client.sql().execute(
                (Transaction) null, "SELECT id, bucket, payload FROM t_data ORDER BY payload")) {
            long rows = 0;
            while (rs.hasNext()) { rs.next(); rows++; }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return Outcome.success(idx, rows, ms);
        } catch (IgniteException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return Outcome.failure(idx, e.codeAsString(), e.getMessage(), ms);
        }
    }

    private static final class Outcome {
        final int idx;
        final boolean success;
        final long rowCount;
        final String codeAsString;
        final String message;
        final long ms;

        private Outcome(int idx, boolean success, long rowCount,
                        String codeAsString, String message, long ms) {
            this.idx = idx;
            this.success = success;
            this.rowCount = rowCount;
            this.codeAsString = codeAsString;
            this.message = message;
            this.ms = ms;
        }

        static Outcome success(int idx, long rows, long ms) {
            return new Outcome(idx, true, rows, null, null, ms);
        }

        static Outcome failure(int idx, String code, String msg, long ms) {
            return new Outcome(idx, false, -1, code, msg, ms);
        }

        String summary() {
            return success
                ? "OK " + rowCount + " rows in " + ms + "ms"
                : "FAIL [" + codeAsString + "] in " + ms + "ms: " + message;
        }
    }
}
