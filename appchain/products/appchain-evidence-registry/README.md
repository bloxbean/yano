# App-chain evidence registry

`appchain-evidence-registry` is the ADR-013 first-party workflow component for
a product-batch inspection or compliance certificate. It lives under
`appchain/products`: it is reusable product code, while the runnable scenario
and sample business data remain under `appchain/examples`. It does not claim to
be a complete DPP implementation or a generic workflow engine.

The v1 domain contract uses topic `evidence.command.v1` and strict canonical
CBOR. It separates the wire schema version from the monotonically increasing
business version, stores immutable version records behind a small head record,
and derives business status from authenticated connector results.

The no-SPI `appchain-evidence-contracts` library includes:

- canonical `SUBMIT`, `NOTIFY`, and `REPUBLISH` commands;
- immutable head, version-record, effect-reference, and terminal-result codecs;
- canonical `evidence.available` Kafka event bytes;
- `evidence/get` request/response contracts that return the exact proven state
  keys and values;
- domain-separated state keys, effect scopes, and Kafka keys; and
- deterministic status derivation with fail-closed connector receipt checks.

`appchain-evidence-registry` ships the deterministic state machine, its
context-aware provider, and a bundle-owned read-only domain API. The state
machine emits `object.put` and `ipfs.pin` independently, retains exact terminal
tuples, and emits `kafka.publish` only after an authorized idempotent `NOTIFY`
command observes two matching storage receipts. The domain route
`GET evidence/{evidence_id}` projects the committed `evidence/get` query as
bounded JSON and binds the result to its chain, machine, height, and state root.

## Frozen v1 shapes

The command opcode is independent of the schema and business versions:

```text
SUBMIT    [1, 0, id, 1,
           object.put CBOR, expected object destination fingerprint,
           ipfs.pin CBOR, expected IPFS target fingerprint,
           Kafka target alias, Kafka topic alias,
           expected Kafka destination fingerprint]
NOTIFY    [1, 1, id, businessVersion]
REPUBLISH [1, 2, id, nextBusinessVersion, ...same connector fields as SUBMIT]
```

Each fingerprint is a required credential-free 32-byte commitment computed
with the corresponding connector contract helper. A receipt that is valid CBOR
but names a different resolved destination cannot advance business state.
Connector command bytes must themselves decode and re-encode under the frozen
`appchain-integration-contracts` v1 codecs. The outer command is at most 4 KiB.

The verified app-message sender becomes the owner; no duplicate signature or
public key is embedded in a command. Runtime policy creates only business
version 1 through `SUBMIT`, permits `REPUBLISH` only for the exact latest+1
version from that owner after a terminal business status, and treats an exact
replay as an idempotent no-op. Those transition rules are implemented in the
state-machine slice, not trusted to the codec alone.

Structural CDDL and literal vectors are published in the contracts JAR under:

```text
META-INF/yano/contracts/evidence/v1/
```

ADR-013 section 10.2, the strict codecs, and their positive/negative golden
tests complete the normative executable rules that CDDL cannot express.

The registry's normal JAR is the thin build-time/native artifact and declares
`appchain-evidence-contracts` as an implementation dependency. The published
`bundle` classifier is the self-contained drop-in plugin and privately
relocates both the evidence contracts and their connector/codec dependencies
while preserving executable provider and host API identity. This prevents a
host application using the public no-SPI SDK from shadowing the bundle's pinned
wire implementation through a parent-first class loader. The public contracts
do not depend on `core-api`; this executable registry is the sole mapping
boundary from framework `EffectOutcome` values to frozen
`EvidenceTerminalOutcome` wire codes. Its drop-in closure treats the Yano API
as host-provided and rejects unrelated Yaci, Netty, Reactor, and Jackson
classes. The separate
`appchain-evidence-client` companion depends directly on the no-SPI contracts
library and owns HTTP orchestration and proof verification; consuming that SDK
cannot activate the registry plugin.

## Machine settings

The provider reads the chain's consensus-affecting plugin settings:

```text
effects.enabled=true
effects.max-per-block=...
effects.max-payload-bytes=...
machines.evidence-registry.issuers=<comma-separated member public keys>
machines.evidence-registry.notify-senders=<comma-separated runner public keys>
machines.evidence-registry.storage-gate=app-final|l1-anchored
machines.evidence-registry.storage-expiry-blocks=0
machines.evidence-registry.notification-expiry-blocks=0
```

An empty issuer list permits any authenticated chain member; an empty notify
list permits only the evidence owner. Zero expiry selects the Effect Runtime's
deterministic default. The deployment profile must reserve two effect slots per
possible `SUBMIT` or `REPUBLISH` message.

## Build

```bash
./gradlew :appchain-evidence-contracts:check \
          :appchain-evidence-registry:check \
          :appchain-evidence-client:check
```
