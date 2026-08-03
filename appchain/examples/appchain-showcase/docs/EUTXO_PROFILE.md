# EUTxO Profile

The dedicated profile delegates to Yano’s maintained EUTxO demo CLI:

```bash
./showcase.sh quickstart --profile eutxo --variant ledger --instance ledger
./showcase.sh quickstart --profile eutxo --variant bridge --instance bridge
./showcase.sh quickstart --profile eutxo --variant zk --instance zk
```

`ledger` is an experimental virtual ledger with test keys and no real funds.
`bridge` demonstrates the Cardano custody/federation boundary. `zk`
demonstrates the maintained validity flow. The light profile merely keeps an
out-of-box `eutxo-ledger` chain visible and submits one deterministic signed
virtual-funds self-payment whose receipt must decode as `ACCEPTED`. Use this
dedicated profile for the full round-trip scenario and its purpose-built
verification. The showcase maps `prepare`, `up`, `run`, and `verify` to the
maintained EUTxO CLI's `setup`, `up`, `round-trip`, and `verify` operations.
All three maintained showcase variants are currently devnet-only; the facade
rejects a public-network selection rather than silently running a devnet.
