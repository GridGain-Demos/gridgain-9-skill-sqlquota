package com.example.gg9quota.support;

import com.example.gg9quota.support.Gg9TestCluster.QuotaConfig;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.sql.ResultSet;
import org.apache.ignite.sql.SqlRow;
import org.apache.ignite.tx.Transaction;

/**
 * Shared workload for customer-scenario reproductions: against a (just-started, not-yet-init'd)
 * Gg9Container, initialize the cluster with the given quota config, load 10 000 × 1 KB rows
 * into t_data, then run a greedy {@code ORDER BY payload} query and record what happened.
 *
 * <p>Used by {@code CustomerScenarioTest} (9.1.22) and any version-specific variants (e.g.
 * {@code CustomerScenarioTestOn9_1_10}) so all version comparisons share one workload.</p>
 */
public final class CustomerScenarioRunner {

    public static final int ROW_COUNT = 10_000;
    public static final int PAYLOAD_BYTES = 1_024;
    public static final String QUERY = "SELECT id, bucket, payload FROM t_data ORDER BY payload";

    private CustomerScenarioRunner() {}

    public static Outcome runGreedySort(Gg9Container container, QuotaConfig quota) throws Exception {
        new Gg9TestCluster(container).initialize(quota);
        try (IgniteClient client = IgniteClients.connect(container)) {
            SchemaFixture.createAndLoadTData(client, ROW_COUNT, PAYLOAD_BYTES);
            return runQuery(client);
        }
    }

    /**
     * Mirrors the customer's CLI flow: init cluster with default quotas, load data into an
     * already-running cluster, then apply the tight quota at runtime via PATCH on
     * {@code /management/v1/configuration/cluster} — the REST equivalent of
     * {@code cluster config update ignite.sql.statementMemoryQuota=…}. Then run the greedy
     * sort and see whether the runtime-applied quota actually engages.
     */
    public static Outcome runGreedySortWithRuntimeQuotaUpdate(
            Gg9Container container, String statementQuota, boolean offloadingEnabled) throws Exception {
        Gg9TestCluster cluster = new Gg9TestCluster(container);
        cluster.initializeWithoutClusterConfig(); // start at GG9 defaults (100% statement quota)

        try (IgniteClient client = IgniteClients.connect(container)) {
            SchemaFixture.createAndLoadTData(client, ROW_COUNT, PAYLOAD_BYTES);

            // Now apply the customer's quota change to the running cluster.
            String hocon = String.join("\n",
                "ignite.sql.offloadingEnabled = " + offloadingEnabled,
                "ignite.sql.statementMemoryQuota = \"" + statementQuota + "\"");
            var patch = cluster.rest().patchClusterConfig(hocon);
            if (patch.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                    "patchClusterConfig failed: " + patch.statusCode() + " " + patch.body());
            }

            return runQuery(client);
        }
    }

    /**
     * Strictly faithful reproduction of the customer's CLI flow: start the cluster at GG9
     * defaults, load data, then issue ONLY {@code cluster config update statementMemoryQuota=…}
     * (no offloading override, no node quota override), then run the greedy query.
     *
     * <p>Also dumps the post-PATCH cluster config so we can record what {@code offloadingEnabled}
     * defaults to on this version — the most likely remaining explanation for the customer's OOM
     * is that 9.1.10's default differs from 9.1.22's verified {@code false}.</p>
     */
    public static Outcome runGreedySortCustomerStyle(
            Gg9Container container, String statementQuota) throws Exception {
        Gg9TestCluster cluster = new Gg9TestCluster(container);
        cluster.initializeWithoutClusterConfig();

        try (IgniteClient client = IgniteClients.connect(container)) {
            SchemaFixture.createAndLoadTData(client, ROW_COUNT, PAYLOAD_BYTES);

            // Customer's exact CLI: only statementMemoryQuota touched. Nothing else.
            String hocon = "ignite.sql.statementMemoryQuota = \"" + statementQuota + "\"";
            var patch = cluster.rest().patchClusterConfig(hocon);
            if (patch.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                    "patchClusterConfig failed: " + patch.statusCode() + " " + patch.body());
            }

            // Dump the effective config so the test log shows what offloadingEnabled actually is.
            var cfg = cluster.rest().rawGet("/management/v1/configuration/cluster");
            String body = cfg.body();
            int off = body.indexOf("\"offloadingEnabled\"");
            String snippet = off >= 0 ? body.substring(off, Math.min(off + 40, body.length())) : "<not found>";
            org.slf4j.LoggerFactory.getLogger(CustomerScenarioRunner.class)
                .info("post-PATCH effective {} ", snippet);

            return runQuery(client);
        }
    }

    private static Outcome runQuery(IgniteClient client) {
        long t0 = System.nanoTime();
        try (ResultSet<SqlRow> rs = client.sql().execute((Transaction) null, QUERY)) {
            int rows = 0;
            while (rs.hasNext()) {
                rs.next();
                rows++;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return Outcome.success(rows, ms);
        } catch (IgniteException ex) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return Outcome.thrown(ex, ms);
        }
    }

    public static final class Outcome {
        public final boolean success;
        public final int rowsReturned;
        public final IgniteException thrown;
        public final long ms;

        private Outcome(boolean success, int rowsReturned, IgniteException thrown, long ms) {
            this.success = success;
            this.rowsReturned = rowsReturned;
            this.thrown = thrown;
            this.ms = ms;
        }

        public static Outcome success(int rows, long ms) {
            return new Outcome(true, rows, null, ms);
        }

        public static Outcome thrown(IgniteException ex, long ms) {
            return new Outcome(false, 0, ex, ms);
        }

        @Override
        public String toString() {
            if (success) return "OK " + rowsReturned + " rows in " + ms + "ms";
            return "THROWN [" + thrown.codeAsString() + "] in " + ms + "ms: " + thrown.getMessage();
        }
    }
}
