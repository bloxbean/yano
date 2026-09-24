---
title: "Your first local node"
description: "Download Yano and start a local Cardano development network."
sidebar:
  order: 2
---

Start a local Cardano chain from a **ready-to-run release**. You do not need Git, Gradle, or a source checkout.

## 1. Download Yano

Open the [latest Yano release](https://github.com/bloxbean/yano/releases/latest) (or choose another from [Yano Releases](https://github.com/bloxbean/yano/releases)) and download:

- **JVM:** `yano-<version>.zip`, with Java 25 installed. Recommended if you also want to try app ledgers.
- **Native:** `yano-native-<version>-<platform>.zip`, matching your operating system and CPU. No Java installation needed.

Replace `<version>` with the selected release version as it appears in the asset names (the release tag without its leading `v`) and `<platform>` with the platform suffix shown in its asset list.

See [installation](/start/installation/) for platform-specific asset names and Windows commands.

## 2. Extract and start

Extract the whole ZIP, including its `config/` directory. For the JVM download on macOS or Linux:

```bash
VERSION="<version>" # Replace with the selected release version.
unzip "yano-${VERSION}.zip"
cd "yano-${VERSION}"
./yano.sh start:devnet
```

For a native download, enter its extracted `yano-native-<version>-<platform>` directory and run the same `./yano.sh start:devnet` command. The launcher selects the packaged binary automatically.

Keep this terminal running. The default REST port is `7070`; the node-to-node port is `13337`. The local devnet uses network magic `42`.

## 3. Check your chain

In another terminal:

```bash
curl -fsS http://localhost:7070/q/health/ready
curl -fsS http://localhost:7070/api/v1/node/tip
curl -fsS http://localhost:7070/api/v1/blocks/latest
curl -fsS http://localhost:7070/api/v1/epochs/latest/parameters
```

Repeat the tip query after a few seconds. Once startup completes, blocks should advance. Open [Swagger UI](http://localhost:7070/q/swagger-ui) to explore the API included in your release.

## What you just started

This is a local Cardano development chain backed by RocksDB. Its test currency has no public-network value. Keep devnet mutation endpoints on your development machine or a trusted test network.

If startup fails, check Java with `java -version` for a JVM installation, confirm that ports are free, and ensure you extracted the complete distribution. See [troubleshooting](/operate/troubleshooting/).

## Next steps

[Connect your favorite SDK](/develop/blockfrost/), [submit a transaction](/develop/transactions/), or [start an app ledger](/app-chains/quickstart/). Source builds are an optional [contributor workflow](/contribute/build-from-source/).
