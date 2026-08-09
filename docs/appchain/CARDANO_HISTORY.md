# Cardano History product

Cardano History is an optional app-chain plugin that assembles the reusable ADR-028 epoch observers
and state-machine components into one installable product. It does not copy Cardano-specific logic
into the Yano core API and it does not define another proof format.

## Presets

| Preset | Protocol parameters | Epoch stake | Proposals and DRep distribution |
|---|---:|---:|---:|
| `params-only-v1` | yes | no | no |
| `params-stake-v1` | yes | yes | no |
| `params-governance-v1` | yes | no | yes |
| `full-v1` | yes | yes | yes |

The default is `params-only-v1`. Stake and governance traversal are never enabled implicitly. The
selected preset and commitment identity are genesis-time inputs; preview chains start fresh when
either changes.

## Read API

The plugin contributes bounded, read-only routes below
`/api/v1/plugins/com.bloxbean.cardano.yano.appchain.cardano-history/`. Every request requires
`chain=<chain-id>`. Routes cover status, epochs, protocol parameters, stake, DRep distribution, and
proposal history.

Responses identify the exact `committedHeight` and `stateRoot` and return typed proof coordinates,
not proof bytes. Stake, DRep, and proposal reads query the fact and completeness metadata in one
composite aggregate operation. An incomplete dataset returns `complete=false` and
`absenceProvable=false`; it is never interpreted as zero stake or non-membership.

## Java client and CLI

The client requires an explicit node API URL and chain ID:

```java
var history = CardanoHistoryClient.builder(
        "http://localhost:17070/api/v1", "cardano-history-chain")
        .apiKey(System.getenv("YANO_CLUSTER_API_KEY")).build();
var stake = history.stake(170, 0, credentialHash);
```

The CLI never assumes port 7070 or derives a chain from an arbitrary showcase instance name:

```bash
yano-cardano-history status \
  --url http://localhost:17070/api/v1 \
  --chain cardano-history-chain \
  --api-key "$YANO_CLUSTER_API_KEY"

yano-cardano-history query params --epoch 170 \
  --url http://localhost:17070/api/v1 \
  --chain cardano-history-chain

yano-cardano-history proof params --epoch 170 --output params-proof.json \
  --url http://localhost:17070/api/v1 \
  --chain cardano-history-chain \
  --api-key "$YANO_CLUSTER_API_KEY"

yano-cardano-history proof stake --epoch 169 \
  --credential "$STAKE_CREDENTIAL_HEX" --coin 1000000 --mode minimum \
  --output stake-proof.json \
  --url http://localhost:17070/api/v1 \
  --chain cardano-history-chain \
  --api-key "$YANO_CLUSTER_API_KEY"

yano-cardano-history verify --bundle params-proof.json \
  --trusted-root independently-obtained-cardano-root.json
```

Exit code `0` means a fully L1-authenticated proof or successful non-proof command, `3` means data
is unavailable/incomplete, `4` means invalid, and `5` means the trie proof is valid against the
pinned root but the Cardano anchor was not independently checked. A proof command first reads the
chain's confirmed anchor commitment, generates every primary and secondary proof at that exact
height, and rejects any root mismatch; it never races a moving latest-state query against the
anchor. A bundle generated from the same node is deliberately
`ROOT_VERIFIED_ANCHOR_UNCHECKED` until the caller supplies an independently verified Cardano anchor
context.

The optional `--trusted-root` document is a separate caller input. The verifier ignores any trust
source embedded in a proof bundle, so editing a bundle cannot promote a root-only result to an L1
authenticated result. A `CARDANO_ANCHOR` trusted root must come from the caller's independently
verified Cardano source.

See [Authenticated snapshots](AUTHENTICATED_SNAPSHOTS.md) for retention and
[Plugin UI extensions](PLUGIN_UI_EXTENSIONS.md) for frontend isolation.

## Product console

Installing the Cardano History bundle also installs a capability-gated **Cardano History** app-chain
tab. It shows the selected chain's enabled history capabilities and anchor, provides bounded
parameter, stake, DRep, and proposal queries, and exposes proof coordinates in a Proof Lab. The UI
runs in the standard opaque-origin sandbox and can use only the declared read-only host bridge; it
does not receive an API key and cannot open its own network connection.

The Proof Lab generates, imports, downloads, and locally verifies
`cardano-history-browser-proof-v1` bundles. It verifies the released MPF wire profile,
locally derives the canonical key from the typed epoch/credential/proposal subject, and verifies
key/value/height/root/profile binding, same-root completeness, snapshot descriptors, and selected
stake/DRep predicates without asking the node to verify its own proof. It supports primary
parameter/proposal proofs and nested authenticated-snapshot stake/DRep inclusion or absence
proofs. Independent Cardano L1 anchor
verification remains available through `yano-cardano-history verify`, and the UI labels that trust
boundary explicitly. Stake and governance tabs appear only when the selected chain exposes those
components.

## On-chain consumption

`appchain-cardano-history-onchain` fixes the Cardano History application ID, MPF profile, canonical
component/snapshot keys, dataset series, and predicate tags. Its compiled parameter and pair
validators compose the reusable MPF anchor, inclusion, same-root completeness, and authenticated
snapshot libraries. Protected keys, epoch, predicate, and operands are script parameters rather
than caller-selected redeemer values.
Stake amount, pool, combined, and absence-with-completeness predicates use tags 0–4; proposal exact
status uses 5; DRep minimum/exact uses 6–7. JMT is deliberately excluded from this module because it
is off-chain-only.

## Showcase distribution

The showcase ZIP contains the product bundle in `yano/plugins` and the standalone CLI below
`tools/cardano-history`. The default light profile enables `params-only-v1`; use a fresh instance
for another genesis-time preset:

```bash
./showcase.sh quickstart --instance history-params
./showcase.sh quickstart --instance history-full \
  --cardano-history-profile full
./showcase.sh quickstart --instance history-snapshots \
  --cardano-history-profile full \
  --enable-authenticated-snapshots=cardano-history-chain
./showcase.sh quickstart --instance without-history --disable-cardano-history
```

The retained `showcase-identity.json` binds enablement, preset, and product-bundle SHA-256. A
restart cannot silently change any of them.

For a full snapshot profile, the stable series IDs are
`l1-epoch-stake-v1.distribution` and
`l1-epoch-governance-v1.drep-distribution`. Stake snapshots use end-of-epoch semantics and DRep
snapshots use start-of-epoch semantics. Always use the descriptor's `datasetEpoch`, or the epoch in
the domain response, rather than assuming the latest parameter epoch is also the latest complete
stake epoch.
