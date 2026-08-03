# ADR-UTXO-001: Pluggable EUTxO Ledger, Cardano Bridge, and ZeroJ Validity Settlement

## Status

Proposed — version 15

Phase A and the automated portions of Phase B are implemented as optional
modules under `appchain/extensions/eutxo/`. Their schemas and bridge remain
alpha pending the external operational and independent-review gates recorded
by the implementation. The automatable Phase C components are implemented
under `appchain/extensions/eutxo-zk/`. The automatable D1-D6 integration,
lifecycle, packaging, and evidence controls are implemented, and a complete
disposable-funds Yano-devnet test now exercises L1 staging deposit, accepted
custody, stable L2 credit, a Jubjub-authorized L2 spend, exact finalized
transition proving, L1 validity-root advancement, payout from the same
custody funds, and stable L2 withdrawal reconciliation. Preview, Preprod,
maximum-batch, rollback/recovery, independent-reconstruction, and security
graduation gates remain open. All ZeroJ functionality remains experimental
with no production-funds claim.
Phase D development and disposable-funds testing may proceed against the
version-pinned development authorization profile while ZeroJ hardening happens
in parallel. A later ZeroJ release can preserve the Java API, but it cannot
preserve or silently upgrade the circuit-security identity.

Version 15:

- makes L2 identity bootstrap an explicit, fundless genesis registration:
  `eutxo-zeroj-preview` requires an L2 address and Jubjub public key, while
  `appchain validity key generate` creates an encrypted, owner-only local
  session-key envelope and never places its private scalar in the blueprint,
  lock, generated configuration, or console output;
- makes lifecycle bootstrap and doctor fail early when that address,
  32-byte lowercase public key, or positive key epoch is absent, and records
  only a public-key digest in the generated contract plan; and
- requires every withdrawal claim in a finalized validity transition to match
  one unique created output, exact lovelace, destination, bridge epoch, nonce,
  and inline datum. A decoded or externally supplied transition therefore
  cannot substitute a host-authored claim that differs from the canonical L2
  transaction.

Version 14:

- makes the preview recipe a real composition of the reusable Cardano bridge
  and ZeroJ settlement capabilities instead of treating them as conflicting;
  the stock JVM distribution packages the ledger, bridge observer, validity
  provider, prover, and lifecycle boundaries needed by that composition;
- removes the circular staging-datum requirement to name its own transaction
  ID: the Julc staging validator derives the accepted deposit outpoint from
  the consumed script input and carries it into the accepted-vault datum;
- defines `EutxoValidityTransition` v4 with the exact canonical withdrawal
  claims emitted by the accepted L2 transition, so proof preparation and
  operation journals derive the same withdrawal amount and transaction
  identities from finalized state;
- separates the proof-controller thread from the accepted-funds vault: a
  successful claim preserves the controller value, consumes and recreates one
  configured custody UTxO, and pays the proof-bound amount from those same
  accepted funds;
- records an automated Yano-devnet L1 → L2 → L1 result using 10 ADA of
  disposable custody funds, a 3 ADA withdrawal, and 7 ADA L2 change, followed
  by stable withdrawal reconciliation; and
- moves the development b16 circuit to
  `eutxo-jubjub-batch-dev-b16-v4` with digest
  `bd0835736116cb6338a82069e913f3815547008e4994508f110065c5dfc64747`.
  Preview and Preprod use the same network-neutral transaction/proof
  boundaries but remain `NOT_EXERCISED` until funded public-testnet runs are
  retained.

Version 13:

- replaces the development b16 circuit identity with
  `eutxo-jubjub-batch-dev-b16-v3` and eight settlement public inputs binding
  previous root, next root, transition digest, authorization commitment,
  batch size, settlement context, batch-data commitment, and withdrawal
  commitment;
- binds proof generation to the exact ordered finalized-transition witnesses
  emitted by the running EUTxO app chain and constrains each transition digest
  into the development authorization accumulator;
- adds a canonical fixed-width b16 transaction manifest and enforces the same
  manifest, profile, verification key, context, and withdrawal statement
  across the trusted-prover host boundary, ZeroJ public statement, and Julc
  root/vault validators;
- fixes the Julc-generated validators to use Plutus V3/PV11-supported list
  operations and verifies the actual b16 proof against the actual compiled
  settlement validator;
- records a live disposable Yano-devnet validity-settlement smoke test from
  configured L2 genesis through app-chain finalization, Groth16 proof, L1 root
  advancement, and a proof-authorized 3 ADA withdrawal; and
- keeps stable L1 deposit observation/credit, maximum-b16 live batching,
  rollback/recovery, independent reconstruction, Preview, and Preprod
  acceptance explicitly open rather than overstating the narrower smoke test.
  It also keeps in-circuit Cardano-CBOR-to-withdrawal derivation open: the
  integrated development profile host-matches that public input and therefore
  remains trusted-prover-only.

Version 12:

- completes the automatable Phase D lifecycle, final-distribution acceptance,
  network-evidence schema, and release-contract gates;
- records the explicit release decision
  `EXPERIMENTAL_TESTNET_ONLY` while `zeroj-jubjub-dev-v1` is selected;
- ships a reproducible contract binding the capability catalog and network
  evidence digests to the exact ledger, validity, authorization, batch,
  circuit, ZeroJ, Julc, proof-system, and curve identities;
- documents Cardano Client Lib, `cardano-cli`, backend-wallet, CIP-30, random
  session-key, and deterministic CIP-8-derived-key compatibility/security
  boundaries; and
- keeps live L1 round trips, rollback/recovery, independent reconstruction,
  adversarial circuit hardening, audit, and production ceremony visibly open
  instead of treating missing external resources as a pass.

Version 11:

- records that the ZeroJ team's reusable Jubjub hardening is not a blocker for
  Yano's lifecycle, packaging, devnet, or explicitly acknowledged public
  testnet engineering;
- keeps `zeroj-jubjub-dev-v1` fail-closed as a trusted-prover,
  disposable-test-funds profile and prohibits using Phase D evidence to claim
  malicious-prover security;
- requires the project blueprint, lock, lifecycle status, proof artifacts,
  contract plan, and release evidence to carry the authorization-security
  profile rather than inferring it from a library version;
- requires any hardened ZeroJ successor, even with an unchanged Java API, to
  select a new batch/circuit identity and new ceremony, proving-key,
  verification-key, validator, and release-manifest identities; and
- permits devnet by default, requires an explicit durable unsafe-development
  acknowledgement for Preview and Preprod, and retains an unconditional
  mainnet rejection in both project initialization and lifecycle commands.

Version 10:

- separates standard Cardano L1 transactions from Yano-defined L2 rollup
  transactions: L1 keeps ledger-CDDL Ed25519 VKey witnesses, while an L2
  envelope carries a Cardano-compatible transaction body and Jubjub
  authorization witnesses;
- selects a Cardano-authorized Jubjub session key as the first high-throughput
  L2 authorization profile instead of proving RFC 8032 Ed25519 for every L2
  transaction;
- permits Phase D functional development with ZeroJ `0.1.0-pre10` only through
  an explicitly experimental, trusted-prover development profile that rejects
  mainnet and makes no permissionless-proof-security claim;
- requires host-side curve, subgroup, non-identity, canonical-encoding, and
  scalar checks as defense in depth while recording that they cannot replace
  equivalent proof constraints against a malicious prover;
- requires a later hardened ZeroJ profile to receive new circuit, R1CS,
  ceremony, proving-key, verification-key, and validator identities even when
  its Java API and L2 wire format remain source-compatible; and
- makes deterministic CIP-8 `signData` key derivation an optional SDK
  convenience with a versioned KDF domain and secret-handling rules, not a
  requirement of the protocol.

Version 9:

- records the D1 implementation correction that validity roots and prover
  input must commit the exact finalized canonical Cardano transaction rather
  than a separately supplied amount tuple;
- adds a signed Cardano auxiliary-data replay domain binding chain, network,
  ledger and validity profiles, nonce, and expiry;
- defines a root-fixed finalized-transition query and deterministic,
  secret-free `EutxoFinalizedProofWitness` derivation; and
- keeps D1 open because ZeroJ `0.1.0-pre10` does not yet provide the complete
  reviewed RFC 8032 verification gadget required by the direct-signature
  circuit.

Version 8:

- adds Phase D to turn the existing EUTxO and ZeroJ components into one
  explicitly testnet-only preview product;
- keeps canonical Cardano transaction CBOR as the L2 transaction format and
  requires the circuit witness to be derived from the exact finalized
  runtime-accepted transaction bytes;
- defines immutable Cardano-shaped L2 protocol-parameter snapshots and Cardano
  Client Lib `UtxoSupplier`, `ProtocolParamsSupplier`, and
  `TransactionProcessor` adapters over Yano's existing generic message and
  query APIs;
- introduces measured, immutable batch profiles rather than a mutable batch
  size setting;
- requires the stock JVM distribution and `./yano.sh` to operate contract
  bootstrap, proving, permissionless relay, recovery, and status;
- permits automated devnet, Preview, and Preprod progression while requiring
  the preview tooling and catalog resolver to reject mainnet; and
- records that the existing one-to-four-payment Poseidon circuit, manually
  supplied proof witness, and conflicting federated-bridge/validity
  capabilities are Phase C building blocks, not completion of the preview
  flow; and
- separates the derived `state:eutxo-ledger` engine from its selectable v1
  key-payment and v2 Plutus profiles, correcting a development recipe that
  previously selected a ledger profile the ZeroJ provider refused at startup.

Version 7:

- completes the automatable Z6 hardening with canonical release manifests,
  fail-closed readiness evidence, ceremony-bundle inventory verification,
  deterministic circuit fuzzing, key-pinned prover failover, and
  protocol-parameter-pinned budget assessment;
- exposes `settlement:zeroj-validity` as an optional JVM-only
  development/testnet capability and recipe while keeping
  `rollup:zeroj-cardano` non-selectable;
- prevents a single-development-setup bundle from being represented as a
  production multi-party ceremony and requires a verified manifest before an
  existing proving-key directory can be loaded; and
- records independent audits, a real production MPC ceremony, external
  reconstruction, operational L1 retention and accountable production-funds
  approval as external gates that the build cannot self-certify.

Version 6:

- selects L1-enforced publication for the bounded Z5 profile: every normal
  validity-root advance must publish the exact canonical batch bytes as one
  inline datum at a deployment-pinned Cardano script in the same transaction;
- fixes the v2 canonical data shape at 101 bytes and requires both the ZeroJ
  circuit and Julc root validator to derive the same reduced BLAKE2b-256
  commitment before accepting the proof/root;
- adds portable publication, data-availability monitoring, independent
  reference reconstruction from genesis or a verified snapshot, and a
  secret-free post-proof recovery bundle;
- makes root and aggregate-withdrawal relay permissionless after a proof
  exists, while explicitly retaining the pre-proof witness/prover and
  transaction-censorship limitations; and
- keeps `rollup:zeroj-cardano` blocked until an external implementation
  reconstructs the vectors and an operational exercise demonstrates the
  declared L1-history retention and recovery horizon.

Version 5:

- narrows the first Z4 withdrawal profile to one aggregate lovelace
  withdrawal per proved bounded batch and one deployment-fixed destination;
- defines that public withdrawal commitment as the canonical aggregate of
  the batch's second outputs and requires the circuit to constrain it;
- integrates replay protection into the vault state thread through a
  sequence and last-consumed validity-root height;
- keeps general recipient, multi-asset, and withdrawal-tree support outside
  this profile until their circuits and inclusion rules receive separate
  review; and
- defines proof-finalized, L1-submitted, L1-stable, and rollback
  reconciliation states as node-local metadata that cannot authorize funds.

Version 4:

- records the Z0/Z1 implementation learning that the standalone arithmetic
  proof's five public inputs are insufficient for L1 replay protection;
- keeps that circuit as the bounded arithmetic primitive and introduces the
  settlement-only `yano-eutxo-zk-z3` profile;
- fixes the L1 ABI to eight ordered public inputs: previous root, next root,
  transition digest, owner commitment, batch size, settlement context,
  batch-data commitment, and withdrawal commitment;
- defines settlement context as a commitment to chain ID, bridge epoch,
  profile, circuit/proof-system identity, and verification-key identity; and
- requires ordinary validity-root advancement to be permissionless and
  proof-only, while profile/circuit/VK migration is an explicit
  authority-signed root-preserving handoff to a precommitted successor
  script.

Version 3:

- keeps the Phase A/B ledger and federated bridge under
  `appchain/extensions/eutxo/`;
- moves every ZeroJ, proving, validity-settlement, and rollup-graduation
  artifact behind the sibling optional product boundary
  `appchain/extensions/eutxo-zk/`;
- separates consensus-time validity commitments from node-local proof
  generation and from build-time Julc/Cardano validators;
- requires the base EUTxO family to expose only a ZeroJ-neutral optional
  validity-commitment boundary;
- requires ledger-only and federated-bridge distributions to remain free of
  ZeroJ, proving-key, circuit, and Julc dependencies; and
- records `feat/eutxo_state_machine_zkrollup` as the Phase C integration
  branch, with one reviewed and tested feature branch per Z milestone.

Version 2:

- groups every EUTxO product module under `appchain/extensions/eutxo/`;
- renames the execution module from `appchain-eutxo-scalus` to
  `appchain-eutxo-ledger`, keeping Scalus as a pinned implementation detail;
- removes Hydra integration from the product and roadmap;
- selects a direct ZeroJ validity-settlement path as the long-term direction;
- separates a validity bridge or validium from the stronger ZK-rollup label;
- introduces a dual Yano-MPF and circuit-friendly EUTxO commitment; and
- pins the design baseline to ZeroJ `0.1.0-pre10` and Julc
  `0.1.0-pre14`.

## Date

2026-07-23

Last updated: 2026-07-24

## Decision owners

Yano app-chain maintainers.

## Parent and related decisions

- [ADR-005](../005-yano-app-chain-framework.md) owns app-chain consensus,
  deterministic state-machine execution, authenticated state, finality,
  persistence, MPF proofs, L1 references, and anchoring.
- [ADR-008.3](../008.3-chain-governed-membership.md) owns governed app-chain
  membership.
- [ADR-008.4](../008.4-script-anchors-l1view.md) owns script anchors and the
  follower-verified L1-observer framework. It explicitly treats bridges as
  products built on those primitives rather than as part of the anchor.
- [ADR-010](../010-deterministic-effect-system.md) owns deterministic effect
  intent, node-local execution, result incorporation, and finality gates.
- [ADR-011](../011-plugin-architecture.md) through
  [ADR-011.4](../011.4-plugin-operations-and-observability.md) own the plugin
  bundle, lifecycle, catalog, domain API, health, and metrics boundaries.
- [ADR-016](../016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md)
  owns the authenticated consensus profile and versioned runtime limits.
- [ADR-022](../022-out-of-box-appchain-capabilities-and-extensible-product-catalog.md)
  owns the product capability catalog and third-party capability metadata.
- [ADR-DX-0001](../dx/0001-unified-appchain-onboarding-configuration-and-lifecycle.md)
  owns configuration generation, validation, project locks, lifecycle
  commands, and distribution-pinned onboarding.
- Root [ADR-028](../../028-unified-console-ui-module.md) owns the runtime
  console. This ADR may define EUTxO UI-hint metadata, but ADR-028 owns whether
  and how the console renders it.

## 0. Decision in plain words

Yano will support a Cardano-shaped EUTxO ledger as an optional app-chain
capability. Ledger-only clients may submit ordinary signed Cardano transaction
CBOR. The validity-rollup profile instead submits a Yano L2 envelope containing
a Cardano-compatible transaction body and separate Jubjub authorization
witnesses. In both cases the state machine resolves inputs from committed
app-chain state, validates deterministic ledger and Plutus rules through
Scalus, and atomically consumes and creates UTxOs under the existing MPF state
root.

The EUTxO ledger, federated bridge, and validity settlement are
separate capabilities:

```text
state:eutxo-ledger
bridge:cardano-federated     # optional, explicit trust statement
settlement:zeroj-validity    # experimental proof-verified root transitions
rollup:zeroj-cardano         # graduation label; requires DA and exit gates
```

The EUTxO ledger is useful without a bridge and MUST be implemented and tested
first with genesis-funded assets. A real-funds bridge is an optional product
layer with a separate threat model.

Scalus is the pinned ledger-validation implementation inside
`appchain-eutxo-ledger`; it is not a separately selectable product
capability.

The recommended first settlement product is a **federated Cardano bridge** for
known operators or consortiums. It locks real Cardano assets in L1 validators,
mirrors corresponding UTxOs on the app chain after stable observation, and
releases the L1 assets only after an irrevocable app-chain withdrawal claim.
Its UI, CLI, generated configuration, and documentation MUST state that the
configured bridge threshold can censor withdrawals and, unless L1 verifies a
complete validity proof, can authorize an invalid state root or settlement.

The selected long-term settlement direction is a direct ZeroJ validity proof.
The first circuit proves a restricted, bounded EUTxO payment batch. It does
not attempt to prove arbitrary Cardano ledger behavior or general Scalus/
Plutus execution. Cardano L1 advances the dedicated EUTxO validity root and
releases proof-gated withdrawals only after verifying that proof through a
Julc Plutus V3 validator.

This product is called `settlement:zeroj-validity` while transaction data and
exit safety depend on the operator set. It may be labelled
`rollup:zeroj-cardano` only after the data-availability, reconstruction, and
exit gates in this ADR are met. Optimistic settlement is not planned.

Most implementation belongs in manifested plugin bundles. No EUTxO models,
Scalus or ZeroJ dependencies, bridge rules, circuits, or custody logic will be
added to Yano core. A future generic L1 transaction co-signing SPI is
permitted only through a separate ADR after the bridge implementation
demonstrates a reusable need.

## 1. Context

### 1.1 Desired user outcome

The motivating flow is:

```text
Cardano L1
  user locks ADA/native assets in a reviewed validator
                         |
                         v
Yano app chain
  deterministic virtual EUTxO set
  rapid signed transactions
  Phase 1 ledger checks
  Phase 2 Plutus execution through Scalus
                         |
                         v
Cardano L1
  an irrevocable withdrawal claim releases locked assets
```

Assets do not physically move to the app chain. L1 assets remain locked in
one or more Cardano UTxOs. The app chain creates a mirrored claim with a
strict conservation relationship to the stable L1 vault inventory. A
withdrawal destroys or irrevocably locks that app-chain spending authority
before the corresponding L1 value is released.

The design has two independent questions:

1. Can Yano execute a deterministic EUTxO ledger and Plutus transactions?
2. What allows Cardano L1 to trust a deposit, root, withdrawal, or final
   settlement?

The first is primarily a state-machine and conformance problem. The second is
a bridge, custody, data-availability, recovery, and adversarial-protocol
problem. Conflating them would produce misleading security claims.

### 1.2 Cardano and Scalus basis

Cardano's EUTxO model makes transaction validity local to a transaction and
its resolved inputs. Outputs carry values and may carry data, while scripts
control whether an output can be consumed. Reference inputs, inline datums,
and reference scripts extend this model without changing its basic UTxO
lineage:

- [Cardano EUTxO overview](https://docs.cardano.org/about-cardano/learn/eutxo-explainer)
- [CIP-31 reference inputs](https://cips.cardano.org/cip/CIP-0031)
- [CIP-32 inline datums](https://cips.cardano.org/cip/CIP-0032)
- [CIP-33 reference scripts](https://cips.cardano.org/cip/CIP-0033)

Scalus provides a JVM ledger-rules framework and UPLC evaluator covering
Phase 1 transaction rules and Phase 2 Plutus execution:

- [Scalus ledger rules](https://scalus.org/docs/ledger/ledger-rules)
- [Scalus emulator](https://scalus.org/docs/testing/emulator)

Running a Plutus evaluator alone is insufficient. A value-bearing ledger must
also resolve every spending, collateral, and reference input and enforce
signatures, value conservation, fees, validity intervals, output bounds,
script witnesses, redeemers, datums, execution limits, and the exact UTxO
mutation semantics selected by the ledger profile.

### 1.3 Verified Yano foundations

The current repository already provides:

- `AppStateMachine` and `AppStateWriter`, including deterministic,
  block-ordered execution and atomic read-your-writes MPF updates;
- app blocks carrying a follower-verified stable L1 slot and block hash;
- threshold finality certificates and height-versioned membership;
- historical MPF inclusion and exclusion proofs bound to an exact committed
  height and state root;
- script anchors carrying chain ID, height, block hash, state root, members,
  and threshold;
- pure, follower-recomputed L1 observers with stability-gated injection;
- deterministic effect intent and node-local executor plugins;
- manifested JVM plugin bundles and build-time inclusion for native images;
- a Scalus bridge accepting raw transaction CBOR and pre-resolved regular,
  collateral, and reference inputs; and
- a release capability catalog and app-chain project generator.

These foundations make a plugin-first EUTxO ledger credible without changing
app-chain block consensus or the MPF persistence engine.

### 1.4 Verified gaps

The repository does not currently provide:

- an app-chain-owned EUTxO state machine;
- a transition API that returns Scalus's exact consumed/created UTxO delta;
- a consensus-pinned app-chain ledger profile;
- an exact script-lock observer carrying an output reference, full multi-asset
  value, datum, reference script, depositor intent, and refund conditions;
- a two-phase staging-to-vault deposit protocol;
- a bridge reserve and supply invariant;
- an irrevocable withdrawal/nullifier state model;
- an L1 vault and withdrawal validator;
- permissionless proof-based withdrawal;
- a reusable distributed L1 transaction co-signing service;
- a forced-exit, close/contest/fan-out, fraud-proof, or validity-proof
  protocol;
- L1 data availability for all app-chain transaction data; or
- an independent security audit covering custody of real assets.

The existing `address-deposit` observer claims only an address and total
lovelace for a paying transaction. It is not an asset-bridge observer.

The existing `cardano.payment` effect spends a configured node-local account,
not a bridge vault, and retains an explicitly documented at-least-once crash
window. It MUST NOT be presented as a bridge settlement executor.

The current script anchor constrains monotonic height, member threshold, and
locked lovelace. It does not validate every off-chain EUTxO transition. A
membership proof against an anchored root proves inclusion relative to that
root; it does not prove that a malicious signing threshold could not anchor a
fabricated root.

## 2. Decision drivers

The selected architecture should:

1. reuse Yano's deterministic app-chain and plugin boundaries;
2. keep Scalus, bridge, ZeroJ, and Julc dependencies out of core and optional
   for users who do not need them;
3. allow the EUTxO ledger to run with no real funds or bridge;
4. reuse Cardano transaction-body CBOR and ordinary wallet witnesses where
   practical without confusing L1 Ed25519 witnesses with L2 authorization;
5. make every consensus-affecting execution parameter explicit and committed;
6. support deterministic replay, catch-up, snapshots, and MPF proofs;
7. make L1 deposit facts independently recomputable by every member;
8. prevent double credit, double spend, double withdrawal, and refund-after-
   credit paths;
9. distinguish committee finality from L1-enforced transition validity;
10. avoid claiming complete Cardano isomorphism for a deliberately restricted
    first profile;
11. preserve a path to native-image distributions without making runtime JAR
    loading a native requirement;
12. remain useful for custom plugins, transaction builders, custody systems,
    and future settlement protocols; and
13. follow KISS by proving the ledger without funds before adding custody.

## 3. Goals and non-goals

### 3.1 Goals

- A reusable, deterministic `state:eutxo-ledger` plugin.
- A bounded `Yano-EUTxO-Profile-v1`.
- Scalus Phase 1 and Phase 2 validation against app-chain-owned UTxO state.
- Atomic input consumption and output creation under the existing Yano MPF
  root and, only when ZK is selected, the plugin-owned validity root.
- Signed Cardano-format transaction submission through the existing app-chain
  gateway.
- Root-fixed UTxO, transaction, deposit, withdrawal, and proof queries.
- A testnet federated bridge with exact stable L1 observation.
- Explicit custody, signer, reserve, replay, upgrade, and recovery policies.
- A direct, staged ZeroJ validity-settlement path for a bounded EUTxO profile.
- Explicit user choice between ledger-only, federated-bridge, and
  validity-settlement deployments.

### 3.2 Non-goals for the version-2 delivery

- A permissionless public mempool or public validator set.
- Full compatibility with every Conway certificate and governance operation.
- Cardano staking, rewards, pools, voting, or governance inside the app chain.
- Arbitrary creation of Cardano-native assets off chain.
- Trustless settlement based only on a threshold-signed root.
- Hydra integration.
- An optimistic fraud-proof game.
- An initial circuit proving complete Cardano ledger behavior or arbitrary
  Plutus batch execution.
- Production mainnet custody before independent contract, bridge, and
  circuit, cryptographic, and operational audits.

## 4. Terminology and security labels

This ADR uses the following terms precisely:

- **EUTxO state machine** — deterministic app-chain transition function
  maintaining UTxOs and validating transaction witnesses and scripts.
- **Cardano-shaped** — uses Cardano transaction, output, and script formats,
  but may enforce a documented subset or different parameters.
- **Cardano-isomorphic** — applies the same relevant ledger rules and output
  semantics as L1, allowing supported state to transfer safely back to L1.
- **App final** — a Yano threshold finality certificate has committed the
  block.
- **Anchored** — Cardano contains a stable anchor committing the app-chain
  root.
- **Federated bridge** — a configured operator or signing threshold is trusted
  for custody, availability, or root validity.
- **Proof-constrained withdrawal** — L1 verifies inclusion of a withdrawal
  claim under an accepted root and prevents claim replay.
- **Optimistic rollup** — L1 accepts assertions subject to data availability,
  challenge periods, and fraud proofs.
- **Validity rollup** — L1 accepts a new root only after verifying a
  cryptographic proof of the complete permitted transition.
- **Validium** — L1 verifies transition validity, while transaction data
  required to reconstruct state remains off L1 or under an external
  availability assumption.

Neither a finality certificate nor an MPF proof changes a federated root into
a validity-proven root.

## 5. Options considered

### 5.1 Option A — Narrow payment/netting UTxO ledger

Implement only key-controlled transfers of ADA and explicitly admitted native
assets, with deposits, withdrawals, and periodic net settlement. Defer general
Plutus scripts.

Advantages:

- smallest value-bearing attack surface;
- straightforward conservation and reserve invariants;
- bounded transaction and output shapes;
- useful for micropayments, clearing, exchanges, games, marketplaces, and
  merchant settlement;
- quickest path to operational learning.

Disadvantages:

- does not satisfy the full smart-contract goal;
- applications must adopt a narrower command/transaction profile;
- later Plutus support still needs a ledger-profile upgrade.

Assessment: **high feasibility** and the preferred first real-funds pilot.

### 5.2 Option B — Yano EUTxO ledger with a federated Cardano bridge

Implement Cardano-shaped transaction execution as a Yano state-machine plugin.
Lock assets in Cardano validators and use an explicit settlement committee,
proof-constrained claims, or an external custody system to release them.

Advantages:

- best fit with Yano's known-operator and consortium trust model;
- reuses app-chain ordering, finality, MPF, observers, effects, plugins, UI,
  and developer tools;
- general wallet witnesses can authorize transactions independently of the
  app-message relayer;
- supports application-specific restrictions and composition;
- can operate without L1 settlement during development;
- provides Java/JVM-native product differentiation.

Disadvantages:

- a malicious bridge/root threshold can censor or steal;
- no permissionless forced exit by default;
- app-chain transaction data is not made available by L1;
- exact compatibility requires continuous differential testing;
- dynamic membership and settlement-key rotation are high-risk.

Assessment: **high feasibility for execution, medium feasibility for audited
two-way settlement**. This is the recommended Yano-native option.

### 5.3 Option C — Threshold root plus MPF proof-based withdrawals

Keep a threshold-authorized anchor/root on L1. An irrevocable withdrawal claim
is released by presenting its value and an MPF proof against an accepted
state root. The L1 validator records a nullifier so the claim cannot be paid
twice.

Advantages:

- a compromised node-local executor cannot invent a claim outside the root;
- relaying may be permissionless;
- reuses Yano's Aiken-compatible MPF commitment and proof format;
- separates transaction submission from custody authorization.

Disadvantages:

- the root threshold can still fabricate a root containing a fraudulent claim;
- old roots remain dangerous if claims can be canceled or rewritten;
- nullifier state, root selection, proof budgets, and vault fragmentation need
  careful on-chain design;
- does not solve data availability or censorship.

Assessment: **medium feasibility** and a recommended enhancement to Option B,
not an independent trustless settlement claim.

### 5.4 Option D — Optimistic UTxO rollup

Publish enough batch data on L1 for independent replay. Accept a proposed root
subject to a challenge period and an L1-enforceable fraud-proof protocol.

Advantages:

- can reduce validity trust to the existence of an honest challenger;
- retains an off-chain execution engine;
- may permit broader participation than a fixed custody committee.

Disadvantages:

- requires L1 data availability or an equivalent enforceable availability
  scheme;
- requires a one-step Cardano/Scalus transition verifier or an interactive
  execution game;
- requires bonds, timeouts, challenge/censorship analysis, forced inclusion,
  and forced exits;
- withdrawal latency includes the challenge window;
- far beyond the existing script-anchor validator.

For background on the required data and fraud-proof relationship, see the
[optimistic rollup overview](https://ethereum.org/developers/docs/scaling/optimistic-rollups/).

Assessment: **low near-term feasibility; research only**.

### 5.5 Option E — Direct ZeroJ validity settlement and future ZK rollup

Use ZeroJ `0.1.0-pre10` to prove that a bounded batch transforms an accepted
pre-state EUTxO validity root into a new root under an exact, restricted
ledger profile. Use Julc `0.1.0-pre14` to compile the Cardano Plutus V3
validator that verifies the proof and advances the L1 root.

ZeroJ supplies the Java circuit/prover/verifier toolchain, while Cardano's
[CIP-381](https://cips.cardano.org/cip/CIP-0381) BLS12-381 primitives make
succinct on-chain verification possible. ZeroJ's
[project status](https://github.com/bloxbean/zeroj) remains experimental and
audit-gated; this ADR does not promote it to production readiness.

The first circuit covers bounded key-authorized ADA and admitted native-asset
transfers. Plutus features graduate one bounded rule or script family at a
time. Scalus remains the runtime ledger engine and differential oracle, but
ordinary Scalus execution is not automatically converted into a circuit.

Advantages:

- removes committee trust for the transitions actually covered by the pinned
  circuit when the proof system and verifier are sound;
- can provide fast proof-based finality;
- off-chain execution remains scalable;
- reuses ZeroJ's Java circuit, proving, verification, and Cardano integration
  surfaces; and
- keeps ZK optional for ledger-only and federated deployments.

Disadvantages:

- requires a new circuit for the selected EUTxO profile;
- proving arbitrary Scalus/Plutus execution would require a circuit model or
  verifiable VM that does not exist today;
- requires proof generation, optional future aggregation, verifier contracts,
  upgrade rules, and extensive formal/security work;
- Cardano verification budgets constrain the proof system;
- data availability remains necessary for state reconstruction and exits;
- Yano's current ZK state machines verify application proofs inside the app
  chain, which is the opposite direction from L1 verifying the complete
  app-chain batch; and
- ZeroJ and its on-chain verifiers remain audit-gated for value-bearing use.

For the distinction between validity and data availability, see the
[ZK-rollup overview](https://ethereum.org/developers/docs/scaling/zk-rollups/).

Assessment: **medium feasibility for a restricted payment batch and low
near-term feasibility for general Plutus**. This is the selected long-term
direction, delivered through explicit feasibility and security gates rather
than promised as part of the initial bridge.

## 6. Decision

### 6.1 Selected product architecture

Adopt Option B as the Yano-native architecture, beginning with Option A's
restricted real-funds profile. Add Option C only after the basic federated
bridge is correct and testable.

Adopt Option E as the explicit long-term settlement direction. Begin with a
bounded payment transition circuit, prove it through ZeroJ, and verify it on
Cardano through Julc. Broader ledger and Plutus coverage graduates only after
differential conformance, proving-cost, L1-budget, and security gates pass.

Do not implement Option D. An optimistic protocol would require a separate
ADR, data-publication design, fraud-proof game, and challenge/exit model.

The federated bridge remains a useful independently deployable product. Users
are not required to install or operate the ZeroJ proving path.

### 6.2 Mandatory separation of capabilities

The derived `state:eutxo-ledger` engine MUST NOT imply a profile, bridge, or
ZK. A product selects exactly one immutable profile:

```text
state:eutxo-ledger
  selectable: false; derived from one profile capability
  provides: Cardano-shaped UTxO state and proofs
  implementation: pinned Scalus adapter
  custody: none
  zeroj dependency: none

profile:eutxo-plutus-v3
  requires: state:eutxo-ledger
  provides: immutable EUTxO v2 bounded Plutus profile

profile:eutxo-key-payments
  requires: state:eutxo-ledger
  provides: immutable EUTxO v1 key-payment profile

bridge:cardano-federated
  requires: state:eutxo-ledger, l1:slot-feed
  provides: staged deposits, vault accounting, withdrawals
  custody: configured settlement/root threshold
  zeroj dependency: none
  conflicts: funding:eutxo-genesis

settlement:zeroj-validity
  requires now: profile:eutxo-key-payments
  provides: experimental proof-verified validity-root components
  implementation: ZeroJ 0.1.0-pre10, Julc 0.1.0-pre14
  availability: devnet, Preview, and Preprod development/test only

rollup:zeroj-cardano
  scope: product maturity label
  selectable: false until graduation gates pass
  requires: settlement:zeroj-validity, L1 data availability,
            independent reconstruction, and approved exit behavior
```

The supported user selections are:

```text
ledger only
  profile:eutxo-plutus-v3 -> state:eutxo-ledger

federated bridge, no ZK
  bridge:cardano-federated
  -> profile:eutxo-plutus-v3 -> state:eutxo-ledger

development validity components, virtual funds
  settlement:zeroj-validity
  -> profile:eutxo-key-payments -> state:eutxo-ledger

integrated experimental testnet flow
  eutxo-zeroj-preview recipe
  -> bridge:cardano-federated + settlement:zeroj-validity
  -> profile:eutxo-key-payments + state:eutxo-ledger + l1:slot-feed
```

`rollup:zeroj-cardano` is initially a non-selectable graduation label rather
than another runtime switch.

The dependency graph MUST enforce:

- ledger and federated-bridge artifacts do not depend on ZeroJ;
- selecting the ledger does not download or initialize the prover;
- selecting the federated bridge does not enable proof generation;
- ZeroJ and Julc proving/on-chain artifacts arrive only with
  `settlement:zeroj-validity`;
- ledger-only and federated deployments do not compute or persist the
  circuit-friendly validity tree;
- JVM distributions package only selected plugin bundles; and
- native distributions include selected bundles at image build time.

Validity settlement is selected when the project/distribution is resolved.
Enabling it later is an explicit consensus-state and L1-contract migration,
not a hot configuration toggle.

Scalus, ZeroJ, and Julc versions are pinned implementation metadata in the
project lock and consensus or settlement profile. They are not independently
selectable user capabilities.

Every capability descriptor and generated project MUST carry a bounded trust
and availability statement. These statements are normative, not marketing
copy.

### 6.3 Plugin-first boundary

The base EUTxO ledger and federated bridge are one bounded product family
under:

```text
appchain/extensions/eutxo/
```

The optional validity-settlement and rollup-graduation implementation is a
sibling product layer under:

```text
appchain/extensions/eutxo-zk/
```

This physical split is normative. It makes dependency-graph and distribution
checks able to prove that selecting the ledger or federated bridge does not
bring in ZeroJ, circuits, proving infrastructure, proving keys, or Julc
validators.

The base family is assembled from:

```text
appchain/extensions/eutxo/appchain-eutxo-contracts
  Java-only public models, canonical codecs, state keys, receipts,
  deposit/withdrawal contracts, and test vectors

appchain/extensions/eutxo/appchain-eutxo-ledger
  UtxoTransitionEngine
  Yano-EUTxO-Profile-v1
  AppStateMachineProvider for state:eutxo-ledger
  pinned Scalus adapter and conformance suite

appchain/extensions/eutxo/appchain-eutxo-client
  Java client, transaction builder helpers, proof verification,
  deposit/withdrawal builders, and status models

appchain/extensions/eutxo/appchain-eutxo-testkit
  deterministic transition vectors, multi-node fixtures,
  bridge fixtures, and packaged-plugin conformance

appchain/extensions/eutxo/appchain-eutxo-bridge-cardano
  exact script-UTxO L1ObserverProvider
  reserve accounting
  optional settlement AppEffectExecutorFactory
  reconciliation, health, and metrics

appchain/extensions/eutxo/appchain-eutxo-bridge-onchain
  staging deposit, vault, root/claim, nullifier, recovery,
  and migration validators
```

The Phase C layer is assembled from:

```text
appchain/extensions/eutxo-zk/appchain-eutxo-zk-contracts
  ZeroJ-neutral validity profiles, public statements, canonical batch and
  witness descriptors, proof envelopes, circuit/VK identities, and codecs

appchain/extensions/eutxo-zk/appchain-eutxo-zk-zeroj
  circuit-friendly validity commitment, bounded ZeroJ circuits,
  witness construction, proof generation, and off-chain verification

appchain/extensions/eutxo-zk/appchain-eutxo-zk-prover
  finalized-block ingestion, durable proving jobs, artifact storage,
  retry/recovery, redundant prover coordination, health, and metrics

appchain/extensions/eutxo-zk/appchain-eutxo-zk-client
  typed validity status, statement, witness, proof, data-availability,
  monitoring, recovery planning, and relay APIs

appchain/extensions/eutxo-zk/appchain-eutxo-zk-onchain
  Julc proof-verifier/validity-root validator, verification-key binding,
  proof-gated withdrawal, migration, and Cardano budget tests

appchain/extensions/eutxo-zk/appchain-eutxo-zk-testkit
  independent vectors, malformed-witness and proof mutation fixtures,
  reconstruction fixtures, and packaged optionality/conformance tests
```

`appchain-eutxo-contracts` defines a small family-private
`ValidityCommitmentEngine` and `ValidityCommitmentEngineProvider` boundary
using only immutable byte-oriented EUTxO records. It contains no ZeroJ,
circuit, proof, or Julc type. The ledger does not discover or invoke a
validity engine when the capability is absent. When validity settlement is
selected, startup requires exactly one engine matching the pinned profile and
fails closed on absence, ambiguity, or an identity mismatch. The selected
engine updates the circuit-friendly commitment atomically with the ordinary
Yano MPF mutation.

`appchain-eutxo-zk-zeroj` supplies that family-private provider. Provider
discovery uses the manifested plugin classloader and does not add a new Yano
core SPI. Proof generation never runs in deterministic state-machine apply:
consensus execution produces the committed validity transition and canonical
witness descriptor, while `appchain-eutxo-zk-prover` consumes finalized
transitions through a durable node-local pipeline. A prover failure cannot
change, skip, or roll back consensus state.

`appchain-eutxo-ledger` names the product behavior. Scalus remains an internal
implementation dependency so that an implementation change does not rename
the user-facing capability or module.

The bundle IDs should use stable reverse-DNS identities such as:

```text
com.bloxbean.cardano.yano.appchain.eutxo
com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano
com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj
com.bloxbean.cardano.yano.appchain.eutxo.zk.prover
```

The modules may evolve independently, but the release index and plugin
manifests MUST pin their compatible versions and artifact digests. The
contracts, client, testkit, and on-chain artifacts live with their owning
product layer even when they are not runtime plugin contributions.

The dependency direction is one-way:

```text
base EUTxO contracts and ledger
          ^
EUTxO-ZK contracts and ZeroJ commitment/circuit
          ^
node-local prover and client
```

No module under `appchain/extensions/eutxo/` may depend on a module under
`appchain/extensions/eutxo-zk/`. The only inversion point is the
ZeroJ-neutral validity-commitment provider contract defined by the base
family.

### 6.4 Core-change policy

UTXO-M0 through UTXO-M5 MUST require no Yano core change. They use:

- `AppStateMachineProvider`;
- `AppStateMachineContext`;
- `AppStateWriter`;
- `L1ObserverProvider`;
- generic submit/query/proof APIs; and
- the manifested plugin/catalog infrastructure.

UTXO-M4 SHOULD first use one of:

1. an external reviewed settlement/custody signer; or
2. permissionless user-submitted proof-constrained withdrawals.

The runtime-private script-anchor co-signing implementation MUST NOT be
exposed directly or copied into the bridge.

If in-process distributed transaction signing remains necessary after the
first bridge implementation, a separate ADR may propose a generic, bounded L1
settlement/co-signing SPI. The SPI must be reusable by bridge, oracle,
treasury, and publication products and must not expose runtime-private stores
or signing keys.

The ZeroJ validity path uses a separate on-chain validity-root validator and
plugin-owned proving pipeline. It does not require the generic Yano anchor to
understand ZeroJ. If a missing generic runtime hook is discovered, it must be
proposed independently rather than placing EUTxO, ZeroJ, or custody types in
core.

No Cardano transaction model, Scalus or ZeroJ type, bridge ABI, circuit type,
or custody secret is added to `core-api`.

### 6.5 JVM and native packaging

For JVM distributions, each selected product layer is a self-contained
manifested plugin bundle installable in the configured plugin directory.
Ledger-only and federated deployments do not carry ZeroJ.

For GraalVM native distributions, arbitrary post-build JAR loading is not
possible. Selected bundles are included at build time so the native image has
the required ServiceLoader entries, resources, reflection metadata, Scala/
Scalus dependencies, ZeroJ cryptography, Julc artifacts, and native
configuration.

A capability is not labelled native-compatible until packaged-native
conformance tests pass. JVM support MUST NOT imply native support.

### 6.6 Pinned implementation baseline

The version-2 design baseline is:

| Component | Pinned version | Role |
|---|---:|---|
| ZeroJ | `0.1.0-pre10` | Circuit definition, proof generation and verification |
| Julc | `0.1.0-pre14` | Plutus V3 proof-verifier and bridge-validator compilation |
| Scalus | release-pinned by Yano | Runtime Phase 1/Phase 2 ledger validation |

At this ADR revision, Maven Central metadata identifies ZeroJ
`0.1.0-pre10` and Julc `0.1.0-pre14` as their latest releases:

- [ZeroJ Maven metadata](https://repo1.maven.org/maven2/com/bloxbean/cardano/zeroj-api/maven-metadata.xml)
- [Julc Maven metadata](https://repo1.maven.org/maven2/com/bloxbean/cardano/julc-core/maven-metadata.xml)

The published `zeroj-onchain-julc:0.1.0-pre10`
[artifact](https://central.sonatype.com/artifact/com.bloxbean.cardano/zeroj-onchain-julc/0.1.0-pre10)
pins `julc-stdlib` and `julc-ledger-api` to `0.1.0-pre14`, confirming this
baseline is internally compatible.

“Latest” is discovery-time information, not a runtime version range. Project
locks, release indexes, proving profiles, verification keys, compiled
validators, and artifact digests pin exact versions. Upgrades are explicit
profile/contract migrations with replay, proof, and on-chain budget gates.

## 7. `Yano-EUTxO-Profile-v1`

### 7.1 Why a profile is required

“Run Plutus” is not a complete consensus definition. All nodes must agree on:

- transaction era and canonical decoding;
- network identity;
- protocol parameters;
- cost models;
- slot/time conversion;
- ledger-rule selection;
- permitted transaction purposes;
- fees and collateral behavior;
- script and datum resolution;
- native-asset policy;
- output and block limits; and
- activation heights for changes.

The complete normalized profile and its digest are consensus-shared. A node
with a different profile MUST fail startup or reject catch-up before
participating.

### 7.2 Initial permitted features

The target profile may permit:

- canonical Conway transaction CBOR;
- key-controlled spending;
- Plutus V1, V2, and V3 spending scripts supported by the pinned Scalus
  version;
- ADA and transfer of explicitly admitted, already-existing L1 native assets;
- datum hashes and inline datums;
- reference inputs whose UTxOs exist in app-chain state;
- reference scripts whose UTxOs and script bytes exist in app-chain state;
- ordinary required signers;
- bounded transaction metadata;
- validity intervals interpreted using the app block's verified stable L1
  slot and pinned slot configuration; and
- zero or explicitly routed app-chain fees.

Each permitted feature graduates only after Scalus, Yano replay, and devnet
golden tests agree.

### 7.3 Initially prohibited features

Version 1 will reject:

- stake registration, delegation, deregistration, and reward withdrawals;
- pool certificates;
- governance certificates, votes, proposals, and treasury operations;
- L1-native minting or burning;
- scripts or reference UTxOs fetched from live node-local L1 state during
  deterministic application;
- node-local or wall-clock protocol parameter selection;
- transactions whose outputs cannot later be created on Cardano under the
  settlement profile;
- unsupported languages, builtins, cost models, cryptographic primitives, or
  native-image paths;
- unbounded datum, redeemer, script, metadata, asset, or output shapes; and
- `isValid=false` collateral transitions until the transition engine returns
  and tests the exact Cardano mutation semantics.

L2-only assets may be introduced later under a separate namespace and MUST
not be represented as withdrawable Cardano-native assets.

### 7.4 Fee and value policy

The profile MUST define where any difference between consumed and produced
value goes. It must not silently disappear from the bridge reserve invariant.

The simplest v1 profile uses zero L2 fees. A later fee-bearing profile may
create an explicit treasury UTxO or define another provable recipient. If an
L1-settleable profile is offered, every resulting output MUST satisfy the
relevant L1 minimum-ADA, size, and value constraints.

### 7.5 Slot and time policy

Deterministic transaction validity MUST use `AppBlock.l1Slot`, not:

- wall-clock time;
- the local node's live L1 tip;
- a mutable global Scalus slot supplier; or
- the app block's proposer timestamp.

### 7.6 L2 protocol-parameter supplier

Each EUTxO chain exposes one immutable Cardano-shaped L2 protocol-parameter
snapshot. It is part of the authenticated consensus profile and project lock,
not a node-local convenience setting. The initial snapshot uses zero L2 fees,
zero unsupported deposits, the selected EUTxO and circuit-profile bounds, the
exact admitted cost model, and an explicit profile version and digest.

The L2 snapshot MUST NOT be used to build Cardano L1 deposit, root-publication,
or withdrawal transactions. Those transactions use the selected Cardano
network's live or explicitly pinned L1 parameters, including real fees and
minimum-UTxO requirements. API and Java type names MUST distinguish L1 and L2
parameter sources.

Changing an L2 snapshot requires a new immutable profile or an independently
specified governed activation boundary. Two members using different L2
parameter digests cannot participate in the same chain.

### 7.7 Cardano Client Lib compatibility

Cardano Client Lib remains the primary Java construction surface for the
supported Cardano-compatible L2 body. L1 deposit, registration, exit, and
batch-settlement transactions remain ordinary Cardano transactions with
ledger-defined Ed25519 `vkeywitness` entries. The L2 validity profile is a
different protocol transaction and MUST NOT place Jubjub keys or signatures
in Cardano's VKey witness list.

The Yano adapter supplies:

- `UtxoSupplier` from a root-fixed EUTxO query snapshot;
- `ProtocolParamsSupplier` from the selected L2 profile;
- an L2 body builder over Cardano Client Lib transaction-body types;
- Jubjub session-key generation, registration, signing, and an optional
  wallet-derived key flow;
- deterministic L2-envelope encoding and submission through the existing
  generic appchain message endpoint on topic `eutxo.transactions`;
- profile-correct transaction evaluation; and
- typed receipt, finality, proof, and settlement queries.

The L2 transaction ID commits the canonical envelope body, including chain,
network, profile, nonce, expiry, and the Cardano-compatible transaction-body
CBOR. The contained Cardano transaction-body hash remains separately available
for Cardano Client Lib correlation. Yano's app-message ID is attempt metadata
and MUST NOT be reported as either identifier. HTTP `202` means pool
admission, not state-machine acceptance or finality.

The L2 envelope has an authorization section containing one Jubjub signature
per distinct registered session key needed by its inputs. The signature is
sent to the app-chain sequencer and prover, but it is a private circuit witness:
it is neither a Groth16 public input nor serialized in the Cardano L1
settlement transaction. The L1 batch transaction contains only the operator
or relay's normal Cardano witnesses, the proof, public inputs, new root, and
the selected data-availability payload.

The first registration flow binds a Cardano payment credential to a Jubjub
public key in a Cardano-authorized deposit or registration transaction. Key
rotation is authorized by the active Jubjub key or by an explicit L1 recovery
path. A recipient credential MUST have an active registered L2 key before it
can receive spendable outputs under this profile.

An SDK MAY deterministically derive a recoverable Jubjub key from one CIP-8
`signData` result. Such a flow MUST use an exact versioned prompt and KDF
domain binding the wallet account, chain, network, profile, and key epoch. The
derivation signature MUST never be transmitted, logged, persisted in a
project, or treated like an ordinarily publishable signature: possession of
it reveals the derived L2 private key. Random encrypted session keys remain a
supported alternative.

The slot-to-time configuration, era boundary inputs, and their digest are part
of the ledger profile.

### 7.6 Optional ZeroJ validity profile

`settlement:zeroj-validity` adds a stricter
`ZeroJ-EUTxO-Validity-Profile-v1`. It defines the exact transaction subset
proved by the pinned circuit:

- bounded batch size;
- bounded input and output counts;
- key-authorized spending rules;
- permitted ADA and native-asset forms;
- signature, value-conservation, validity-interval, and fee rules;
- circuit-friendly validity-root commitment;
- public-input encoding;
- circuit, proving-key, verification-key, and validator identities; and
- activation and migration rules.

Ledger-only and federated deployments do not load this profile and may use
supported EUTxO features that are not yet circuit-proven.

When `settlement:zeroj-validity` is selected, a transaction that can change
withdrawable state MUST be covered by the active validity profile. Unsupported
transactions are rejected before state transition; they are not accepted into
a state that the prover cannot advance.

General Plutus execution is not part of the first validity profile. Each later
script family or ledger feature requires:

- an explicit circuit/profile version;
- Scalus-to-circuit differential vectors;
- proof-generation and malformed-witness tests;
- L1 verification-budget evidence; and
- an activation/migration decision.

#### 7.6.1 Jubjub development and hardened security profiles

The first high-throughput authorization scheme is Poseidon EdDSA over Jubjub,
the curve native to the BLS12-381 scalar field used by this proof system. It is
not Cardano Ed25519 and it is not serialized as a Cardano VKey witness.

ZeroJ `0.1.0-pre10` supplies off-circuit signing, point arithmetic, and an
in-circuit verification equation. Its current verification contract assumes
that public-key and signature points were already validated for curve and
subgroup membership. That assumption is acceptable only for the following
explicit development profile:

```text
authorization-profile: zeroj-jubjub-dev-v1
status: experimental
trusted-prover-required: true
mainnet: prohibited
funds: disposable-test-funds-only
```

The development profile:

- performs canonical decoding, on-curve, subgroup, non-identity, scalar-range,
  signature, domain, nonce, and expiry checks in the sequencer and prover host;
- restricts proof submission to the deployment-pinned development operator or
  committee until proof-level checks are complete;
- permits disposable devnet by default and Preview/Preprod only after an
  explicit unsafe-development acknowledgement;
- reports its incomplete malicious-prover protection in project locks,
  status, doctor output, release manifests, and evidence bundles; and
- cannot be represented as permissionless, hardened, production, or suitable
  for valuable funds.

Host validation is defense in depth, not a proof-soundness substitute. A
malicious prover that can bypass host validation is outside the security claim
of this profile.

The successor profile, provisionally
`zeroj-jubjub-hardened-v1`, requires the circuit itself to enforce canonical
point encoding, curve membership, prime-subgroup membership, non-identity,
canonical scalar range, domain binding, and the complete signature equation.
The hardened profile requires adversarial vectors and independent review
before it can remove the trusted-prover restriction.

Even if the ZeroJ Java API and Yano L2 envelope remain unchanged, adding those
constraints changes the circuit. The development and hardened profiles
therefore MUST have different circuit, R1CS, ceremony, proving-key,
verification-key, validator, and release-manifest identities. Development
artifacts are disposable and cannot be silently relabeled or reused.

### 7.7 Optional dual commitment model

When `settlement:zeroj-validity` is selected, the assembled EUTxO product
maintains two deterministic commitments:

```text
Yano app-chain MPF root
  - existing Blake2b/Aiken-compatible persistence and query commitment
  - commits every EUTxO record, receipt, bridge record, and the validity root

EUTxO validity root
  - circuit-friendly Poseidon commitment owned by appchain-eutxo-ledger
  - covers the exact state consumed and produced by the validity circuit
  - tracked by the separate Cardano validity-root validator
```

ZeroJ's Poseidon MPF is not interchangeable with Yano's existing
Blake2b/Aiken-compatible MPF. The product does not replace Yano's generic MPF
or make core understand Poseidon. The selected ZeroJ integration atomically
maintains the validity root alongside ordinary EUTxO state and stores that
root under a versioned key covered by the Yano MPF root.

Ledger-only and federated deployments maintain only the ordinary Yano MPF
state and incur no validity-tree or proving overhead. A later migration to
validity settlement must construct and verify an initial validity root from a
root-fixed EUTxO snapshot before the L1 validity contract activates.

The ZeroJ proof binds at least:

```text
chain ID
validity profile digest
circuit and verification-key identity
previous validity root
new validity root
batch data commitment
withdrawal commitment
bridge epoch
```

The separate L1 validator verifies the proof and advances only from its
current validity root to the proved new root. The generic Yano anchor remains
useful for app-chain evidence but is not treated as the ZK settlement root.

## 8. Deterministic EUTxO state

### 8.1 Logical state layout

The exact binary keys and values live in `appchain-eutxo-contracts`. The
logical layout is:

```text
~ profile and bridge framework keys remain in their reserved namespaces

u/<tx-id>/<index>             -> canonical TxOut
t/<tx-id>                     -> accepted transaction receipt
a/<app-message-id>            -> attempt receipt
d/<l1-tx-id>/<index>          -> deposit record
w/<withdrawal-id>             -> irrevocable withdrawal claim
n/<withdrawal-id>             -> local paid/finalized marker
r/<asset-id>                  -> mirrored supply and reserve counters
z/root                        -> optional circuit-friendly validity root
z/batch/<height>              -> optional batch/proof/data commitment status
z/withdrawals                 -> optional withdrawal commitment
address index chunks          -> bounded UTxO references by address/credential
script/datum indexes          -> bounded content-addressed records
```

Keys and values are canonical, versioned, bounded, and covered by golden
vectors. Address indexes are explicitly maintained by the state machine;
they do not require prefix iteration from `AppStateReader`.

### 8.2 Transaction identity and attempts

The Cardano transaction ID is derived from the canonical transaction body.
The app-message ID identifies one submission attempt.

An accepted transaction ID is unique in app-chain history. A second accepted
application of the same transaction is impossible.

An attempt that is invalid against the ordered state records a bounded
attempt receipt but does not reserve the transaction ID permanently. The same
signed transaction may be retried later if its validity interval still allows
it and the previously missing inputs become available.

### 8.3 Block-ordered execution

Mempool and `validateForBlock` admission perform only bounded checks that do
not depend on tentative ordering:

- envelope and body size;
- canonical CBOR shape;
- profile version;
- transaction ID derivation;
- fixed feature bans; and
- obvious witness/script bounds.

Actual ledger validity is evaluated sequentially inside deterministic
`apply()` against the writer's read-your-writes view:

1. resolve every regular, reference, and collateral input;
2. run the pinned transition engine at `block.l1Slot`;
3. if accepted, delete exactly the consumed UTxOs and insert exactly the
   produced UTxOs and indexes;
4. record the accepted receipt; or
5. if rejected, make no UTxO mutation and record only the bounded attempt
   receipt.

For two valid transactions chained within one app block, the second can read
the first transaction's staged outputs. For conflicting transactions, the
first valid transaction in block order wins and later conflicts are rejected
deterministically.

An unexpected engine exception fails the proposal; it is not converted into a
successful or ordinary invalid receipt.

### 8.4 Authorization

Ledger-only application users are authorized by Cardano transaction witnesses
and scripts. Under the validity-rollup profile, L2 users are authorized by
registered Jubjub witnesses over the canonical L2 envelope. Neither profile
uses the outer app-message sender as authority.

The current app-chain member that signs and diffuses an app message is a
relayer. It must not gain authority over the transaction's inputs merely by
being a member. Public or multi-tenant submission still requires ordinary API
authentication, quotas, rate limiting, and abuse controls at the gateway.

## 9. Scalus transition boundary

### 9.1 Required interface

The existing general `TransactionValidator` returns a verdict only. The
plugin needs an internal transition contract similar to:

```java
interface UtxoTransitionEngine {
    TransitionResult apply(
            byte[] canonicalTransactionCbor,
            ResolvedInputs inputs,
            UtxoLedgerProfile profile,
            long currentSlot);
}

sealed interface TransitionResult {
    record Accepted(
            byte[] transactionId,
            List<UtxoRef> consumed,
            List<Utxo> produced,
            ExecutionSummary execution) implements TransitionResult {}

    record Rejected(
            String stableCode,
            byte[] boundedDetailHash) implements TransitionResult {}
}
```

This interface belongs to the EUTxO modules, not `core-api`. It MUST return the
exact mutation, including collateral behavior once that profile is enabled.
The state machine MUST NOT reproduce transaction mutation independently after
asking Scalus only for a Boolean verdict.

### 9.2 Isolation from live L1 state

During deterministic app-chain execution, Scalus receives only:

- the canonical submitted transaction;
- inputs resolved from app-chain committed/staged state;
- scripts and datums from that state or the transaction witnesses;
- the pinned ledger profile; and
- the app block's stable L1 slot.

It MUST NOT call the node's current L1 `UtxoState`, protocol-parameter
provider, wallet backend, network, file system, or wall clock.

### 9.3 Conformance

Every supported feature needs reproducible vectors across:

1. the Scalus emulator;
2. the Yano EUTxO transition engine;
3. restart/replay from app-chain blocks;
4. at least two app-chain members producing identical roots;
5. a Yano Cardano devnet or the Cardano reference ledger where the transaction
   is meaningful; and
6. packaged JVM and, when claimed, native distributions.

The suite covers valid and invalid signatures, conflicting inputs,
within-block chaining, scripts, datums, redeemers, reference inputs/scripts,
fees, validity intervals, assets, collateral, output bounds, and activation
heights.

Version drift in Scalus, Cardano Client Library, protocol parameters, cost
models, codecs, or cryptographic providers is a consensus change and cannot
be released without vector and replay gates.

### 9.4 ZeroJ witness and circuit conformance

When validity settlement is enabled, the transition engine emits a canonical,
bounded transition trace sufficient for witness construction. The trace is
not trusted by the circuit: it supplies private witness values, while the
circuit independently constrains input membership, authorization, transaction
ordering, non-reuse, value conservation, output creation, and the old-to-new
validity-root transition.

The witness builder MUST consume finalized transaction bodies, the pinned
profile, and deterministic state proofs. It MUST NOT reimplement a different
business transition or read mutable live state.

Every validity-profile rule has three agreeing implementations or oracles:

1. Scalus/runtime acceptance and exact mutation;
2. the ZeroJ witness/circuit result; and
3. the Julc/Cardano verifier's public-input and root-transition contract.

A circuit proving success for a Scalus-rejected transition, failing to prove a
Scalus-accepted in-profile transition, or producing a different root blocks
release.

## 10. Cardano deposit protocol

### 10.1 Exact observations

Add a plugin observer such as `script-utxo-deposit-v1`. Its canonical claim
contains at least:

```text
observer and ABI version
chain ID
L1 transaction ID and output index
L1 slot and block hash
validator/script identity
complete canonical Value
inline datum or datum hash plus required preimage
reference script, when present
intended L2 owner/address
deposit nonce
staging refund deadline
```

Every member recomputes the claim from its own L1 block stream before voting.
All fields and collections are bounded and canonically ordered.

### 10.2 Two-phase staging

Deposits use:

```text
user-controlled input
        |
        v
refundable staging validator
        |
        | bridge acceptance before deadline
        v
canonical bridge vault inventory
        |
        | stable follower-verified L1 observation
        v
mirrored app-chain UTxO
```

The staging validator provides a user recovery path if the bridge never
accepts the deposit. The app chain MUST NOT credit a refundable staging output
because the user could later recover the L1 value after spending its mirror.

The mirrored UTxO is created only after the staging output has been consumed
into the recognized vault protocol and that L1 transaction has reached the
configured stability depth.

The deterministic L2 outpoint derives from the chain ID, deposit ABI, and
exact accepted L1 outpoint. One L1 outpoint can be credited at most once.

### 10.3 Deep L1 rollback

The app chain does not roll back after finality. Therefore a rollback deeper
than the configured deposit stability depth is a catastrophic bridge event:

- halt new deposits and withdrawals;
- freeze settlement effects;
- compare app-chain credited deposits with the surviving L1 vault;
- execute the documented reconciliation/governance runbook; and
- never silently repair supply through a node-local mutation.

The selected stability depth is a bridge risk parameter, not a performance
default.

## 11. Reserve and asset invariants

For every withdrawable asset, every node maintains a provable relationship:

```text
stable L1 vault inventory
  >= spendable mirrored L2 supply
     + L2 value committed to pending withdrawals
     - L1 withdrawals already stably confirmed
```

The exact algebra depends on when a claim becomes irrevocable, but the
following are mandatory:

- no credit before stable vault acceptance;
- no deposit outpoint credited twice;
- no L2 transaction creates withdrawable L1 assets;
- no withdrawal releases more than its irrevocable claim;
- no claim is released twice;
- pending and completed withdrawals remain distinguishable;
- vault change stays inside recognized bridge scripts;
- every vault transaction is reconciled from L1 observations;
- counters are derived from the same atomic EUTxO transition as the UTxOs;
  and
- a mismatch halts the bridge rather than becoming a warning.

Minting or burning an L1-native asset off chain is prohibited. A later
L2-only asset namespace is not part of the L1 reserve invariant and is never
withdrawable as the corresponding Cardano-native unit.

## 12. Withdrawal and settlement options

### 12.1 Irrevocable app-chain claim

A withdrawal transaction consumes ordinary mirrored UTxOs and creates an
irrevocable claim containing:

```text
claim ABI and chain ID
unique withdrawal ID/nullifier
exact Cardano destination
complete Value
origin transaction ID
created height
bridge epoch
optional bounded expiry/recovery policy
```

Once a claim is final, the application cannot cancel it or redirect its value.
This permits a proof against an older accepted root to remain safe after newer
roots are anchored. Any recovery mechanism creates a new, explicitly
authorized settlement outcome; it does not rewrite the old claim.

### 12.2 External settlement signer — first implementation

The recommended first executor:

1. reads the final claim and its proof;
2. prepares an unsigned L1 vault transaction;
3. sends the complete body and evidence to a reviewed external custody or
   threshold-signing service;
4. submits the signed transaction;
5. persists the exact submitted transaction ID/body identity;
6. retries only by reconciling that transaction, never by blindly rebuilding;
7. waits for stable L1 observation; and
8. incorporates the matching result into app-chain state.

Signers independently verify chain, network, claim, root, destination, value,
vault inputs, change, fee/collateral policy, bridge epoch, limits, and replay
status. They do not sign coordinator-provided bytes blindly.

### 12.3 Permissionless proof withdrawal — preferred enhancement

An L1 validator may accept:

- one explicitly accepted anchor/root;
- a canonical withdrawal key and value;
- an MPF inclusion proof;
- destination and value matching that value;
- the current bridge epoch; and
- a state-thread/nullifier transition proving the claim was not paid.

Anyone may relay the transaction. The L1 contract, rather than a hot-wallet
executor, enforces claim inclusion and replay protection.

This constrains the executor but remains federated while the app-chain
threshold controls which root becomes accepted.

### 12.4 ZeroJ validity-proof withdrawal — selected future path

Under `settlement:zeroj-validity`, the L1 validity-root validator advances
only when a ZeroJ proof establishes the permitted old-root to new-root batch
transition. The public statement includes the withdrawal commitment.

A withdrawal transaction then proves the immutable claim under that accepted
withdrawal commitment and consumes the corresponding L1 nullifier/state
thread. Anyone may relay it.

This removes threshold trust for transition validity within the exact active
circuit profile. It does not, by itself, guarantee transaction-data
availability, proof availability, censorship resistance, or a usable forced
exit. Those properties gate the stronger rollup label.

### 12.5 Integrated Yano co-signing — deferred

A later deployment may require Yano members to co-sign the vault transaction
inside the node runtime. This is deferred because:

- the existing anchor co-signing path is private and anchor-specific;
- a generic service needs durable round identity, body verification, key
  separation, timeout, failover, resubmission, reconciliation, and lifecycle
  semantics; and
- prematurely exposing the anchor implementation would create a brittle core
  dependency.

Any future core SPI requires its own ADR and must be reusable beyond this
bridge.

## 13. L1 contract set

The bridge SHOULD use separate, reviewed contracts rather than overloading the
current anchor validator:

1. **Deposit staging validator**
   - binds chain, intended L2 owner, nonce, value, and deadline;
   - permits recognized bridge acceptance before the deadline;
   - permits the depositor's authenticated refund after the deadline.

2. **Vault validator**
   - preserves all unwithdrawn assets under recognized vault outputs;
   - accepts only the selected direct-threshold or proof-withdrawal path;
   - validates network, bridge epoch, claim, value, destination, and change;
   - prevents arbitrary output leakage.

3. **Federated bridge state/root validator**
   - carries chain ID, bridge ABI, accepted app-chain root and height,
     bridge epoch, signer policy, and optional withdrawal accumulator;
   - advances monotonically;
   - separates migration from ordinary advancement.

4. **ZeroJ validity-root validator**
   - exists only when `settlement:zeroj-validity` is selected;
   - pins the circuit, proof system, verification key, profile, and bridge
     epoch;
   - verifies the Julc/Plutus V3 proof with context-bound public inputs;
   - advances only from the current validity root to the proved new root;
   - binds the batch-data and withdrawal commitments; and
   - separates verification-key/profile migration from ordinary advancement.

5. **Nullifier/state-thread validator**
   - records paid withdrawal IDs or a commitment to them;
   - guarantees one payment per claim;
   - provides bounded update proofs and migration.

Combining contracts is allowed only after benchmark evidence shows that the
combined ABI remains understandable, bounded, and independently auditable.

## 14. Membership and key separation

App-chain finality members and bridge settlement signers are different roles,
even when the same organizations operate them.

Production configuration SHOULD use separate keys and custody controls for:

- app-message relay;
- app-chain block/finality signing;
- script-anchor/root advancement;
- bridge vault settlement; and
- L1 transaction fees/collateral.

App-chain governed membership MUST NOT automatically rotate bridge settlement
keys. A bridge epoch change requires:

- an explicit future activation point;
- authorization under the current L1 bridge policy;
- a bounded old/new signer-set transition;
- observation of the stable L1 update by every app-chain member;
- no pending signing round under ambiguous ownership; and
- a recovery path if the new set is unavailable.

The v1 bridge may use a static settlement set while app-chain membership is
governed. That is safer than silently coupling the two.

## 15. Effects and idempotency

The deterministic effect system may coordinate settlement intent and outcome,
but it does not itself make an external transfer true.

The bridge executor needs a new type such as:

```text
cardano.bridge.withdrawal-v1
```

It MUST NOT reuse `cardano.payment`.

Structural idempotency comes from:

- one immutable withdrawal ID;
- one prepared L1 transaction identity;
- vault input consumption;
- on-chain nullifier/replay checks;
- persisted submitted-reference reconciliation; and
- follower-verified stable L1 confirmation.

Metadata may assist operator discovery but is not the sole replay defense.

The executor exposes bounded status and metrics for prepared, submitted,
confirmed, retried, expired, parked, mismatched, reserve-halted, and
reconciliation-required states. Secrets, full signed transactions, datums,
and customer destinations are not emitted as metric labels or default logs.

## 16. Data availability, proofs, and exits

Yano app-chain replication is not L1 data availability. A finality certificate
and anchor do not guarantee that a user can later obtain:

- every transaction needed to replay the state;
- the current UTxO set;
- a withdrawal claim proof;
- the applicable membership history; or
- the latest accepted bridge root.

A federated production profile therefore requires:

- at least the configured threshold plus one independent archival copy;
- versioned snapshots and tested restore;
- retained transaction bodies for the bridge horizon;
- root-fixed state and withdrawal proof serving;
- portable finality and anchor evidence;
- exportable UTxO snapshots and transaction history;
- proof availability monitoring; and
- a recovery procedure when the sequencer or proof-serving nodes censor a
  user.

These measures improve availability but do not create a permissionless forced
exit.

A valid ZeroJ proof establishes transition validity for the pinned circuit; it
does not publish the batch or guarantee that another prover or user can
reconstruct state. Therefore:

- `settlement:zeroj-validity` may use an explicitly documented external data
  availability and remain a validity bridge or validium;
- the proof public inputs always bind a canonical batch-data commitment;
- proving nodes, independent archives, and users can retrieve identical batch
  bytes for the declared retention horizon; and
- missing data or proof service halts rollup graduation rather than being
  hidden behind an otherwise valid root.

For the first bounded validity profile, the approved L1-enforced publication
mechanism is part of the validity-root transition itself:

- the redeemer carries the exact canonical `EutxoZkBatchData` bytes;
- the same transaction creates exactly one output at a deployment-pinned DA
  script with those bytes as an inline datum;
- the v2 encoding is exactly 101 bytes: version, count, four fixed payment
  slots, and zero-filled unused slots;
- the ZeroJ circuit derives BLAKE2b-256 from those constrained payment values,
  while the root validator derives it from the inline bytes; both reduce the
  digest into the BLS12-381 scalar field and compare it with the same
  proof-bound public input; and
- missing, duplicate, differently addressed, oversized, or mutated batch
  publication makes the root transition invalid.

The Cardano transaction/output reference and inline datum are the canonical
publication identity. An operator MUST configure and monitor an L1 history
source that serves those transactions for the declared security horizon.
Merely observing the unspent DA output is insufficient because the
publication remains part of transaction history after that output is spent.

The first recovery bundle contains public batch data, the proof, and its
verification key. It contains no owner secret. Once the proof exists, root
advancement and the bounded aggregate withdrawal are permissionless to relay.
Before the proof exists, recovery requires a party that already possesses the
private witness and a compatible proving service/key. The design does not
force inclusion of a transaction censored before app-chain finality.

`rollup:zeroj-cardano` may be claimed only when the accepted transaction data
needed for independent reconstruction is available through the approved L1
or L1-enforced availability design and the approved exit/recovery path does
not depend solely on the current operator threshold.

## 17. Security requirements

Before any mainnet or material-value deployment, the implementation MUST cover:

| Threat | Required control |
|---|---|
| Deposit credited twice | Exact L1 outpoint identity and permanent deduplication |
| Refund after credit | Two-phase staging-to-vault acceptance |
| L1 reorganization | Stability gating and catastrophic deep-rollback halt |
| L2 double spend | Atomic ordered UTxO consumption |
| Conflicting transactions in one block | Deterministic first-valid-in-block order |
| Scalus/version divergence | Pinned profile digest and differential vectors |
| Scalus/circuit divergence | Runtime, witness, circuit, and root differential gates |
| Wrong previous/new validity root | Both roots bound as public inputs and enforced by L1 state |
| Circuit or VK substitution | Pinned circuit, proving key, VK, validator, and profile identities |
| Malformed proof/public input | ZeroJ verification plus Julc VM and Cardano devnet negative vectors |
| Trusted-setup compromise | Reviewed MPC ceremony, transcript verification, and artifact pinning |
| Prover failure or censorship | Redundant prover jobs, durable witnesses, and independent replay |
| Time-dependent script divergence | Stable `AppBlock.l1Slot` and pinned slot conversion |
| Missing reference script/datum | Resolve only from transaction or app-chain state |
| Supply inflation | Per-asset mirrored-supply and stable-vault invariant |
| Withdrawal replay | Irrevocable ID, prepared tx identity, vault inputs, L1 nullifier |
| Executor crash after submit | Durable submitted reference and reconcile-only retry |
| Root threshold compromise | Explicit federated label, limits, delays, signer separation |
| Settlement-key rotation attack | Delayed bridge epochs authorized by current L1 policy |
| Unsettleable UTxO | L1-compatible output/value/datum/script bounds |
| Data withholding | Archival quorum, snapshots, proof availability monitoring |
| Invalid rollup marketing claim | Validium label until DA, reconstruction, and exit gates pass |
| Native/JVM semantic mismatch | Packaged distribution parity suites |
| Contract migration | Explicit versioned migration action and dual-state tests |

Additional mandatory controls include:

- maximum value and per-asset exposure;
- deposit and withdrawal rate limits;
- bridge pause/freeze independent of ordinary app-chain operation;
- destination/network validation;
- fee and collateral limits;
- bounded vault input selection and change;
- hardware or external custody support;
- signed release and artifact digests;
- contract blueprint and compiled script identity pinning;
- circuit source/R1CS, proving-key, verification-key, ceremony, ZeroJ, and
  Julc artifact identity pinning;
- adversarial multi-node, restart, rollback, retry, and failover tests; and
- independent review of on-chain contracts, transition logic, circuits,
  cryptography integration, custody, operations, and recovery.

## 18. Capability and configuration model

The following is illustrative, not a final schema:

```yaml
yano:
  app-chain:
    chains:
      - chain-id: payments-eutxo
        state-machine: eutxo-ledger

        eutxo:
          profile: yano-eutxo-v1
          profile-file: config/eutxo-profile.yml
          expected-profile-digest: "<sha256>"
          execution-engine: scalus

        bridge:
          mode: none # none | cardano-federated

          # Required only for cardano-federated.
          cardano:
            network: preprod
            staging-script-hash: "<hash>"
            vault-script-hash: "<hash>"
            root-script-hash: "<hash>"
            stability-depth: 2160
            settlement-mode: external-signer
            max-value-per-withdrawal-lovelace: 100000000

        validity:
          mode: none # none | zeroj

          # Required only for zeroj.
          zeroj:
            version: 0.1.0-pre10
            julc-version: 0.1.0-pre14
            profile: zeroj-eutxo-validity-v1
            profile-file: config/eutxo-validity-profile.yml
            expected-profile-digest: "<sha256>"
            proof-system: groth16-bls12-381
            circuit-digest: "<sha256>"
            verification-key-digest: "<sha256>"
            validator-script-hash: "<hash>"
            data-availability: operator-archive
```

Secrets are referenced through the existing secret/config mechanisms and are
never rendered into project locks, capability metadata, Studio output, status
JSON, or logs.

The project lock pins at least:

- EUTxO bundle ID, version, and digest;
- Scalus and Cardano Client Library versions;
- ledger profile and digest;
- ZeroJ `0.1.0-pre10` and Julc `0.1.0-pre14` when validity is selected;
- validity profile, circuit, R1CS, proving key, verification key, ceremony,
  compiled validator, and their digests;
- protocol parameters and cost models;
- contract ABI and compiled artifact hashes;
- bridge network and chain identity;
- genesis/deposit/vault identities;
- settlement mode and signer-policy commitment; and
- native/JVM support classification.

## 19. User experience

The eventual CLI may expose:

```text
./yano.sh appchain capabilities
./yano.sh appchain init --recipe eutxo-ledger
./yano.sh appchain init --recipe eutxo-cardano-bridge
./yano.sh appchain init --recipe eutxo-zeroj-validity
./yano.sh appchain init --recipe eutxo-zeroj-preview
./yano.sh appchain config validate

./yano.sh appchain eutxo transaction submit <signed-tx.cbor>
./yano.sh appchain eutxo transaction status <tx-id>
./yano.sh appchain eutxo utxo get <tx-id>#<index>
./yano.sh appchain eutxo utxo list --address <address>
./yano.sh appchain eutxo proof <tx-id>#<index>

./yano.sh appchain bridge deposit prepare ...
./yano.sh appchain bridge deposit status <l1-outpoint>
./yano.sh appchain bridge withdraw prepare ...
./yano.sh appchain bridge withdraw status <withdrawal-id>
./yano.sh appchain bridge reserves
./yano.sh appchain bridge doctor

./yano.sh appchain validity status
./yano.sh appchain validity prove <app-height>
./yano.sh appchain validity proof <app-height>
./yano.sh appchain validity doctor
```

These commands are Phase D product goals and are not accepted syntax until
their owning milestone implements and packages them.

The UI and CLI always distinguish:

- virtual EUTxO transaction accepted;
- app final;
- root anchored;
- ZeroJ batch witness ready;
- validity proof generated and independently verified;
- validity root accepted on L1;
- withdrawal claim ready;
- L1 transaction submitted;
- L1 transaction observed;
- L1 transaction stable;
- data available or unavailable under the configured mode; and
- bridge halted or reconciliation required.

“Settled” is used only for the exact configured settlement definition.

## 20. Delivery plan

### Phase A — Virtual EUTxO ledger

### UTXO-M0 — Profile and transition feasibility

Deliver:

- `appchain-eutxo-contracts` and `appchain-eutxo-testkit`;
- `Yano-EUTxO-Profile-v1`;
- Scalus `UtxoTransitionEngine`;
- canonical transaction/output/receipt vectors;
- valid, invalid, chained, and conflicting transaction tests;
- JVM determinism and replay tests;
- no app-chain capability, L1 contracts, or real funds yet.

Exit criteria:

- Scalus emulator and Yano transition results agree for the supported profile;
- the engine has no live L1, file, network, or wall-clock dependency;
- mutation results are complete enough that the state machine does not
  independently guess Cardano UTxO changes; and
- no ZeroJ dependency exists in the contracts, ledger, client, or testkit
  runtime graph.

### UTXO-M1 — Genesis-funded EUTxO app chain

Deliver:

- `appchain-eutxo-ledger` and `appchain-eutxo-client`;
- the `state:eutxo-ledger` provider;
- genesis/bootstrap allocation transaction;
- transaction submission, receipts, address/outpoint queries, and MPF proofs;
- within-block chaining and deterministic conflict resolution;
- two/three-node restart, catch-up, snapshot, and identical-root tests;
- capability metadata, generated configuration, docs, and a no-funds demo.

Exit criteria:

- signed Cardano-shaped transactions and supported Plutus scripts execute
  identically on all members;
- no bridge config or L1 funds are required;
- the capability remains optional and absent from default runtime dependency
  graphs unless selected.

### UTXO-M2 — Bounded Plutus support and product DX

Deliver:

- supported Scalus/Plutus features graduated one bounded family at a time;
- CLI, Java client, configuration generation, validation, doctor, and demo;
- root-fixed UTxO, receipt, transaction, and proof queries;
- restart, snapshot, catch-up, replay, JVM, and native packaging tests;
- capability metadata, documentation, examples, and trust statement; and
- verified absence of bridge, ZeroJ, Julc, and custody dependencies in a
  ledger-only distribution.

Exit criteria:

- all selected ledger features execute identically across members and replay;
- unsupported features fail with stable bounded codes;
- `state:eutxo-ledger` is a reusable no-funds plugin product; and
- users can install and operate the ledger without selecting bridge or ZK.

### Phase B — Optional federated Cardano bridge

### UTXO-M3 — Stable one-way deposits

Deliver:

- `appchain-eutxo-bridge-cardano`;
- staging and vault contracts from `appchain-eutxo-bridge-onchain`;
- exact deposit observer;
- staging-to-vault acceptance builder;
- stable accepted-deposit import;
- deposit deduplication and mirrored-supply invariant;
- refund-before-acceptance and deep-rollback halt tests; and
- preprod/devnet end-to-end tests.

Exit criteria:

- no refundable output can be credited;
- every credited output maps to one stable accepted L1 outpoint;
- one-way deposit failures cannot inflate mirrored supply; and
- the bridge distribution still has no ZeroJ dependency.

### UTXO-M4 — Federated withdrawals

Deliver:

- irrevocable withdrawal claims;
- external settlement signer integration;
- vault transaction builder and reconciliation;
- L1 confirmation observer;
- limits, pause, bridge epochs, health, metrics, doctor, and runbooks;
- crash-after-submit, signer failure, competing vault input, L1 rollback, and
  recovery tests.

Exit criteria:

- no retry path creates a second payment;
- completed and pending claims reconcile with stable vault inventory;
- the UI, CLI, catalog, and docs use the federated trust label; and
- users may stop at this milestone without enabling ZeroJ.

### UTXO-M5 — Federated bridge stabilization

Deliver:

- root/claim and nullifier validator;
- MPF proof conversion and on-chain verification;
- permissionless relay client;
- proof and transaction budget benchmarks;
- root selection, old-root, migration, and replay adversarial tests.
- prolonged multi-node and packaged-distribution testing;
- JVM/native classification based on actual packaged tests;
- independent contract and bridge review;
- custody and disaster-recovery exercises;
- mainnet parameter/budget dry runs without material custody;
- schema stability decision after external use.

Exit criteria:

- an executor cannot release a claim absent from an accepted federated root;
- the same claim cannot be released twice;
- documentation states that the root threshold still controls root validity;
- no production-funds label before audit findings and operational gates close;
- capability, profile, and contract schemas remain alpha until independently
  implemented or exercised outside their authoring environment; and
- `bridge:cardano-federated` is independently useful without ZK.

### Phase C — Optional direct ZeroJ validity settlement

### UTXO-Z0 — ZeroJ/Cardano feasibility gate

Deliver:

- `appchain-eutxo-zk-contracts`, `appchain-eutxo-zk-zeroj`,
  `appchain-eutxo-zk-onchain`, and `appchain-eutxo-zk-testkit` skeletons
  under `appchain/extensions/eutxo-zk/`;
- ZeroJ `0.1.0-pre10` and Julc `0.1.0-pre14` artifact/digest locks;
- optional validity-commitment integration and dual-root reference model;
- one bounded key-authorized payment transition circuit;
- a five-input feasibility proof for the old/new validity roots, transition
  digest, owner commitment, and batch size; this standalone proof is not an
  L1 settlement profile;
- Groth16 BLS12-381 proof generation and off-chain verification;
- deterministic test-only setup through ZeroJ's single-development-setup
  ceremony API, with the resulting proving-key, verification-key, and setup
  transcript identities pinned in test evidence;
- Julc Plutus V3 validator verification on a Cardano devnet/testnet; and
- constraint, setup, proving-time, proof-size, script-size, CPU, and memory
  benchmarks.

Exit criteria:

- one real old-root to new-root EUTxO transition proves and verifies on
  Cardano;
- tampered roots, profile, batch commitment, proof, VK, and context fail;
- the proof and validator fit pinned Cardano budgets with safety margin; and
- no production or rollup claim is made.

The single-development-setup ceremony is permitted only for automated
feasibility, differential, devnet, and packaged acceptance tests. Its output
MUST be labelled unsafe for production and MUST NOT satisfy the Z6 production
MPC-ceremony gate.

### UTXO-Z1 — Bounded batch transition circuit

Deliver:

- fixed maximum batch, input, output, asset, and witness sizes;
- circuit constraints for input membership, signatures, transaction order,
  nullifier/non-reuse, value conservation, fees, validity intervals, output
  creation, and validity-root updates;
- canonical Poseidon validity-root and withdrawal-commitment codecs;
- positive, negative, malformed-witness, and mutation testing; and
- differential vectors across Scalus, Yano runtime, ZeroJ witness/circuit,
  ZeroJ verification, and Julc VM verification.

Exit criteria:

- no known invalid in-profile batch can produce a valid proof;
- every supported runtime-accepted batch produces the same circuit root;
- unsupported Scalus/Plutus behavior cannot change withdrawable state; and
- proof cost remains within the approved operational envelope.

### UTXO-Z2 — Durable prover pipeline

Deliver:

- `appchain-eutxo-zk-prover` and `appchain-eutxo-zk-client`;
- finalized-block to canonical-witness generation;
- durable proving jobs and artifacts;
- retry, restart, cancellation, timeout, and reconciliation semantics;
- independent/redundant prover support;
- proving-key memory/storage lifecycle and capacity controls;
- proof, public-input, and batch-data APIs; and
- metrics, health, doctor, and operator runbooks.

Exit criteria:

- finalized batches deterministically map to one public statement;
- two independent provers produce proofs accepted for the same statement;
- prover failure cannot mutate consensus or silently skip a batch; and
- proof generation remains optional and absent when the capability is not
  selected.

### UTXO-Z3 — Proof-verified L1 validity-root advancement

Deliver:

- the deployable validators and budget suite in
  `appchain-eutxo-zk-onchain`;
- a settlement-bound circuit/profile with exactly eight ordered public
  inputs: previous root, next root, transition digest, owner commitment,
  batch size, settlement context, batch-data commitment, and withdrawal
  commitment;
- a settlement-context commitment covering chain ID, bridge epoch, profile,
  circuit/proof system, and verification-key identity;
- context-bound Julc validity-root validator;
- old-root to new-root state-thread transition;
- pinned profile, circuit, proof system, VK, batch-data commitment,
  withdrawal commitment, and bridge epoch;
- permissionless proof relay;
- explicit circuit/VK/profile upgrade and rollback-safe migration; and
- Cardano devnet/preprod adversarial and budget tests.

Exit criteria:

- a threshold signature without a valid proof cannot advance the validity
  root;
- a proof for another chain, profile, root, batch, VK, or bridge epoch fails;
- ordinary advancement cannot perform an upgrade; and
- accepted roots are independently observable and reproducible.

### UTXO-Z4 — Proof-gated withdrawals

Deliver:

- withdrawal commitment in the proved transition;
- an initial bounded profile with one aggregate lovelace withdrawal per
  proved batch to a deployment-fixed destination; general claim trees,
  arbitrary recipients, and multi-asset withdrawal require a later profile;
- claim inclusion and nullifier/state-thread enforcement;
- permissionless withdrawal relay;
- proof-validated vault release;
- old-root, replay, duplicate, migration, and competing-vault-input tests; and
- reconciliation of proof-finalized and L1-stable withdrawals.

Exit criteria:

- no invalid app-chain transition can authorize an L1 withdrawal under the
  active validity profile;
- the same claim cannot release value twice;
- committee signatures cannot bypass the validity proof; and
- documentation still states remaining availability and censorship
  assumptions.

### UTXO-Z5 — Data availability, reconstruction, and exits

Deliver:

- reconstruction and adversarial fixtures in
  `appchain-eutxo-zk-testkit`;
- canonical batch publication format bound by the proof;
- L1-enforced inline-datum publication in the root-advance transaction;
- independent state reconstruction from genesis or a verified snapshot;
- public proof/data availability monitoring;
- secret-free post-proof recovery bundles and permissionless root/withdrawal
  relay behavior; and
- censorship, unavailable-data, unavailable-prover, and operator-loss
  exercises.

Exit criteria:

- a separately implemented reference oracle reconstructs every accepted
  validity root in automated vectors, followed by confirmation from an
  implementation outside the authoring environment;
- required batch data remains available for the declared security horizon in
  an operational L1-history exercise;
- the approved exit/recovery path does not rely solely on the current
  operator threshold after proof generation, and the pre-proof witness/prover
  limitation is explicit; and
- only after these gates pass may the product request the
  `rollup:zeroj-cardano` label.

The in-repository implementation and adversarial fixtures satisfy the
automatable portions of this milestone. External reconstruction and
operational retention are release/readiness evidence, not facts a unit test
can manufacture; they remain open and keep the rollup label non-selectable.

### UTXO-Z6 — Production hardening and bounded expansion

Deliver:

- independent circuit, cryptographic-integration, Julc validator, bridge, and
  custody audits;
- production MPC ceremony, transcript verification, and artifact provenance;
- circuit fuzzing, mutation testing, differential CI, and formal review of
  conservation and root-transition invariants;
- redundant proving operations, capacity/failover exercises, and release
  reproducibility;
- mainnet parameter and budget dry runs without material custody; and
- one-at-a-time evaluation of additional ledger or Plutus profile features.

Exit criteria:

- all critical/high findings are closed or explicitly accepted by accountable
  owners;
- ceremony, circuit, VK, validator, profile, ZeroJ, and Julc artifacts are
  immutable and reproducible;
- production-funds approval is made through a separate readiness decision;
  and
- general Plutus support is not inferred from a restricted circuit.

The in-repository implementation completes the automatable portion of Z6:
artifact and ceremony identities are canonical and verified before load,
readiness is fail-closed, circuit mutation/fuzz coverage is deterministic,
prover failover is key- and capacity-constrained, and budget dry runs consume
an explicitly pinned protocol-parameter snapshot. The independent audits,
production MPC ceremony, external reconstruction, operational retention
exercise, mainnet dry-run evidence, findings disposition, and separate
production-funds decision remain external gates. Consequently,
`settlement:zeroj-validity` is selectable only with an explicit
development/testnet trust statement and `rollup:zeroj-cardano` remains
non-selectable.

Completing UTXO-M0 through UTXO-M5 produces a federated bridge, not a ZK
system. Completing UTXO-Z0 through UTXO-Z4 produces validity settlement but
not automatically a rollup. Completing the automatable UTXO-Z5 work adds
L1-enforced data publication and recovery tooling, but the rollup label still
requires external reconstruction, operational retention/recovery evidence,
and the applicable UTXO-Z6 readiness gates.

### Phase D — Testnet preview product integration

Phase D integrates the Phase A/B EUTxO ledger and Cardano bridge concepts with
the Phase C proof components. A milestone is not complete merely because its
interfaces can be exercised by a unit test. Its exit criteria require the
same bytes and identities to cross the real adjacent boundaries.

Each Phase D milestone is developed on a dedicated
`feat/eutxo-d*-<description>` branch. It is implemented, tested, reviewed, and
iterated there before one approved commit is merged into
`feat/eutxo_state_machine_zkrollup`. The next milestone branch starts from
that updated integration branch.

#### UTXO-D0 — Preview contract and fail-closed product boundary

Deliver:

- explicit catalog metadata for the networks supported by a recipe and
  capability;
- resolver enforcement for both recipe-selected and explicitly added
  capabilities;
- a testnet policy permitting `devnet`, `preview`, and `preprod` while
  rejecting `mainnet` for `settlement:zeroj-validity`;
- immutable static identities for transaction format, EUTxO profile, validity
  profile, circuit, proof system, ZeroJ, Julc, and deployment network, plus
  required identity slots for D4-provisioned L2 protocol parameters,
  ceremony, verification key, and validators;
- an explicit distinction between the existing
  `eutxo-zeroj-validity` development recipe and the future
  `eutxo-zeroj-preview` live recipe; and
- catalog, JSON, initializer, and project-lock tests for the policy.

Exit criteria:

- mainnet initialization fails before files, keys, or contract plans are
  produced;
- an explicit ZeroJ capability cannot bypass the recipe's network policy;
- unrestricted existing recipes retain their current behavior;
- custom component catalogs can declare the same bounded network metadata;
  and
- the future preview recipe remains unavailable until D4 supplies an
  executable packaged lifecycle.

D0 implementation review found that the earlier development recipe selected
EUTxO profile v2 while `ZerojPoseidonValidityProvider` accepted only profile
v1. D0 therefore separates the derived state-machine engine from the
selectable `profile:eutxo-plutus-v3` and
`profile:eutxo-key-payments` capabilities. The development recipe now pins
profile v1 and the current static validity identities. D4 owns provisioning
and locking the remaining deployment-specific ceremony, verification-key,
protocol-parameter, and validator identities.

#### UTXO-D1 — Finalized L2 transaction and Jubjub proof parity

Deliver:

- a canonical L2 envelope separating a Cardano-compatible transaction body
  from its Jubjub authorization witnesses;
- Cardano-authorized registration of one active Jubjub key epoch per L2
  payment credential, plus deterministic rotation and revocation records;
- deterministic derivation of a private proof witness from the exact ordered,
  finalized, runtime-accepted L2 envelope;
- development-profile circuit constraints for Jubjub authorization over the
  exact L2 signing commitment, with the canonical body, resolved inputs,
  created outputs, and previous-to-next validity-root transition committed by
  the finalized runtime witness;
- one domain-separation commitment binding chain ID, network, ledger profile,
  validity profile, authorization profile, transaction-body hash, key epoch,
  nonce, and expiry;
- identical runtime, witness, circuit, verifier, and Julc root vectors; and
- removal of the manual `EutxoKeyPaymentBatch` handoff from the live ingestion
  boundary.

Ordinary Cardano Ed25519 remains the L1 gatekeeper for deposit, registration,
recovery, exit, and batch-settlement transactions. The initial L2 profile uses
Jubjub because direct RFC 8032 verification is materially more expensive in a
BLS12-381 arithmetic circuit and is not required on every high-frequency L2
transaction.

Exit criteria:

- every supported finalized transaction produces one deterministic witness;
- the development circuit proves the Jubjub signature equation over the exact
  accepted L2 signing commitment rather than a parallel amount tuple, while
  the trusted runtime/prover verifies and commits the supported body semantics
  and root transition;
- changing any accepted body byte, L2 domain, resolved input, registered key,
  key epoch, signature, output, profile, or root invalidates the witness or
  proof;
- host guards reject non-canonical, off-curve, non-subgroup, identity,
  duplicate, unrelated, expired, or revoked authorization data; and
- unsupported transaction fields fail before they can change withdrawable
  state.

D1 implementation review identified and corrected a Phase C boundary mismatch:
the runtime validity accumulator previously committed a canonical mutation
descriptor while the development circuit independently accumulated amount
tuples. Those were not the same transition. The D1 runtime boundary commits a
versioned finalized-transition witness containing the exact canonical L2
envelope, ordered resolved input records, ordered consumed outpoints and
created outputs, chain and test-network identities, EUTxO, validity and
authorization profile digests, replay-domain commitment, stable L1 slot, app
height, and message ordinal. Its BLAKE2b-256 digest is the sole transition
value accumulated into the validity root, and the exact witness is available
through a root-fixed typed query for asynchronous proving.

The earlier D1 checkpoint encoded the replay domain in Cardano auxiliary data
and extracted public Cardano VKey witnesses. Version 10 supersedes that live
validity boundary with the separate L2 envelope. Ledger-only EUTxO retains
ordinary Cardano transaction behavior. The former manual amount-tuple adapter
remains only as `EutxoDevelopmentBatchIngestor`; it is not a live
finalized-runtime boundary.

D1 may complete functionally against `zeroj-jubjub-dev-v1` while the ZeroJ
team hardens its reusable Jubjub verifier in parallel. Such completion means
only that the trusted Yano sequencer/prover path, proof wiring, and L1
integration work with disposable test funds. It does not satisfy the later
malicious-prover or production-security gate. The ZeroJ upgrade creates a new
immutable hardened circuit profile and artifacts rather than mutating or
relabeling the development profile.

The development profile intentionally divides enforcement as follows:

- the circuit proves the Jubjub signature equation and binds it to the exact
  canonical L2-envelope signing commitment;
- deterministic host validation enforces canonical point encodings, curve and
  subgroup membership, non-identity points, canonical scalars, active key
  registration, supported Cardano-body fields, input ownership,
  conservation, validity interval, output creation, and transition/root
  derivation; and
- the trusted prover publishes the runtime-derived finalized witness and
  matching public inputs.

Host validation is defense in depth, not a substitute for constraints against
an adversarial prover. The hardened successor must move every security-
critical host-only predicate into the circuit, or prove it through an
equivalent reviewed construction, before removing the trusted-prover
restriction. A ZeroJ library upgrade that preserves Java APIs does not
preserve circuit identity: the hardened R1CS, ceremony, proving key,
verification key, validator, and project-lock identities are all new.

#### UTXO-D2 — Cardano Client Lib and public L2 client surface

Deliver:

- root-fixed `UtxoSupplier` and paginated address/outpoint queries;
- immutable `ProtocolParamsSupplier` with an L2 parameter/profile digest;
- an L2 SDK adapter that wraps a Cardano-compatible body, signs the
  domain-separated envelope with a Jubjub session key, and submits it through
  Yano's existing generic `messages` endpoint;
- random encrypted session keys and optional deterministic CIP-8-derived keys,
  with registration, rotation, recovery, and secret-redaction tests;
- deterministic evaluation against the same state root, cost model, L2
  parameters, and stable L1 slot used by consensus;
- transaction-ID and app-message-ID receipt correlation;
- Java, raw HTTP, and command-line examples; and
- stale-root, profile-mismatch, pagination, duplicate-submission, rejection,
  and finality tests.

Exit criteria:

- an ordinary Cardano Client Lib builder can select L2 UTxOs and balance with
  the L2 parameter snapshot before the Yano adapter signs and submits the L2
  envelope;
- submission returns the L2 transaction ID and contained Cardano body hash
  while retaining the outer app-message ID for attempt diagnostics;
- HTTP `202`, app acceptance, app finality, proof generation, and L1
  settlement remain distinct states; and
- L2 zero-fee parameters cannot be used accidentally by the L1 builder.

D2 implements the public client boundary in
`appchain-eutxo-zk-client`: `EutxoRootFixedUtxoSupplier` provides deterministic
CCL paging and rejects mixed-root sessions; `EutxoL2ProtocolParameters`
exposes a zero-fee CCL snapshot whose digest binds the chain, ledger,
validity, and authorization profiles; `EutxoL2TransactionBuilder` wraps the
CCL body and creates ordered Jubjub authorizations; and `EutxoL2Client`
preserves the L2 transaction ID, contained Cardano body hash, and outer Yano
message ID. The state machine exposes the canonical parameter snapshot through
the generic `protocol-parameters` query path.

Random session keys can be stored through an authenticated AES-256-GCM
envelope using PBKDF2-HMAC-SHA256. An optional deterministic CIP-8 signature
derivation is domain-separated; both paths redact key material and support
explicit in-memory destruction. L1 registration, rotation, revocation, and
recovery remain lifecycle operations owned by D4 rather than hidden client
side effects.

#### UTXO-D3 — Measured scalable batch profiles

Deliver:

- immutable candidate profiles such as `cardano-payment-b16`,
  `cardano-payment-b32`, and `cardano-payment-b64`;
- separate circuit, profile, proving-key, verification-key, ceremony, and
  validator identities per batch bound;
- the authorization-security profile as part of every immutable batch
  identity, preventing a development artifact from being relabeled hardened;
- benchmarks for constraints, proving time, memory, proving-key size, proof
  size, canonical L1 batch data, Cardano transaction size, and Julc execution
  budget;
- capacity and backpressure policies for the selected default; and
- mutation and differential suites over minimum, partial, and maximum batches.

Exit criteria:

- batch size is selected by immutable profile and never by an ordinary runtime
  property;
- the default is chosen from recorded measurements on supported developer
  hardware and Cardano devnet/Preview/Preprod limits;
- proof and public-input size remain bounded as declared; and
- exceeding the profile bound fails before proving work is queued.

Recursive aggregation may be evaluated later and is not required for the
first preview.

D3 defines immutable b16, b32, and b64 identities that bind the batch bound,
circuit, proof system, ZeroJ version, and authorization-security profile. The
new development circuit proves an ordered prefix of one to sixteen Jubjub
authorizations with eight constant settlement public inputs and a constant
192-byte Groth16 proof. A real maximum-b16 run recorded 383,495 constraints,
777,269 wires, 109,263 ms development setup, and 15,058 ms proof generation
under the documented local Java 25/4 GiB test-heap environment. The actual
Julc settlement validator accepted another full-b16 proof on the Yano devnet
profile with a measured execution budget of 4,246,292,718 CPU and 1,239,176
memory units.

Only b16 is the measured **development** default. b32 and b64 remain
unselectable candidates. Preview/Preprod transaction and Julc-budget
measurements, plus persisted-key and peak-memory measurements on all
networks, remain `NOT_EXERCISED`; no value is invented for those fields.
`EutxoFinalizedBatchScheduler` provides bounded FIFO ingestion, validates each
finalized-witness digest, and rejects queue overflow before proving work is
accepted.

#### UTXO-D4 — Packaged prover, relay, contracts, and `yano.sh` lifecycle

Implementation status (version 15):

- the stock JVM distribution now packages the EUTxO state-machine provider,
  Cardano bridge observer, ZeroJ validity provider, b16 prover support, and
  lifecycle CLI;
- `eutxo-zeroj-preview` composes the bridge and settlement capabilities and
  generates a project for devnet, Preview, or Preprod;
  the two public testnets require a durable unsafe-development
  acknowledgement and mainnet is rejected at resolution and lifecycle time;
- `appchain validity` provides project-aware bootstrap, status, prove, proof,
  doctor, deposit, settlement, withdrawal, recovery, and reconciliation
  commands;
- `appchain validity key generate` creates the initial encrypted local Jubjub
  session key before project generation; the preview recipe then materializes
  only its public key, L2 address, and key epoch as a fundless registration;
- deterministic contract plans, content-addressed proofs, operation journals,
  secret-by-reference submission, and retained-artifact verification are
  implemented;
- an external Cardano builder/wallet remains responsible for constructing and
  signing ordinary L1 transactions; the lifecycle submits signed CBOR and
  records stability without taking custody of an L1 key; and
- a source-level Yano-devnet test now stages and accepts a real L1 deposit,
  waits for stable L2 credit, submits and finalizes a Jubjub-authorized L2
  withdrawal, creates and verifies the exact proof, advances the root, pays
  from the accepted custody funds, and waits for stable withdrawal
  reconciliation. Packaged plan-only commands must still report
  `PLANNED_NOT_SUBMITTED`, and unrun public-network or recovery rows must
  remain `NOT_EXERCISED`.

Deliver:

- stock JVM distribution packaging for the selected ZeroJ provider, prover,
  client, contract artifacts, and permissionless relay;
- canonical finalized-transition import and durable proof-job operation;
- pinned development/testnet ceremony import and verification;
- deploy/bootstrap plans for deposit staging, validity root, batch-data, and
  proof-withdrawal vault validators;
- `./yano.sh appchain init --recipe eutxo-zeroj-preview`;
- project-aware cluster, validity bootstrap/status/prove/proof/doctor, deposit,
  transaction, settlement, withdrawal, and recovery commands;
- explicit API-key, secret-reference, filesystem-permission, and log-redaction
  handling; and
- explicit trusted-prover status and unsafe-development acknowledgement for
  `zeroj-jubjub-dev-v1`; and
- idempotent restart, retry, cancellation, relay, and reconciliation behavior.

Exit criteria:

- a generated devnet project starts without source-tree-only classpaths;
- the stock distribution performs artifact bootstrap, exact finalized
  transition proving, proof verification, signed-CBOR relay, operation
  journaling, and reconciliation without a source-tree-only classpath;
- deterministic contract and operation plans make every still-external
  Cardano build/sign/stability step explicit; D5 owns live deployment,
  observation, root stability, and proof-gated withdrawal evidence;
- every lifecycle step is safely repeatable after partial failure; and
- mainnet remains rejected by both initialization and lifecycle commands;
  Preview and Preprod reject the development profile unless the explicit
  unsafe-development acknowledgement is present.

#### UTXO-D5 — Live devnet, Preview, and Preprod acceptance

Implementation status (version 15):

- a final-distribution black-box gate exercises recipe generation, project
  validation, contract-plan bootstrap, lifecycle status, public-testnet
  acknowledgement, and mainnet rejection through packaged `./yano.sh`;
- a versioned, schema-bounded network evidence file is shipped in the JVM
  archive and build tests prevent it from overstating the development
  authorization profile;
- packaged lifecycle policy is `PASSED` for devnet, Preview, and Preprod;
- the complete live Yano-devnet path from staged L1 deposit through stable L2
  credit, an actual app-chain transition, Groth16 proof, L1 root advancement,
  payout from accepted custody funds, and stable L2 withdrawal reconciliation
  is `PASSED`;
- maximum-b16, rollback/recovery, and independent reconstruction remain
  `NOT_EXERCISED` on devnet; all live rows remain `NOT_EXERCISED` on Preview
  and Preprod; and
- those external rows are not milestone code defects, but they remain release
  evidence gates and prevent a preview/production-readiness claim.

Deliver:

- automated disposable Yano devnet end-to-end acceptance;
- public Cardano Preview interoperability acceptance using only disposable
  test funds and an explicitly trusted proof submitter;
- Cardano Preprod mainnet-like integration rehearsal with pinned scripts,
  protocol parameters, disposable funds, and the same trust warning;
- restart, process crash, L1 rollback, appchain replay, duplicate relay,
  unavailable prover, unavailable data, operator loss, and recovery tests;
- cross-node and independent reconstruction checks; and
- bounded observability, evidence bundles, and operator runbooks.

Exit criteria:

- test ADA completes deposit to rapid L2 transactions to proof-gated L1
  withdrawal on all three supported networks;
- rollback and restart cannot duplicate deposits, roots, or withdrawals;
- another implementation reconstructs the accepted roots from published
  canonical data, where such an external implementation is available; and
- no step relies on development-only implicit paths or unpinned artifacts.

Preview/Preprod access, funded wallets, and independent implementation work
are external acceptance resources. The build may package their scripts and
evidence schema but MUST report those gates as `NOT_EXERCISED` until the real
networks and independent implementation have been used.

#### UTXO-D6 — Preview stabilization and release decision

Implementation status (version 15):

- the release decision is `EXPERIMENTAL_TESTNET_ONLY`;
- a schema-bounded release contract pins the catalog/evidence digests and all
  stable product identities, and is included in the JVM archive;
- build gates assert experimental maturity, JVM-only runtime, exact supported
  networks, mainnet rejection, non-selectable graduation label, trusted
  prover, test-funds policy, and the open security gates;
- Cardano Client Lib, `cardano-cli`, backend-wallet, CIP-30, random-key, and
  CIP-8-derived-key behavior and limitations are documented; and
- D6's automatable exit criteria are complete, while the named D5 live-network
  and Z5/Z6 security gates remain `NOT_EXERCISED` and continue to block
  preview/production graduation.

Deliver:

- closure or explicit accountable disposition of preview-blocking findings;
- reproducible release manifests and packaged acceptance evidence;
- a compatibility statement for Cardano Client Lib, backend wallets,
  `cardano-cli`, and tested CIP-30 `signData` key derivation;
- a documented random-key alternative and CIP-8-derived-key security model;
- clear custody, censorship, data-availability, proving, recovery, and
  performance limitations; and
- an explicit release decision keeping the live recipe experimental while
  `zeroj-jubjub-dev-v1` is selected, or changing it to preview only after a
  hardened authorization profile and its gates exist.

Exit criteria:

- `eutxo-zeroj-preview` is selectable only for devnet, Preview, and Preprod,
  with the development-profile acknowledgement required on public testnets;
- all generated output and runtime status identify the exact authorization
  profile, trusted-prover requirement, and test-only funds policy;
- release evidence cannot mark malicious-prover protection complete while the
  development profile is selected;
- the generic `rollup:zeroj-cardano` graduation label remains non-selectable
  until its separate Z5/Z6 external and production-readiness gates pass; and
- mainnet enablement requires a later ADR and cannot be unlocked by a hidden
  flag, acknowledgement string, or configuration override.

## 21. Build and release gates

The build fails when:

- a selected EUTxO capability has no pinned execution engine;
- capability, bundle, profile, or contract versions disagree;
- the profile digest differs across generated members;
- a constraint enforced as consensus lacks a deterministic source or vector;
- packaged CLI/runtime artifacts cannot load the selected JVM bundle;
- native support is claimed without build-time bundle inclusion and native
  conformance;
- deposit observer keys/ABI drift from maintained contract and launcher
  artifacts;
- on-chain compiled hashes differ from the release index;
- deterministic replay produces a different root;
- an unsupported transaction feature is accepted;
- reserve/conservation property tests find inflation;
- retry/failover tests produce two L1 releases for one claim;
- docs or capability metadata omit the applicable trust statement;
- ledger-only or federated-bridge dependency graphs contain ZeroJ or Julc
  proving/on-chain artifacts;
- a selected validity capability does not pin ZeroJ `0.1.0-pre10`, Julc
  `0.1.0-pre14`, profile, circuit, R1CS, proving key, VK, ceremony, and
  validator identities;
- a development Jubjub circuit or ceremony is labeled hardened,
  permissionless, production-ready, or mainnet-compatible;
- an L2 Jubjub witness is encoded in Cardano's L1 VKey witness list;
- authorization-profile identity is absent from a circuit, project lock,
  release manifest, status response, or evidence bundle;
- Scalus/runtime and ZeroJ circuit vectors disagree on acceptance, mutation,
  or validity root;
- a Julc/Cardano negative vector accepts a mismatched chain, profile, old/new
  root, batch commitment, withdrawal commitment, VK, or bridge epoch;
- a validity root can advance without exactly one proof-bound canonical batch
  datum at the pinned L1 data-availability script;
- the validity-root public statement is not reproducible from the finalized
  batch;
- proof, script, transaction, CPU, or memory budgets exceed their configured
  bounds; or
- an imported proving-key directory is loaded without a matching ceremony
  inventory, profile, circuit, and verification-key identity;
- a release manifest omits the pinned ZeroJ, Julc, profile, circuit, key, or
  validator identities;
- a self-certified record satisfies a gate that requires independent or
  accountable external evidence; or
- `rollup:zeroj-cardano` is exposed as selectable before its data
  availability, reconstruction, exit, and readiness gates pass.

Real-funds release gates additionally require:

- completed independent review;
- all critical/high findings closed or explicitly accepted by the responsible
  owner;
- verified pause, recovery, signer rotation, and deep-rollback runbooks;
- exposure caps and rate limits enabled by default;
- no demo keys or automatic secret generation in production profiles; and
- a supported external custody/signing configuration.

Validity-settlement release gates additionally require:

- pinned and verified production ceremony provenance;
- independent review of the circuit, witness generation, ZeroJ integration,
  Julc validator, public-input binding, and migration design;
- redundant prover and independent reconstruction exercises;
- stable Cardano devnet/preprod proof-verification evidence;
- explicit validium or rollup availability classification; and
- no value-bearing production label while ZeroJ or the selected verifier path
  remains outside its approved readiness posture.

## 22. Consequences

### Positive

- Yano gains a reusable EUTxO and Plutus execution product without coupling
  Scalus, ZeroJ, or custody to core.
- Developers may use the ledger with genesis funds, simulations, private
  assets, or custom settlement.
- The bridge trust boundary is explicit and independently replaceable.
- Existing MPF, L1 observer, effects, plugin, catalog, Studio, CLI, and console
  work remains reusable.
- Users choose ledger-only, federated bridge, or ZeroJ validity settlement
  without paying for unselected dependencies.
- The direct ZeroJ path can progressively remove transition-validity trust
  from L1 settlement.
- The validium/rollup distinction prevents stronger availability and exit
  claims than the implementation earns.

### Negative

- The first bridge is federated and cannot provide a permissionless forced
  exit.
- The execution profile is intentionally narrower than all of Conway.
- Scalus and Cardano ledger upgrades become consensus-sensitive release work.
- Native packaging requires build-time selection, and the ZK image is
  materially larger than ledger-only or federated images.
- A safe bridge requires multiple contracts, external custody integration,
  operational controls, and independent audits.
- Dynamic app-chain membership cannot automatically manage bridge custody.
- The dual commitment must be maintained and tested atomically.
- The first ZeroJ circuit supports a restricted payment profile, not arbitrary
  Plutus.
- Proving infrastructure, ceremonies, circuit upgrades, data availability,
  and audits add substantial operational work.

### Neutral but important

- MPF proofs authenticate values under a root; the root's trust source remains
  a separate question.
- Script anchoring is necessary for on-chain consumption but does not by
  itself prove batch validity.
- A valid ZeroJ proof establishes only the statement encoded by its pinned
  circuit.
- Proof validity does not imply transaction-data availability or forced exit.
- Effects coordinate external work but do not prove external truth without
  stable observation or contract enforcement.

## 23. Rejected shortcuts

The following are explicitly rejected:

- modifying the existing `balances` state machine and calling it UTxO support;
- treating Plutus script evaluation alone as Cardano ledger validation;
- resolving reference inputs or protocol parameters from each member's live
  L1 tip during `apply()`;
- crediting a refundable staging deposit;
- mirroring only total lovelace while discarding output identity and assets;
- allowing app-message membership to replace transaction witnesses;
- minting withdrawable L1-native assets inside the app chain;
- using `cardano.payment` as the bridge executor;
- relying on metadata as the only withdrawal idempotency mechanism;
- overloading the current anchor validator with custody and withdrawal logic;
- coupling bridge-key rotation automatically to governed membership;
- describing a threshold-signed root plus MPF proof as a rollup validity proof;
- describing off-chain operator-held data plus a validity proof as a rollup;
- assuming Scalus or arbitrary Plutus execution automatically becomes a
  ZeroJ circuit;
- accepting transactions that can change withdrawable state but are outside
  the active validity profile;
- treating a ZeroJ proof verifier as sufficient without binding chain,
  profile, old/new roots, batch, withdrawals, VK, and bridge epoch;
- sharing one commitment and pretending Poseidon and Yano's native MPF are
  interchangeable;
- adding Scalus, ZeroJ, EUTxO, or bridge contracts to `core-api`;
- introducing Hydra integration under this product;
- promising drop-in runtime plugins for an already-built native executable; or
- promising mainnet custody from successful devnet functional tests.

## 24. Open follow-on decisions

The following need concrete resolution during the indicated milestones:

1. Exact canonical transaction/output and receipt codecs.
2. Whether v1 supports zero fees only or an explicit treasury UTxO.
3. The initial Plutus language/builtin subset on JVM and native.
4. Exact `isValid=false` and collateral graduation criteria.
5. Deposit ABI, staging deadline, and vault inventory shape.
6. Single pooled vault versus bounded sharded vault UTxOs.
7. External custody/signing protocol and prepared-transaction persistence.
8. MPF withdrawal key/value shape and on-chain proof budget.
9. Nullifier data structure and migration.
10. Root acceptance and bridge epoch contract.
11. Reserve snapshot/bootstrap and cold-start recovery.
12. Proof/data-retention horizon and archival quorum.
13. Public submission authentication, quotas, and fee policy.
14. Native support classification for the pinned Scalus release.
15. Whether field demand justifies a generic core settlement co-signing SPI.
16. Exact Poseidon validity-tree shape and atomic dual-root update contract.
17. Initial ZeroJ batch/input/output/asset bounds.
18. Groth16 ceremony and circuit-upgrade governance.
19. Proving-key storage, redundant-prover, and job-recovery topology.
20. Exact public-input and batch-data commitment codecs.
21. Julc validator context binding and Cardano budget safety margin.
22. Which data-availability mechanism can satisfy rollup graduation.
23. Forced-exit or recovery behavior permitted by Cardano transaction and
    script budgets.
24. The independently audited feature set that may graduate beyond
    key-authorized payments.

## 25. Review questions

1. Is the first real-funds use case narrow enough to begin with payment/netting
   rather than general Plutus?
2. Is the federated trust statement acceptable for the target operators and
   users?
3. Should MPF proof-constrained withdrawal be mandatory before a federated
   production pilot or remain part of M5?
4. Can an external custody system satisfy threshold, reconciliation, and
   rotation requirements without a Yano core SPI?
5. Which exact UTxO output features must be portable back to L1 in v1?
6. What data and proof availability guarantee is required for the first
   deployment?
7. Is the proposed dual-root design preferable to proving the existing
   Blake2b MPF despite its expected circuit cost?
8. Are the first ZeroJ validity profile and batch bounds narrow enough to
   implement and audit?
9. Which availability and exit gates are mandatory before using the rollup
   label?
10. What value/exposure cap is acceptable before the bridge receives an
   independent audit?
