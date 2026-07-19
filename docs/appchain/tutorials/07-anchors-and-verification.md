# Tutorial 7 — Connect an App Proof to Cardano

[Open an evidence chain in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=evidence-publication&network=devnet&members=3&finality=all&sequencing=fixed&runtime=jvm&deployment=host&name=anchored-evidence&chainId=anchored-evidence)

- **Level:** intermediate to advanced
- **Time:** about 20 minutes on local devnet
- **Outcome:** bootstrap a threshold-enforced script anchor, advance it after app
  blocks finalize, and understand the independent verification chain.

An app-chain finality certificate proves that the configured member threshold
approved a block. An L1 anchor makes a later application position durable and
discoverable through Cardano. These are related but distinct proofs.

## 1. Start an anchored local cluster

```bash
cd app/appchain-cluster
export YANO_CLUSTER_DIR=/tmp/yano-tutorial-anchor

./cluster.sh start 3 \
  --anchor-mode script \
  --anchor-every 2
```

Script mode uses a thread NFT and a Plutus V3 validator. The local launcher has
a deterministic demo anchor seed and can fund it from the self-contained
devnet faucet.

## 2. Bootstrap one chain's immutable anchor identity

```bash
./cluster.sh anchor-bootstrap orders-chain
```

Bootstrap consumes a seed UTxO, mints the one-shot thread NFT, and creates the
genesis datum. It establishes identity; it does not let the wallet alone claim
an arbitrary application tip.

Inspect the L1 Anchor card on <http://127.0.0.1:7070/ui/app-chain/>. It should
show the thread policy, script address, wallet, anchored height, transaction,
and lag.

## 3. Produce application progress

```bash
./cluster.sh submit orders-chain audit '{"event":"certificate-issued","id":"C-1"}'
./cluster.sh submit orders-chain audit '{"event":"certificate-published","id":"C-1"}'
./cluster.sh submit orders-chain audit '{"event":"certificate-acknowledged","id":"C-1"}'
```

Wait for app finality and the local L1 transaction:

```bash
sleep 20
./cluster.sh status

curl -s \
  http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/status \
  | jq .anchor
```

All members independently reconcile the authenticated script UTxO from their
own L1 view. Node-local “confirmed since restart” counters may differ after a
restart; the durable anchored height, transaction, and lag should converge.

## 4. The independent verification chain

For one application record, a verifier needs:

```text
record/value
   │ MPF or messages-root proof
   ▼
state root / certified app block
   │ threshold finality certificate + block hash chain
   ▼
anchored descendant
   │ exact thread NFT + validator address + inline datum
   ▼
Cardano transaction confirmed by an independent L1 source
```

The checks are:

1. Recompute the record/message commitment.
2. Verify its MPF or messages-root path against the correct app-block root.
3. Verify the app block's threshold signatures using an independently trusted
   chain profile.
4. When the record predates the anchor, verify the certified block-hash chain
   to the anchored descendant.
5. Fetch the Cardano transaction/UTxO independently.
6. Require the expected validator address and state-thread asset.
7. Decode the exact inline datum and match chain id, height, block hash, state
   root, member set, and threshold.

A transaction hash appearing in a Yano JSON response is not, by itself,
independent anchor verification.

## Metadata versus script anchoring

| Property | Metadata | Script |
|---|---|---|
| Setup | Fund wallet | Fund wallet + bootstrap |
| L1 enforcement | Data commitment only | Monotonic thread + threshold member signatures |
| Main safety authority | Anchor wallet | On-chain validator and member threshold |
| Typical use | Low-cost timestamp/discovery | Strong consortium settlement boundary |

Both modes commit application data; only script mode enforces the threshold
and monotonic successor rules on chain.

## Public test networks

For preview or preprod:

- generate a dedicated raw 32-byte anchor seed;
- provide it through the owner-only anchor-key file mechanism;
- fund the printed enterprise address with test ADA;
- use an explicit public-network confirmation before the demo spends; and
- increase cadence/stability settings to match real L1 timing and fees.

Do not reuse a wallet mnemonic, validator member seed, actor signing key, or
API key as the anchor wallet.

## 5. Clean up

```bash
./cluster.sh clean
unset YANO_CLUSTER_DIR
```

Local devnet state is disposable. A public script anchor is permanent Cardano
history even after local files are deleted.

## Go deeper

- Follow [L1 anchoring §5](../../APP_CHAIN_USER_GUIDE.md) for portable evidence
  bundles and exact trust-context construction.
- Use `EvidenceVerifier` and `EffectProofVerifier` rather than trusting server
  booleans.
- Test L1 rollback and anchor resubmission before a pilot.
- Review the bundled Java/julc and Aiken validator twins and their pinned
  hashes before depending on a released script identity.
