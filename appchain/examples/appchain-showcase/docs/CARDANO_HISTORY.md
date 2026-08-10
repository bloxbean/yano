# Cardano History demo

Cardano History is a separately packaged server-side product plugin, not showcase-owned
state-machine logic. The light distribution installs its jar, standalone CLI, and read-only domain
API. The main Yano console owns the Cardano History page and activates it only when a running chain
exposes the required capability; the plugin bundle contains no frontend assets.

## Choose a fresh-chain preset

The default is deliberately inexpensive and observes protocol parameters only:

```bash
./showcase.sh quickstart --profile light --nodes 3 --instance history-params
```

Enable larger L1 datasets explicitly at genesis:

```bash
# Parameters plus epoch stake.
./showcase.sh quickstart --instance history-stake \
  --cardano-history-profile params-stake

# Parameters, proposals, and DRep distribution.
./showcase.sh quickstart --instance history-governance \
  --cardano-history-profile params-governance

# Every released dataset.
./showcase.sh quickstart --instance history-full \
  --cardano-history-profile full
```

`showcase-identity.json` retains product enablement, normalized preset, and the product-bundle
SHA-256. Changing these options requires another instance. Use `--disable-cardano-history` only
when demonstrating the base catalog without the product chain.

Cardano History does not invent an attestation from mutable current-epoch state. On startup, all
members reconstruct the same completed boundaries still retained in their L1 account-state stores;
if none is retained, the first fact arrives after the next stable L1 epoch transition. Before any
fact finalizes, generic chain status, capability, and anchor views are live, but Cardano History
`status` and epoch queries return HTTP 404 (CLI exit code 3).

## Per-epoch authenticated snapshots

Every stake or governance preset automatically enables the reusable logical-snapshot capability.
Each completed stake epoch and DRep distribution epoch gets its own immutable descriptor and
secondary authenticated root. The parameters-only preset needs no secondary snapshot: parameter
facts remain directly provable from the primary MPF root.

```bash
# MPF secondary roots: off-chain and Cardano-validator-capable verification.
./showcase.sh quickstart --instance history-mpf \
  --cardano-history-profile full

# JMT secondary roots: off-chain verification only.
./showcase.sh quickstart --instance history-jmt \
  --cardano-history-profile full \
  --authenticated-snapshot-profile jmt-blake2b256-v1

# Experimental node-local reachable-node pruning for sealed MPF stores.
./showcase.sh quickstart --instance history-mpf-pruned \
  --cardano-history-profile full \
  --enable-authenticated-snapshot-mpf-pruning=cardano-history-chain
```

Inspect lifecycle without guessing storage paths:

```bash
./showcase.sh snapshots status --instance history-mpf
./showcase.sh snapshots list cardano-history-chain --instance history-mpf
./showcase.sh snapshots descriptor cardano-history-chain \
  l1-epoch-stake-v1.distribution 0 --instance history-mpf
```

Archive, restore, and eviction are explicit node-local administrative jobs. They do not change the
consensus root and a proof request never triggers an implicit restore.

## Console proof demonstration

Open the console printed by `quickstart` and choose **Cardano history**. The navigation entry is
visible only while at least one chain advertises `l1-epoch-params-v1`.

1. Query protocol parameters, a stake credential, DRep, or proposal.
2. Generate a root-fixed primary or authenticated-snapshot proof from the query coordinates.
3. Export the JSON material for independent verification.

The native console may call the node's generic proof verifier for a primary proof; that result is
not an independent Cardano-anchor verification. Use the standalone CLI with an independently
obtained trusted-root document for the full portable verification workflow.

## Standalone CLI

The ZIP includes `tools/cardano-history/bin/yano-cardano-history`. Always provide the actual node
URL; the CLI never maps an arbitrary instance name to port 7070.

```bash
tools/cardano-history/bin/yano-cardano-history status \
  --url http://127.0.0.1:7070/api/v1 --chain cardano-history-chain \
  --api-key "$YANO_CLUSTER_API_KEY"

tools/cardano-history/bin/yano-cardano-history query params --epoch 1 \
  --url http://127.0.0.1:7070/api/v1 --chain cardano-history-chain \
  --api-key "$YANO_CLUSTER_API_KEY"

tools/cardano-history/bin/yano-cardano-history proof params --epoch 1 \
  --output params-proof.json \
  --url http://127.0.0.1:7070/api/v1 --chain cardano-history-chain \
  --api-key "$YANO_CLUSTER_API_KEY"
```

Proof generation is fixed to the latest L1-confirmed Cardano History commitment, not merely the
node's moving app-chain tip. For stake proofs, query a complete epoch first; the latest complete
stake epoch can lag the latest protocol-parameter epoch because the datasets have different ledger
semantics and ingestion sizes.

Use `verify --bundle ... --trusted-root ...` with a root independently obtained from the Cardano
SCRIPT anchor. A proof that only verifies against its bundled root remains explicitly labelled
root-verified/anchor-unchecked.
