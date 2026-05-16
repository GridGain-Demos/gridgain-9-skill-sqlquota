# Sizing GG9 quotas — rules of thumb

When the customer needs help picking quota values rather than diagnosing a bug, use this as a starting point. None of these numbers are GG9-recommended; they're conservative defaults that make K-V isolation likely on a typical deployment.

## Budget the heap

Start by dividing the JVM heap conceptually into four buckets. Allocate explicit ceilings to each:

| Bucket | Rough share of heap | Knob |
|---|---|---|
| K-V resident data + indexes | 30–40% | Implicit — sized by data volume; not directly capped. |
| SQL execution (sum of in-flight) | 30–40% | `ignite.sql.nodeMemoryQuota` |
| GC + JVM overhead headroom | 20–25% | Implicit — never give to anything else. |
| Other (Raft state, network buffers) | 5–10% | Implicit. |

The 20–25% GC headroom is the most commonly under-budgeted slice. Without it, even well-behaved workloads see latency spikes during major GC cycles.

## `nodeMemoryQuota` — pick a fraction

The simplest model: pick a fraction of heap that bounds the WORST CASE sum of in-flight SQL memory.

- **Conservative (K-V latency priority).** 30% of heap. Plenty of room for K-V hot path; SQL gets less concurrency.
- **Balanced.** 50% of heap. Default-ish behaviour, fits most workloads.
- **SQL-heavy.** 60–70% of heap. Acceptable if K-V is secondary or workloads don't compete.

Avoid `≥ 80%`. The GG9 default of `"60%"` is in the balanced range — leave it there unless K-V latency requires tightening.

## `statementMemoryQuota` — pick relative to query shape

The per-statement cap should bound a **single query's** allocation. Rules of thumb:

- **Estimate the query's interim memory** from `query-memory-analysis.md` (operator × row-count × row-bytes).
- **Set the quota to 1.5× the largest legitimate query.** Headroom accommodates planner variance and the per-operator overhead.
- **Express in absolute bytes** (`"200M"`), not percentages — the customer cares about absolute predictability across environments.

For the headline use case of "protect K-V from runaway SQL", `statementMemoryQuota` should be a fraction of `nodeMemoryQuota`:

```
statementMemoryQuota ≤ nodeMemoryQuota / max_expected_concurrency
```

With `nodeMemoryQuota=2G` and expected concurrent SQL of 4: per-statement should be `≤ 500M` to keep all 4 queries within the node cap.

## `offloadingEnabled` — when to keep it off

In this harness it's always `false`. For the customer's environment:

- **Keep `false`** if quotas are meant to PROTECT — i.e. a runaway query should fail loudly rather than degrade silently.
- **Set `true`** only if the customer explicitly wants graceful degradation over quota errors AND has provisioned disk for `ignite.sql.offloadingDataDir`. Note that setting it true effectively neuters the K-V isolation benefit — the query keeps running, just slowly.

If the customer asks "should I enable offloading?", the default answer is no.

## Concurrency sizing

`ignite.sql.execution.threadCount` (node config, default 4) governs how many queries can execute in parallel. For tuning quota behaviour:

- If `statementMemoryQuota × threadCount < nodeMemoryQuota`, the node quota will essentially never fire — increase concurrency or tighten the node quota to make it meaningful.
- If `statementMemoryQuota × threadCount > nodeMemoryQuota`, queries will compete for the node budget; some will be rejected with `GG-MEMQUOTA-4` under load.

This is the lever for trading throughput against tail latency.

## Worked example

Customer: 8 GB heap, primarily K-V workload, occasional analytical SQL with `ORDER BY` over ~500k rows.

Suggested config:

| Knob | Value | Rationale |
|---|---|---|
| `nodeMemoryQuota` | `"3G"` | ~38% of heap; leaves K-V budget intact. |
| `statementMemoryQuota` | `"800M"` | Comfortably above a 500k×1KB sort (~500 MB) with 60% headroom. |
| `offloadingEnabled` | `false` | Surface runaway queries with `GG-MEMQUOTA-3` so they can be debugged. |
| `ignite.sql.execution.threadCount` | `4` (default) | 4 × 800 MB = 3.2 GB → exceeds node cap, so queue forms before more queries can launch. |

Validate by running the customer's worst legitimate query alone (must succeed) and 4× concurrently (some MUST hit `GG-MEMQUOTA-4`).

## What NOT to recommend

- **`nodeMemoryQuota="100%"`** — defeats the K-V isolation guarantee.
- **`statementMemoryQuota="60%"`** of heap — a single query can monopolise the SQL budget; concurrent SQL workloads will always serialise.
- **Identical statement and node quotas** — concurrent queries can never both be in flight; the node-quota lever does nothing.
- **Enabling offloading "just in case"** — silently disables the feature you're paying for.
