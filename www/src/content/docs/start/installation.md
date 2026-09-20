---
title: "Download & install Yano"
description: "Choose and run a JVM or platform-specific Yano release."
sidebar:
  order: 3
---

Download a ready-to-run ZIP from [Yano v0.1.0-pre15](https://github.com/bloxbean/yano/releases/tag/v0.1.0-pre15). Extract the complete archive; keep the executable, launcher, and `config/` directory together.

## Choose your download

| Distribution | Download | Requirements |
| --- | --- | --- |
| **JVM — recommended for app chains** | [yano-0.1.0-pre15.zip](https://github.com/bloxbean/yano/releases/download/v0.1.0-pre15/yano-0.1.0-pre15.zip) | Java 25 |
| Linux x64 | [Native ZIP](https://github.com/bloxbean/yano/releases/download/v0.1.0-pre15/yano-native-0.1.0-pre15-linux-x64.zip) | Linux on x64 |
| Linux arm64 | [Native ZIP](https://github.com/bloxbean/yano/releases/download/v0.1.0-pre15/yano-native-0.1.0-pre15-linux-arm64.zip) | Linux on arm64 |
| macOS arm64 | [Native ZIP](https://github.com/bloxbean/yano/releases/download/v0.1.0-pre15/yano-native-0.1.0-pre15-macos-arm64.zip) | Apple silicon Mac |
| Windows x64 | [Native ZIP](https://github.com/bloxbean/yano/releases/download/v0.1.0-pre15/yano-native-0.1.0-pre15-windows-x64.zip) | Windows on x64 |

For app-chain onboarding, **use the JVM distribution for now**. It is also the distribution to choose for JVM extensions. Native images cannot dynamically load plugin JARs.

## Start on macOS or Linux

For the JVM ZIP:

```bash
unzip yano-0.1.0-pre15.zip
cd yano-0.1.0-pre15
./yano.sh start:devnet
```

For a native ZIP, enter the corresponding extracted directory and use the same launcher. To connect to a public network instead:

```bash
./yano.sh start:preprod
```

Use separate storage for different networks. The launcher runs with configuration from the extracted distribution. See [network configuration](/node/networks/) before changing networks or peers.

## Start on Windows

Extract the ZIP with Explorer or PowerShell, then open PowerShell in the extracted directory. Native distribution:

```powershell
.\yano.exe -Dquarkus.profile=devnet -Dyano.block-producer.script-evaluator=scalus
```

JVM distribution, with Java 25 installed:

```powershell
java -Dquarkus.profile=devnet -jar yano.jar
```

For JVM app chains, use `java "-Dquarkus.profile=devnet,appchain" -jar yano.jar`.

## Releases and documentation versions

The download links above select **pre15**, the newest published preview. This site's advanced reference follows the current source line, which may be newer. Resource profiles described in [choose a runtime profile](/start/runtime-profiles/) were added after pre15; use them only when the matching files exist in your extracted `config/` directory. Use your release's bundled configuration and `/q/swagger-ui` as the reference for its available endpoints.

Use release-matched SDKs, plugins, and verifiers. Start preview releases with fresh node storage unless that release explicitly documents compatibility.

You can browse [all releases](https://github.com/bloxbean/yano/releases) for later artifacts. To modify Yano itself or use unreleased changes, see [build from source](/contribute/build-from-source/); building is not required for the download workflow.
