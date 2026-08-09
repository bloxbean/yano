# ADR app-layer/035: Cardano History product, console, and distribution

**Status:** Accepted and implemented for preview — P0–P7 complete; production remains subject to
ADR-028 M8d and ADR-027 public-network release gates
**Date:** 2026-08-09
**Scope:** Reusable plugin UI contributions plus the optional Cardano History plugin/profile, read
API, typed client and CLI, plugin-packaged console experience, showcase presets, reference on-chain
consumers, and release packaging
**Depends on:** ADR app-layer/028 M1–M7 and ADR app-layer/031
**Related:** ADR app-layer/025.x (authenticated state), 032 (verifiable indexers), and 033
(showcase catalog and capability discovery)
**Changes no consensus or proof wire format.**

---

## 1. Context

ADR-028 specifies the reusable foundation for observing epoch-boundary L1 ledger state and placing
protocol parameters, epoch stake, proposal lifecycle, and DRep distribution in authenticated app
state. Its `core-api` SPI and independently selectable stdlib components are deliberately useful to
custom `AppStateMachine` implementations.

Those primitives alone are not an out-of-box product. An evaluator should not need to discover the
correct observer combination, write a composition provider, construct physical authenticated-map
keys, correlate proofs to one root, or build a UI before proving a historical Cardano fact. The
standard distribution needs one installable and demonstrable assembly while preserving ADR-028's
reusability and ADR-031's rule that every deployed application is an `AppStateMachine`.

The repository already has the required product seams:

* plugin bundles contribute `AppStateMachineProvider` and `DomainApiProvider` through `ServiceLoader`;
* `AppCapabilityManifest` exposes components, workflows, cross-cutting capabilities, and typed
  proof subjects without interpreting a state-machine or chain name;
* generic state query, proof, verification, commitment, and L1-anchor APIs already exist;
* the console already discovers application capabilities, but it has no runtime plugin UI
  contribution or asset-hosting contract;
* product families can publish a runtime-independent client and a deterministic bundle jar.

This ADR turns those seams into the **Cardano History** product. It does not relocate or duplicate
the epoch implementation from ADR-028.

## 2. Decision summary

Yano will ship an optional plugin bundle with plugin ID
`com.bloxbean.cardano.yano.appchain.cardano-history`. The bundle contributes one
`AppStateMachineProvider`, with state-machine ID `cardano-history`, and one read-only domain API.
The state machine is a configured composition of ADR-028 stdlib components, not a new epoch-state
implementation.

The product family will also provide:

1. a lightweight typed Java client and independent proof-bundle verifier;
2. a distributable CLI built on that client;
3. application-specific reference on-chain validators built on the generic ADR-031 MPF verifier;
4. a reusable, sandboxed `ui-extension` plugin contribution and a Cardano History UI packaged with
   the product bundle; and
5. ready showcase and operator profiles, including a low-cost parameters-only default and explicit
   stake/governance/full profiles.

The main console remains the trusted application shell, but it no longer contains
Cardano-History-specific pages. At runtime it discovers eligible `ui-extension` contributions,
adds their navigation, and mounts their content in a sandboxed frame through a versioned,
permission-limited host bridge. The plugin bundle contains production-built HTML, JavaScript, CSS,
and immutable assets; it does not receive access to the parent DOM or console credentials.

## 3. Goals and non-goals

### 3.1 Goals

| # | Goal |
|---|---|
| G1 | Install and enable Cardano History with one plugin bundle and one validated product preset |
| G2 | Keep protocol parameters, stake, and governance independently selectable and reusable |
| G3 | Query a historical fact and generate a root-fixed proof without knowing physical map keys |
| G4 | Verify MPF proof bundles independently off-chain and use reference predicates on-chain |
| G5 | Make enabled datasets, progress, completeness, roots, and anchoring clear in the console |
| G6 | Provide a short, reproducible three-node showcase path and production-oriented packaging |
| G7 | Add no epoch-specific core REST API and no second authenticated source of truth |
| G8 | Make one plugin bundle sufficient to install a product's backend capabilities and UI |
| G9 | Establish a reusable, versioned, capability-gated UI extension contract for future products |

### 3.2 Non-goals

This ADR does not:

* change ADR-028 observation, snapshot, key, value, replay, rollback, or completeness semantics;
* add JMT on-chain verification—JMT remains off-chain-only;
* place a ledger-state database, an indexer database, or source snapshots in the plugin;
* make the app-chain runtime depend on the Cardano History product;
* execute plugin JavaScript in the trusted console DOM or grant undeclared host capabilities;
* provide an unversioned general-purpose browser bridge or cross-plugin frontend dependency graph;
* replace the generic app-chain proof, anchor, evidence, or capability APIs;
* make a node-hosted verification endpoint an independent trust boundary; or
* migrate preview app-chain identities or state. A changed profile starts a fresh chain.

## 4. Architecture and ownership

```text
existing L1 ledger state
        |
        v
ADR-028 core-api L1EpochObserver SPI + runtime coordinator
        |
        v
ADR-028 stdlib params / stake / governance components
        |
        v
Cardano History AppStateMachine provider (thin preset assembly)
        |
        +---- generic ADR-031 state/proof/anchor APIs ---- client / CLI / plugin UI
        |
        +---- AppCapabilityManifest + ui-extension ------- console shell discovery
        |
        `---- MPF root anchored by SCRIPT anchor -------- reference validators
```

### 4.1 Module layout

| Module | Responsibility |
|---|---|
| `core-api/.../plugin/ui` | Framework-neutral, immutable UI contribution/catalog descriptors and validation limits; no web framework or asset loading |
| `app` | Host-owned UI contribution lifecycle, catalog endpoint, immutable asset gateway, path/media/digest enforcement, and plugin unload cleanup |
| `console-ui` | Generic plugin navigation, sandboxed frame host, versioned message bridge, permission checks, loading/error states, and no product-specific pages |
| `appchain/products/appchain-cardano-history` | Plugin provider, presets, configuration validation, capability manifest, read-only domain API, product frontend source/build, UI descriptor/assets, and deterministic bundle jar |
| `appchain/products/appchain-cardano-history-client` | Typed queries, root-fixed proof-bundle assembly, canonical history semantic checks, and reuse of `appchain-client` verification; no plugin activation |
| `appchain/products/appchain-cardano-history-cli` | Operator/user commands built only on the public client and HTTP APIs; distributable application archive |
| `appchain/onchain/appchain-cardano-history-onchain` | Reference parameter, stake, proposal, and DRep predicates layered on `MpfOnChainVerifier` |
| `appchain-showcase` | Bundle inclusion, presets, launch flags, fixtures, smoke tests, and runbook |

Canonical epoch keys, values, manifests, chunks, and proof subjects remain in
`appchain-stdlib-contracts`. Observation and state-transition implementations remain in
`appchain-stdlib`. There is no product-local copy and no `cardano-history-contracts` module in v1.
The client may expose convenience DTOs for a complete proof bundle, but canonical leaf codecs have
one owner.

### 4.2 Dependency rules

1. `appchain-cardano-history` may depend on `core-api`, stdlib contracts, stdlib, and narrowly
   required plugin APIs. It must not depend on `runtime`, `node-app`, or concrete ledger-state
   implementations.
2. `appchain-cardano-history-client` reuses `appchain-client`'s `ProofVerifier` for generic MPF/JMT
   cryptography and commitment/finality bindings. It must not depend on `runtime`, Quarkus, a plugin
   provider, or ledger state. Loading the client must never activate a plugin.
3. The CLI depends on the client, not on server implementation modules.
4. The on-chain module depends on the generic MPF on-chain verifier and canonical codecs/fixtures,
   not on server/runtime modules.
5. The console shell imports no product source or generated product assets at build time. It
   recognizes only the generic UI descriptor and bridge protocol; the runtime catalog supplies the
   plugin identity, eligible views, and content-addressed asset URLs.
6. Plugin UI uses only its declared host-bridge operations. It cannot import console internals,
   reach another plugin's assets, or call server/runtime implementation classes.

Artifact-boundary tests enforce these rules by inspecting runtime dependency graphs, bundle
contents, `ServiceLoader` descriptors, and duplicate classes.

## 5. Plugin and product profile

### 5.1 Contributions

The bundle manifest declares exactly:

* plugin ID `com.bloxbean.cardano.yano.appchain.cardano-history`;
* one `state-machine` contribution for `cardano-history`;
* one `domain-api` contribution for read-only convenience queries;
* one `ui-extension` contribution for the Cardano History experience; and
* required foundation compatibility versions.

The product does not contribute separate `L1EpochObserverProvider` implementations. The first-party
providers are reusable stdlib capabilities. Product startup resolves the selected preset to those
provider IDs and fails if a required provider is missing or ambiguous.

The published thin jar is suitable for Maven consumers. A deterministic `-bundle.jar` is the
single-file operator install. It excludes Yano host APIs and first-party stdlib classes, merges only
the product's service descriptors, relocates product-private third-party dependencies where needed,
and fails its build if it packages a second copy of a host/provider class.

### 5.2 Reusable `ui-extension` contribution

ADR-035 introduces `ui-extension` as a first-class plugin contribution kind. It is discovered only
from a plugin that passed the existing allow-list, manifest, compatibility, and lifecycle checks.
Its descriptor is data, not Java code executed in the console shell. The v1 descriptor fixes:

```json
{
  "uiApiVersion": 1,
  "extensionId": "cardano-history",
  "title": "Cardano History",
  "mountPoint": "app-chain",
  "entrypoint": "META-INF/yano/ui/index.html",
  "requiredCapabilities": ["l1-epoch-params-v1"],
  "permissions": [
    "app-chain.status.read",
    "app-chain.domain.read",
    "app-chain.proof.read",
    "app-chain.anchor.read"
  ],
  "assetsManifest": "META-INF/yano/ui/assets-manifest.json"
}
```

The concrete record names follow repository plugin conventions, but these semantics are normative:

* `(bundleId, extensionId)` is globally unique and every string/collection has a strict bound;
* the mount point and permission values are closed host enums, not arbitrary routes or scopes;
* required capabilities are AND-matched against the selected chain's actual
  `AppCapabilityManifest`; installation alone never makes a view eligible;
* the entrypoint and every transitive asset must be a normalized jar resource below
  `META-INF/yano/ui/`, with fixed media type, byte size, and SHA-256 digest;
* absolute paths, `..`, symlinks, duplicate normalized paths, executable media not declared by v1,
  inline source maps, and assets outside the declared count/total-byte budget are rejected; and
* unsupported `uiApiVersion`, bridge permission, or capability requirements fail that UI
  contribution without exposing a partially compatible view. Consensus contributions follow their
  existing fail-closed startup policy independently.

The host publishes a read-only catalog for successfully loaded UI contributions and serves assets
from a core-owned, content-addressed namespace such as:

```text
/ui-plugins/{bundleId}/{assetsDigest}/index.html
/ui-plugins/{bundleId}/{assetsDigest}/assets/app.js
```

The gateway streams only validated resources from the loaded bundle; it never treats a jar path as
a filesystem path. Responses use the declared media type, strong digest/ETag, immutable caching,
`nosniff`, and a restrictive Content Security Policy. No plugin route can override the main console
or another bundle's namespace.

The console mounts the entrypoint in an iframe with `sandbox="allow-scripts"` and without
`allow-same-origin`, top navigation, popups, forms, or parent-DOM access. Its CSP denies network
connections and external assets. A small `postMessage` bridge is therefore the only host interface.
Every request carries the negotiated UI API version, extension/session nonce, selected chain, method
ID, and request ID. The shell verifies the frame source, origin model, nonce, permission, selected
chain capability, input size/schema, response size, timeout, and cancellation before dispatch.

V1 bridge operations are read-only: selected-chain/capability/theme changes, app-chain status,
plugin domain GET/POST constrained to the calling bundle's declared `READ` routes, generic query,
state proof, state identity, finality/anchor reads, and bounded file import/export. There is no raw
`fetch`, arbitrary URL, admin API, message submission, key/seed access, cookie/storage access,
HTML injection into the parent, or direct bridge from one plugin to another. Future mutable bridge
permissions require a separate ADR and explicit user authorization.

The main console owns navigation and renders loading, incompatible, disabled, and crashed states.
Removing/disabling a plugin removes its catalog entry after runtime lifecycle reconciliation; stale
content-addressed asset URLs return not found. A plugin UI failure cannot stop app-chain consensus or
the rest of the console.

### 5.3 Plugin frontend layout and build

Cardano History is the conformance implementation. Its source stays beside the product and its
production output is embedded in the same operator bundle:

```text
appchain/products/appchain-cardano-history/
  frontend/                         # TypeScript/Svelte (or equivalent) source
  src/main/java/                    # providers and domain API
  src/main/resources/META-INF/yano/plugins/...

cardano-history-bundle.jar
  META-INF/yano/ui/ui-extension.json
  META-INF/yano/ui/index.html
  META-INF/yano/ui/assets/app.<hash>.js
  META-INF/yano/ui/assets/app.<hash>.css
  META-INF/yano/ui/assets-manifest.json
```

Gradle invokes a reproducibly pinned frontend toolchain, runs lint/unit/component/accessibility tests,
builds production assets, rejects undeclared external URLs and dynamic remote imports, generates the
asset manifest, and then assembles the jar. `shadowJar` must preserve these resources without
rewriting their paths or digests. The build runs without downloading dependencies after the normal
repository dependency-resolution step, and identical source/toolchain inputs produce identical UI
asset bytes.

### 5.4 Presets

| Preset | Params | Stake | Proposal history | DRep distribution | Intended use |
|---|---:|---:|---:|---:|---|
| `params-only-v1` | yes | no | no | no | Default and lightweight evaluation |
| `params-stake-v1` | yes | yes | no | no | Stake amount and delegation proofs |
| `params-governance-v1` | yes | no | yes | yes | Governance history evaluation |
| `full-v1` | yes | yes | yes | yes | Full Cardano History deployment |

These are product conveniences, not new state machines. A custom machine may compose any ADR-028
component without using the product provider. A product preset is immutable after chain genesis;
its resolved components, canonical-format versions, commitment profile, and relevant settings are
included in the application profile fingerprint.

### 5.5 Configuration

An illustrative configuration is:

```yaml
yano:
  app-chain:
    chains:
      - chain-id: cardano-history-chain
        state-machine: cardano-history
        machines:
          cardano-history:
            preset: params-only-v1
        state:
          commitment-profile: mpf-blake2b256-v1
          l1-proof-consumption-required: true
        anchor:
          enabled: true
          mode: script
          signing-key: <injected-by-operator-or-showcase>
          script:
            validator: <injected-validator-cbor>
            thread-policy: <injected-thread-policy-cbor>
        l1:
          epoch-stability-depth: 2160
        observers:
          epoch-params:
            type: l1-epoch-params-v1
```

The exact YAML shape follows the implemented ADR-031 configuration model rather than creating a
parallel product parser. A renderer in the CLI/showcase may generate the machine and observer
sections together, but the runtime still validates them independently.

Startup validation rejects:

* an enabled state component without its exact observer and canonical format version;
* an observer enabled for a dataset the state machine cannot consume;
* stake or DRep traversal when the node lacks the required account/governance state;
* an on-chain-consumption profile using JMT, metadata-only anchoring, or an unbootstrapped SCRIPT
  anchor;
* budgets inconsistent with ADR-028's 25,000-entry and 32,768-operation limits; and
* different resolved profile fingerprints among cluster members.

For local development only, a clearly named `anchor-required: false` mode may expose unanchored
proofs. Capability/status output must then say that the root is not independently L1-bound; the
console must not present such a proof as on-chain verifiable.

## 6. Capability discovery and health

The provider declares `applicationId=cardano-history` and an application format version. It reports
only capabilities actually enabled by the resolved preset.

Minimum component identifiers are:

* `l1-epoch-params-v1`;
* `l1-epoch-stake-v1`;
* `l1-epoch-proposal-history-v1`; and
* `l1-epoch-drep-distribution-v1`.

Proof subjects use the canonical ADR-028 identifiers, including
`l1-epoch-protocol-parameters-v1`, `l1-epoch-stake-v1`, proposal status, and DRep distribution.
The manifest separately declares MPF/JMT commitment, off-chain/on-chain proof support, SCRIPT-anchor
support, and completeness requirements. It never claims on-chain support merely because MPF is
configured; the anchor and proof-consumption gates must also be satisfied.

Discovery is based on the manifest, not `chain-id`, state-machine ID, installed plugin list, or
physical state key. Installing a disabled plugin creates no enabled capability.

Dynamic node-local health—observer lag, spool state, failed/cancelled generation, and anchor
progress—uses the existing app-chain status surface. Committed dataset metadata—available epochs,
boundary, completion, height, and root—comes from authenticated application queries. The console
labels these separately so a healthy local observer is never mistaken for a committed historical
fact.

## 7. Read-only domain API

The plugin contributes bounded `READ` routes under its existing plugin-domain base path. Initial
route templates are:

```text
status
epochs
epochs/{epoch}/parameters
epochs/{epoch}/stake/{credential-type}/{credential-hash}
epochs/{epoch}/dreps/{drep-type}/{drep-hash}
proposals/{transaction-id}/{index}
```

For example, the host exposes the parameter route as
`GET /plugins/com.bloxbean.cardano.yano.appchain.cardano-history/epochs/170/parameters?chain=cardano-history-chain`.

`chain` is mandatory where the host domain API does not already bind it. Collection routes use
bounded `limit` and opaque cursors. Hash lengths, discriminators, epoch ranges, and response sizes
are strictly checked before a state query. These routes never mutate state, enqueue work, expose
ledger-state objects, or accept arbitrary physical keys.

A fact response includes enough data to construct and audit a root-fixed proof request:

* API and dataset format versions;
* requested fact and canonical decoded value, if present;
* snapshot semantics, boundary epoch, and dataset epoch;
* completeness state and its proof-subject coordinates when required;
* canonical value bytes and typed proof-subject coordinates;
* committed height and state root returned by the bounded `DomainQueryService`; and
* the proof coordinates needed by a client to obtain generic commitment, finality, and anchor data.

The domain context does not expose block certificates, commitment identity, profile fingerprint, or
anchor lookup, so the convenience response does not synthesize them. The client obtains those from
the existing generic state-identity, proof, finality, and anchor surfaces.

The domain API does not invent another proof endpoint. The client and console pass the returned
typed coordinates and exact height to ADR-031's generic proof endpoint and use the generic anchor
endpoint. If the root advances between the convenience query and proof retrieval, they retry from a
new root-fixed query; they never combine artifacts from different heights.

Absence is not reported as a historical zero or non-delegation until the dataset's completeness
leaf is true at the same state root. A partial epoch is visible as progress, never as complete data.

## 8. Proof bundle, client, and CLI

### 8.1 `cardano-history-proof-bundle-v1`

The typed client assembles a self-contained bundle containing:

* chain, application, genesis, commitment, and profile identities;
* dataset type/version, snapshot semantics, boundary epoch, and dataset epoch;
* exact canonical key and value bytes;
* the normalized `mpf-proof-wire-v1` fact proof;
* a same-root completeness proof when the assertion requires it;
* finalized height, block ID, state root, and certificate coordinates;
* the SCRIPT anchor output/reference and canonical anchor datum; and
* the requested semantic predicate and its public operands.

Decoded JSON is explanatory. Verification is performed over canonical bytes and strict profile
identities, not over a display value.

### 8.2 Verification

The history client delegates generic proof/profile/finality cryptography to the existing
`appchain-client` `ProofVerifier` and adds only the Cardano History key/value/completeness and
predicate layer. The assembled verifier checks, in order:

1. strict bundle/schema/profile versions and size limits;
2. canonical physical-key derivation from the typed subject and request;
3. MPF inclusion or exclusion proof against the declared state root;
4. fact and completeness proof equality of root, height, and commitment identity;
5. required `complete=true` semantics for zero, absence, and whole-dataset claims;
6. snapshot labels and canonical value decoding;
7. the application predicate—for example `coin >= minimum`, `poolHash == expected`, both, protocol
   parameter equality/range, proposal status, or DRep voting stake; and
8. the L1 anchor datum, chain identity, output/reference, and stability evidence supplied by the
   caller.

The verifier can also stop after step 7 and return `ROOT_VERIFIED_ANCHOR_UNCHECKED`; it must not call
that result independently anchored. L1 lookup is injectable so an application can use its own
Cardano source.

### 8.3 CLI

The CLI provides, at minimum:

```text
yano-cardano-history status
yano-cardano-history epochs
yano-cardano-history query params --epoch E
yano-cardano-history query stake --epoch E --credential ...
yano-cardano-history query drep --epoch E --drep ...
yano-cardano-history query proposal --tx-id ... --index ...
yano-cardano-history proof stake --epoch E --credential ... --output bundle.json
yano-cardano-history verify --bundle bundle.json [--cardano-source ...]
yano-cardano-history config render --preset params-only-v1
```

Commands accept an explicit node base URL and chain ID. They do not silently assume port 7070 or an
instance name. Exit codes distinguish unavailable/incomplete data, invalid proof, valid root with
unchecked anchor, and fully verified anchor.

## 9. Reference on-chain consumers

The on-chain module is an application layer over the existing generic MPF proof verifier and
eleven-field anchor datum. It does not implement another trie or anchor validator.

Reference predicates cover:

* protocol parameter equality and bounded comparison;
* stake coin equality/minimum, delegated pool equality, and the combined predicate from one
  canonical `[coin, poolHash]` leaf;
* stake absence/non-delegation only with a same-root completeness proof;
* proposal lifecycle status at the defined epoch; and
* DRep voting stake equality/minimum with the same completeness rule.

Every validator fixes the proof profile, canonical key/value schema, dataset version, and expected
Cardano History application identity. A caller cannot substitute another chain with a coincidentally
valid MPF root. Golden vectors are shared between the Java verifier, browser verifier, and on-chain
tests. ADR-028 M4 remains the gate for releasing stake/DRep validators at production scale.

## 10. Console experience

The Cardano History plugin contributes its page; `console-ui` contains only the reusable extension
host. When the runtime catalog contains a compatible Cardano History UI contribution and the
selected chain satisfies its declared capabilities, the shell adds a compact navigation entry and
mounts it below a host route such as
`/ui/app-chain/extensions/com.bloxbean.cardano.yano.appchain.cardano-history/cardano-history`.
The URL is a shell route, not a product page compiled into the console. The general Reference
Capability Matrix remains on its separate tab.

The Cardano History page contains:

1. **Overview** — enabled datasets, snapshot semantics, latest complete epochs, observer/spool
   health, current root, finality, and L1 anchor status;
2. **Protocol Parameters** — select epoch, inspect canonical/display values, and generate a proof;
3. **Stake** — enter credential, inspect coin and delegated pool, choose amount/pool/combined
   predicate, and include the completeness proof when required;
4. **Governance** — proposal status and DRep distribution queries when their capabilities exist;
5. **Proof Lab** — generate/download a typed bundle, paste/upload a bundle, verify it, inspect each
   binding, and clearly distinguish root verification from independent L1-anchor verification.

The plugin UI implements strict local verification of the released MPF wire profile using a small,
versioned browser proof package built from the same generic proof contracts—not a different history
proof algorithm—and adds history semantic bindings using shared golden vectors. Calling the node's
generic `/proof/verify` route through the bridge is an optional diagnostic comparison and is labelled
**node-assisted**, not independent verification. Independent anchor verification may use a bounded
bundle imported through the host bridge; data obtained from the same app-chain node is identified as
such.

The page is capability-driven and handles partial presets. It does not display empty Stake or
Governance controls for a params-only chain. Its bridge grant exposes no admin keys, seed material,
mutable domain routes, arbitrary network access, or parent-console DOM.

## 11. Showcase and operator experience

### 11.1 Showcase profiles

The showcase ZIP contains the product bundle, CLI distribution, config templates, proof fixtures,
and runbook. Once ADR-028 M3 exists, this ADR extends ADR-033's default `light` catalog with a
thirteenth chain, `cardano-history-chain`, using `params-only-v1`. This is the inexpensive stock
recipe defined by ADR-028. Short devnet epochs let a presenter observe a boundary, finality,
ingestion, state-root change, anchoring, query, and proof in one session.

The launcher additionally supports an explicit option such as:

```text
./showcase.sh quickstart --profile light
./showcase.sh quickstart --profile light --cardano-history-profile full-v1
./showcase.sh quickstart --profile light --disable-cardano-history
```

The no-option light profile selects `params-only-v1`. The profile option accepts only the released
preset IDs; the explicit disable switch removes the chain for a minimal or ADR-033-only run. Stake,
governance, and full selections must print their account-state requirements, estimated storage/time,
epoch settings, and whether the selected network can complete within the demo window. They must not
silently turn on a 1.3M-entry preprod/mainnet scan.

The launcher renders observers and application components from the same preset, creates a fresh
commitment identity, validates the three members' profile agreement, bootstraps the SCRIPT anchor,
and prints the exact UI and CLI commands. As in ADR-033, instance validation resolves a real prepared
instance and its stored ports; an arbitrary name or hard-coded port must fail clearly.

### 11.2 Demonstration path

The automated devnet demonstration must:

1. start three members and confirm identical application/commitment identity;
2. cross the first-new-epoch boundary without delaying the block publisher;
3. show params observation preparation, sequencing, completion, and identical roots;
4. anchor the completed root through the SCRIPT anchor;
5. query one parameter, generate a proof bundle, and verify it independently through the CLI;
6. repeat proof verification through the browser Proof Lab; and
7. restart a member and prove replay reproduces the root without reading historical L1 state.

Full-profile qualification additionally exercises stake amount, pool, combined, absence plus
completeness, proposal, and DRep proofs. Preprod setup consumes only operator-supplied chainstate and
keys; no release artifact contains private keys or copied app-chain databases.

## 12. Distribution

The release publishes:

* Maven artifacts for the thin product and client jars;
* a checksummed Cardano History plugin bundle jar containing its validated production UI assets;
* a versioned CLI archive and launcher;
* reference on-chain source/artifacts and golden vectors;
* configuration templates for every preset; and
* an operator/user runbook covering install, anchor bootstrap, query, proof, verification, backup,
  retention, resource sizing, and clean removal.

The main Yano distribution may include the reusable ADR-028 stdlib providers. The optional product
bundle selects and presents them. Installing or removing that bundle never changes core-api/runtime
classes. Release validation starts a stock distribution, copies only the product bundle into its
plugin directory, enables a preset, and proves backend, domain API, capability, navigation, and UI
asset discovery without rebuilding `console-ui`. It also proves that removing the bundle removes its
UI catalog/navigation and makes startup fail explicitly when `state-machine: cardano-history`
remains configured.

## 13. Security and correctness requirements

| Risk | Required control |
|---|---|
| Product diverges from reusable semantics | One canonical stdlib codec/transition owner; cross-module golden vectors |
| Convenience API creates a second proof protocol | It returns typed coordinates; generic ADR-031 endpoint supplies proofs |
| Same-node query/proof race | Root-fixed height and root; retry rather than mix artifacts |
| Partial snapshot interpreted as absence | Same-root completeness proof is mandatory |
| Proof valid for the wrong application/profile | Verify chain, application, genesis, commitment, and profile identities |
| MPF root is not independently anchored | Require and verify SCRIPT anchor; label unanchored/root-only results |
| Browser verifier drifts from Java/on-chain code | Shared positive/negative golden vectors and differential tests |
| Plugin shadows host APIs/providers | Bundle duplicate-class and service-descriptor audit |
| Plugin UI executes in trusted console context | Opaque-origin sandbox without parent DOM or direct host APIs |
| Plugin UI exfiltrates data | CSP denies network; bridge has closed read-only permissions and no credentials/secrets |
| Asset traversal or content-type confusion | Normalized jar paths, declared hashes/sizes/media types, `nosniff`, core-owned namespace |
| Stale/tampered assets | Content-addressed URLs, digest verification at load, immutable cache, lifecycle removal |
| Bridge spoofing or confused deputy | Frame source plus session nonce, schema/size checks, selected-chain capability and permission check per request |
| UI crash affects node | UI lifecycle isolated from consensus/plugin providers; shell timeout and error boundary |
| Expensive scans enabled accidentally | Explicit non-default presets, startup gates, resource estimates, bounded spool |
| Domain query used for denial of service | READ-only routes, strict input/limit/response bounds, rate-limit compatibility |
| Sensitive operator material enters artifacts | Fixture-only devnet keys; release/ZIP secret scan; preprod keys remain external |

All ADR-028 rollback and replay gates remain normative. In particular, no preprod/production
attestation is released before ADR-027 §2.4 deep-rollback detection is wired.

## 14. Implementation phases and gates

| Phase | Work | Exit gate |
|---|---|---|
| **P0 — characterization** | Freeze product IDs, preset/profile schema, API DTOs, capability IDs, UI descriptor/bridge/permission schemas, asset limits, bundle schema, CLI exit codes, dependency rules, and current generic API behavior | Contract tests and architecture checks fail against missing implementation for the intended reasons |
| **P1 — plugin UI foundation** | Add `core-api` UI contracts, plugin contribution validation/lifecycle, catalog and content-addressed asset gateway, sandboxed console host, read-only bridge, and malicious fixture plugins | Conformance proves capability gating, digest/path/media enforcement, sandbox/permission isolation, lifecycle cleanup, and no product-specific console code |
| **P2 — product assembly** | Add product module, thin provider over ADR-028 components, preset resolver/validation, capability manifest, plugin metadata, and deterministic bundle | Params-only plugin installs into stock distribution; three members resolve byte-identical profiles; no duplicate host classes |
| **P3 — query/client/CLI** | Add bounded domain queries, root-fixed client, proof bundle, standalone verifier, CLI, malformed/negative vectors | Params proof verifies independently from an L1 SCRIPT anchor; race, wrong-root, wrong-profile, and incomplete claims fail closed |
| **P4 — on-chain consumers** | Add parameter validator first, then stake/governance predicates after their ADR-028 milestones and M4 budget gate | Emulator/golden tests prove canonical predicates and reject wrong key/value/root/profile/completeness |
| **P5 — Cardano History plugin UI** | Build/package UI descriptor and assets, dataset views, Proof Lab and browser verifier; integrate only through the generic bridge | Installing one jar adds eligible navigation/UI without rebuilding the console; params/full views and browser/Java/on-chain vectors agree |
| **P6 — showcase/distribution** | Package bundle/CLI/templates/runbook, add params default and explicit full option, automate SCRIPT anchor/bootstrap/demo | Fresh three-node ZIP installation completes the demonstration path with no source-tree dependency |
| **P7 — qualification** | Restart/replay, UI threat/fault tests, resource/security audit, full build, devnet soak, then preprod pilot when ADR-027 and ADR-028 gates allow | ADR-028 M8, artifact audit, UI tests, `clean build`, and published operator budgets are green |

P1 is reusable infrastructure and can proceed independently. P2's params-only product path begins
after ADR-028 M3. Stake functionality cannot pass P3/P4/P5/P6 until M4 accepts direct MPF and M5 is
complete. Governance functionality waits for M6. The product may ship a params-only preview before
the full preset, but it must not advertise unavailable capabilities.

Each phase follows implement → review → test → learn → iterate. Findings that alter canonical epoch
semantics return to ADR-028; findings limited to packaging, API, console, or distribution update this
ADR.

## 15. Definition of done

The ADR is complete only when:

* every preset is a composition of the same independently tested ADR-028 components;
* a custom non-product `AppStateMachine` can still reuse each component;
* params-only activation performs no stake/governance traversal;
* domain queries are bounded, typed, root-fixed, read-only, and contain no alternate proof bytes;
* Java, CLI, browser, and on-chain verifiers share golden vectors and fail closed;
* stake coin-only, pool-only, combined, and absence/completeness predicates are proven on-chain
  within the accepted M4 budgets;
* capability discovery works without state-machine/chain/plugin-name heuristics;
* copying one plugin bundle into a stock runtime adds its backend contributions and eligible UI
  navigation without rebuilding the standard console;
* UI conformance fixtures reject traversal, digest/media mismatch, oversize assets, unsupported API,
  undeclared permissions, forged bridge messages, wrong-chain capability, and cross-plugin access;
* disabling/removing a plugin removes its catalog entry and assets without affecting other views or
  app-chain consensus;
* a fresh three-node cluster agrees on identities, roots, completion, and SCRIPT anchors;
* restart and from-genesis replay reproduce roots without historical L1 access;
* bundle, CLI, showcase ZIP, templates, and runbook work outside the source tree;
* no private/preprod material or app-chain database appears in a release artifact; and
* `./gradlew clean build` and the relevant cluster/UI/on-chain suites pass.

## 16. Rejected alternatives

### 16.1 Put Cardano-specific endpoints in core REST

Rejected. It expands core for one product and gives custom machines no benefit. The read-only domain
API plus generic proof APIs provide the required surface.

### 16.2 Implement history again in the product plugin

Rejected. It creates two canonical encodings, transition implementations, and proof semantics. The
plugin is a preset/assembly and presentation layer over stdlib.

### 16.3 Load plugin JavaScript directly into the console DOM

Rejected. A same-realm module can inspect or mutate the shell, intercept credentials, collide with
dependencies/styles/routes, and bypass a meaningful permission boundary. V1 accepts compiled plugin
UI only through the content-verified, opaque-origin sandbox and closed host bridge.

### 16.4 Limit plugins to declarative host-rendered forms

Rejected as the only v1 mechanism. It is safer but cannot express a useful local proof verifier and
Proof Lab without continually expanding a product-specific host schema. The descriptor/navigation
is declarative; product behavior runs in the sandbox. A later library of declarative host widgets
can coexist with this contract.

### 16.5 Detect the feature by chain ID or installed plugin

Rejected. Names do not prove enabled behavior, and an installed bundle may be disabled. The
application capability manifest is authoritative.

### 16.6 Add duplicate stake coin and pool leaves

Rejected by ADR-028. One canonical `[coin, poolHash]` leaf supports independent and combined
predicates; duplicate leaves add consistency and proof-surface risk.

### 16.7 Use a node-hosted verify endpoint as the only verifier

Rejected. It is useful for diagnostics but does not satisfy independent verification. The client,
CLI, browser, and on-chain consumer verify the released proof/profile themselves.

## 17. Resolved v1 product decisions

1. Cardano History is one optional plugin bundle whose state machine composes reusable ADR-028
   stdlib capabilities.
2. `params-only-v1` is the low-cost default; stake and governance are explicit opt-ins.
3. The reference independently/on-chain-verifiable deployment uses `mpf-blake2b256-v1` and a
   bootstrapped SCRIPT anchor. JMT deployments are labelled off-chain-only.
4. The product exposes typed convenience queries but reuses generic state, proof, verification,
   commitment, and anchor APIs.
5. The standard console owns only the generic UI-extension host. The Cardano History bundle carries
   its compiled HTML/JavaScript/CSS and descriptor, and eligible navigation is discovered from both
   the loaded contribution and the selected chain's capabilities.
6. A lightweight client and separate CLI are independently distributable. Neither activates or
   embeds the server plugin.
7. The product adds application-semantic on-chain predicates on top of the generic MPF verifier; it
   does not implement a second authenticated-map verifier.
8. Preview deployments start fresh when a canonical preset/profile changes. No migration or
   backward-compatibility layer is required before release.
9. Plugin UI runs only in an opaque-origin sandbox with a versioned, closed, read-only bridge in v1;
   direct execution in the console DOM and arbitrary network access are not supported.
10. One bundle jar is the server/operator installation unit. Client, CLI, and on-chain artifacts
    remain separately consumable because they execute outside the server plugin runtime.
