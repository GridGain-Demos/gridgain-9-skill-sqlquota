# Reproduction template

Copy-pasteable skeleton for reproducing a customer's quota issue inside this harness. The pattern mirrors `PerQueryQuotaTest` / `TotalQuotaTest` but drops the existing schema in favour of the customer's.

## Workflow

1. Save the file as `src/test/java/com/example/gg9quota/<NameOf>ReproTest.java`.
2. Fill in the **four placeholders** marked `// REPLACE`.
3. Run with `./gradlew test --tests <NameOf>ReproTest --info` — the `--info` flag surfaces the test's logging so you can see exactly what `codeAsString()` came back.
4. Compare the observed outcome to what the customer reported. If they diverge, the issue is in the customer's environment; if they match, the harness now has a self-contained reproduction.

## When to also write a control test

If the reproduction *succeeds* (the customer's query passes when it shouldn't), add a second `@Test` method in the same class that runs the same query against the same data with **a quota tight enough that it MUST fire**. If the harder test passes too, something deeper is wrong than a quota threshold (e.g. `offloadingEnabled=true` snuck in). If the harder test fails as expected, the customer's quota is simply set too loose.

## Synthetic data: matching the customer's shape

The customer's real data may be proprietary. Generate synthetic data that matches the *shape* that drives memory cost:

- **Row count** — match exactly. This is the single biggest input to interim memory.
- **Column count** — match exactly for the columns the query touches.
- **Average row width** — for VARCHAR columns, use a fixed-width filler that approximates their actual average.
- **Cardinality of join / group-by keys** — match the number of distinct values. Skewed distributions matter; if the customer's data is heavily skewed, ask them for an approximation (e.g. "80% of rows fall into 5 keys, the rest are uniform").
- **Ordering** — usually doesn't matter for the planner unless there's an index. Default to inserting in primary-key order.

If you can't match the cardinality exactly, document the discrepancy in a comment so the customer knows.

## Skeleton

```java
package com.example.gg9quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.gg9quota.support.Gg9Container;
import com.example.gg9quota.support.Gg9TestCluster;
import com.example.gg9quota.support.Gg9TestCluster.QuotaConfig;
import com.example.gg9quota.support.IgniteClients;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.sql.BatchedArguments;
import org.apache.ignite.sql.IgniteSql;
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
 * Customer reproduction: <one-line description of what the customer reported>.
 *
 * Customer context (from intake):
 *   GG9 version       : 9.1.22
 *   Heap              : <e.g. 2g>
 *   statementMemoryQuota : <e.g. "100M">
 *   nodeMemoryQuota   : <e.g. "60%" — default>
 *   offloadingEnabled : <e.g. false>
 *   Reported symptom  : <quote them>
 */
@Testcontainers
final class CustomerReproQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(CustomerReproQuotaTest.class);

    // ── REPLACE 1: heap to match the customer's environment.
    private static final String HEAP = "2g";

    // ── REPLACE 2: quotas to match the customer's reported config.
    private static final String STATEMENT_QUOTA = "100M";
    // For node-quota reproductions, also tighten the node cap:
    // private static final String NODE_QUOTA = "60%";

    // ── REPLACE 3: customer's schema. Keep it minimal — only what the query needs.
    private static final String DDL =
        "CREATE TABLE IF NOT EXISTS customer_table ("
        + "id BIGINT PRIMARY KEY,"
        + "group_key INT,"
        + "payload VARCHAR(512))";

    // Approximate scale of the customer's data.
    private static final int ROW_COUNT = 100_000;
    private static final int PAYLOAD_BYTES = 512;
    private static final int GROUP_CARDINALITY = 1_000;

    @Container
    static final Gg9Container GG9 = new Gg9Container().withHeap(HEAP);

    private static IgniteClient client;

    @BeforeAll
    static void setUp() throws Exception {
        // For per-statement reproductions:
        new Gg9TestCluster(GG9).initialize(QuotaConfig.tightStatement(STATEMENT_QUOTA));
        // For per-node reproductions, swap the line above for:
        // new Gg9TestCluster(GG9).initialize(QuotaConfig.tightNode(NODE_QUOTA, STATEMENT_QUOTA));

        client = IgniteClients.connect(GG9);
        loadCustomerSchema();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
    }

    private static void loadCustomerSchema() {
        IgniteSql sql = client.sql();
        sql.execute((Transaction) null, DDL);

        String payload = "x".repeat(PAYLOAD_BYTES);
        final int batchSize = 5_000;
        long inserted = 0;
        for (int start = 0; start < ROW_COUNT; start += batchSize) {
            int end = Math.min(start + batchSize, ROW_COUNT);
            BatchedArguments args = BatchedArguments.create();
            for (int i = start; i < end; i++) {
                args.add((long) i, i % GROUP_CARDINALITY, payload);
            }
            long[] updates = sql.executeBatch((Transaction) null,
                "INSERT INTO customer_table (id, group_key, payload) VALUES (?, ?, ?)", args);
            for (long u : updates) inserted += u;
        }
        log.info("Loaded {} rows into customer_table", inserted);
    }

    /**
     * Run the customer's exact query and observe what happens. The assertion below
     * is intentionally permissive so the test PRINTS the actual outcome; tighten it
     * once you know what to expect.
     */
    @Test
    void customer_query_observed_behaviour() {
        // ── REPLACE 4: the customer's exact query, character-for-character.
        String customerQuery = "SELECT * FROM customer_table ORDER BY payload";

        long t0 = System.nanoTime();
        try (ResultSet<SqlRow> rs = client.sql().execute((Transaction) null, customerQuery)) {
            long rows = 0;
            while (rs.hasNext()) { rs.next(); rows++; }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info("Customer query SUCCEEDED: {} rows in {} ms", rows, ms);
            log.warn("Expected GG-MEMQUOTA-3 but query succeeded — confirm offloadingEnabled and "
                + "verify the cluster config matches the customer's reported settings.");
        } catch (IgniteException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info("Customer query FAILED in {} ms: codeAsString={} groupName={} message={}",
                ms, e.codeAsString(), e.groupName(), e.getMessage());
        }
    }

    /**
     * Optional control test: prove the harness isn't broken by running a tiny query
     * that should always succeed under the same quota config.
     */
    @Test
    void control_small_query_succeeds() {
        try (ResultSet<SqlRow> rs = client.sql().execute(
                (Transaction) null, "SELECT COUNT(*) FROM customer_table")) {
            long count = rs.next().longValue(0);
            assertThat(count).isEqualTo(ROW_COUNT);
        }
    }
}
```

## Variants

- **Reproducing a node-quota issue (`GG-MEMQUOTA-4`).** Set `tightNode(NODE_QUOTA, STATEMENT_QUOTA)`, then submit N concurrent copies of the customer's query via an `ExecutorService` — see `TotalQuotaTest` for the pattern.
- **Reproducing a "K-V starvation" report.** Add a `KvWorkload.run(...)` call on the main thread while a background pool fires the customer's greedy query in a loop — see `KvIsolationTest` for the pattern.
- **Reproducing skewed data distribution.** Replace the `i % GROUP_CARDINALITY` term with a deliberately skewed function (e.g. 80% of rows mapping to 5 keys) and document the skew in a comment.

## What to capture back to the customer

After running the reproduction, return the following:

- The reproduction class itself (it's now their bug report attachment).
- The observed `codeAsString()`, `groupName()`, and message.
- The actual `/management/v1/configuration/{cluster,node}` snapshot at the moment of the test.
- One sentence explaining whether the reproduction matched their report.
