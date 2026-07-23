# EUTxO bridge on-chain contracts

This module owns the Julc-authored Plutus V3 contracts for the optional
federated EUTxO bridge.

The M3 vault is intentionally one-way: accepted value can enter the vault but
the `VaultValidator` rejects every spend. Withdrawal paths are introduced only
with M4 after irreversible claims, external signing, reconciliation, and
replay protection exist.

`DepositStagingValidator` has two actions:

- `0` accepts before the datum deadline and requires exactly one vault output
  that preserves the staged lovelace and the exact chain, L2 owner, nonce,
  staging outpoint, and deadline. Acceptance fees must come from a separate
  operator input; and
- `1` refunds at or after the deadline only when the depositor key signed.

The source is part of the alpha bridge ABI. A production deployment must use
release-pinned compiled artifacts and hashes; source compilation alone is not
a production contract identity.
