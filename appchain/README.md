# Yano App Chain — modules & build guide

This folder holds the **app-chain module family**: the code for running a
sequenced, replicated, L1-anchored application ledger next to Cardano. The
consensus engine and the built-in `ordered-log` state machine live in the
`runtime` module; everything here is the standard library, the client/testing
tooling, the optional plugins, and the on-chain anchor validators.

Project names are kept flat (`yano-appchain-*`) even though the directories are
nested — see `settings.gradle` for the path mappings.

## Where the documentation lives

Don't look for feature docs here — they're centralized:

| Doc | Covers |
|---|---|
| [`docs/APP_CHAIN_USER_GUIDE.md`](../docs/APP_CHAIN_USER_GUIDE.md) | Configuration, REST, anchoring (metadata + script), operations |
| [`docs/APP_CHAIN_TUTORIAL.md`](../docs/APP_CHAIN_TUTORIAL.md) | Hands-on: run a chain from the distribution + a custom plugin |
| [`docs/APP_CHAIN_PLUGIN_QUERY_AND_DOMAIN_API.md`](../docs/APP_CHAIN_PLUGIN_QUERY_AND_DOMAIN_API.md) | Plugin-author contract for committed queries and manifested domain APIs |
| [`docs/APP_CHAIN_CONSENSUS_GUIDE.md`](../docs/APP_CHAIN_CONSENSUS_GUIDE.md) | Developer-level internals: consensus round, state-machine SPI, every built-in machine's wire format |
| `adr/app-layer/` | Design history (005 framework, 006 extensions, 008.x consensus/anchoring) |
| `app/appchain-cluster/README.md` | The multi-node demo cluster launcher |

## Modules

| Module (`:project`) | Path | Purpose |
|---|---|---|
| `appchain-stdlib` | `appchain-stdlib/` | Ready `AppStateMachine`s selected by id: `kv-registry`, `approvals`, `balances`, `doc-trail` (`ordered-log` is built into `runtime`). Ships in the distribution. |
| `appchain-client` | `appchain-client/` | Java client SDK — REST + SSE + client-side proof verification. |
| `appchain-testkit` | `appchain-testkit/` | JUnit 5 `@AppChainCluster` embedded multi-node clusters for tests. |
| `appchain-anchor-onchain` | `onchain/appchain-anchor-onchain/` | **On-chain anchor validators** (Plutus V3), authored in Java via julc — the default. See "On-chain artifacts" below. |
| _(Aiken twin)_ | `onchain/aiken/appchain-anchor/` | The same validators in Aiken — the opt-in, auditor-familiar alternative (same ABI). Its own README covers the Aiken build. |
| `appchain-integration-contracts` | `appchain-integration-contracts/` | Provider-neutral connector wire contracts, CDDL, and golden vectors. |
| `appchain-kafka` | `extensions/appchain-kafka/` | Plugin: finalized blocks and acknowledged `kafka.publish` effects → Kafka topics. |
| `appchain-objectstore-s3` | `extensions/appchain-objectstore-s3/` | Plugin: immutable, versioned `object.put` promotion for tested S3-compatible stores. |
| `appchain-ipfs` | `extensions/appchain-ipfs/` | Plugin: reconciled, acknowledged `ipfs.pin` effects against a configured Kubo node. |
| `appchain-zk` | `extensions/appchain-zk/` | Plugin (EXPERIMENTAL): ZeroJ-based proof-verified state machines. |
| `appchain-effects-cardano` | `extensions/appchain-effects-cardano/` | Plugin: Cardano payment executor for the app-chain effect system. |
| `appchain-spring-boot-starter` | `../spring-starters/appchain-spring-boot-starter/` | Spring Boot auto-config for the client SDK. |

## Building

```bash
./gradlew :appchain-stdlib:build              # one module
./gradlew build                               # everything (from repo root)
./gradlew :appchain-anchor-onchain:test       # anchor validator conformance vectors
```

Custom state machines are packaged as plugin jars (provider + `META-INF/services`)
or embedded in library mode — see the user guide §6 and `scaffolds/plugin-template/`.

## On-chain artifacts (the anchor validators)

The script-anchor validators (`appchain-anchor-onchain`) are Plutus V3 scripts.
The **compiled UPLC is checked in** at
`onchain/appchain-anchor-onchain/src/main/resources/META-INF/plutus/`
(`AnchorValidator.plutus.json`, `AnchorThreadPolicy.plutus.json`) and is what
the runtime loads — the build does **NOT** recompile them.

**Why checked in, not built each time.** The script hash derived from the
artifact IS a chain's on-chain identity (its thread policy id + validator
address). It must be byte-identical on every machine and every julc version —
otherwise two builds of the node could disagree on the identity and a node
couldn't spend its own anchor UTxO. Compiling at build time tied the bytes to
whatever julc a build happened to resolve (this actually bit us: the released
julc 0.1.0-pre14 miscompiled the validator while a local build did not). So the
model matches the Aiken twin: **compile once, commit the artifact, ship that.**

Two guards protect the artifact:

- **Golden pin** (`runtime/.../AnchorScriptArtifactsTest.bundledArtifacts_pinTheOnChainIdentity`)
  — asserts the checked-in bytes derive the exact known policy id + validator
  hash through the *runtime* loading path. Catches an accidental edit to the
  artifact, or a julc **runtime-library** bump that would change the hash.
- **Drift check** (opt-in) — the `JulcSourceCompile*ConformanceTest` suites
  recompile the validators FROM SOURCE with the locally resolved julc and run
  the full conformance vectors. Off by default (the released julc miscompiles
  them); run explicitly.

### Changing the validator (regeneration flow)

Do this whenever you edit a validator's Java source, or deliberately adopt a
newer/fixed julc that should produce the shipped bytes:

```bash
# 1. Verify the new source compiles correctly and passes ALL vectors:
./gradlew :appchain-anchor-onchain:test -Djulc.source-check=true

# 2. Copy the freshly compiled UPLC over the checked-in copies:
cp appchain/onchain/appchain-anchor-onchain/build/classes/java/main/META-INF/plutus/*.plutus.json \
   appchain/onchain/appchain-anchor-onchain/src/main/resources/META-INF/plutus/

# 3. Refresh the golden-pin hashes:
#    update GOLDEN_POLICY_ID / GOLDEN_VALIDATOR_HASH in
#    runtime/.../appchain/AnchorScriptArtifactsTest.java
#    (get the new values by running that test and reading the assertion diff)

# 4. Full build to confirm green, then commit source + artifact + pin together:
./gradlew :appchain-anchor-onchain:test :runtime:test
```

> A regenerated artifact = a **new script hash = a new on-chain identity** for
> chains bootstrapped *after* the commit. Chains already anchored on L1 keep
> their old identity forever (it's immutable on-chain) — so this is a
> deliberate, reviewable change, never a silent side effect of a dependency
> bump.

**Known gap:** a default build does not detect "source edited but artifact not
regenerated" — only the opt-in drift check does. It has to stay opt-in while
CI's julc release miscompiles the validator; once julc ships a fixed release
(> 0.1.0-pre14), turn the drift check on in CI so source ↔ artifact are
cross-verified automatically. (Tracked in `adr/app-layer/008.4-*` delivery
notes.)
