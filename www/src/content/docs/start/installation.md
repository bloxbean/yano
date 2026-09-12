---
title: "Install and package Yano"
description: "Choose a JVM distribution, native binary, source build, or embedded library."
sidebar:
  order: 3
---

## Choose a distribution

| Form | Requirements | Use it for |
| --- | --- | --- |
| JVM ZIP | JDK 25 | Node operation, dynamic plugins, optional DuckLake history |
| Native ZIP | Matching OS and architecture | Fast startup with retained core providers |
| Source checkout | JDK 25 and bundled Gradle wrapper | Contributions and unreleased source changes |
| Maven libraries | JDK 25, Maven or Gradle | In-process node and testkit integration |

Check [GitHub releases](https://github.com/bloxbean/yano/releases) for published assets. Documentation for a source revision is not a guarantee that a matching release has already been published.

## Build a JVM distribution

From the repository root:

```bash
./gradlew :app:yanoDistZip -PskipSigning=true
```

Find `yano-<version>.zip` in `app/build/distributions/`. Extract it, enter the extracted directory, and run:

```bash
./yano.sh start:devnet
```

The archive includes the launcher, JAR, configuration, genesis files, license, SBOM, and JVM plugin tooling.

## Build a native distribution

Use Oracle GraalVM 25.3 with `native-image`:

```bash
./gradlew :app:yanoNativeDistZip \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true
```

The output is `app/build/distributions/yano-native-<version>-<platform>.zip`. Extract and use its `yano.sh` launcher. Native images cannot dynamically load plugin JARs. Optional Yano X extensions and DuckLake history use the JVM distribution.

For container packaging and build-time API prefixes, see [distribution details](/reference/distributions/).
