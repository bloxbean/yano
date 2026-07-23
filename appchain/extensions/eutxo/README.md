# Yano EUTxO product family

This optional extension provides a deterministic, Cardano-shaped EUTxO state
machine. Its first milestone is a **no-real-funds experimental ledger**. It is
not a Cardano bridge, a custody product, or a rollup.

## Virtual-ledger capability

Select the `eutxo-ledger` recipe for a JVM project. It combines
`state:eutxo-ledger` with the separate `funding:eutxo-genesis` capability and
generates:

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

## Stable Cardano deposits

The separate `eutxo-cardano-bridge` recipe selects
`bridge:cardano-federated`. It deliberately excludes
`funding:eutxo-genesis`; a Cardano-backed chain cannot mix virtual genesis
value into its reserve. The generated consensus configuration contains:

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
            bridge:
              observer-id: bridge-deposits
              vault-address: addr_test1w...
              vault-script-hash: 0123...
        observers:
          bridge-deposits:
            type: eutxo-vault-deposit-v1
            chain-id: payments-eutxo
            vault-address: addr_test1w...
            vault-script-hash: 0123...
            max-lovelace: 100000000
```

The one-way deposit flow is deliberately conservative:

1. The depositor locks lovelace at the refundable staging contract with the
   target chain, L2 address, nonce, staging outpoint, and refund deadline.
2. Before the deadline, the federation moves that exact intent into the
   configured vault. The staging validator requires the vault inline datum to
   preserve every credited field; it cannot redirect the L2 owner.
3. Yano's stable L1 observer accepts only the configured vault address and
   script hash, inline canonical datum, ADA-only value, and one accepted vault
   output per transaction.
4. The state machine binds the observation envelope to the exact accepted
   Cardano outpoint, deduplicates it, creates one mirrored EUTxO, and updates
   the committed reserve atomically.

Staging outputs are never observed or credited, so a still-refundable output
cannot inflate L2 supply. A duplicate accepted outpoint is idempotent; the
same outpoint with different data fails closed. The typed client provides
root-fixed deposit and reserve snapshot queries.

This path is experimental and deposit-only. `VaultValidator` has no spend
path, so withdrawals are unavailable. The source validators use Julc
`0.1.0-pre14`; operators must pin and independently review compiled validator
artifacts and script hashes before using funds. A deep rollback below the
highest credited deposit is a fail-closed reconciliation event: stop
acceptance, persist the bridge halt through the governed operator procedure,
and reconcile Cardano inventory before resuming. It is not safe to
automatically debit already-spendable L2 value from a node-local callback.

The optional bridge runtime bundle embeds neither Julc nor ZeroJ. Julc is an
on-chain contract build tool only; direct validity settlement remains a
separate future capability.

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
