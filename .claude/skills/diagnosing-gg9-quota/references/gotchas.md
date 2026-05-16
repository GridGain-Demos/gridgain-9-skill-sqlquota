# GG9 quota gotchas — silent failure modes

A list of the ways the SQL memory quota feature silently doesn't do what an operator thinks it's doing. Roughly ordered by how often it's the answer when "quotas aren't firing".

## 1. `offloadingEnabled` flips quotas into spill mode

**Symptom.** Queries that should hit `GG-MEMQUOTA-3` instead succeed and consume large amounts of disk space (or take dramatically longer than expected). No quota exception in logs.

**Cause.** `ignite.sql.offloadingEnabled` controls what GG9 does when a SQL operator's working set exceeds the per-statement cap. Two paths:

- `false` (default in 9.1.22) — throw `IgniteException` with code `GG-MEMQUOTA-3`. This is what the harness's quota tests rely on.
- `true` — spill the over-quota state to disk (under `ignite.sql.offloadingDataDir` in node config). No exception. Query completes; latency and disk I/O degrade.

The default of `false` makes the demo work out-of-the-box, but the customer may have enabled offloading in pursuit of "no failures", not realising it nullifies the quota's protective effect.

**Detection.**
```
curl -s http://<host>:10300/management/v1/configuration/cluster | jq .ignite.sql.offloadingEnabled
```

**Fix.** PATCH cluster config to set it false:
```
curl -X PATCH http://<host>:10300/management/v1/configuration/cluster \
    -H 'Content-Type: text/plain' \
    -d 'ignite.sql.offloadingEnabled = false'
```
Takes effect immediately; no node restart needed.

## 2. Wrong config tree (cluster vs node)

**Symptom.** The customer set `nodeMemoryQuota` (or `statementMemoryQuota`) and confirms the value via `curl`, but the live cluster shows defaults. Or: customer set the cap they wanted, but a *different* cap is firing.

**Cause.** The two quotas live in different trees:

- `statementMemoryQuota` + `offloadingEnabled` → **cluster** config (`/management/v1/configuration/cluster`).
- `nodeMemoryQuota` → **node** config (`/management/v1/configuration/node`).

Writing the right knob to the wrong endpoint silently no-ops or sets an unrelated key.

**Detection.** Run BOTH `GET`s. If the customer's intended value appears in neither tree, they wrote to the wrong place.

**Fix.** Re-issue the PATCH against the correct endpoint.

## 3. Quota values expressed as percentages, not bytes

**Symptom.** Customer set `statementMemoryQuota` to `100M`. They believe each query has 100 MB of headroom. Quota fires at a different threshold.

**Cause.** GG9 accepts both absolute sizes (`100M`, `2G`) and percentages of heap (`60%`, `100%`). The defaults are percentages. If the customer thinks "100%" means "100 megabytes" — that's not it; it means the whole heap.

**Detection.** Look for `"100%"`, `"60%"`, etc. in the live config. Confirm the customer's understanding.

**Fix.** Express in absolute bytes (`"2G"`) for clarity. Note that GG9 evaluates percentages against `JVM_MAX_MEM`, not against any post-startup runtime heap measurement.

## 4. Setting node config before cluster init completes

**Symptom.** PATCH to `/management/v1/configuration/node` returns 200, but the new value doesn't take effect. Subsequent GET shows the previous value.

**Cause.** PATCHes against a cluster that hasn't fully initialised (no `clusterTag` in `/cluster/state`) silently no-op. This is rare in production but easy to hit in scripts.

**Detection.** Always poll `/cluster/state` until `clusterTag` is present before any other config writes. See `ClusterInitClient.waitForClusterActive()` in this harness.

**Fix.** Same pattern: poll-then-write.

## 5. License field omitted from init → cluster never initialises

**Symptom.** `/cluster/init` returns 400 with `"License must not be empty."`. Cluster sits in `STARTING` forever.

**Cause.** GG9 requires the license to be included in the init payload as a JSON string. Mounting `gridgain-license.json` into `/opt/gridgain/etc/` is **not** sufficient — the file isn't auto-discovered.

**Fix.** Embed the license content as a JSON-escaped string in the init payload's `license` field. See `Gg9TestCluster.buildInitPayload()` for the pattern.

## 6. Default `nodeMemoryQuota` is large (60% of heap)

**Symptom.** Customer expects `GG-MEMQUOTA-4` to fire under modest concurrency. It doesn't, because the default cap is huge relative to typical workloads.

**Detection.** GET `/management/v1/configuration/node`. If `nodeMemoryQuota` is still `"60%"`, the customer has not explicitly tightened it.

**Fix.** Tighten to a fraction of heap that bounds the worst-case sum of concurrent query memory. Rule of thumb in `tuning.md`.

## 7. Default `statementMemoryQuota` is `"100%"` (i.e. unlimited)

**Symptom.** Same as above but per-query: greedy single queries succeed when they should be rejected.

**Detection.** GET cluster config; check `statementMemoryQuota`. If `"100%"`, no per-statement cap is in effect.

**Fix.** Tighten via cluster init's `clusterConfiguration` HOCON, or PATCH the cluster config endpoint.

## 8. JDBC vs `IgniteClient.sql()` error wrapping

**Symptom.** Customer reports a generic SQLException rather than `IgniteException` with `codeAsString="GG-MEMQUOTA-3"`. They can't tell which quota fired.

**Cause.** The JDBC driver wraps GG9 exceptions in `java.sql.SQLException`. The original error code is preserved in the cause chain but you have to dig for it.

**Fix.** Either switch to `IgniteClient.sql()` (cleaner exception surface, the path this harness uses) or, if JDBC must stay, walk the cause chain looking for `IgniteException` and inspect its `codeAsString()`.

## 9. Test-time vs production heap size mismatch

**Symptom.** Customer says quota fires in their staging environment but not production. Or vice versa.

**Cause.** Quotas expressed as percentages (`"60%"`) scale with `JVM_MAX_MEM`. A 60% cap on a 2 GB heap is 1.2 GB; on a 16 GB heap it's 9.6 GB. The same workload may pass under one configuration and fail under another simply because the headroom moved.

**Fix.** When demoing or reproducing, always pin both `JVM_MAX_MEM` and the quota to absolute values. Don't rely on percentage defaults.

## 10. Quota config applied after the test/queries have already started

**Symptom.** Customer sets quotas, then runs a smoke test that succeeds. They conclude the quotas don't work.

**Cause.** Some operations cache plans / pre-prepare statements. Long-lived clients may execute a query against the pre-quota plan even after the cluster config changes. In practice this is rare for ad-hoc queries but can bite long-running services.

**Fix.** After PATCHing config, force a fresh client connection or wait for the configured plan cache TTL (default `1800` seconds in `ignite.sql.planner.planCacheExpiresAfterSeconds`). The harness's per-test container model side-steps this entirely.
