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
 * Same workload as {@link CustomerScenarioTest}, run against a locally-built image of the
 * customer's actual version (9.1.10) rather than the published 9.1.22. The 9.1.10/9.1.11
 * builds were never published to Docker Hub, so we build {@code gg9-local:9.1.10} from the
 * dist zip via {@code dockerfiles/Gg9FromDist.Dockerfile} before this test runs.
 *
 * <p>Prerequisite: {@code docker build -f dockerfiles/Gg9FromDist.Dockerfile -t gg9-local:9.1.10
 * --build-arg DIST_PARENT=gridgain9-db-9.1.10 --build-context dist=/Users/davidbrown/Code/gg9/9.1.10 .}</p>
 *
 * <p>These tests use only observational assertions — we genuinely don't know yet whether 9.1.10
 * reproduces the customer's OOM, succeeds via offload like 9.1.22 does, or fails some third way.
 * Each test logs the outcome and asserts the bare minimum that lets us tell those branches apart.</p>
 */
@Testcontainers
final class CustomerScenarioTestOn9_1_10 {

    private static final Logger log = LoggerFactory.getLogger(CustomerScenarioTestOn9_1_10.class);

    private static final String IMAGE = "gg9-local:9.1.10";
    private static final String STATEMENT_QUOTA = "1M";

    @Container
    final Gg9Container GG9 = new Gg9Container(IMAGE);

    @Test
    void offloading_explicitly_off() throws Exception {
        Outcome o = CustomerScenarioRunner.runGreedySort(GG9, new QuotaConfig(STATEMENT_QUOTA, false, null));
        log.info("9.1.10 offloadingEnabled=false outcome: {}", o);
        assertThat(o)
            .as("must produce some observable result (success or thrown), not hang or crash this JVM")
            .isNotNull();
    }

    @Test
    void offloading_explicitly_on() throws Exception {
        Outcome o = CustomerScenarioRunner.runGreedySort(GG9, new QuotaConfig(STATEMENT_QUOTA, true, null));
        log.info("9.1.10 offloadingEnabled=on outcome: {}", o);
        assertThat(o).isNotNull();
    }

    /**
     * Mirrors the customer's exact CLI flow on their version: cluster starts with defaults,
     * then they run {@code cluster config update ignite.sql.statementMemoryQuota=10%} on the
     * already-running cluster, then run the greedy query. This is the most likely path that
     * explains the customer's OOM — if 9.1.10 doesn't propagate the runtime-applied quota
     * to the SQL engine, the query runs with the prior wide-open quota and OOMs.
     */
    @Test
    void runtime_quota_update_engages_for_subsequent_queries() throws Exception {
        Outcome o = CustomerScenarioRunner.runGreedySortWithRuntimeQuotaUpdate(
            GG9, STATEMENT_QUOTA, false);
        log.info("9.1.10 runtime-applied quota outcome: {}", o);
        assertThat(o).isNotNull();
    }

    /**
     * Strict customer reproduction: only statementMemoryQuota is touched, offloadingEnabled
     * left at whatever 9.1.10 defaults to.
     */
    @Test
    void customer_style_only_statement_quota_touched() throws Exception {
        Outcome o = CustomerScenarioRunner.runGreedySortCustomerStyle(GG9, STATEMENT_QUOTA);
        log.info("9.1.10 customer-style outcome: {}", o);
        assertThat(o).isNotNull();
    }
}
