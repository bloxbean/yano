# EUTxO Indexer Replay Contract

This document defines the canonical records from which the optional EUTxO
lifecycle index is rebuilt. The index is derived state. It is never an
authority for consensus, proof verification, bridge accounting, or L1
stability.

## Canonical sources

| Lifecycle data | Canonical source | Replay order | Coverage |
| --- | --- | --- | --- |
| Finalized L2 transaction attempts | `transactions/summaries` query | committed `sequence` | FULL |
| L1 deposits accepted by the bridge | `bridge/deposits/records` query | committed deposit sequence | FULL |
| Withdrawal claims and confirmations | `bridge/withdrawals/records` query | bridge epoch and settlement sequence | FULL |
| Current UTxOs by address | `utxos/address` query | derived from committed state | FULL for point queries |
| Validity transitions | transaction summary position plus `validity/transitions/finalized` | app height and ordinal | FULL when the selected profile emits validity transitions |
| ZK proof and L1 publication lifecycle | provider-neutral prover/relay durable records and stable L1 records | provider-defined canonical position | FULL when the configured validity source is present and retained |
| Bridge halt/reconciliation diagnostic | committed `bridge/halt` state | latest committed app height | FULL |

The page cursors are exclusive. `before=0` starts at the newest committed
record. Consumers read the matching count query first, derive the absolute
sequence for each returned item, and use the oldest included sequence as the
next `before` cursor. Consumers that require forward replay reverse each page
locally and persist their last source position only after the database
transaction commits.

## Identity mapping

- `chainId` partitions every index.
- `messageId` identifies the app-chain submission.
- `transactionId` is the canonical Cardano transaction-body hash for an L2
  transaction.
- `acceptedOutpoint` identifies an L1 deposit accepted into the vault.
- `claimId` identifies an L2 withdrawal request.
- `settlementTransactionId` identifies the L1 settlement that confirms a
  withdrawal.
- App height and message ordinal identify the exact finalized validity
  transition.
- L1 slot and block hash identify the stable L1 observation source point.

## Rebuild and rollback

An empty index rebuilds only from these committed sources. App-chain rollback
removes projections above the retained app height before replay. L1 rollback
removes only unstable projections above the retained slot. A rollback deeper
than the configured L1 stability boundary halts bridge projection and requires
operator reconciliation; it must not rewrite accepted finalized history.

The indexer may expose lag or partial coverage, but it must never invent a
missing canonical record or silently substitute demo journal data.
