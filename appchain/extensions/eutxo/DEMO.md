# Disposable EUTxO demos

These commands create disposable three-node Yano devnet clusters. They
generate local test identities, projects, member secrets, operation journals,
and reports. They never create production keys or accept public-network
funds.

From an extracted Yano JVM distribution, run commands beside `yano.sh`.
From a source checkout, use `./app/yano.sh` in place of `./yano.sh`.

## Inspect installed scenarios

```bash
./yano.sh appchain eutxo demo scenarios
```

| Scenario | Demonstrates | Trust boundary |
|---|---|---|
| `ledger` | Cardano-shaped zero-fee L2 transfer and MPF proof | Virtual genesis; no L1 funds |
| `bridge` | Devnet funding, L1 deposit, L2 claim and L1 payout | Disposable federated/native-script custody |
| `zk` | Jubjub L2 authorization, finalized transition, b16 Groth16 proof and demo payout | Trusted development prover and disposable native-script vault |

The `zk` quick demo proves the finalized transition before payout, but its
quick-demo vault is not the proof-withdrawal validator. The maintained live
ZK E2E acceptance exercises the validity-root and proof-withdrawal contracts.

## Virtual ledger

```bash
./yano.sh appchain eutxo demo up --scenario ledger \
  --workspace ./eutxo-ledger-demo
./yano.sh appchain eutxo demo round-trip \
  --workspace ./eutxo-ledger-demo
```

Expected: `EUTXO_LEDGER_DEMO_ROUND_TRIP_PASS`.

## Cardano bridge

```bash
./yano.sh appchain eutxo demo up --scenario bridge \
  --workspace ./eutxo-bridge-demo
./yano.sh appchain eutxo demo round-trip \
  --workspace ./eutxo-bridge-demo \
  --count 2
```

Expected: `EUTXO_BRIDGE_DEMO_ROUND_TRIP_PASS`.

Each round is multi-user: the launcher funds disposable Alice, Bob, and
operator wallets; Alice and Bob each deposit on L1; Alice signs an L2 payment
to Bob; Bob signs the withdrawal-producing L2 spend; and only the operator
signs the native-script vault settlement that pays Bob's separate L1 payout
account. Bob's L2 address and configured L1 payout address are deliberately
different: an output to the configured payout address is the deterministic
withdrawal marker. Direct bridge L2 transactions use ordinary Cardano VKey
witnesses.

The granular `fund`, `deposit`, `transfer`, `settle`, `withdraw`,
`reconcile`, and `verify` commands resume the same journaled plan.
`--count N` is a resumable target: it ensures that independent rounds
`1..N` have completed. Repeating the same command verifies/reuses those
journaled rounds; increasing the count adds only the missing rounds. The
maximum is 16. Each round has distinct L1 transactions, L2 transaction,
withdrawal claim, nonces, and artifacts.

## ZeroJ proof demo

```bash
./yano.sh appchain eutxo demo setup --scenario zk \
  --workspace ./eutxo-zk-demo
./yano.sh appchain eutxo demo ceremony \
  --workspace ./eutxo-zk-demo --yes
./yano.sh appchain eutxo demo start --workspace ./eutxo-zk-demo
./yano.sh appchain eutxo demo round-trip \
  --workspace ./eutxo-zk-demo \
  --count 2
```

Expected: `EUTXO_ZK_DEMO_ROUND_TRIP_PASS`.

The ZK scenario follows the same Alice-to-Bob lifecycle. Setup creates an
encrypted Jubjub session key for each user. Each L1 deposit binds that user's
Cardano payment credential to the corresponding Jubjub public key. Alice and
Bob independently sign the payment and withdrawal envelopes, and the b16
Groth16 job proves both finalized transitions.

The report identifies `cardano-payment-b16`, the proof ID, finalized
transition, L1 transaction IDs, claim ID, and trust boundary. Private keys and
passwords are never printed or included in the public manifest.
The same resumable `--count` semantics apply to the ZK scenario, with a
separate finalized transition and Groth16 proof for every round.

## Add an externally owned depositor

The quick flow signs with disposable owner-only files. An application or wallet
can instead keep signing outside Yano:

```bash
./yano.sh appchain eutxo demo deposit-build \
  --workspace ./eutxo-bridge-demo \
  --address addr_test1... \
  --amount 20000000 \
  --output alice-deposit.unsigned.cbor

# Sign alice-deposit.unsigned.cbor with CIP-30, cardano-cli, or CCL.

./yano.sh appchain eutxo demo deposit-submit \
  --workspace ./eutxo-bridge-demo \
  --signed-transaction alice-deposit.signed.cbor
```

For `--scenario zk`, also provide the user's lowercase 32-byte Jubjub public
key with `--l2-public-key`. The Cardano address payment credential authorizes
that binding. No mnemonic, Cardano signing key, Jubjub secret, or password is
accepted as a command-line option.

The builder is deliberately devnet/demo scoped. It emits ordinary Cardano
transaction CBOR; the wallet owns signing. The submitter rejects missing VKey
witnesses before sending bytes to Yano.

## Inspect L2 transactions

Open:

```text
http://127.0.0.1:7070/ui/app-chain/
```

Select the EUTxO chain and choose **EUTxO Explorer**. The explorer lists up to
20 recent finalized attempts at a time and can page backward or find either
the Cardano-shaped L2 transaction ID or its outer app-chain message ID.
Details show input/output outpoints, owners, lovelace, final app block, L1
slot, and authorization profile. Decoding is performed by the EUTxO plugin
from committed state; the generic console remains state-machine neutral.

## Status, restart, and cleanup

```bash
./yano.sh appchain eutxo demo status --workspace ./eutxo-zk-demo
./yano.sh appchain eutxo demo stop --workspace ./eutxo-zk-demo
./yano.sh appchain eutxo demo start --workspace ./eutxo-zk-demo
./yano.sh appchain eutxo demo verify --workspace ./eutxo-zk-demo
./yano.sh appchain eutxo demo reset --workspace ./eutxo-zk-demo --yes
```

`reset` accepts only a verified demo workspace and refuses broad or unmarked
directories. For production or public-testnet provisioning, use the manual
operator flow in the EUTxO and ZeroJ getting-started guides.
