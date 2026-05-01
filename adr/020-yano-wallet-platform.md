# ADR-020: Yano Wallet Platform and JavaFX Desktop App

## Status

Draft

## Date

2026-04-30

Last updated: 2026-05-01

## Context

Yano now has enough node and ledger-state surface to support a wallet-oriented
desktop runtime:

- full UTXO state,
- account and ledger state,
- effective protocol parameters,
- current governance state,
- REST APIs for current state, UTXOs, protocol parameters, governance, and
  transaction submission,
- local transaction validation and mempool admission,
- devnet support through Yano runtime profiles, with Yaci DevKit integration as
  a later developer convenience.

The question is whether Yano can become a self-custody wallet without turning
the core node application into a wallet-specific product.

The answer should be yes, but the positioning matters. Yano Wallet should not
be "another desktop wallet". It should be:

```text
A full-node Cardano wallet and local developer runtime built in Java.
```

The product direction is a wallet plus platform:

- full-node verification,
- Java-native Cardano stack,
- local wallet and node APIs,
- dApp bridge,
- Yaci DevKit integration,
- advanced transaction inspection,
- developer-focused transaction runtime,
- devnet, preview, preprod, and mainnet profiles.

This ADR defines a maintainable module design that keeps the existing
`node-app` and `yano.jar` implementation low-risk. The current `yano.jar`
continues to be the node/server application. A separate `yano-wallet.jar` and
later native desktop distribution will bundle Yano plus wallet/platform UI.

## References Checked

- Yano modules: `node-api`, `node-runtime`, `node-app`, `ledger-state`,
  `scalus-bridge`, `ccl-ledger-rules`
- Existing Yano APIs:
  - `NodeAPI.submitTransaction(byte[])`
  - `NodeAPI.getUtxoState()`
  - `NodeAPI.getLedgerStateProvider()`
  - `NodeAPI.getAccountHistoryProvider()`
  - `UtxoState`
  - `LedgerStateProvider`
  - `AccountHistoryProvider`
  - `ProtocolParamsMapper`
  - `UtxoMapper`
- Cardano Client Lib 0.8.0-pre3 local checkout:
  `/Users/satya/work/bloxbean/cardano-client-lib`
- Relevant CCL APIs and tests:
  - `Wallet.create(...)`, `Wallet.createFromMnemonic(...)`,
    `Wallet.createFromRootKey(...)`, `Wallet.createFromAccountKey(...)`
  - `HDWalletAddressIterator`
  - `QuickTxBuilder(UtxoSupplier, ProtocolParamsSupplier,
    TransactionProcessor)`
  - `Tx.from(Wallet)`
  - `SignerProviders.signerFrom(Wallet)`
  - `CIP30DataSigner`
  - `CIP30UtxoSupplier`
- CIP-30: <https://cips.cardano.org/cip/CIP-30>
- CCL project/docs: <https://github.com/bloxbean/cardano-client-lib>
- GluonFX JavaFX native packaging:
  <https://docs.gluonhq.com/>
- GraalVM Native Image:
  <https://www.graalvm.org/jdk21/reference-manual/native-image/>

## Goals

1. Provide a self-custody desktop app backed by local Yano state, with
   encrypted multi-wallet storage and one explicitly active account at a time.
2. Keep wallet code in separate modules so `node-app` and `yano.jar` are not
   destabilized.
3. Reuse Cardano Client Lib 0.8.0-pre3 HD wallet, QuickTx, transaction signing,
   transaction spec, and CIP helpers.
4. Reuse Yano `node-runtime`, `node-api`, UTXO state, ledger state, transaction
   validation, script evaluation, and submission.
5. Make key security the first design constraint.
6. Support a JavaFX UI first, with a GraalVM native image path designed from the
   beginning.
7. Provide a local wallet API and basic CIP-30 bridge for dApps and local
   developer tooling.
8. Make Yano Wallet useful for both normal wallet workflows and developer
   workflows on devnet and mainnet.

## Non-Goals for MVP

- Staking.
- Governance operations.
- Hardware wallet support.
- Multi-sig.
- Full analytics.
- Plugin system.
- Native image as the first deliverable.
- Advanced policy engine.
- Browser-extension-grade dApp injection for arbitrary websites.

Those are valuable, but they should not be in the first wallet milestone.

## Decision

Create separate wallet modules under the Yano Gradle multi-module project.
Do not add wallet UI, key storage, dApp approval, or wallet-specific APIs to
`node-app`.

Initial module layout:

```text
yano
|-- node-api
|-- node-runtime
|-- node-app                 # existing yano.jar, unchanged product surface
|-- wallet-core              # yano-wallet-core library
|-- wallet-yano-adapter      # CCL adapters over NodeAPI/UtxoState/LedgerState
|-- wallet-bridge            # local wallet API and CIP-30 bridge
|-- wallet-ui                # JavaFX views and view models
`-- wallet-app               # yano-wallet.jar and later native desktop app
```

`wallet-app` is the only wallet application assembly. It may depend on
`wallet-ui`, `wallet-bridge`, `wallet-core`, `wallet-yano-adapter`, and
`node-runtime`. It should not depend on `node-app` or Quarkus.

`node-app` remains a Quarkus app that builds `yano.jar`. `wallet-app` is a
JavaFX app that builds `yano-wallet.jar`.

## Runtime Model

Use an embedded Yano runtime as the default wallet runtime:

```text
JavaFX wallet-app
  -> wallet-core services
  -> wallet-yano-adapter
  -> node-runtime / NodeAPI
  -> UtxoState, LedgerStateProvider, transaction validation, submission
```

Rationale:

- In-process access gives the wallet better lifecycle control, sync status,
  local state reads, validation, and transaction inspection.
- It avoids coupling the desktop app to Quarkus REST resources.
- It is a better foundation for a single native desktop image later.
- It keeps `node-app` unchanged.

Also define a process-mode adapter behind the same interface:

```java
interface WalletNodeRuntime {
    void start();
    void stop();
    NodeStatus status();
    ChainTip localTip();
    UtxoState utxoState();
    LedgerStateProvider ledgerStateProvider();
    String submitTransaction(byte[] txCbor);
}
```

Implementations:

- `EmbeddedYanoNodeRuntime`: default, backed by `node-runtime`.
- `YanoRestNodeRuntime`: optional future fallback, starts or connects to an
  external `yano.jar`/native process and uses REST endpoints.

Process mode is useful for development and as a native-image escape hatch if a
single image containing JavaFX, RocksDB, Netty, and the full node runtime becomes
too costly in the first packaging pass.

## Module Responsibilities

### `wallet-core`

Pure Java wallet domain module. No JavaFX, no Quarkus, no REST server.

Responsibilities:

- wallet creation and restore flows,
- wallet metadata,
- single-account address derivation,
- wallet address discovery state,
- receive address allocation,
- balance aggregation,
- UTXO queries,
- local wallet transaction history index,
- transaction draft model,
- transaction approval model,
- signing service,
- vault abstraction,
- dApp connection permissions,
- local API command model.

Key CCL dependencies:

- `cardano-client-hd-wallet`
- `cardano-client-core`
- `cardano-client-address`
- `cardano-client-crypto`
- `cardano-client-transaction-spec`
- `cardano-client-common`

The module should use CCL `Wallet` and `Account` for HD derivation and signing.
It must not implement Cardano key derivation itself.

### `wallet-yano-adapter`

Integration module between wallet-core/CCL and Yano node state.

Responsibilities:

- `YanoUtxoSupplier implements com.bloxbean.cardano.client.api.UtxoSupplier`
  using `UtxoState`.
- `YanoProtocolParamsSupplier implements ProtocolParamsSupplier` using
  `LedgerStateProvider.getProtocolParameters(epoch)` and
  `ProtocolParamsMapper.fromSnapshot(...)`.
- `YanoTransactionProcessor implements TransactionProcessor` using
  `NodeAPI.submitTransaction(byte[])`.
- `YanoScriptSupplier implements ScriptSupplier` using
  `UtxoState.getScriptRefBytesByHash(...)`.
- Optional `YanoBackendService` facade if later QuickTx flows benefit from a
  full CCL `BackendService`.
- Conversion utilities that reuse or move the current runtime `UtxoMapper`
  pattern.

This is the main place where CCL QuickTx sees Yano as a backend:

```java
QuickTxBuilder builder = new QuickTxBuilder(
        yanoUtxoSupplier,
        yanoProtocolParamsSupplier,
        yanoTransactionProcessor);
```

Basic ADA send should be built through QuickTx:

```java
Tx tx = new Tx()
        .payToAddress(receiverAddress, Amount.ada(amount))
        .from(wallet);

Transaction unsigned = builder.compose(tx)
        .feePayer(wallet)
        .withTxInspector(txInspector)
        .build();
```

After UI approval, sign with CCL:

```java
Transaction signed = builder.compose(tx)
        .feePayer(wallet)
        .withSigner(SignerProviders.signerFrom(wallet))
        .buildAndSign();
```

Then submit through `YanoTransactionProcessor`.

### `wallet-bridge`

Local developer and dApp bridge module.

Responsibilities:

- local loopback API server,
- dApp session registry,
- origin permissions,
- approval request queue,
- CIP-30 shaped methods,
- local developer API,
- request authentication and CSRF protection.

If the bridge needs to expose an HTTP/WebSocket API, the API server should use
Quarkus because it is lightweight, familiar in this codebase, and native-image
friendly. Keep the bridge command/session/approval model independent from
Quarkus so `wallet-core` remains pure Java and the transport can still be
tested without starting an HTTP server.

For MVP, expose only a small bridge:

- `getNetworkId`
- `getBalance`
- `getUtxos`
- `getChangeAddress`
- `getRewardAddresses`
- `signTx`
- `submitTx`

The user listed `getBalance`, `getUtxos`, `signTx`, and `submitTx` as the core
scope. The extra network/address reads are cheap and many dApps expect them.

Important limitation: a desktop app cannot inject `window.cardano.yano` into
arbitrary browser pages by itself. MVP bridge should therefore be a local
loopback bridge plus a small JS connector for dev/test dApps. A browser
extension or native-messaging host can come later.

MVP bridge transport:

- bind only to `127.0.0.1`,
- fixed reserved port `47000` by default so local dApps can deterministically
  load `http://127.0.0.1:47000/yano-cip30.js`,
- per-run bearer token,
- per-origin permission grants,
- no signing without JavaFX approval,
- no blanket "always sign" mode.

The fixed port is for developer ergonomics, not security. Security still relies
on loopback-only binding, browser `Origin` validation, per-origin sessions,
explicit wallet approval, and decoded transaction review. A browser
extension/native-messaging bridge remains the stronger later option for
arbitrary websites because any local process can attempt to bind an unauthenticated
localhost port before the wallet starts.

Implementation direction:

- `wallet-bridge` owns the bridge domain model and service contracts.
- A Quarkus-backed bridge endpoint layer may live in `wallet-bridge` or in a
  later `wallet-bridge-quarkus` module if native packaging or dependency
  isolation requires it.
- `wallet-app` starts/stops the bridge with the selected wallet runtime and
  exposes the selected loopback URL/token only through the UI and app logs that
  are safe to share.

### `wallet-ui`

JavaFX UI module.

Responsibilities:

- screens,
- dialogs,
- view models,
- local state presentation,
- transaction review views,
- dApp approval prompts,
- theme and styling.

Expected MVP screens:

- node and sync dashboard,
- network/profile selector,
- create/restore wallet wizard,
- lock/unlock wallet,
- overview balance,
- receive address,
- UTXO list,
- transaction history,
- multi-recipient ADA/native asset send,
- transaction review and approval,
- dApp connections and signing approvals,
- developer/runtime panel with local API and DevKit status.

Use JavaFX properties and observable models at the UI boundary. Keep wallet and
node logic in `wallet-core` and `wallet-yano-adapter`.

### `wallet-app`

Application and distribution module.

Responsibilities:

- main JavaFX application,
- wallet app configuration,
- data directory layout,
- embedded Yano lifecycle,
- module wiring,
- distribution tasks for `yano-wallet.jar`,
- later GraalVM/GluonFX native desktop packaging.

`wallet-app` should be added to the root `nonLibraryModules` set, similar to
`node-app`, because it is an application assembly and will have its own build
and distribution needs.

## Network Profiles

Yano Wallet must support these runtime profiles from the beginning:

- `devnet`
- `preview`
- `preprod`
- `mainnet`

For Phase 1, `devnet` should start Yano directly in devnet mode. It should not
depend on connecting to Yaci DevKit first. DevKit integration remains important
for the developer runtime story, but the wallet MVP should prove that Yano can
run the local devnet profile by itself.

`preview` and `preprod` should be first-class sync/test profiles. `mainnet`
should be available for read flows, with transaction signing and submission
gated behind explicit confirmations and security review criteria.

The existing `node-app` working directory contains a fully synced preprod
chainstate. That state can be useful for local adapter and UI testing. Tests and
manual runs should prefer using a copy or a configured read-only/test data
directory so wallet tests do not mutate the developer's synced preprod state
accidentally.

## Data Directories

Default layout:

```text
~/.yano-wallet/
|-- config/
|   `-- wallet.yml
|-- networks/
|   |-- mainnet/
|   |   |-- yano/          # node chainstate, UTXO, ledger DB
|   |   `-- wallet/        # wallet metadata and wallet-local indexes
|   |-- preprod/
|   |-- preview/
|   `-- devnet/
|-- vault/
|   `-- wallet-vault.json
`-- logs/
```

Keep wallet vault data separate from Yano chainstate data. Deleting or
resyncing chainstate must not affect the encrypted wallet vault.

## Key Security Decisions

Security is the first constraint. The wallet must assume local malware and
malicious dApps are realistic threats, even in a developer-oriented platform.

### Vault

Define a `WalletSecretStore` abstraction in `wallet-core`:

```java
interface WalletSecretStore {
    LockedWallet create(WalletSecret secret, char[] passphrase);
    UnlockedWallet unlock(char[] passphrase);
    void lock();
    void rotatePassphrase(char[] oldPassphrase, char[] newPassphrase);
}
```

MVP vault:

- encrypted vault file,
- user passphrase required at startup/unlock,
- AES-GCM for authenticated encryption,
- PBKDF2-HMAC-SHA256 or stronger JDK-available KDF for MVP portability,
- unique salt and nonce per vault write,
- no plaintext mnemonic or private key on disk,
- no private key material in logs,
- zeroize passphrase char arrays and short-lived key buffers where practical,
- auto-lock on idle timeout,
- require unlock before any signing operation.

Phase 2 vault hardening:

- OS keychain integration:
  - macOS Keychain,
  - Windows DPAPI/Credential Manager,
  - Linux Secret Service/libsecret where available,
- optional passphrase-only portable mode,
- migration versioning for vault formats,
- security review and threat-model document.

### What to Store

MVP should store only the encrypted secret needed to recreate the wallet seed.
Options:

1. Encrypted mnemonic.
2. Encrypted root private key.
3. Encrypted account private key for an account profile.

Current MVP direction: encrypted mnemonic, because explicit multi-account
creation needs the seed-level material to derive additional account indexes.
Encrypted root key storage is an acceptable future migration if CCL support and
vault migration are clean. Encrypted account-key storage can be added later as a
per-account hardening mode, but it does not replace seed-level storage for
wallets where the user wants to create additional accounts from the same
mnemonic.

Do not store plaintext mnemonic after create/restore. Show the mnemonic once
during wallet creation and require backup confirmation.

### Signing Boundary

Private keys live only in `wallet-core` memory after unlock. Yano node runtime,
UTXO state, ledger state, REST APIs, and bridge server never receive private
keys.

Every signing operation must go through an approval service:

```text
dApp/API/UI request
  -> transaction decode and risk summary
  -> JavaFX approval dialog
  -> unlock check
  -> CCL signing
  -> witness set or signed tx result
```

No signing endpoint should ever be an unauthenticated local REST endpoint.

### Transaction Approval

Before signing, show a transaction summary:

- recipients,
- outgoing ADA,
- native assets,
- fee,
- change address,
- input count,
- deposits,
- withdrawals,
- mint/burn if present,
- scripts/reference inputs if present,
- validity interval,
- required signers,
- network id,
- warnings for unknown addresses, metadata, scripts, certificates, governance,
  or unusually high fees.

MVP basic send will be simple, but `signTx` from the bridge must be treated as
untrusted and inspected before approval.

## Address Discovery

MVP supports multiple encrypted wallet seeds and explicit account profiles:

- each encrypted seed has one or more stored account profiles,
- account index `0` is created by default,
- additional account indexes are created only by explicit user action,
- only one account profile is active for signing and balance display at a time,
- external/payment chain first,
- gap limit: `20` by default,
- receive address allocation from CCL `Wallet.getBaseAddress(index)`,
- change address from CCL account change address or wallet base address until a
  change-index strategy is added.

CCL already has `HDWalletAddressIterator`, which scans addresses through a
`UtxoSupplier`. Reuse it where it fits.

Important Yano detail: CCL `UtxoSupplier.isUsedAddress(...)` expects a used
address answer. Yano's current `UtxoState` answers current unspent UTXOs, not
necessarily all historical address use. Therefore wallet discovery needs either:

- a wallet-local history index that tracks derived wallet addresses during sync,
- a future Yano address history provider,
- or a backfill scanner for restored wallets.

MVP should implement a wallet-local index in `wallet-core`:

- derive a gap-limit window,
- index wallet-relevant outputs and spends while Yano syncs,
- maintain pending submitted transactions,
- support background backfill for restored wallets if the node is already at
  tip,
- expose history status separately from balance status.

Balance and UTXO list can be available before full history backfill completes
because they can read current `UtxoState` directly.

## Transaction History

MVP transaction history should be wallet-local, not a general node API.

Sources:

- transactions submitted by this wallet,
- UTXO changes affecting derived wallet addresses,
- block events while wallet indexing is active,
- optional backfill over chain blocks for restore.

History status should be explicit:

- `SYNCING_NODE`: Yano not at tip.
- `INDEXING_WALLET`: node may be at tip, wallet-local history still catching up.
- `READY`: wallet index caught up to node tip.
- `PARTIAL`: current balance is available but historical backfill was skipped or
  interrupted.

Do not pretend transaction history is complete if the wallet was restored after
the node had already synced and no backfill has run.

## Basic Send Flow

Use CCL QuickTx:

1. UI collects recipient and ADA amount.
2. `wallet-core` validates address network and amount.
3. `wallet-yano-adapter` provides:
   - UTXOs,
   - protocol parameters,
   - transaction processor,
   - optional script supplier/evaluator.
4. QuickTx builds an unsigned transaction.
5. `wallet-core` creates a transaction summary.
6. JavaFX shows approval screen.
7. On approval, CCL signs with `SignerProviders.signerFrom(wallet)`.
8. Yano validates on submission through the existing transaction validation
   event path.
9. Wallet records pending transaction and watches for confirmation.

For MVP, do not build manual coin selection. Use CCL and Yano-backed
`UtxoSupplier`.

## CIP-30 Bridge MVP

CIP-30 methods are consent-gated. The bridge should model that explicitly.

MVP behavior:

- dApp calls `enable`.
- JavaFX prompts the user with origin and requested capabilities.
- If approved, bridge creates a scoped session.
- Read methods use wallet-core read services.
- `signTx` creates an approval request and returns only the witness set allowed
  by CIP-30.
- `submitTx` submits through Yano and returns the transaction id.

Minimum supported methods:

```text
enable
getNetworkId
getBalance
getUtxos
getChangeAddress
getRewardAddresses
signTx
submitTx
```

Defer:

- `signData` unless needed immediately. CCL has `CIP30DataSigner`, so it is a
  small follow-up after the approval UI exists.
- collateral-specific APIs.
- experimental namespaces.
- arbitrary website injection.

Implementation notes:

- CIP-30 `getBalance` returns CBOR value hex. Use CCL transaction spec/value
  serialization.
- CIP-30 `getUtxos` returns CBOR transaction unspent outputs. CCL has
  `CIP30UtxoSupplier` for reading such UTXOs; the wallet bridge should add a
  small serializer using CCL `TransactionInput`, `TransactionOutput`, and CBOR
  utilities instead of hand-rolled string encoding.
- `signTx` should deserialize transaction CBOR using CCL transaction spec,
  inspect it, sign only wallet-owned inputs, and return a witness set. If CCL
  does not expose a witness-set-only helper cleanly, add the smallest adapter in
  wallet-core and cover it with tests.

## JavaFX UI Direction

The first screen should be the app, not a marketing page.

MVP UI flow:

1. Network/runtime view:
   - selected network,
   - node running/stopped,
   - sync progress,
   - tip,
   - UTXO/ledger/wallet-index status.
2. Wallet setup:
   - create wallet,
   - restore wallet,
   - backup confirmation,
   - passphrase setup.
3. Wallet overview:
   - total ADA,
   - pending transactions,
   - receive address,
   - sync/index status.
4. Send:
   - recipient,
   - amount,
   - fee estimate,
   - transaction review,
   - approval.
5. UTXOs:
   - list by address/outpoint,
   - assets,
   - datum/script indicators.
6. History:
   - incoming/outgoing/self/pending,
   - confirmation status.
7. Developer:
   - local API status,
   - bridge sessions,
   - DevKit connection/profile,
   - transaction inspector.

Visual direction:

- modern desktop app, not a web landing page,
- dense but readable operational layout,
- clear network and signing state,
- strong danger/approval states for signing,
- no hidden signing or auto-approve behavior.

## GraalVM Native Image Plan

Native image is not part of the MVP implementation milestone, but the modules
must be designed for it.

Native-image-friendly decisions:

- keep wallet app independent from Quarkus,
- keep wallet logic in pure Java modules,
- avoid runtime classpath scanning in wallet modules,
- avoid reflection-heavy UI wiring,
- keep plugin systems out of wallet MVP,
- keep native-image metadata localized in `wallet-app`,
- prefer explicit service wiring,
- verify JavaFX native packaging with GluonFX,
- run native-image-agent against wallet UI flows before native packaging.

Phased packaging:

1. JVM `yano-wallet.jar`.
2. Platform-specific app bundle running JVM.
3. Native image prototype for devnet/preprod.
4. Native image release after security and sync tests pass.

Native image risks:

- JavaFX native packaging,
- RocksDB JNI,
- Netty/native DNS paths,
- generated event bindings,
- crypto providers,
- reflection metadata for CCL transaction models,
- native signing/notarization on macOS and Windows.

If a single native image is not practical initially, package `wallet-app` native
plus a managed `yano` native process behind `YanoRestNodeRuntime`. That is a
fallback, not the preferred architecture.

## MVP Scope

Build first:

- new modules:
  - `wallet-core`,
  - `wallet-yano-adapter`,
  - `wallet-bridge`,
  - `wallet-ui`,
  - `wallet-app`;
- multiple encrypted wallets;
- explicit account profiles per wallet seed;
- one active wallet account at a time;
- create wallet;
- restore wallet;
- encrypted vault;
- unlock/lock;
- address generation;
- receive address;
- balance;
- UTXO list;
- basic wallet-local transaction history;
- Yano embedded runtime lifecycle;
- Yano sync status;
- basic send ADA/native asset transactions;
- multi-recipient send using CCL QuickTx;
- transaction review and approval;
- transaction submission through Yano;
- local signTx approval flow;
- basic CIP-30 bridge:
  - `getNetworkId`,
  - `getBalance`,
  - `getUtxos`,
  - `getChangeAddress`,
  - `getRewardAddresses`,
  - `signTx`,
  - `submitTx`;
- JavaFX UI;
- devnet/preview/preprod/mainnet network profiles.

Do not build first:

- staking;
- governance;
- hardware wallet;
- multi-sig;
- full analytics;
- plugin system;
- native image release;
- advanced policy engine.

## Phases

### Phase 0: Scaffolding and Architecture

Deliverables:

- Gradle module scaffolding.
- Shared wallet configuration model.
- `WalletNodeRuntime` interface.
- Embedded runtime proof of life.
- CCL adapter skeletons.
- Vault interface and test-only in-memory implementation.
- JavaFX shell with node status.

Acceptance:

- `yano.jar` still builds and behaves unchanged.
- `wallet-app` can start without wallet features enabled.
- Unit tests cover adapter mapping basics.

### Phase 1: MVP Wallet

Deliverables:

- create/restore encrypted wallets,
- create additional account profiles from an unlocked wallet seed,
- encrypted vault,
- unlock/lock,
- address derivation and receive address,
- balance and UTXO list from Yano UTXO state,
- basic wallet-local transaction history index,
- ADA/native asset send via QuickTx,
- multi-recipient transaction composition,
- transaction review,
- UI approval,
- submission through Yano,
- pending/confirmed transaction tracking,
- sync and wallet-index status.

Acceptance:

- works on devnet by starting Yano in devnet mode directly,
- works on preprod/preview after sync,
- mainnet read-only works,
- mainnet send is available only after explicit network and transaction
  confirmation,
- no plaintext secrets on disk,
- no wallet secret logs,
- transaction validation failures are shown clearly.

### Phase 2: Local Platform and CIP-30 Bridge

Deliverables:

- local loopback bridge,
- dApp session approval,
- per-origin permissions,
- CIP-30 methods listed in MVP scope,
- local developer API,
- transaction inspector for bridge signTx,
- DevKit profile integration,
- documentation for dApp connector script.

Acceptance:

- a local dev dApp can connect to Yano Wallet through the connector,
- dApp cannot read wallet data before approval,
- dApp cannot sign or submit without explicit allowed flow,
- rejected requests return CIP-30 shaped errors where applicable.

### Phase 3: Hardening and Native Packaging

Deliverables:

- threat model,
- vault format versioning,
- OS keychain option,
- native-image-agent run profile,
- GluonFX/GraalVM native prototype,
- platform distribution tasks,
- startup/shutdown recovery tests,
- long-running sync tests.

Acceptance:

- native prototype starts, syncs, unlocks, builds a transaction, signs after UI
  approval, and submits on devnet/preprod,
- app can recover from interrupted sync,
- vault migration tests pass,
- signing approval tests pass.

### Phase 4: Platform Expansion

Deliverables:

- hardware wallet support,
- advanced account policies and portfolio views,
- staking,
- governance actions and voting,
- richer DevKit workflows,
- advanced transaction lab,
- optional plugin/policy engine,
- analytics.

These should be separate ADRs before implementation.

## API and Adapter Details

### `YanoUtxoSupplier`

Map `UtxoState` to CCL `UtxoSupplier`.

Rules:

- `getPage(address, pageSize, page, order)` should call
  `UtxoState.getUtxosByAddress(...)` or
  `getUtxosByPaymentCredential(...)` when search-by-vkh is enabled.
- CCL uses zero-based page in `DefaultUtxoSupplier`; Yano REST uses one-based
  page. The adapter must normalize this explicitly.
- `getTxOutput(txHash, outputIndex)` should use `UtxoState.getUtxo(...)`, and
  if wallet transaction inspection needs spent outputs, use
  `getUtxoSpentOrUnspent(...)` where available.
- Map assets, inline datum, datum hash, and reference script hash consistently
  with existing `UtxoMapper`.

### `YanoProtocolParamsSupplier`

Use:

- latest tip epoch from node status/config where available,
- `LedgerStateProvider.getProtocolParameters(epoch)`,
- `ProtocolParamsMapper.fromSnapshot(...)`.

Fallback:

- parse `NodeAPI.getProtocolParameters()` using
  `ProtocolParamsMapper.fromNodeProtocolParam(...)` if effective snapshots are
  unavailable.

Do not guess protocol parameters.

### `YanoTransactionProcessor`

Implement CCL `TransactionProcessor`.

`submitTransaction(byte[])` calls `NodeAPI.submitTransaction(byte[])` and wraps
the result in CCL `Result<String>`.

For `evaluateTx(...)`, either:

- delegate to Yano `TransactionEvaluationService` when embedded runtime exposes
  it, or
- return an unsupported result for MVP basic ADA transactions.

Script evaluation can be completed in Phase 2 if MVP focuses on ADA transfer.

### `WalletSigningService`

Use CCL signing APIs:

- `SignerProviders.signerFrom(Wallet)` for payment signing,
- `Wallet.sign(...)` for wallet UTXO-derived signers,
- `Account.sign(...)` only when operating on a concrete account,
- `CIP30DataSigner` for later `signData`.

Do not add new Cardano signing primitives in Yano Wallet unless CCL lacks a
required public adapter.

## Testing Strategy

Unit tests:

- vault encryption/decryption and failed unlock,
- no plaintext secret in vault file,
- derivation metadata,
- Yano UTXO to CCL UTXO mapping,
- protocol params mapping,
- QuickTx ADA send using mocked Yano suppliers,
- transaction approval model,
- CIP-30 request/response shapes.

Integration tests:

- wallet create on devnet,
- wallet restore on devnet,
- sync status display model,
- send ADA on Yano devnet,
- optional DevKit integration smoke test after the direct devnet path works,
- submit invalid tx and surface validation errors,
- local bridge approval/rejection,
- dApp signTx flow with a sample connector.

End-to-end UI tests:

- JavaFX startup,
- create wallet wizard,
- restore wallet wizard,
- lock/unlock,
- send review and approval,
- dApp approval dialog.

Native-image tests later:

- app startup,
- vault unlock,
- node startup,
- sync smoke test,
- ADA send on devnet/preprod.

## Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| Wallet code destabilizes core node app | Keep wallet modules separate and do not depend on `node-app`. |
| Key leakage | Isolated vault, no private keys in NodeAPI, no signing in bridge without approval, secret zeroization where practical. |
| Incomplete transaction history on restore | Track wallet history status and implement background backfill. Do not claim history is complete until index catches up. |
| CIP-30 expectations exceed local bridge | Be explicit that MVP is a loopback dev bridge plus JS connector; browser extension comes later. |
| Native image complexity | Avoid Quarkus in wallet-app, keep reflection low, use GluonFX/native-image-agent, retain process-mode fallback. |
| Mainnet signing too early | Gate mainnet signing behind explicit warnings and do security review before public positioning. |
| Address discovery uses only current UTXO state | Add wallet-local history/backfill or future address-history provider. |

## Open Questions

1. Should the first public MVP allow mainnet transaction signing, or should
   mainnet be read-only until the vault and approval flow have a separate
   security review?
2. Should the initial vault store encrypted mnemonic/root key for simplicity, or
   should we block MVP until encrypted account-key storage is cleanly supported?
3. Is the first dApp target a local DevKit/dev dApp that can load a JS connector,
   or should a browser extension/native messaging host be part of Phase 2?
4. Should `wallet-yano-adapter` expose a full CCL `BackendService`, or keep the
  smaller `UtxoSupplier`/`ProtocolParamsSupplier`/`TransactionProcessor`
  adapters until a concrete need appears?

## Implementation Progress

Progress is updated as implementation tasks land.

### 2026-05-01

- [x] Updated ADR for Quarkus bridge option, direct Yano devnet MVP profile,
  preview/preprod/mainnet profile support, and preprod chainstate testing note.
- [x] Add Gradle wallet module scaffolding.
- [x] Add wallet-core vault contracts and encrypted file vault.
  - Added wallet network profile model for devnet, preview, preprod, and
    mainnet.
- [x] Add wallet-yano-adapter CCL/Yano adapters.
  - Added `YanoUtxoSupplier`, `YanoProtocolParamsSupplier`,
    `YanoTransactionProcessor`, and wallet runtime delegation contracts.
- [x] Add bridge, UI, and app placeholders.
  - Added bridge session/permission domain contracts and in-memory session
    registry for the later Quarkus transport layer.
- [x] Add focused unit tests for implemented functionality.
  - Added vault, network profile, bridge session, and Yano adapter tests;
    verified with `:wallet-core:test`, `:wallet-yano-adapter:test`,
    `:wallet-bridge:test`, and `:wallet-app:compileJava`.
- [x] Add runnable JavaFX wallet UI shell.
  - Added an operational dashboard with network selector, sync controls, wallet
    setup entry points, balance/UTXO/history placeholders, and local bridge
    status.
  - Clarified UI labels so the first shell does not imply that real preprod
    chainstate is opened yet.
  - Verified with `:wallet-ui:compileJava` and `:wallet-app:run`.
- [x] Add modern JavaFX launch screen and wallet runtime wiring.
  - Added a Yano logo mark, launch controls, runtime status tiles, current
    address panel, balance/UTXO state, and QuickTx draft status.
  - Wired launch actions to open the local preprod chainstate, restore a wallet
    in memory, scan balance/UTXOs, and build a signed self-payment draft without
    submitting it.
  - Kept the supplied preprod recovery phrase out of source, tests, logs, and
    ADR text.
  - Verified the launch screen with `:wallet-app:run`.
- [x] Add preprod chainstate probe path for local verification.
  - Added `--probe-preprod-wallet` for headless validation using
    `YANO_WALLET_TEST_MNEMONIC`.
  - Verified against the real `node-app/chainstate` preprod RocksDB state at tip
    slot `121880214` / block `4659382`.
  - Verified wallet restore, balance scan, UTXO scan, and signed QuickTx draft
    creation; no transaction submission was performed.
- [x] Move preprod wallet testing to a wallet-owned chainstate copy.
  - Copied the current preprod RocksDB state from `node-app/chainstate` to
    `~/.yano-wallet/networks/preprod/yano/chainstate`.
  - Updated the JavaFX launch flow and headless probe to prefer the wallet data
    directory so the desktop app does not mutate the original node-app
    chainstate.
- [x] Replace placeholder sync with embedded Yano preprod client sync.
  - Added runtime controller operations for start sync, stop runtime, status
    refresh, wallet refresh, signed ADA send draft, and explicit submit.
  - Updated JavaFX polling so local/remote tip, sync progress, balance, and UTXO
    count refresh while the wallet runtime syncs.
  - Verified sync-mode startup against the copied wallet chainstate; Yano
    connected to the preprod relay, found intersection, and began pipelined sync.
- [x] Fix wallet sync crash caused by RocksDB metrics sampling.
  - Reproduced a native JVM crash during JavaFX sync startup. The crash occurred
    in RocksDB JNI `DBImpl::GetProperty` from `DefaultUtxoStore`'s background
    estimate sampler.
  - Disabled Yano RocksDB/UTXO metrics sampling for the embedded wallet runtime
    with `yaci.node.metrics.enabled=false`.
  - Added a sync linger probe and verified preprod sync stayed alive past the
    previous crash window, advanced local slots, refreshed status, and shut down
    cleanly.
- [x] Add wallet-local pending transaction lifecycle MVP.
  - Added `PendingTransaction`, `PendingTransactionStatus`,
    `PendingTransactionStore`, and JSON-backed `FilePendingTransactionStore` in
    `wallet-core`.
  - Extended QuickTx drafts with TTL/invalid-hereafter slot capture where CCL
    provides it.
  - Updated `submitLastDraft` to persist a wallet-local pending record after
    successful submission, and to persist failed submission attempts with error
    visibility.
  - Added JavaFX history/pending transaction rows and polling refresh so a
    submitted transaction is visible immediately as pending.
  - Added a Yano block event listener that marks pending transactions confirmed
    when their tx hash appears in a newly applied valid block.
  - Added rollback handling that moves confirmed transactions removed by rollback
    into an active rolled-back state so they can be re-confirmed or expire.
  - Added pending transaction expiry checks from the current local tip when TTL
    is known.
  - Verified with `:wallet-core:test`, `:wallet-yano-adapter:test`,
    `:wallet-ui:compileJava`, and `:wallet-app:compileJava`.
- [x] Harden pending transaction startup reconciliation.
  - Added a `YanoPendingTransactionReconciler` that uses Yano's UTXO
    `getOutputsByTxHash` index to confirm persisted pending ADA transactions
    after app restart or runtime refresh.
  - Wired reconciliation into preprod chainstate open, sync start, runtime
    status refresh, and pending transaction refresh.
  - Added adapter tests for pending -> confirmed reconciliation from indexed
    chainstate data.
- [x] Add encrypted persistent wallet repository in wallet-core.
  - Added network-scoped wallet storage under the intended
    `~/.yano-wallet/networks/{network}/wallets` layout.
  - Added plaintext wallet metadata only for wallet id, name, network, account
    index, display receive address, stake address, DRep id, vault file, and
    timestamps.
  - Kept mnemonic material encrypted per wallet using the existing
    `FileWalletSecretStore`; tests assert neither index nor vault files contain
    plaintext mnemonic text.
  - Added restore/import, random 24-word wallet creation, unlock, duplicate
    prevention, and wrong-passphrase coverage.
  - Added multi-address account view support using CCL Wallet APIs, including
    receive addresses, stake address, and DRep id for the active account.
  - Verified with `:wallet-core:test`.
- [x] Wire persistent wallets into wallet-app and JavaFX.
  - Added controller operations to list stored wallets, generate a new preprod
    mnemonic, import/restore a mnemonic into an encrypted vault, unlock a stored
    wallet, and refresh active account details.
  - Added JavaFX create/import/unlock flows with passphrase confirmation and a
    stored wallet selector.
  - Added a receive-address account panel showing multiple base addresses for
    the active account, plus stake address and DRep id.
  - Kept the older in-memory restore path for local testing, but the normal UI
    path now stores imported/created wallets under the encrypted per-network
    wallet directory.
  - Verified with `:wallet-core:test`, `:wallet-ui:compileJava`, and
    `:wallet-app:compileJava`.
- [x] Harden multi-address account support.
  - Updated account address derivation to use the stored wallet account index
    explicitly through CCL wallet/account APIs.
  - Added role and BIP32 derivation path metadata for each displayed receive
    address while continuing to expose base and enterprise addresses.
  - Added JavaFX controls to load additional receive addresses for the active
    account without unlocking or re-importing the wallet.
  - Kept stake address and CIP-129 DRep id visible with the active account
    details.
  - Verified with `:wallet-core:test`, `:wallet-ui:compileJava`, and
    `:wallet-app:compileJava`.
- [x] Add ADA/native asset send draft support with metadata.
  - Extended the QuickTx draft builder to accept optional native asset transfers
    using CCL `Amount.asset(...)`.
  - Added optional CIP-20 message metadata attachment from the send screen
    metadata text area.
  - Updated QuickTx draft snapshots and the JavaFX send form to show asset and
    metadata summaries during review.
  - Verified with `:wallet-core:test`, `:wallet-yano-adapter:test`,
    `:wallet-ui:compileJava`, and `:wallet-app:compileJava`.
- [x] Add local CIP-30 bridge service contract.
  - Added a testable `LocalCip30BridgeService` in `wallet-bridge` for
    `getNetworkId`, `getBalance`, `getUtxos`, `getChangeAddress`,
    `getRewardAddresses`, `signTx`, and `submitTx`.
  - Added explicit bridge error categories, wallet backend contract, sign result
    model, and approval request/handler abstractions.
  - Enforced per-session permissions and active approval for `signTx` and
    `submitTx`; the default approval handler denies all signing/submission.
  - Added tests for read permissions, sign permission, approval refusal,
    approval success, submit authorization, and invalid CBOR rejection.
  - Verified with `:wallet-bridge:test`.
- [x] Wire bridge backend to the active wallet runtime.
  - Added an app-side `ActiveWalletBridgeBackend` that serves CIP-30 reads from
    the currently unlocked wallet and Yano UTXO state.
  - Encoded `getBalance` as Cardano `Value` CBOR, `getUtxos` as CIP-30
    transaction-unspent-output CBOR, and change/reward addresses as raw address
    bytes hex.
  - Added bridge signing support that deserializes a dapp transaction, matches
    transaction inputs to active wallet UTXOs with CCL derivation paths, signs
    through CCL Wallet APIs, and returns the witness-set CBOR hex.
  - Routed `submitTx` through the wallet app's Yano transaction processor when
    the embedded runtime is in sync mode.
  - Exposed a controller factory for `LocalCip30BridgeService` so the future
    Quarkus loopback transport can reuse the same backend and approval handler.
  - Verified with `:wallet-bridge:test`, `:wallet-app:test`, and
    `:wallet-app:compileJava`.
- [x] Add loopback HTTP transport for the local bridge.
  - Added `LocalBridgeHttpServer` in `wallet-bridge`, bound only to the local
    loopback address with a single JSON `/cip30` endpoint.
  - Added an explicit `BridgeHttpAccessPolicy` so HTTP `enable` requests are
    refused unless the wallet app approves the origin and requested
    permissions.
  - Exposed read, sign, and submit methods over HTTP while keeping `signTx` and
    `submitTx` behind the existing bridge approval handler.
  - Added CORS preflight handling for browser-based local bridge shims while
    preserving per-origin session approval.
  - Verified allowed-origin reads, refused origins, and refused sign approvals
    with `:wallet-bridge:test`.
- [x] Wire bridge start/stop and approval prompts into wallet-app/JavaFX.
  - Extended `WalletRuntimeController` with bridge start, stop, and status
    operations plus UI callback contracts for connection and transaction
    approvals.
  - Wired `YanoWalletAppController` to start the loopback HTTP bridge against
    the active wallet backend and to stop it during controller shutdown.
  - Replaced the placeholder JavaFX bridge toggle with a real start/stop path,
    endpoint/session display, origin approval prompt, and transaction approval
    prompt.
  - Added a controller test for loopback bridge start, status refresh, and stop.
  - Verified with `:wallet-bridge:test`, `:wallet-ui:compileJava`,
    `:wallet-app:test`, and `:wallet-app:compileJava`.
- [x] Record bridge-submitted transactions in wallet-local history.
  - Added a submission hook to `ActiveWalletBridgeBackend` so successful
    `submitTx` calls can notify the wallet app with tx hash and signed CBOR.
  - Wired the wallet app hook to persist a generic pending transaction record
    for bridge submissions, including fee and TTL when transaction inspection
    succeeds.
  - This makes dapp-submitted transactions visible to the same pending ->
    confirmed reconciliation path as JavaFX-created transactions.
  - Verified with `:wallet-app:test` and `:wallet-app:compileJava`.
- [x] Fix live overview wallet balance refresh.
  - Bound the overview wallet and balance panels to JavaFX state properties
    instead of one-time string snapshots.
  - Updated the wallet Refresh action to reload stored wallets, refresh the
    active wallet balance/UTXOs, and refresh active account details.
  - Verified with `:wallet-ui:compileJava`.
- [x] Serve a CIP-30 JavaScript shim from the local bridge.
  - Added `/yano-cip30.js` to the loopback bridge server.
  - The script injects `window.cardano.yano`, `window.cardano.yanoWallet`, and
    `window.yanoWallet` and forwards CIP-30 calls to the local `/cip30` JSON
    endpoint.
  - The shim requests read, sign, and submit permissions through the existing
    origin approval prompt; `signTx` and `submitTx` remain protected by the
    transaction approval prompt.
  - Verified script serving and injection contents with `:wallet-bridge:test`.
- [x] Add seed-scoped multi-account wallet support.
  - Added a `seedId` to stored wallet profiles so multiple account-index
    profiles can share one encrypted mnemonic vault without duplicating secret
    material.
  - Added account creation under the active encrypted wallet; the new account is
    derived in memory from the unlocked CCL wallet and becomes the active
    account.
  - Updated the JavaFX wallet selector and overview panel to show account index
    and active account state.
  - Verified repository vault sharing and app-level account activation with
    `:wallet-core:test`, `:wallet-app:test`, and `:wallet-ui:compileJava`.
- [x] Add native asset balance aggregation for wallet send flows.
  - Extended wallet balance scans to aggregate native assets across discovered
    account UTXOs.
  - Exposed asset balances through wallet snapshots so JavaFX can populate
    asset selection controls from real account state.
  - Verified aggregation with `:wallet-core:test`.
- [x] Redesign JavaFX send for multi-recipient ADA/native asset QuickTx drafts.
  - Reworked the send screen around explicit recipient outputs, each with
    optional ADA and one or more native assets selected from available balances.
  - Added aggregate output validation so recipient rows cannot collectively
    request more displayed ADA/native asset balance than the active account has.
  - Extended the QuickTx service to build one signed transaction from multiple
    `payToAddress` outputs, including token-only outputs where CCL handles
    minimum ADA.
  - Kept metadata support and explicit submit as before.
  - Verified with `:wallet-core:test`, `:wallet-ui:compileJava`, and
    `:wallet-app:test`.
- [x] Add a top-level CIP-30 browser dApp example.
  - Added `wallet-cip30-example`, a Vite/TypeScript/Mesh browser application
    that loads the local `/yano-cip30.js` shim, discovers all standard
    `window.cardano` wallets, lets the user connect a selected wallet, reads
    wallet state, builds multi-recipient ADA/native-asset transactions with
    optional CIP-20 metadata, signs through CIP-30, and submits through the
    connected wallet.
  - The same example can connect to Yano Wallet through the local bridge or to
    standard browser wallets such as Eternl when installed.
  - The app only accepts loopback Yano shim URLs and never asks for or stores
    wallet secrets.
  - Added helper tests for loopback shim URL normalization and metadata chunking.
  - Switched the example from the full `@meshsdk/core` umbrella dependency to
    `@meshsdk/transaction` plus `@meshsdk/wallet`, which removed transitive
    provider/UTxO-RPC dependencies from the browser demo and made
    `npm audit` clean.
  - Verified with `npm test`, `npm audit`, and `npm run build`.
- [x] Harden local CIP-30 bridge origin handling.
  - Changed bridge `enable` to trust the browser `Origin` header instead of the
    JSON body origin supplied by the page.
  - Refused `enable` when body origin and header origin disagree.
  - Bound all tokenized bridge calls to the same browser origin that created the
    session, preventing a token from being replayed by a different origin.
  - Added no-store and content-type hardening headers to bridge responses.
  - Verified with new bridge service and HTTP tests in `:wallet-bridge:test`.
- [x] Reserve a deterministic local CIP-30 bridge port.
  - Changed the wallet bridge default from an ephemeral port to
    `127.0.0.1:47000`.
  - Kept the explicit test/override start method for non-default ports while
    making `wallet-app` use the reserved endpoint by default.
  - Updated the browser example to include
    `http://127.0.0.1:47000/yano-cip30.js` directly and to prefill the matching
    `/cip30` endpoint.
  - Documented the fixed-port port-hijack limitation: this improves developer
    ergonomics but does not authenticate the local process.
  - Verified with `:wallet-bridge:test`, `:wallet-app:test`, `npm test`, and
    `npm run build`.
- [x] Fix browser demo startup with Mesh browser dependencies.
  - Split the browser demo entrypoint into a small bootstrap module that defines
    browser-safe `global`, `process`, and `Buffer` globals before dynamically
    importing Mesh wallet/transaction code.
  - Kept the fixed bridge shim include in `index.html`.
  - Verified the blank-page failure was caused by early dependency evaluation
    errors for `global`/`Buffer`; after the bootstrap change Vite reports only
    non-fatal Mesh dependency compatibility warnings.
  - Verified with `npm test` and `npm run build`.
- [x] Improve JavaFX bridge transaction approval detail.
  - The `signTx`/`submitTx` approval prompt now attempts to decode transaction
    CBOR with CCL and shows origin, method, partial-sign flag, fee, input counts,
    reference/collateral counts, metadata presence, advanced field warnings,
    and output address/value summaries before approval.
  - Unknown or undecodable CBOR still falls back to a clear decode error plus an
    abbreviated CBOR preview.
  - Verified with `:wallet-ui:compileJava` and `:wallet-app:compileJava`.
- [x] Fix CIP-30 browser demo/Yano bridge connection usability.
  - Made the `window.cardano.yanoWallet` compatibility alias non-enumerable and
    filtered it from demo discovery so the browser example shows a single Yano
    Wallet entry instead of both `yano` and `yanoWallet`.
  - Removed the browser demo's Mesh-only transaction build restriction. Generic
    CIP-30 wallets now decode balance, change address, and UTXOs from CBOR hex
    and can build/sign/submit through the same demo path.
  - Kept signing secure by routing raw CIP-30 `signTx` through the desktop
    bridge, which still requires the JavaFX approval prompt.
  - Brought JavaFX bridge approval dialogs to the front when shown so dApp
    connection/signing prompts are visible during browser testing.
  - Added a visible JavaFX bridge activity line and automatic bridge status
    refresh after connection/signing prompts so dApp activity updates the
    desktop UI instead of only changing in the browser.
  - Verified with `npm test`, `npm run build`, `:wallet-bridge:test`,
    `:wallet-ui:compileJava`, and `:wallet-app:compileJava`.
- [x] Improve wallet UI layout, identifier copy, and default change handling.
  - Increased the default JavaFX viewport and moved the full receive-address
    table to a dedicated Addresses page so the default Overview surface fits
    without an immediate vertical scrollbar.
  - Made long address/stake/DRep values and address table cells copyable with
    read-only text fields.
  - Rendered transaction hashes and UTXO outpoints as clickable Cardanoscan
    links for mainnet, preprod, and preview; devnet remains copy-only because
    no public explorer exists for local chains.
  - Made wallet QuickTx drafts explicitly set change to the active base sender
    address and changed CIP-30 `getChangeAddress` to return that same address
    instead of an internal HD change address.
  - Verified with `:wallet-core:test`, `:wallet-app:test`, and
    `:wallet-ui:compileJava`.
- [x] Add first GraalVM native-image build for wallet-app.
  - Added `:wallet-app:nativeWalletImage`, defaulting to
    `~/.sdkman/candidates/java/25.0.2-graal` when `GRAALVM_HOME`/`JAVA_HOME`
    are not set.
  - Kept native-image metadata under owning modules:
    `wallet-app`, `wallet-ui`, `wallet-core`, `wallet-bridge`, and
    `node-runtime`.
  - Added native reflection/resource metadata for wallet JSON records, bridge
    request/response DTOs, JavaFX CSS, node runtime service/resources, and
    wallet app entry points.
  - The native-image task intentionally excludes `slf4j-reload4j`/`reload4j`
    from the native build classpath to avoid freezing build-time log file
    appenders into the image. The regular JVM wallet runtime still uses the
    normal logging classpath.
  - Added `--native-smoke` to verify the native executable starts without
    opening JavaFX or touching wallet secrets.
  - Verified with `:wallet-app:nativeWalletImage`,
    `wallet-app/build/native/yano-wallet --native-smoke`,
    `:wallet-core:test`, `:wallet-bridge:test`, `:wallet-app:test`,
    `:wallet-ui:compileJava`, and `:wallet-app:classes`.
- [x] Make the GraalVM native image launch the JavaFX UI.
  - Fixed native-image metadata discovery by making `nativeWalletImage` depend
    on wallet module resource processing and pass each module's
    `META-INF/native-image` config directory explicitly to GraalVM.
  - Added `wallet-ui` JavaFX reflection metadata for dynamically loaded toolkit,
    Glass, Prism Metal/ES2/software pipeline, and Mac GL classes.
  - Added `wallet-ui` JavaFX JNI metadata for the macOS Glass callbacks and
    CoreText font structures collected from a short native-image-agent JVM run.
  - Added JavaFX shader, Modena, resource bundle, and native dylib resource
    patterns under `wallet-ui` native-image config.
  - Verified with `:wallet-app:nativeWalletImage` and launched
    `wallet-app/build/native/yano-wallet`; the hand-rolled native image starts
    but is not the release path for macOS JavaFX because the Stage is not shown
    reliably from that launcher.
- [x] Add GluonFX native desktop packaging path for the visible JavaFX wallet.
  - Added the GluonFX Gradle plugin to `wallet-app` and kept it isolated from
    the regular `yano.jar` and JVM `wallet-app` paths.
  - Added `:wallet-app:gluonNativeWalletImage` as the native desktop build
    alias, with module-owned reflection/resource configuration kept beside the
    modules that require it.
  - Force-linked GraalVM `libmanagement.a` and `libmanagement_ext.a` to satisfy
    macOS native-link symbols required by the Yano runtime dependency graph.
  - Added a macOS/aarch64 compatibility task,
    `patchGluonJavafxGlassJniVersion`, because Gluon's JavaFX static SDK
    `21-ea+11.3` reports JNI 1.4 from Glass and Oracle GraalVM 25 rejects it.
    The task patches the local cached static `libglass.a` to report JNI 1.8,
    keeps a backup, and can restore it with
    `restoreGluonJavafxGlassJniVersion`.
  - Replaced Gradle `project.exec` usage in that patch task with
    `ProcessBuilder` so the task works under the current Gradle version.
  - Verified with `:wallet-app:patchGluonJavafxGlassJniVersion` and
    `:wallet-app:gluonNativeWalletImage`.
  - Launched `wallet-app/build/gluonfx/aarch64-darwin/wallet-app`; the native
    JavaFX wallet opened successfully and used the wallet-owned preprod
    chainstate under `~/.yano-wallet/networks/preprod/yano/chainstate`.
- [x] Record native packaging license/compliance checkpoint.
  - No new mandatory dependency was added to the core Yano node jar.
  - The GluonFX Gradle plugin is build-time tooling for `wallet-app`; the
    plugin repository is BSD-3-Clause, while Gluon Substrate is GPL-2.0
    build tooling and should not be shipped as a runtime dependency.
  - JavaFX static libraries linked into the native executable are OpenJFX code
    under GPLv2 with the Classpath Exception, so release packaging must include
    the required notices and keep the exception intact.
  - The local `libglass.a` binary patch is acceptable for development testing,
    but it is a release compliance risk unless replaced by an upstream-compatible
    Gluon/JavaFX static SDK or documented as a reproducible patch with source
    and notices.
  - Oracle GraalVM 25 is currently used as the native-image toolchain; before
    distributing binaries, confirm the selected GraalVM distribution's
    redistribution terms and prefer an OpenJDK/GraalVM CE-compatible path if
    free redistribution of packaged binaries is required.

## Transaction Lifecycle Slice

Status: MVP implementation landed for the JavaFX wallet, local bridge, and
browser CIP-30 example. The remaining work is manual preprod submit-to-confirm
validation through the desktop UI and browser example, followed by deeper
transaction inspection/policy hardening.

This slice closes the transaction lifecycle gap before adding more surface area
such as the CIP-30 bridge transport. A submitted transaction must be visible to
the user immediately, remain pending while the wallet waits for block inclusion,
and move to confirmed only after Yano observes the transaction in an applied
block.

### Pending Transaction Lifecycle

Add a wallet-local pending transaction component in `wallet-core`, with UI and
Yano adapter integration in `wallet-app` / `wallet-ui`.

Required state model:

- `DRAFTED`: transaction has been built and signed locally but not submitted.
- `SUBMITTED`: transaction submission returned successfully from Yano.
- `PENDING`: transaction is waiting for block inclusion.
- `CONFIRMED`: transaction was observed in an applied block.
- `ROLLED_BACK`: a previously confirmed transaction was removed by rollback and
  should return to pending if still valid.
- `EXPIRED`: transaction is past its TTL without confirmation.
- `FAILED`: submission or validation failed with an error that needs user
  visibility.

Required persisted fields:

- transaction hash
- signed transaction CBOR
- created/submitted timestamps
- current status
- source wallet id
- network profile
- total outgoing ADA
- fee
- recipient summary
- TTL / invalid-hereafter slot when available
- confirmed slot, block number, and block hash once known
- last error message when failed

Implementation direction:

- Store the pending transaction index under the wallet data directory, separate
  from Yano chainstate. This keeps wallet UI state disposable without mutating
  node data.
- When `submitLastDraft` succeeds, create or update a pending transaction record
  before the UI refreshes balance.
- Add a confirmation watcher that observes newly applied blocks from the embedded
  Yano runtime and matches transaction hashes. If a direct runtime event is not
  available yet, add the smallest wallet adapter needed to scan new block bodies.
- On confirmation, mark the transaction confirmed and refresh balance, UTXOs,
  and history.
- On rollback, move a confirmed transaction back to pending until it is
  re-observed or expires.
- On startup, reload pending transactions and reconcile them against the current
  tip before showing the dashboard.

Acceptance criteria:

- After a successful submit, the transaction appears in the UI immediately as
  pending with hash, fee, amount, and recipient summary.
- The transaction remains pending until Yano sees it in an applied block.
- Once included in a block, the UI marks it confirmed and shows confirmation
  slot/block details.
- Pending transactions survive app restart.
- Expired or failed transactions are visible and actionable instead of silently
  disappearing.
- Unit tests cover pending store persistence and status transitions.
- Integration/manual preprod testing covers submit -> pending -> confirmed using
  the wallet-owned preprod chainstate.

### Recommended Ordering

1. Pending transaction store and state model in `wallet-core`.
2. Controller integration so `submitLastDraft` records submitted transactions.
3. JavaFX history/pending panel updates.
4. Embedded Yano block confirmation watcher.
5. Rollback and expiry handling.
6. CIP-30 bridge MVP using the same transaction lifecycle path for `signTx` and
   `submitTx`.

## Summary

Build Yano Wallet as a separate wallet platform on top of Yano, not inside the
existing node app.

The maintainable path is:

- keep `yano.jar` as-is,
- build `yano-wallet.jar` in `wallet-app`,
- embed `node-runtime` by default,
- keep a process-mode adapter for fallback,
- use CCL 0.8.0-pre3 for HD wallet, QuickTx, signing, transaction spec, and
  CIP helpers,
- keep private keys only in wallet-core,
- require UI approval for all signing,
- implement MVP as a JavaFX desktop app with encrypted wallet/account
  selection, multi-recipient ADA/native asset send, and a basic local CIP-30
  bridge.
