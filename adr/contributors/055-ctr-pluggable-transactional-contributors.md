# ADR-055: Pluggable transactional contributors

## Status

Implementation in review. The initial wallet migration and plugin SPI are implemented
in the uncommitted working tree; final manual review is required before committing.
See [implementation plan and validation](055-ctr-implementation-plan.md).

Preview baseline: a fresh sync is expected. Prefer a coherent API over legacy
adapters; wallet API consumers must recompile against the neutral `ChainPoint`.

## Date

2026-09-07

## Decision in brief

Introduce a small, synchronous contributor registry for optional UTxO-derived
indexes. Start by moving wallet indexing into `WalletUtxoIndexContributor`, which
implements `UtxoIndexContributor`. Accept contributors through the existing plugin
catalog and through explicit embedded-runtime registration. Include one external
plugin example to prove the extension point.

The UTxO subsystem continues to own its transaction. Contributors stage their
changes through a scoped writer; they cannot commit or close the transaction.
UTxO state, contributor data, undo information, and completeness/error metadata
commit together in the same RocksDB write batch.

Keep the existing archival `CanonicalProjectionContributor` and its execution
position, apply, rollback, and lifecycle behavior unchanged. Hot loading,
parallel execution, automatic backfill, and migration of other subsystems are
outside the initial scope.

## Context and motivation

`DefaultUtxoStore` currently mixes UTxO application with optional wallet extraction,
query coordination, and wallet-specific rollback/lifecycle orchestration.
Persistence and much of the index logic already live in `WalletIndexStore`.
Nevertheless, adding an optional index requires changing this
large class, making transaction behavior and feature behavior harder to review.

The shared write batch is necessary for atomicity, but does not require these
features to share one implementation class. The transaction owner needs to know
when contributions run, how failures are handled, and when it may commit. It does
not need to know wallet credential formats or future plugins' index schemas.

There are existing foundations to reuse:

- `CanonicalProjectionContributor` and `ProjectionStagingWriter` already separate
  feature-owned encoding from host-owned batch staging.
- `UtxoEventHandler` publishes `UtxoStateAppliedEvent` after the canonical UTxO
  write has committed. That acknowledgement cannot add writes to the original
  transaction.
- `PropagatingEventBus` already forces synchronous delivery for UTxO acknowledgement
  events. However, those events are post-commit, and dispatch continues after a
  listener fails before throwing an aggregate. Neither behavior supplies the
  participant savepoint and failure-marker contract needed inside this transaction.
- The plugin catalog correlates manifests with typed service providers and
  manages selection, compatibility, ownership, and callback lifecycle.

The registry makes transaction participation explicit while retaining post-commit
events for observers.

## Initial scope

1. Introduce public contributor contracts and a neutral `ChainPoint` in `core-api`.
2. Introduce a runtime-owned `UtxoIndexRegistry` and a scoped staging writer.
3. Extract wallet-specific apply, rollback, genesis, pruning, and storage-rebind
   responsibilities from `DefaultUtxoStore` into the wallet contributor.
4. Preserve wallet query behavior, including
   the pending CCL decoding and partial-scan/error-tracking behavior described in
   [the wallet index guide](../../docs/wallet-indexes.md).
5. Support startup registration through the existing plugin catalog and explicit
   embedded-runtime assembly; freeze the registry before block processing.
6. Provide a small external plugin and tests demonstrating atomic apply, restart,
   rollback, and scoped storage.

The wallet's existing first-seen and scan enable flags remain independent. A node
with both disabled should not collect wallet-only data or invoke wallet indexing.

### Explicit non-goals

- Migrating or redesigning archival projection.
- Refactoring all remaining UTxO/stake-balance logic at once.
- Hot addition, removal, replacement, or reordering of contributors during apply.
- Fork/join callbacks or concurrent mutation of a write batch.
- Automatic historical backfill, repair workers, or schema migration engines.
- One generic event/payload covering every ledger subsystem.
- One transaction spanning independently committed Yano subsystems.
- Isolation of untrusted JVM code or guarantees that a slow plugin cannot delay sync.

## Big picture: a reusable pattern, focused contracts

The reusable pattern is:

```text
Existing plugin catalog / explicit registration
                    |
            Subsystem registry
                    |
      Owner stages canonical state
                    |
      Contributors stage derived state
                    |
         Owner commits once
                    |
        Post-commit notifications
```

Other subsystems may later define their own contributor contracts for epoch
artifacts, governance views, or other derived state. They can reuse provider
discovery, stable identities, lifecycle conventions, scoped storage, and failure
policy. Each subsystem must define its own input data, transaction boundary,
ordering, completeness, and rollback semantics.

`UtxoIndexContributor` intentionally describes the first boundary. It does not
grant permission to change canonical UTxOs or imply access to all ledger state.
The UTxO-related portion of archival projection could eventually use an adapter;
its epoch-boundary and other subsystem contributions are not automatically covered.
A broader name such as `BlockStateContributor` requires a separate decision about
the broader contract, not just an interface rename.

## Proposed components

| Component | Responsibility |
| --- | --- |
| `ChainPoint` | Neutral canonical block number, slot, hash, and explicit origin. |
| `UtxoChanges` | Read-only effective block changes made available to contributors. |
| `UtxoIndexContributor` | Stage derived apply and rollback operations; own index semantics and undo. |
| `UtxoIndexContributorProvider` | Create one contributor using constrained host services and configuration. |
| `UtxoIndexContext` | Network identity, contributor identity/configuration, and scoped persistent storage. |
| `UtxoIndexRegistry` | Frozen ordered participants, lifecycle dispatch, failure isolation, and capture requirements. |
| `IndexWriter` | Callback-scoped staging operations over the owner's transaction. |
| `WalletUtxoIndexContributor` | Wallet extraction, index persistence, error/completeness handling, and lifecycle. |
| Wallet query service | Existing first-seen and scan behavior through host-controlled read scopes. |
| `DefaultUtxoStore` | Core processing, transaction/read-lock ownership, storage generation, registry calls, and existing wallet API delegation. |

Use `org.yanoproject.api.chain.ChainPoint` and place the contributor SPI
in `org.yanoproject.api.utxo.index`. Method signatures still need API
review. The following signatures describe
the main seam, not the entire lifecycle API:

```java
public interface UtxoIndexContributor {
    void stageApply(UtxoChanges changes, IndexWriter writer);
    void stageRollback(ChainPoint target, IndexWriter writer);
}

public interface UtxoIndexContributorProvider {
    String id();
    UtxoIndexContributor create(UtxoIndexContext context);
}
```

The complete contract also includes staging fresh-genesis initialization and undo
pruning, rebinding storage while processing is paused, and closing resources after
callbacks drain. These are required for the wallet migration, not deferred hooks.
V1 has no general post-commit or abort callback: the host owns generation changes,
and contributors use persisted state and callback-local computation.

## Data contract

### Neutral chain coordinate

Use `ChainPoint`, not `WalletChainPoint` and not Yaci's slot/hash-only `Point`:

```java
public record ChainPoint(long blockNumber, long slot, String blockHash) {}
```

The implementation includes shared validation and `ORIGIN`: block number `-1`,
slot `0`, and a 32-byte zero hash. Real slot-zero blocks remain distinct from origin.
Hash representation is consistent with the existing wallet JSON contract.

General contributor APIs must not depend on wallet packages. Consolidate the preview
wallet APIs directly onto `ChainPoint`; remove `WalletChainPoint` rather than adding
conversion wrappers. JSON coordinates retain their existing field names. No legacy
database migration or binary adapter is included in this preview change.

### UTxO changes

Conceptually, `UtxoChanges` contains:

| Field | Meaning |
| --- | --- |
| Previous/current `ChainPoint` | Exact canonical transition being staged. |
| Era | Interpretation of transactions and effective effects. |
| Ordered transaction effects | Transaction identity, validity, effective consumed and created outputs. |
| Relevant transaction data | Immutable credential/reward-account subjects plus original transaction-body CBOR (optional for synthetic/Byron transactions). |
| Protocol-consumed outputs | Non-transaction effects such as Allegra bootstrap removal, using the same input resolution shape. |

Each consumed entry retains its `Outpoint`. When requested information cannot be
resolved, that absence is explicit; it must not be represented as an empty input
list or silently treated as an unrelated address. The cause/reference is available
for a contributor's completeness diagnostics.

The contract must preserve:

- Original transaction order, including outputs created and spent in the same block.
- Ordinary inputs/outputs for valid transactions and effective collateral effects
  for invalid transactions; excluded effects must not enter indexes accidentally.
- Original address identity, including historical trailing bytes. Wallet decoding
  and credential extraction use CCL, including Byron support. Pointer addresses
  contribute payment credentials only; stake-pointer resolution remains unsupported.
- Effective changes before optional storage filtering. If a required historical
  input is unavailable because of a storage mode, the host must reject the
  incompatible configuration or expose incomplete capability explicitly.
- Byron main-block effects without requiring a Shelley `TransactionBody`.
  Empty/canonical transitions and genesis use explicit host lifecycle semantics.

Do not expose mutable Yaci transaction models. V1 uses immutable snapshots:
effective input outpoints/resolved addresses, created outpoints/addresses/lovelace
and collateral-return flags, and sealed credential/reward-account subject records.
Lists are copied; strings, quantities and coordinates are immutable. Consumers may
retain these values, but never a live reader/writer. Full consumed-output values
and richer structured transaction metadata are future additive capabilities.
Raw Shelley-family transaction CBOR is available for additional transaction fields.

Contributors declare capture requirements at startup. The owner captures their
union **with the existing archival requirements** once, and avoids copying full
consumed UTxOs when only addresses are needed. Consumed information has explicit
`NOT_REQUESTED`, `RESOLVED`, and `UNRESOLVED` states; `null` cannot encode both
not-requested and unknown. Requirements also declare `requiresFullUtxoStorage`.
The host rejects an incompatible storage mode or filter chain at startup, before
any contributor runs, rather than retaining wallet-specific constructor checks.
There is no second block traversal or database re-resolution per plugin merely to
recover information already available during apply.

## Transaction and execution contract

Capture the previous canonical point before staging any cursor changes. On apply,
replace wallet staging in place: stage UTxOs, invoke the registry, then stage the
delta/cursor, stake balances, and existing archival projection before committing.
Do not move the archival hook. Each participant savepoint is released or rolled
back before the next participant; no savepoint may escape its scope. Test this
ordering against the existing projection savepoint using RocksDB.

```text
Apply:    begin batch → stage UTxOs → stage contributors → commit → acknowledge
Rollback: begin batch → reverse UTxOs → reverse contributors → commit → acknowledge
```

Callbacks run synchronously on the owner's apply thread in a deterministic order.
Registration order is built-ins in declared order, then external contributors in
lexical selector order (including explicitly registered external contributors).
Bundle dependency ordering does not order typed providers. The first
version does not permit contributors to depend on another contributor's pending
data, so arbitrary priority schemes are unnecessary.

`IndexWriter` provides scoped puts/deletes, `getCommitted(table, key)`, and paginated
`scanCommitted(table, prefix, cursor, limit)` within the participant's namespace.
Pages are capped at 256 entries, cursors are opaque, and no native iterator escapes.
It cannot commit, close, clear, or roll back the owner's whole batch. The host owns savepoints
and expires the writer after the callback. A contributor cannot write canonical
UTxO tables or another contributor's namespace. This is new enforcement for this
SPI; the existing archival writer's column-family lookup is not similarly scoped
and is unchanged by this ADR.

V1 reads are **committed-only**: they never include pending writes, including the
participant's own earlier writes. Contributors maintain callback-local accumulators
or deduplication sets for read-modify-write and repeated keys. The wallet already
uses committed reads and first-seen deduplication. No generic overlay or
`WriteBatchWithIndex` is introduced, and no read-your-writes guarantee is implied.

No network calls, asynchronous batch use, unbounded historical scans, or retained
batch references are allowed in callbacks. Contributors must not publish durable
progress or mutate authoritative caches before commit. Persisted state and
callback-local computation avoid needing post-commit/abort callbacks in v1.

The host must not close a transaction or classloader while a callback is running.
In-process plugin execution remains trusted: a restricted writer is an ownership
boundary, not a JVM security sandbox or a hard execution-time limit.

### Query ownership and storage generation

`DefaultUtxoStore` continues implementing its existing wallet query interfaces by
delegating to a wallet query service. The host supplies short-lived read scopes
under its existing store monitor, exposing the applied/canonical point and storage
generation. The internal wallet binding also supplies its filter/error readers,
archive capability checks, and canonical block-body reader. External providers do
not receive arbitrary canonical table access or this wallet-specific binding.

Each scan step checks generation and canonical identity and reads under that scope;
HTTP output happens outside it. Do not hold a lock or RocksDB snapshot for an entire
stream. Recheck the canonical point around first-seen reads as today. The host
increments generation after a successful rollback commit and before storage
replacement/reinitialization or close invalidates handles. A failed/uncommitted
rollback must not increment it. This preserves scan invalidation without making
the wallet contributor responsible for observing commit success.

## Storage and rollback

The host owns contributor storage in the same RocksDB database as the UTxO batch.
External contributors receive stable, collision-free namespaces and logical
tables; they do not add physical CF declarations per plugin to
`DirectRocksDBChainState`, where the wallet CFs are currently declared.
Use shared `utxo_index_data` and `utxo_index_undo` CFs for external participants,
and host-only `utxo_index_meta` for registry metadata. Encode data/undo keys as
`version-byte | u16 contributor-id length | UTF-8 id | u16 table-id length | UTF-8 table-id | user-key`,
with unsigned big-endian lengths. Prefix scans must remain within that exact
namespace. Contributor IDs/selectors are stable lowercase ASCII identifiers matching
`[a-z][a-z0-9._-]{0,127}`; table IDs match `[a-z][a-z0-9_-]{0,63}`. Reject invalid
or duplicate identities at startup. Logical tables do not receive independent
RocksDB tuning in v1; the wallet keeps its existing physical CFs and point-lookup
options.

The built-in wallet contributor uses its existing physical CFs through an internal
host binding, retaining useful point-lookup tuning. This is not a compatibility
guarantee: the new host metadata requires a fresh sync. The binding is not permission
for external contributors to request reserved CFs. For external storage, logical
table `undo` maps to the shared undo CF; other logical tables map to the data CF.

New external namespaces record owning identity, schema version, network identity,
canonical progress, and availability. An external provider declares its schema
version; incompatible persisted versions fail startup, with no automatic migration.
The wallet retains its own format and genesis identity checks. Invalid wallet
metadata makes reads unavailable; staging failures use the new host safety gate,
not a second wallet failure callback. No legacy marker conversion is provided.
Network magic is exposed by the context; the full genesis identity and outputs
are supplied to the genesis callback.

Atomicity is not automatic chain rollback. Each contributor supplies undo semantics
for its own state and stages undo records in the apply batch. The owner supplies the
exact rollback target; contributors stage reversals in the same rollback batch.
Same-height replacement blocks are distinguished by hash. Insufficient retained
undo invalidates the affected index's availability; it must not falsely report a
successful complete rollback.

Genesis initialization and undo pruning run through generic registry lifecycle
hooks. Snapshot restore pauses callbacks, replaces/rebinds host storage, refreshes
contributor handles, and revalidates persisted identity/progress before resuming.
Active wallet scans retain their existing generation/hash invalidation behavior.

### Owner operation coverage

| Owner path | V1 behavior with registered contributors |
| --- | --- |
| Shelley/Byron apply, including empty transitions | Stage effective changes in the owner batch; Byron does not require a Shelley body. |
| Fresh genesis initialization | Stage initialization in the same owner batch, preserving wallet genesis behavior. |
| Deferred producer Shelley genesis materialization | Validate funds against the already-indexed genesis digest and atomically record a one-shot materialization marker. Shifted-devnet fast-forward blocks may precede this step. Repeated matching calls are no-ops, even after funds are spent; do not emit duplicate index events. |
| Ad-hoc Byron genesis insertion / raw outpoint removal | Reject with active contributors; normal genesis and Allegra apply use their atomic paths instead. |
| Rollback | Stage contributor undo and host availability restoration before commit; advance generation only after success. |
| Undo/delta pruning | Registry prune hooks share the prune batch and retention cutoff. |
| Reinitialize/snapshot restore | Pause/drain callbacks, invalidate read generation, rebind, and validate identities/progress before resuming. |
| Full-state rebuild from genesis | Reject before mutation while contributors are active in v1. |
| Bootstrap UTxO injection | Reject before mutation while contributors are active; guard under the owner lock, including its cursor/delta writes. |
| Faucet UTxO injection | Reject before mutation while contributors are active; do not silently omit injected addresses or invent chain history. |
| Close | Drain callbacks, invalidate reads, close contributor resources before DB/classloader resources. |

The injection guards are deliberate new restrictions for active contributors.
With no contributors, existing behavior remains unchanged. Supporting those
maintenance operations later requires a separate initialization/history contract,
not synthetic block events in v1. Existing archival replacement during rebuild is
outside this frozen registry and remains unchanged.

Undo retention uses the existing `yano.utxo.rollbackWindow` slot setting. A prune
hook failure aborts and logs the entire prune batch; the pruning cursor advances
only in that successful batch. Retry on a later prune cycle, without failing an
already committed canonical apply. Do not preserve the current silent catch as the
registry contract. Per-block completeness/error records are not undo and must not
be removed merely because their undo retention expires.

## Failure and completeness policy

Separate expected data-level incompleteness from unexpected participant failures:

1. **Expected extraction issue:** the contributor continues processing independent
   records, stages good data, and records the affected block's incomplete status
   atomically. Wallet parsing errors must not discard other addresses' index writes.
2. **Unexpected contributor exception:** the registry discards that contributor's
   staged changes to its savepoint, then stages a durable unavailable/error marker.
   Other contributors may continue if canonical state remains safe.
3. **Cannot persist the failure state, storage failure, or process-fatal failure:**
   fail the apply transaction/generation. Logging alone is not proof that an index
   is incomplete and must not permit apparently complete state to commit.

Coverage is contributor-owned semantics with host-enforced lifecycle boundaries.
The host writes the availability marker itself, without depending on a contributor
failure callback. The registry does not pretend that all indexes have the
same query/recovery semantics.

The host-only `utxo_index_meta` CF holds a versioned `availability` record per
contributor, addressed by the same length-prefixed contributor identity followed
by a host-defined metadata key. It records availability, the canonical failure
point, and a bounded reason. Schema/network/progress/availability share one encoded
host state record; genesis-materialization validation uses a separate digest record.
On an unexpected callback failure, write the unavailable record **outside** the
participant savepoint, along with host-owned undo of its previous value in the
host-only metadata CF, keyed by contributor and canonical block number; encoded
states retain the exact block hash. Rollback
restores those host records in the owner batch; missing host undo leaves the index
unavailable. Prune host undo under the same retention rule as contributor undo.
A later successful block does not clear unavailability automatically.

Corrupt host metadata is deliberately different from a failed index callback:
startup or mutation fails rather than overwriting an untrusted safety/undo record.
There is no automatic recovery from corruption in v1. Query availability reports
the corruption as unavailable (including wallet coverage diagnostics), not an
unhandled decoding error. RocksDB I/O failures still propagate. Fresh sync is the
recovery path; corrupt optional wallet payloads remain governed by wallet policy.

This is a coarse safety gate, not a second coverage model. Queries must satisfy
both this host gate and contributor-specific coverage checks. Wallet expected
per-block extraction errors remain exclusively in `wallet_index_errors`, retaining
partial-range queries; they do not trip the coarse gate. Existing wallet unavailable
metadata still participates in wallet coverage checks. A missing host record is
unavailable; initialization occurs during a fresh genesis sync, not by inferring
completeness from a legacy database. Missing/disabled providers are non-queryable;
startup validation must detect gaps before any re-enabled provider can serve data.

Host-gate rejection preserves the wallet's available range and identity diagnostics
when storage is readable. During storage replacement/closure, queries must not read
native handles merely to enrich an error response.

For the wallet contributor, preserve the partial-scan contract: known block errors
are durable, good addresses remain indexed, and scans crossing an affected block
return available matches plus warnings. They terminate with `incomplete` and
`complete: false`, never normal `done`. Unaffected ranges can complete successfully
when their boundary state is valid. First-seen queries cannot claim complete
historical answers while relevant completeness remains unproven. Error records
roll back with their blocks; new coverage must not fabricate old missing history.

## Plugin discovery and lifecycle

Add a manifest-required `utxo-index-contributor` contribution kind for
`UtxoIndexContributorProvider` through the existing catalog. Register its service
descriptor, compatibility checks, ownership metadata, trust classification, and
callback/cleanup handling consistently with other typed providers. Adding the
public SPI requires an additive plugin API level update and generated catalog/native
metadata support; do not assume an automated gate already enforces that update.
Classify the kind as `PRIVILEGED_LOCAL`, not a consensus extension. Implement its
typed selector extraction, lifecycle facade, exhaustive trust-tier handling, and
native metadata tests alongside the kind.

For JVM directory deployment, a correctly packaged JAR supplies an owning manifest,
the service-provider entry, implementation, and dependencies. The configured plugin
directory is discovered at startup. Only selected/enabled contributors that pass
existing policy and compatibility checks are instantiated and registered; copying
a JAR does not bypass operator policy.

Use `yano.utxo.index-contributors` as an explicit list of external registrations:

```yaml
yano:
  utxo:
    index-contributors:
      - type: example.output-index
        enabled: true
        config: {}
```

`type` is the catalog selector and stable storage identity; provider `id()` must
match it. Duplicate selectors fail startup, so v1 supports one instance per
selector. An omitted registration or `enabled: false` does not instantiate a
provider; `enabled` defaults to false. The entry's `config` is the provider's
configuration source (scalar string/number/boolean values normalized to immutable
strings), supplied through `UtxoIndexContext`, not unrestricted global
configuration. Catalog bundle allow/deny policy applies before registration.
An explicitly enabled selector that is missing, denied, or incompatible fails
startup rather than silently disappearing from the requested registry.
Reserve selector `wallet` for the built-in; it is not externally replaceable or
configured through this list. Its existing two wallet flags alone select its work.
Embedded registration uses the same selector/configuration and validation model.

The provider declares capture/storage requirements before creation. The context
supplies immutable network and contributor identity, the entry's configuration,
and namespace-scoped committed
reads through host-controlled read scopes. It exposes no raw database, batch,
arbitrary column-family lookup, or canonical mutation service. Additional public
host services require an explicit API decision, not a general service locator.

Packaged/native deployments include providers at build time. Native executables do
not acquire new Java implementations by copying JARs beside an already built binary.
Embedded applications can explicitly supply contributors/providers through runtime
assembly; all registration paths meet the same identity and registry checks.

Freeze the registry and capture requirements before processing blocks. Startup must
establish required storage namespaces before the first callback. Shutdown drains
callbacks before contributor cleanup, database closure, or plugin classloader
release, using the existing plugin lifecycle fences.

In v1, installing an index after sync has started requires a fresh sync for
availability; no partial-history activation is inferred. Disabling it while blocks
advance creates a gap. Removal preserves its
stored data but cannot leave an apparently current queryable index. Re-enabling
must validate schema and canonical progress; no backfill or repair is inferred.
If a provider is absent, its namespace must remain unavailable rather than claim
rollback correctness for callbacks that could not run.

Normal runtime stop/start closes and recreates external products through the same
frozen registration. Old contexts expire; catalog cleanup barriers complete after
product close. This is lifecycle restart, not hot replacement or changed selection.
After both canonical and async UTxO apply drain, the runtime closes index products,
then seals callback admission and awaits product-cleanup futures. Cleanup attempts every product
even when another close fails, preserving the strongest failure for the caller.

## Implementation sequence and acceptance

1. Finalize `ChainPoint`, focused change views, capture requirements, scoped writer,
   lifecycle contracts, identity/schema policy, and plugin API compatibility.
2. Add the registry and owner hooks. Prove transaction/savepoint behavior with test
   contributors before moving wallet logic.
3. Extract `WalletUtxoIndexContributor` while retaining storage formats, enable flags,
   query surfaces, partial-scan semantics, and snapshot/rollback behavior.
4. Add provider discovery and explicit registration. Supply a small external plugin
   that indexes created outputs by block with undo, and prove directory discovery
   plus selected registration on JVM.
5. Run focused regressions, inspect disabled-feature overhead, and review the public
   contract before committing to further subsystem migrations.

Acceptance includes:

- Adding the example plugin requires no example-specific changes to `DefaultUtxoStore`.
- Committed UTxO/index/error/undo coordinates agree; aborted or failed commits expose
  neither staged contributor data nor advanced contributor progress.
- A contributor cannot write another namespace or retain a live writer after return.
  Reads demonstrably return committed state, excluding staged writes; repeated-key
  updates and same-block deduplication are correct using callback-local state.
- Expected wallet extraction failures preserve other addresses and produce explicit
  partial results; unexpected callback failures follow the durable failure policy.
- Rollback/replay, same-height reorgs, missing undo, restart, snapshot restore, and
  active-scan invalidation preserve or explicitly invalidate completeness.
- Failed rollback commits do not advance generation. Query scopes preserve existing
  locking/canonical checks without retaining a lock across stream output.
- Every owner operation in the lifecycle table is tested, including pre-mutation
  maintenance guards and prune failure without pruning-cursor advancement.
- Same-block create/spend, invalid collateral effects, Byron genesis/main blocks,
  historical extended addresses, pointer payment-only handling, and unresolved
  inputs retain their specified behavior.
- Disabled contributors incur no unnecessary capture; late installation and
  disable/re-enable never invent historical completeness.
- Plugin duplicates, incompatible versions, missing providers, shutdown cleanup,
  and packaged/native metadata have deterministic tested behavior.
- Built-in/lexical ordering, explicit enablement, reserved selectors, policy checks,
  namespace-prefix isolation, and incompatible filtered storage are tested.
- Existing archival projection regression checks remain unchanged and pass.

## Alternatives and consequences

Keeping feature branches in `DefaultUtxoStore` is initially easy but repeats the
maintenance problem for every optional index. Publishing a raw batch on the general
event bus obscures transaction participation and exposes async/lifetime hazards.
Post-commit indexing provides looser coupling but requires independent progress,
recovery, and consistency machinery. Parallel contribution adds coordination and
memory costs before there is evidence it is needed.

The chosen registry reduces feature coupling and provides a testable plugin seam.
It adds a public API and lifecycle responsibilities that require compatibility
discipline. It does not make plugin work free, remove index-specific undo logic,
repair historical databases automatically, or make independently owned subsystem
transactions atomic together.

Future contributor ADRs should refer to this pattern and specify only the new
subsystem's data, owner transaction, failure, and rollback differences. Expansion
should follow demonstrated simplification, not a requirement to convert every
existing event handler into a contributor.

## References

- [ADR-054: Optional wallet discovery indexes](../054-optional-wallet-indexes.md)
- [ADR-042: Byron projection through the UTxO batch](../042-byron-projection-through-utxo-batch.md)
- [ADR-052: Native projection/history providers](../052-native-projection-history-service-providers.md)
- [Wallet index API and partial-result behavior](../../docs/wallet-indexes.md)
- [CanonicalProjectionContributor](../../core-api/src/main/java/org/yanoproject/api/archive/CanonicalProjectionContributor.java)
- [ProjectionStagingWriter](../../core-api/src/main/java/org/yanoproject/api/archive/ProjectionStagingWriter.java)
- [UTxO event acknowledgements](../../runtime/src/main/java/org/yanoproject/runtime/utxo/UtxoEventHandler.java)
- [Plugin contribution kinds](../../plugin-catalog/src/main/java/org/yanoproject/catalog/ContributionKind.java)
- [Plugin provider registry](../../runtime/src/main/java/org/yanoproject/runtime/plugins/PluginProviderRegistry.java)
