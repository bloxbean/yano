# Runtime base image: Amazon Corretto 25 LTS (GPLv2 + Classpath Exception) on Amazon Linux 2023.
# 25.0.5.1 fixes CVE-2026-114938 ("Java4Shell"); Temurin has no fixed 25 build yet.
# Pinned by multi-arch index digest (linux/amd64 + linux/arm64). release-docker.yml passes the same value.
ARG JVM_BASE_IMAGE=amazoncorretto:25.0.5-al2023-headless@sha256:d43cc2b87faf3e80d04659d315dd9f1dba2c1176cc9b48d9daed285856ac056c
FROM ${JVM_BASE_IMAGE}

ARG YANO_UID=10001
ARG YANO_GID=10001

# The base already has ca-certificates and curl (healthcheck); entrypoint.sh needs find (findutils).
RUN dnf install -y findutils \
    && dnf clean all \
    && rm -rf /var/cache/dnf \
    && mkdir -p /app/chainstate /app/logs /app/plugins

WORKDIR /app

COPY yano/ /app/
COPY entrypoint.sh /entrypoint.sh

RUN chmod 0755 /entrypoint.sh \
    && mkdir -p /app/default-config \
    && cp -a /app/config/network /app/default-config/network \
    && chown -R "${YANO_UID}:${YANO_GID}" /app /entrypoint.sh

USER ${YANO_UID}:${YANO_GID}

ENV YANO_PROFILE=preprod \
    HOME=/app \
    QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_LOG_FILE_ENABLED=true \
    QUARKUS_LOG_FILE_PATH=/app/logs/yano.log \
    YANO_STORAGE_PATH=/app/chainstate \
    YANO_PLUGINS_DIRECTORY=/app/plugins

EXPOSE 7070 13337
# Compose mounts chainstate below /app/data; declaring it would leave an unused anonymous volume.
VOLUME ["/app/logs", "/app/plugins"]

HEALTHCHECK --interval=20s --timeout=5s --retries=12 \
    CMD curl -fsS http://localhost:7070/q/health/ready || exit 1

ENTRYPOINT ["/entrypoint.sh"]
