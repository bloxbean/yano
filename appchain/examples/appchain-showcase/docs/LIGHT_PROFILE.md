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

The shared YAML starts thirteen chains. `payment-chain-settlement` is part of the
default light profile; it does not need a separate chain-start command.
Membership, keys, peers, proposer, and threshold are injected by the maintained
cluster launcher; the same YAML works at every node count.

| Group | Chains | What the group demonstrates |
|---|---|---|
| Standalone foundations | `orders-chain`, `registry-chain`, `approvals-chain`, `balances-chain`, `documents-chain`, `roles-chain`, `payments-chain` | one stock application behavior at a time |
| Cross-cutting application | `workflow-chain` | orders + approval + audit + outbox effects |
| Cross-cutting application | `document-review-chain` | documents + actors/roles + approval + consumption receipt |
| Authorization application/backend comparison | `authenticated-map-chain`, `authenticated-map-jmt-chain` | direct actor/role authorization and approval, with MPF/JMT proof behavior |
| L1 application boundary | `payment-chain-settlement` | L1 observers + EUTxO + settlement effects + derived indexer |
| L1 history and authenticated snapshots | `cardano-history-chain` | async epoch protocol parameters, stake, proposals, DRep distribution, and optional per-epoch MPF/JMT roots |

`document-review-chain` reuses the ADR-019/031 actor registry, role-aware
approvals, role authorization capability, and document transitions. It does
not copy the role-evidence workflow or introduce another approval model.

Every status response includes an immutable `capabilityManifest`. The console
uses it for the cross-chain matrix and composition panels; it does not infer
business capabilities from a state-machine name. Start an indexed generation
with either opt-in form:

```bash
./showcase.sh up --instance indexed --enable-finalized-message-index
./showcase.sh up --instance selected \
  --enable-finalized-message-index=documents-chain,workflow-chain
```

The selection is consensus state, not the rebuildable SQL indexer. It is
retained in the instance identity and cannot be changed on restart.

Cardano History defaults to parameters only. Select `--cardano-history-profile
params-stake|params-governance|full` at genesis for the corresponding larger L1 datasets.
Authenticated snapshots are also a fresh-generation choice. Enable the selected
Cardano history series with `--enable-authenticated-snapshots=cardano-history-chain`.
MPF is the default and supports the nested on-chain proof path; add
`--authenticated-snapshot-profile jmt-blake2b256-v1` for the off-chain-only
comparison. The selected series/profile are retained in the instance identity.
For MPF, `--enable-authenticated-snapshot-mpf-pruning=cardano-history-chain`
enables archive-time reachable-node pruning. This node-local choice is retained in the instance
marker and disabled by default until retained-root qualification is complete.

`membership.mode=governed` is enabled independently on the chains. That is
app-chain member-set governance, not application actor authorization or
business action approval.

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
