# ADR-033 Capability Catalog and Proof Lab

Use this page as the short presenter narrative for the default `light`
profile. The machine-readable source of truth is
`catalog/showcase-catalog-v1.json` in the distribution; `showcase.sh describe
light` renders it directly.

| Chain | Kind | Demonstrates | Proof target |
|---|---|---|---|
| `orders-chain` | foundation | ordered log + intrinsic finalized-message record | MPF, off-chain + on-chain |
| `registry-chain` | foundation | conditional key/value state | MPF, off-chain + on-chain |
| `approvals-chain` | foundation | basic sender-quorum approval | MPF, off-chain + on-chain |
| `balances-chain` | foundation | deterministic balances | MPF, off-chain + on-chain |
| `documents-chain` | foundation | document head/history transitions | MPF, off-chain + on-chain |
| `workflow-chain` | composition | orders + approval + audit + effects | MPF, off-chain + on-chain |
| `roles-chain` | foundation | actors, roles, policies, actor approvals | MPF, off-chain + on-chain |
| `payments-chain` | foundation | virtual no-real-funds EUTxO | MPF, off-chain + on-chain |
| `authenticated-map-chain` | composition | authenticated map + direct roles + approval | MPF, off-chain + on-chain |
| `authenticated-map-jmt-chain` | backend contrast | identical map business profile on classic JMT | JMT, off-chain only |
| `payment-chain-settlement` | L1 boundary | L1 observers + EUTxO + settlement effects/indexer | MPF, off-chain + on-chain |
| `document-review-chain` | composition | documents + roles + approval + consumption receipt | MPF, off-chain + on-chain |
| `cardano-history-chain` | L1 history | protocol parameters, epoch stake, proposals, and DRep distribution | primary MPF; optional MPF/JMT logical snapshots |

The console’s matrix and composition panel are populated from each
application’s `capabilityManifest`. Membership governance and executable
profile governance are displayed separately from business authorization and
approval.

## Optional finalized-message state proof

Every finalized block already has a message-inclusion path to `messagesRoot`.
The optional index additionally stores a compact authenticated record
containing height, original message index, topic, and sender. It does not copy
the body and does not prove continued body availability.

```bash
# Default: off for non-intrinsic applications.
./showcase.sh up --instance default

# Selected applications.
./showcase.sh up --instance selected \
  --enable-finalized-message-index=documents-chain,workflow-chain

# Every configured application. orders-chain remains intrinsically indexed.
./showcase.sh up --instance all-indexed \
  --enable-finalized-message-index
```

The scope, policy, cost bound, and digest are retained in
`showcase-identity.json`; selected applications receive a distinct
state-generation identity. Restart with a conflicting option fails closed.

In the UI, select a message, inspect its block inclusion, choose “Use message
state key,” retrieve the authenticated state proof, and compare the state root
with the latest applicable L1 anchor. MPF proof wires can also be consumed by
the reference on-chain verifier. JMT proofs are intentionally off-chain-only.

## Three composition traces

```bash
./showcase.sh run composite --instance demo
./showcase.sh run document-review --instance demo
./showcase.sh run authenticated-map --instance demo
```

Explain each as a committed trace, not a log-derived claim:

```text
command -> authorization/approval -> application mutation -> effect/consumption receipt
```

`payment-chain-settlement` starts in light for discoverability, but public
script deployment, owner keys, deposits, and withdrawals remain explicit.

## Optional authenticated snapshots

`cardano-history-chain` is the first consumer of the reusable
`authenticated-snapshots-v1` capability. It keeps small descriptors and a
hash-linked series head in primary MPF state while each large epoch stake or
DRep distribution gets its own logical authenticated store. The default is
disabled, preserving the direct-state behavior and identity of other chains.

```bash
# MPF secondary roots: off-chain and on-chain verification.
./showcase.sh quickstart --instance history-mpf \
  --cardano-history-profile full \
  --enable-authenticated-snapshots=cardano-history-chain

# Classic JMT secondary roots: off-chain verification only.
./showcase.sh quickstart --instance history-jmt \
  --cardano-history-profile full \
  --enable-authenticated-snapshots cardano-history-chain \
  --authenticated-snapshot-profile jmt-blake2b256-v1

# Optional MPF archive-time reachable-node pruning.
./showcase.sh quickstart --instance history-mpf-pruned \
  --cardano-history-profile full \
  --enable-authenticated-snapshots=cardano-history-chain \
  --enable-authenticated-snapshot-mpf-pruning=cardano-history-chain

./showcase.sh snapshots status --instance history-mpf
./showcase.sh snapshots list --instance history-mpf
./showcase.sh snapshots descriptor cardano-history-chain \
  l1-epoch-stake-v1.distribution 0 --instance history-mpf
```

The console discovers the capability from the runtime manifest and shows the
enabled series, storage mode, completed descriptors, profiles, and node-local
lifecycle. Archive, restore, and eviction are explicit privileged jobs; proof
requests never trigger an implicit restore. An MPF nested proof binds the
entry to its logical snapshot root, the descriptor to the primary app-chain
root, and that primary root to an independently verified Cardano L1 anchor.
