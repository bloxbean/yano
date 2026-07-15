# Yano App-Chain IPFS Integration

First-party, pin-only IPFS connector for Yano app chains. The plugin
contributes `AppEffectExecutorFactory` (`ipfs`) and executes acknowledged
`ipfs.pin` actions through the ADR-010 effect runtime.

V1 deliberately supports only a known CID and the Kubo HTTP RPC `pin` API. It
does not add document bytes, derive a CID, unpin content, fetch caller-selected
URLs, or provide an IPFS gateway. Those operations have different data,
authorization, retention, and determinism contracts and are not hidden inside
`ipfs.pin`.

See [ADR-013](../../../adr/app-layer/013-first-party-integration-connectors-and-effect-demo.md)
for the frozen command, receipt, reconciliation, and security model.

## Packaging

The stock Yano application omits this optional T3 integration. Build the
self-contained JVM plugin bundle with:

```bash
./gradlew :appchain-ipfs:shadowJar
# appchain/extensions/appchain-ipfs/build/libs/
#   yano-appchain-ipfs-<version>-bundle.jar
```

Copy only the `*-bundle.jar` into `yaci.plugins.directory`. It contains the
Kubo client and merged ServiceLoader metadata. Connector contracts, CBOR, and
BLAKE2b are relocated into bundle-private namespaces so independently
installed bundles cannot accidentally share their implementation classes.
The fixed Kubo response envelopes use a dependency-free, exact-schema,
bounded JSON parser; the artifact gate rejects accidental Jackson or Byte
Buddy inclusion.

The ordinary `yano-appchain-ipfs-<version>.jar` stays thin for build-time and
native inclusion. Build the app with
`-PincludeFirstPartyPluginBundles=true` to include this provider before native
catalog and reflection generation; native images cannot load plugin-directory
JARs after build time.

## Configuration

Effect payloads contain a stable target alias and canonical CID, never an API
URL or credential. The executor maps the alias to a locally configured Kubo
target. A local-demo target uses a numeric loopback/private address so startup
does not depend on an unbounded DNS lookup:

```properties
yano.app-chain.effects.executors.ipfs.enabled=true
yano.app-chain.effects.executors.ipfs.targets.local.target-id=local-kubo-v1
yano.app-chain.effects.executors.ipfs.targets.local.api-url=http://127.0.0.1:5001
yano.app-chain.effects.executors.ipfs.targets.local.security-profile=local-demo
yano.app-chain.effects.executors.ipfs.targets.local.allowed-codecs=raw,dag-pb
yano.app-chain.effects.executors.ipfs.targets.local.recursive=true
yano.app-chain.effects.executors.ipfs.targets.local.replication-policy=demo-single
yano.app-chain.effects.executors.ipfs.targets.local.connect-timeout-ms=1000
yano.app-chain.effects.executors.ipfs.targets.local.request-timeout-ms=5000
yano.app-chain.effects.executors.ipfs.targets.local.close-timeout-ms=1000
```

Optional detail documents can be durably archived under a private POSIX path:

```properties
yano.app-chain.effects.executors.ipfs.detail-archive-path=/var/lib/yano/ipfs-details
```

For a non-local Kubo RPC, terminate authenticated TLS in a private reverse
proxy and use the strict bearer profile. The hostname is verified by the JVM
trust configuration; redirects and ambient proxies remain disabled:

```properties
yano.app-chain.effects.executors.ipfs.targets.archive.target-id=archive-kubo-v1
yano.app-chain.effects.executors.ipfs.targets.archive.api-url=https://kubo.example.com:443
yano.app-chain.effects.executors.ipfs.targets.archive.security-profile=bearer-tls
yano.app-chain.effects.executors.ipfs.targets.archive.allowed-codecs=raw,dag-pb
yano.app-chain.effects.executors.ipfs.targets.archive.recursive=true
yano.app-chain.effects.executors.ipfs.targets.archive.replication-policy=archive-managed
yano.app-chain.effects.executors.ipfs.targets.archive.connect-timeout-ms=2000
yano.app-chain.effects.executors.ipfs.targets.archive.request-timeout-ms=10000
yano.app-chain.effects.executors.ipfs.targets.archive.close-timeout-ms=2000
yano.app-chain.effects.executors.ipfs.targets.archive.bearer-token=${YANO_IPFS_BEARER_TOKEN}
```

Keep `YANO_IPFS_BEARER_TOKEN` in the node's secret injection mechanism, never
in an effect, committed state, receipt, metric, or checked-in profile. Changing
an endpoint or policy requires a new `target-id`; do not repoint an identity
while its effects may still execute.

Omitting the executor namespace, setting `enabled=false`, or defining no
targets leaves the contribution inactive. The Kubo RPC port is an
administrative interface: bind it to a private network and do not expose it as
a public gateway.

Committed V1 commands carry the exact canonical CIDv1 bytes allowed by
ADR-013. Any conversion from CIDv0 text belongs in client/demo tooling, before
the command is emitted. The executor probes current pin state before mutation
and confirms only after Kubo reports the configured recursive/direct pin
policy. Retries therefore reconcile a prior acknowledgement instead of
blindly adding another pin.

## What a receipt means

A confirmed receipt proves only that the configured Kubo target reported the
expected CID pinned under the selected policy. A pin is not a promise of
permanent global availability: operators still own replication, storage,
monitoring, backup, and provider lifecycle.

IPFS content is not confidential. Never publish plaintext that must remain
private. Encrypt before publication under a separately designed key and
disclosure lifecycle, or retain the material only in a private object store.

## Tests

```bash
./gradlew :appchain-ipfs:check

# Exact supported release target; offline makes the missing-content case
# deterministic and prevents the test daemon from fetching the fixture.
docker run --detach --rm --name yano-kubo-adr013 \
  --publish 127.0.0.1::5001 \
  --env IPFS_TELEMETRY=off \
  ipfs/kubo:v0.42.0 daemon --offline --migrate=true
KUBO_PORT="$(docker port yano-kubo-adr013 5001/tcp | sed 's/.*://')"

./gradlew \
  -Dyano.ipfs.integration.enabled=true \
  -Dyano.ipfs.integration.endpoint="http://127.0.0.1:${KUBO_PORT}" \
  :appchain-ipfs:test --tests '*KuboIpfsPinClientRealIntegrationTest'

docker rm --force yano-kubo-adr013
```

The offline gate covers contract conformance, policy and error normalization,
reconciliation, lifecycle, and artifact isolation. The real-Kubo suite is
opt-in so the normal build never depends on a network service. Release evidence
pins both the image tag and digest recorded in ADR-013.
