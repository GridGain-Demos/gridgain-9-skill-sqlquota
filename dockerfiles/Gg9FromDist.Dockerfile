# Builds a runnable GG9 image from a locally-extracted dist tree.
#
# Usage:
#   docker build \
#     -f dockerfiles/Gg9FromDist.Dockerfile \
#     -t gg9-local:9.1.10 \
#     --build-arg DIST_PARENT=gridgain9-db-9.1.10 \
#     --build-context dist=/Users/davidbrown/Code/gg9/9.1.10 \
#     .
#
# The DIST_PARENT arg is the top-level directory name inside the unzipped tree
# (e.g. "gridgain9-db-9.1.10" — the zip stores everything under that).
#
# We don't run as a non-root user; the dist's launcher script just `cd`s to GRIDGAIN_HOME
# and execs java. Heap is overridden at runtime via JVM_MIN_MEM / JVM_MAX_MEM env vars.

FROM eclipse-temurin:21-jdk

ARG DIST_PARENT

WORKDIR /opt/gridgain
COPY --from=dist /${DIST_PARENT}/ /opt/gridgain/

# vars.env hard-codes JVM_MAX_MEM/JVM_MIN_MEM to 16g, which the launcher then sources
# unconditionally. Patch it to fall back to those defaults only when env doesn't already
# provide values — so docker run / Testcontainers can pass smaller heap sizes.
RUN sed -i -E \
    -e 's|^JVM_MAX_MEM="[^"]*"|JVM_MAX_MEM="${JVM_MAX_MEM:-16g}"|' \
    -e 's|^JVM_MIN_MEM="[^"]*"|JVM_MIN_MEM="${JVM_MIN_MEM:-16g}"|' \
    etc/vars.env

# These match what the official gridgain/gridgain9 image exposes.
EXPOSE 10300 10800 3344

# Override heap from the dist's 16g default — Testcontainers sets the right value per test
# via withEnv, but if someone runs the image directly, 512m is a sane local default.
ENV JVM_MAX_MEM=512m
ENV JVM_MIN_MEM=512m

ENTRYPOINT ["bin/gridgain9db"]
