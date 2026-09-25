# Download & install Yano

Choose and run a JVM, native, or Docker Compose Yano release.

Canonical URL: https://getyano.dev/start/installation/

Download the [latest Yano release](https://github.com/bloxbean/yano/releases/latest), or choose another from [Yano Releases](https://github.com/bloxbean/yano/releases). Extract the complete archive; keep the executable, launcher, and `config/` directory together.

## Choose your download

| Distribution | Download | Requirements |
| --- | --- | --- |
| **JVM — recommended for app ledgers** | `yano-<version>.zip` | Java 25 |
| Linux x64 | `yano-native-<version>-linux-x64.zip` | Linux on x64 |
| Linux arm64 | `yano-native-<version>-linux-arm64.zip` | Linux on arm64 |
| macOS arm64 | `yano-native-<version>-macos-arm64.zip` | Apple silicon Mac |
| Windows x64 | `yano-native-<version>-windows-x64.zip` | Windows on x64 |
| Docker Compose | `yano-docker-<version>.zip` | Docker Engine or Docker Desktop with Compose v2 |

Replace `<version>` with the selected release version as it appears in the asset names (the release tag without its leading `v`).

For the Docker ZIP, follow [Run with Docker Compose](/start/docker/). It contains
the launcher and configuration and pulls a versioned image; Java is included in
the JVM image. Some older releases omit this ZIP; use another listed distribution
or [build the Compose ZIP from source](/contribute/build-from-source/#docker-compose-zip-distribution).

For app ledger onboarding, **use the JVM distribution for now**. It is also the distribution to choose for JVM extensions. Native images cannot dynamically load plugin JARs.

## Start on macOS or Linux

For the JVM ZIP:

```bash
VERSION="<version>" # Replace with the selected release version.
unzip "yano-${VERSION}.zip"
cd "yano-${VERSION}"
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

For JVM app ledgers, use `java "-Dquarkus.profile=devnet,appchain" -jar yano.jar`.

## Releases and documentation versions

This site's advanced reference follows the current source line, which may be newer than the preview you downloaded. Use your release's bundled configuration and `/q/swagger-ui` as the reference for its available profiles, properties, and endpoints.

Use release-matched SDKs, plugins, and verifiers. Start preview releases with fresh node storage unless that release explicitly documents compatibility.

To modify Yano itself or use unreleased changes, see [build from source](/contribute/build-from-source/); building is not required for the download workflow.
