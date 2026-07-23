# EUTxO bridge on-chain contracts

This module owns the Julc-authored Plutus V3 contracts for the optional
federated EUTxO bridge.

The M4 `VaultValidator` authorizes the reviewed external settlement signer and
requires exactly one continuing vault output whose inline settlement datum
binds the claim ID and payout amount. The input-to-continuation lovelace
decrease must equal that payout plus the transaction fee. The external
threshold/HSM signer remains responsible for independently checking the exact
destination, accepted claim root/proof, limits, vault inputs, change, and
bridge epoch.

`DepositStagingValidator` has two actions:

- `0` accepts before the datum deadline and requires exactly one vault output
  that preserves the staged lovelace and the exact chain, L2 owner, nonce,
  staging outpoint, and deadline. Acceptance fees must come from a separate
  operator input; and
- `1` refunds at or after the deadline only when the depositor key signed.

The source is part of the alpha bridge ABI. A production deployment must use
release-pinned compiled artifacts and hashes; source compilation alone is not
a production contract identity. The current artifacts have not passed the
independent audit and material-funds operational gates.
