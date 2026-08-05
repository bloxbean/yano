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

## 7. Implementation log

### BR-M1 — attach mode (2026-08-05, branch feat/adr-utxo-008-br-m1)

Delivered as designed with one addition: attach setup requires an explicit
`--chain-id` (the `payments-eutxo` default would silently drive the wrong
chain on an external cluster). Shape: `setup --scenario bridge --target-base
<url> --operator-seed-file <64-hex file> --chain-id <id> --workspace <dir>`,
then operations directly (`round-trip`, `deposit`, …). The manifest pins
`targetBase`; `EutxoDemoCluster.nodeBases()` observes exactly the target;
`generateProject`/`start`/`stop` refuse on attached workspaces
(`ATTACHED_WORKSPACE_LIFECYCLE`); provider `stop` is a safe no-op so `reset`
works; conflicting `--target-base` against the manifest is rejected. The
bridge and external-deposit workflows are byte-identical — they only ever
used `cluster.apiBase()`/`status()`.

Review found and fixed two defects before merge: (1) the operator seed file
was read after the workspace marker was written, so a typo'd path left an
unrecoverable marker-bearing directory (now read/validated before anything is
created; regression-tested); (2) the imported operator seed array was never
zeroed, breaking the module's secret-hygiene convention (now zeroed in
`importWallet`).

Deferred to BR-M2/M3: live attached run (needs the bridge chain on a light
cluster) and the indexer allow-list note — `round-trip`/`verify` await index
readiness on the target, so the light profile must allow-list
`com.bloxbean.cardano.yano.appchain.eutxo.indexer`.

### BR-M2 — payment-chain-l1bridge in the light profile (2026-08-05, branch feat/adr-utxo-008-br-m2)

`chains[10]` shipped as designed: `eutxo-ledger` with profile
`yano-eutxo-v2-plutus-v3` (the bridge recipe's profile), `l1.stability-depth
2`, the full `machines.eutxo.bridge.*` block, both `observers.*` blocks, no
virtual genesis. Deterministic PUBLIC demo identities (seed =
sha256("yano-showcase-demo-actor:" + actor)): operator `bridge-operator` →
vault `addr_test1wpxg9ntn83pztkpw09lfkvv4uurd7pxztlx7yg0zqr0frdcuc9zzj`
(script hash `4c82cd733c4225d82e797e9b3195e706df04c25fcde221e200de91b7`),
payout `bridge-payout` →
`addr_test1vrpz48l78va55y3ewuv7p6narrtgsw2ajq3ns9xx945e0vsmpxjls`, all pinned
by golden test `ShowcaseBridgeChainConfigTest` re-deriving them from the
formula. Allow-list gained `…eutxo.bridge.cardano` (observers) and
`…eutxo.indexer` (index domain routes; the lifecycle indexer itself was
already on by default host-side).

Findings recorded during implementation:
- The chain's `withdrawal-address` is THE single L2 address whose outputs
  become claims, so attach workspaces must adopt it — attach setup gained
  `--payout-address` (public value, no secret) overriding the workspace's
  generated payout identity.
- The bridge chain admits only eutxo transactions and proposer-injected L1
  observations and holds no funds at genesis, so the generic
  `governance activate` proof-block loop cannot submit to it. It bootstraps
  with its membership epoch active; the activation flow now skips it with a
  note, and a LATER pending epoch (member changes) activates on the next
  bridge block — drive one with a deposit. Documented as a demo-cluster
  gotcha.
- JMT `chain add` on a legacy 9-chain instance now migrates directly to the
  full 11-chain packaged set (the bridge chain is config-only and additive);
  a 10-chain instance is told to run `chain add payment-chain-l1bridge`
  (BR-M5).

Live-verified on a fresh devnet instance built from the updated zip
(3 nodes, side ports): 11-chain boot, governance activation, and a FULL
ATTACHED round-trip through the light cluster's bridge chain —
`EUTXO_BRIDGE_DEMO_ROUND_TRIP_PASS`, deposits mirrored, claim CONFIRMED,
index READY_FULL, non-submitting node converged at the same height/root.
This doubles as BR-M1's deferred live verification. Both hermetic contracts
(script + distribution) pass with the 11-chain assertions.

### BR-M3 — showcase bridge command group + docs (2026-08-05, branch feat/adr-utxo-008-br-m3)

`./showcase.sh bridge info|run|fund|deposit|transfer|settle|verify|status`
(light profile). Workflow verbs delegate to the packaged demo CLI in attach
mode: first use creates `data/showcase/<instance>/bridge-workspace/` pinned
to `http://127.0.0.1:<http-base>` with the deterministic operator seed
(derived on the fly by the same sha256 formula, 0600 perms) and the packaged
yml's withdrawal address — the yml is the single source of the pinned
identities (`bridge_yml_value` reads them; nothing is duplicated in script
constants). `run` maps to `round-trip`; `--count N` batches rounds. The
automated verbs refuse non-devnet networks (the flow is faucet-funded);
`bridge info` works on any network, prints vault facts + live tip, the
datum warning, and the exact next step — devnet facade commands or the
Java-client deposit command for public networks. `bridge status` surfaces
the attach journal.

Docs: new `docs/BRIDGE_CHAIN.md` walkthrough (custody model, automated +
staged flows, preprod guidance, gotchas incl. the pending-epoch-needs-a-
bridge-block rule); DEMO_SHOWCASE.md gained the chain's TOC entry, console
row, §5 subsection, and eleven-chain counts. Contract additions pin the
info output, the attach-mode delegation argv (setup + deposit --count), and
the derived operator seed file.

Live-verified on the BR-M2 devnet instance: facade `bridge run` drove a full
attached round-trip (tip 5→10), `bridge info` renders reachable and
unreachable variants, staged verbs delegate correctly. Both hermetic
contracts pass. Noted: a transient index HTTP_409 appeared on one status
probe immediately after a round — readiness gating inside the workflow was
unaffected; watch during BR-M4.

### BR-M4 — showcase-client eutxo demo (2026-08-05, branch feat/adr-utxo-008-br-m4)

`ShowcaseClientDemos` gained the `eutxo` demo (`ShowcaseEutxoClientDemo`):
user role `deposit` (mnemonic via file or prompt, never argv; stage → vault
payment with the inline datum → poll the L2 mirror), `utxos`, `transfer`,
`claim`, `receipt`; operator role `settle` (vault unlock + payout + await
CONFIRMED). One backend for everything: the node's Blockfrost-compatible API
plus the app-chain routes. Defaults resolve the showcase's deterministic
vault/withdrawal identities with override flags for other bridge chains;
`--l2-owner-address` and `--payout-address` default to the depositor's own
address (one identity across layers, as designed in D5).

Implementation finding: a withdrawal claim's id embeds the CHAIN-ASSIGNED
settlement sequence, so a client cannot print it from local knowledge — the
demo resolves it by probing candidate sequences against
`withdrawal/snapshot` after finality (the client-chosen nonce makes the id
unique). Recorded as a candidate product improvement: a lookup route from
withdrawal outpoint → claim id would remove the probe.

Live-verified end to end on the BR-M2 devnet instance with a real mnemonic
account (base address): faucet-funded deposit of 8 ADA MIRRORED to the L2,
1 ADA L2 transfer (height 12), 2 ADA claim (height 14, probed id), operator
settle → CONFIRMED, and exactly 2,000,000 lovelace received back at the
user's own L1 address from the settlement transaction. Base-address owners
work throughout — nothing assumes enterprise addresses.

### BR-M5 — chain add payment-chain-l1bridge migration (2026-08-05, branch feat/adr-utxo-008-br-m5)

New identity migration `chain-add-bridge`: V10 (JMT present) → the full
11-chain packaged set. Config-only — no genesis generation; it verifies BOTH
retained authenticated-map geneses are untouched, re-records the packaged
config/plugin digests, and rewrites the cluster marker's chain list. The
showcase dispatcher now accepts `chain add payment-chain-l1bridge`
(idempotent: an already-migrated instance just refreshes configs and
restarts); legacy 9-chain instances are directed to the JMT add, which
adopts both chains. Hermetic contract coverage: prepare → downgrade markers
to V10 → migrate → assert 11 chains + idempotent rerun.

APPLIED LIVE to the user's preprod cluster
(`/Users/satya/Downloads/yano-cluster/yano-showcase-0.1.0-pre11`, instance
`preprod-anchor`): markers migrated 10→11, all three nodes restarted and
converged, history retained (workflow-chain tip 49 with its bootstrapped
anchor, JMT chain tip 6), `payment-chain-l1bridge` live at tip 0 with the
membership epoch active, `bridge info` printing the preprod guidance path.
Backups of the pre-migration markers + yml retained under the instance's
`pre-bridge-backup-*` directory. Operational finding during the restart:
two stray devnet-profile yano processes (a leftover playground batch from
earlier in the day, one sibling of which had already crashed) were squatting
on ports 7071/7072 and had to be terminated before the cluster could
rebind — the port-busy diagnostic made this obvious.

### BR-M6 — preprod guided run (2026-08-05, branch feat/adr-utxo-008-br-m6)

Executed against the user's migrated preprod cluster. `bridge info` on
preprod prints the guidance path (vault facts, datum warning, the exact
Java-client deposit command). The BF-endpoint question left open in the
milestone plan is ANSWERED live: the preprod-connected node serves
`/addresses/{addr}/utxos` from a real populated index (probed with the
preprod faucet address — 20 UTxOs returned) and
`/epochs/latest/parameters`, so client deposits need no external provider —
D5's "the client talks to the same node for L1 and L2" holds on public
networks. BRIDGE_CHAIN.md §5 now carries the live-verified copy-paste
sequence, the ~40s stability expectation at preprod block times, and the
note that the deterministic operator address must hold fee funds before
settlement.

The funded deposit itself intentionally awaits the user's own preprod
mnemonic (the client demands the user's key by design; the anchor seed is
scoped to anchoring and was not repurposed). The handed-off command is in
the doc and in `bridge info` output.

### BR-M7 — console deposit screen (2026-08-05, branch feat/adr-utxo-008-br-m7)

Host endpoint delivered as pinned in D6, plus one addition discovered during
design: CIP-30's `signTx` returns only a witness set, so a companion
`POST …/eutxo/bridge/deposit/assemble` merges the witnesses server-side
(deserialize, attach vkey witnesses, serialize) — no wasm CBOR library in
the browser. Routes: `GET …/eutxo/bridge/info` (chain bridge facts) and
`deposit/build` (unsigned, fee-balanced via QuickTx over the node's own
UtxoState + tracked protocol params, `additionalSignersCount(1)`,
TTL tip+7200, inline vault datum shared from `appchain-eutxo-contracts`).
Placement as decided: `EutxoBridgeResource` behind a more-specific locator
(`chains/{chainId}/eutxo/bridge`) so `ChainScopedResource` stays
machine-agnostic; settings resolved from MicroProfile Config
(`EutxoBridgeSettingsLoader`); registered in the API-key realm at READ
level; `NodeUtxoSupplier` widened to public for reuse. Depositor addresses
accepted as bech32 or CIP-30 hex bytes.

Console: the eutxo explorer's bridge tab gained a "Deposit with your wallet
(CIP-30)" card using `@cardano-foundation/cardano-connect-with-wallet-core`
(dynamic import only — the library touches browser globals at import time
and would break prerender), wallet discovery/connect/disconnect, ADA amount
validation (`lib/eutxo/deposit.ts` + vitest), then build → wallet signTx →
assemble → node submit, with the L2-mirror expectation surfaced from the
chain's stability depth.

Verified: container-free unit tests for build/validation/conflict/assemble
(fee-balanced tx decoded, CIP-30-style witness merge, txId stable), full
:app suite (215 tests) green, console gate green (svelte-check, vitest,
190KB/1MiB gzip budget), and a LIVE smoke on the devnet instance through
the full JAX-RS/CDI stack: info 200 with pinned facts, non-bridge chain
404, deposit/build 200 with a 418-byte fee-balanced unsigned tx for a
funded address. Browser CIP-30 click-through remains MANUAL per the
established console convention (same posture as the 025.2 console pages).
