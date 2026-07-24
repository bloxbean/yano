# D4 packaged testnet lifecycle

## Outcome

D4 packages the experimental EUTxO/ZeroJ path in the stock JVM distribution
and exposes it through `./yano.sh appchain validity`. It is a project-aware
operator lifecycle, not a claim that a live rollup has passed public-network
acceptance.

The packaged boundary contains:

- the `eutxo-zeroj-preview` recipe for devnet, Preview, and Preprod;
- the EUTxO state-machine provider and ZeroJ validity provider;
- the measured `cardano-payment-b16` development circuit;
- ceremony generation/import verification, proving, and independent proof
  verification;
- deterministic plans for deposit staging, validity root, batch-data, and
  proof-withdrawal contracts;
- idempotent operation journals for deposit, settlement, withdrawal, and
  recovery;
- signed Cardano-CBOR submission through Yano's transaction endpoint; and
- status, doctor, and reconciliation commands.

## Safety boundary

`zeroj-jubjub-dev-v1` combines in-circuit Jubjub verification with host checks
that have not all been constrained against an adversarial prover. It therefore
requires a trusted sequencer/prover and disposable test funds.

- devnet is allowed by default under the generated trust warning;
- Preview and Preprod require
  `EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET` in the project blueprint and lock;
- mainnet is rejected by project resolution and again by every lifecycle
  command; and
- a hardened successor must use new circuit, R1CS, ceremony, proving-key,
  verification-key, validator, and release identities.

The current ZeroJ release can be used for functional lifecycle work while its
Jubjub gadget is hardened in parallel. Functional evidence from this profile
must not be counted as malicious-prover security evidence.

## Durable state

All lifecycle state is below `<project>/runtime/validity`:

| Path | Purpose |
|---|---|
| `contracts.json` | Deterministic contract identities and deployment plan |
| `ceremony/` | Development or imported proving and verification artifacts |
| `proofs/<digest>.proof` | Content-addressed proof artifacts |
| `operations/<kind>/<id>.json` | Idempotent L1 operation journals |
| `state.json` | Current lifecycle identity, stage, and counts |

The tool refuses symlinks, bounds input sizes, uses atomic replacement, checks
semantic identity before reusing output, and applies owner-only POSIX
permissions where supported. API keys are accepted only by environment
variable name and are never persisted.

## Operator boundary

Yano does not hold an L1 signing key. A Cardano builder or wallet constructs
and signs ordinary L1 transactions from the deterministic plan/request.
`validity ... submit` accepts the resulting signed CBOR, posts it to Yano's
Blockfrost-compatible endpoint, and stores only the transaction ID and CBOR
digest. An operator or later watcher marks the matching transaction stable.

This separation keeps key custody outside the lifecycle module and makes
partial failure visible. A plan reports `PLANNED_NOT_SUBMITTED`; it never
silently becomes “deployed.”

## Verification

```bash
./gradlew \
  :appchain-eutxo-zk-contracts:test \
  :appchain-eutxo-zk-zeroj:test \
  :appchain-eutxo-zk-lifecycle:test \
  :appchain-eutxo-zk-runtime:check \
  :appchain-devtools:test
```

The D4 tests cover profile/VK/proof identity binding, project/network policy,
idempotent plan and operation state, public-testnet acknowledgement, mainnet
rejection, packaged provider presence, and catalog/distribution parity.

Live contract deployment and deposit-to-withdrawal acceptance remain D5
evidence and must stay `NOT_EXERCISED` until performed on the named network.
