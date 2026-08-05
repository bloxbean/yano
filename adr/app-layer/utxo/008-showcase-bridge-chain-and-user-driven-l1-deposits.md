# ADR-UTXO-008: Showcase Bridge Chain and User-Driven L1 Deposits

- Status: Proposed
- Version: v1
- Date: 2026-08-05
- Owners: App-chain / EUTxO / Showcase / Console
- Related: ADR-023 (unified showcase distribution), ADR-UTXO-001, ADR-UTXO-002,
  ADR-UTXO-003, ADR-UTXO-006, ADR-UTXO-007, ADR-025.2 (console patterns)

## 1. Idea in plain words

The light showcase profile today demonstrates the EUTxO state machine only as
a virtual ledger (`payments-chain`): funds are genesis-minted ledger state and
never touch a real L1 UTxO. The Cardano custody boundary — deposits into an
L1 vault, mirrored L2 credits, operator settlement paying withdrawals back out
— is demonstrated only by the separate `eutxo` profile, which stands up its
own private devnet cluster through the maintained `appchain-eutxo-demo`
harness.

This ADR adds the bridge story to the shared light cluster as an 11th chain,
`payment-chain-l1bridge`, so one console shows the virtual ledger and the L1
bridge side by side, and extends the demo experience to real user
participation:

1. the maintained bridge demo workflow gains an **attach mode** so it can
   drive an already-running cluster instead of always creating its own;
2. the showcase gains a `bridge` command group (automated devnet flow +
   `bridge info` guidance on any network);
3. the showcase Java client gains an **eutxo demo** where a user deposits
   their own L1 funds with their own mnemonic and operates on the L2;
4. a later milestone adds a **console deposit screen** using standard CIP-30
   connect-wallet, with the node building the unsigned deposit transaction.

The ZK (validity) variant intentionally stays in the `eutxo` profile with its
ceremony flow; this ADR does not move it.

## 2. Motivation

- The showcase exists to demonstrate all app-chain capabilities from one
  cluster; the custody boundary is the most-asked follow-up ("how does real
  ADA get in and out?") and today requires switching to a second cluster.
- The consensus-critical half of bridge mode (state-machine bridge
  transitions, `eutxo-vault-deposit-v1` and
  `eutxo-withdrawal-confirmation-v1` L1 observers) already ships in the light
  distribution — bridge is enabled purely by per-chain settings
  (`machines.eutxo.bridge.*` + `observers.*`), not by different code.
- The missing piece is only the off-chain actor that builds and signs L1
  transactions. That actor exists (`EutxoBridgeDemoWorkflow`) but is welded
  to a self-managed workspace/cluster; decoupling it is cheaper and safer
  than duplicating the L1 choreography in showcase tooling.
- On public networks, a wallet-owned deposit driven by the user's own
  mnemonic (and later a CIP-30 wallet) turns the bridge from a scripted
  demo into a participatory one.

## 3. Decisions

### D1. One maintained L1 driver, two consumers

`EutxoBridgeDemoWorkflow` remains the single source of truth for the bridge
choreography (fund → deposit → transfer → settle → verify). It is NOT copied
into the showcase module. `appchain-eutxo-demo` gains an attach mode:

```
yano.sh appchain eutxo demo <op> --scenario bridge \
  --target-base <http://host:port> --chain-id payment-chain-l1bridge \
  --workspace <dir> [--count N]
```

Attach mode skips project generation and cluster start/stop, keeps its own
workspace strictly for wallets/journal/artifacts, and points every step at
the supplied API base. The existing self-managed mode is unchanged; the
`eutxo` profile keeps using it. Workspace manifests record the mode and
target so the two modes cannot be mixed on one workspace.

### D2. `payment-chain-l1bridge` as `chains[10]` in the light profile

- `state-machine: eutxo-ledger`, bridge settings per the capability catalog
  (`machines.eutxo.bridge.observer-id/vault-address/vault-script-hash/
  confirmation-observer-id/withdrawal-address/epoch/max-*`, plus the two
  `observers.*` blocks and `l1.stability-depth: 2`).
- NO `machines.eutxo.genesis.*` — `funding:eutxo-genesis` conflicts with
  `bridge:cardano-federated`; all L2 funds enter via deposits.
- Plugin allow-list gains `com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano`
  (without it the node fails startup: configured observer type not selected).
- Vault v1 = the demo shape: native script over a deterministic showcase
  operator key (same public-demo-key posture as other showcase identities).
  The address is derived, nothing is deployed on L1.
- Both delivery paths, mirroring the `authenticated-map-jmt-chain` pattern:
  present in source for fresh instances, and a `chain add
  payment-chain-l1bridge` migration tier (10→11 chain list) for existing
  instances. `showcase.sh` / `showcase_identity.py` chain-list constants,
  `showcase-contract.sh`, and `distribution-contract.sh` are updated
  accordingly.

### D3. Network posture: document, do not block

No client- or script-side network refusal and no forced confirmation flags.
The docs (a new `docs/BRIDGE_CHAIN.md` walkthrough plus DEMO_SHOWCASE.md
section) state plainly:

- the showcase vault is a single-operator-key native script — the operator IS
  custody; suitable for capped-amount demos, not for real funds (ADR-UTXO-006/
  007 describe the hardened tiers);
- deposits are bounded by the chain's `max-deposit-lovelace` (consensus-side
  enforcement, the one cap that stays);
- a plain wallet transfer to the vault address is NOT a deposit — the
  observer credits L2 owners from the inline vault datum, so deposits must be
  built by the provided tooling (client, or later the console screen).

This consciously relaxes ADR-023's "bridge is devnet-only in the showcase"
line for guided/user-driven use; the automated faucet-funded flow remains
devnet-only by nature (`/devnet/fund` exists only under dev-mode).

### D4. Showcase `bridge` command group

- Devnet: `./showcase.sh bridge run|deposit|transfer|settle|verify …`
  delegates to the packaged demo CLI in attach mode against the light
  cluster (thin delegation, `delegate_eutxo` pattern).
- Any network: `./showcase.sh bridge info` reads the running chain's bridge
  config and prints the facts (vault address + script hash, max deposit,
  stability depth, withdrawal address/epoch) and, on public networks, the
  exact showcase-client command to run next — guidance with a copy-paste next
  step instead of a dead end.

### D5. Showcase-client eutxo demo (user- and operator-driven)

`ShowcaseClientDemos` gains an `eutxo` demo class with two roles:

- User role: `deposit` (L1 mnemonic via file or interactive prompt, never
  argv; builds the vault deposit with the correct inline datum; submits;
  polls the L2 until the mirrored deposit appears), plus L2 operations:
  balance/UTxO list, transfer, withdrawal claim, proof fetch.
- Operator role: `settle` — clearly labeled as the vault keyholder's action;
  unlocks the vault and pays the claim's payout address.

Backend: Yano's Blockfrost-compatible endpoints only (UTxOs, protocol
params, submit) — the client talks to the same node for L1 and L2; no Koios
or external provider. L2 owner credential defaults to the depositor's
payment key hash (one identity across layers; overridable).

### D6. Console deposit screen (later milestone)

When an eutxo bridge chain is selected in the console: a deposit page using
standard CIP-30 connect-wallet.

- Server-built transaction, wallet-signed: a small node endpoint takes
  depositor address, amount, and L2 owner credential and returns the UNSIGNED
  deposit CBOR built with cardano-client-lib (datum logic stays in Java,
  shared with D5 — never reimplemented in TypeScript). Browser flow:
  connect → fetch unsigned tx → `wallet.signTx` → submit (wallet or node).
  The node only assembles bytes; custody stays in the user's wallet.
- Endpoint: `POST /api/v1/app-chain/chains/{chainId}/eutxo/bridge/deposit/build`
  — chain-scoped (vault address/script hash, datum shape, and
  `max-deposit-lovelace` all come from the chain's bridge config), namespaced
  under `eutxo/bridge` to leave room for the symmetric
  `…/eutxo/bridge/withdrawal/build`, `…/build` verb suffix per the existing
  `proof/verify` convention. Request
  `{depositorAddress, lovelace, l2Owner?}` (`l2Owner` defaults server-side to
  the depositor's payment key hash); response
  `{unsignedTxCborHex, vaultAddress, datumHex, fee, ttlSlot}`; 4xx when the
  amount exceeds `max-deposit-lovelace` or the chain has no bridge config.
  No new submit route — signed bytes go out via the wallet's `submitTx` or
  the existing `POST /api/v1/tx/submit`.
- Implementation home: host-owned, as a dedicated sub-resource class
  (`EutxoBridgeResource` under `app/.../api/appchain/`) attached via a
  `@Path("eutxo/bridge")` locator on `ChainScopedResource` — NOT more methods
  in `AppChainResource` (which stays machine-agnostic), and NOT a plugin
  domain-api route: the ADR-011.3 domain SPI is read-oriented by design
  (`DomainApiContext` exposes only bundle config + chain-state queries — no
  L1 UTxO store, protocol params, or slot access, all of which tx building
  needs). Precedent for eutxo-aware host code: `EutxoLifecycleIndexers`.
- Wallet connection uses the Cardano Foundation's `cardano-connect-with-wallet`
  (https://github.com/cardano-foundation/cardano-connect-with-wallet) —
  specifically the framework-independent
  `@cardano-foundation/cardano-connect-with-wallet-core` package, since the
  console is Svelte, not React. It provides wallet discovery, connect state,
  and the CIP-30 enable/sign plumbing instead of hand-rolled
  `window.cardano` handling. The package is bundled at build time like every
  other console dependency (no CDN — the strict CSP forbids external
  origins), which is compatible because the library wraps the injected page
  API and makes no network calls of its own.
- CSP unchanged: CIP-30 is an injected page API (`window.cardano`), not a
  network origin; all fetches stay same-origin (Phase F `console-security`
  filter untouched).
- Default L2 owner = connected wallet's payment key hash; advanced field to
  override. Opens a later door to CIP-30 `signData`-signed L2 spends.

## 4. Milestones

- BR-M1 Attach mode in `appchain-eutxo-demo` (D1) + tests (workspace
  manifest mode/target pinning; attach-mode round-trip against a test
  cluster).
- BR-M2 `payment-chain-l1bridge` chain config + allow-list + deterministic
  vault identity in the light profile; fresh-instance path green
  (`showcase-contract`, `distribution-contract` updated) (D2).
- BR-M3 `showcase.sh bridge` group incl. `bridge info` (D4) + docs
  (`docs/BRIDGE_CHAIN.md`, DEMO_SHOWCASE.md section) (D3).
- BR-M4 Showcase-client eutxo demo: user deposit + L2 ops + operator settle,
  live-verified on a devnet light instance end to end (D5).
- BR-M5 `chain add payment-chain-l1bridge` migration tier (10→11), applied
  live to an existing instance (D2).
- BR-M6 Preprod guided run: `bridge info` + client deposit with a real
  funded preprod mnemonic against a preprod-connected instance; record
  gotchas (BF-endpoint coverage for address UTxOs is verified here).
- BR-M7 Console deposit screen (D6): node unsigned-tx endpoint + Svelte page
  + CIP-30 flow; manual browser verification per the 025.2 convention.

## 5. Open questions

- Withdrawal UX on public networks: the L2 claim is user-side but settlement
  is operator-side; how the demo narrates the waiting state in `bridge info`
  and the console.
- Whether attach mode should also serve the `eutxo` profile's ledger variant
  against the light cluster's `payments-chain` (nice-to-have, not scoped).
- Console screen scope for withdrawals (claim submission via wallet-derived
  L2 identity) — depends on the `signData` door in D6.

## 6. Out of scope

- ZK/validity variant in the light profile (stays in the `eutxo` profile).
- Plutus vault validators in distributions, multi-operator custody,
  federated settlement — ADR-UTXO-006/007 territory.
- Any change to the `eutxo` profile's self-managed demo flow.
