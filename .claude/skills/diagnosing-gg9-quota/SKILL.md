---
name: diagnosing-gg9-quota
description: |
  Use when diagnosing GridGain 9 SQL memory quota issues — queries that should be
  rejected but succeed, heap exhaustion / OOM despite quotas being configured,
  surprising GG-MEMQUOTA-3 or GG-MEMQUOTA-4 errors, K-V latency degradation under
  SQL load, or any confusion about which quota knob to set where. Walks through
  an environment + config triage, analyses a customer-supplied query for memory
  hotspots, and (when given the query + schema) generates a reproduction test
  class inside this harness so the behaviour can be inspected in isolation.

  Trigger phrases: "quota not firing", "quota didn't work", "OOM in SQL",
  "GG-MEMQUOTA-3", "GG-MEMQUOTA-4", "node memory quota", "statement memory
  quota", "GridGain query failed", "offloadingEnabled", "K-V latency spike
  under SQL", "GridGain heap exhausted".
---

# Diagnosing GridGain 9 SQL memory quota issues

This skill is the entry point for helping a customer who is running into trouble with the GG9 SQL memory quota feature. The harness in this repo already proves the feature *works* on a stock single-node setup; what this skill is for is figuring out why it isn't working **in the customer's environment** with **their queries**.

The skill is organised as a triage flow with five steps. Don't skip them — Step 1 (cluster inspection) catches the most common failure mode (`offloadingEnabled=true`) and rules out 70%+ of "quota not firing" reports before anyone needs to look at a query plan.

## When NOT to use this skill

- If the customer just wants an overview of the feature → point them at `CLAUDE.md` and `README.md`.
- If the harness's own tests are failing → that's an internal regression, debug normally.
- If the customer reports a different GG9 issue (Raft, table partitioning, network) → out of scope.

## Step 0: Capture the report

Before doing anything else, get these from the customer in a single round-trip if possible:

1. **Symptom in one sentence.** "My query that should fail with GG-MEMQUOTA-3 instead runs and uses 4 GB of heap."
2. **GG9 version.** `curl http://<host>:10300/management/v1/cluster/state` → response includes `igniteVersion`. Confirm it matches what they think.
3. **The query** that's misbehaving. Full SQL, not paraphrased.
4. **The schema** for any table the query touches. Either DDL or "`name` BIGINT, `payload` VARCHAR(N), …" descriptions, plus approximate row count and average value size.
5. **The quota config they believe is in effect.** What did they set? Where (cluster config, node config, init payload, runtime PATCH)? At what value?
6. **What they observed.** Did the query succeed, fail with the wrong code, fail with the right code at the wrong time, or crash the node? If a code, the exact `codeAsString()` if available.
7. **Cluster shape.** Single node or multi-node? Heap size? `JVM_MAX_MEM`?
8. **(If they have it) their Java/client code** that submits the query.

Do not move to Step 1 with fewer than items 1–5. Ask follow-ups if any are missing.

## Step 1: Inspect the live cluster config

This catches almost all "quota not firing" cases. Run BOTH of these against the customer's node:

```
curl -s http://<host>:10300/management/v1/configuration/cluster | jq .ignite.sql
curl -s http://<host>:10300/management/v1/configuration/node    | jq .ignite.sql
```

What to look for, in priority order:

### 1a. `offloadingEnabled`

This lives in **cluster config** (not node config). Default in 9.1.22 is `false`. If it is `true` in the customer's environment, **that's the answer**: GG9 silently spills oversized result sets to disk instead of throwing — so the quota error never fires, but the node still uses unbounded heap-then-disk and the query "succeeds" with terrible latency.

Fix: PATCH cluster config to set it back to `false`. See `references/gotchas.md` § "offloadingEnabled flips quotas into spill mode".

### 1b. `statementMemoryQuota`

In **cluster config** (`/management/v1/configuration/cluster`). Default `"100%"` (effectively no limit). If the customer thinks they set this but it's still `"100%"`, they likely:

- Set it in the wrong tree (in node config instead of cluster config).
- Set it at startup via env vars that GG9 9.1.22 doesn't actually honor.
- Set it after init but a later operation reset it.

### 1c. `nodeMemoryQuota`

In **node config** (`/management/v1/configuration/node`). Default `"60%"` of heap. Same diagnosis as 1b but flipped: if the customer thinks they set it but the cluster config tree shows something different, it's because they wrote to the wrong endpoint.

### 1d. The keys are SEPARATE config trees

The most common confusion in the field. Map them explicitly for the customer:

| Quota knob | Lives in | Read endpoint | Settable via |
|---|---|---|---|
| `ignite.sql.statementMemoryQuota` | **cluster** config | `GET /management/v1/configuration/cluster` | `clusterConfiguration` HOCON in `POST /cluster/init`, or runtime PATCH `/configuration/cluster` |
| `ignite.sql.offloadingEnabled`    | **cluster** config | `GET /management/v1/configuration/cluster` | same as above |
| `ignite.sql.nodeMemoryQuota`      | **node** config    | `GET /management/v1/configuration/node`    | PATCH `/management/v1/configuration/node` (HOCON body, `Content-Type: text/plain`); no node restart needed |

If the customer's reported config doesn't match what's actually in the live cluster, stop here — the problem is config persistence/scope, not anything about their query.

## Step 2: Map the symptom to the right quota

Once you've confirmed what's actually in effect, classify:

- **`GG-MEMQUOTA-3`** ("Statement quota was exceeded.") → the customer hit `statementMemoryQuota`. A single query exceeded its per-statement cap.
- **`GG-MEMQUOTA-4`** ("Node quota was exceeded.") → the customer hit `nodeMemoryQuota`. The sum of concurrent in-flight queries on this node exceeded the per-node cap.
- **No error but heap exhausted / OOM** → almost certainly `offloadingEnabled=true` (silent spill) OR quotas are at their `"100%"` / `"60%"` defaults (effectively unlimited).
- **`GG-MEMQUOTA-3` on a query the customer thinks should fit** → quota too tight for the query shape. Go to Step 3.
- **`GG-MEMQUOTA-4` on a query that runs alone** → quota set extraordinarily tight, OR there are other in-flight queries the customer isn't aware of (e.g. background metrics / catalog probes). Inspect via `SELECT * FROM SYSTEM.SQL_QUERIES`.

## Step 3: Analyse the query for memory hotspots

Once it's clear *which* quota is firing (or should be), look at what the query actually allocates. See `references/query-memory-analysis.md` for the operator-by-operator table, but the short version:

- `ORDER BY` on an **un-indexed** column → full materialise + sort → ≈ row-count × avg-value-size, often the dominant cost.
- `GROUP BY` on a high-cardinality column → hash aggregation → ≈ distinct-keys × group-state.
- Hash `JOIN` with no broadcast hint → build-side table fully resident → ≈ build-side row-count × row-size.
- `DISTINCT` over a wide projection → same as a hash GROUP BY.
- Window functions / `OVER (PARTITION BY ...)` → per-partition state, hard to estimate; suspect this if results are surprising.
- `SELECT *` with no `LIMIT` returning many rows over the client connector → not quota-counted but consumes heap on the result buffer.

Estimate the dominant operator's memory in MB; compare against the customer's effective `statementMemoryQuota`. If estimated > quota, the quota *should* be firing — go back to Step 1 (it's almost certainly an `offloadingEnabled` or wrong-tree issue).

## Step 4: Build a reproduction inside this harness

The most powerful thing this harness offers is the ability to **reproduce the customer's situation in isolation**, free of their other operational state. Build a new test class that:

1. Stands up a fresh GG9 9.1.22 container with the customer's heap size.
2. Initialises the cluster with the customer's quota config exactly as they have it.
3. Recreates their schema (or a synthetic schema with the same shape).
4. Loads synthetic data matching their row count and approximate distribution.
5. Runs their query.
6. Asserts on the observed outcome — and crucially, also runs a control case to prove the test isn't broken.

A copy-pasteable template is at `references/reproduction-template.md`. The workflow:

1. Read `references/reproduction-template.md` for the full skeleton.
2. Copy it to `src/test/java/com/example/gg9quota/CustomerReproQuotaTest.java` (rename per situation).
3. Fill in the four placeholders: `HEAP`, `STATEMENT_QUOTA` / `NODE_QUOTA`, the `CREATE TABLE` DDL, and the customer's query.
4. Adjust the data-load loop in `SchemaFixture` (or inline) to match their distribution. For high-cardinality columns use `RANDOM(seed)`; for skewed distributions, mention to the customer that they may need to provide a more representative loader.
5. Run with `./gradlew test --tests CustomerReproQuotaTest --info` — `--info` surfaces the test's own logging so the operator can see exactly what error code came back.
6. Capture the actual `codeAsString()`, `groupName()`, and message in the test output. Compare against what the customer reported.

If the harness reproduces the customer's behaviour: hand them the test class. They now have an independent reproduction to bring to GridGain Support or to attach to a bug report. **This is often the entire deliverable** — a smaller, runnable thing they can iterate on.

If the harness does NOT reproduce it: that's diagnostic too. It means the issue is in their environment, not the GG9 SQL engine. Go to Step 5 (code review).

## Step 5: Review the customer's Java client code (when provided)

See `references/code-review.md` for the full check-list, but in priority order, look for:

1. **Catching `IgniteException` too broadly and swallowing the error.** Common pattern that hides quota rejections behind a generic "query failed" log line.
2. **Not closing `ResultSet`s.** Leaked cursors hold partial sort state until GC; under quota pressure this manifests as intermittent OOM.
3. **Retry storms.** Wrapping every query in a tight `retry(N)` loop without backoff means a single quota rejection turns into 10 quota rejections, which can briefly pin a node.
4. **Using JDBC where the API surface is different.** This harness uses `IgniteClient.sql()` deliberately; JDBC has a slightly different error wrapping that can hide the underlying GG9 code.
5. **Quota config written before cluster init completes.** Race: setting node config via PATCH before `/cluster/state` returns `clusterTag` will silently no-op. The harness's `ClusterInitClient.waitForClusterActive()` exists for exactly this reason.
6. **Submitting via async APIs but not handling failure on the future.** `executeAsync` returns a `CompletableFuture` that won't surface a quota rejection unless the caller checks `whenComplete` / `exceptionally`.

## Step 6: Recommend a fix

Once the actual cause is identified, decide between:

- **Config fix** (most common): right knob in right tree, restart not needed, instructions in `references/gotchas.md`.
- **Query fix**: rewrite the customer's query to reduce its intermediate state (add `LIMIT`, push predicates, use an indexed `ORDER BY`, narrow the projection). See `references/query-memory-analysis.md`.
- **Quota fix**: raise the quota (carefully — explain trade-off vs K-V isolation; see `references/tuning.md`).
- **Code fix**: from Step 5.
- **Bug report**: if the harness's reproduction confirms the customer is seeing something the harness doesn't reproduce on stock 9.1.22, the reproduction test class IS the bug report. Attach it.

## Output format

When delivering findings to the operator who invoked you, structure the response as:

```
## Diagnosis
<one-sentence root cause>

## Evidence
- Live config snapshot: <values that confirm the diagnosis>
- (If reproduced) Test class: <path>
- Customer-reported code in line: <only the codeAsString>

## Fix
<concrete steps the customer should take, in priority order>

## Follow-ups
<anything the customer should monitor or change downstream>
```

Keep it short. The full investigation lives in the test class and logs, not in this response.

## Related references

- `references/query-memory-analysis.md` — operator memory cost estimation
- `references/reproduction-template.md` — Java template for reproducing a customer query
- `references/code-review.md` — Java-client anti-patterns to look for
- `references/gotchas.md` — common silent-failure modes in GG9 quota config
- `references/tuning.md` — sizing rules of thumb
