# Optional First-Party App-Chain Connectors

Kafka, S3-compatible storage, IPFS/Kubo, and Cardano payments are maintained
Yano integrations, but they are not present in the stock distribution. They
are `FIRST_PARTY_OPTIONAL`: selecting their capability creates an artifact and
configuration requirement; it does not install a plugin or provision an
external service.

## Packaging matrix

| Integration | Capabilities | JVM bundle task | External ownership |
|---|---|---|---|
| Kafka | `executor:kafka`, `sink:kafka` | `:appchain-kafka:shadowJar` | Brokers, TLS/SASL identity, credentials, topic ACLs and retention |
| S3-compatible object storage | `executor:objectstore-s3` | `:appchain-objectstore-s3:shadowJar` | Versioned buckets, create-only policy, credentials, encryption and retention |
| IPFS/Kubo | `executor:ipfs` | `:appchain-ipfs:shadowJar` | Kubo API identity, authentication, pin durability and replication |
| Cardano payments | `executor:cardano-payment` | `:appchain-effects-cardano:shadowJar` | Funded payer, signing-key custody, backend trust, caps and reconciliation |

Build one JVM plugin from the repository root, for example:

```bash
./gradlew :appchain-kafka:shadowJar
```

Copy only the resulting `*-bundle.jar` into the plugin directory of every node
that owns that sink or executor. The default directory is `plugins/`; an
operator may override it with `yaci.plugins.directory`. Keep the plugin version
aligned with the Yano release, and run `doctor` before starting:

```bash
cp appchain/extensions/appchain-kafka/build/libs/*-bundle.jar /opt/yano/plugins/
./yano.sh appchain doctor my-project --distribution /opt/yano
```

Do not copy a thin build-time JAR into the JVM plugin directory. Conversely, a
native executable cannot load a bundle JAR after it is built. Include the
first-party providers before native-image analysis:

```bash
./gradlew :app:quarkusBuild \
  -PincludeFirstPartyPluginBundles=true \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true
```

That property includes the maintained first-party connector set. A narrower
release flavor should be produced by the release build rather than by copying
JARs beside an existing native executable.

## Configuration boundary

Consensus records contain a stable effect type, target alias, and bounded
action payload. Endpoints, credentials, wallet keys, TLS material, bucket
names, and broker/Kubo/backend policy are node-local. Never put them into an
effect payload, blueprint, lock, Studio link, or shared consensus YAML.

At-least-once execution is intentional. Targets must use the effect ID as an
idempotency key or provide the connector's documented reconciliation
semantics. An accepted effect is not proof that the external system acted;
verify the incorporated result and the external receipt.

## Kafka

Kafka contributes two independent features:

- `sink:kafka` publishes finalized app blocks; and
- `executor:kafka` executes allowlisted `kafka.publish` effects.

The sink and executor have separate configuration. Both require a node-local
security profile and operator-owned topic policy. See the
[Kafka integration guide](../../appchain/extensions/appchain-kafka/README.md).

## S3-compatible object storage

The `object.put` executor promotes a pre-staged, hash-verified object into a
versioned immutable destination. The target configuration owns endpoints,
buckets, credentials, encryption and retention. Its create-only bucket-policy
and version-history requirements are part of correctness, not optional
hardening. See the
[object-store guide](../../appchain/extensions/appchain-objectstore-s3/README.md).

## IPFS/Kubo

The `ipfs.pin` executor pins an already known CID. It does not add document
bytes, derive a CID, expose a gateway, or promise permanent replication. See
the [IPFS guide](../../appchain/extensions/appchain-ipfs/README.md).

## Cardano payments

The `cardano.payment` executor controls material funds and therefore has a
stricter readiness boundary. Use an operator-controlled payer, explicit
network/backend, transaction cap, protected signing credential, and a funded
testnet rehearsal before production. See the
[Cardano effects guide](../../appchain/extensions/appchain-effects-cardano/README.md).

## Release and demo distinction

The evidence demo stages Kafka, S3 and IPFS plugins and local services to prove
the complete scenario. That does not make those plugins bundled in the normal
Yano distribution. Always use the release capability catalog and `doctor`, not
the contents of a demo Compose environment, as packaging truth.
