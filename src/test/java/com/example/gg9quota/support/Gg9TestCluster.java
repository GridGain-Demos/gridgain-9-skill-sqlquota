package com.example.gg9quota.support;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives a single Gg9Container through its post-start init flow: discover the node name,
 * POST cluster init (with license + optional cluster-level HOCON), wait for active state,
 * and (optionally) PATCH node-level config for keys that don't live at cluster scope.
 *
 * <p>GG9 9.1.22 splits the SQL quota knobs across two config trees:
 * {@code statementMemoryQuota}/{@code offloadingEnabled} are cluster-scoped (set via the init
 * payload's {@code clusterConfiguration} HOCON), while {@code nodeMemoryQuota} is node-scoped
 * (must be PATCHed onto {@code /management/v1/configuration/node} after init).</p>
 */
public final class Gg9TestCluster {

    private static final Logger log = LoggerFactory.getLogger(Gg9TestCluster.class);

    private static final Pattern NODE_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    /** SQL quota knobs across both cluster and node scopes. */
    public static final class QuotaConfig {
        /** Cluster scope: {@code ignite.sql.statementMemoryQuota}. */
        public final String statementMemoryQuota;
        /** Cluster scope: {@code ignite.sql.offloadingEnabled}. Keep {@code false} or quotas don't throw. */
        public final boolean offloadingEnabled;
        /** Node scope: {@code ignite.sql.nodeMemoryQuota}. {@code null} = leave at default ("60%"). */
        public final String nodeMemoryQuota;

        public QuotaConfig(String statementMemoryQuota, boolean offloadingEnabled, String nodeMemoryQuota) {
            this.statementMemoryQuota = statementMemoryQuota;
            this.offloadingEnabled = offloadingEnabled;
            this.nodeMemoryQuota = nodeMemoryQuota;
        }

        /** Tighten the per-statement cap; leave node quota at default. */
        public static QuotaConfig tightStatement(String size) {
            return new QuotaConfig(size, false, null);
        }

        /** Tight node-level cap with a loose statement cap so individual queries succeed. */
        public static QuotaConfig tightNode(String nodeSize, String looseStatementSize) {
            return new QuotaConfig(looseStatementSize, false, nodeSize);
        }
    }

    private final Gg9Container container;
    private final ClusterInitClient rest;

    public Gg9TestCluster(Gg9Container container) {
        this.container = container;
        this.rest = new ClusterInitClient(container.restBaseUrl());
    }

    public ClusterInitClient rest() {
        return rest;
    }

    public Gg9Container container() {
        return container;
    }

    public void initialize(QuotaConfig quota) throws Exception {
        runInit(buildClusterConfigHocon(quota));
        if (quota.nodeMemoryQuota != null) {
            applyNodeMemoryQuota(quota.nodeMemoryQuota);
        }
    }

    /** Init with no clusterConfiguration overrides — used by the smoke spike. */
    public void initializeWithoutClusterConfig() throws Exception {
        runInit(null);
    }

    private void applyNodeMemoryQuota(String size) throws Exception {
        String hocon = "ignite.sql.nodeMemoryQuota = \"" + size + "\"";
        HttpResponse<String> patchResp = rest.patchNodeConfig(hocon);
        if (patchResp.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                "PATCH node config failed: " + patchResp.statusCode() + " " + patchResp.body());
        }

        // Confirm the change actually landed.
        HttpResponse<String> nodeCfg = rest.rawGet("/management/v1/configuration/node");
        if (nodeCfg.statusCode() != 200) {
            throw new IllegalStateException(
                "GET node config failed: " + nodeCfg.statusCode() + " " + nodeCfg.body());
        }
        String expected = "\"nodeMemoryQuota\":\"" + size + "\"";
        if (!nodeCfg.body().contains(expected)) {
            throw new IllegalStateException(
                "PATCH appeared to succeed but nodeMemoryQuota=" + size + " not reflected. Body: " + nodeCfg.body());
        }
        log.info("node nodeMemoryQuota set to \"{}\"", size);
    }

    private void runInit(String hoconOrNull) throws Exception {
        String nodeName = discoverNodeName();
        log.info("Discovered node name: {}", nodeName);

        String license = LicenseLoader.loadLicenseJson();
        String payload = buildInitPayload(nodeName, hoconOrNull, license);

        HttpResponse<String> initResp = rest.initCluster(payload);
        if (initResp.statusCode() / 100 != 2) {
            throw new IllegalStateException("cluster/init failed: " + initResp.statusCode() + " " + initResp.body());
        }

        rest.waitForClusterActive();
    }

    private String discoverNodeName() throws Exception {
        HttpResponse<String> resp = rest.getNodeState();
        String body = resp.body();
        if (resp.statusCode() != 200 || body == null) {
            throw new IllegalStateException("node/state failed: " + resp.statusCode() + " " + body);
        }
        Matcher m = NODE_NAME.matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("Could not parse node name from /node/state: " + body);
        }
        return m.group(1);
    }

    private String buildClusterConfigHocon(QuotaConfig q) {
        return String.join("\n",
            "ignite.sql.offloadingEnabled = " + q.offloadingEnabled,
            "ignite.sql.statementMemoryQuota = \"" + q.statementMemoryQuota + "\"");
    }

    private String buildInitPayload(String nodeName, String hoconOrNull, String licenseJson) {
        String escapedLicense = jsonEscape(licenseJson);
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"metaStorageNodes\":[\"").append(nodeName).append("\"],");
        sb.append("\"cmgNodes\":[\"").append(nodeName).append("\"],");
        sb.append("\"clusterName\":\"test\",");
        sb.append("\"license\":\"").append(escapedLicense).append("\"");
        if (hoconOrNull != null) {
            sb.append(",\"clusterConfiguration\":\"").append(jsonEscape(hoconOrNull)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
