# EUTxO ZeroJ validity settlement: getting started

## Current status

The current capability is experimental. It provides executable circuit,
prover, data-availability, reconstruction, and Julc validator components, but
it is not yet a single live Cardano L1-to-appchain-to-L1 product.

In particular:

- the stock Yano distribution does not yet package and operate the ZeroJ
  prover and settlement relay;
- `./yano.sh appchain cluster` does not yet accept an EUTxO-ZK recipe or
  bootstrap the Cardano contracts;
- the federated bridge and `settlement:zeroj-validity` capabilities currently
  conflict;
- the bounded proof witness is not yet derived automatically from finalized
  signed EUTxO transactions; and
- the current circuit proves a restricted one-to-four-payment arithmetic
  transition, not Cardano VKey or CIP-8 signatures.

Do not use this profile with real funds. The commands in
[Current executable developer flow](#current-executable-developer-flow) are
available now. The commands in
[Target preview user flow](#target-preview-user-flow) define the intended
preview product and are not accepted CLI syntax yet.

## Current executable developer flow

### Prerequisites

- Java 25.
- The Yano source tree.
- No real ADA or public-network credentials.

### 1. Run the complete module suite

From the repository root:

```bash
./gradlew \
  :appchain-eutxo-zk-contracts:test \
  :appchain-eutxo-zk-zeroj:test \
  :appchain-eutxo-zk-prover:test \
  :appchain-eutxo-zk-client:test \
  :appchain-eutxo-zk-onchain:test \
  :appchain-eutxo-zk-testkit:test
```

This exercises:

- deterministic dual-root EUTxO state updates;
- a single-participant development ceremony;
- real Groth16 BLS12-381 proof generation and verification;
- durable proof jobs and prover failover;
- proof-bound canonical batch data;
- Julc Plutus V3 validity-root and withdrawal validators; and
- reconstruction and recovery behavior.

The tests execute the boundaries in one build, but they do not deploy the
validators or submit transactions to a live Cardano network.

### 2. Run the bounded proof example

```bash
./gradlew :appchain-eutxo-zk-zeroj:test \
  --tests \
  'com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoZ1BatchCircuitTest.realFourPaymentProofVerifiesAndPublicTamperingFails' \
  --rerun-tasks
```

Expected output includes the circuit constraint and wire counts and the local
proof duration. The test also changes public inputs and proves that the
mutated statement is rejected.

### 3. Run the proof-bound L1 validator example

```bash
./gradlew :appchain-eutxo-zk-onchain:test \
  --tests \
  'com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain.EutxoValidityRootValidatorTest.rootAdvanceRequiresOneExactProofBoundDataPublication' \
  --rerun-tasks
```

This compiles and evaluates the Julc validator in a Plutus V3 VM. It checks
that a normal root advance requires:

- the active root-thread identity;
- a proof for the exact previous and next roots;
- the pinned settlement context and verification key;
- the exact canonical batch-data inline datum; and
- the withdrawal commitment carried by the proof.

It is a VM-level contract test, not a live devnet transaction.

### 4. Inspect the generated development project

Build the version-matched tooling, then generate the current virtual-value
recipe:

```bash
./gradlew :appchain-devtools:installDist

./app/yano.sh appchain init --non-interactive \
  --recipe eutxo-zeroj-validity \
  --network devnet \
  --members 3 \
  --runtime jvm \
  --deployment host \
  --name eutxo-zk-development \
  --chain-id payments-zk \
  --answer eutxoGenesisAddress=<testnet-address> \
  --answer eutxoGenesisLovelace=100000000 \
  --output build/eutxo-zk-development
```

The generated project is useful for inspecting and validating the selected
capabilities and consensus configuration. It is not yet a live ZK-rollup
launcher because the distribution, prover, relay, and L1 bootstrap work
listed above is still required.

The recipe selects `profile:eutxo-key-payments`, not
`profile:eutxo-plutus-v3`. This matches the current ZeroJ provider's reviewed
transaction subset and prevents generated projects from claiming Plutus
transitions that the circuit cannot prove.

## Target preview user flow

The preview should eventually provide this flow through `./yano.sh`:

```text
Cardano test ADA
  -> refundable deposit staging contract
  -> accepted validity vault
  -> stable L1 observation
  -> mirrored EUTxO on the Yano appchain
  -> wallet-authorized rapid EUTxO transactions
  -> finalized bounded batch
  -> ZeroJ proof
  -> permissionless proof/root relay with batch data on L1
  -> proof-gated withdrawal
  -> stable Cardano testnet output
```

The intended command shape is:

```bash
# Proposed preview syntax — not implemented yet.
./yano.sh appchain init --recipe eutxo-zeroj-preview \
  --network devnet --members 3 --runtime jvm \
  --name payments-zk --output payments-zk

./yano.sh appchain cluster start --project payments-zk
./yano.sh appchain validity bootstrap --project payments-zk
./yano.sh appchain validity status --project payments-zk

./yano.sh appchain bridge deposit prepare --project payments-zk ...
./yano.sh appchain bridge deposit status --project payments-zk <l1-outpoint>

./yano.sh appchain eutxo transaction submit \
  --project payments-zk <wallet-signed-command.cbor>

./yano.sh appchain validity prove --project payments-zk <finalized-height>
./yano.sh appchain validity settle --project payments-zk <finalized-height>

./yano.sh appchain bridge withdraw prepare --project payments-zk ...
./yano.sh appchain bridge withdraw status --project payments-zk <withdrawal-id>
```

The final implementation may combine automatic proving and settlement behind
one operator process. Status must still expose each distinct stage: app final,
witness ready, proof generated, proof verified, root submitted, root stable,
withdrawal submitted, and withdrawal stable.

## Network progression

| Network | Preview use |
|---|---|
| Yano local devnet | Default. Automatically create unsafe development keys, deploy contracts, fund demo wallets, and permit disposable reset. |
| Cardano Preview | Early public-network interoperability and upgrade testing with test ADA. Operator supplies funded keys and accepts network fees and timing. |
| Cardano Preprod | Mainnet-like final rehearsal with pinned protocol parameters, script identities, rollback tests, and a persistent project lock. |
| Cardano mainnet | Disabled for the preview capability. The implementation can remain network-neutral, but mainnet activation requires the production ceremony, audits, operational evidence, and a separate release decision. |

## Scaling beyond four payments

Four payments are a feasibility bound, not the intended scalability ceiling.
The bound is compiled into the circuit and therefore identifies a distinct
profile, proving key, verification key, ceremony, and validator deployment.
It must not be changed through an ordinary runtime property.

The next preview should benchmark fixed profiles such as 16, 32, and 64
transactions. Increasing the bound has different effects:

- Groth16 proof size and the number of public settlement inputs can stay
  constant.
- L1 proof verification is approximately independent of the number of
  transactions when the same public-input shape is retained.
- circuit constraints, proving-key size, prover memory, and proof time grow
  with the admitted transaction work;
- canonical L1 batch data and its hashing cost grow with the batch; and
- Cardano transaction-size and execution-budget limits constrain the final
  profile.

The default must be selected from measured Yano devnet, Preview, and Preprod
results. Recursive proof aggregation is a later optimization, not a
prerequisite for the first preview.

## L1 and L2 transaction compatibility

Keep two protocols separate:

| Boundary | Transaction | Authorization |
|---|---|---|
| Cardano L1 | Ordinary ledger-CDDL Cardano transaction | Ed25519 VKey witness |
| Yano L2 | Canonical Yano envelope containing a Cardano-compatible transaction body | Jubjub session-key signature |

Deposits, key registration/recovery, proof publication, and withdrawals remain
ordinary Cardano transactions. Jubjub keys and signatures never appear in a
Cardano VKey witness set and are never published as individual L1
transactions.

Cardano tooling such as Cardano Client Lib can still construct the L2
transaction body:

- construct a Conway transaction body;
- refer to appchain EUTxOs with ordinary transaction-hash/index inputs;
- create ordinary Cardano outputs and inline datums; and
- calculate the standard Cardano transaction-body hash.

The Yano client adapter then wraps that body with the L2 replay domain and
Jubjub authorizations. It supplies appchain UTxO lookup, selected profile
limits, zero or configured L2 fees, current stable L1 slot, submission, and
receipt queries. Existing CCL body builders remain reusable; a normal Cardano
transaction signer is used only for L1 operations.

### L2 protocol parameters

Every EUTxO appchain exposes one immutable, Cardano-shaped L2 protocol
parameter snapshot. Cardano Client Lib can consume it through its existing
`ProtocolParamsSupplier`; the appchain UTxO query adapter implements the
existing `UtxoSupplier`.

The initial L2 snapshot should include:

- `minFeeA: 0` and `minFeeB: 0`;
- zero stake, pool, governance, and other unsupported deposits;
- the EUTxO profile's maximum transaction size, input/output bounds, value
  size, and execution-unit bounds;
- zero collateral requirements for profiles that prohibit failed-script
  transitions and collateral fields;
- the exact admitted Plutus V3 cost model for script-enabled profiles; and
- a protocol/profile version and canonical digest.

These are L2 consensus parameters, not the Cardano network's current L1
parameters. Every member must materialize the same values in its authenticated
consensus profile and project lock. They cannot be overridden independently
per node. An L2 parameter change requires a new versioned profile or a
separately defined governed activation boundary.

The L1 transaction builder for deposits, proof/root publication, and
withdrawals must continue to use the selected Cardano network's live or pinned
L1 protocol parameters, including real L1 fees and minimum-UTxO requirements.
The client APIs and Java types must name these sources explicitly so zero-fee
L2 parameters can never be used to build an L1 settlement transaction.

The intended client surface reuses Yano's generic appchain submission and
query APIs. A separate transaction-submission REST endpoint is unnecessary:

```text
GET /api/v1/app-chain/chains/{chainId}/eutxo/protocol-parameters
GET /api/v1/app-chain/chains/{chainId}/eutxo/utxos?address={address}
POST /api/v1/app-chain/chains/{chainId}/messages
```

The submission request carries the canonical L2 envelope:

```json
{
  "topic": "eutxo.transactions",
  "bodyHex": "<canonical-yano-eutxo-l2-envelope>"
}
```

This endpoint already returns HTTP `202` with the app-message ID. That means
the message has been admitted to the appchain message pool; it does not mean
that the EUTxO transition is final. The client subsequently queries
`transactions/receipt` by Cardano transaction ID, or `attempts/receipt` by
app-message ID, to distinguish accepted, rejected, and finalized outcomes.

Programmatic body construction remains standard Cardano Client Lib. The Yano
adapter signs the envelope after the body is built:

```java
UtxoSupplier l2Utxos = yanoEutxoClient.utxoSupplier();
ProtocolParamsSupplier l2Params =
        yanoEutxoClient.protocolParamsSupplier();
TxBuilderContext context =
        TxBuilderContext.init(l2Utxos, l2Params);

EutxoL2Submission submitted =
        yanoEutxoClient.signAndSubmit(transactionBody, jubjubSessionKey);
```

The adapter delegates submission to the existing generic message endpoint on
topic `eutxo.transactions`. Its result keeps three identities distinct: the
L2 transaction ID, the contained Cardano body hash, and Yano's outer
app-message ID. HTTP `202` means message-pool admission only.

Because Cardano Client Lib's `TransactionProcessor` also extends
`TransactionEvaluator`, the adapter must provide profile-correct evaluation.
Key-only profiles can return an empty execution-unit result after deterministic
preflight validation. A Plutus-enabled profile must evaluate against the same
root-fixed L2 UTxO snapshot, L2 protocol parameters, cost model, and stable L1
slot used by consensus; it must not silently delegate to an L1 evaluator.

The protocol-parameter response carries the chain ID, EUTxO profile,
validity-profile identity, parameter digest, and Cardano-compatible
`ProtocolParams` fields. Clients must reject a response whose identities do
not match the project lock.

Format compatibility does not mean that every Cardano ledger feature is
enabled. The first proof profile should fail closed on fields outside its
reviewed subset. The initial subset is:

- canonical L2 envelope and Conway transaction-body encoding;
- key-controlled lovelace inputs and outputs;
- registered Jubjub session-key authorization;
- bounded input and output counts;
- zero L2 fee;
- validity start and expiry slots; and
- optional inline datum only when selected by the profile.

Minting, native assets, staking, governance, certificates, withdrawals,
reference inputs/scripts, collateral, and general Plutus execution remain
unsupported until separate circuit profiles prove them.

The L2 envelope binds the chain ID, network, ledger profile, validity profile,
authorization profile, body hash, key epoch, 32-byte command nonce, and
expiry. Runtime admission rejects a missing or mismatched domain before
changing EUTxO state.

The experimental `zeroj-jubjub-dev-v1` circuit proves the Jubjub signature
equation over the exact envelope signing commitment. Deterministic host
validation enforces canonical point encodings, group checks, active
registration, supported Cardano-body semantics, ownership, conservation, and
the finalized transition/root. This split is allowed only with a trusted
sequencer/prover and disposable test funds. A hardened successor must
constrain all security-critical semantics against an adversarial prover and
must use new circuit, ceremony, key, validator, and lock identities.

## Cardano wallet and L2 authorization

The Cardano wallet remains the L1 identity and gatekeeper. It signs a normal
Cardano registration or deposit transaction that binds its payment credential
to one L2 Jubjub public key and key epoch. High-frequency L2 transactions are
then signed by the corresponding Jubjub session key in the Yano client SDK.
The proof compresses those L2 authorizations; individual signatures do not
appear on L1.

Two session-key sources are part of the design:

1. a randomly generated encrypted key, with explicit backup, rotation,
   revocation, and L1 recovery; or
2. an optional deterministic key derived from one domain-separated CIP-8
   `signData` result.

The deterministic option needs wallet compatibility and security testing. Its
derivation input must bind the appchain, network, profiles, account, purpose,
and version. The SDK must never log the signature, derived scalar, or key
material, and a wallet whose `signData` output is not stable for the same
request must use the random-key flow. No Cardano private key is exposed to the
prover.

## Preview graduation criteria

The capability can move from `experimental` to `preview` only after:

- the proof witness is deterministically derived from finalized,
  runtime-accepted signed transactions;
- the circuit proves input membership/non-reuse, authorization, value
  conservation, output creation, and the exact validity-root transition;
- the L1 deposit, root, data-availability, vault, and withdrawal validators
  are deployed and exercised end to end on Yano devnet;
- the prover and permissionless relay are packaged and operated by the stock
  JVM distribution;
- `./yano.sh appchain cluster` can launch the generated project and report all
  settlement stages;
- the same flow passes on Cardano Preview and then Preprod with test ADA;
- restart, L1 rollback, duplicate relay, unavailable prover, and unavailable
  operator tests pass; and
- all output and documentation continue to reject production-funds claims.
