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
  --workspace ./eutxo-bridge-demo
```

Expected: `EUTXO_BRIDGE_DEMO_ROUND_TRIP_PASS`.

The granular `fund`, `deposit`, `transfer`, `settle`, `withdraw`,
`reconcile`, and `verify` commands resume the same journaled plan.

## ZeroJ proof demo

```bash
./yano.sh appchain eutxo demo setup --scenario zk \
  --workspace ./eutxo-zk-demo
./yano.sh appchain eutxo demo ceremony \
  --workspace ./eutxo-zk-demo --yes
./yano.sh appchain eutxo demo start --workspace ./eutxo-zk-demo
./yano.sh appchain eutxo demo round-trip --workspace ./eutxo-zk-demo
```

Expected: `EUTXO_ZK_DEMO_ROUND_TRIP_PASS`.

The report identifies `cardano-payment-b16`, the proof ID, finalized
transition, L1 transaction IDs, claim ID, and trust boundary. Private keys and
passwords are never printed or included in the public manifest.

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
