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
legacy aggregate API packaging. Keep the existing `yano-core-api` coordinate as
a compatibility aggregate where necessary, with transitive dependencies on the
new owners; it must not embed duplicate class definitions. The small host SPI
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
