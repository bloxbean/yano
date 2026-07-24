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

## Cardano transaction-format compatibility

The preview must keep canonical Cardano transaction CBOR as the submitted
EUTxO transaction format. The ZeroJ profile defines which Cardano transaction
semantics it proves; it does not define a replacement payment wire format.

Consequently, Cardano tooling such as Cardano Client Lib can continue to:

- construct a Conway transaction body;
- refer to appchain EUTxOs with ordinary transaction-hash/index inputs;
- create ordinary Cardano outputs and inline datums;
- calculate the standard Cardano transaction-body hash;
- produce an ordinary VKey witness set; and
- serialize the result as canonical Cardano transaction CBOR.

An appchain SDK adapter supplies the changing pieces: appchain UTxO lookup,
the selected profile limits, zero or configured L2 fees, current L1 slot,
submission, and receipt queries. Existing builders and signing providers do
not need a Yano-specific transaction model.

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

The submission request carries the canonical signed transaction without
re-encoding it into a Yano-specific transaction model:

```json
{
  "topic": "eutxo.transactions",
  "bodyHex": "<canonical-signed-cardano-transaction-cbor>"
}
```

This endpoint already returns HTTP `202` with the app-message ID. That means
the message has been admitted to the appchain message pool; it does not mean
that the EUTxO transition is final. The client subsequently queries
`transactions/receipt` by Cardano transaction ID, or `attempts/receipt` by
app-message ID, to distinguish accepted, rejected, and finalized outcomes.

Programmatic construction remains standard Cardano Client Lib:

```java
UtxoSupplier l2Utxos = yanoEutxoClient.utxoSupplier();
ProtocolParamsSupplier l2Params =
        yanoEutxoClient.protocolParamsSupplier();
TransactionProcessor l2Transactions =
        yanoEutxoClient.transactionProcessor();

TxBuilderContext context =
        TxBuilderContext.init(l2Utxos, l2Params);

Result<String> submitted =
        l2Transactions.submitTransaction(signedTransaction.serialize());
```

The `TransactionProcessor` adapter delegates submission to the existing
`EutxoClient.submit(byte[])`, which posts to the generic message endpoint on
topic `eutxo.transactions`. On HTTP `202`, its `Result<String>.value` is the
standard Cardano transaction-body hash calculated from the submitted CBOR,
not Yano's outer app-message ID. The adapter retains the app-message ID as
correlation metadata so callers can inspect an individual rejected attempt.

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

- Conway transaction/body/witness-set encoding;
- key-controlled lovelace inputs and outputs;
- standard Ed25519 VKey witnesses;
- bounded input and output counts;
- zero L2 fee;
- validity start and expiry slots; and
- optional inline datum only when selected by the profile.

Minting, native assets, staking, governance, certificates, withdrawals,
reference inputs/scripts, collateral, and general Plutus execution remain
unsupported until separate circuit profiles prove them.

The circuit must be constrained to the exact canonical transaction bytes. It
must verify the transaction-body hash, supported-field decoding, input
membership and non-reuse, VKey authorization, conservation, and output
creation. Merely letting the runtime parse Cardano CBOR while the circuit
proves a separate amount tuple is insufficient.

Cardano's transaction body has a network identifier but no Yano appchain ID.
The supported profile therefore also needs a standard Cardano auxiliary-data
entry whose hash is carried by the transaction body and which binds at least
the appchain ID, EUTxO profile, validity profile, and command nonce. Cardano
SDKs already support transaction metadata, so this provides replay-domain
separation without changing the Cardano transaction format.

## Cardano wallet authorization

The base EUTxO state machine already verifies standard Cardano VKey witnesses
over transaction-body hashes. The current ZK circuit does not prove that
verification; it uses a Poseidon owner-secret commitment instead. A prover
must never require a user's Cardano private key.

Two wallet-compatible validity profiles are useful:

1. **Direct Cardano signature profile.** Cardano Client Lib, `cardano-cli`,
   or a compatible CIP-30 wallet signs the canonical Cardano transaction
   body. The public key, VKey signature, and transaction bytes become proof
   witness data, and the circuit proves the Ed25519 verification and
   payment-credential binding. This is the simplest trust model but is
   expensive when repeated for every transaction.
2. **Wallet-authorized session profile.** The Cardano wallet signs a bounded,
   expiring delegation to a ZK-friendly session key. The registration
   transition proves the Cardano signature once; subsequent appchain
   transactions use the cheaper session-key signature. This preserves wallet
   control while providing better throughput.

Backend and programmatic wallets can sign the standard transaction directly.
Browser-wallet behavior needs compatibility testing: a CIP-30 wallet may
refuse `signTx` when it cannot find virtual appchain inputs on Cardano L1. If
that prevents portable browser support, `CIP-30 signData` can authorize the
standard Cardano transaction body hash as a companion envelope; it does not
replace the Cardano transaction payload. The signed payload must bind the
appchain ID, network, profiles, transaction-body hash, nonce, and expiry to
prevent cross-chain and replay use.

ZeroJ currently contains the SHA-512, Blake2b, Ed25519 point, BIP32-Ed25519,
and CIP-1852 derivation building blocks. A complete audited RFC 8032
signature-verification gadget and its cost benchmark are still required for
the direct profile.

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
