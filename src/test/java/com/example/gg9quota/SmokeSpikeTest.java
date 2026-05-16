package com.example.gg9quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gg9quota.support.Gg9Container;
import com.example.gg9quota.support.Gg9TestCluster;
import com.example.gg9quota.support.LicenseLoader;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cheap pre-flight checks that catch the dumbest reasons the real tests would fail:
 * license file missing, image not pullable, REST endpoint wrong. Runs in ~5 seconds.
 */
@Testcontainers
final class SmokeSpikeTest {

    @Container
    static final Gg9Container GG9 = new Gg9Container();

    @Test
    void license_file_is_present_locally() {
        assertThat(LicenseLoader.loadLicenseJson())
            .as("license file at " + LicenseLoader.resolvePath() + " must be non-empty")
            .isNotBlank();
    }

    @Test
    void node_reports_state_via_rest() throws Exception {
        HttpResponse<String> resp = new Gg9TestCluster(GG9).rest().getNodeState();
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"name\"");
    }
}
