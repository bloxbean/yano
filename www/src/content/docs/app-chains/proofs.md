---
title: "Finality, proofs & L1 anchors"
description: "Distinguish message inclusion, state proofs, certificates, and Cardano confirmation."
sidebar:
  order: 4
---

Yano exposes different evidence for different questions. Choose the proof that matches the claim you want to make.

| Evidence | What it establishes |
| --- | --- |
| Finality certificate | The configured member threshold certified an app block |
| Message proof | A message ID occurs in a finalized block's `messagesRoot` |
| State proof | A key/value or absence relates to an expected Merkle Patricia Forestry root |
| Evidence bundle | Related message, block, certificate, and available anchor evidence for verification |
| Cardano anchor | An app-chain commitment was recorded on L1 under the selected anchor scheme |

## Query a proof

Message inclusion uses:

```text
GET /api/v1/app-chain/chains/{chainId}/messages/{messageId}/proof
```

The lower-level state route is `state/proof/{keyHex}`. **A public message ID is not the physical state key.** For ordered-log message positions, use the `finalized-message-v1` typed proof subject. See the [ordered-log walkthrough](/app-chains/ordered-log/).

## Anchor modes

Metadata anchoring records a commitment in transaction metadata. Script anchoring uses the script-anchor protocol and thread NFT. These have different verification and operating requirements. Script anchoring requires bootstrap, signing configuration, funding, and a matching script identity; setting a boolean alone is insufficient.

App finality and L1 stability are distinct. Confirm the anchor transaction and bind it to the exact app block/root. A node reporting an anchor is not an independent Cardano lookup.

## Verification checklist

1. Pin the expected chain and genesis identity.
2. Obtain the expected root independently, or explicitly accept the trust in its provider.
3. Verify the proof using a release-matched verifier.
4. Verify the finality certificate against trusted membership and height-specific consensus context.
5. If relying on L1, verify the matching transaction, datum or metadata, script identity, and confirmation policy through an independent Cardano source.

`org.yanoproject:yano-appchain-proof-verifier` supplies the retained portable proof-verification module. Hashing payload bytes alone does not verify finality or anchoring.

For consensus configuration and anchor lifecycle detail, see [the consensus guide](/app-chains/consensus/).
