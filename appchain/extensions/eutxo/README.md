# Yano EUTxO product family

This optional extension provides a deterministic, Cardano-shaped EUTxO state
machine. Its first milestone is a **no-real-funds experimental ledger**. It is
not a Cardano bridge, a custody product, or a rollup.

## M1 capability

Select `state:eutxo-ledger` for a JVM project. The generated YAML uses:

```yaml
yano:
  app-chain:
    chains:
      - chain-id: payments-eutxo
        state-machine: eutxo-ledger
        machines:
          eutxo:
            profile: yano-eutxo-v1
            expected-profile-digest: 2499d01ee7cb0d09d0d498040c6351accd9da83df31666cd4463d0b1722d1212
            genesis:
              address: addr_test1...
              lovelace: 100000000
```

The profile commitment is SHA-256 over its canonical, versioned consensus
fields. All members must use the same profile, digest, and genesis allocation. M1
accepts canonical signed Conway transactions with:

- key-controlled testnet inputs and outputs;
- ADA only;
- zero L2 fee;
- at most 64 inputs and 64 outputs; and
- optional validity start/TTL interpreted at the app block's stable L1 slot.

Scripts, minting, native assets, collateral, reference inputs, certificates,
withdrawals, governance, and `isValid=false` are rejected with stable bounded
codes in this milestone.

## State and queries

Submit signed transaction CBOR to topic `eutxo.transactions` through Yano's
generic message endpoint. The state machine records:

- every unspent output under a deterministic outpoint key;
- an accepted receipt under the Cardano transaction ID;
- every accepted or rejected attempt under the outer app-message ID; and
- a bounded address index.

The typed `appchain-eutxo-client` uses Yano's generic root-fixed query and MPF
proof endpoints for:

- `utxos/outpoint`;
- `utxos/address`;
- `transactions/receipt`;
- `attempts/receipt`; and
- the exact profile digest.

Within one app block, transactions execute in message order against a
read-your-writes view. A child can spend an earlier transaction's output. If
transactions conflict, the first valid spend wins and later attempts receive
`INPUT_NOT_FOUND` without changing EUTxO state.

## Modules

- `appchain-eutxo-contracts` — public identifiers, state keys, models, and
  canonical CBOR codecs.
- `appchain-eutxo-ledger` — optional state-machine plugin and transition
  engine.
- `appchain-eutxo-client` — typed wrapper over generic submit/query/proof APIs.
- `appchain-eutxo-testkit` — deterministic wallets, transactions, and replay
  state for extension authors.

The ledger-only graph has no bridge, custody, ZeroJ, or Julc dependency.
GraalVM native compatibility is not claimed until packaged-native conformance
passes.

## No-funds quick start

From a source checkout, print the fixed public demo identity:

```bash
./gradlew :appchain-eutxo-testkit:runNoFundsDemo
```

Use the printed address to generate a JVM project:

```bash
./yano.sh appchain init --non-interactive \
  --recipe eutxo-ledger --network devnet --members 3 --runtime jvm \
  --deployment host --output eutxo-demo \
  --answer eutxoGenesisAddress=<printed-address> \
  --answer eutxoGenesisLovelace=100000000
```

This creates only virtual app-chain state. It does not contact Cardano, lock
funds, install a bridge, or select a validity prover. The fixed demo wallet is
public test material and must never receive real funds.
