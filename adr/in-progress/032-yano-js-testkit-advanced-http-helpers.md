# ADR-032: Advanced JavaScript Testkit Helpers Over Yano HTTP

**Status:** Accepted / Partially Implemented
**Date:** 2026-06-26
**Related:** ADR-030 (Yano Testkit For JVM Integration Testing), ADR-031 (Native Yano Testkit For Non-JVM And npm Testing)

## Context

ADR-030 gives Java/JVM tests a rich embedded testkit. Because those tests run in
the same process as Yano, they can call helper facades backed directly by public
runtime roles:

- wallet fixtures through Cardano Client Lib;
- faucet funding;
- deterministic time and epoch advance;
- snapshot/restore;
- rollback;
- chain, ledger, UTXO, protocol-parameter, and epoch queries;
- transaction submit/evaluate;
- await and assertion helpers.

ADR-031 created the JavaScript/npm fixture around the native Yano binary. That
is the right process boundary for JS applications, but it means JS tests can
only interact with Yano through HTTP. The npm package currently starts/stops the
native process and exposes a small helper for `POST /api/v1/devnet/fund`.

The goal of this ADR is to make JS tests productive in the same way as the Java
testkit while preserving the ADR-031 boundary:

- no JVM objects in JS;
- no runtime internals exposed through npm;
- real native Yano process and real RocksDB storage by default;
- production HTTP endpoints for normal chain/ledger workflows;
- devnet-only HTTP endpoints for mutation helpers such as funding, rollback,
  snapshots, and time travel.

## Existing HTTP Surface

The following endpoint inventory is relative to `yano.apiBaseUrl`
(`http://127.0.0.1:<port>/api/v1/`) unless noted otherwise.

### Readiness

- `GET /q/health/ready` from `baseUrl`, not `apiBaseUrl`.
- `GET node/tip` from `apiBaseUrl`.

ADR-031 readiness already waits for both.

### Node And Chain Basics

- `GET node/status`
- `POST node/start`
- `POST node/stop`
- `GET node/tip`
- `GET node/config`
- `GET node/protocol-params`
- `GET node/epoch-calc-status`
- `GET node/epoch-nonce`
- `POST node/recover`
- `GET status`
- `GET genesis`
- `GET network`
- `GET blocks/latest`
- `GET blocks/{hashOrNumber}`

These support query, await, and assertion helpers without new server work.

### UTXO, Transactions, And Evaluation

- `GET addresses/{address}/utxos`
- `GET addresses/{address}/utxos/{asset}`
- `GET credentials/{paymentCredential}/utxos`
- `GET utxos/{txHash}/{index}`
- `POST node/tx/submit` with `application/octet-stream`
- `POST tx/submit` with `application/cbor` or `text/plain`
- `GET txs/{txHash}`
- `GET txs/{txHash}/utxos`
- `POST utils/txs/evaluate` with `application/cbor` or `text/plain`
- `GET scripts/{script_hash}/cbor`

These map to transaction, UTXO, balance, and script-evaluation helpers. The JS
testkit should still let applications use their own Blockfrost-compatible client
directly against `apiBaseUrl`.

### Devnet Mutation And Time Control

These endpoints require Yano dev mode and are not production controls:

- `POST devnet/fund` with `{ "address": string, "ada": number }`
- `POST devnet/rollback` with one of `slot`, `block_number`, or `count`
- `POST devnet/snapshot` with `{ "name": string }`
- `POST devnet/restore/{name}`
- `GET devnet/snapshots`
- `DELETE devnet/snapshot/{name}`
- `POST devnet/time/advance` with exactly one of `slots`, `seconds`, `epochs`
- `POST devnet/epochs/shift` with `{ "epochs": number }`
- `POST devnet/epochs/catch-up`
- `GET devnet/genesis/download`

This is enough to expose most of Java `YanoFaucet`, `YanoSnapshots`, and
`YanoTime` in JS.

### Epoch, Account, Governance, And Debug Queries

Additional public query endpoints already exist:

- `GET epochs/latest`
- `GET epochs/latest/parameters`
- `GET epochs/{number}/parameters`
- `GET epochs/latest/adapot`
- `GET epochs/{number}/adapot`
- `GET epochs/adapots`
- `GET epochs/latest/stake/total`
- `GET epochs/{number}/stake/total`
- `GET epochs/{number}/stakes/{poolId}`
- `GET epochs/{number}/stake/pool/{poolId}`
- `GET accounts/{stakeAddress}`
- `GET accounts/{stakeAddress}/stake`
- `GET accounts/{stakeAddress}/stake/{epoch}`
- `GET accounts/{stakeAddress}/withdrawals`
- `GET accounts/{stakeAddress}/delegations`
- `GET accounts/{stakeAddress}/registrations`
- `GET accounts/{stakeAddress}/mirs`
- `GET accounts/registrations`
- `GET accounts/delegations`
- `GET accounts/drep-delegations`
- `GET accounts/pools`
- `GET accounts/pool-retirements`
- `GET governance/proposals`
- `GET governance/proposals/{txHash}/{certIndex}`
- `GET governance/proposals/{txHash}/{certIndex}/votes`
- `GET governance/dreps`
- `GET governance/dreps/{drepId}`
- `GET governance/dreps/{drepId}/distribution`
- `GET governance/dreps/{drepId}/distribution/{epoch}`

The npm testkit should not wrap every query in the first pass. Prefer focused
helpers for common tests and keep raw `apiBaseUrl` available for advanced
application-specific queries.

`/api/debug/...` endpoints also exist for internal diagnostics. They should not
be promoted into the public JS testkit unless a later ADR explicitly makes them
part of the test contract.

## Decision

Extend `@bloxbean/yano-testkit` with typed, HTTP-backed helper facades on the
object returned by `startYanoDevnet()`.

The helper facades should be small wrappers over existing HTTP endpoints. They
must not hide the fact that JS tests are using a native process through HTTP, and
they must not introduce a second execution path that behaves differently from
the app's production endpoints.

The root object remains the lifecycle/process handle:

```ts
const yano = await startYanoDevnet();
const address = appWallet.address();

try {
  await yano.faucet.fundAddress(address, 1000);
  await yano.time.advanceSlots(5);
  await yano.await.untilSlotAtLeast(5);
} finally {
  await yano.stop();
}
```

Existing direct helpers such as `yano.fundAddress(address, ada)` may remain as
short aliases, but grouped helpers should be the primary documented API:

- `yano.client` - low-level typed HTTP helper for testkit internals and escape
  hatch use;
- `yano.queries` - status, tip, config, genesis, protocol params, UTXO, block,
  transaction, epoch, and selected account queries;
- `yano.faucet` - fund one or many addresses;
- `yano.time` - advance by slots/seconds/epochs, advance to slot/epoch, cross
  epoch boundary, shift genesis, catch up to wall clock;
- `yano.snapshots` - create, restore, list, delete, exists, withSnapshot;
- `yano.rollback` or `yano.devnet.rollback` - rollback by slot, block number, or
  count;
- `yano.transactions` - submit CBOR/hex, evaluate CBOR/hex, submitAndAwait;
- `yano.await` - polling helpers for node readiness, slot, block, epoch, and tx
  visibility;
- `yano.assertions` - runner-neutral assertion helpers that throw plain
  `AssertionError`/`Error`, not Jest/Vitest-specific assertion types.

The base npm package should keep direct access to:

```ts
yano.baseUrl;
yano.apiBaseUrl;
yano.url("node/tip");
yano.stop();
yano.logs();
```

This keeps existing low-level usage viable.

## Response Casing And Normalization

The npm wrapper should not normalize every HTTP response at the client boundary.
`yano.client` and query helpers preserve the server response shape so JS tests
see the same JSON contract as production HTTP clients. This means Blockfrost
compatible endpoints may expose snake_case fields such as `tx_hash`,
`block_number`, or `output_index`.

Wrapper-only request options may use idiomatic JavaScript camelCase and map to
the server request shape internally. This keeps convenience method calls natural
without hiding the actual HTTP response contract from tests.

Follow-up: pin response DTO contracts for devnet-only endpoints and consider
adding explicitly named normalized convenience DTOs if JS users need them. Do
not silently change `yano.client` or existing query helpers to return a
different casing model.

## HTTP Address Boundary And JS Wallet Convenience

Java `YanoWallets` is backed by Cardano Client Lib and can create signing
accounts in-process. The JS native fixture cannot create usable signing keys
through Yano HTTP today, and it should not make the Yano native process own test
private keys.

The Yano HTTP boundary must stay address-only:

- Yano HTTP accepts addresses and serialized transactions, not wallets,
  mnemonics, private keys, accounts, or signing callbacks.
- The npm base package helpers accept address strings where funding, UTXO, and
  balance helpers need a target.
- Wallet/key generation and transaction signing must happen in the JS test
  process, owned by the application or an explicit JS-side test helper.
- The HTTP wrapper must not pass wallet objects, mnemonics, private keys, or
  signing callbacks to Yano.

The npm wrapper may still provide a JS-side test wallet convenience for default
test addresses. That helper is not a Yano HTTP feature; it runs locally in Node
and only passes derived addresses to `yano.faucet`, `yano.queries`, and
`yano.assertions`.

Preferred shape:

```ts
import { testWallets } from "@bloxbean/yano-testkit/wallets";

const wallets = testWallets({
  networkMagic: yano.networkMagic,
  networkId: 0
});

const alice = await wallets.defaultWallet();
await yano.faucet.fundAddress(alice.address, 1000);

const signedTx = await appBuildsAndSignsTx(alice);
await yano.transactions.submitAndAwait(signedTx);
```

The wallet helper may expose the same deterministic mnemonic/default-address
scheme as the Java testkit so examples are repeatable across languages. If it
needs a Cardano JS library, keep that dependency isolated in the `wallets`
subpath or a separate package so the base native-process fixture remains small.

This keeps Yano's HTTP contract aligned with production usage while still
letting JS tests get default deterministic addresses when they want them.

## Proposed API Shape

### Low-Level HTTP Client

```ts
await yano.client.getJson("node/tip");
await yano.client.postJson("devnet/time/advance", { slots: 5 });
await yano.client.deleteJson("devnet/snapshot/before-test");
await yano.client.postCbor("tx/submit", txCbor);
await yano.client.postText("utils/txs/evaluate", txHex);
```

The client should:

- resolve paths relative to `apiBaseUrl`;
- parse JSON responses;
- preserve response status and body in thrown errors;
- support `AbortSignal` or timeout options where useful;
- never retry mutating requests automatically.

### Faucet

```ts
await yano.faucet.fundAddress(address, 1000);      // ADA
await yano.faucet.fundAddressLovelace(address, 1_000_000n);
await yano.faucet.fundAll([
  { address: aliceAddress, ada: 1000 },
  { address: bobAddress, lovelace: 2_000_000n }
]);
```

`fundAll` is sequential and non-atomic, matching the Java testkit behavior and
runtime semantics.

### Time

```ts
await yano.time.advanceSlots(5);
await yano.time.advanceSeconds(10);
await yano.time.advanceEpochs(1);
await yano.time.advanceToSlot(50);
await yano.time.advanceToEpoch(2);
await yano.time.crossEpochBoundary();
await yano.time.shiftGenesisAndStartProducer(3);
await yano.time.catchUpToWallClock();
```

`advanceToSlot()` can be implemented initially with `GET node/tip` followed by
`POST devnet/time/advance` with the computed slot delta. If we need exact
server-side "advance until absolute slot" semantics, extend
`TimeAdvanceRequest` later with `target_slot` and add tests around race-free
behavior. Until that endpoint exists, `advanceToSlot()`, `advanceToEpoch()`,
and `crossEpochBoundary()` are best-effort helpers under live block production;
tests that need a precise final state should follow them with an await helper or
assertion.

`advanceToEpoch()` and `crossEpochBoundary()` can use `GET node/config` and the
same epoch-slot calculation rules as Java's `EpochSlotCalc`. The JS helper must
handle `epochLength`, `byronSlotsPerEpoch`, and `firstNonByronSlot`; do not
hardcode devnet-only epoch math if config exposes the full values.

### Snapshots And Rollback

```ts
await yano.snapshots.create("before");
await yano.snapshots.restore("before");
const snapshots = await yano.snapshots.list();
await yano.snapshots.delete("before");
await yano.snapshots.withSnapshot("case-1", async () => {
  // test setup
});

await yano.devnet.rollback({ count: 1 });
await yano.devnet.rollback({ slot: 10 });
await yano.devnet.rollback({ blockNumber: 4 });
```

`withSnapshot()` should restore in `finally` and preserve/suppress errors in a
way that keeps the original failure visible.

### Queries

Minimum first-pass query helpers:

```ts
await yano.queries.status();
await yano.queries.tip();
await yano.queries.config();
await yano.queries.currentSlot();
await yano.queries.currentBlockNumber();
await yano.queries.currentEpoch();
await yano.queries.genesis();
await yano.queries.protocolParameters();   // GET epochs/latest/parameters
await yano.queries.protocolParameters(0);
await yano.queries.utxosByAddress(address, { page: 1, count: 100 });
await yano.queries.utxo(txHash, index);
await yano.queries.tx(txHash);
await yano.queries.txUtxos(txHash);
await yano.queries.latestBlock();
await yano.queries.block(hashOrNumber);
```

Additional account, governance, and epoch queries can be added in follow-up
slices based on real JS application needs. The raw `apiBaseUrl` remains the
escape hatch.

### Transactions

```ts
await yano.transactions.submitCbor(txCbor);
await yano.transactions.submitHex(txHex);
await yano.transactions.submitAndAwait(txCbor);
await yano.transactions.evaluateCbor(txCbor);
await yano.transactions.evaluateHex(txHex);
```

The helpers do not build or sign transactions. They only submit/evaluate
serialized transactions produced by the application or its Cardano JS library.

### Await And Assertions

```ts
await yano.await.untilReady();
await yano.await.untilSlotAtLeast(10);
await yano.await.untilBlockAtLeast(5);
await yano.await.untilEpochAtLeast(1);
await yano.await.untilTxVisible(txHash);

await yano.assertions.slotAtLeast(10);
await yano.assertions.blockAtLeast(5);
await yano.assertions.address(address).hasAtLeastAda(1000);
```

Await helpers should default to short test-friendly polling intervals and
configurable timeouts. Assertions should be runner-neutral and throw normal
errors so they work in Node test runner, Vitest, Jest, Mocha, and custom tests.

ADA-denominated assertion helpers are convenience helpers for typical test
amounts; lovelace helpers using `bigint` or string values are the exact path for
large or fractional amounts.

## Known Follow-Ups

- Pin response DTO contracts for devnet-only endpoints and decide whether to add
  explicitly named normalized DTO helpers while preserving raw HTTP query
  helpers.
- Add a server-side absolute `target_slot` time-advance request if JS tests need
  race-free target-slot movement equivalent to the JVM embedded testkit SPI.
- Consider string-based ADA amount inputs if JS applications need exact
  fractional ADA convenience helpers. Lovelace helpers remain exact today.

## Implementation Plan

1. Completed: add an internal HTTP client layer.
   - Centralize path resolution, JSON parsing, content-type handling, and error
     messages.
   - Move the existing `fundAddress()` implementation onto this layer.
   - Add fake-server tests for success, HTTP error body propagation, and
     mutating-request no-retry behavior.

2. Completed: add devnet control facades.
   - `faucet`, `time`, `snapshots`, and `devnet.rollback`.
   - Keep `yano.fundAddress(address, ada)` as an alias for
     `yano.faucet.fundAddress(address, ada)`.
   - Unit-test every request path/body against the fake server.

3. Completed: add query, transaction, await, and assertion facades.
   - Start with the minimum query set listed above.
   - `submitAndAwait()` should use `txs/{txHash}/utxos` or another public UTXO
     query, not a runtime-internal status.
   - Tests should cover timeout messages and polling success/failure.

4. Completed: document the HTTP address boundary.
   - Accept address strings in faucet, UTXO, and balance helpers.
   - Do not pass wallet objects, mnemonics, private keys, or signing callbacks
     to Yano HTTP.
   - Document that Yano helpers still fund/query by address and submit
     serialized transactions.

5. Deferred: JS-side wallet convenience.
   - Allow a JS-side `wallets` helper or package to provide default
     deterministic test wallets/addresses.
   - Isolate any Cardano JS wallet/signing dependency from the base package.

6. Deferred: add server endpoints only for proven gaps.
   - Candidate: `POST devnet/time/advance` with `target_slot` for race-free
     `advanceToSlot()`.
   - Candidate: batch funding if sequential client-side funding proves too slow.
   - Candidate: `GET devnet/capabilities` if JS needs to detect optional devnet
     controls before calling them.

No new server endpoints were needed for the implemented helper set.

## Compatibility And Production Safety

- These helpers are npm-side wrappers. They should not change normal Yano
  production startup or existing production HTTP endpoint behavior.
- Devnet mutation helpers must call only endpoints that already require
  `yano.dev-mode=true`.
- The base package remains plain Node.js and test-runner agnostic.
- New helper methods must be additive. Existing `startYanoDevnet()` lifecycle
  behavior and returned connection fields must continue to work.
- No helper should expose `RuntimeNode`, RocksDB handles, Java objects, or debug
  endpoints as public JS API.

## Validation

The HTTP helper implementation is complete when:

- npm unit tests verify every helper's HTTP method, path, body, response
  parsing, and error behavior using a fake Yano server;
- TypeScript definitions cover all helper facades and response DTOs;
- a real native devnet smoke test demonstrates funding, slot advance, snapshot
  restore, transaction visibility polling, and cleanup;
- docs show plain Node.js usage and at least one test-runner usage;
- docs clearly state that Yano HTTP is address-only and any test wallet helper
  is local to the JS process;
- production app tests still pass, proving no regression to normal Yano HTTP
  usage.

Current validation:

- `npm test` covers lifecycle plus HTTP-backed faucet, time, snapshot, rollback,
  query, transaction, await, assertion, and HTTP-error helpers against a fake
  Yano process.
- `npm run typecheck` validates the JavaScript source and TypeScript
  definitions.

## Consequences

JS users get most of the Java testkit ergonomics while keeping the native-process
boundary honest. The wrapper becomes a small HTTP test client plus lifecycle
owner, not a second Yano runtime.

The main tradeoff is that JS tests do not get wallet fixtures from Yano HTTP.
That is intentional. A JS-side helper may provide default deterministic
addresses or wallets, but `@bloxbean/yano-testkit` exercises Yano by address and
serialized transaction.
