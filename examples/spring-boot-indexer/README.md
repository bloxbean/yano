# Yano Spring Boot indexer

A Spring Boot application that embeds a Yano node as a library and indexes one slice of
Cardano data into its own database, the way a yaci-store scoped indexer does. It stores
transaction metadata under one label (CIP-20 messages, label `674`, by default) in H2 or
PostgreSQL and serves it over REST.

The step-by-step guide is on the documentation site:
[Yano as a library](https://getyano.dev/develop/library/).

## Run

Java 25 is required. From this directory:

```bash
../../gradlew bootRun
```

The application syncs preprod from a public relay into `./data/chainstate` and writes the
index to `./data/indexer.mv.db`. Then:

```bash
curl -s localhost:8080/api/status
curl -s 'localhost:8080/api/metadata?limit=5'
```

Use PostgreSQL instead of H2 with the `postgres` profile (database `yano_indexer` must exist):

```bash
../../gradlew bootRun --args='--spring.profiles.active=postgres'
```

## What it shows

| File | Purpose |
|------|---------|
| `YanoConfiguration` | Builds the `Yano` bean from a network preset and `application.yml`. |
| `YanoNodeLifecycle` | Registers listeners, catches up from Yano's block store, starts and stops the node. |
| `MetadataIndexer` | `@DomainEventListener` methods for block and rollback events. |
| `MetadataStore` | One JDBC transaction per block, with a cursor row for restart recovery. |
| `IndexerController` | REST endpoints over the index and the node status. |

The build uses the published `org.yanoproject` artifacts (`yanoVersion` in
`gradle.properties`). To try unreleased changes, run `./gradlew publishToMavenLocal` at the
repository root, add `mavenLocal()` to the repositories and set `yanoVersion` to the version
it publishes.

To copy this example out of the repository, run `gradle wrapper` in the copy to add a Gradle
wrapper.
