package com.example.gg9quota.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LicenseLoader {

    public static final String LICENSE_PATH_PROPERTY = "gg9.license.path";
    public static final String DEFAULT_LICENSE_PATH = "gridgain-license.json";

    private LicenseLoader() {}

    public static String loadLicenseJson() {
        Path path = resolvePath();
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                "GridGain license file not found at " + path.toAbsolutePath()
                    + ". Place gridgain-license.json at the project root, or override with -D"
                    + LICENSE_PATH_PROPERTY + "=<path>.");
        }
        try {
            String contents = Files.readString(path).trim();
            if (contents.isEmpty()) {
                throw new IllegalStateException("License file is empty: " + path.toAbsolutePath());
            }
            return contents;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read license file at " + path.toAbsolutePath(), e);
        }
    }

    public static Path resolvePath() {
        String configured = System.getProperty(LICENSE_PATH_PROPERTY, DEFAULT_LICENSE_PATH);
        return Paths.get(configured).toAbsolutePath();
    }
}
