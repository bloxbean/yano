FROM ubuntu:24.04

ARG YANO_UID=10001
ARG YANO_GID=10001

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl libstdc++6 zlib1g \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/chainstate /app/logs

WORKDIR /app

COPY yano/ /app/
COPY entrypoint.sh /entrypoint.sh

RUN chmod 0755 /entrypoint.sh /app/yano \
    && mkdir -p /app/default-config \
    && cp -a /app/config/network /app/default-config/network \
    && chown -R "${YANO_UID}:${YANO_GID}" /app /entrypoint.sh

USER ${YANO_UID}:${YANO_GID}

ENV YANO_PROFILE=preprod \
    HOME=/app \
    QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_LOG_FILE_ENABLED=true \
    QUARKUS_LOG_FILE_PATH=/app/logs/yano.log \
    YANO_STORAGE_PATH=/app/chainstate

EXPOSE 7070 13337
VOLUME ["/app/chainstate", "/app/logs"]

HEALTHCHECK --interval=20s --timeout=5s --retries=12 \
    CMD curl -fsS http://localhost:7070/q/health/ready || exit 1

ENTRYPOINT ["/entrypoint.sh"]
