package com.example.gg9quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.gg9quota.support.Gg9Container;
import com.example.gg9quota.support.Gg9TestCluster;
import com.example.gg9quota.support.Gg9TestCluster.QuotaConfig;
import com.example.gg9quota.support.IgniteClients;
import com.example.gg9quota.support.SchemaFixture;
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
 * Demonstrates GG9's per-statement memory quota: when a single SQL query's interim heap
 * footprint exceeds {@code ignite.sql.statementMemoryQuota}, GG9 rejects it cleanly rather
 * than letting it consume unbounded heap.
 *
 * <p>Setup: cluster heap 512 MB; statement quota tightened to 1 MB; offloading off (default).
 * Workload: 10,000 rows × 1 KB payload (~10 MB raw) ordered by the unindexed payload column —
 * the planner must materialize the full set to sort it, well over the 1 MB cap.</p>
 */
@Testcontainers
final class PerQueryQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(PerQueryQuotaTest.class);

    private static final int ROW_COUNT = 10_000;
    private static final int PAYLOAD_BYTES = 1_024;
    private static final String STATEMENT_QUOTA = "1M";

    @Container
    static final Gg9Container GG9 = new Gg9Container();

    private static IgniteClient client;

    @BeforeAll
    static void initClusterAndLoadData() throws Exception {
        new Gg9TestCluster(GG9).initialize(QuotaConfig.tightStatement(STATEMENT_QUOTA));
        client = IgniteClients.connect(GG9);
        SchemaFixture.createAndLoadTData(client, ROW_COUNT, PAYLOAD_BYTES);
    }

    @AfterAll
    static void closeClient() {
        if (client != null) client.close();
    }

    @Test
    void small_aggregation_under_quota_succeeds() {
        long count = SchemaFixture.countRows(client);
        assertThat(count).isEqualTo(ROW_COUNT);
    }

    @Test
    void greedy_sort_is_rejected_by_statement_quota() {
        assertThatThrownBy(() -> {
            try (ResultSet<SqlRow> rs = client.sql().execute(
                    (Transaction) null, "SELECT id, bucket, payload FROM t_data ORDER BY payload")) {
                int drained = 0;
                while (rs.hasNext()) {
                    rs.next();
                    drained++;
                }
                log.warn("Drained {} rows without hitting quota — query streamed unexpectedly", drained);
            }
        })
        .isInstanceOf(IgniteException.class)
        .satisfies(t -> {
            IgniteException ig = (IgniteException) t;
            log.info("Got expected IgniteException: code={} codeAsString={} groupName={} message={}",
                ig.code(), ig.codeAsString(), ig.groupName(), ig.getMessage());
            // The plan predicts "GG-MEMQUOTA-3"; assert on the codeAsString contract.
            assertThat(ig.codeAsString())
                .as("statement quota errors should be coded as GG-MEMQUOTA-3")
                .isEqualTo("GG-MEMQUOTA-3");
        });
    }
}
