# GridGain 9 Query Quota Test Harness

## Background

GridGain 9 introduced a feature that limits the amount of JVM heap memory SQL queries are allowed to consume — both a per-query cap and a per-node cap across all in-flight queries. Documentation: https://www.gridgain.com/docs/gridgain9/latest/administrators-guide/memory-management

The motivation is well-understood: interim SQL result sets live on the JVM heap (some data is also brought from off-heap into heap when it's read), and a runaway or pathological query can balloon that working set fast enough to choke the rest of the node — most importantly the key-value (K-V) subsystem that productionised workloads depend on for low-latency primary access. The quota feature is supposed to prevent that.

Our job here is to **build a test harness that proves the feature works as advertised** and gives us a reproducible demo to show stakeholders. We want hard evidence, not just docs trust.

## Goals — what the harness must demonstrate

Three distinct behaviours, in order of complexity:

1. **Per-query quota** — A single greedy SQL query whose interim memory exceeds the per-query cap must be rejected by GG9 with a clear, programmatically-matchable error. Not silently truncated, not OOM-killed, not turned into a successful but wrong result. The test must assert the error contract explicitly.

2. **Per-node quota** — When several moderate SQL queries run concurrently and their *combined* memory exceeds the node-wide cap, late arrivals must be rejected cleanly while in-flight queries either complete or fail without corrupting each other. The test must assert that the rejection error is distinct from the per-query case so an operator can tell them apart in logs.

3. **K-V isolation under runaway SQL** — The headline use case. With quotas in place, point K-V `get` / `put` operations must keep running with healthy tail latency *while* a runaway SQL workload is hammering the node. This is what the feature is really for, so it deserves a dedicated test that records latency every operation (use HdrHistogram or similar) and asserts on p99 / p99.9 thresholds, not just averages.

Each scenario should have a "control" path in the same test class — a small query that succeeds under the tight quota — so a green test demonstrates "the quota fired" rather than "the harness was broken".

## Tech stack & conventions

- **Language:** Java. The GG9 native client is Java and we want to assert on GG9-specific exception types, so other-language wrappers (Python, .NET) are not worth the impedance mismatch here.
- **Test framework:** JUnit 5 + AssertJ.
- **Orchestration:** Testcontainers-java. One GG9 docker container per test class, started/stopped automatically. No external `docker-compose up` step — tests must be runnable with just `./gradlew test` on a fresh checkout, fully hermetic.
- **GG9 access:** use the `IgniteClient.sql()` API directly. Don't bring JDBC into the picture — it adds an artifact dimension we don't need to validate.
- **Build:** Gradle with the Kotlin DSL, single module.
- **Java version:** 17 LTS is fine on the client side; we don't need 21 features.
- **No mocks of GG9.** Every test must run against a real container.
- **No metrics / observability stack** (no Prometheus, no Grafana). In-process HdrHistogram is enough for the K-V isolation test. If we need richer telemetry later, we'll add it then.

## Test phases

The plan is to ramp up scale incrementally so we catch problems early:

- **Phase 1 — single node, small data.** ~10 000 rows, ~10 MB raw, small heap (~512 MB). Quotas tight relative to the workload so behaviour is dramatic and tests run in seconds. This is the primary deliverable.
- **Phase 2 — single node, larger data.** ~1 000 000 rows, ~1 GB raw, larger heap (~4 GB). Same three scenarios, scaled up to confirm the quota mechanism handles GB-scale interim state, not just toy MB-scale. Tag these tests so they're opt-in — the default `./gradlew test` should stay fast.
- **Phase 3 — small cluster.** Optional / future. Don't implement now, but don't paint us into a single-node corner: the container wrapper should accept a node count so a future cluster scenario doesn't require a rewrite.

## Target environment

Docker on a local Mac (macOS / Docker Desktop / Apple silicon). That's the developer machine the harness has to run on first. Anything fancier (CI, cluster) is downstream concern.

A few things to watch on macOS:
- The Docker socket path matters. Recent Docker Desktop uses `~/.docker/run/docker.sock` rather than `/var/run/docker.sock`; the build should set `DOCKER_HOST` sensibly so Testcontainers finds the daemon without manual setup.
- Pick a recent stable Testcontainers (1.21+) — older 1.20.x doesn't always negotiate cleanly with current Docker Desktop builds.

## Licensing

GG9 requires a license to initialize a cluster. The license file is operator-supplied — **never check it into git**. The harness should:
- Read it from `gridgain-license.json` at the project root (configurable via system property for flexibility).
- Fail fast in `@BeforeAll` with an actionable error if the file is missing.
- Inline the license content into the cluster-init payload (the GG9 management API requires it there, not just as a mounted file).
- Redact the license from any logging — test output ends up in CI artifacts and shouldn't carry credentials.

`gridgain-license.json` must be in `.gitignore` from day one.

## Quality bar

- All tests run from a fresh checkout via `./gradlew test`, no manual steps beyond putting the license file in place.
- Test failures must be informative — not "expected: 0 but was: 1" but "K-V p99 under SQL pressure was 480 ms (threshold: 200 ms)".
- The harness should be small and obvious. A single support package with a thin Testcontainers wrapper, a REST client for the GG9 management API, a schema fixture, and a K-V workload helper. No abstractions that don't earn their keep.
- Document what you discover about GG9's actual behaviour (config key paths, exception codes, REST endpoints) in the README — these are the things that get lost between sessions.
