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
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Demonstrates GG9's per-node SQL memory quota: when concurrent SQL queries collectively
 * exceed {@code ignite.sql.nodeMemoryQuota}, late arrivals are rejected with
 * {@code GG-MEMQUOTA-4} while in-flight queries keep their reserved budget.
 *
 * <p>Setup: 512 MB heap, {@code statementMemoryQuota="100M"} (loose — each query alone fits),
 * {@code nodeMemoryQuota="15M"} (tight — only 1–2 ORDER BYs fit at once). Table loaded with
 * 10 000 rows × 1 KB payload; the workload is N parallel {@code ORDER BY payload} queries
 * (~10 MB intermediate each, can't share state), fired off via an {@code ExecutorService}.</p>
 *
 * <p>The "quota gates concurrency, not all queries" demonstration is split across two tests
 * so concurrency-race noise doesn't taint the harness-sanity check:
 * {@link #single_sort_succeeds_in_isolation()} proves the workload IS feasible at this
 * configuration, then {@link #concurrent_sorts_collectively_exceed_node_quota()} proves
 * contention triggers the quota.</p>
 */
@Testcontainers
final class TotalQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(TotalQuotaTest.class);

    private static final int ROW_COUNT = 10_000;
    private static final int PAYLOAD_BYTES = 1_024;
    private static final String STATEMENT_QUOTA = "100M";
    private static final String NODE_QUOTA = "15M";
    private static final int CONCURRENCY = 4;

    @Container
    static final Gg9Container GG9 = new Gg9Container();

    private static IgniteClient client;

    @BeforeAll
    static void initClusterAndLoadData() throws Exception {
        new Gg9TestCluster(GG9).initialize(QuotaConfig.tightNode(NODE_QUOTA, STATEMENT_QUOTA));
        client = IgniteClients.connect(GG9);
        SchemaFixture.createAndLoadTData(client, ROW_COUNT, PAYLOAD_BYTES);
    }

    @AfterAll
    static void closeClient() {
        if (client != null) client.close();
    }

    @Test
    void single_sort_succeeds_in_isolation() {
        // Sanity check: with the quotas in place, one ORDER BY on its own MUST fit. This
        // separates "harness broken / workload too big" from "concurrency triggers quota".
        QueryOutcome outcome = runOneSort(0);
        log.info("isolated sort outcome: {}", outcome.summary());
        assertThat(outcome.success)
            .as("a single sort under loose %s statement / %s node quotas must succeed (workload is feasible alone)",
                STATEMENT_QUOTA, NODE_QUOTA)
            .isTrue();
        assertThat(outcome.rowCount).isEqualTo(ROW_COUNT);
    }

    @Test
    void concurrent_sorts_collectively_exceed_node_quota() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<CompletableFuture<QueryOutcome>> futures = new ArrayList<>(CONCURRENCY);
            for (int i = 0; i < CONCURRENCY; i++) {
                final int idx = i;
                futures.add(CompletableFuture.supplyAsync(() -> runOneSort(idx), pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(60, TimeUnit.SECONDS);

            List<QueryOutcome> outcomes = futures.stream().map(CompletableFuture::join).toList();
            int successes = (int) outcomes.stream().filter(o -> o.success).count();
            List<QueryOutcome> rejections = outcomes.stream().filter(o -> !o.success).toList();

            log.info("TotalQuotaTest outcomes: {} success, {} rejection(s) out of {}",
                successes, rejections.size(), CONCURRENCY);
            for (QueryOutcome o : outcomes) log.info("  query #{}: {}", o.idx, o.summary());

            assertThat(rejections)
                .as("concurrent queries should collectively bust the %s node quota", NODE_QUOTA)
                .isNotEmpty();
            assertThat(rejections)
                .as("all rejections should carry the GG-MEMQUOTA-4 (node-quota) error code")
                .allSatisfy(o -> assertThat(o.codeAsString).isEqualTo("GG-MEMQUOTA-4"));
        } finally {
            pool.shutdownNow();
        }
    }

    private static QueryOutcome runOneSort(int idx) {
        long t0 = System.nanoTime();
        try (ResultSet<SqlRow> rs = client.sql().execute(
                (Transaction) null, "SELECT id, bucket, payload FROM t_data ORDER BY payload")) {
            long rows = 0;
            while (rs.hasNext()) { rs.next(); rows++; }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return QueryOutcome.success(idx, rows, ms);
        } catch (IgniteException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return QueryOutcome.failure(idx, e.codeAsString(), e.getMessage(), ms);
        }
    }

    private static final class QueryOutcome {
        final int idx;
        final boolean success;
        final long rowCount;
        final String codeAsString;
        final String message;
        final long ms;

        private QueryOutcome(int idx, boolean success, long rowCount,
                             String codeAsString, String message, long ms) {
            this.idx = idx;
            this.success = success;
            this.rowCount = rowCount;
            this.codeAsString = codeAsString;
            this.message = message;
            this.ms = ms;
        }

        static QueryOutcome success(int idx, long rows, long ms) {
            return new QueryOutcome(idx, true, rows, null, null, ms);
        }

        static QueryOutcome failure(int idx, String code, String msg, long ms) {
            return new QueryOutcome(idx, false, -1, code, msg, ms);
        }

        String summary() {
            return success
                ? "OK " + rowCount + " rows in " + ms + "ms"
                : "FAIL [" + codeAsString + "] in " + ms + "ms: " + message;
        }
    }
}
