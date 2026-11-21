# Yano BOM

A Gradle `java-platform` (Maven BOM) that aligns the versions of the supported Yano host artifacts.
Import it once, then declare Yano modules without versions.

## Artifact

`org.yanoproject:yano-bom` (packaging `pom`). The Gradle project path is `:yano-bom` (directory `bom/`).

## Key contents

Version constraints for:

- APIs: `yano-node-api`, `yano-cardano-api`, `yano-host-api` (own version train), `yano-plugin-catalog`
- Node: `yano-runtime`, `yano-consensus`, `yano-p2p`, `yano-ledger-rules`, `yano-ledger-state`
- Optional services: `yano-tx-services`, `yano-bootstrap-providers`, `yano-script-evaluators`,
  `yano-scalus-bridge`, `yano-archive-api`, `yano-archive-core`, `yano-archive-store-ducklake`
- Quarkus host: `yano-app-core`
- Devnet and test support: `yano-devnet-toolkit`, `yano-testkit`, `yano-testkit-ccl`, `yano-app-e2e-testkit`

Unpublished projects (`app`, `console-ui`, `ledger-conformance`, examples) are not in it.

```gradle
dependencies {
    implementation platform("org.yanoproject:yano-bom:$yanoVersion")
    implementation "org.yanoproject:yano-runtime"
    implementation "org.yanoproject:yano-tx-services"
}
```

## Dependencies and boundaries

- Constraints only; it adds no dependencies to a consumer.
- Lists Yano artifacts only; the guard below scans the generated BOM so that it never names an artifact of
  the downstream application-ledger framework
  ([ADR-064](../adr/) D11).
- The root `org.yanoproject:yano` starter POM is a separate, dependency-only artifact; see the root
  `build.gradle`.

## Build and test

```bash
# writes bom/build/publications/mavenJava/pom-default.xml
./gradlew :yano-bom:generatePomFileForMavenJavaPublication -PskipSigning=true
./gradlew :yano-bom:publishToMavenLocal -PskipSigning=true
./gradlew verifyNoYanoXDependency -PskipSigning=true
```

## Notes

- The constraint list in `build.gradle` is maintained by hand: add a newly published host artifact there.
