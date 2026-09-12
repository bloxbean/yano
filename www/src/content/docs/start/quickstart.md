---
title: "Your first local node"
description: "Build and start a local Cardano development network with Yano."
sidebar:
  order: 2
---

Start with a devnet: it produces local blocks without waiting for a public-network sync. You need **JDK 25** and Git. The repository includes the Gradle wrapper.

## 1. Build the application

```bash
git clone https://github.com/bloxbean/yano.git
cd yano
./gradlew :app:quarkusBuild -PskipSigning=true
```

These docs describe the `org.yanoproject` source line. Use a checkout or release containing that namespace. See [versions and upgrades](/operate/upgrades/) before mixing libraries and binaries.

## 2. Start the devnet

Run from `app/` so the launcher can find its packaged configuration and genesis files:

```bash
cd app
./start-devnet.sh
```

Keep this terminal running. The default REST port is `7070`; the node-to-node port is `13337`. The local devnet uses network magic `42`.

## 3. Check your chain

In another terminal:

```bash
curl -fsS http://localhost:7070/q/health/ready
curl -fsS http://localhost:7070/api/v1/node/tip
curl -fsS http://localhost:7070/api/v1/blocks/latest
curl -fsS http://localhost:7070/api/v1/epochs/latest/parameters
```

Repeat the tip query after a few seconds. Once startup completes, blocks should advance. Open [the local console](http://localhost:7070/ui/) to explore the node or [Swagger UI](http://localhost:7070/q/swagger-ui) to inspect API schemas.

## What you just started

This is a local Cardano development chain backed by RocksDB. Its test currency has no public-network value. Keep devnet mutation endpoints on your development machine or a trusted test network.

If startup fails, check Java with `java -version`, confirm that ports are free, and run the launcher from `app/`. See [troubleshooting](/operate/troubleshooting/) for sync and storage issues.

## Next steps

[Submit a transaction](/develop/transactions/), [write an integration test](/develop/java-testkit/), or [start an app chain](/app-chains/quickstart/).
