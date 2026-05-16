# Reviewing customer Java code that uses `IgniteClient`

When the harness can't reproduce a customer's report on stock GG9 9.1.22, the issue is likely in how their code uses the GG9 client. Run through this checklist against the code they shared. Each item lists the symptom, what to look for, and the fix.

## 1. Swallowing `IgniteException` behind a generic catch

**Symptom.** Customer says "the query failed but the log just says `query error`". They can't tell us the `codeAsString()`.

**Look for.**
```java
try {
    client.sql().execute(...);
} catch (Exception e) {           // ← too broad
    log.error("Query failed", e); // ← drops codeAsString
    throw new RuntimeException(e);
}
```

**Fix.** Always inspect `IgniteException.codeAsString()` before re-throwing or generalising.

```java
} catch (IgniteException e) {
    if ("GG-MEMQUOTA-3".equals(e.codeAsString())
            || "GG-MEMQUOTA-4".equals(e.codeAsString())) {
        log.warn("Quota rejection {}: {}", e.codeAsString(), e.getMessage());
        // fall through to retry-with-backoff or surface to caller
    } else {
        log.error("Unexpected SQL error code={} group={}: {}",
            e.codeAsString(), e.groupName(), e.getMessage(), e);
        throw e;
    }
}
```

## 2. Leaked `ResultSet`s

**Symptom.** Intermittent OOM with quotas configured, only under sustained load.

**Look for.** Any execute path that isn't wrapped in try-with-resources:

```java
ResultSet<SqlRow> rs = client.sql().execute(...);
while (rs.hasNext()) { ... }
// rs never closed
```

**Fix.** Always:

```java
try (ResultSet<SqlRow> rs = client.sql().execute(...)) {
    while (rs.hasNext()) { ... }
}
```

Same applies for `AsyncResultSet` returned from `executeAsync` — close on completion.

## 3. Retry storms

**Symptom.** A single quota rejection turns into hundreds of attempts within a second, briefly pinning the node.

**Look for.** Retry loops without backoff or jitter:

```java
for (int i = 0; i < 10; i++) {
    try {
        return runQuery();
    } catch (IgniteException e) { /* try again immediately */ }
}
```

**Fix.** Exponential backoff with jitter, and refuse to retry on quota errors specifically (they're a signal to back off, not retry):

```java
} catch (IgniteException e) {
    if ("GG-MEMQUOTA-3".equals(e.codeAsString())
            || "GG-MEMQUOTA-4".equals(e.codeAsString())) {
        // Quota is signalling load — surface, don't retry.
        throw e;
    }
    Thread.sleep(jitter(backoffMs *= 2));
}
```

## 4. Using JDBC where exception handling has different shape

**Symptom.** Customer reports `java.sql.SQLException: query failed` rather than a `codeAsString`.

**Look for.** Imports from `java.sql.*` or a JDBC URL like `jdbc:ignite:thin://…`.

**Fix.** Either:
- Switch to `IgniteClient.sql()` (preferred — cleaner error surface, the path this harness uses).
- Or, if JDBC must stay, walk the cause chain:
  ```java
  } catch (SQLException sql) {
      Throwable t = sql;
      while (t != null) {
          if (t instanceof IgniteException ig) {
              log.info("underlying code: {}", ig.codeAsString());
              break;
          }
          t = t.getCause();
      }
  }
  ```

## 5. Config writes before cluster is active

**Symptom.** Customer's bootstrap code sets `nodeMemoryQuota` via PATCH, then runs a query. Quota doesn't fire. Manual config inspection shows the PATCH didn't stick.

**Look for.** No "wait for active" between cluster init and the first config PATCH:

```java
restClient.initCluster(...);          // returns 200
restClient.patchNodeConfig("...");    // silently no-ops if cluster not yet active
```

**Fix.** Poll `/cluster/state` until `clusterTag` is present. See `ClusterInitClient.waitForClusterActive()` in this harness for an exemplar.

## 6. Async API without exception handling

**Symptom.** `executeAsync(...)` calls that "look successful" but the customer's K-V workload is degraded.

**Look for.** Fire-and-forget on `CompletableFuture`:

```java
client.sql().executeAsync(...);  // ← future ignored
```

**Fix.** Always attach a `.whenComplete` or `.exceptionally`:

```java
client.sql().executeAsync(null, query, params)
    .whenComplete((rs, ex) -> {
        if (ex instanceof IgniteException ig) {
            log.warn("async query quota result: {}", ig.codeAsString());
        }
        if (rs != null) rs.close();
    });
```

## 7. Long-lived `IgniteClient` with stale plan cache

**Symptom.** Config change applied at runtime but old behaviour persists for ~30 minutes.

**Look for.** Single application-wide `IgniteClient` created at startup and never refreshed; quota config changed via PATCH later.

**Fix.** Either tear down and rebuild the client after a config change, or wait for `ignite.sql.planner.planCacheExpiresAfterSeconds` (default 1800) to roll the cached plans.

## 8. `KeyValueView` vs `RecordView` confusion in K-V isolation reports

**Symptom.** Customer says K-V calls are slow under SQL pressure, but their "K-V" calls are actually `RecordView` reads of large records.

**Look for.** `table.recordView()` with wide records (≥ a few KB). These behave more like SQL `SELECT *` than like K-V point lookups.

**Fix.** Confirm the customer is using `keyValueView(K.class, V.class)` for true K-V access. If they need RecordView, the latency expectations are different (closer to SQL read path).

## 9. Hardcoded heap percentages without absolute values

**Symptom.** Customer's environment and the test harness disagree about whether quotas fire.

**Look for.** Quota config expressed as `"60%"` / `"100%"`.

**Fix.** When the customer is diagnosing, pin everything to absolute byte sizes (`"2G"`, `"100M"`) so the same config means the same thing across heaps. Reserve percentages for production where heap size is itself controlled.

## 10. Catching `OutOfMemoryError`

**Symptom.** Customer catches `Error` or `OutOfMemoryError` somewhere — silently — and continues. The JVM is in an undefined state but appears to keep running.

**Look for.** Any `catch (Throwable …)` or `catch (Error …)`.

**Fix.** Don't catch `Error`. If the customer's heap is exhausted, the right answer is to let the JVM die and re-bootstrap. Quotas exist precisely so that *queries* hit a clean exception instead of *the JVM* hitting an OOM — if OOMs are still happening, quotas aren't actually configured (see `gotchas.md`).
