# ADR-UTXO-003: Multi-User EUTxO Deposit, Payment, and Withdrawal

- Status: Implemented
- Version: v2
- Date: 2026-07-25
- Owners: App-chain / EUTxO
- Related: ADR-UTXO-001, ADR-UTXO-002

## 1. Context

The first disposable EUTxO demos use one generated Cardano identity as the L1
depositor and L2 spender, and a second address only as the withdrawal payout.
That proves the bridge mechanics but does not demonstrate the intended product:
independent users deposit from L1, transact rapidly on L2, and withdraw to L1.

The bridge protocol already binds every accepted deposit to an `l2Address`.
The direct EUTxO profile can authorize that address with ordinary Cardano VKey
witnesses. The ZK profile additionally needs a committed mapping from the
address payment credential to the user's Jubjub session public key. Today that
mapping is available only for one genesis identity.

The demo must also distinguish disposable automatic signing from external
wallet use. Production-like commands must never accept private keys or
mnemonics as command-line values.

## 2. Decision

### 2.1 One lifecycle for direct and ZK profiles

Both profiles use the same user journey:

1. Alice creates and signs an L1 deposit naming Alice's L2 address.
2. The accepted vault output is observed and mirrored as Alice's L2 EUTxO.
3. Alice signs an L2 payment to Bob.
4. Bob signs a second L2 transaction creating a withdrawal claim.
5. The operator/prover settles the claim and pays Bob on L1.
6. Reconciliation confirms the exact claim, L1 transaction, and L2 record.

Direct EUTxO uses Cardano VKey witnesses for steps 3 and 4. ZK EUTxO uses
Jubjub session-key authorizations and proves both finalized transitions.

### 2.2 Deposit-bound optional L2 key registration

The staging and accepted-vault datum contracts carry an optional bounded L2
key binding:

```text
authorization profile
key epoch
32-byte public key
```

An absent binding is represented canonically and remains valid for direct
EUTxO. When present:

- the L2 address must be key-controlled;
- its Cardano payment credential must equal the deposit's depositor key hash;
- the authorization profile must equal the chain's selected profile;
- the key epoch must be positive;
- the imported registration is committed under the payment credential;
- an identical registration is idempotent; and
- a conflicting registration fails closed.

The staging validator preserves the binding exactly when value moves into the
vault. This makes the user's L1-authorized deposit the registration event; the
operator cannot substitute an L2 key.

The preview ZeroJ profile continues to require its documented trusted-prover
boundary. Dynamic registrations are MPF-committed and host-verified. Folding a
registration-set commitment into the validity circuit is a graduation gate,
not a claim made by this ADR.

### 2.3 Quick demo identities

Every new bridge or ZK demo workspace creates named disposable users:

- Alice: Cardano wallet and, for ZK, encrypted Jubjub session key;
- Bob: Cardano L2 wallet, a distinct Cardano L1 payout wallet and, for ZK,
  encrypted Jubjub session key; and
- operator: demo vault/settlement authority.

The Bob L2 and L1 payout addresses are intentionally distinct. The bridge
identifies a withdrawal-producing output by the configured L1 payout address;
reusing that address for Bob's ordinary L2 account would make a normal payment
look like a malformed withdrawal.

The current generated wallet files may be retained as compatibility aliases,
but all reports and documentation use the user roles.

`round-trip --count N` is a resumable target. Every round contains distinct
Alice and Bob deposits, Alice-to-Bob payment, Bob withdrawal, settlement,
nonces, artifacts, and (for ZK) a proof over the round's transitions.

### 2.4 External signed-deposit boundary

The demo tooling exposes an offline-safe split:

```text
deposit-build  -> unsigned Cardano transaction file
external wallet/cardano-cli/CCL signs it
deposit-submit -> validates and submits signed CBOR
```

The builder accepts public inputs only: source address, destination L2 address,
amount, output file, and optional L2 public-key binding. The submitter:

- accepts a file, never an inline private key;
- decodes and bounds the transaction before submission;
- verifies exactly one output targets the configured demo vault;
- verifies the chain and requested L2 identity in its inline datum;
- submits through the configured Yano L1 endpoint; and
- waits for the corresponding stable L2 deposit record.

Disposable quick-demo commands use the same builder and submitter internally,
adding only local signing from owner-only workspace files.

Public testnet production provisioning remains in the manual operator flow;
the disposable demo remains devnet-only.

The disposable builder writes directly to its native-script demo vault and
records the selected funding outpoint in datum v2. This is intentional for
the explicitly federated quick demo. Public-testnet and production flows
continue to use the separately implemented refundable staging validator and
acceptance transaction.

## 3. Safety and invariants

- No secret is written to the public manifest, report, command line, or logs.
- Secret files are owner-only and referenced indirectly.
- A user signs only spends of that user's L1/L2 inputs.
- Bob's withdrawal transaction must consume a Bob-controlled L2 output.
- L2 key registration is keyed by Cardano payment credential, not a display
  name.
- All operation IDs, nonces, and artifacts are round- and user-specific.
- Repeating a completed target count performs no new transactions.
- Existing bridge reserve, pending-withdrawal, replay, and settlement checks
  remain authoritative.

## 4. Milestones and acceptance

### MU-M1 — Contracts and registration

- Add the canonical optional key binding to staging, vault, and deposit claim.
- Preserve it in the Julc staging validator and Cardano observer.
- Import registrations deterministically with idempotency/conflict tests.

### MU-M2 — Named users and direct flow

- Generate Alice, Bob, and operator identities.
- Execute Alice deposit, Alice-to-Bob VKey payment, Bob VKey withdrawal, and
  L1 payout.
- Support resumable multiple rounds.

### MU-M3 — ZK flow

- Generate encrypted Alice and Bob Jubjub keys.
- Deposit-bind both registrations.
- Execute Alice-to-Bob and Bob-withdrawal authorizations.
- Prove and verify both transitions in each round.

### MU-M4 — External signing

- Add build/submit commands and strict file/transaction validation.
- Prove a transaction signed outside Yano reaches the same deposit importer.
- Document CIP-30, cardano-cli, and CCL integration boundaries.

### MU-M5 — Packaged acceptance

- Run direct and ZK multi-user flows from the release ZIP.
- Restart and re-run the same target count without journal mutation.
- Verify no private material appears in JSON/text output or public artifacts.

## 5. Consequences

The demo becomes a faithful multi-user product walkthrough rather than a
single-operator mechanics test. The deposit datum grows and therefore requires
coordinated contract, observer, ledger, testkit, and documentation updates.
Because EUTxO app-chain support has not been released, no legacy datum migration
is required; retained development workspaces must be recreated.

## 6. Implementation notes

The EUTxO contracts, Julc validator, Cardano observer, ledger, direct demo, and
optional ZK demo implement this decision. Old `ledgerWallet` and
`recipientWallet` names remain internal aliases only; user-facing output uses
Alice, Bob, and operator. Each ZK proof consumes the ordered payment and
withdrawal transitions for its round.
