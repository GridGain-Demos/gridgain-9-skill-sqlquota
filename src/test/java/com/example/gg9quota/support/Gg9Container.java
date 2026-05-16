package com.example.gg9quota.support;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public final class Gg9Container extends GenericContainer<Gg9Container> {

    public static final int REST_PORT = 10300;
    public static final int CLIENT_PORT = 10800;

    private static final String DEFAULT_IMAGE = "gridgain/gridgain9:9.1.22";
    private static final String LICENSE_PATH_IN_CONTAINER = "/opt/gridgain/etc/gridgain-license.json";

    public Gg9Container() {
        this(DEFAULT_IMAGE);
    }

    public Gg9Container(String image) {
        super(DockerImageName.parse(image));
        withExposedPorts(REST_PORT, CLIENT_PORT);
        withEnv("JVM_MIN_MEM", "512m");
        withEnv("JVM_MAX_MEM", "512m");
        withCopyFileToContainer(
            MountableFile.forHostPath(LicenseLoader.resolvePath()),
            LICENSE_PATH_IN_CONTAINER);
        waitingFor(Wait.forHttp("/management/v1/node/state")
            .forPort(REST_PORT)
            .forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(2)));
    }

    public Gg9Container withHeap(String size) {
        withEnv("JVM_MIN_MEM", size);
        withEnv("JVM_MAX_MEM", size);
        return this;
    }

    public String restBaseUrl() {
        return "http://" + getHost() + ":" + getMappedPort(REST_PORT);
    }

    public String clientHost() {
        return getHost();
    }

    public int clientPort() {
        return getMappedPort(CLIENT_PORT);
    }

    public String licensePathInContainer() {
        return LICENSE_PATH_IN_CONTAINER;
    }
}
