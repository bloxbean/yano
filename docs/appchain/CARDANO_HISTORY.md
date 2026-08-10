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

Stake and governance presets require `authenticated-snapshots-v1`; they do not fall back to storing
the full large dataset in the primary map. Each complete stake epoch and DRep distribution epoch
has its own immutable descriptor and secondary authenticated root. Protocol parameters remain small
primary-MPF facts and do not need a redundant secondary snapshot.

## Read API

The plugin contributes bounded, read-only routes below
`/api/v1/plugins/com.bloxbean.cardano.yano.appchain.cardano-history/`. Every request requires
`chain=<chain-id>`. Routes cover status, epochs, protocol parameters, stake, DRep distribution, and
proposal history.

Protocol-parameter responses expose a sorted `fields` catalog. A single named field is available at
`epochs/{epoch}/parameters/fields/{field-id}` and returns its typed canonical leaf plus root-fixed
proof coordinates. The complete document lives at `params/{epoch}/document`; named leaves live at
`params/{epoch}/fields/{field-id}`. This permits a compact proof such as
`key-deposit == 2_000_000 lovelace` without parsing a hard-fork-specific positional array.

A fresh Cardano History generation does not synthesize a fact from mutable current-epoch state. On
startup, every member deterministically reconciles completed boundaries still retained by its local
L1 account-state store; those retained facts can seed prior epochs without an external indexer. If
no completed boundary is retained, the first fact arrives after the next stable L1 epoch transition.
Until a fact finalizes, generic app-chain status, capability-discovery, and anchor endpoints remain
available, while product routes return HTTP 404 and the CLI reports unavailable data (exit code 3).

At boundary `E-1 -> E`, protocol parameters and DRep distribution are labelled for `E`, while the
stake dataset is the end-of-epoch snapshot labelled `E-1`. It must not be presented as an active
stake distribution for `E`. All members must retain the same reconstructed dataset, and the
app-chain proposer waits for a stable L1 reference before sequencing those observations.

The showcase can retain a bounded source window explicitly:

```bash
./showcase.sh quickstart --instance history-stake \
  --cardano-history-profile params-stake \
  --l1-source-snapshot-retention-epochs 2
```

This node-local operational setting controls how many completed L1 account-state boundaries may be
reconstructed after startup; it does not change the immutable app-chain snapshot retention policy.
The reference preprod deployment uses `2`, so it loads the two latest available boundaries without
attempting to rebuild every epoch contained in an older chainstate backup.

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

See [Authenticated snapshots](AUTHENTICATED_SNAPSHOTS.md) for retention.

## Product console

The standard Yano console owns a native, capability-gated **Cardano History** page. The plugin jar
contains no JavaScript, CSS, HTML, UI service provider, or UI contribution declaration. This keeps
the server-side product independently distributable and lets a product team build and release a
separate UI without coupling frontend code to the plugin lifecycle.

The native page appears only when a running chain declares `l1-epoch-params-v1`. It uses the same
console connection, styling, domain API, generic state-proof API, authenticated-snapshot API, and
anchor view as the rest of Yano. Stake and governance controls appear only when their corresponding
capabilities are present. Stake and Parameters views follow query → claim → verify: proof validity
authenticates the recorded bytes, claim validity evaluates the requested business condition, and an
accepted result requires both. The evaluator decodes the canonical value from the proof envelope so
editing presentation JSON cannot turn a false claim into a true one. It can generate and export
packages that explicitly show the authenticated actual value, requested predicate, proof validity,
claim result, and final acceptance. These result fields are explanatory: imported packages are
always reverified and their bundled verdict is never trusted. A newly generated package remains
`pending-offchain-verification` until that check runs.
root-fixed proof material; independent Cardano anchor verification remains available through
`yano-cardano-history verify`.

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
  --cardano-history-profile full
./showcase.sh quickstart --instance without-history --disable-cardano-history
```

Stake and governance showcase presets also set the Cardano History chain's consensus
`max-message-bytes` to 6 MiB and `block.max-bytes` to 8 MiB. ADR-028's canonical 25,000-entry
chunks cannot fit the general 64 KiB message default; the paired limits retain growth plus
framework/finality-certificate headroom without changing other showcase chains.

Epoch stake is enumerated in strict `(credentialType, credentialHash bytes)` order. MPF and JMT
derive the same root from the same unique key/value map regardless of insertion order, but the
canonical enumeration is still required to make chunks, manifests, and consensus messages
identical on every member.

The retained `showcase-identity.json` binds enablement, preset, and product-bundle SHA-256. A
restart cannot silently change any of them.

For a full snapshot profile, the stable series IDs are
`l1-epoch-stake-v1.distribution` and
`l1-epoch-governance-v1.drep-distribution`. Stake snapshots use end-of-epoch semantics and DRep
snapshots use start-of-epoch semantics. Always use the descriptor's `datasetEpoch`, or the epoch in
the domain response, rather than assuming the latest parameter epoch is also the latest complete
stake epoch.
