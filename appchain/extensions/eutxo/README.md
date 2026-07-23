# Yano EUTxO product family

This optional extension provides a deterministic, Cardano-shaped EUTxO state
machine. Its first milestone is a **no-real-funds experimental ledger**. It is
not a Cardano bridge, a custody product, or a rollup.

## Virtual-ledger capability

Select `state:eutxo-ledger` for a JVM project. The generated YAML uses:

```yaml
yano:
  app-chain:
    chains:
      - chain-id: payments-eutxo
        state-machine: eutxo-ledger
        machines:
          eutxo:
            profile: yano-eutxo-v2-plutus-v3
            expected-profile-digest: 8cd4adb72def2c31dc8551a02f67429ea468bb2024dbe85a1dc7300590c9d1bf
            genesis:
              address: addr_test1...
              lovelace: 100000000
```

The profile commitment is SHA-256 over its canonical, versioned consensus
fields. All members must use the same profile, digest, and genesis allocation.
Profile v2 accepts canonical Conway transactions with:

- key-controlled testnet inputs and outputs;
- witnessed Plutus V3 spending evaluated by Scalus 0.18.2;
- inline datums and spending redeemers under fixed count and execution bounds;
- ADA only;
- zero L2 fee;
- at most 64 inputs and 64 outputs; and
- optional validity start/TTL interpreted at the app block's stable L1 slot.

Plutus V1/V2, native scripts, minting, native assets, collateral, reference
inputs/scripts, datum hashes, certificates, withdrawals, governance, and
`isValid=false` are rejected with stable bounded codes. Profile v1 remains
available for chains that intentionally want key payments only.

## State and queries

Submit signed transaction CBOR to topic `eutxo.transactions` through Yano's
generic message endpoint. The state machine records:

- every unspent output under a deterministic outpoint key;
- an accepted receipt under the Cardano transaction ID;
- every accepted or rejected attempt under the outer app-message ID; and
- a bounded address index.

The typed `appchain-eutxo-client` uses Yano's generic query and MPF proof
endpoints. Every typed query has a `*Snapshot` form returning the decoded value
together with the exact committed height and state root:

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
Scalus is supplied by Yano's version-pinned host runtime rather than copied
into the thin plugin bundle. The GraalVM 25.0.2 application image builds with
the provider included; native runtime compatibility remains unclaimed until
the packaged conformance suite also passes.

## CLI

The product commands call the same typed client and print structured JSON:

```bash
./yano.sh appchain eutxo doctor --chain payments-eutxo \
  --expected-profile-digest 8cd4adb72def2c31dc8551a02f67429ea468bb2024dbe85a1dc7300590c9d1bf

./yano.sh appchain eutxo transaction submit payment.tx \
  --chain payments-eutxo

./yano.sh appchain eutxo transaction status <tx-id> \
  --chain payments-eutxo

./yano.sh appchain eutxo utxo get <tx-id#index> \
  --chain payments-eutxo

./yano.sh appchain eutxo proof <tx-id#index> \
  --chain payments-eutxo
```

Use `--url` for a non-default REST base. If authentication is enabled, use
`--api-key-env <environment-variable>`; the CLI has no inline secret option and
never prints the key.

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

## Trust statement

The virtual ledger is experimental and its schemas remain alpha. Scalus
validates only the profile's admitted Plutus family; this is not an assertion
that every Cardano ledger feature is available. A state root or MPF proof shows
what the app-chain committed, not that funds exist on Cardano. Select the
federated bridge capability separately if and only if its custody assumptions
are acceptable.
