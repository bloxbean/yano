# ADR-035 implementation report

This report records phase evidence for
[ADR-035](035-cardano-history-product-console-and-distribution.md). Each phase is implemented and
reviewed on a feature branch before it is merged into the ADR-028 integration branch.

## P0 — characterization

Status: complete

The v1 contract is frozen before runtime wiring:

| Contract | Frozen value |
|---|---|
| Bundle ID | `com.bloxbean.cardano.yano.appchain.cardano-history` |
| State machine/application | `cardano-history` |
| Presets | `params-only-v1`, `params-stake-v1`, `params-governance-v1`, `full-v1` |
| UI API/mount | `1` / `app-chain` |
| UI asset budget | 256 files, 4 MiB each, 16 MiB total |
| UI sandbox | scripts only, opaque origin, no direct network |
| Proof bundle | canonical ADR-028 authenticated-snapshot bundle plus history predicates |
| CLI success classes | unavailable/incomplete, invalid, root-only valid, L1-anchored valid |

The core API now supplies immutable descriptor, permission, asset-manifest, and provider contracts.
Contract tests accept the Cardano History descriptor and reject traversal, unknown media, duplicate
paths, undeclared permissions, non-canonical digests, and budget violations. These are generic
plugin contracts; no Cardano-specific type was added to `core-api`.

## P1 — plugin UI foundation

Status: complete

Implemented:

- `ui-extension` is a manifest-required, auxiliary-local contribution kind with native-image and
  offline/runtime catalog parity;
- the runtime eagerly reads every declared asset through the plugin callback fence, checks its
  exact size and SHA-256 digest, derives a deterministic content-set digest, and publishes only a
  host-owned immutable byte snapshot;
- stop/unload atomically removes the catalog and invalidates the content-addressed namespace;
- the REST adapter exposes a no-store catalog and immutable assets with `nosniff`, ETag,
  restrictive CSP, referrer, and same-origin resource headers;
- the console adds a generic capability-gated extension route and an opaque-origin iframe with
  only `allow-scripts`;
- the v1 `postMessage` bridge binds source window, opaque origin, API version, random session
  nonce, selected chain, request ID, permission, input/response size, concurrency, and timeout;
  it offers only bounded read operations and never passes the API key or a raw-fetch primitive.

Conformance covers invalid paths/media/digests, duplicate assets, unsupported permissions and API
versions, wrong-chain capability requirements, forged nonces, undeclared bridge methods,
cross-namespace asset access, and lifecycle cleanup. The production console contains no
Cardano-History-specific source or asset.

## P2 — product assembly

Status: complete

The optional `appchain-cardano-history` module contributes the stable `cardano-history` provider.
Its four immutable presets construct the existing `EpochParamsStateMachine`,
`EpochStakeStateMachine`, and `EpochGovernanceStateMachine` through `ComposableAppStateMachine`.
The product contains no observation, transition, key, value, or snapshot implementation.

Startup validates exact preset/observer agreement, bounded chunk sizes, authenticated-state
identity, and MPF whenever L1 proof consumption is required. `params-only-v1` activates neither
stake/governance traversal nor a secondary authenticated-snapshot series. Artifact checks reject
host API, composition, or stdlib classes in the deterministic bundle jar.

## P3 — Query, client, and CLI

Status: complete

- Added a bundle-owned read-only domain API for status, epoch catalog, parameters, stake, DRep, and
  proposal history.
- Stake/governance fact and completeness reads use one `composite/aggregate-v1` query and one
  committed root. Incomplete datasets never assert absence.
- Added an explicit-URL/explicit-chain typed client with exact-height proof lookup and root-race
  rejection.
- Added a portable canonical snapshot bundle and standalone verifier with distinct invalid,
  root-only, and independently Cardano-anchored results.
- Added the `yano-cardano-history` CLI distribution and documented its commands and exit codes.

## P4 — On-chain consumers

Status: complete

The `appchain-cardano-history-onchain` module provides compiled, product-bound parameter and pair
validators over the generic MPF anchor, inclusion, same-root pair, and authenticated-snapshot
libraries. The generic anchor entry points remain separately consumable. The product validators
bind state keys, epoch, predicate, and operands as script parameters, never as caller-controlled
redeemer authority. Emulator suites cover inclusion, exclusion, same-root completeness, parameter
values, stake amount/pool/combined, proposal, DRep, and wrong-root/profile cases; product tests
compile both validators and freeze the canonical keys and predicate mapping. JMT is not exposed as
an on-chain option.

## P5 — Cardano History plugin UI

Status: complete

The product bundle now contributes its own sandboxed Cardano History UI. The capability-aware
overview hides stake/governance controls when their preset components are absent. Queries and proof
generation use only the versioned read-only host bridge; imported bundles are verified without the
bridge. The assets contain no direct network primitive or external URL. The Proof Lab verifies the
released MPF inclusion and absence wire profile. It derives canonical physical or snapshot keys
from the typed epoch/credential/proposal subject, preventing a valid leaf from being relabelled. It
verifies primary/secondary root binding, the canonical snapshot descriptor, same-primary-root
completeness, and stake amount/pool/combined, proposal, DRep, or absence semantics. Fixed browser
vectors agree with raw Java MPF branch/single-leaf/absence vectors and reject malformed CBOR,
wrong key/value/root, and subject relabelling. Proof export is a permission-gated host download,
not clipboard-only or sandbox filesystem access. Cardano-anchor trust remains explicitly unchecked
until supplied independently to the standalone verifier.

## P6 — Showcase and distribution

Status: complete

The light showcase now uses the real product provider; the temporary pilot provider and duplicate
composition logic are removed. Its default chain selects `params-only-v1`. Fresh instances may
select `params-stake`, `params-governance`, or `full`, or omit the product chain entirely. The
normalized choice and product-bundle SHA-256 are retained in deployment identity. Dynamic chain
indexing keeps preprod correct after the devnet-only settlement chain is removed.

The showcase ZIP contains the product jar in the runtime plugin directory and a standalone CLI
distribution under `tools/cardano-history`. Snapshot and optional MPF-pruning flags are valid only
for a snapshot-capable preset. Contract tests cover all preset shapes, JMT/MPF selection, pruning
constraints, disablement, identity drift, and preprod indexing. The product guide, plugin-UI bridge
reference, snapshot guide, capability catalog, light profile, master demo, and a dedicated packaged
Cardano History runbook document installation, queries, nested proofs, trust labels, and operation.

Distribution qualification also ran the installed ZIP as a fresh three-node devnet cluster with
the `full-v1` preset and authenticated snapshots enabled. All thirteen chains started, the normal
composite/authenticated-map quickstart completed, and every node reported the same Cardano History
identity and root. That run exposed an invalid bundle-owned domain contribution selector which the
earlier source-text metadata test had missed. The manifest is now parsed with the production strict
parser in unit tests, and the rebuilt product activated successfully. The thin bundle boundary also
excludes the host-supplied SLF4J API and verifies that exclusion during `check`.

## P7 — qualification

Status: complete for preview; the ADR-028 M8d public-network observation period remains a release
gate rather than an unimplemented product feature.

Final review and iteration closed the following integration defects:

- plugin state-machine facades now forward authenticated-snapshot series and source-commitment
  adapters through the TCCL boundary, including defensive byte-array copies;
- each epoch observer offers only its earliest unfinalized job, preventing overlapping builds in a
  single-build snapshot series while allowing different observers to progress independently;
- a late member accepts a genuinely missing local observation only while applying an already
  threshold-certified historical block. A contradictory exact-epoch job still fails as
  `MISMATCH`, and live proposals retain the original fail-closed rule;
- Cardano History domain fact/completeness reads use one composite query at one root;
- client proof generation first obtains the exact L1-confirmed commitment and pins all primary and
  secondary proof calls to its height/root, removing the moving-tip/anchor race;
- real Conway protocol parameters authenticate positional cost-model arrays once. Reserved field
  21 is `null`, field 22 carries compact raw arrays, and the measured value fell from 42,670 to
  3,038 bytes while preserving ledger data and the 8 KiB on-chain envelope;
- generic `capabilities.*` settings, snapshot-admin credentials, generated schemas, and launcher
  forwarding now share one public configuration registry; and
- the standalone state verifier requires an explicit block hash when the caller labels a root as
  Cardano-anchor authenticated.

Qualification evidence:

- a fresh three-node accelerated-epoch devnet produced ordered MPF stake and DRep snapshots on all
  nodes with identical Cardano History roots;
- a retained-state stop/restart and late-follower catch-up reproduced height 6 and root
  `dd3dbfe369c671cf2fc5d8c2808682034d0ac8eaaabffd414eb5d4ea3faf91ee` on all three nodes, with no
  proposal mismatch, snapshot-overlap, or generation error;
- the Cardano History SCRIPT anchor was bootstrapped and confirmed; protocol-parameter and stake
  bundles were generated at anchored height 6 and independently returned
  `ROOT_VERIFIED_ANCHOR_UNCHECKED`, the deliberate exit-5 trust label before supplying an
  independently verified Cardano transaction;
- a 30-second, 10-message/s, three-node soak accepted and finalized 300/300 messages, returned zero
  errors or throttles, and ended with identical roots/tips on every member;
- browser MPF vectors, plugin/TCCL, domain API, client/CLI, configuration, devnet assembly,
  showcase script, and copied-distribution contract suites pass; and
- `./gradlew clean build` passes all 495 tasks. The build itself found and closed missing registry,
  generated-schema, and trusted-block-hash coverage before this status was recorded.
