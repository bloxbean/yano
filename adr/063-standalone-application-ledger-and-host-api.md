# ADR-063: Standalone application ledgers and a stable Yano Host API

- Status: Accepted for implementation; acceptance gates below are not yet complete
- Date: 2026-11-14
- Tracking: [Yano #245](https://github.com/bloxbean/yano/issues/245)
- Baselines inspected: Yano `c1da08405`, Yano-X `fb5e7f91`
- Delivery: two implementation milestones, **one coordinated Yano release**

## Context and decision authority

This decision incorporates the complete issue and all three follow-up comments:
[extraction and release independence](https://github.com/bloxbean/yano/issues/245#issuecomment-6870897538),
[multiple-L1 readiness](https://github.com/bloxbean/yano/issues/245#issuecomment-6870961304), and
[configuration and observer compatibility](https://github.com/bloxbean/yano/issues/245#issuecomment-6871355503).
The first comment supersedes the original issue's no-extraction non-goal. The
last comment rules out using extraction to redesign configuration or observers.

[ADR-029](https://github.com/bloxbean/yano-x/blob/main/adr/029-repository-split-analysis.md)
is historical evidence, not the current module inventory. Its leaf-tier split
has happened: Yano-X already publishes optional JVM plugins, products, clients,
and distributions. Yano still owns app-chain API, engine, storage, configuration,
Cardano observers/anchors, operations and console. Its current `core-host.md`
and AGENTS ownership rules describe that earlier boundary. This ADR changes that
boundary explicitly; it does not authorize unrelated UI/product rewrites.

The purpose is independent application-ledger releases and optional Cardano L1,
not faster Gradle configuration. Keep one engine, one consensus implementation,
one state format and Yaci's existing transport.

## Current architecture, verified against source

| Surface | Current implementation | Consequence |
| --- | --- | --- |
| Composition | `runtime/.../assembly/YanoAssembly`, `internal/RuntimeNode` | `RuntimeNode` constructs Cardano storage, transactions, serving, UTxO, ledger, chronology and sync before startup; disabling the client alone is insufficient. |
| Lifecycle | `kernel/NodeKernel`, `Subsystem`, `SubsystemContext` | Already orders initialization/start and reverse cleanup, including failed construction/start; reuse this ownership model. |
| Plugins | `NodePlugin`, `PluginContext`, `PluginRuntimeEnvironment`, `PluginProviderRegistry` | Configuration, scheduler, event bus, typed service lookup, manifests, health/metrics and bounded cleanup already exist. Do not introduce a second plugin loader. |
| App assembly | `AppChainManager`, `AppChainSubsystem` | One subsystem per chain; manager multiplexes inbound chain IDs; flat/multi-chain configuration already share `AppChainConfigParser`. |
| Transport | `AppPeerClient`, `SharedAppTransport`, `ServeSubsystem` | Dedicated outbound sessions already use Yaci handshake, keepalive, 100 and optional 103. Shared transport decorates an L1 `PeerClientFactory`; it cannot be used without L1. |
| Inbound transport | Yaci `NodeServer` / `NodeServerSession` | Extra per-connection `AgentFactory` instances are supported, but server sessions unconditionally create ChainSync, BlockFetch and TxSubmission. An explicit app-only session composition is necessary. |
| Handshake | Yaci `N2NVersionTableConstant`, `HandshakeAgent` | Existing V100 signals app capability, while tables also offer Cardano V11–15. Version negotiation does not negotiate individual protocol IDs. AppMsg has its own init/ack. |
| Identity | `AppChainConsensusProfileCommitment`, `ConsensusContext`, observer profile digest, `StateCommitmentIdentity` | Existing commitments and persisted identity checks are the place to enforce chain-wide no-L1 semantics. A process-local flag alone is unsafe. |
| L1 behavior | `L1ObservationService`, epoch observation coordinator, L1 delivery loop, anchor services | Keep Cardano-specific contracts and deterministic re-verification. External publication already follows app finality. |
| Storage | `AppLedgerStore` | Own RocksDB database; no reason to initialize Cardano chainstate in standalone mode. Preserve codecs, column families and snapshot identity. |
| Public compatibility | `Yano.appChain()/appChains()`, domain API contracts, plugin catalog contribution kinds | App types leak through published facades. Artifact moves must retain class names and transitive dependencies; deleting methods would break embedders. |
| New coupling since ADR-029 | `AppChainSubsystem` imports `runtime.chain.BlockBodyRetentionRegistry`; plugin resolver implementations are runtime-owned | The old claim of zero node-domain imports no longer holds. Move the retention *contract*, not the storage implementation, across the host boundary. |

The app API imports a small number of non-app API types (`ProtocolParamsSnapshot`,
`UtxoState`, `CostModelUtil`, `StrictEd25519`). The first three belong to optional
Cardano integration; crypto helpers are not an L1 settlement service. A source
move of the entire `runtime.appchain` package into one new jar would incorrectly
retain Cardano anchor/CCL dependencies in the deterministic core.

### Open PR conflict review

Reviewed open PR descriptions and complete changed-file lists on 2026-11-14.
No unrelated PR is a prerequisite for this work.

| PR | Potential overlap and handling |
| --- | --- |
| [#244](https://github.com/bloxbean/yano/pull/244) | Strict CCL/Yaci decoding and header validation. Preserve its independent dependency upgrade; recheck Yaci session changes against the eventual pin. Do not adopt its unreleased decoder APIs. |
| [#243](https://github.com/bloxbean/yano/pull/243) | Cross-repository release tracking only. Coordinate final dependency pins, without waiting for this umbrella. |
| [#241](https://github.com/bloxbean/yano/pull/241) | Amaru removal touches settings/build files, `YanoProducer`, property keys, configuration and TxSubsystem. Keep standalone selection localized; resolve these overlaps when rebasing, retaining both intentions. |
| [#240](https://github.com/bloxbean/yano/pull/240), [#226](https://github.com/bloxbean/yano/pull/226) | Amaru artifacts/workflows, outside the app-ledger path. No changes to those mechanisms here. |
| [#237](https://github.com/bloxbean/yano/pull/237), [#235](https://github.com/bloxbean/yano/pull/235) | Nonce/producer fixes. Keep Cardano production paths intact and rerun integrated regressions after rebase. |
| [#233](https://github.com/bloxbean/yano/pull/233) | Duplicate synced block handling. Do not alter body ingestion for standalone. |
| [#229](https://github.com/bloxbean/yano/pull/229) | Proposed ADR-061 block validation modes; standalone has no Cardano body-validation pipeline. |
| [#206](https://github.com/bloxbean/yano/pull/206) | Produced header versions; outside app consensus. Preserve protocol-version behavior. |

## Decisions

### D1. Explicit operating mode; existing configuration stays authoritative

Add `yano.mode=cardano|app-ledger`, defaulting to `cardano`. This selects runtime
composition, not a second application consensus engine. Standalone requires
`yano.app-chain.enabled=true` and dedicated transport. Continue using
`yano.app-chain.*` in Yano and Yano-X, including flat settings, `chains[i]`, peer
addresses, identity, paths, observers, state machines, effects and plugin settings.
No new parallel `yano-x.*` configuration schema or migration script.

Choose the mode before constructing Cardano chainstate, UTxO/ledger state,
transaction services, genesis/epoch state, sync, producer, observers or anchors.
Use the existing kernel, configuration parser, plugin catalog and app manager.
Cardano queries/capabilities are explicitly unavailable; never synthesize a
Cardano tip, UTxO set, epoch or protocol parameters. HTTP routes must report
unavailability rather than crash or report fabricated L1 readiness.

Standalone rejects selected Cardano-only features with the offending property
name: shared transport, producer/bootstrap, positive L1/epoch stability depth,
Cardano observers, anchoring, L1-final effects gates and L1-dependent retention.
Generic observations, policies, workflows, deterministic effects and proof/query
APIs remain available. Validate selected provider requirements as well as built-in
settings; do not reject all generic providers because Cardano is absent.

### D2. App-only sessions reuse Yaci, with explicit network separation

Yaci retains handshake, multiplexer, reconnect machinery and protocols 100/103.
The needed Yaci change is additive session composition: retain the current
Cardano agent set by default, allow a session with only handshake, keepalive,
and supplied app agent factories. Reject duplicate protocol IDs and failed
factory creation rather than publishing a partially initialized app session.
Do not implement a second Netty server or copy mini-protocol implementations.

Reserve an explicit app-only handshake version distinct from the existing
hybrid V100 (proposed V101, subject to Yaci verification). Standalone offers
only that version. It must not fall back to V100 or Cardano V11–15. Keep the
existing N2N version-data encoding and explicit network-magic matching; require
an app-network magic and reject known Cardano network identities. Peers with
wrong mode/version/network fail negotiation with an actionable diagnostic.
This is capability/network separation, not cryptographic peer authentication;
existing signed app messages and consensus membership remain authoritative.

Protocol 103 is installed for sequencing chains before negotiation; protocol
100 retains its init/ack and chain allowlist. Keepalive and reconnect operate
without ChainSync. The session tests must inspect actual registered agents and
exercise real TCP handshake, gossip, catch-up and reconnect. Offering a version
number alone is not evidence that 100/103 work.

### D3. No-L1 semantics are immutable chain identity

Introduce an additive chain operating-profile selection, derived from the host
mode and checked for consistency on every hosted chain. Keep the existing hybrid
profile bytes/digests unchanged. For standalone, domain-separate the existing
consensus-context digest with the versioned standalone profile. Persist and
verify that profile before admitting messages or serving history. Absence of a
profile on an existing database means legacy Cardano semantics, never permission
to reinterpret history. A fresh standalone chain requires explicit genesis
identity; restarting it must reproduce the same identity and profile.

Standalone proposals **and finalized catch-up blocks** require `l1Slot=0` and
empty `l1BlockHash`; reject reserved Cardano observation inputs. Do not just
turn off a validator and thereby accept arbitrary L1 references. Bind profile
checks into proposal, votes/certificates, catch-up, restart and snapshot restore.
Retain AppBlock encoding, hashes, certificate formats, existing consensus and
state commitment formats. No live conversion of an L1-linked chain is supported.

### D4. Extract by dependency, retaining public binary names

Target artifact responsibilities (names finalized in artifact inventories):

| Owner / module | Responsibility / allowed dependencies |
| --- | --- |
| Yaci `yaci-core` | Transport and protocols 100/103; no dependency on Yano or Yano-X. |
| Yano `yano-host-api` | Small infrastructure hosting contracts, independent version/API level; no app-ledger implementation dependency. |
| Yano node libraries/runtime | Cardano node implementation; implements host capabilities. No dependency on Yano-X engine implementation. |
| Yano-X app API/core | Existing app interfaces, consensus, state execution, RocksDB ledger, evidence, generic observations/effects, proofs. No Yano implementation or Cardano settlement SDK dependency. |
| Yano-X Yaci transport | App peer/session wiring using Yaci; no Yano node runtime. |
| Yano-X Cardano integration | Existing observer contracts/implementations, L1 delivery/view, anchor/evaluation/transaction adapters and on-chain artifacts. Optional for standalone. |
| Yano-X Yano adapter | Hosts the framework through Host API, manifest/catalog lifecycle, optional Cardano capabilities; no node implementation imports. |
| Integration/distribution | Existing Yano executable and Yano-X integrated JVM ZIP assemble compatible node, adapter and framework artifacts. Also a standalone Yano-X JVM entry point without Cardano runtime. |

The distribution depends on both sides; neither implementation depends on the
other implementation. Do not require a sibling checkout, composite build,
source-generation input, matching repository revision or per-X-release Yano ZIP.
Use exact Maven artifacts for coordinated development in an isolated staging
repository; no public release of Phase 1 or an unfinished Host API is needed.

Preserve `org.yanoproject.api.appchain.*`, observer signatures and provider IDs,
ServiceLoader resource names, `org.yanoproject.runtime.appchain.*` binary names
where feasible. Package names need not change when artifact ownership changes.
Do not add an `org.yanoproject.<product-name>` namespace in Yano.

Acyclic API extraction requires separating foundational node contracts from
legacy aggregate API packaging. The initial extraction retained `yano-core-api`
as a compatibility aggregate. The preview cleanup decision below supersedes that
packaging choice: consumers declare the concrete API owners directly. The small host SPI
must not become that aggregate. Optional Cardano observer APIs may depend on
Cardano model contracts, including the existing Yaci `Block` parameter. The
standalone core must not gain those dependencies through an aggregate POM.

Likewise retain `Yano.appChain()/appChains()` through the integration-facing
facade/API artifact or compatibility adapter; do not silently delete signatures.
Audit `ContributionKind`, domain API/read-model contracts and testkit POMs with
the same rule. Compile/run a plugin built against the prior API without recompiling
it. Document any unavoidable artifact-level changes and non-Maven classpath needs.

### D5. Minimal Host API; reuse existing lifecycle and providers

Reuse `NodePlugin` initialization/start/stop/close, `PluginContext` configuration,
events, scheduler and service discovery, and existing operations health/metrics
contracts. Do not add a competing extension loader, scheduler, event bus or kernel.
The adapter checks a monotonic host API level and explicit optional capabilities
before activation. Missing capabilities produce a startup diagnostic.

Only these new or promoted contracts are justified:

1. **N2N registration capability:** currently absent from PluginContext. Register
   inbound and outbound per-connection Yaci agent factories and supported
   handshake versions before sessions are constructed. Seal registrations before
   starting listeners/dials; reject duplicate IDs, late registration and invalid
   mode/version combinations. Supply connection lifecycle/negotiated-version
   information for shared-session binding and detach on disconnect. Preserve
   `SharedAppTransport` behavior through this seam, not a new transport.
2. **Optional Cardano capability bundle:** expose already-used chain query,
   UTxO access, tx submission/evaluation, protocol params, events and epoch view.
   Use existing interfaces instead of new generic L1 facades. Discovery is
   optional; standalone never creates fake implementations.
3. **Block-body retention lease:** promote the narrow existing registration
   operations currently exposed as a concrete runtime nested type. Keep storage
   ownership in Yano; release the lease during existing subsystem cleanup.
4. **Provider lookup contract, if needed by extraction:** the minimal operations
   already used from `PluginProviderRegistry`, including cleanup ownership.
   Implement with the existing catalog/registry. Do not bypass manifest checks
   with new raw ServiceLoader discovery.

Lifecycle ordering: validate config/API/capabilities; initialize extensions and
register protocols; seal registration; open/verify app stores; start app services;
publish listeners and outbound sessions. On failure, release all acquired
resources in reverse order, including partially started extensions. On stop,
fence callbacks, stop ingress/workers, then close stores and plugin loaders.
Keep existing restart generation fences and test shutdown during connection and
proposal work. API registration must not depend on L1 sync being live.

### D6. Release independence is a tested contract

Version the host artifact separately from Yano distributions and declare its
minimum supported API level. The first frozen host contract is the oldest
supported version for this feature; pre-feature Yano hosts are not claimed to
support it. Preserve existing plugin `yanoApi` compatibility checks and add host
capability checks rather than interpreting the Yano-X version as a Yano version.

Compile the adapter against the oldest supported immutable Host API artifact.
Run the same newer framework/adapter on that oldest compatible host distribution
and the current host. Include a standalone distribution test with no Yano runtime
jar. Reject an unsupported API level before opening databases/listeners. Assert
resolved module versions and no duplicate API classes. An intentionally frozen
baseline fixture can exercise development before publication, but cannot be
reported as testing an old published host; pin the final artifact digest before
release qualification.

Ordinary X fixes/features can release using that API with a process restart and
without a Yano release. New host capabilities may raise the minimum. Consensus
changes may still require app-node coordination; host compatibility does not
imply consensus compatibility. No hot swapping or arbitrary live JAR replacement.

### D7. Stable finalized commitment, without a settlement redesign

Expose an immutable version-1 commitment/evidence value after finalization with
chain ID, genesis/commitment-profile identity, consensus-context digest, height,
block hash and state root. Specify canonical, length-delimited binary serialization,
fixed integer byte order, domain `yano-app-finalized-commitment-v1` and a digest
algorithm; reject malformed lengths/unknown versions. Golden vectors cover this
representation and evidence verification. This is an external envelope over
existing AppBlock identity, not a new block hash or database format.

Use the existing finalized-block subscription and worker/effect mechanisms.
External publication is asynchronous and idempotent; publication failure never
changes app finality. Destination records are identified by network plus
adapter/config identity plus app chain/genesis and commitment digest, so two
independent future destinations cannot overwrite one another's receipt/status.
Keep any added receipt representation outside canonical app history. Results
enter app state only through existing deterministic transitions.

This is a contract for future publication adapters, not a new generic publisher,
outbox or receipt store in this change. A finalized-block callback alone is not a
durable delivery guarantee: future adapters must recover from persisted finalized
history using destination-specific acknowledged progress or a durable outbox,
including a crash after finality is persisted but before work is enqueued. Their
acceptance tests must exercise that crash window and replay idempotently. Existing
Cardano anchor services retain their periodic reconciliation of the persisted app
ledger tip against their persisted anchor frontier/pending transaction state.

Keep Cardano anchors/receipts and observers operational. Do not add other L1
adapters, a universal observer interface, bridge contracts, or production fanout.
A signed finality certificate/MPF proof attests to the app trust model. Posting a
root provides anchoring/timestamp evidence, not independently enforced settlement,
asset custody, withdrawals or a trustless bridge.

## Implementation and migration milestones

1. **Architecture commit:** this ADR, source/conflict inventory and explicit gates.
2. **Phase 1 transport spike:** minimal Yaci composition/version support and real
   app-only session tests; coordinated Yaci PR if required. Retain old constructors.
3. **Phase 1 runtime:** additive mode, early composition selection, immutable
   profile checks, standalone three-node consensus/catch-up/restart tests, minimal
   configuration/example and precise unavailable-L1 diagnostics.
4. **Phase 2 API boundary:** freeze the smallest contracts after actual usage,
   retain plugin/facade compatibility and add classpath/ABI gates.
5. **Phase 2 moves:** mechanical source/test/resource moves separate from wiring;
   split Cardano dependencies, switch integrated distribution to the adapter,
   add standalone X packaging and exact artifact inventories/BOMs.
6. **Phase 2 independence:** staged artifacts, baseline-host compatibility tests,
   unchanged configuration/scripts, plugin binary test and packaged cluster tests.
7. **One final qualification:** integrate both phases and dependency pins, then
   run one comprehensive Yano release cycle covering both modes. Record versions,
   hashes and reports. Corresponding compatible X artifacts accompany that release.

Yano and Yano-X PRs start from their respective `main`, remain draft until their
required gates pass, and link #245 and each other. Do not merge a distribution
that references unavailable artifacts. Stage Host API first, X artifacts second,
integrated distributions last; publish the coordinated set only after qualification.
No tags, release publication or unrelated PR merges are part of this implementation.

Existing operators retain configuration, scripts, plugin IDs and persisted state.
Moving artifacts must not migrate existing app databases or regenerate genesis.
Standalone operators use a fresh app database and explicit new network/chain
identity. Mode/profile mismatch on existing data fails closed.

## Validation matrix and acceptance evidence

Unmarked gates are planned, not claims of completed validation.

| Gate | Required evidence |
| --- | --- |
| Configuration | Old flat and multi-chain fixtures parse identically; same defaults/provider IDs; strict validation accepts only additive keys; bad modes and incompatible settings rejected before resources. |
| Identity/storage | Legacy hybrid digest/block/cert golden vectors unchanged; standalone digest differs; mode/genesis mismatch rejected on restart, catch-up and snapshot restore; apply/replay/root parity. |
| Transport | Two real TCP peers negotiate app-only version; exact agents 100/103 plus keepalive, no L1 agents; wrong mode/magic rejected; reconnect and init/ack recovered. |
| Consensus | Three standalone nodes with no Cardano process/upstream/chainstate independently finalize identical blocks/certificates/roots; valid message propagation and invalid proposal rejection. |
| Catch-up | Late join, disconnect, process restart and protocol-103 range retrieval verify certified history/state; bounded shutdown and no leaked threads/stores. |
| No-L1 policy | Nonzero refs and Cardano observations rejected in both proposal and catch-up; generic observation/effects/proof paths usable; L1-required plugins fail explicitly. |
| Hybrid | Existing consensus, shared/dedicated transport, L1 observers/epoch views, follower verification, metadata/script anchors, rollback behavior and deployment scripts unchanged. |
| Dependency | X core compiles without Yano implementation or L1 settlement SDK; standalone closure has no Cardano runtime; no cycles or duplicate classes; module/version inventory gates. |
| API/plugin | Prior compiled plugin loads unchanged; ServiceLoader/manifests work; facade signatures retained; lifecycle/N2N registration rejected at wrong phase; missing capability diagnostics. |
| Independent release | New X against oldest frozen/published Host API and host, plus latest host; unsupported host rejected before startup; normal process restart upgrade. |
| Distribution | Existing Yano JVM/native packaging and CLI contracts; X JVM-only/artifact inventory/distribution gates; packaged standalone and integrated cluster runs. |
| Final release | Exactly one comprehensive release-QA cycle after both phases, with focused unit/integration runs at each milestone; no intermediate release. |

Use existing JUnit fixtures and cluster harness invariants rather than cloning a
second QA framework. Historical low-level Yano app-chain scripts must be checked
against the post-leaf-split paths before use. Use fresh temporary test state,
never retained devnet/preprod cluster directories or owner keys.

## Risks and rejected alternatives

- **Handshake assumption:** V100 means app support, not app-only composition.
  Reject reusing ordinary Cardano tables or null chainstate as a hidden mode flag.
- **Packaging cycle:** keeping app SPI in an aggregate consumed by X core prevents
  independent API releases. Separate contracts and compatibility aggregates;
  verify POM graphs, not just Java imports.
- **False L1 independence:** moving anchor classes with the engine leaves QuickTx,
  Plutus/CCL settlement and host runtime in the core. Split by actual dependency.
- **Binary compatibility:** package renaming, deleting facade methods, manifest
  identifier changes or duplicated SPI jars are not acceptable shortcuts.
- **Consensus drift:** preserve hybrid digest bytes; immutable standalone identity
  prevents a local flag from weakening another member's verification policy.
- **Lifecycle races:** preserve existing connection epochs, generation fences and
  reverse cleanup; test failures between acquisition and publication.
- **Speculative framework:** no generic universal L1 observer API, new consensus,
  new transport, duplicate kernel or scheduler. Promote only contracts required
  by actual extraction callers and explain each in D5.
- **Release coupling:** an X bundle must not require rebuilding the node or matching
  its version string. The baseline compatibility test is a release gate.
- **Scope creep:** console restyling, unrelated build optimization, additional L1s,
  public network sync improvements and hot reload are explicitly excluded.

## Development evidence and concrete Host API seam

Coordinated drafts: [Yano #246](https://github.com/bloxbean/yano/pull/246),
[Yaci #245](https://github.com/bloxbean/yaci/pull/245), and
[Yano-X #102](https://github.com/bloxbean/yano-x/pull/102).
Yaci's application protocols are on `next`, not `main`; its transport prerequisite
therefore targets `next`. Both Yano and Yano-X feature branches start from `main`.

Phase 1 focused evidence (development artifacts, no release):

- App-only Yaci sessions register exactly keepalive/100/103 and reject wrong
  magic, hybrid/Cardano negotiation, duplicate agents and failing factories.
- Three runtime nodes finalize follower submissions, fetch certified history over
  103, verify evidence, stop/start and reopen retained state without Cardano storage.
  Signed L1-linked proposals and certified catch-up blocks are rejected.
- The focused configuration gate passes 419 tests; selected hybrid regressions
  pass 61 tests; plugin regressions report 242 tests with one skip and no failures.
- The wider app-chain gate reports **570 tests, two skipped, no failures**.
  The adapter checks pass 24 tests. These counts describe their respective runs,
  not unique tests to add together.
- A packaged three-JVM cluster passes follower submission, late joining, committed
  block/root agreement, graceful restart/catch-up, HTTP 503 on unavailable L1
  operations and absence of L1 chainstate. This caught and fixed missing maintenance
  gate wiring that the in-process runtime tests could not expose.
- `qa/app-ledger/run.py` retains JUnit XML/logs, fails on missing suites, timeouts,
  compiler/test failures or source changes during a run, and emits JSON/Markdown.
  The packaged harness reuses existing QA helpers and records artifact SHA-256.

The first Host API module retains binary names for the existing provider registry,
peer-client factory/endpoint, digest mode and block-body boundary. The shared
lifecycle failure-ordering utility moves with those contracts so the extracted
core need not depend on `yano-runtime` for cleanup. A legacy `runtime.*` Java name
here denotes an API-owned contract/utility, not a node implementation dependency.
`yano-core-api` re-exports the host artifact; manually assembled non-Maven
classpaths must include the new JAR. No class is embedded twice.

`HostApi` is obtained through `PluginContext.getService`, published before plugin
initialization, and exposes an independent major/API-level check, the existing
provider registry, and `N2NExtensionRegistry`. The latter accepts inbound Yaci
factories with explicit IDs and decorators of the existing `PeerClientFactory`.
It seals before listeners or dials are composed and rejects duplicate, late,
null or mismatched registrations. Shared outbound sessions are explicitly absent
in standalone mode; its existing dedicated Yaci app-peer clients remain unchanged.
`BlockBodyRetentionLease` promotes only the already-used update/read/close methods.
These additions supply missing capabilities; they do not introduce a second plugin
loader, scheduler, consensus engine or connection runtime.

Host API extraction checks pass one version-contract test and 59 selected runtime
registration, plugin-lifecycle, shared-transport and standalone tests. The Maven
artifact has its own `hostApiVersion`; API major 1 / level 1 is the initial contract.
This is not yet a claim of a frozen or previously published baseline. Core/API
moves, optional Cardano isolation, prior compiled plugin and oldest-host matrix,
packaging/tooling compatibility, and final coordinated qualification remain open.

The portable finalized-commitment envelope is implemented in X's existing
`yano-x-proof-contracts` module, with canonical-byte/digest golden vectors and
separate network/adapter/configuration receipt keys. It adds no L1 publisher.

## Extraction milestone: API ownership and engine/storage boundary

The first mechanical extraction uses these concrete artifacts:

| Artifact | Source owner and responsibility |
| --- | --- |
| `yano-host-api` | Yano; separately versioned host registration/provider/peer contracts. |
| `yano-node-api` | Yano; existing node/plugin DTOs and interfaces, preserving binary names. |
| `yano-core-api` | Yano; compatibility aggregate of node API and X application API. No duplicate definitions. |
| `yano-x-appchain-api` | X `framework/api`; existing app-chain contracts, domain SPI, events and wire resources. |
| `yano-x-appchain-config` | X `framework/config`; existing parser/registry/semantic validation. |
| `yano-appchain-config` | Yano; compatibility aggregate retaining the original Maven coordinate. |
| `yano-x-appchain-core` | X `framework/core`; existing consensus engine, ledger storage, authenticated state, observation/effect kernels, snapshots and OrderedLog. |

The node/API split is necessary to avoid an artifact cycle: application API
signatures already expose node interfaces such as `UtxoState`,
`TxEvaluationGateway`, protocol parameter snapshots and existing plugin types.
Removing those signatures would break extensions. The new foundational artifact
contains those unchanged contracts, not node assembly, chain sync or ledger-state
implementations. Existing Cardano model/codec/crypto dependencies are retained;
this is not a claim that the legacy observer API is Cardano-type-free.

Fifty-three existing engine/storage/helper source files move to X without package
renaming. The one reverse helper call (effect executor ownership fingerprint)
moves into `EffectRuntime`; the old subsystem helper delegates to the identical
algorithm. No storage schema, consensus encoding or hash domain changes accompany
this move. Cardano integration, transport assembly and the large subsystem remain
in Yano at this milestone. Their extraction and plugin activation are **not yet
complete**; an independent X standalone distribution is not yet delivered.

Yano consumes exact Maven artifacts, with an optional explicit staging repository
for this coordinated draft. Within a Yano build, published node/host API
coordinates substitute the corresponding local API projects so the runtime has
one class definition. This is an intra-repository substitution, not a sibling
source/composite build. X builds against externally staged/published node/host
artifacts and never invokes a Yano checkout.

Boundary checks resolve X's complete production core closure and reject Yano
implementation artifacts, QuickTx and julc. Reports retain artifact hashes.
Yano's compatibility tests verify unique class/resource ownership and load a
fixed plugin JAR compiled against the local `0.1.0-pre19` core API artifact.
The fixture's source, compiler, baseline hash and bytecode hash are committed;
it exercises old config/service calls and two lifecycle cycles. It is not rebuilt
by the test, and is not claimed as a remotely verified published baseline.

The extracted X API/config/core suites currently pass 214/31/12 tests. Yano's
standalone three-node test and old plugin bytecode test also pass with the
extracted core artifact. The initial unpublished Host API remains a candidate;
freezing its immutable compatibility baseline and running the packaged
oldest/current-host matrix are still required before completion of Phase 2.

The next mechanical step moves the four existing dedicated/shared Yaci transport
wiring classes to the same X artifact; protocols themselves remain in Yaci.
`AppChainGateway` gains a default, read-only finalized-commitment export method,
backed by the versioned X proof contract. Existing implementations remain binary
compatible; unavailable legacy identity/history returns empty rather than inventing
an identity. Three-node tests compare exported commitments after catch-up.

The host regression gate additionally runs japicmp against the pinned pre19 API
and compares the combined owning API artifacts. It rejects binary/source
incompatibilities, validates the baseline checksum, and records comparator/API
hashes. A temporary JAR with `NodePlugin` removed was rejected. X's boundary gate
also rejected a deliberately injected transitive node-runtime dependency. These
negative probes establish that the guards can fail, rather than merely reporting
successful builds. The initial complete post-extraction run passed 570 app-chain
cases (two skipped), 419 configuration/API cases, 61 hybrid cases and 245 plugin
cases (one skipped); a missing named retention test correctly failed the host gate
and is addressed by the new lease/locking test. Retain that failed report as
harness evidence, not as a passing aggregate run.

## Review refinements before freezing the host contract

Independent review found that the first extraction's API aggregate still pulled
Plutus into the core and that X's published strict Host API version prevented
newer compatible hosts from resolving. Correct both before calling the boundary
stable: narrow `node-api` to existing generic plugin/config/crypto/peer contracts;
keep Cardano node DTO/query APIs in `cardano-api`; make `host-api` depend on that
generic foundation, not the reverse. Move the unchanged legacy effect factory
and its Cardano context together with the anchor datum and protocol-parameter
codec into X `framework/cardano-api`. Integrated compatibility aggregates reexport
both APIs. Core depends only on the generic foundation. Publish an ordinary
Host API dependency; exact oldest-version selection belongs in the test matrix.

Preserve public runtime ABI as well as the core API. Move only the existing
`Subsystem`, `SubsystemHealth`, `SubsystemContext`, `Schedulers` and
`ServiceRegistry` compatibility contracts into the generic foundation, retaining
all Java names. `NodeKernel`, its state machine and node storage implementations
remain runtime-owned. This avoids renaming the large subsystem or its public
nested records and avoids a second lifecycle interface.

A new, narrow lifecycle registration point is justified by existing ordering:
NodePlugin.start runs before Cardano storage/bootstrap, while application ledgers
must start after sync assembly and before read-model/domain publication. Register
existing Subsystem instances during host initialization (the framework assembles
after all NodePlugin.init callbacks); seal registration before runtime startup
and adapt them into the current pre-publication kernel stages.
Registration transfers close ownership only on success. Rejected registration
leaves ownership with the caller. The registry retains accepted services even
when later initialization fails, never runs callbacks under its monitor, and
waits for concurrent close completion. Failed starts clean the failing service
as well as prior services. Normal stop/close drains services before plugin
providers/loaders. No new kernel, loader, scheduler or early database opening is
introduced. Keep plugins.enabled=false app-chain behavior through required
platform activation without enabling optional user plugins.

The existing `BlockBodyRetentionRegistry` also moves unchanged to the generic
foundation. It coordinates generic consumer boundaries and has no storage
implementation dependency. This preserves the public nested `Registration` type,
`wireL1Chain` descriptor and the exact lock used by the runtime-owned pruner.
A second frozen pre19 bytecode comparison covers public runtime app-chain classes,
including nested records, in addition to the six-artifact core API comparison.

Only the kernel owns lifecycle transitions after assembly. Registry cleanup runs
inside its final resource stage, never concurrently with kernel start/stop/close.
The compatibility two-argument Host API constructor rejects lifecycle registration
because it has no assembly owner. Plugin initialization and transactional start
failures drain registered services before plugin teardown through a narrow
existing-manager overload using `Runnable`; no new lifecycle SPI is introduced.

The resolved dependency check identified unused SDK paths through Yaci core/helper
and the MPF library. Exclude CCL core from the generic Yaci dependency edges and
Plutus from MPF/MPF-RocksDB edges; retain their exact upstream implementations,
crypto/common/CBOR dependencies and native proof-wire methods. The optional MPF
Plutus conversion is not used by the framework. Integrated Cardano APIs restore
SDK dependencies explicitly. A real loopback test with SDK classes absent covers
handshake 101, signed protocol-100 messages, protocol-103 persisted block bytes,
and keepalive; existing state/snapshot tests run on that same lean classpath.

Move observer profile/network identity encoding once into `ObserverConsensusProfile`.
It accepts existing observer identities through a JDK function and retains the
existing domains, ordering and byte encodings, including the no-observer digest.
Cardano provider resolution remains in the existing observation service. This
avoids loading a settlement adapter simply to calculate standalone chain identity.
Standalone custom sequencers now receive an explicit unsupported-L1 diagnostic
from `currentL1Slot`; the existing Cardano unsynced-slot behavior remains zero.

Refinement validation uses framework `0.1.0-245.4` against independently staged
foundation `0.1.0-245.3` and Host API `1.0.0-245.3`. X passes 202 generic API,
15 optional Cardano API, 31 configuration, 60 core/transport and six commitment
contract tests, plus its resolved dependency and artifact-inventory checks.
Yano's bounded gates pass: consensus 571 (two skips), host 66, standalone seven,
hybrid 61, configuration 419, plugins 247 (one skip), and application adapter 24.
These overlapping gates are not an additive test total. Each report records an
unchanged source fingerprint; the host gate includes both frozen pre19 ABI
comparisons and the unchanged precompiled plugin. The packaged JVM build passes.
This remains incremental evidence: optional Cardano attachment extraction,
manifested framework activation, independent standalone X packaging and the
frozen oldest/current host matrix are still incomplete. Do not mark Phase 2 or
final release qualification complete based on these results.

## Phase 2 runtime extraction refinement

The public signature audit supports a smaller shape than a new attachment SPI
plus compatibility façade: move the existing `AppChainSubsystem` to the optional
Cardano module as a specialization of one extracted `AppLedgerRuntime`. Keep all
four public constructors, `AppChainSubsystem.AnchorFeeParams` (including both
constructors), and existing Cardano wiring descriptors on that exact class.
Inherited gateway/lifecycle methods keep normal JVM linkage. Move the manager
implementation once into `AppLedgerManager`; preserve the old `AppChainManager`
constructor on its Cardano specialization. Existing gateway return types already
use `AppChainGateway`, so they need no redesign. This supersedes the earlier
suggestion of a separate attachment interface/object; no new public integration
SPI is needed.

Use finite protected hooks only at existing Cardano call sites. Preserve observer
identity validation before journal writes, observers before governance, anchors
after governance, delivery after sink/effect construction, and teardown barriers
before ledger closure. Keep the existing generation fences, workers, engine and
store owners in the shared runtime. Stable reference, reference verification,
anchor gates and gateway defaults retain existing no-L1 behavior. The standalone
runtime must reject configured L1 features rather than invent observations or
clocks. Constructor-time rotating-sequencer selection must use a supplied resolver
that does not read uninitialized subclass fields. The legacy effect factory and
Cardano effect context move with the optional specialization; generic effects,
webhooks and bridge diffusion remain common.

Activation continues through the existing manifested NodePlugin and shared
plugin service registry. A host-owned required embedded bundle selection must
activate the framework even when optional plugins are disabled, without scanning
plugin directories or constructing unrelated legacy providers. Requiredness is
assembly policy, not a new manifest field. Yano generates its existing native
catalog metadata from published framework artifacts; X remains JVM-only.

Independent X distributions must compose framework-free host artifacts with
their selected framework version. An older integrated uber-JAR cannot have its
embedded classes replaced by a parent-first plugin-directory overlay. The matrix
must exercise actual oldest/current host artifacts and packaged startup, not
only dependency resolution. Keep the integrated deployment unchanged and do not
add sibling checkout/build dependencies or a second runtime/plugin loader.

Validation extends the existing ABI and precompiled-plugin harness, including
legacy constructor/nested-record linkage when inheritance changes. Run focused
L1 delivery/observer/anchor/lifecycle tests per extraction slice, then physically
exclude Cardano artifacts for standalone startup/consensus/100/103/restart tests.
One final comprehensive Yano qualification still follows completion of both phases.


## Runtime ownership and manifested activation milestone

The subsystem/manager extraction now supersedes the earlier core-only milestone.
`framework/core` owns `AppLedgerRuntime`, `AppLedgerManager`, the existing engine,
store, query workers, observation/effect execution and validator resolver.
`framework/cardano` owns the exact legacy `AppChainSubsystem` and
`AppChainManager` names, their public constructors and nested fee record, plus
Cardano delivery, observer and anchor implementations. The old Yano on-chain
coordinate reexports `yano-x-appchain-anchor-onchain`; checked validator bytes,
Java/Aiken authoring sources and conformance tests are owned by
`framework/cardano-onchain`. No validator regeneration or persisted format change
is involved. Host-only epoch and transaction-builder implementations stay in
Yano. Remove unused host production MPF/JMT/julc dependencies so Maven nearest-wins
resolution cannot constrain a later framework release through obsolete host pins.

Activation reuses schema-v1 `NodePlugin`, `PluginContext` services and the existing
Host API. `framework/host-adapter` is a descriptor-free shared implementation;
`framework/standalone` and `framework/cardano` are mutually exclusive manifested
roots for `org.yanoproject.x.app-ledger`. They publish ordinary Maven libraries,
without the existing product bundle task that strips classes against a particular
Yano ZIP. This small standalone root keeps provider class, SPI declaration and
manifest together for the existing artifact scanner; there is no second loader.

The host supplies copied `YanoConfig` and immutable `RuntimeOptions` snapshots.
One optional, concrete `CardanoRuntimeServices` record groups only callbacks
already used by the existing assembly: submission, UTXOs, canonical chain reads,
body-retention registration, evaluation and the two existing parameter suppliers.
This is an adapter-owned data carrier, not a new generic L1 interface. Preserve its
constructor as part of the embedding compatibility surface. The two parameter
suppliers stay distinct because linear fees and script pricing have different
fallback behavior. Epoch-state wiring retains its existing later assembly point.
The plugin registers its concrete assembly entry point through the existing
`PluginContext` service registry during `init()`. After every plugin has initialized
successfully, the host invokes `assemble()` before sealing the N2N and lifecycle
registries. This preserves the original ordering: user state-machine, signer and
sequencer factories may rely on their own plugin's completed initialization.
The assembly method constructs one existing manager, registers N2N agents and
decorators, transfers lifecycle ownership exactly once, and returns that manager
for the existing public gateway facade. No new interface, service-registry phase,
or plugin start/stop runtime is introduced. Provider callbacks execute outside
the assembly monitor. Failed construction or rejected ownership closes the same
chain resources in reverse order. A failure in any plugin's initialization prevents
factory construction entirely. The preliminary unpublished host `.4` embedding
candidate is superseded by host `.5`; the Host API `.4` interface stays unchanged.

Required embedded roots are host assembly policy, not a manifest schema change.
They activate even when optional plugins are disabled, including restrictive
allow/deny lists. Disabled mode never scans directory artifacts or constructs or
resolves unrelated optional providers. Required SPI entries and artifact origins
are still checked. Library mode without an aggregate index uses qualified
manifests and the existing artifact scanner; packaged/native mode keeps its
exactly-one-index requirement. Integrated standalone deployments retain the
optional Cardano specialization without L1 wiring so legacy context-aware effect
factories continue to work. The standalone root uses the SDK-independent core.

Runtime extraction validation includes a frozen pre19 client JAR compiled only
against checksum-verified original artifacts. Its unchanged bytecode exercises
old constructors, nested fee records, Cardano wiring, inherited gateway/protocol
methods, finality and reopen. Both public API comparisons and this runtime fixture
pass against candidate framework `0.1.0-245.5`. The lean X test executes real
startup, consensus, query-worker access, restart and database reopen with Cardano
runtime/SDK classes absent. Cleanup-hook failure tests cover concurrent and repeat
close, self-close rejection and reopening after failed teardown. Candidate `.6`
adds manifested activation; integrated packaging and the oldest-host matrix must
pass before declaring Phase 2 complete.

The first supported Host API baseline must be an immutable artifact with these
capabilities; pre19 is only a compatibility fixture and cannot be represented as
a Host API host. A QA-only thin packaged host fixture will exercise the ordinary
library embedding path against baseline/current host artifacts and one selected
X artifact set. It must exclude embedded old framework code, retain original
fixture bytecode, verify class/resource ownership and record resolved hashes.
The integrated Quarkus deployment remains the existing default. Do not overlay
new framework classes onto its parent-first uber-JAR. One comprehensive Yano
release-qualification cycle follows integration and these incremental gates;
there is no intermediate Yano release or public candidate publication.


Open-PR review refreshed during activation integration: #244 changes the ledger
CBOR reader and header validation; #237/#235 change nonce paths; #233 changes body
fetch/application; #206 changes producer header versions. Those implementation
areas are untouched by this extraction. #241 (Amaru removal) overlaps build and
application configuration and must apply its `YanoPropertyKeys` edits to the new
`node-api` owner. #240 and the #243 tracking plan remain independent. No unrelated
PR is a prerequisite and none was awaited.


## Completed extraction development gates

The final implementation supersedes the earlier partial-milestone status above.
X candidate `.9` owns the complete framework runtime and optional Cardano adapter;
Yano host `.5` consumes it through deferred manifested activation and the unchanged
Host API `1.0.0-245.4`. X compiles against node API `.4`. All seven incremental
Yano gates pass, including 573 consensus tests (two existing skips), 419
configuration tests, both pre19 ABI checks and unchanged compiled clients.
The integrated three-process standalone package passes finality/catch-up/restart.

The retained supported-host fixture passes both standalone and Cardano-construction
profiles with X `.9` and `.8` on identical host/API bytes. It was compiled with
`.9` and is never rebuilt by an ordinary matrix run. The first supported host is
also the current host; no older public Host API release is invented. These are
unpublished development candidates. The Cardano construction profile does not
replace live observer/anchor and L1 regression qualification.

Available public dependency pins and one comprehensive coordinated Yano release
qualification remain release gates. No intermediate release is planned or made.

X tooling regression tests (262) and the full `distributionCheck` pass against the
matching Yano `.5` JVM ZIP. Extraction exposed non-transitive consumers of old
aggregate coordinates and duplicate local/published framework owners. Tooling now
depends on its actual framework owners; an inventory-driven, same-repository
source-build rule selects those projects for all requested versions. A gate
checks older-host requests, single class ownership and normal published Maven
metadata. It never substitutes host modules or sibling source builds, and the
external compatibility matrix remains artifact-only. The integrated ZIP is not
an upgrade overlay: independent framework selection uses ordinary thin Maven
embedding; the existing integrated distribution remains a bundled release.


## Preview cleanup: remove the redundant core API aggregate

After reviewing the extracted boundaries, the project owner chose to remove
`yano-core-api` before this coordinated preview release. It contains no production
classes; retaining its transitive exports obscures the actual dependencies. This
supersedes the aggregate-retention statements in the historical milestones above.
Keep the descriptive `yano-cardano-api` name; do not rename it to `core-api`.

The concrete boundaries remain:

| Consumer | Direct API dependency |
| --- | --- |
| Generic X application API and core | `yano-node-api` (directly or through the X API) |
| X host adapter | `yano-host-api`, which exposes the shared node contracts |
| Optional X Cardano integration | `yano-cardano-api` and the applicable Host API contracts |
| Cardano node implementation | `yano-cardano-api`, plus concrete X API artifacts where used |
| Integrated Yano/X distribution | Yano runtime and selected X artifacts; the bundled JVM ZIP matches its Yano runtime version |

`PluginContext`, `StorageFilter`, `UtxoFilterContext` and existing configuration
contracts stay in `node-api`. Their existing signatures connect them; moving
only the UTxO context to `cardano-api` would introduce a dependency cycle. A new
filter SPI or configuration split is unnecessary for deleting an empty aggregate.
The shared foundation is therefore not claimed to be free of all Cardano-related
information. Cardano-facing state, event and transaction contracts remain in
`cardano-api`; the generic X implementation still has no Yano implementation or
Cardano settlement SDK dependency. No production class, interface, binary name,
consensus rule, persisted encoding, or configuration key changes in this cleanup.

Migration replaces aggregate dependencies with actual owners in Yano, X, the BOM,
starter POM, distribution manifests, scaffolding and current documentation.
Scaffolding accepts an independent X version alongside the existing Yano version,
so selecting an older compatible host does not select that host's version for an
X artifact. It continues to build against published/staged artifacts without a
sibling checkout. The other existing compatibility artifacts are outside this
cleanup's scope.

Tests previously housed in the aggregate move to their production owners:
seven shared-contract suites to `node-api`, nine Cardano suites to `cardano-api`,
and application/domain suites and wire specifications to X's existing API modules.
Already extracted copies remain single owners; the message-proof vector test uses
its bundled resource. The topic-vector generator follows its X-owned fixtures.
No test or canonical fixture is intentionally dropped.

The intentional compatibility change is Maven-level: new releases no longer
publish or list `yano-core-api`; consumers requesting that coordinate must update
their dependencies. The supported Host API version and frozen oldest-host fixture
are unchanged. Older immutable hosts may still contain their historical aggregate;
that is allowed in the older-host matrix, not in the new host's runtime closure.

Validation retains both frozen pre19 public-API comparisons and compiled-client
fixtures to catch accidental signature changes. The existing artifact gate also
rejects `yano-core-api` in the current runtime and all generated Maven POMs,
including BOM and starter metadata. Focused relocated tests, full consumer
compilation, framework boundaries, scaffold tests, packaged standalone consensus,
existing integrated distribution checks, and the unchanged oldest-host fixture
cover missing transitive dependencies and packaging mistakes. One comprehensive
Yano release-qualification cycle remains planned after final coordinated pins;
this cleanup introduces no intermediate release.
