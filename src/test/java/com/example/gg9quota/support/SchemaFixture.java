package com.example.gg9quota.support;

import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.sql.BatchedArguments;
import org.apache.ignite.sql.IgniteSql;
import org.apache.ignite.sql.ResultSet;
import org.apache.ignite.sql.SqlRow;
import org.apache.ignite.tx.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SchemaFixture {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixture.class);

    private SchemaFixture() {}

    /**
     * Creates {@code t_data(id BIGINT PK, bucket INT, payload VARCHAR)} and loads {@code rowCount}
     * rows whose payload is {@code payloadBytes} bytes of ASCII filler. Buckets cycle 0..99 so a
     * self-join on bucket fans out predictably.
     */
    public static void createAndLoadTData(IgniteClient client, int rowCount, int payloadBytes) {
        IgniteSql sql = client.sql();

        sql.execute((Transaction) null, "CREATE TABLE IF NOT EXISTS t_data ("
            + "id BIGINT PRIMARY KEY,"
            + "bucket INT,"
            + "payload VARCHAR)");

        String payload = "x".repeat(payloadBytes);

        final int batchSize = 5_000;
        long inserted = 0;
        for (int start = 0; start < rowCount; start += batchSize) {
            int end = Math.min(start + batchSize, rowCount);
            BatchedArguments args = BatchedArguments.create();
            for (int i = start; i < end; i++) {
                args.add((long) i, i % 100, payload);
            }
            long[] updates = sql.executeBatch((Transaction) null,
                "INSERT INTO t_data (id, bucket, payload) VALUES (?, ?, ?)", args);
            for (long u : updates) inserted += u;
        }
        log.info("Loaded {} rows into t_data (payload={} bytes)", inserted, payloadBytes);
    }

    /**
     * Creates {@code kv_data(k BIGINT PK, v VARCHAR)} and pre-loads {@code keyspaceSize} entries
     * so subsequent {@code get(k)} calls always hit (no NPEs from missing keys, which would
     * otherwise muddy the K-V latency histogram).
     */
    public static void createAndLoadKvData(IgniteClient client, int keyspaceSize) {
        IgniteSql sql = client.sql();
        sql.execute((Transaction) null,
            "CREATE TABLE IF NOT EXISTS kv_data (k BIGINT PRIMARY KEY, v VARCHAR)");

        final int batchSize = 5_000;
        long inserted = 0;
        for (int start = 0; start < keyspaceSize; start += batchSize) {
            int end = Math.min(start + batchSize, keyspaceSize);
            BatchedArguments args = BatchedArguments.create();
            for (int i = start; i < end; i++) {
                args.add((long) i, "v" + i);
            }
            long[] updates = sql.executeBatch((Transaction) null,
                "INSERT INTO kv_data (k, v) VALUES (?, ?)", args);
            for (long u : updates) inserted += u;
        }
        log.info("Loaded {} rows into kv_data (keyspace size)", inserted);
    }

    public static long countRows(IgniteClient client) {
        try (ResultSet<SqlRow> rs = client.sql().execute((Transaction) null, "SELECT COUNT(*) FROM t_data")) {
            SqlRow row = rs.next();
            return row.longValue(0);
        }
    }
}
