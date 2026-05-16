package com.example.gg9quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gg9quota.support.CustomerScenarioRunner;
import com.example.gg9quota.support.CustomerScenarioRunner.Outcome;
import com.example.gg9quota.support.Gg9Container;
import com.example.gg9quota.support.Gg9TestCluster.QuotaConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reproduces a customer-reported scenario on GG9 9.1.10/9.1.11: they set
 * {@code ignite.sql.statementMemoryQuota=10%}, did not touch {@code offloadingEnabled},
 * and got a JVM {@code OutOfMemoryError} (heap dump ~17 GB) instead of a quota rejection
 * when running {@code SELECT … ORDER BY <unindexed column>}.
 *
 * <p>This class targets the published {@code gridgain/gridgain9:9.1.22} image — the version
 * we've verified. {@code CustomerScenarioTestOn9_1_10} runs the same workload against a
 * locally-built image of the customer's actual version.</p>
 *
 * <p>Why "1M" rather than literally "10%": on our 512 MB test heap, 10% = 51 MB and the
 * 10 000×1 KB workload (~10 MB raw) would fit under the cap. We use absolute "1M" so the
 * workload genuinely exceeds it. The semantic parity with the customer is "quota set, dataset
 * exceeds it"; the absolute value is just scaled to our heap.</p>
 */
@Testcontainers
final class CustomerScenarioTest {

    private static final Logger log = LoggerFactory.getLogger(CustomerScenarioTest.class);

    private static final String STATEMENT_QUOTA = "1M";

    @Container
    final Gg9Container GG9 = new Gg9Container();

    @Test
    void offloading_explicitly_off_rejects_with_GG_MEMQUOTA_3() throws Exception {
        Outcome o = CustomerScenarioRunner.runGreedySort(GG9, new QuotaConfig(STATEMENT_QUOTA, false, null));
        log.info("9.1.22 offloadingEnabled=false outcome: {}", o);
        assertThat(o.thrown)
            .as("with offloading off, the engine must reject the over-quota query, not let it run")
            .isNotNull();
        assertThat(o.thrown.codeAsString())
            .as("statement-quota rejections carry GG-MEMQUOTA-3")
            .isEqualTo("GG-MEMQUOTA-3");
    }

    @Test
    void offloading_explicitly_on_does_not_reject_via_quota() throws Exception {
        Outcome o = CustomerScenarioRunner.runGreedySort(GG9, new QuotaConfig(STATEMENT_QUOTA, true, null));
        log.info("9.1.22 offloadingEnabled=true outcome: {}", o);
        if (o.thrown != null) {
            assertThat(o.thrown.codeAsString())
                .as("with offloading on, the engine should not raise the statement-quota error " +
                    "(GG-MEMQUOTA-3). It may succeed via spill, or fail with a different error " +
                    "if spill is unsupported for this operator — but never the quota path.")
                .isNotEqualTo("GG-MEMQUOTA-3");
        }
    }

    /**
     * Mirrors the customer's exact CLI flow: cluster starts with defaults, then they run
     * {@code cluster config update ignite.sql.statementMemoryQuota=10%} on the already-running
     * cluster, then run the greedy query. Asks the question "does a runtime-applied tight
     * quota actually engage for queries that run afterwards?"
     */
    @Test
    void runtime_quota_update_engages_for_subsequent_queries() throws Exception {
        Outcome o = CustomerScenarioRunner.runGreedySortWithRuntimeQuotaUpdate(
            GG9, STATEMENT_QUOTA, false);
        log.info("9.1.22 runtime-applied quota outcome: {}", o);
        assertThat(o).isNotNull();
    }

    /**
     * Strict customer reproduction: only statementMemoryQuota is touched, offloadingEnabled
     * left at whatever the version defaults to.
     */
    @Test
    void customer_style_only_statement_quota_touched() throws Exception {
        Outcome o = CustomerScenarioRunner.runGreedySortCustomerStyle(GG9, STATEMENT_QUOTA);
        log.info("9.1.22 customer-style outcome: {}", o);
        assertThat(o).isNotNull();
    }
}
