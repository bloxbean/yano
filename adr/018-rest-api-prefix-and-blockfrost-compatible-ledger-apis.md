# ADR-018: REST API Prefix and Blockfrost-Compatible Ledger APIs

## Status

Draft

## Date

2026-04-28

## Context

`node-app` currently exposes production REST resources with the API version
hard-coded in each JAX-RS resource class:

- `YaciNodeResource`: `@Path("/api/v1/node")`
- `BlockResource`: `@Path("/api/v1/blocks")`
- `EpochResource`: `@Path("/api/v1/epochs")`
- `AccountStateResource`: `@Path("/api/v1/accounts")`
- `UtxoResource`: `@Path("/api/v1")`
- `TransactionResource`: `@Path("/api/v1/txs")`
- similar hard-coded paths for scripts, genesis, status, tx submission, utils,
  devnet, and epoch nonce endpoints

There is also `DebugSnapshotResource` under `@Path("/api/debug")`. Some of
those debug reads have matured enough to expose as production APIs, while others
are still too expensive or too diagnostic-focused.

The next API work should:

- avoid repeating `/api/v1` in every resource class,
- make the public prefix configurable through `yaci.node.api-prefix`, defaulting
  to `/api/v1`,
- preserve existing URLs by default,
- add production endpoints for AdaPot and account state,
- follow Blockfrost endpoint names and response shapes where Yano has the
  backing ledger state,
- avoid promoting full-scan debug endpoints as production endpoints.

Blockfrost references used for compatibility:

- Blockfrost OpenAPI source: <https://github.com/blockfrost/openapi>
- Blockfrost account endpoint family includes:
  - `GET /accounts/{stake_address}`
  - `GET /accounts/{stake_address}/rewards`
  - `GET /accounts/{stake_address}/withdrawals`
  - `GET /accounts/{stake_address}/delegations`
  - `GET /accounts/{stake_address}/registrations`
  - `GET /accounts/{stake_address}/mirs`
  - `GET /accounts/{stake_address}/addresses`
  - `GET /accounts/{stake_address}/addresses/assets`
- `node-app` currently uses RESTEasy Classic, which supports a root path for all
  REST endpoints via `quarkus.resteasy.path`, resolved relative to
  `quarkus.http.root-path`.

## Goals

1. Move the public API prefix out of individual resource paths.
2. Keep `GET /api/v1/...` behavior unchanged with default config.
3. Add cheap production AdaPot endpoints.
4. Add Blockfrost-compatible account endpoints backed by direct indexes.
5. Identify which debug endpoints can be promoted and which must remain debug.
6. Define implementation phases without changing code in this ADR.

## Non-Goals

- Do not attempt full Blockfrost API coverage in the first pass.
- Do not expose expensive full-chain or full-UTXO scans as production endpoints.
- Do not remove existing debug endpoints immediately.
- Do not require Yano to implement Blockfrost authentication or rate limiting in
  this phase.
- Do not make `DebugSnapshotResource` response shapes part of the public API
  contract.

## API Prefix Plan

### Configuration

Add a new config property:

```yaml
yaci:
  node:
    api-prefix: /api/v1
```

Use it to configure the REST application root:

```yaml
quarkus:
  resteasy:
    path: ${yaci.node.api-prefix:/api/v1}
```

`node-app` currently uses RESTEasy Classic (`quarkus-resteasy`), so the
implemented property is `quarkus.resteasy.path`. If the app later migrates to
Quarkus REST, revisit this property name.

Then refactor production resources to use relative resource paths:

- `@Path("node")`
- `@Path("blocks")`
- `@Path("epochs")`
- `@Path("accounts")`
- `@Path("")` or class-specific split for address/utxo routes
- `@Path("txs")`
- etc.

Important detail: absolute JAX-RS paths such as `@Path("/api/v1/accounts")`
must be removed from production resources. If the path remains absolute, a
server-level REST root path cannot reliably own the API prefix.

### Debug Path Policy

Keep debug endpoints outside the public prefix by default:

```text
/api/debug/...
```

If a future deployment wants debug endpoints under the same prefix, add a
separate explicit property such as:

```yaml
yaci.node.debug-api-prefix: /api/debug
```

Do not couple debug endpoint routing to the public Blockfrost-compatible API
prefix.

## Production AdaPot Endpoints

Blockfrost does not define an AdaPot endpoint, so these should be Yano extension
endpoints under the existing epoch namespace:

```text
GET /epochs/latest/adapot
GET /epochs/{number}/adapot
GET /epochs/adapots?from={epoch}&to={epoch}&page=1&count=20&order=asc
```

With the default prefix, these resolve to:

```text
GET /api/v1/epochs/latest/adapot
GET /api/v1/epochs/{number}/adapot
GET /api/v1/epochs/adapots
```

Response fields should use Blockfrost-style JSON naming and string-encoded
lovelace values:

```json
{
  "epoch": 627,
  "treasury": "1621148478340078",
  "reserves": "6410741060944473",
  "deposits": "4418026000000",
  "fees": "39737903986",
  "distributed_rewards": "6688719045189",
  "undistributed_rewards": "8435206026340",
  "rewards_pot": "18904906339411",
  "pool_rewards_pot": "15123925071529"
}
```

Data source:

- `DefaultAccountStateStore.getAdaPotTracker().getAdaPot(epoch)`
- latest epoch can be derived from the chain tip epoch or latest AdaPot scan
  helper.

Performance:

- Point lookup is cheap.
- Range lookup is acceptable only with pagination or bounded `from/to`.
- Add a maximum page size, likely 100.

Promote from debug:

- `GET /api/debug/adapot/{epoch}` can be promoted.
- `GET /api/debug/adapot-chain` can be promoted only after adding pagination,
  range bounds, response shape cleanup, and production error handling.

## Blockfrost-Compatible Account Endpoint Plan

### Address Normalization

Accept Blockfrost-compatible stake address input:

```text
GET /accounts/{stake_address}
```

The resource should normalize:

- bech32 stake addresses (`stake1...`, `stake_test1...`),
- hex reward account bytes where useful for internal/admin callers,
- credential type and hash extracted from the reward address.

Return `400` for invalid stake address format and `404` for a well-formed but
unknown account.

### `GET /accounts/{stake_address}`

Target Blockfrost-compatible response:

```json
{
  "stake_address": "stake1...",
  "active": true,
  "registered": true,
  "active_epoch": 621,
  "controlled_amount": "123456789",
  "withdrawable_amount": "12345",
  "pool_id": "pool1...",
  "drep_id": "drep1..."
}
```

Fields Yano can support immediately from current account state:

- `active`: current pool delegation exists
- `registered`: current stake credential registration exists
- `withdrawable_amount`: `getRewardBalance`
- `pool_id`: `getDelegatedPool`, converted to bech32 pool id
- `controlled_amount`: current UTXO balance controlled by the stake credential
  plus the current withdrawable reward balance
- `drep_id`: current DRep delegation converted to CIP-129 bech32 id
- Yano extension fields: `current_utxo_balance`, `stake_deposit`, `pool_hash`,
  `drep_type`, `drep_hash`

Fields intentionally out of scope for this endpoint:

- `rewards_sum`: cumulative earned rewards
- `withdrawals_sum`: cumulative withdrawals
- `reserves_sum`: cumulative MIR reserves credited to this account
- `treasury_sum`: cumulative MIR treasury and treasury withdrawals credited to
  this account

Additional current-state field supported from existing delegation metadata:

- `active_epoch`: Blockfrost-compatible epoch of the most recent stake
  registration action for currently registered accounts. For deregistered
  accounts, this is omitted unless a latest stake action index is added later.
- `delegation_epoch`: Yano extension field for the epoch of the current pool
  delegation certificate. This is intentionally separate from `active_epoch`
  because Blockfrost defines `active_epoch` around stake registration state, not
  current pool delegation.

This is intentionally a read-only API projection. It does not add block
processing writes, storage migration, startup rebuild, or rollback logic.

Recommendation:

- `controlled_amount` is required for Yano to be useful as a wallet backend.
  Implement it before exposing this endpoint as production-ready.
- Treat the cumulative `*_sum` fields as not supported in the first production
  pass unless per-account history indexes exist. Do not fill historical sums
  with guessed values.
- If strict Blockfrost compatibility requires all fields, delay declaring the
  endpoint fully Blockfrost-compatible until the historical sums are backed by
  stable indexes. A Yano extension endpoint may omit or explicitly mark
  unsupported aggregate-history fields.

Important accounting split:

- The stake-credential balance aggregate should store **current UTXO lovelace
  only**.
- `withdrawable_amount` should come from the account reward balance.
- API `controlled_amount` should be computed as:

```text
controlled_amount = current_utxo_lovelace_by_stake_credential + withdrawable_reward_balance
```

This keeps the UTXO-derived index aligned with `cfUnspent` and avoids mixing
reward-account accounting into UTXO storage.

### Current Account Summary

Earlier design considered a Yano-only
`GET /accounts/{stake_address}/balance` endpoint for current account balances.
That endpoint is redundant once `GET /accounts/{stake_address}` returns the
same current balance fields, so it is not exposed in the production account
resource.

Current-account response shape:

```json
{
  "stake_address": "stake1...",
  "active": true,
  "reward_balance": "12345",
  "stake_deposit": "2000000",
  "utxo_balance": "123456789",
  "utxo_count": 12,
  "controlled_amount": "123469134",
  "pool_id": "pool1...",
  "drep": {
    "type": "key_hash",
    "hash": "..."
  }
}
```

Fields available cheaply today:

- `active`
- `reward_balance`
- `stake_deposit`
- `pool_id`
- current DRep delegation

Fields requiring a new stake-credential UTXO balance index:

- `utxo_balance`
- `utxo_count`
- `controlled_amount`

Do not implement current-account balance fields with the existing debug
full-UTXO scan. They should depend on the same aggregate/index used by
`/accounts/{stake_address}` and future `/accounts/{stake_address}/utxos`.

`controlled_amount` follows the same rule as the Blockfrost aggregate endpoint:
`utxo_balance + reward_balance`. The persisted stake-credential aggregate
remains UTXO-only.

### `GET /accounts/{stake_address}/rewards`

Blockfrost-compatible endpoint for reward history.

Target fields:

```json
[
  {
    "epoch": 627,
    "amount": "12345",
    "pool_id": "pool1...",
    "type": "member"
  }
]
```

Implementation requirement:

- Maintain or expose a per-account reward history index from reward
  distribution writes.
- Include reward type where known: `leader`, `member`, `pool_deposit_refund`,
  or Yano-compatible extensions for MIR/treasury withdrawal if they are not
  represented by Blockfrost's reward endpoint.

### `GET /accounts/{stake_address}/withdrawals`

Blockfrost-compatible endpoint for reward withdrawals.

Target fields:

```json
[
  {
    "tx_hash": "...",
    "amount": "12345"
  }
]
```

Optional newer Blockfrost-compatible fields can be added when available:

- `tx_slot`
- `block_height`
- `block_time`

Implementation requirement:

- `DefaultAccountStateStore.processWithdrawal` currently updates reward
  balance. Production history requires an append-only per-account withdrawal
  index keyed by stake credential and slot/tx/cert order.

### `GET /accounts/{stake_address}/delegations`

Blockfrost-compatible endpoint for delegation history.

Target fields:

```json
[
  {
    "active_epoch": 621,
    "tx_hash": "...",
    "amount": "0",
    "pool_id": "pool1..."
  }
]
```

Implementation requirement:

- Current direct lookup can return the active pool delegation.
- History requires a per-account delegation event index with tx hash, slot,
  certificate index, and effective epoch.

### `GET /accounts/{stake_address}/registrations`

Blockfrost-compatible endpoint for stake registration history.

Target fields:

```json
[
  {
    "tx_hash": "...",
    "action": "registered"
  }
]
```

Implementation requirement:

- Add or expose per-account registration/deregistration history.
- Include `tx_slot`, `block_height`, and `block_time` when available.

### `GET /accounts/{stake_address}/mirs`

Blockfrost-compatible endpoint for MIR history.

Implementation requirement:

- Existing MIR processing tracks reward-rest flows for reward calculation.
- Production API needs a stable account-scoped MIR history index with tx hash,
  amount, and source pot.

### `GET /accounts/{stake_address}/utxos`

Blockfrost has account UTXO models in its OpenAPI clients. Yano should expose
this only after adding a direct stake-credential-to-UTXO index.

Do not promote the current debug implementation of
`/api/debug/utxo-balance/{credHash}` as-is. It iterates every unspent UTXO and
extracts delegation credentials from each address, which is too expensive for a
production endpoint on mainnet.

## Required Storage and API Extensions

### Account State Store Read API

Extend `LedgerStateProvider` or `AccountStateStore` with point and history
queries:

```java
Optional<AccountInfo> getAccountInfo(int credType, String credHash);
List<AccountReward> getAccountRewards(int credType, String credHash, int page, int count, Order order);
List<AccountWithdrawal> getAccountWithdrawals(int credType, String credHash, int page, int count, Order order);
List<AccountDelegation> getAccountDelegations(int credType, String credHash, int page, int count, Order order);
List<AccountRegistration> getAccountRegistrations(int credType, String credHash, int page, int count, Order order);
List<AccountMir> getAccountMirs(int credType, String credHash, int page, int count, Order order);
```

Avoid forcing `node-app` resources to downcast to `DefaultAccountStateStore`
for production endpoints.

### UTXO State Read API

Add direct stake-credential UTXO balance and listing support:

```java
Optional<UtxoBalanceSummary> getUtxoBalanceByStakeCredential(int credType, String credHash);
List<Utxo> getUtxosByStakeCredential(int credType, String credHash, int page, int count, Order order);
```

Backing index options:

1. Maintain a balance aggregate per stake credential during block apply and
   rollback.
2. Maintain a stake-credential-to-outpoint index for UTXO listing.
3. Keep existing address and payment credential indexes unchanged.

This avoids full scans and makes `/accounts/{stake_address}` and
`/accounts/{stake_address}/utxos` production-safe.

### Live Stake-Credential UTXO Balance Aggregate

For wallet-backend use cases, Yano needs a cheap current `controlled_amount`.
The first production step should be a compact aggregate:

```text
stake_credential -> current_unspent_lovelace
```

This aggregate is UTXO-only. It should not include reward balances, reward
history, MIR history, withdrawals, or deposits. API code combines it with the
current reward balance when returning `controlled_amount`.

Configuration:

```yaml
yaci:
  node:
    account:
      stake-balance-index-enabled: true
```

- Default should be `true` because wallet-backend use cases need
  `controlled_amount`.
- When `false`, Yano must not maintain the live stake-credential UTXO balance
  aggregate or its rollback journal.
- When `false`, APIs that require current UTXO balance or `controlled_amount`
  should return `503` with a clear feature-disabled error, or omit those fields
  only from Yano extension endpoints whose schema explicitly allows omission.
- Do not fall back to full UTXO scans in production request paths when the flag
  is disabled.

Correctness model:

- Treat the aggregate as a materialized view of current `cfUnspent`.
- Update it in the same RocksDB `WriteBatch` as UTXO apply and rollback.
- For created outputs, extract the stake credential from the output address and
  add lovelace.
- For spent inputs, read the exact previous UTXO record being removed from
  `cfUnspent` and subtract that record's lovelace. Do not derive subtracts from
  the transaction body alone.
- On rollback, invert the same block: subtract created outputs and add back
  spent outputs restored from `cfSpent`.
- Handle intra-block spend as net zero if an output is created and consumed in
  the same block.
- Handle invalid transaction collateral: collateral inputs subtract, collateral
  return adds.

Rollback safety:

- Either derive rollback updates from the existing UTXO block delta log, or add
  a compact `stake_balance_delta` journal.
- Prefer storing previous balances for touched credentials in the journal if the
  implementation complexity is acceptable; this allows exact restore and makes
  drift easier to diagnose.
- Retain the stake-balance rollback journal for at least the same rollback
  window as UTXO rollback data.

Conway pointer-address policy:

- Before Conway, pointer addresses may resolve to their pointed stake
  credential and count toward the aggregate.
- In Conway and later, pointer-address balances must not count toward any stake
  credential's UTXO balance or controlled amount.
- At the Conway transition, rebuild the aggregate once using Conway rules, or
  subtract any still-unspent pointer-address contributions that had been counted
  before the transition.
- The epoch snapshot path already applies this boundary rule by checking
  `isConwayOrLater(epoch + 1)` and disabling pointer resolution for Conway-era
  snapshots.

Storage and performance expectation:

- The aggregate is per active stake credential, not per UTXO.
- A mainnet full-sync chainstate observed at
  `/Users/satya/Downloads/tt/yano/node-app/chainstate` is about 291 GB.
- Budget roughly 100-600 MB persistent data for the aggregate on mainnet, with
  about 1 GB headroom for RocksDB compaction and write amplification.
- This is expected to be less than 0.5% of the observed chainstate size.
- During block apply, compute a per-block map of credential deltas and write one
  balance update per touched credential. The cost is proportional to unique
  stake credentials touched by the block, not total UTXOs in the database.
- Exact performance impact should be measured during full sync, but this should
  be substantially cheaper than a request-time full UTXO scan.

Operational constraints:

- If Yano runs with UTXO storage filters that do not retain all mainnet UTXOs,
  it must not advertise complete account `controlled_amount`.
- Provide a rebuild command or startup maintenance mode that recomputes the
  aggregate from `cfUnspent`.
- Add periodic verification by comparing aggregate totals against a full
  `cfUnspent` scan in debug/maintenance mode.
- Keep the aggregate implementation isolated from ledger validation,
  transaction submission, reward calculation, AdaPot tracking, and existing UTXO
  query behavior. It is a derived read model only.
- A failure to update the aggregate while the feature is enabled should fail the
  same block batch instead of silently continuing with drift. When the feature
  is disabled, no aggregate update code should run.
- The first implementation should avoid changing existing public response
  shapes except for endpoints explicitly added in this ADR.

## Debug Endpoint Promotion Matrix

| Debug endpoint | Production action | Reason |
| --- | --- | --- |
| `/api/debug/adapot/{epoch}` | Promote | RocksDB point lookup, stable semantics |
| `/api/debug/adapot-chain` | Promote after pagination | Cheap bounded range, but needs limits |
| `/api/debug/pool-params/{poolHash}` | Consider promote under `/pools/{pool_id}` | Point lookup, useful public data |
| `/api/debug/pool-params/{poolHash}/epoch/{epoch}` | Consider promote later | Historical pool params are useful but need response compatibility |
| `/api/debug/epoch-fees/{epoch}` | Consider promote under `/epochs/{number}` | Cheap if retained, but retention must be explicit |
| `/api/debug/epoch-block-counts/{epoch}` | Keep debug or add paged pool stats | Can be large and retention-dependent |
| `/api/debug/epoch-snapshot/{epoch}` | Keep debug | Large response, snapshot retention-dependent |
| `/api/debug/reward-inputs/{epoch}` | Keep debug | Diagnostic, large, reward-calculation internal shape |
| `/api/debug/utxo-balance/{credHash}` | Do not promote | Full UTXO scan; replace with index-backed account balance |
| `/api/debug/deregistered-accounts/{epoch}` | Keep debug initially | Diagnostic and slot-range scan behavior |
| `/api/debug/retired-pools/{epoch}` | Consider promote under pools | Bounded list, but align with Blockfrost pool endpoint names |

## Response Compatibility Rules

For Blockfrost-compatible endpoints:

- Use Blockfrost path names where possible.
- Use snake_case JSON fields.
- Encode lovelace and asset quantities as strings.
- Keep `page`, `count`, and `order` query parameters.
- Cap `count`, default to 20, max 100.
- Return arrays directly for history endpoints.
- Return `404` for not found.
- Return `400` for invalid addresses or malformed path parameters.
- Return `503` when the required Yano state component is disabled.

For Yano extension endpoints:

- Keep paths under existing namespaces where intuitive, such as `/epochs`.
- Do not pretend extension endpoints are Blockfrost-compatible.
- Document extension response schemas in OpenAPI.

## Implementation Phases

### Phase 1: Prefix Refactor

Status: completed on 2026-04-28.

- Added `yaci.node.api-prefix` default `/api/v1`.
- Configured RESTEasy Classic root path using
  `quarkus.resteasy.path: ${yaci.node.api-prefix:/api/v1}`.
- Converted production resource class paths from absolute `/api/v1/...` to
  relative paths.
- Left debug endpoints unchanged at `/api/debug`.
- Updated startup log messages to use the configured API prefix.
- Added tests proving default routes remain unchanged.
- Added a non-default `/bf` prefix test using only `yaci.node.api-prefix`.

Verification:

```bash
./gradlew :node-app:test \
  --tests com.bloxbean.cardano.yano.app.YaciNodeResourceTest \
  --tests com.bloxbean.cardano.yano.app.ApiPrefixResourceTest \
  --tests com.bloxbean.cardano.yano.app.api.devnet.DevnetResourceTest
```

Result: passed. The test run emits existing Java 25/JBoss Threads shutdown
warnings and an existing warning about `config/application.yml.working`, but the
Gradle task succeeds.

### Phase 2: Production AdaPot API

- Move AdaPot DTOs out of `DebugSnapshotResource`.
- Add production endpoints under `EpochResource`.
- Add latest and bounded range support.
- Verify against Yaci Store and existing expected AdaPot JSON.
- Keep debug endpoints as aliases for now.

Status: API implementation completed on 2026-04-28; live mainnet/Yaci Store
cross-check remains a separate verification task.

Implemented:

- Added `LedgerStateProvider.AdaPotSnapshot` plus read-only AdaPot methods:
  `isAdaPotTrackingEnabled`, `getAdaPot`, and `getLatestAdaPot`.
- Added `NodeAPI.getLedgerStateProvider()` as a default method so production
  resources can use the stable API contract instead of downcasting to
  `YaciNode` or `DefaultAccountStateStore`.
- Mapped persisted RocksDB AdaPot CBOR in `DefaultAccountStateStore` to the
  stable provider snapshot.
- Added production DTO `AdaPotDto` with string-encoded lovelace and
  Blockfrost-style snake_case JSON names.
- Added:
  - `GET /epochs/latest/adapot`
  - `GET /epochs/{number}/adapot`
  - `GET /epochs/adapots?from={epoch}&to={epoch}&page=1&count=20&order=asc`
- Kept the range endpoint bounded with `count` limited to `1..100`; pagination
  is applied to epoch numbers before point lookups, so it does not scan a wide
  range to build one response.
- Production error behavior:
  - `503` when ledger/AdaPot tracking is unavailable or disabled,
  - `404` when a requested epoch has no AdaPot data,
  - `400` for invalid range, page, count, or order parameters.

Verification:

```bash
../gradlew :node-app:test \
  --tests com.bloxbean.cardano.yano.app.api.epochs.EpochResourceAdaPotTest \
  --tests com.bloxbean.cardano.yano.app.YaciNodeResourceTest \
  --tests com.bloxbean.cardano.yano.app.ApiPrefixResourceTest
```

Result: passed. The test run still emits existing warnings about
`config/application.yml.working`, multiple SLF4J providers, annotation
processor source level, and the Java 25/JBoss Threads shutdown issue, but the
Gradle task succeeds.

### Phase 3: Account Current State API

- Add stake address normalization helper.
- Add direct account info query APIs to `AccountStateStore`.
- Add stake-credential UTXO balance aggregate.
- Implement `GET /accounts/{stake_address}` for current reward balance, stake
  deposit, UTXO balance, controlled amount, pool delegation, and DRep delegation.
- Do not keep a duplicate Yano-only `/accounts/{stake_address}/balance` endpoint
  once the Blockfrost-style account summary exposes the same data.
- Add tests for registered, deregistered, delegated, undelegated, and unknown
  accounts.

Status: first current-balance task completed on 2026-04-28.

Implemented:

- Added `UtxoState` production read methods for the live stake-credential UTXO
  balance aggregate:
  - `isStakeBalanceIndexEnabled`
  - `isStakeBalanceIndexReady`
  - `getUtxoBalanceByStakeCredential`
- Added RocksDB CF `utxo_stake_balance` and maintain it from
  `DefaultUtxoStore` block apply, rollback, genesis UTXO, faucet, bootstrap,
  and direct UTXO-removal paths.
- Added config `yaci.node.account.stake-balance-index-enabled`, defaulting to
  `true`.
- Added a readiness marker so upgraded chainstates that already contain UTXOs
  but have never built this aggregate do **not** return incorrect
  `controlled_amount`. Those stores return feature-unavailable until a rebuild
  task is added and run.
- Added current account summary endpoint:

```text
GET /accounts/{stake_address}
```

The endpoint returns current state only:

- `stake_address`
- `active`
- `current_utxo_balance`
- `withdrawable_amount`
- `controlled_amount`
- `stake_deposit`
- `pool_id`
- `pool_hash`
- `drep_type`
- `drep_hash`

`controlled_amount` is computed as:

```text
current_utxo_balance + withdrawable_amount
```

The endpoint accepts bech32 reward/stake addresses and hex reward address bytes.
It returns:

- `400` for invalid stake address input,
- `503` if account state, UTXO state, or the stake-balance index is unavailable,
  disabled, not ready, or filtered,
- `404` when the account has no registration, reward, delegation, deposit, or
  UTXO balance.

Pointer-address policy in this first current-balance implementation:

- pointer addresses are not counted in the live current aggregate;
- this is correct for Conway-era `controlled_amount`, which is the target of
  the production wallet-backend API;
- pre-Conway pointer inclusion remains relevant for historical stake snapshot
  logic, not for this current balance endpoint.

Remaining work before declaring full Blockfrost `GET /accounts/{stake_address}`
compatibility:

- decide exact representation for unsupported Blockfrost historical fields in
  documentation and client compatibility notes,
- add broader registered/deregistered account endpoint tests,
- optionally add stake-credential UTXO listing support.

Current-state bech32 response enrichment completed on 2026-04-29.

Implemented:

- Added shared `CardanoBech32Ids` in `node-api` so API/export modules can
  format presentation ids without duplicating bech32 logic.
- The shared formatter uses Cardano Client Lib APIs:
  - `Credential` + `AddressProvider` for stake reward addresses,
  - `Bech32` for pool ids,
  - `GovId` for DRep ids.
- Kept the utility interface primitive/string based:
  - `stakeAddress(credType, credHash, protocolMagic)`
  - `poolId(poolHash)`
  - `drepId(drepType, drepHash)`
- Added bech32 fields to the account current-state list endpoints:
  - `GET /accounts/registrations`: `stake_address`
  - `GET /accounts/delegations`: `stake_address`, `pool_id`
  - `GET /accounts/drep-delegations`: `stake_address`, `drep_id`
  - `GET /accounts/pools`: `pool_id`
  - `GET /accounts/pool-retirements`: `pool_id`
- Reused the same formatter for existing `/accounts/{stake_address}` and
  delegation-history pool/DRep ids.
- Bech32 values remain response-layer presentation fields only. RocksDB and
  account-state records continue to store raw credential, pool, and DRep hashes.
- Raw fields such as `credential`, `pool_hash`, and `drep_hash` remain in the
  current-state responses for debugging and internal consumers.
- Malformed or unsupported bech32 conversions produce `null`, and DTOs omit
  null bech32 fields instead of returning raw hashes under bech32 field names.

Verification:

```bash
../gradlew :node-api:test \
  --tests com.bloxbean.cardano.yano.api.util.CardanoBech32IdsTest \
  :node-app:test \
  --tests com.bloxbean.cardano.yano.app.api.accounts.AccountStateResourceBalanceTest

../gradlew :node-app:quarkusBuild
```

Result: passed. The test run still emits existing log4j/SLF4J and Gradle
warnings, and the Quarkus build still emits existing duplicate-dependency and
deprecated native-property warnings, but both Gradle tasks succeed.

### Stake Balance Index Startup Migration

The live stake-balance aggregate has two independent states:

```text
yaci.node.account.stake-balance-index-enabled
utxo_meta["stake_balance_index_ready"]
```

Runtime rules:

- `enabled=false`: do not maintain the aggregate and do not serve
  `controlled_amount`.
- `enabled=true` and ready marker exists: maintain live deltas and serve
  `controlled_amount`.
- `enabled=true`, ready marker missing, and `utxo_unspent` has data: run a
  startup migration before sync/event processing starts.
- `enabled=true`, ready marker missing, and the UTXO store is empty: mark ready
  and maintain the aggregate during normal sync.

If the node applies any UTXO mutation while
`stake-balance-index-enabled=false`, it must remove the ready marker. This
handles the sequence:

```text
enabled=true  -> aggregate ready
enabled=false -> node syncs or rolls back without maintaining aggregate
enabled=true  -> rebuild required before controlled_amount can be trusted
```

Decision update on 2026-04-29:

- Do **not** expose a separate offline/Gradle stake-balance index build command
  as the normal user path. Most users run the Yano jar directly, and an
  external maintenance command would be easy to miss after upgrades or config
  changes.
- Implement this as a Yano startup pre-initialization migration. The migration
  is triggered solely by `stake-balance-index-enabled=true`; no additional
  `stake-balance-index-auto-rebuild-on-startup` flag is needed.
- Startup may take longer the first time after upgrading an existing chainstate
  or after re-enabling the feature. This is preferable to starting with an
  unavailable or partially correct `controlled_amount`.

Migration placement:

1. Open `DirectRocksDBChainState`.
2. Run startup migrations against RocksDB before registering UTXO event
   handlers, starting prune services, bootstrap writes, or auto-sync.
3. Initialize `DefaultUtxoStore`, which should observe the ready marker written
   by the migration.
4. Continue normal node startup.

The migration should be implemented as part of a small generic startup
migration framework, not as ad hoc code in the REST layer:

```java
interface StartupMigration {
    String id();
    boolean shouldRun(StartupMigrationContext context);
    StartupMigrationResult run(StartupMigrationContext context);
}
```

The migration runner should:

- run migrations synchronously during node creation/startup;
- keep migrations idempotent;
- log each migration id, reason, start time, progress, completion, and failure;
- avoid REST request-time migrations;
- avoid starting chain sync until required migrations finish;
- fail startup if a required migration fails and continuing would risk corrupt
  derived state.

Stake-balance migration behavior:

- remove the ready marker before rebuilding;
- clear `utxo_stake_balance`;
- scan current `utxo_unspent`;
- ignore Byron, enterprise, malformed, and pointer addresses;
- aggregate lovelace by stake credential;
- write aggregate entries in batches;
- write the ready marker only after the full rebuild succeeds;
- leave the marker absent on failure;
- fail node startup if `stake-balance-index-enabled=true`, `utxo_unspent` is
  non-empty, and the migration fails.

Progress logging:

- Log before the migration starts, including chainstate path and why it is
  required.
- Log the clear phase.
- During the scan phase, log progress at a bounded cadence, such as every
  1,000,000 scanned UTXOs or every 30 seconds, whichever comes first.
- During the write phase, log credential batches written.
- On completion, log scanned UTXO count, skipped UTXO count, credential count,
  total lovelace, and elapsed time.

UTXO filter policy:

- Complete account `controlled_amount` requires a complete, unfiltered UTXO
  store.
- If UTXO storage filters are enabled, startup should not claim the complete
  stake-balance index is ready. It should log that account controlled amount is
  unavailable for filtered chainstate.
- Any UTXO mutation while the store is filtered should invalidate the complete
  stake-balance ready marker, the same as running with
  `stake-balance-index-enabled=false`.
- Returning to an unfiltered complete index after syncing with filters requires
  a complete chainstate. If the chainstate only contains filtered UTXOs, the
  user must resync or restore an unfiltered chainstate before the startup
  migration can produce complete account balances.

Startup failure policy:

- If the migration is required and fails, do not continue syncing with
  `stake-balance-index-enabled=true`. Continuing from a missing base aggregate
  would apply future deltas to an incomplete index.
- The error message should state that startup failed during
  `stake-balance-index` migration and that setting
  `yaci.node.account.stake-balance-index-enabled=false` disables account
  controlled-amount support.
- If the feature is disabled, Yano starts normally and account controlled amount
  remains unavailable.

Status: completed on 2026-04-29.

Implemented:

- Removed the public Gradle maintenance task from the implementation path.
- Added a small startup migration framework:
  - `StartupMigration`
  - `StartupMigrationContext`
  - `StartupMigrationResult`
  - `StartupMigrationRunner`
- Added `StakeBalanceIndexStartupMigration`, triggered when
  `stake-balance-index-enabled=true` and the ready marker is missing.
- Refactored `StakeBalanceIndexRebuilder` into a runtime component used by the
  startup migration, with progress logging during clear, scan, and write
  phases.
- Invoked startup migrations from `YaciNode` after RocksDB opens and before
  UTXO store event handlers, pruning, bootstrap, or sync can run.
- Passed `yaci.node.account.stake-balance-index-enabled` from `node-app`
  config into runtime globals so jar users get the configured behavior.
- Kept the readiness marker and disabled-index invalidation behavior.
- Added filtered-chainstate handling so a filtered UTXO store does not claim a
  complete stake-balance index or serve `controlled_amount`.

Verification:

```bash
../gradlew :node-runtime:test \
  --tests com.bloxbean.cardano.yano.runtime.migration.StakeBalanceIndexStartupMigrationTest \
  --tests com.bloxbean.cardano.yano.runtime.utxo.DefaultUtxoStoreTest \
  :node-app:test \
  --tests com.bloxbean.cardano.yano.app.api.accounts.AccountStateResourceBalanceTest \
  --tests com.bloxbean.cardano.yano.app.api.epochs.EpochResourceAdaPotTest \
  --tests com.bloxbean.cardano.yano.app.ApiPrefixResourceTest \
  --tests com.bloxbean.cardano.yano.app.YaciNodeResourceTest
```

Result: passed. The same existing Java 25/JBoss Threads, SLF4J, Gradle, and
`config/application.yml.working` warnings are still present, but the Gradle
tasks succeed.

### Phase 4: Account History APIs

Goal: add Blockfrost-compatible account history endpoints without putting
reward-scale work on the block hot path and without coupling history indexing to
current account-state correctness.

Configuration:

```yaml
yaci:
  node:
    account-history:
      enabled: false
      tx-events-enabled: true
      rewards-enabled: false
      retention-epochs: 0        # 0 means retain all
      rollback-safety-slots: 4320 # optional; defaults to yaci.node.utxo.rollbackWindow
      prune-interval-seconds: 300
      prune-batch-size: 50000
```

Principles:

- Keep account history indexes in separate classes/modules from
  `DefaultAccountStateStore`. Current account state must continue to work when
  history indexing is disabled or fails during development.
- Keep the first implementation opt-in with `account-history.enabled=false` by
  default until mainnet performance is measured.
- Use separate read indexes for each endpoint family, but one shared rollback
  journal for tx/cert history insertions.
- Do not update history indexes from REST request paths.
- Do not block or mutate existing current-state reads when history is disabled.

Hot-path tx/cert indexes:

- Maintain these from block events because they are naturally tied to
  transactions/certificates and are relatively low volume compared with rewards:
  - logical withdrawal history by stake credential;
  - logical delegation history by stake credential;
  - logical registration/deregistration history by stake credential;
  - logical MIR certificate history by stake credential when available in block
    data.
- The first implementation stores these logical indexes in one RocksDB column
  family, `account_history`, using a typed key prefix:

```text
[type][stake_cred_type][stake_cred_hash][slot][tx_idx][event_idx]
```

  This keeps CF count low while still giving efficient prefix scans for each
  endpoint family.
- Use a separate component such as `AccountHistoryStore` and
  `AccountHistoryEventHandler`.
- For every inserted history record, add an entry to a shared rollback journal:

```text
account_history_delta:
  block_no -> {slot, inserted_account_history_keys[]}
```

- On rollback, scan journal entries above the target slot/block and delete the
  exact inserted keys. This makes rollback deterministic and avoids
  re-interpreting certificates during rollback.
- Because history keys include the slot, rollback also performs a retained-row
  scan as a defensive cleanup for missing/pruned delta entries. This keeps
  account history safe for startup adhoc rollback without constraining the
  common rollback floor for authoritative stores.
- Write history records and their rollback-journal entries in the same RocksDB
  batch where possible. If using an independent event handler, ensure ordering
  is deterministic and failures are visible rather than silently ignored.

Reward history design:

- Do **not** write every account reward record in the block hot path.
- Rewards are epoch-level and potentially high volume. Build reward history
  from the epoch-processing/export path instead.
- Use Parquet export as the durable epoch/bulk artifact for reward calculation
  output.
- Add an epoch reward export manifest with status such as:

```text
pending -> complete -> invalidated
```

- Build a compact point-query index from finalized reward exports:

```text
account_rewards_by_cred_epoch -> reward record
```

- `GET /accounts/{stake_address}/rewards` should read from this compact index,
  not scan all Parquet epoch files per API request.
- If rollback crosses an exported/finalized epoch boundary, mark affected reward
  export/index epochs invalid and rebuild them during epoch recovery before
  serving those epochs as complete.

Pruning:

- `retention-epochs=0` keeps all account history.
- For tx/cert history, prune records whose effective epoch or block epoch is
  older than the retention window.
- Prune matching rollback-journal entries only after they are outside the
  configured history retention window and, when available, outside the node
  rollback safety window. The first implementation reads
  `yaci.node.account-history.rollback-safety-slots` when set, otherwise it uses
  `yaci.node.utxo.rollbackWindow` as the safety slot window.
- Apply the rollback safety window to history-row pruning as well as delta
  pruning. This avoids pruning rows that would still be inside retention after a
  rollback across an epoch boundary.
- For reward history, prune the compact RocksDB reward index and exported
  Parquet reward files/manifests using the same retention policy.
- Public endpoints must not silently imply complete history when pruning is
  enabled. Responses should either document retention or include an extension
  metadata/header later if needed.

Production endpoints in this slice:

- `GET /accounts/{stake_address}`
- `GET /accounts/{stake_address}/withdrawals`
- `GET /accounts/{stake_address}/delegations`
- `GET /accounts/{stake_address}/registrations`
- `GET /accounts/{stake_address}/mirs`

Endpoint behavior:

- Return `503` when `account-history.enabled=false` or the specific history
  index is disabled/unavailable.
- Return paged arrays with Blockfrost-compatible fields and string-encoded
  lovelace values.
- Include newer optional fields such as `tx_slot`, `block_height`, and
  `block_time` when available.
- `GET /accounts/{stake_address}` returns supported current-state fields now:
  `stake_address`, `active`, `registered`, `controlled_amount`,
  `withdrawable_amount`, `pool_id`, and `drep_id`, plus Yano extensions for
  `current_utxo_balance`, `stake_deposit`, `pool_hash`, `drep_type`, and
  `drep_hash`. `active` is based on the presence of a current pool delegation;
  `registered` is based on the current stake credential registration state.
- Null account summary fields are omitted from JSON using Jackson
  `NON_NULL` serialization. The `unsupported_fields` extension was removed to
  keep the response closer to Blockfrost. `drep_id` is encoded with CCL
  `GovId` for key-hash and script-hash DRep delegations.
- Cumulative Blockfrost aggregate fields (`rewards_sum`, `withdrawals_sum`,
  `reserves_sum`, `treasury_sum`) are intentionally omitted from the DTO.
- `active_epoch` is implemented as a read-only API projection from the current
  stake registration slot. `delegation_epoch` is a Yano extension computed from
  the current pool delegation slot. `LedgerStateProvider` exposes optional
  current pool delegation metadata, while `getDelegatedPool` remains available
  for callers that only need the pool hash.

Testing requirements:

- disabled flag does not create or mutate history indexes;
- tx/cert indexes write expected records for withdrawals, delegations,
  registrations, and MIRs;
- rollback deletes exactly the records inserted by rolled-back blocks;
- rollback is idempotent and does not advance the account-history metadata when
  the requested target is ahead of the index;
- startup adhoc rollback includes account history and removes retained rows
  above the target slot;
- startup reconciliation rolls account history back when its metadata is ahead
  of the chain tip, and forward-replays stored block bodies when chainstate is
  ahead of the account-history index;
- rollback resets account-history prune cursors so pruning after rollback uses
  the rolled-back tip epoch, not the pre-rollback tip epoch;
- pruning removes old records without violating rollback safety;
- reward history remains unavailable until the epoch export/index manifest is
  complete;
- reward export invalidation prevents serving stale rewards after epoch
  rollback.

Status: tx/cert history index foundation implemented on 2026-04-29.

Implemented in this slice:

- Added `AccountHistoryStore`, `AccountHistoryCborCodec`,
  `AccountHistoryCfNames`, and `AccountHistoryEventHandler` in `ledger-state`.
- Added RocksDB column families `account_history` and
  `account_history_delta`; `account_history` is tuned as a prefix-scan CF when
  RocksDB tuning is enabled.
- Added node-app config and runtime globals for `account-history.enabled`,
  `tx-events-enabled`, `rewards-enabled`, `retention-epochs`,
  `prune-interval-seconds`, and `prune-batch-size`.
- Wired account history startup only when account state is enabled and RocksDB
  is used. Initialization is isolated and non-fatal, so a history setup failure
  does not prevent current account-state processing from starting.
- Added scheduled pruning for retained history and old rollback deltas when
  `retention-epochs > 0`.
- Added `RollbackCapableStore` support for account history so startup adhoc
  rollback participates with account state, UTXO, and chain state. The account
  history rollback floor is `0` because the index is append-only and retained
  rows can be truncated by slot even when old delta rows have been pruned.
- Added tests for disabled mode, withdrawal/delegation/registration/MIR writes,
  invalid transaction skipping, exact rollback deletion, adhoc-style direct
  rollback, rollback after missing delta rows, rollback prune-cursor reset, and
  pruning.
- Reward history remains intentionally unimplemented in this slice; it should
  come from the epoch export/Parquet manifest path described above.

Final rollback review update on 2026-04-29:

- `AccountHistoryStore` now persists lightweight last-applied slot/block
  metadata in the history column family. Metadata is written for every applied
  block with a decoded body, including blocks that contain no indexed account
  history records, so startup reconciliation can detect gaps after a crash
  between chainstate persistence and event handling.
- Startup initialization calls `AccountHistoryStore.reconcile(chainState)` before
  subscribing the history event handler or starting history pruning. If the
  history index is behind the chain tip and stored block bodies are available,
  it replays those bodies through the same indexing path. If the history index
  is ahead of the chain tip, it rolls back by slot.
- Normal/API rollback still publishes `RollbackEvent`, but YaciNode also performs
  an idempotent direct account-history rollback/verification afterward. This
  keeps the optional history index consistent even if event publication fails or
  another listener aborts the event flow.
- `rollbackToSlot` now propagates rollback failures after marking the optional
  history index unhealthy. Public history endpoints already return `503` while
  the provider is unhealthy.
- History pruning now subtracts the rollback safety window from the history-row
  cutoff and uses the existing tip-relative safety cutoff for delta rows. The
  safety value comes from `account-history.rollback-safety-slots` when present,
  otherwise from `utxo.rollbackWindow`.
- Mutating account-history operations are synchronized so scheduled pruning,
  startup reconciliation, block apply, and rollback do not interleave within the
  optional index.
- Added focused tests for `tx-events-enabled=false`, invalid tx certificates and
  MIRs, rollback idempotency/no-op metadata behavior, missing-delta rollback
  cleanup, prune cursor reset, explicit/fallback rollback-safety pruning, and
  startup reconciliation when history is ahead of the chain tip.

REST endpoint implementation update:

- Added `AccountHistoryProvider` in `node-api` and implemented it from
  `AccountHistoryStore`, so REST code depends on a read interface instead of the
  concrete RocksDB store.
- Added:
  - `GET /accounts/{stake_address}`
  - `GET /accounts/{stake_address}/withdrawals`
  - `GET /accounts/{stake_address}/delegations`
  - `GET /accounts/{stake_address}/registrations`
  - `GET /accounts/{stake_address}/mirs`
- Removed the redundant Yano-only `GET /accounts/{stake_address}/balance`
  endpoint because the account summary includes the same current balance fields.
- Removed unsupported placeholder account endpoints from the production resource
  so OpenAPI and route listings only show endpoints backed by current indexes.

Verification:

```bash
../gradlew :ledger-state:test \
  --tests com.bloxbean.cardano.yano.ledgerstate.AccountHistoryStoreTest

../gradlew :node-runtime:test \
  --tests com.bloxbean.cardano.yano.runtime.migration.StakeBalanceIndexStartupMigrationTest \
  --tests com.bloxbean.cardano.yano.runtime.utxo.DefaultUtxoStoreTest \
  :node-app:test \
  --tests com.bloxbean.cardano.yano.app.api.accounts.AccountStateResourceBalanceTest \
  --tests com.bloxbean.cardano.yano.app.ApiPrefixResourceTest \
  --tests com.bloxbean.cardano.yano.app.api.epochs.EpochResourceAdaPotTest \
  --tests com.bloxbean.cardano.yano.app.YaciNodeResourceTest
```

Result: passed. Existing Java 25/JBoss Threads shutdown warnings, RocksDB native
access warnings, SLF4J provider warnings, Gradle deprecation warnings, and
`config/application.yml.working` warnings remain present.

### Phase 5: Account UTXO API

- Add stake-credential-to-outpoint index.
- Implement `GET /accounts/{stake_address}/utxos`.
- Add pagination and ordering tests.
- Add performance checks on mainnet-sized state.

### Phase 6: Debug Cleanup and OpenAPI

- Mark promoted debug endpoints as deprecated in debug docs.
- Add OpenAPI descriptions for all production APIs.
- Keep heavy diagnostic endpoints under `/api/debug`.
- Document which features require `utxo`, `account-state`, `adapot`,
  `rewards`, or `governance` to be enabled.

## Risks and Open Questions

- `node-app` uses RESTEasy Classic today, so Phase 1 uses
  `quarkus.resteasy.path`. If the app moves to Quarkus REST, the equivalent
  property may be `quarkus.rest.path` and should be re-tested.
- REST root path configuration is normally resolved at application startup. For
  native images, confirm whether changing `yaci.node.api-prefix` requires a
  rebuild or only a runtime config update before documenting native deployment
  behavior.
- Blockfrost's account aggregate fields require historical sums. Current Yano
  state has the current reward balance but not all cumulative histories exposed
  through stable production interfaces.
- Current UTXO indexes are address and payment-credential oriented. Stake
  credential balance needs a new aggregate or index to avoid scans.
- Bech32 conversion for pool ids and stake addresses should be centralized to
  avoid inconsistent response formats.
- Some debug endpoints depend on retention windows. Public endpoints should not
  silently return incomplete historical data when the underlying facts were
  pruned.

## Decision Summary

Proceed with a phased API plan:

1. centralize the public REST prefix,
2. promote cheap AdaPot reads first,
3. add Blockfrost-compatible account endpoints only after direct account and
   stake-credential UTXO indexes exist,
4. keep heavy diagnostic endpoints under `/api/debug`,
5. prefer correctness and predictable performance over premature endpoint
   coverage.
