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
`doc-trail`, `showcase-composite`, `role-approvals`, the experimental
`eutxo-ledger`, and `authenticated-map`. Membership, keys, peers, proposer, and
threshold are injected by the maintained cluster launcher; the same YAML works
at every node count.

The authenticated-map chain demonstrates six independent collection policies,
covering the basic, direct-role, and multi-organization approval authorization
modes of ADR-025.2:

| Collection | Authorization | Value policy |
|---|---|---|
| `attachments` | owner | opaque bytes, no validator |
| `canonical-events` | member | canonical-CBOR array, no validator |
| `products` | owner | canonical-CBOR map plus a declarative product schema |
| `gtins` | owner | canonical-CBOR text plus the first-party `gs1-gtin-v1` plugin |
| `governed-catalog` | governed-role (`issuer-write`) | opaque bytes, actor-signed direct authorization |
| `released-products` | approval (`product-release`) | opaque bytes, two auditor approvals from distinct organizations |

The governed genesis registers three demo organizations
(`acme-manufacturing`, `auditor-guild-a`, `auditor-guild-b`), four
deterministic demo actors (`registry-admin-a`, `issuer-a`, `auditor-a`,
`auditor-b`), an administrator authority, one direct-role policy, and one
approval policy. `./showcase.sh run authenticated-map` exercises both governed
flows through offline CLI authoring: the node API never receives an actor
private key; `tools/showcase_signer.py` plays the external wallet/HSM signer
for the deterministic demo seeds only. Every record — entries, receipts,
actors, policies, proposals, consumptions, and proofs — is then inspectable in
the packaged console at `/ui/app-chain/authenticated-map/`.

The launcher generates this chain's canonical genesis from the actual
bootstrap public keys and the `ARTIFACT_CLOSURE` digest in the bundled runtime
catalog. Its digest is retained with the instance identity and checked on
restart. This also demonstrates the supported custom-format extension point:
an application may provide its own trusted validator factory and bind it to an
`opaque` or `canonical-cbor` collection. Validator code is consensus-bound,
must be allow-listed and artifact-pinned on every node, and is not a hot-loaded
user upload.

`showcase-composite` and `showcase-outbox` are demo-only plugin contributions.
The composite transition performs no I/O. Node 0’s executor writes an atomic,
idempotent local receipt only after the effect is eligible. All nodes validate
and finalize both the intent and its result.

```bash
./showcase.sh run all --instance five-node
./showcase.sh run authenticated-map --instance five-node
./showcase.sh load orders --count 100 --instance five-node
./showcase.sh verify all --instance five-node
```

Use `config show` for the resolved deployment and `config paths` for files.
