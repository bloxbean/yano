# Cardano History demo

Cardano History is a separately packaged product plugin, not showcase-owned state-machine logic.
The light distribution installs its jar, standalone CLI, read-only domain API, and sandboxed UI.
The runtime activates the UI only when the selected chain exposes the required capability.

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

## Enable per-epoch authenticated snapshots

Stake and DRep datasets can use the reusable logical-snapshot capability. It cannot be selected
with the default parameters-only preset.

```bash
# MPF secondary roots: off-chain and Cardano-validator-capable verification.
./showcase.sh quickstart --instance history-mpf \
  --cardano-history-profile full \
  --enable-authenticated-snapshots=cardano-history-chain

# JMT secondary roots: off-chain verification only.
./showcase.sh quickstart --instance history-jmt \
  --cardano-history-profile full \
  --enable-authenticated-snapshots=cardano-history-chain \
  --authenticated-snapshot-profile jmt-blake2b256-v1

# Experimental node-local reachable-node pruning for sealed MPF stores.
./showcase.sh quickstart --instance history-mpf-pruned \
  --cardano-history-profile full \
  --enable-authenticated-snapshots=cardano-history-chain \
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

Open the console printed by `quickstart`, choose **App-chain extensions**, select
`cardano-history-chain`, and open **Cardano History**.

1. Query protocol parameters, a stake credential, DRep, or proposal.
2. For stake/DRep, choose the minimum, pool, combined, exact, or absence predicate you want to
   verify.
3. Open **Proof lab**, generate the root-fixed proof, and inspect the local verdict.
4. Download the JSON bundle and import it again to repeat verification without a node call.

The browser derives the canonical state key from the typed subject and verifies the MPF wire. For
logical snapshots it also verifies the secondary proof, primary descriptor proof, descriptor
coordinates, and same-primary-root completeness proof. The UI intentionally reports
`anchorVerified=false`: independently validating the Cardano anchor is a separate trust step.

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
