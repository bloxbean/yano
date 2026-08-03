# EUTxO ZK rollup on Yano devnet

This walkthrough shows the complete experimental flow:

```text
Yano devnet faucet
  -> ordinary signed Cardano staging transaction
  -> ordinary signed Cardano deposit-acceptance transaction
  -> stable accepted L1 vault output
  -> mirrored EUTxO on the payments-zk appchain
  -> zero-fee Cardano-shaped L2 transaction
  -> Jubjub session-key authorization
  -> appchain finalization
  -> ZeroJ Groth16 proof
  -> ordinary signed Cardano validity-root transaction
  -> ordinary signed Cardano proof-withdrawal transaction
  -> stable payout on L1
  -> confirmed withdrawal record on L2
```

The capability is experimental. Use only an isolated Yano devnet and
disposable keys and funds. The current `zeroj-jubjub-dev-v1` profile requires
a trusted sequencer/prover. It is not a production or mainnet rollup.

## Contents

1. [Understand the command boundaries](#1-understand-the-command-boundaries)
2. [Run the proven end-to-end reference first](#2-run-the-proven-end-to-end-reference-first)
3. [Build or extract Yano](#3-build-or-extract-yano)
4. [Prepare disposable identities](#4-prepare-disposable-identities)
5. [Create the three-member project](#5-create-the-three-member-project)
6. [Provision and start the cluster](#6-provision-and-start-the-cluster)
7. [Bootstrap the development ceremony](#7-bootstrap-the-development-ceremony)
8. [Fund the Cardano operator](#8-fund-the-cardano-operator)
9. [Publish and accept the L1 deposit](#9-publish-and-accept-the-l1-deposit)
10. [Confirm the mirrored L2 EUTxO](#10-confirm-the-mirrored-l2-eutxo)
11. [Build and publish the L2 withdrawal transaction](#11-build-and-publish-the-l2-withdrawal-transaction)
12. [Export and prove the finalized transition](#12-export-and-prove-the-finalized-transition)
13. [Publish the proof and root on L1](#13-publish-the-proof-and-root-on-l1)
14. [Publish the L1 proof withdrawal](#14-publish-the-l1-proof-withdrawal)
15. [Verify the complete round trip](#15-verify-the-complete-round-trip)
16. [Stop, restart, and clean up](#16-stop-restart-and-clean-up)
17. [Move the same workflow to Preview or Preprod](#17-move-the-same-workflow-to-preview-or-preprod)
18. [Troubleshooting](#18-troubleshooting)

## 1. Understand the command boundaries

The product deliberately separates orchestration from key custody:

| Operation | Tool |
|---|---|
| Generate project, config, lock, and cluster scripts | `./yano.sh appchain init` |
| Validate project and distribution | `./yano.sh appchain doctor` |
| Run the generated same-machine cluster | `<project>/scripts/start` |
| Generate the encrypted Jubjub L2 key | `./yano.sh appchain validity key generate` |
| Submit/query a canonical L2 envelope | `./yano.sh appchain eutxo ...` |
| Generate/verify a ZeroJ proof | `./yano.sh appchain validity prove|proof` |
| Journal and submit externally signed L1 CBOR | `./yano.sh appchain validity <kind> ...` |
| Construct and sign Cardano L1 transactions | Cardano Client Lib, a wallet service, or another Cardano builder |

`./yano.sh appchain cluster start` launches the stock demonstration cluster.
It does not launch an `appchain init` project. A generated project has its own
digest-protected `scripts/start`, `scripts/status`, and `scripts/stop`.

Yano does not persist a Cardano payment mnemonic or private key. Consequently,
the `validity` lifecycle cannot silently deploy or sign an L1 transaction.
It records a deterministic operation, accepts already signed Cardano CBOR,
submits it to Yano's Blockfrost-compatible endpoint, and retains only the
transaction ID and CBOR digest.

This guide shows both publication paths:

```bash
# Journalled L1 publication
./yano.sh appchain validity deposit submit \
  --project "$PROJECT" \
  --id deposit-0001 \
  --tx deposit-acceptance.signed.cbor \
  --url "$NODE"

# Direct publication of the same ordinary signed Cardano CBOR
curl --fail-with-body --silent --show-error \
  -H 'Content-Type: application/cbor' \
  --data-binary @deposit-acceptance.signed.cbor \
  "$API/tx/submit"
```

Use the first form for an operator run because it provides an idempotent
project journal. Use the direct endpoint when developing or debugging an
external Cardano builder.

## 2. Run the proven end-to-end reference first

The source tree contains the exact live reference implementation. It uses a
real Yano devnet L1, real Cardano transactions, Julc-compiled Plutus V3
validators, a real Groth16 proof, and the generic appchain REST path:

```bash
./gradlew :app:e2eTest \
  --tests \
  'com.bloxbean.cardano.yano.app.e2e.EutxoZkRollupDevnetE2ETest' \
  --rerun-tasks
```

The successful run logs one line resembling:

```text
EUTXO_ZK_DEVNET_ROUND_TRIP_PASS
  deposit=<L1-tx-id>
  bootstrap=<L1-tx-id>
  settlement=<L1-tx-id>
  withdrawal=<L1-tx-id>
  proof=<proof-digest>
  profile=cardano-payment-b16
  root=<next-validity-root>
  claim=<withdrawal-claim-id>
```

This is the fastest way to observe the full protocol today. The manual
sections below expose the same boundaries one operation at a time. When
implementing an external Cardano builder, use
`app/src/test/java/com/bloxbean/cardano/yano/app/e2e/`
`EutxoZkRollupDevnetE2ETest.java` as the executable Cardano Client Lib
reference for script compilation, datum/redeemer construction, fees,
collateral, reference scripts, and signing.

## 3. Build or extract Yano

From source:

```bash
./gradlew :app:yanoDistZip
mkdir -p build/eutxo-zk-devnet
unzip app/build/distributions/yano-*.zip -d build/eutxo-zk-devnet
cd build/eutxo-zk-devnet/yano-*
```

Alternatively, extract the JVM `yano-{version}.zip` release and enter its
root. The JVM distribution is required for this experimental capability.

Set stable paths before creating the project:

```bash
export YANO_HOME="$PWD"
export WORK="$PWD/eutxo-zk-work"
export PROJECT="$WORK/payments-zk"
export CHAIN_ID=payments-zk
export NODE=http://127.0.0.1:7070
export API="$NODE/api/v1"
mkdir -p "$WORK/artifacts"
```

Do not move `YANO_HOME` or `PROJECT` while the cluster is running.

## 4. Prepare disposable identities

There are three different identities:

| Identity | Purpose | Secret location |
|---|---|---|
| Appchain member Ed25519 keys | Propose and finalize app blocks | `secrets/nodeN.env` |
| Cardano Ed25519 account | Authorize L1 deposits and operator transactions | External Cardano builder/wallet |
| Jubjub session key | Authorize high-frequency L2 transactions | Encrypted local key file |

### 4.1 Development appchain members

Use these known values only on a disposable local devnet:

| Node | Private seed | Public member key |
|---:|---|---|
| 0 | 32 bytes of `01` | `8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c` |
| 1 | 32 bytes of `02` | `8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394` |
| 2 | 32 bytes of `03` | `ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1` |

```bash
export MEMBER_0=8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c
export MEMBER_1=8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394
export MEMBER_2=ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1
```

### 4.2 Cardano operator, payout, and accepted-vault identity

Create disposable testnet accounts in a Cardano Client Lib application or
wallet. The accepted custody address must be a script address. The live
reference uses a native `ScriptPubkey` controlled by the disposable operator:

```java
Account operator = new Account(Networks.testnet());
Account payout = new Account(Networks.testnet());

ScriptPubkey fundsVaultScript = ScriptPubkey.create(
        VerificationKey.create(operator.publicKeyBytes()));
String fundsVaultAddress = AddressProvider.getEntAddress(
        fundsVaultScript, Networks.testnet()).toBech32();
String fundsVaultScriptHash = fundsVaultScript.getPolicyId();
String l2Address = operator.enterpriseAddress();
String payoutAddress = payout.enterpriseAddress();

System.out.println("operator mnemonic (secret): " + operator.mnemonic());
System.out.println("payout mnemonic (secret): " + payout.mnemonic());
System.out.println("L2_ADDRESS=" + l2Address);
System.out.println("PAYOUT_ADDRESS=" + payoutAddress);
System.out.println("VAULT_ADDRESS=" + fundsVaultAddress);
System.out.println("VAULT_SCRIPT_HASH=" + fundsVaultScriptHash);
```

The distribution's `yano.jar` contains these Cardano Client Lib classes, so
the snippet can also be evaluated with:

```bash
jshell --class-path "$YANO_HOME/yano.jar"
```

Import:

```java
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
```

Record these non-secret outputs:

```bash
export L2_ADDRESS='<operator enterprise addr_test...>'
export PAYOUT_ADDRESS='<payout addr_test...>'
export VAULT_ADDRESS='<accepted custody script addr_test...>'
export VAULT_SCRIPT_HASH='<56 lowercase hex characters>'
```

Keep the operator and payout mnemonics outside the Yano project. The operator
must remain able to sign the native script during this development flow.

### 4.3 Jubjub L2 session key

```bash
export YANO_L2_KEY_PASSWORD='<development password of at least 12 characters>'

./yano.sh appchain validity key generate \
  --output "$WORK/l2-session-key.enc" \
  --password-env YANO_L2_KEY_PASSWORD \
  > "$WORK/l2-key-result.json"

export L2_PUBLIC_KEY="$(
  jq -r '.publicKey' "$WORK/l2-key-result.json"
)"
```

Expected status:

```text
L2_SESSION_KEY_CREATED
```

The encrypted key is not a Cardano key. Its public key is registered to the
payment credential of `L2_ADDRESS`; the private scalar signs only L2
envelopes.

## 5. Create the three-member project

Pin HTTP port 7070 explicitly. Generated host projects otherwise default to
8080.

```bash
./yano.sh appchain init --non-interactive \
  --recipe eutxo-zeroj-preview \
  --network devnet \
  --members 3 \
  --runtime jvm \
  --deployment host \
  --http-port-base 7070 \
  --server-port-base 13337 \
  --name payments-zk \
  --chain-id "$CHAIN_ID" \
  --member-key "$MEMBER_0" \
  --member-key "$MEMBER_1" \
  --member-key "$MEMBER_2" \
  --answer "eutxoL2Address=$L2_ADDRESS" \
  --answer "eutxoL2PublicKey=$L2_PUBLIC_KEY" \
  --answer "bridgeVaultAddress=$VAULT_ADDRESS" \
  --answer "bridgeVaultScriptHash=$VAULT_SCRIPT_HASH" \
  --answer bridgeMaxDepositLovelace=100000000 \
  --answer "bridgeWithdrawalAddress=$PAYOUT_ADDRESS" \
  --answer bridgeEpoch=1 \
  --answer bridgeMaxWithdrawalLovelace=50000000 \
  --answer bridgeMaxPendingWithdrawals=100 \
  --output "$PROJECT"
```

Validate the generated project and installed distribution:

```bash
./yano.sh appchain config validate --mode project "$PROJECT"
./yano.sh appchain doctor "$PROJECT" --distribution "$YANO_HOME"
```

Project validation must report `VALID_PROJECT`. Before bootstrap, `doctor`
normally reports `DOCTOR_WARNINGS`: it confirms that the JVM artifacts and
member identities are ready, while explicitly leaving the development
ceremony, contract identities, test-funds acknowledgement, and external L1
operator actions pending.

Review:

```bash
sed -n '1,240p' "$PROJECT/appchain.yaml"
sed -n '1,260p' "$PROJECT/appchain.lock"
sed -n '1,260p' "$PROJECT/config/shared-consensus.yaml"
```

The lock must contain the `eutxo-zeroj-preview` recipe, the measured
`cardano-payment-b16` profile, `zeroj-jubjub-dev-v1`, and
`disposable-test-funds-only`.

## 6. Provision and start the cluster

Copy each generated secret template and insert its matching disposable seed:

```bash
cp "$PROJECT/secrets/node0.env.example" "$PROJECT/secrets/node0.env"
cp "$PROJECT/secrets/node1.env.example" "$PROJECT/secrets/node1.env"
cp "$PROJECT/secrets/node2.env.example" "$PROJECT/secrets/node2.env"

python3 - "$PROJECT" <<'PY'
from pathlib import Path
import sys

project = Path(sys.argv[1])
seeds = ["01" * 32, "02" * 32, "03" * 32]
for node, seed in enumerate(seeds):
    path = project / "secrets" / f"node{node}.env"
    lines = path.read_text(encoding="utf-8").splitlines()
    rendered = [
        f"YANO_APPCHAIN_SIGNING_KEY={seed}"
        if line.startswith("YANO_APPCHAIN_SIGNING_KEY=")
        else line
        for line in lines
    ]
    path.write_text("\n".join(rendered) + "\n", encoding="utf-8")
    path.chmod(0o600)
PY
```

Start the generated cluster:

```bash
export YANO_HOME
"$PROJECT/scripts/start"
"$PROJECT/scripts/status"
```

Expected endpoints:

| Node | HTTP | Role |
|---:|---|---|
| 0 | `http://127.0.0.1:7070` | L1 producer and fixed appchain proposer |
| 1 | `http://127.0.0.1:7071` | L1 follower and appchain member |
| 2 | `http://127.0.0.1:7072` | L1 follower and appchain member |

Check readiness and the chain:

```bash
for port in 7070 7071 7072; do
  curl --fail --silent "http://127.0.0.1:$port/q/health/ready"
  echo
done

curl --fail --silent \
  "$API/app-chain/chains/$CHAIN_ID/status" | jq

./yano.sh appchain eutxo doctor \
  --url "$API" \
  --chain "$CHAIN_ID"
```

If a node does not become ready, inspect
`$PROJECT/logs/node0.log`, `node1.log`, and `node2.log`.

## 7. Bootstrap the development ceremony

First generate the non-secret contract plan:

```bash
./yano.sh appchain validity bootstrap --project "$PROJECT"
./yano.sh appchain validity status --project "$PROJECT"
```

Then generate the disposable single-participant development ceremony:

```bash
./yano.sh appchain validity bootstrap \
  --project "$PROJECT" \
  --development-ceremony \
  --yes

./yano.sh appchain validity doctor --project "$PROJECT"
```

The b16 setup can require several gigabytes of heap and roughly two minutes on
a development machine. The command creates:

```text
runtime/validity/contract-plan.json
runtime/validity/ceremony/
runtime/validity/state.json
```

The development proving key is secret local state. Do not commit or publish
the ceremony directory.

## 8. Fund the Cardano operator

The Yano devnet faucet creates an ordinary L1 UTxO:

```bash
curl --fail-with-body --silent --show-error \
  -X POST "$API/devnet/fund" \
  -H 'Content-Type: application/json' \
  -d "{\"address\":\"$L2_ADDRESS\",\"ada\":100}" | tee \
  "$WORK/artifacts/operator-funding.json"
```

Expected output includes the L1 transaction hash, output index, and lovelace.
Wait until it is queryable:

```bash
curl --fail --silent \
  "$API/addresses/$L2_ADDRESS/utxos" | jq
```

The faucet exists only in dev mode. Preview and Preprod use their normal
test-ADA faucets.

## 9. Publish and accept the L1 deposit

An accepted deposit is two ordinary Cardano transactions:

1. **Stage:** pay 10 ADA to the parameterized
   `DepositStagingValidator` with `EutxoStagingDatum`.
2. **Accept:** spend that staging output before its refund deadline and
   recreate the exact 10 ADA at `VAULT_ADDRESS` with `EutxoVaultDatum`.

The acceptance output, not the first staging output, is what the stable L1
observer mirrors to L2.

Create an audit request for the lifecycle journal:

```bash
cat > "$WORK/artifacts/deposit-0001.json" <<EOF
{
  "chainId": "$CHAIN_ID",
  "bridgeEpoch": 1,
  "ownerAddress": "$L2_ADDRESS",
  "acceptedVaultAddress": "$VAULT_ADDRESS",
  "lovelace": 10000000,
  "purpose": "disposable Yano devnet walkthrough"
}
EOF

./yano.sh appchain validity deposit prepare \
  --project "$PROJECT" \
  --id deposit-0001 \
  --request "$WORK/artifacts/deposit-0001.json"
```

The current lifecycle records the request file and digest; it does not parse
that JSON into a Cardano transaction. Build and sign the staging and
acceptance transactions in the external Cardano component. The canonical
reference is:

- `DepositStagingValidator` in
  `appchain-eutxo-bridge-onchain`;
- `EutxoStagingDatum` and `EutxoVaultDatum` in
  `appchain-eutxo-contracts`;
- `DepositAcceptanceBuilder` in `appchain-eutxo-bridge-cardano`; and
- `acceptDeposit(...)` in `EutxoZkRollupDevnetE2ETest`.

Publish the signed staging CBOR directly:

```bash
curl --fail-with-body --silent --show-error \
  -H 'Content-Type: application/cbor' \
  --data-binary @"$WORK/artifacts/deposit-staging.signed.cbor" \
  "$API/tx/submit"
```

Publish the signed acceptance CBOR through the journal:

```bash
DEPOSIT_TX_ID="$(
  ./yano.sh appchain validity deposit submit \
    --project "$PROJECT" \
    --id deposit-0001 \
    --tx "$WORK/artifacts/deposit-acceptance.signed.cbor" \
    --url "$NODE" |
  tee "$WORK/artifacts/deposit-submit.json" |
  jq -r '.details.transactionId'
)"

export DEPOSIT_TX_ID
```

Do not mark the operation stable merely because submission returned a
transaction ID. Wait until the accepted output is present in stable L1 state.
For the local walkthrough, confirm the transaction and output are visible:

```bash
curl --fail --silent "$API/txs/$DEPOSIT_TX_ID" | jq
curl --fail --silent "$API/txs/$DEPOSIT_TX_ID/utxos" | jq
```

After the configured stability depth has passed:

```bash
./yano.sh appchain validity deposit stable \
  --project "$PROJECT" \
  --id deposit-0001 \
  --tx-id "$DEPOSIT_TX_ID"
```

Record the accepted vault output index:

```bash
export ACCEPTED_OUTPOINT="$DEPOSIT_TX_ID#<accepted-vault-output-index>"
```

## 10. Confirm the mirrored L2 EUTxO

The bridge observer emits a deterministic deposit claim only after L1
stability. The appchain then creates one L2 output tied to the exact accepted
L1 outpoint.

Poll the L2 owner:

```bash
until ./yano.sh appchain eutxo utxo list "$L2_ADDRESS" \
    --url "$API" \
    --chain "$CHAIN_ID" \
    > "$WORK/artifacts/l2-utxos.json"; do
  sleep 1
done

jq . "$WORK/artifacts/l2-utxos.json"
```

Wait until the returned `utxos` array contains a record with origin
`L1_DEPOSIT`. Its `outpoint` is the input to the L2 transaction:

```bash
export L2_INPUT='<mirrored-transaction-id>#<index>'

./yano.sh appchain eutxo utxo get "$L2_INPUT" \
  --url "$API" \
  --chain "$CHAIN_ID" | jq
```

The L2 input normally preserves the accepted L1 outpoint identity. Always use
the committed deposit record or returned L2 record rather than assuming an
index.

## 11. Build and publish the L2 withdrawal transaction

The L2 transaction is not an L1 Cardano transaction. It contains:

- a canonical Cardano Conway transaction **body**;
- a Yano replay-protection domain;
- one or more Jubjub authorizations; and
- no Cardano VKey witness set and no L1 fee.

For a 10 ADA deposit, this walkthrough creates 7 ADA change and a 3 ADA
withdrawal claim. The withdrawal output carries `EutxoWithdrawalDatum`.

An application builds it with:

```java
EutxoL2ProtocolParameters parameters = EutxoL2ProtocolParameters.from(
        eutxo.l2ParametersSnapshot().value());

TransactionBody body = TransactionBody.builder()
        .inputs(List.of(new TransactionInput(inputTxId, inputIndex)))
        .outputs(List.of(changeOutput, withdrawalOutput))
        .fee(BigInteger.ZERO)
        .ttl(expirySlot)
        .networkId(NetworkId.TESTNET)
        .build();

EutxoL2Domain domain = new EutxoL2Domain(
        parameters.chainId(),
        "devnet",
        parameters.ledgerProfileDigest(),
        parameters.validityProfileDigest(),
        parameters.authorizationProfile(),
        parameters.authorizationProfileDigest(),
        commandNonce,
        expirySlot);

try (EutxoL2SessionKey key = EutxoL2SessionKey.decrypt(
        encryptedKey, password)) {
    EutxoL2Transaction transaction = EutxoL2TransactionBuilder.sign(
            domain,
            body,
            List.of(new EutxoL2TransactionBuilder.Signer(
                    paymentCredential, 1, List.of(0), key)));
    Files.write(output, transaction.canonicalBytes());
}
```

See `EutxoL2ClientSurfaceTest` for a compact builder example and
`l2Withdrawal(...)` in the live devnet test for the exact withdrawal datum.
Use the L2 parameter snapshot from the node; never use zero-fee L2 parameters
to build an L1 transaction.

Publish the resulting canonical envelope:

```bash
L2_SUBMISSION="$(
  ./yano.sh appchain eutxo transaction submit \
    "$WORK/artifacts/l2-withdrawal.cbor" \
    --url "$API" \
    --chain "$CHAIN_ID"
)"
printf '%s\n' "$L2_SUBMISSION" | tee \
  "$WORK/artifacts/l2-submission.json" | jq
```

The response contains the outer app message ID. HTTP acceptance means only
that the message entered the appchain pool.

The builder should also print or retain the L2 transaction ID:

```bash
export L2_TX_ID='<64-hex L2 transaction ID>'
```

Wait for deterministic execution:

```bash
until ./yano.sh appchain eutxo transaction status "$L2_TX_ID" \
    --url "$API" \
    --chain "$CHAIN_ID" \
    > "$WORK/artifacts/l2-receipt.json" &&
  jq -e '.receipt.status == "ACCEPTED"' \
    "$WORK/artifacts/l2-receipt.json" >/dev/null; do
  sleep 1
done

jq . "$WORK/artifacts/l2-receipt.json"
```

Retain `receipt.appHeight` and `receipt.ordinal`. A rejected receipt is final
for that attempt and includes a bounded code/detail; do not proceed to proof
generation.

## 12. Export and prove the finalized transition

The typed Java client exports the exact finalized prover input. Run this from
the distribution root after setting the receipt position:

```bash
export APP_HEIGHT="$(
  jq -r '.receipt.appHeight' "$WORK/artifacts/l2-receipt.json"
)"
export APP_ORDINAL="$(
  jq -r '.receipt.ordinal' "$WORK/artifacts/l2-receipt.json"
)"
export TRANSITION_FILE="$WORK/artifacts/transition-$APP_HEIGHT-$APP_ORDINAL.cbor"
```

Use `EutxoClient.finalizedValidityTransition(appHeight, ordinal)`, write
`transition.canonicalBytes()` to `TRANSITION_FILE`, and record the lowercase
hex form of `transition.previousRoot()`:

```java
AppChainClient appchain = AppChainClient.builder(
        "http://127.0.0.1:7070/api/v1")
    .chainId("payments-zk")
    .build();
EutxoClient eutxo = new EutxoClient(appchain);
EutxoValidityTransition transition =
        eutxo.finalizedValidityTransition(appHeight, ordinal)
            .value()
            .orElseThrow();
Files.write(output, transition.canonicalBytes());
String previousRoot =
        HexFormat.of().formatHex(transition.previousRoot());
```

Generate one real Groth16 proof:

```bash
export PREVIOUS_ROOT='<previousRoot printed by the exporter>'

PROVE_RESULT="$(
  ./yano.sh appchain validity prove \
    --project "$PROJECT" \
    --previous-root "$PREVIOUS_ROOT" \
    --transition "$TRANSITION_FILE"
)"
printf '%s\n' "$PROVE_RESULT" | tee \
  "$WORK/artifacts/prove-result.json" | jq

export PROOF_ID="$(
  jq -r '.details.proofId' "$WORK/artifacts/prove-result.json"
)"

./yano.sh appchain validity proof "$PROOF_ID" \
  --project "$PROJECT" | tee \
  "$WORK/artifacts/proof-verification.json" | jq
```

Expected status:

```text
PROOF_VALID
```

The proof binds the project/network/profile identities, previous and next
validity roots, ordered transaction manifest, batch-data commitment, and
aggregate withdrawal commitment. The b16 profile accepts at most 16 finalized
transitions in one proof.

## 13. Publish the proof and root on L1

Prepare the settlement journal:

```bash
./yano.sh appchain validity settlement prepare \
  --project "$PROJECT" \
  --id settlement-0001 \
  --proof "$PROOF_ID"
```

The external Cardano builder now performs the root operation:

1. Parameterize and compile `EutxoValidityRootValidator` with the pinned
   verification key, circuit/profile bounds, data script, thread-token
   identity, and migration authority.
2. Create the initial root-thread UTxO if this is the first settlement.
3. Spend the current root-thread UTxO with
   `EutxoValidityOnChainAbi.advanceRedeemer(proof, manifest)`.
4. Recreate exactly one root-thread output with the next root datum.
5. Publish the exact canonical `EutxoZkBatchManifest` as inline batch data.
6. Use live devnet L1 protocol parameters, fees, collateral, and cost models.
7. Sign with the disposable Cardano operator or configured relay signer.

The live reference implementation is the block beginning with
`EutxoJubjubBatchDevelopmentSetup.create(...)` and the `settleTx` builder in
`EutxoZkRollupDevnetE2ETest`.

Publish the externally signed transaction:

```bash
SETTLEMENT_TX_ID="$(
  ./yano.sh appchain validity settlement submit \
    --project "$PROJECT" \
    --id settlement-0001 \
    --tx "$WORK/artifacts/root-settlement.signed.cbor" \
    --url "$NODE" |
  tee "$WORK/artifacts/settlement-submit.json" |
  jq -r '.details.transactionId'
)"

curl --fail --silent "$API/txs/$SETTLEMENT_TX_ID" | jq
```

After the root transaction is stable:

```bash
./yano.sh appchain validity settlement stable \
  --project "$PROJECT" \
  --id settlement-0001 \
  --tx-id "$SETTLEMENT_TX_ID"
```

## 14. Publish the L1 proof withdrawal

Prepare a second operation bound to the same proof:

```bash
./yano.sh appchain validity withdrawal prepare \
  --project "$PROJECT" \
  --id withdrawal-0001 \
  --proof "$PROOF_ID"
```

The external Cardano builder constructs one atomic transaction that:

1. reads the settled root;
2. spends the proof-withdrawal controller;
3. spends accepted custody funds;
4. recreates the controller with its sequence advanced;
5. pays exactly 3 ADA to `PAYOUT_ADDRESS`;
6. returns the remaining accepted funds with `EutxoSettlementDatum`; and
7. supplies the proof-bound withdrawal redeemer, reference scripts, fees,
   collateral, and any required Cardano signatures.

The reusable base builder is `ProofWithdrawalTransactionBuilder`. The exact
Julc proof-controller flow is the `withdrawalTx` block in
`EutxoZkRollupDevnetE2ETest`.

Publish and retain the transaction ID:

```bash
WITHDRAWAL_TX_ID="$(
  ./yano.sh appchain validity withdrawal submit \
    --project "$PROJECT" \
    --id withdrawal-0001 \
    --tx "$WORK/artifacts/proof-withdrawal.signed.cbor" \
    --url "$NODE" |
  tee "$WORK/artifacts/withdrawal-submit.json" |
  jq -r '.details.transactionId'
)"

curl --fail --silent "$API/txs/$WITHDRAWAL_TX_ID/utxos" | jq
```

Verify that one output pays `PAYOUT_ADDRESS` exactly 3,000,000 lovelace. After
the transaction is stable:

```bash
./yano.sh appchain validity withdrawal stable \
  --project "$PROJECT" \
  --id withdrawal-0001 \
  --tx-id "$WITHDRAWAL_TX_ID"

./yano.sh appchain validity reconcile --project "$PROJECT"
```

## 15. Verify the complete round trip

The successful final state has all of these properties:

| Check | Expected result |
|---|---|
| L1 accepted vault | Original 10 ADA accepted output was consumed |
| L2 deposit input | Mirrored input was consumed |
| L2 change | 7 ADA-equivalent output remains at `L2_ADDRESS` |
| L2 withdrawal claim | 3 ADA claim is committed and later confirmed |
| Proof | `PROOF_VALID` |
| L1 root | Root-thread datum contains the proof's next root |
| L1 payout | 3,000,000 lovelace exists at `PAYOUT_ADDRESS` |
| Operation journals | Deposit, settlement, and withdrawal are `STABLE` |

Inspect the L2 owner:

```bash
./yano.sh appchain eutxo utxo list "$L2_ADDRESS" \
  --url "$API" \
  --chain "$CHAIN_ID" | jq
```

Verify the retained proof and lifecycle:

```bash
./yano.sh appchain validity proof "$PROOF_ID" \
  --project "$PROJECT" | jq

./yano.sh appchain validity status \
  --project "$PROJECT" | jq

./yano.sh appchain validity reconcile \
  --project "$PROJECT" | jq
```

Compare all three appchain nodes:

```bash
for port in 7070 7071 7072; do
  curl --fail --silent \
    "http://127.0.0.1:$port/api/v1/app-chain/chains/$CHAIN_ID/status" |
    jq '{chainId, tipHeight, stateRoot, running}'
done
```

All members must converge on the same tip and state root.

## 16. Stop, restart, and clean up

Stop while preserving state:

```bash
"$PROJECT/scripts/stop"
```

Restart the same project:

```bash
export YANO_HOME
"$PROJECT/scripts/start"
"$PROJECT/scripts/status"
./yano.sh appchain validity reconcile --project "$PROJECT"
```

Project data and proof/lifecycle state are intentionally separate:

```text
<project>/data/                  node and appchain state
<project>/runtime/validity/      ceremony, proofs, and operation journals
```

For a disposable reset, stop first and remove only the explicitly reviewed
`payments-zk` project directory. Never point a recursive delete at
`YANO_HOME`, the repository root, `$HOME`, or an unresolved variable.

## 17. Move the same workflow to Preview or Preprod

The protocol steps remain the same, but:

- select `--network preview` or `--network preprod`;
- add
  `--acknowledge EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET`;
- supply funded testnet Cardano keys and real test ADA;
- use public-network L1 protocol parameters, fees, collateral, timing, and
  stability depth;
- persist the project lock, contract identities, ceremony identity, and
  operation journals; and
- do not use the devnet faucet or disposable fixed member seeds.

Mainnet is rejected by project generation and lifecycle commands.

## 18. Troubleshooting

### `PUBLIC_MEMBER_IDENTITIES_REQUIRED_BEFORE_START`

The project was generated without all member public keys. Re-run `init` with
three `--member-key` values, or update the blueprint and render it before
starting.

### The node is on port 8080 instead of 7070

The project was initialized without `--http-port-base 7070`. Use the port in
the generated node config, or regenerate before producing runtime state.

### `appchain cluster start` launches orders/registry/effects chains

That command is the stock demo cluster. Start this generated project with:

```bash
export YANO_HOME='<absolute extracted distribution root>'
<project>/scripts/start
```

### The deposit never appears on L2

Check that:

- the accepted output, not merely the staging output, is present and stable;
- its address exactly equals `bridgeVaultAddress`;
- its payment script hash exactly equals `bridgeVaultScriptHash`;
- its inline datum is an `EutxoVaultDatum` for the same chain;
- the amount is within `bridgeMaxDepositLovelace`; and
- node logs show the `bridge-deposits` observer running.

### L2 submission succeeded but no accepted receipt appears

HTTP submission is pool admission only. Query by the L2 transaction ID, not
the outer app message ID. Check the final receipt's `code` and `detail` for a
domain, key epoch, expiry, ownership, conservation, or unsupported-field
rejection.

### `validity prove` rejects the transition

The canonical transition must be finalized, belong to the same chain/network,
start at the supplied previous root, use the locked profiles, and contain no
more than the b16 bound. Do not reconstruct it from UI JSON; export the
runtime's canonical transition bytes through `EutxoClient`.

### L1 submission returns HTTP 400

Inspect the returned `validationErrors`. Rebuild with the current L1 protocol
parameters and confirm fees, minimum UTxO, collateral, redeemers, reference
inputs, Plutus V3 cost model, validity interval, and required signers. Never
substitute the zero-fee L2 parameter snapshot.

### Ceremony generation is slow or runs out of memory

The measured b16 profile uses a 4 GiB tooling heap and a large development
setup. Ensure adequate memory and retain a completed ceremony directory.
Never reuse a partially written or identity-mismatched setup.
