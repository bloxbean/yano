# ADR-024: Bootstrap Shelley Genesis Staking Through GenesisBlockEvent

## Status

Proposed

## Date

2026-06-05

## Context

Yano devnet can start directly from a Shelley/Conway genesis file that includes
initial funds and a `staking` section:

- `staking.pools`: genesis pool parameters
- `staking.stake`: stake credential hash to pool hash delegations

Yaci DevKit's `yano-only` mode uses this shape. In the current run, the deployed
Shelley genesis contains pool
`7301761068762f5900bde9eb7c1c15b09840285130f5b0f53606cc57` and a delegated
stake credential, but Yano's account state reports no pools and no delegations.
Epoch reward calculation then sees `pools=0`, `snapshot=0 entries`, and
`activeStake=0`, so Yano AdaPot diverges from yaci-store on devnet.

Public-network AdaPot is not affected by this specific gap because public
network Shelley genesis files have empty `staking` sections and pool/delegation
state is created by on-chain certificates.

Yano already has a genesis bootstrap hook that is synchronous for the
account-state subscription:

- `GenesisBlockEvent`
- `AccountStateEventHandler.onGenesisBlock`
- `DefaultAccountStateStore.handleGenesisBlock`

However, `GenesisBlockEvent` currently carries only block location metadata, and
`DefaultAccountStateStore.handleGenesisBlock` only bootstraps epoch parameters.

Haskell `cardano-ledger` handles this devnet scenario in its direct-start
transition path. `shelleyRegisterInitialFundsThenStaking` /
`conwayRegisterInitialFundsThenStaking` registers initial funds first, registers
Shelley genesis pools, registers genesis staking credentials with delegations,
and then calls `resetStakeDistribution`. That reset creates an initial
`ssStakeMark` and pool distribution from `snapShotFromInstantStake
(addInstantStake utxo mempty) dState pState`. Yano should align with that ledger
state transition while still using Yano's RocksDB account-state model.

Follow-up comparison against Haskell `cardano-node` showed an important
accounting rule: Shelley genesis pool and delegated stake credentials are
registered at genesis and their deposits are booked into the deposit pot, but
the initial reserves row remains `maxLovelaceSupply - sum(initialFunds)`.
For the Yaci DevKit fixture with one pool and one delegated stake credential,
that means `500 ADA + 2 ADA = 502,000,000 lovelace` in deposits, while initial
reserves are not reduced by that amount.

The same comparison also showed that direct-start devnets must count the real
slot-0 producer block in reward inputs. Yano stores a real block at slot 0, but
the genesis bootstrap path must still guarantee that the account-state
`PREFIX_BLOCK_ISSUER` fact exists for that block. Missing that single block
changes the early reward pot and compounds into treasury/reserves divergence.

## Decision

Use `GenesisBlockEvent` as the single synchronous bootstrap event for
genesis-derived state.

Extend the event with a structured optional bootstrap payload. The payload should
be domain data, not file paths or runtime `GenesisConfig` objects:

- Shelley initial funds, if a listener needs them
- Shelley genesis staking:
  - zero or more pools
  - zero or more stake delegations
- future genesis sections as needed, such as governance genesis state

For local block-production genesis events, also carry the optional producer pool
hash derived from the signed block producer's issuer cold verification key. This
is event metadata about the actual slot-0 block, not Shelley genesis staking
configuration.

Keep old constructors or factory methods so existing callers can continue to
publish a metadata-only `GenesisBlockEvent`.

Add Shelley genesis staking parsing to `ShelleyGenesisParser` /
`ShelleyGenesisData`. The parser must support multiple pools and multiple stake
delegations. It must accept the canonical Haskell ledger pool field names
`poolId` and `accountAddress`, while continuing to accept the legacy aliases
`publicKey` and `rewardAccount` found in existing devnet files. The pool map key
is the pool identity; if the body identity is present and differs from the map
key, parsing must fail closed. Pool data must include at least:

- pool hash/operator
- VRF key hash
- pledge
- cost
- margin numerator and denominator
- reward account bytes/credential
- owners
- relays and metadata when available

For non-empty `staking.pools`, missing or malformed required pool fields should
fail startup/block production rather than producing partial pool state. Empty or
missing public-network staking sections remain a no-op.

For initial funds, follow the Haskell ledger direct-start rule:
`extraConfig.initialFunds` may override legacy `initialFunds` only when the
legacy map is empty. Yano should support the embedded
`extraConfig.initialFunds.data` form and fail closed for
`extraConfig.initialFunds.file` until Yano has equivalent file-system and hash
verification semantics.

Add an idempotent genesis-staking bootstrap handler in `DefaultAccountStateStore`
behind `handleGenesisBlock`. On a fresh direct-start devnet, it should seed the
same account-state prefixes that normal certificate processing expects:

- pool registration data
- pool params history, effective from genesis
- pool registration slot
- pool delegation entries
- stake account entries for delegated genesis stake credentials
- account registration slot entries as needed so snapshots include those
  delegations

Preserve each pool's `rewardAccount` as the pool operator reward destination.
Do not, however, synthesize a stake-registration event/account entry for that
reward credential merely because it appears in pool parameters. Registration
state is separate from pool parameter state. If the reward credential is also
registered by genesis stake data or a real on-chain certificate, it should be
treated as registered. Delegated genesis stake credentials must be registered
with the genesis `keyDeposit` so snapshot logic includes them and AdaPot deposits
match Haskell. Pool reward accounts must not be registered unless they are
explicitly present as genesis stake credentials. Yaci-store and
`cf-rewards-calculation` currently treat the devnet pool reward account this way:
the pool parameter has a reward account, but the operator reward is denied
because that credential has never had a registration event. Auto-registering
that credential would change reward inputs and create a new mismatch.

Publish the enriched `GenesisBlockEvent` from a fail-closed path. Genesis staking
bootstrap failure must fail startup or block production; it must not be swallowed
by a best-effort event publishing path.

## Implementation Constraints

### Haskell Ledger Alignment

Treat Haskell `cardano-ledger` as the source of truth for direct-start devnet
genesis state:

- `registerInitialFunds` inserts genesis UTXOs and subtracts only initial funds
  from reserves.
- `registerInitialStakePools` creates pool state with `ppPoolDeposit`.
- `shelleyRegisterInitialAccounts` / `conwayRegisterInitialAccounts` registers
  each `staking.stake` credential with `ppKeyDeposit` and its pool delegation.
- `resetStakeDistribution` sets the initial `ssStakeMark` and
  `ssStakeMarkPoolDistr` from the registered accounts, pools, and initial UTXO
  stake.

Yano's internal `cfEpochSnapshot` key `-1` is only the persisted representation
of that Haskell initial mark snapshot in Yano's reward-snapshot keyspace. It is
not a synthetic slot, block, or yaci-store storage convention.

### Publish Sites And Payload Sources

Build `GenesisBootstrapData` in one runtime component, for example
`GenesisBootstrapDataProvider`, from the parsed genesis configuration at startup.
All `GenesisBlockEvent` publishers should consume this provider instead of
parsing Shelley genesis independently.

The provider should expose the parsed payload and the resolved Shelley genesis
hash. The hash must reuse Yano's existing Shelley genesis hash resolution:
configured `shelley-genesis-hash` when present, otherwise blake2b-256 of the
configured Shelley genesis file bytes. Do not introduce a second hash definition
for the marker.

Publisher behavior:

| Publisher | Payload | Failure behavior |
| --- | --- | --- |
| `Yano.publishDirectStartGenesisBootstrapIfNeeded` | Attach provider payload when available. Metadata-only events are still allowed for old behavior. When Shelley genesis staking is non-empty and block 0 is already persisted, derive the producer pool hash from the stored block-0 issuer vkey and attach it to repair missing slot-0 reward facts on restart. | Do not swallow listener failures when an enriched payload is published; log and rethrow so startup fails. A payload is enriched when it carries non-empty Shelley staking or a resolved Shelley genesis hash, because hash-only empty-staking events still validate existing bootstrap markers. |
| `BodyFetchManager.publishGenesisBlockEventIfNeeded` | Attach provider payload on `freshChain` genesis publication. | Existing uncaught publish behavior is acceptable; listener failure propagates through the block apply path. |
| `BlockProducerHelper.publishGenesisBlockEventIfNeeded` | Attach provider payload for devnet block `0`; attach producer pool hash only when a signed local producer is active. | Publish outside the best-effort block-event catch, or rethrow genesis publish failures from the catch, so block production fails closed. If epoch metadata is unavailable and the payload is enriched, fail before storing block 0 instead of silently skipping the genesis event. |

The account-state listener must remain a synchronous listener for
`GenesisBlockEvent` (`AccountStateEventHandler`, order `110`). If future event
bus options make genesis listeners asynchronous, this bootstrap must opt out or
gain an explicit completion barrier before block application and reward
calculation continue.

### Idempotency Marker

The account-state handler owns idempotency. Metadata-only events without a
resolved Shelley genesis hash do not write the genesis-staking marker. Enriched
events whose parsed staking section is empty do not write a marker or any
staking state. If an existing marker is present and the empty-staking event
carries a resolved Shelley genesis hash, the handler must still verify that the
hash matches the marker. This keeps clean public-network starts byte-for-byte on
the existing path while preventing a stale chainstate bootstrapped from genesis A
from silently running under genesis B. Enriched events with non-empty Shelley
genesis staking check and write the marker.

Marker value:

- bootstrap payload version
- resolved Shelley genesis hash
- bootstrapped-at slot

If the marker exists with the same version and genesis hash, staking/deposit
writes no-op regardless of which publisher fired. The handler may still repair a
missing idempotent slot-0 block issuer fact when the same-genesis event carries a
producer pool hash. If the marker exists with a different genesis hash,
startup/block production fails with an explicit error. A payload version change
must be handled as a migration decision: either a deliberate no-op compatibility
path or an explicit one-time migration, not silent overwrite.

### Genesis Write Values

Genesis bootstrap must not reuse certificate-path activation delays. It must use
genesis protocol deposits for the genesis staking entries and must book those
deposits into account-state metadata and AdaPot epoch 0. Write the following
synthetic values:

| State | Genesis value | Reason |
| --- | --- | --- |
| `PREFIX_POOL_DEPOSIT` / `PoolRegistrationData.deposit` | `poolDeposit` from Shelley genesis protocol params. | Haskell books the genesis pool deposit into the deposit pot. For the DevKit fixture this is `500,000,000` lovelace per pool. |
| `PREFIX_POOL_DEPOSIT` / pool params | Parsed genesis margin, cost, pledge, reward account, owners, VRF, relays, and metadata where the local model supports them. | Downstream pool params should see the real genesis pool metadata. |
| `PREFIX_POOL_PARAMS_HIST` | Same pool params with `activeEpoch = 0`. | Genesis pools are active from genesis; do not apply `currentEpoch + 2`/`+3` certificate delays. |
| `PREFIX_POOL_REG_SLOT` | Genesis event slot, normally `0`. | Stale-delegation filtering must consider genesis delegations valid for the genesis pool lifecycle. |
| `PREFIX_POOL_DELEG` | Parsed pool hash, genesis event slot, `txIdx = 0`, deterministic synthetic `certIdx` from sorted delegation order. | Snapshot filtering needs a stable delegation point without implying a real transaction. |
| `PREFIX_ACCT` for delegated genesis stake credentials | reward `0`, deposit `keyDeposit` from Shelley genesis protocol params. | Haskell books the genesis stake key deposit. For the DevKit fixture this is `2,000,000` lovelace per delegated credential. |
| `PREFIX_ACCT_REG_SLOT` for delegated genesis stake credentials | Genesis event slot. | Snapshot stale-registration checks need the genesis registration point. |
| `PREFIX_STAKE_EVENT` for delegated genesis stake credentials | Synthetic registration event at genesis slot with deterministic synthetic indexes. | Reward calculation's "ever registered" queries need explicit registration history for credentials that genesis actually registered. |
| `cfEpochSnapshot` initial mark snapshot | Internal snapshot key `-1`, keyed by delegated genesis stake credential, value `{poolHash, amount}` where `amount` is aggregated from Shelley `initialFunds` by stake credential. | Represents Haskell's `resetStakeDistribution` initial `ssStakeMark`. Yano reward epoch 3 reads snapshot key `N - 4 = -1`; without this row, the first reward-bearing epoch sees `snapshot=0` even though the genesis account/delegation prefixes exist. |
| `META_TOTAL_DEPOSITED` | Existing total plus `poolDeposit * poolCount + keyDeposit * delegationCount`, once. | Keeps the cumulative deposit pot aligned with Haskell and later certificate deltas. |
| `PREFIX_ADAPOT` epoch 0, if AdaPot tracking is enabled | Same treasury/reserves as current genesis bootstrap, with `deposits = META_TOTAL_DEPOSITED`. Initial reserves stay `maxLovelaceSupply - sum(initialFunds)`. | Haskell reports genesis deposits in the deposit pot without subtracting them from initial reserves. |
| `PREFIX_BLOCK_ISSUER` for the genesis event slot, when a producer pool hash is present and that pool exists in Shelley genesis staking | Producer pool hash at `(epoch = event.epoch, slot = event.slot)`, written only if missing. | Haskell counts the real direct-start slot-0 producer block in reward activity. This is an idempotent per-block fact, not a synthetic extra block. |
| Pool reward account from pool params only | No `PREFIX_ACCT`, no `PREFIX_ACCT_REG_SLOT`, no `PREFIX_STAKE_EVENT`. | A reward account reference is not itself a stake registration. |

The bootstrap should use a single RocksDB `WriteBatch`. Writes should be
deterministic: sort genesis pools by pool hash and stake delegations by stake
credential hash before assigning synthetic indexes.

Yano must not create a synthetic yaci-store-style genesis row. Instead, for
local direct-start block production, the event carries the actual producer pool
hash and `DefaultAccountStateStore` seeds the normal per-block issuer key for
the real slot-0 block if it is missing. The write is constrained to non-empty
genesis staking and only accepted when the producer pool is one of the parsed
genesis pools, so public-network sync and metadata-only genesis events remain
unchanged.

### Snapshots

Seed Haskell's initial mark snapshot directly when Shelley genesis staking is
non-empty. Haskell creates that mark snapshot during `resetStakeDistribution`,
before any epoch-boundary `SNAP` transition. Yano's reward calculation for epoch
3 reads snapshot key `N - 4 = -1`; the ordinary epoch-boundary snapshot path
starts too late for that first reward-bearing devnet epoch. The seed should
write `cfEpochSnapshot` entries for every parsed genesis delegation at Yano's
internal snapshot key `-1`, using the delegated pool hash and the amount
aggregated from Shelley `initialFunds` addresses for that stake credential.

This snapshot seed remains guarded by non-empty Shelley genesis staking and an
enriched `GenesisBlockEvent`. Public networks with empty staking sections do not
write snapshot rows. This seed must not create a synthetic slot or block.
Integration verification must assert that epoch 3 reward inputs become non-empty
(`snapshot > 0`, `pools > 0`, `activeStake > 0`).

### Rollback

Genesis-staking bootstrap represents chain genesis state, not a normal block
delta. Ordinary rollback must not duplicate deposits, duplicate delegations, or
remove the marker in a way that causes replay to diverge. The marker and
idempotent writes are the primary safety mechanism across restart, rollback, and
replay.

## Public-Network Safety

The implementation must preserve current public-network behavior:

- Empty or missing `staking` in Shelley genesis must result in no pool,
  delegation, stake-account, marker, or AdaPot-deposit bootstrap writes.
- Mainnet, preprod, preview, and other public sync paths continue to derive
  pool/delegation state only from on-chain certificates.
- Genesis AdaPot reserves and protocol parameter bootstrapping remain unchanged
  for public networks. For non-empty genesis staking, AdaPot deposits are
  initialized to the genesis deposit total while reserves stay unchanged.
- The new event payload is optional, so existing metadata-only genesis event
  publications remain valid.
- The bootstrap handler must be idempotent. Re-publishing the genesis event on
  restart must not duplicate deposits, overwrite newer on-chain state, or
  re-run epoch boundary logic.

## Rationale

Using the existing genesis event keeps startup loosely coupled. The runtime owns
genesis parsing and event publication; account state owns how genesis staking is
represented in RocksDB.

Adding the data to `GenesisBlockEvent` is cleaner than direct calls from runtime
to `DefaultAccountStateStore` because other listeners may need genesis data in
the future. The existing account-state subscription is synchronous and ordered
at `110`, so the bootstrap can complete before later block application and
reward calculation when published from a fail-closed path.

The store should seed its normal prefixes instead of adding special cases to
reward calculation. That keeps snapshots, reward calculation, DRep distribution,
REST account APIs, rollback/restart behavior, and future state inspection using
the same state model as certificate-derived data.

## Consequences

Positive:

- Devnet genesis-derived AdaPot inputs align with Haskell and yaci-store when
  Shelley genesis contains pools and delegations: deposits, initial stake,
  initial pool state, and the real slot-0 producer block are represented.
- Multiple genesis pools and delegations are supported from the start.
- Public-network behavior remains unchanged because their genesis staking data is
  empty.
- Genesis-derived state follows the existing event/listener architecture.
- Account-state persistence stays in one place.

Tradeoffs:

- `GenesisBlockEvent` becomes a richer bootstrap event and needs careful
  compatibility constructors or factories.
- `DefaultAccountStateStore` needs an idempotent genesis bootstrap path distinct
  from normal certificate processing.
- AdaPot deposit values must now be carried forward from account-state deposits
  at epoch boundaries instead of being reset to zero.
- Tests must cover event publication ordering and restart idempotency.

## Implementation Notes

Suggested payload shape:

```java
public record GenesisBlockEvent(
        Era era,
        int epoch,
        long slot,
        long blockNumber,
        String blockHash,
        GenesisBootstrapData bootstrapData,
        String producerPoolHash
) implements Event {
    public GenesisBlockEvent(Era era, int epoch, long slot, long blockNumber, String blockHash) {
        this(era, epoch, slot, blockNumber, blockHash, GenesisBootstrapData.empty(), null);
    }
}
```

`GenesisBootstrapData` should be immutable and contain optional sections. The
Shelley staking section can be modeled with Yano-owned records rather than
depending on yaci-store classes.

Suggested staking payload shape:

```java
public record GenesisBootstrapData(
        String shelleyGenesisHashHex,
        ShelleyGenesisBootstrap shelley
) {
    public static GenesisBootstrapData empty() { ... }
}

public record ShelleyGenesisBootstrap(
        Map<String, BigInteger> initialFunds,
        BigInteger maxLovelaceSupply,
        BigInteger keyDeposit,
        BigInteger poolDeposit,
        List<GenesisPool> pools,
        List<GenesisDelegation> delegations
) {}

public record GenesisPool(
        String poolHash,
        String vrfKeyHash,
        BigInteger pledge,
        BigInteger cost,
        BigInteger marginNumerator,
        BigInteger marginDenominator,
        String rewardAccount,
        Set<String> owners,
        List<GenesisRelay> relays,
        String metadataUrl,
        String metadataHash
) {}

public record GenesisRelay(String type, String host, Integer port) {}

public record GenesisDelegation(String stakeCredentialHash, String poolHash) {}
```

The account-state bootstrap should use a single RocksDB `WriteBatch` and should
record the durable marker described above.

## Test Plan

Parser tests:

- parse a devnet Shelley genesis with one pool and one delegation
- parse multiple pools and multiple delegated stake credentials
- parse empty public-network `staking` as an empty optional section
- parse embedded `extraConfig.initialFunds.data`
- fail closed when non-empty top-level `initialFunds` and
  `extraConfig.initialFunds` are both present
- fail closed for `extraConfig.initialFunds.file` until Yano supports the same
  file injection semantics as Haskell
- parse reward accounts with key and script credentials where supported
- preserve pool owners, cost, pledge, margin, and VRF key hash

Account-state tests:

- publish a `GenesisBlockEvent` with staking payload and assert:
  - pools are registered
  - pool deposit equals the Shelley genesis `poolDeposit`
  - pool params history is available at epoch 0
  - delegations are present
  - delegated stake credential deposit equals the Shelley genesis `keyDeposit`
  - total deposited equals `poolDeposit * poolCount + keyDeposit * delegationCount`
  - epoch-0 AdaPot deposits equal total deposited while reserves stay unchanged
  - slot-0 producer block issuer fact exists exactly once when producer pool hash
    is supplied
  - `cfEpochSnapshot` at internal snapshot key `-1` includes genesis delegated
    stake and the balance aggregated from Shelley `initialFunds`
  - pool reward account is not automatically registered
- re-publish the same event and assert no duplicate or changed state, especially
  no doubled deposits
- publish with empty staking payload and assert no pool/delegation/marker/AdaPot
  deposit writes
- publish with empty staking payload against an existing marker for a different
  genesis hash and assert an explicit startup/block-production failure
- restart/reopen RocksDB and verify the bootstrap marker prevents duplicate
  writes
- publish enriched events from direct-start, sync-from-peer fresh chain, and
  devnet block production paths and verify identical resulting state
- publish against an existing marker for a different genesis hash and assert an
  explicit startup/block-production failure
- apply genesis bootstrap, process blocks and an epoch boundary, roll back to
  genesis, replay, and assert pool/delegation state and deposits remain stable

Integration tests:

- direct-start devnet with genesis staking produces non-empty reward inputs:
  at epoch 3, `snapshot > 0`, `pools > 0`, `activeStake > 0`
- compare Yano, Haskell node, and yaci-store AdaPot on a Yaci DevKit-style
  direct-start devnet across enough epoch boundaries to verify deposits,
  treasury, and reserves
- if deposits and reward inputs match but treasury/reserves diverge by reward
  arithmetic only, handle that as a separate reward-library parity issue rather
  than widening the genesis bootstrap design
- run public-network genesis parser and startup smoke tests to verify no new
  pools or delegations are created from empty staking sections

Regression checks:

- existing mainnet/preprod AdaPot tests continue to pass
- existing epoch-parameter bootstrap behavior from `GenesisBlockEvent` continues
  to pass
- block production must fail if genesis staking bootstrap fails

## References

- `core-api/src/main/java/com/bloxbean/cardano/yano/api/events/GenesisBlockEvent.java`
- `ledger-state/src/main/java/com/bloxbean/cardano/yano/ledgerstate/AccountStateEventHandler.java`
- `ledger-state/src/main/java/com/bloxbean/cardano/yano/ledgerstate/DefaultAccountStateStore.java`
- `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/genesis/ShelleyGenesisParser.java`
- `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/genesis/ShelleyGenesisData.java`
- Haskell ledger:
  `/Users/satya/work/cardano-comm-projects/cardano-ledger/eras/shelley/impl/src/Cardano/Ledger/Shelley/Transition.hs`
- Haskell ledger:
  `/Users/satya/work/cardano-comm-projects/cardano-ledger/eras/conway/impl/src/Cardano/Ledger/Conway/Transition.hs`
- Haskell ledger:
  `/Users/satya/work/cardano-comm-projects/cardano-ledger/libs/cardano-ledger-core/src/Cardano/Ledger/State/SnapShots.hs`
- yaci-store:
  `components/common/src/main/java/com/bloxbean/cardano/yaci/store/common/genesis/ShelleyGenesis.java`
- yaci-store:
  `aggregates/adapot/src/main/java/com/bloxbean/cardano/yaci/store/adapot/processor/GenesisPoolProcessor.java`
