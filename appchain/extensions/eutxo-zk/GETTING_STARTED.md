# EUTxO ZeroJ validity settlement: getting started

For a protocol-oriented, step-by-step walkthrough of project generation,
three-member cluster startup, the devnet faucet, L1 deposit publication, L2
Jubjub submission, proof generation, L1 root settlement, and proof withdrawal,
see [EUTxO ZK rollup on Yano devnet](DEVNET_WALKTHROUGH.md).

## Current status

The current capability is an experimental, JVM-only testnet lifecycle. The
stock JVM distribution carries the EUTxO state machine, the ZeroJ validity
provider, project generation, a measured 16-transaction development circuit,
proof tooling, deterministic Cardano contract plans, and durable operation
journals for deposit, settlement, withdrawal, and recovery.

It deliberately separates what Yano can automate from transactions that an
operator must authorize:

- Yano generates and verifies the project, circuit, ceremony, proof, contract
  plan, and content-addressed operation state.
- An external Cardano builder/wallet constructs and signs each L1 transaction.
- Yano submits the signed CBOR, records its transaction ID, and reconciles the
  stable result without persisting credentials.

Yano devnet now has a source-level live deposit-to-withdrawal test. It creates
and accepts a real L1 staging deposit, waits for stable mirrored L2 credit,
spends that exact EUTxO, proves the finalized transition, advances the L1
validity root, atomically spends the proof controller and accepted custody
funds, pays the Cardano withdrawal, and waits for stable L2 reconciliation.
Preview and Preprod, maximum-batch, restart/rollback, and independent
reconstruction evidence remain open; the lifecycle must not be described as
a production-ready rollup.

The integrated development prover fails closed when the settlement context or
ordered transaction-manifest commitment does not match the finalized batch.
Its aggregate withdrawal is still a trusted-prover, host-supplied public
input; it is not yet derived from Cardano-shaped transaction CBOR
in-circuit. The smoke test proves lifecycle and validator interoperability,
not permissionless withdrawal security.

Do not use this profile with real funds. The commands in
[Current executable developer flow](#current-executable-developer-flow) are
available now. The only supported authorization profile,
`zeroj-jubjub-dev-v1`, requires a trusted sequencer/prover and disposable test
funds. Mainnet is rejected unconditionally.

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
  :appchain-eutxo-zk-testkit:test \
  :appchain-eutxo-zk-lifecycle:test \
  :appchain-eutxo-zk-runtime:check
```

This exercises:

- deterministic dual-root EUTxO state updates;
- a single-participant development ceremony;
- real Groth16 BLS12-381 proof generation and verification;
- durable proof jobs and prover failover;
- proof-bound canonical batch data;
- Julc Plutus V3 validity-root and withdrawal validators; and
- reconstruction and recovery behavior.

The module tests execute the boundaries in one build, but they do not deploy
the validators or submit transactions to a live Cardano network.

### 2. Run the live Yano devnet validity-settlement smoke test

```bash
./gradlew :app:e2eTest \
  --tests \
  'com.bloxbean.cardano.yano.app.e2e.EutxoZkRollupDevnetE2ETest' \
  --rerun-tasks
```

The test uses disposable funds and a fresh development ceremony. It verifies:

- a real 10 ADA L1 staging deposit accepted into the custody vault;
- stable L1 observation and exact-outpoint mirrored L2 credit;
- a real Cardano-shaped L2 transaction through the generic app-chain REST
  submission path, creating a 3 ADA withdrawal and 7 ADA change;
- app-chain finalization and exact finalized-transition witness derivation;
- a real Groth16 proof bound to the runtime validity-root transition;
- actual Julc Plutus V3 staging, root, and vault scripts on Yano devnet L1;
- stable root advancement; and
- a proof-authorized 3 ADA payout from the same accepted custody funds,
  followed by stable withdrawal reconciliation on L2.

### 3. Build or extract a JVM distribution

From source:

```bash
./gradlew :app:yanoDistZip
unzip app/build/distributions/yano-*.zip -d build/yano-eutxo-preview
cd build/yano-eutxo-preview/yano-*
```

From a release, extract `yano-{version}.zip` and enter its directory. All
remaining commands use that directory's `./yano.sh`.

### 4. Generate the local L2 session identity

Create a password through your normal local secret mechanism and export only
its environment-variable name to the command. For disposable development:

```bash
export YANO_L2_KEY_PASSWORD='<development password of at least 12 characters>'
./yano.sh appchain validity key generate \
  --output l2-session-key.enc \
  --password-env YANO_L2_KEY_PASSWORD
```

The command refuses to overwrite an existing file, writes an encrypted
owner-only key envelope, and prints JSON containing its 64-hex-character
Jubjub public key. It never prints the private scalar or password. Keep the
encrypted file outside generated project configuration and backups intended
for public sharing.

Choose the Cardano-formatted testnet address whose payment credential will own
deposited L2 EUTxOs. The project records that address, the public key, and key
epoch 1 as a fundless genesis registration. This does not mint L2 value;
spendable value arrives only through a stable accepted L1 deposit.

### 5. Generate a devnet project

```bash
./yano.sh appchain init --non-interactive \
  --recipe eutxo-zeroj-preview \
  --network devnet \
  --members 3 \
  --runtime jvm \
  --deployment host \
  --name payments-zk \
  --chain-id payments-zk \
  --member-key <node-0-public-member-key> \
  --member-key <node-1-public-member-key> \
  --member-key <node-2-public-member-key> \
  --answer eutxoL2Address=<testnet-address-for-the-L2-owner> \
  --answer eutxoL2PublicKey=<publicKey-from-key-generate> \
  --answer bridgeVaultAddress=<accepted-custody-script-address> \
  --answer bridgeVaultScriptHash=<56-hex-script-hash> \
  --answer bridgeMaxDepositLovelace=100000000 \
  --answer bridgeWithdrawalAddress=<ordinary-testnet-payout-address> \
  --answer bridgeEpoch=1 \
  --answer bridgeMaxWithdrawalLovelace=50000000 \
  --answer bridgeMaxPendingWithdrawals=100 \
  --output payments-zk
```

Preview and Preprod require a durable acknowledgement in the generated
blueprint and lock:

```bash
./yano.sh appchain init --non-interactive \
  --recipe eutxo-zeroj-preview \
  --network preview \
  --members 3 \
  --runtime jvm \
  --deployment host \
  --name payments-zk-preview \
  --chain-id payments-zk-preview \
  --member-key <node-0-public-member-key> \
  --member-key <node-1-public-member-key> \
  --member-key <node-2-public-member-key> \
  --answer eutxoL2Address=<testnet-address-for-the-L2-owner> \
  --answer eutxoL2PublicKey=<publicKey-from-key-generate> \
  --answer bridgeVaultAddress=<accepted-custody-script-address> \
  --answer bridgeVaultScriptHash=<56-hex-script-hash> \
  --answer bridgeMaxDepositLovelace=100000000 \
  --answer bridgeWithdrawalAddress=<ordinary-testnet-payout-address> \
  --answer bridgeEpoch=1 \
  --answer bridgeMaxWithdrawalLovelace=50000000 \
  --answer bridgeMaxPendingWithdrawals=100 \
  --acknowledge EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET \
  --output payments-zk-preview
```

Omitting the acknowledgement fails closed. Selecting mainnet also fails
closed and cannot be bypassed with an acknowledgement or hidden flag.

### 6. Inspect the contract plan and bootstrap development proof keys

Plan-only bootstrap is fast and does not generate secret proving material:

```bash
./yano.sh appchain validity bootstrap --project payments-zk
./yano.sh appchain validity status --project payments-zk
```

For a disposable local ceremony:

```bash
./yano.sh appchain validity bootstrap \
  --project payments-zk \
  --development-ceremony \
  --yes

./yano.sh appchain validity doctor --project payments-zk
```

The b16 setup needs several gigabytes of heap and can take roughly two minutes
on a developer workstation. The packaged tooling reserves a 4 GiB maximum
heap. The generated proving key is owner-only where POSIX permissions are
available and is never copied into the project lock.

The command writes:

- `runtime/validity/contract-plan.json` — deterministic, pinned contract plan;
- `runtime/validity/ceremony/` — development proving/verification artifacts;
- `runtime/validity/state.json` — lifecycle identity and stage; and
- `runtime/validity/operations/` — durable content-addressed operation
  journals.

### 7. Prove finalized L2 transitions

Export each finalized `EutxoValidityTransition` as canonical binary and pass
the exact ordered files:

```bash
./yano.sh appchain validity prove \
  --project payments-zk \
  --previous-root <64-hex-root> \
  --transition transition-0001.cbor \
  --transition transition-0002.cbor
```

The result returns a proof digest. Independently reload and verify it with:

```bash
./yano.sh appchain validity proof <proof-digest> \
  --project payments-zk
```

Proof generation refuses a transition from another chain/network, an
unmeasured batch profile, a mismatched ceremony/VK identity, an invalid
authorization, or more than 16 transitions.

### 8. Prepare, submit, and reconcile L1 operations

Prepare an idempotent operation from a non-secret JSON request:

```bash
./yano.sh appchain validity deposit prepare \
  --project payments-zk \
  --id deposit-0001 \
  --request deposit-0001.json

./yano.sh appchain validity settlement prepare \
  --project payments-zk \
  --id settlement-0001 \
  --proof <proof-digest>

./yano.sh appchain validity withdrawal prepare \
  --project payments-zk \
  --id withdrawal-0001 \
  --proof <proof-digest>
```

Build and sign the corresponding ordinary Cardano transaction with Cardano
Client Lib, `cardano-cli`, or a wallet-backed service. Then submit its raw
signed CBOR through Yano:

```bash
./yano.sh appchain validity settlement submit \
  --project payments-zk \
  --id settlement-0001 \
  --tx settlement-0001.signed.cbor \
  --url http://127.0.0.1:7070
```

If the node requires an API key, pass only its environment-variable name:

```bash
export YANO_L1_API_KEY='<secret>'
./yano.sh appchain validity settlement submit \
  --project payments-zk \
  --id settlement-0001 \
  --tx settlement-0001.signed.cbor \
  --url https://node.example \
  --api-key-env YANO_L1_API_KEY
```

The value is neither logged nor persisted. After an independently observed
stable Cardano result:

```bash
./yano.sh appchain validity settlement stable \
  --project payments-zk \
  --id settlement-0001 \
  --tx-id <64-hex-cardano-tx-id>

./yano.sh appchain validity reconcile --project payments-zk
```

The same `submit` and `stable` shape applies to `deposit`, `withdrawal`, and
`recovery`. Settlement and withdrawal preparation both require the proof
digest, ensuring their journals retain the same previous/next root,
batch-data, withdrawal amount, and finalized transaction identities.
Repeating a completed step returns the retained result instead of creating a
duplicate operation.

### 9. Run the standalone proof and validator examples

The bounded proof regression is:

```bash
./gradlew :appchain-eutxo-zk-zeroj:test \
  --tests \
  'com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoJubjubBatchCircuitTest.b16MaximumBatchProducesOneRealConstantSizeProof' \
  --rerun-tasks
```

The proof-bound L1 validator regression is:

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

## Lifecycle boundary and target live flow

The modules and commands now model this flow:

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

The lifecycle currently automates project safety, artifact identity, proof
generation/verification, transaction submission, journals, and
reconciliation. The executable Yano-devnet test additionally builds and signs
transactions with disposable test keys and checks actual L1 stability. The
packaged operator lifecycle intentionally does not hold keys or silently
claim deployment: the operator supplies externally signed Cardano CBOR, and a
plan-only bootstrap is reported as `PLANNED_NOT_SUBMITTED`.

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

The implementation defines fixed 16, 32, and 64 transaction identities.
Only `cardano-payment-b16` has a real local Groth16 measurement and is the
development default. The b32 and b64 identities are unmeasured candidates and
cannot be selected. Increasing the bound has different effects:

- Groth16 proof size and the number of public settlement inputs can stay
  constant.
- L1 proof verification is approximately independent of the number of
  transactions when the same public-input shape is retained.
- circuit constraints, proving-key size, prover memory, and proof time grow
  with the admitted transaction work;
- canonical L1 batch data and its hashing cost grow with the batch; and
- Cardano transaction-size and execution-budget limits constrain the final
  profile.

The b16 run produced 383,495 constraints and a constant 192-byte proof; on the
recorded local Java 25 run, single-party development setup took 109.263
seconds and proof generation took 15.058 seconds. See
[D3_BATCH_PROFILES.md](D3_BATCH_PROFILES.md) for the exact command and
unexercised measurements. A public-testnet default still requires Yano devnet,
Preview, and Preprod evidence. Recursive proof aggregation is a later
optimization, not a prerequisite for the first experimental release.

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

The client surface reuses Yano's generic appchain submission and query APIs.
A separate transaction-submission REST endpoint is unnecessary:

```text
POST /api/v1/app-chain/chains/{chainId}/query/protocol-parameters
POST /api/v1/app-chain/chains/{chainId}/query/utxos/address
POST /api/v1/app-chain/chains/{chainId}/messages
```

Query requests use the generic `{"paramsHex":"..."}` envelope. The typed Java
client handles the canonical codecs and retains the committed height and state
root returned by the node.

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

Programmatic body construction remains standard Cardano Client Lib. The
implemented D2 client signs the envelope after the body is built:

```java
AppChainClient appchain = AppChainClient.builder(
        "http://127.0.0.1:7070/api/v1")
    .chainId("payments-zk")
    .build();
EutxoClient eutxo = new EutxoClient(appchain);
EutxoL2ProtocolParameters l2Params =
        EutxoL2ProtocolParameters.from(
                eutxo.l2ParametersSnapshot().value());
EutxoL2Client yanoEutxoClient =
        new EutxoL2Client(eutxo, l2Params);
UtxoSupplier l2Utxos = yanoEutxoClient.utxoSupplier();
TxBuilderContext context =
        TxBuilderContext.init(l2Utxos, l2Params);

try (EutxoL2SessionKey key =
        EutxoL2SessionKey.decrypt(encryptedKey, password)) {
    EutxoL2Transaction transaction =
            EutxoL2TransactionBuilder.sign(
                    domain,
                    transactionBody,
                    List.of(new EutxoL2TransactionBuilder.Signer(
                            paymentCredential,
                            keyEpoch,
                            List.of(0),
                            key)));
    EutxoL2Client.Submission submitted =
            yanoEutxoClient.submit(transaction);
}
```

The adapter delegates submission to the existing generic message endpoint on
topic `eutxo.transactions`. Its result keeps three identities distinct: the
L2 transaction ID, the contained Cardano body hash, and Yano's outer
app-message ID. HTTP `202` means message-pool admission only.

`EutxoRootFixedUtxoSupplier` pins its first read to one committed state root
and rejects later reads from a different root. `EutxoL2SessionKey` supports a
random key encrypted locally with AES-256-GCM/PBKDF2 and an optional
domain-separated CIP-8-signature derivation. Its string representation is
always redacted and closing it clears the retained scalar bytes.

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
