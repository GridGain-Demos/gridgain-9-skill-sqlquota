plugins {
    `java-library`
}

group = "com.example.gg9quota"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    maven {
        name = "GridGainExternal"
        url = uri("https://www.gridgainsystems.com/nexus/content/repositories/external")
    }
}

// --- Versions ---
// The ignite-client artifact tracks the GG9 server version: keep this in lockstep with
// the docker image tag set in Gg9Container.
val igniteClientVersion = "9.1.22"
val testcontainersVersion = "1.21.4"
val junitVersion = "5.11.3"

dependencies {
    testImplementation("org.gridgain:ignite-client:$igniteClientVersion")

    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.hdrhistogram:HdrHistogram:2.2.2")

    testImplementation("ch.qos.logback:logback-classic:1.5.12")
    testImplementation("org.slf4j:slf4j-api:2.0.16")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        // Tag handling (in priority order):
        //   -PincludeTags=scale     run ONLY those tags (e.g. just the heavy scale suite)
        //   -PexcludeTags=perf      run everything except those tags
        //   (no flags)              default: exclude @Tag("scale") so the heavy Phase 2 suite is opt-in
        val include = project.findProperty("includeTags")?.toString()
        val exclude = project.findProperty("excludeTags")?.toString()
        when {
            include != null -> includeTags(*include.split(",").toTypedArray())
            exclude != null -> excludeTags(*exclude.split(",").toTypedArray())
            else -> excludeTags("scale")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    // Forward the license path so tests can override the default ./gridgain-license.json
    systemProperty("gg9.license.path", System.getProperty("gg9.license.path", "gridgain-license.json"))

    // Help Testcontainers find Docker Desktop's socket on macOS when DOCKER_HOST isn't set.
    // Modern Docker Desktop uses ~/.docker/run/docker.sock; Testcontainers' default
    // /var/run/docker.sock no longer works without an explicit symlink.
    if (System.getenv("DOCKER_HOST") == null) {
        val home = System.getProperty("user.home")
        val candidate = file("$home/.docker/run/docker.sock")
        if (candidate.exists()) {
            environment("DOCKER_HOST", "unix://${candidate.absolutePath}")
        }
    }
}
