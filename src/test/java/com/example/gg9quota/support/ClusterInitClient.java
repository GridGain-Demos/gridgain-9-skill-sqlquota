package com.example.gg9quota.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin REST client for GG9's /management/v1/cluster/* endpoints. Used by the smoke spike to
 * confirm the actual init contract (inline license string vs file mount, HOCON formatting, etc.).
 * Falls back to a docker exec CLI init if the REST path rejects the license.
 */
public final class ClusterInitClient {

    private static final Logger log = LoggerFactory.getLogger(ClusterInitClient.class);

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private final String baseUrl;

    public ClusterInitClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public HttpResponse<String> getNodeState() throws Exception {
        return get("/management/v1/node/state");
    }

    public HttpResponse<String> getClusterState() throws Exception {
        return get("/management/v1/cluster/state");
    }

    public HttpResponse<String> getClusterConfig() throws Exception {
        return get("/management/v1/cluster/configuration");
    }

    public HttpResponse<String> initCluster(String jsonPayload) throws Exception {
        log.info("POST /management/v1/cluster/init payload:\n{}", redactLicense(jsonPayload));
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/management/v1/cluster/init"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("cluster/init -> {} body={}", resp.statusCode(), resp.body());
        return resp;
    }

    public void waitForClusterActive() throws Exception {
        Instant deadline = Instant.now().plus(POLL_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> resp = getClusterState();
            String body = resp.body();
            if (resp.statusCode() == 200 && body != null && body.contains("clusterTag")) {
                log.info("cluster/state -> {}", body);
                return;
            }
            log.debug("cluster/state not ready: {} {}", resp.statusCode(), body);
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException("Cluster did not become active within " + POLL_TIMEOUT);
    }

    private static String redactLicense(String jsonPayload) {
        return jsonPayload.replaceAll("\"license\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"", "\"license\":\"<redacted>\"");
    }

    public HttpResponse<String> rawGet(String path) throws Exception {
        return get(path);
    }

    /**
     * PATCH the node-scoped configuration tree at {@code /management/v1/configuration/node}.
     * GG9 accepts HOCON as the body.
     */
    public HttpResponse<String> patchNodeConfig(String hoconDiff) throws Exception {
        log.info("PATCH /management/v1/configuration/node body: {}", hoconDiff);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/management/v1/configuration/node"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "text/plain")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(hoconDiff))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("PATCH node config -> {} body={}", resp.statusCode(), resp.body());
        return resp;
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
