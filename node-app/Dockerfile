# ── Stage 1: Build ─────────────────────────────────────────────────────────────
# Clones the bloxbean/yano repo and builds the node-app uber-jar.
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

RUN apk add --no-cache git

RUN git clone --depth 1 --branch main https://github.com/bloxbean/yano.git .

# Build only the node-app module — produces build/yano.jar (uber-jar)
RUN ./gradlew :node-app:quarkusBuild -x test --no-daemon

# ── Stage 2: Runtime ───────────────────────────────────────────────────────────
# Must use a glibc-based image — RocksDB JNI requires libstdc++.so.6 which is
# absent on Alpine (musl libc).
FROM eclipse-temurin:25-jre-jammy AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Uber-jar (self-contained, no classpath needed)
COPY --from=builder /build/node-app/build/yano.jar yano.jar

# Genesis files and network configs (devnet profile reads these from ./config/)
COPY --from=builder /build/node-app/config ./config

# 8080  — Blockfrost-compatible REST API + Swagger UI
# 13337 — Cardano N2N server
EXPOSE 8080 13337

# Chain state is persisted here; mount a named volume to survive restarts
VOLUME /app/chainstate

CMD ["java", "-Dquarkus.profile=devnet", "-Dyaci.node.tx-evaluation.enabled=true", "-Dyaci.node.block-producer.script-evaluator=scalus", "-jar", "yano.jar"]
