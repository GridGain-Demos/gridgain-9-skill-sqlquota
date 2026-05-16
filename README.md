# GridGain 9 Query Quota Test Harness

A JUnit 5 + Testcontainers harness that exercises GridGain 9's heap-based SQL memory quotas and demonstrates that they protect the K-V subsystem from runaway SQL. Background and goals are in [CLAUDE.md](CLAUDE.md).

## Prerequisites

- **Java 17+** (toolchain auto-managed by Gradle; older JDKs will be downloaded if needed).
- **Docker Desktop** (or compatible Docker engine) running. Recent versions on macOS use `~/.docker/run/docker.sock`; the build picks that up automatically.
- **A GridGain 9 license** (see [License setup](#license-setup) below).
- ~6 GB free RAM for the default suite, ~10 GB for the Phase 2 scale suite.

## License setup

GridGain 9 requires a license to initialize a cluster. Before running the suite for the first time:

1. Obtain your GridGain license file (a `.json` document containing fields like `edition`, `features`, `id`, `signatures`, …) from GridGain or your account team.
2. Save it as **`gridgain-license.json` at the project root** (alongside `build.gradle.kts`).
3. The file is listed in `.gitignore` — confirm it stays uncommitted before pushing anywhere.

If the file is missing or empty, `@BeforeAll` fails fast with the absolute path the harness was looking for, so the diagnostic is obvious.

The license content is mounted into the container only at runtime and is automatically redacted from any test log output.

## Running

```
./gradlew test
```

First run pulls `gridgain/gridgain9:9.1.22` (~500 MB) and completes in ~90 s. Subsequent runs (default suite, container image cached) complete in ~60 s.

To exclude perf-sensitive tests on a busy laptop:

```
./gradlew test -PexcludeTags=perf
```

The heavy Phase 2 scale tests (1 M rows, 4 GB heap, ~10 min) are opt-in:

```
./gradlew test -PincludeTags=scale
```

## Test Inventory

### `SmokeSpikeTest` — pre-flight sanity (~5 s)

Cheap checks that catch the dumbest reasons the real tests would fail (license missing, image not pullable, REST endpoint wrong).

| Test | What it verifies |
| --- | --- |
| `license_file_is_present_locally` | `gridgain-license.json` is present and non-empty at the configured path. |
| `node_reports_state_via_rest` | Container starts; `GET /management/v1/node/state` returns 200 with a `name` field. |

### `PerQueryQuotaTest` — per-statement memory quota (~12 s)

Demonstrates `ignite.sql.statementMemoryQuota` rejecting a single greedy query.

**Setup:** 512 MB heap, `statementMemoryQuota="1M"`, `offloadingEnabled=false` (default). Table `t_data(id, bucket, payload)` is loaded with **10 000 rows × 1 024 B payload** (~10 MB raw) via batched `INSERT`.

| Test | What it verifies |
| --- | --- |
| `small_aggregation_under_quota_succeeds` | `SELECT COUNT(*) FROM t_data` returns 10 000 — control test proving the harness isn't broken when the workload fits. |
| `greedy_sort_is_rejected_by_statement_quota` | `SELECT … FROM t_data ORDER BY payload` (must materialize the full 10 MB result to sort an unindexed column) throws `IgniteException` with `codeAsString="GG-MEMQUOTA-3"`, `groupName="MEMQUOTA"`, message `"SQL query ran out of memory: Statement quota was exceeded."` |

### `TotalQuotaTest` — per-node memory quota (~12 s)

Demonstrates `ignite.sql.nodeMemoryQuota` rejecting concurrent queries that collectively bust the node-wide budget while a single instance of the same workload fits comfortably.

**Setup:** 512 MB heap, `statementMemoryQuota="100M"` (loose — any one query fits), `nodeMemoryQuota="15M"` (tight — only one ORDER BY at a time fits). Same 10 000 × 1 KB `t_data` payload as `PerQueryQuotaTest`. Node quota is applied via PATCH `/management/v1/configuration/node` after cluster init (it lives in node config, not cluster config — see [GG9 configuration notes](#gg9-configuration-notes-9122) below).

| Test | What it verifies |
| --- | --- |
| `single_sort_succeeds_in_isolation` | One `ORDER BY payload` query on its own returns 10 000 rows in <100 ms. Separates "workload too big" from "concurrency triggered the quota". |
| `concurrent_sorts_collectively_exceed_node_quota` | 4 simultaneous `ORDER BY payload` queries: at least one (typically all) throws `IgniteException` with `codeAsString="GG-MEMQUOTA-4"`, message `"SQL query ran out of memory: Node quota was exceeded."` |

### `KvIsolationTest` — headline isolation demo (~25 s, tagged `perf`)

The motivating use case: with quotas in place, runaway SQL fails fast and K-V keeps running. Same `statementMemoryQuota="1M"` as `PerQueryQuotaTest`. Two phases inside one test:

1. **Baseline** (8 s, K-V only) — single-threaded `get`/`put` loop on `kv_data` (10 000 entries, 4 reads per 1 write), every op timed into an HdrHistogram.
2. **Under load** (15 s, K-V + 3 SQL pressure threads) — three background threads in a tight loop fire the greedy `ORDER BY` query that each time hits `GG-MEMQUOTA-3` and immediately retries; meanwhile the main thread continues the K-V workload.

**Assertions:**
- 0 K-V failures in either phase.
- Every background SQL attempt that completes during the test surfaces `GG-MEMQUOTA-3` (no unexpected successes or other error codes).
- K-V p99 under SQL pressure < 100 ms, p99.9 < 300 ms.
- K-V p99 degradation factor (under-load / baseline) < 8×.

**Observed locally** (Docker Desktop / macOS, M-series):

| Phase | ops | failures | mean | p99 | p99.9 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline (8 s)       | ~31 600 | 0 | 0.25 ms | 0.61 ms | 1.01 ms | 32 ms |
| Under load (15 s)    | ~41 600 | 0 | 0.36 ms | 1.87 ms | 3.39 ms | 10 ms |

Under sustained SQL pressure (≈2 000 quota-rejected queries per second), **K-V p99 stays under 2 ms** and total throughput goes *up* (more time spent on the K-V path because the SQL path fails so quickly). Tagged `@Tag("perf")` because tail-latency thresholds are environment-sensitive — exclude with `./gradlew test -PexcludeTags=perf` on a loaded laptop.

## Phase 2 — Scale-Up (tagged `scale`, opt-in)

Phase 2 re-runs two of the Phase 1 scenarios at 100× the raw data volume (1 000 000 × 1 KB rows ≈ 1 GB) on a 4 GB heap. Goal: confirm the quota mechanism still gates cleanly when interim result sets are gigabyte-scale, not toy MB-scale. The full scale suite is ~8 minutes (insert phase dominates).

```
./gradlew test -PincludeTags=scale
```

### `LargeScaleTotalQuotaTest` — node quota at GB scale (~3 min)

**Setup:** 4 GB heap, `statementMemoryQuota="2G"` (loose — single 1 GB sort fits), `nodeMemoryQuota="2G"` (tight — two concurrent sorts overflow). 1 M-row × 1 KB `t_data` loaded in ~2.5 min.

| Test | What it verifies |
| --- | --- |
| `single_large_sort_succeeds_in_isolation` | One 1 M-row `ORDER BY payload` returns all rows in ~2.8 s (~355 k rows/sec). Proves the workload IS feasible on the 4 GB heap when alone. |
| `concurrent_large_sorts_collectively_exceed_node_quota` | 4 simultaneous 1 M-row sorts: all four throw `IgniteException` with `codeAsString="GG-MEMQUOTA-4"` after ~2.1 s. The quota still gates concurrency strictly at GB scale. |

### `LargeScaleKvIsolationTest` — K-V isolation at GB scale (~4 min)

**Setup:** 4 GB heap, `statementMemoryQuota="100M"` (tight — each greedy 1 GB sort attempt is rejected before consuming much heap), 1 M-row × 1 KB `t_data`, 100 k-entry `kv_data`. Same baseline + under-load shape as `KvIsolationTest`, longer durations (10 s / 30 s). Assertion tolerates up to 2 % "unexpected" SQL outcomes at the thread-shutdown boundary.

**Observed locally** (Docker Desktop / macOS, M-series):

| Phase | ops | failures | mean | p99 | p99.9 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline (10 s)         | ~39 400 | 0 | 0.25 ms | 0.64 ms | 1.47 ms | 51 ms |
| Under load (30 s)       | ~90 700 | 0 | 0.33 ms | 0.76 ms | 6.89 ms | 52 ms |

SQL pressure during the 30 s under-load phase: **1 098 attempts, 1 095 rejected with `GG-MEMQUOTA-3`** (99.7 %, 0 unexpected this run). The headline: **K-V p99 degradation is only 1.20×** (0.64 ms → 0.76 ms) under sustained 1 GB-sort pressure — the quota rejects greedy SQL fast enough that K-V hot-path latency is essentially unaffected. Same pattern as Phase 1, just bigger numbers.

## Troubleshooting your own environment

This repo ships with a Claude Code skill at [`.claude/skills/diagnosing-gg9-quota/`](.claude/skills/diagnosing-gg9-quota/) that helps diagnose GG9 quota issues in your environment — queries succeeding when they should be rejected, OOM under load, surprising `GG-MEMQUOTA` errors, K-V latency degradation under SQL pressure. To use it:

1. Open this project in Claude Code (or any agentic harness that loads project-local `.claude/skills/`).
2. Ask Claude something like *"my GG9 query isn't getting rejected even though I set a statement quota"* or *"help me reproduce my failing query in this harness"*. The skill auto-triggers.
3. The skill will ask you to share the query, schema, approximate data scale, current quota config, and the symptom — then walk through:
   - A live config inspection (the #1 silent failure mode is `offloadingEnabled` being true).
   - An operator-by-operator estimate of the query's interim memory.
   - Generation of a reproduction test class (using `references/reproduction-template.md`) so the issue can be inspected in isolation, free of your operational state.
   - A focused review of your Java client code, if you share it, against the common anti-patterns in `references/code-review.md`.

If you'd rather read the playbook directly, start with [`.claude/skills/diagnosing-gg9-quota/SKILL.md`](.claude/skills/diagnosing-gg9-quota/SKILL.md); the `references/` directory holds the longer-form guides on gotchas, memory analysis, code review, and tuning.

## GG9 configuration notes (9.1.22)

A few GG9-specific details worth knowing when reading or extending this harness — these are the things that aren't always obvious from the public docs:

- **Docker image** `gridgain/gridgain9:9.1.22`. Ports `10300` (REST/management) and `10800` (client connector — SQL, JDBC, and K-V all multiplex over it). Heap is controlled with the `JVM_MIN_MEM` / `JVM_MAX_MEM` env vars.
- **Cluster init** at `POST /management/v1/cluster/init` **requires** the `license` field inline in the JSON payload (the entire license file content, JSON-escaped). Mounting the license file into the container is not by itself sufficient. Successful init returns `200` with an empty body.
- **SQL quota keys live in two config trees** — the most important gotcha:
  - **Cluster config** (`/management/v1/configuration/cluster`): `ignite.sql.statementMemoryQuota` (default `"100%"` of heap), `ignite.sql.offloadingEnabled` (default `false`), `ignite.sql.memoryQuotaBlockSize` (default `"512k"`).
  - **Node config** (`/management/v1/configuration/node`): `ignite.sql.nodeMemoryQuota` (default `"60%"` of heap), `ignite.sql.offloadingDataDir`, `ignite.sql.offloadingDataLimit`.
- **Quota exception contract**: thrown as `org.apache.ignite.lang.IgniteException` — match on `codeAsString()`, not class identity. Per-statement breaches surface as `GG-MEMQUOTA-3` (`"Statement quota was exceeded."`); per-node breaches as `GG-MEMQUOTA-4` (`"Node quota was exceeded."`).
- **Setting node-level config at runtime**: `PATCH /management/v1/configuration/node` with `Content-Type: text/plain` and a HOCON body (e.g. `ignite.sql.nodeMemoryQuota = "15M"`). Returns `200` with an empty body and the change takes effect immediately — no node restart needed for the quota knobs.
- **Testcontainers** needs `1.21.x` or later. Older 1.20.x builds don't always negotiate cleanly with current Docker Desktop's bridge socket. On macOS, `build.gradle.kts` sets `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` when the env var isn't already set.

## Project Layout

```
gg9-query-quota/
  build.gradle.kts                            Gradle Kotlin DSL
  settings.gradle.kts
  gradle.properties
  gridgain-license.json                       gitignored, operator-supplied
  CLAUDE.md                                   project intent
  README.md                                   you are here
  src/test/
    java/com/example/gg9quota/
      SmokeSpikeTest.java
      PerQueryQuotaTest.java
      TotalQuotaTest.java
      KvIsolationTest.java
      LargeScaleTotalQuotaTest.java         Phase 2, @Tag("scale")
      LargeScaleKvIsolationTest.java        Phase 2, @Tag("scale")
      support/
        Gg9Container.java                     Testcontainers GenericContainer wrapper
        Gg9TestCluster.java                   cluster lifecycle + init payload + node-config PATCH
        ClusterInitClient.java                java.net.http REST client (license-redacted logging)
        LicenseLoader.java                    reads gridgain-license.json
        IgniteClients.java                    IgniteClient factory
        SchemaFixture.java                    DDL + batched load for t_data and kv_data
        KvWorkload.java                       single-threaded get/put loop, HdrHistogram timing
.claude/
  skills/
    diagnosing-gg9-quota/
      SKILL.md                                customer-facing diagnostic playbook
      references/
        gotchas.md                            silent-failure modes (offloadingEnabled etc.)
        query-memory-analysis.md              operator-by-operator memory estimation
        reproduction-template.md              copy-pasteable repro test skeleton
        code-review.md                        Java-client anti-patterns
        tuning.md                             sizing rules of thumb
    resources/
      logback-test.xml
```
