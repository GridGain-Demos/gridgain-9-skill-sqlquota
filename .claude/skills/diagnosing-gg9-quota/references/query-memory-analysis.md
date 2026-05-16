# Estimating a query's heap footprint

When the customer's query is "should bust the quota but doesn't" or "should fit but doesn't", the answer often falls out of a rough operator-by-operator cost estimate. Use this as a working model — exact numbers depend on the GG9 planner's choices.

## Per-operator memory cost

| Operator | Working-set size (approximate) | Notes |
|---|---|---|
| `ORDER BY` on unindexed column | `rows × avg_row_bytes` | Full materialise + sort. Often the dominant cost. |
| `ORDER BY` on indexed column | small (streaming) | Planner may skip the sort entirely. Confirm with `EXPLAIN PLAN FOR …`. |
| `GROUP BY` on low-cardinality | `distinct_keys × group_state` | Group state ≈ (key cols + aggregates) per group. Bounded by distinct-key count. |
| `GROUP BY` on high-cardinality | `rows × group_state` worst case | When distinct keys ≈ row count. Same as DISTINCT. |
| `DISTINCT` over wide projection | `rows × projection_bytes` | Worst when projection includes wide VARCHARs. |
| Hash `JOIN` (default for inner joins) | `build_side_rows × row_bytes` | Build side is whichever the planner picks as smaller. Bigger side streams. |
| Sort-merge `JOIN` | small per side (streaming) | Only used if both inputs are pre-sorted; planner choice. |
| Window function / `OVER (PARTITION BY …)` | per-partition state | Hard to estimate; suspect first when the surprise is "I didn't expect this to use 1 GB". |
| `UNION` (with dedup) | `union_result × row_bytes` | Dedup behaves like DISTINCT. `UNION ALL` is streaming. |
| Subquery in `WHERE` (uncorrelated) | result-set of the subquery | Materialises into an in-memory hash for the outer predicate. |
| `SELECT *` returning many rows | result buffer, not quota-counted | Doesn't count toward statement quota but consumes heap on the client connector side. Affects K-V latency, not quota errors. |

## Walking the customer's query

1. **Pull a query plan.** Run `EXPLAIN PLAN FOR <their query>` (GG9 supports this through the SQL API). The plan tree tells you which operators the planner actually picked — don't assume.
2. **Locate memory-blocking operators.** Anything in the table above with a `rows × …` cost is a candidate. Look for the largest one.
3. **Multiply through.** `rows × avg_bytes` is the floor. Add ~30% overhead for object headers, references, sort scratch space.
4. **Compare to the effective `statementMemoryQuota`.** If estimate > quota, the quota *should* fire. If it doesn't, the cause is environmental (see `gotchas.md` § 1).
5. **If estimate < quota.** Either the customer's row-count estimate is wrong (always plausible — verify with `SELECT COUNT(*)`) or there's another operator higher in the tree (window, dedup) eating the budget.

## Common rewrites that drop memory cost

When the customer's query is legitimately too big and they want to keep the quota, suggest in priority order:

1. **Add `LIMIT N`** — bounded result sets sort with a heap-sized priority queue (`O(N)` instead of `O(row_count)`). Often the cheapest fix.
2. **Push predicates down** — moving filters from the outer query into a subquery / CTE reduces the row count fed into the sort/group/join.
3. **Project only what's needed** — replace `SELECT *` with explicit columns. The sort's per-row cost drops with narrower projections.
4. **Add an index aligned with `ORDER BY` / `GROUP BY`** — converts a blocking operator into a streaming one. GG9 supports `CREATE INDEX … ON … (col)`.
5. **Rewrite `DISTINCT` over wide rows as `GROUP BY` on the key column** — equivalent semantics, often a tighter plan.
6. **Force a smaller build side on a hash join** — `JOIN … /*+ BROADCAST */ …` hints if the planner picks the wrong side. Verify the syntax against GG9 9.1.22 docs; hint surface changes between versions.

## Tools to use

- `EXPLAIN PLAN FOR <query>` — query plan.
- `SELECT * FROM SYSTEM.SQL_QUERIES` — currently in-flight queries with their memory budgets and elapsed time. Useful for catching unexpected concurrency.
- The harness's `KvWorkload` + `HdrHistogram` setup gives latency percentiles, not memory; for actual heap inspection use a JVM heap dump or async-profiler against the running container.
