# Light Profile

The light profile is a local JVM cluster with 3 nodes by default. Any
bootstrap size from 1 to 16 is accepted; 3, 5, and 7 are convenient demo
sizes and default to thresholds 2, 3, and 4 respectively.

It needs Java, Python 3, `curl`, and `jq`. The Python helpers use only the
standard library; there is no `pip` install or virtual environment.

```bash
./showcase.sh up --profile light --nodes 5 --instance five-node
./showcase.sh status --instance five-node
```

The shared YAML hosts `ordered-log`, `kv-registry`, `approvals`, `balances`,
`doc-trail`, `showcase-composite`, `role-approvals`, and the experimental
`eutxo-ledger`. Membership, keys, peers, proposer, and threshold are injected
by the maintained cluster launcher; the same YAML works at every node count.

`showcase-composite` and `showcase-outbox` are demo-only plugin contributions.
The composite transition performs no I/O. Node 0’s executor writes an atomic,
idempotent local receipt only after the effect is eligible. All nodes validate
and finalize both the intent and its result.

```bash
./showcase.sh run all --instance five-node
./showcase.sh load orders --count 100 --instance five-node
./showcase.sh verify all --instance five-node
```

Use `config show` for the resolved deployment and `config paths` for files.
