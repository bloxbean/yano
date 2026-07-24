# Z4 proof-gated aggregate withdrawals

The first validity-settlement profile intentionally supports one aggregate
withdrawal per proved bounded batch. It is not a general per-user withdrawal
tree.

For this profile, the public `withdrawalCommitment` scalar is the canonical
unsigned 32-byte encoding of the sum of every payment's second output. The
ZeroJ circuit computes that sum from the private bounded-batch witness and
constrains the public input to it. A prover therefore cannot authorize more
lovelace than those outputs without producing an invalid proof.

`EutxoProofWithdrawalVaultValidator` releases that aggregate amount to one
deployment-fixed Cardano address. It requires exactly one reference input
carrying the validity-root thread token at the proof validator's script. The
root's chain, bridge epoch, generation, height, and proved withdrawal amount
must match the claim.

The vault's own thread datum records:

- chain and bridge epoch;
- next withdrawal sequence;
- last consumed validity-root height; and
- migration generation.

The claim is permissionless. A successful transaction must consume exactly
one vault thread, produce exactly one continuing thread, increase the
sequence, move to a strictly newer root height, pay exactly one matching
output, and preserve every non-withdrawn vault asset. Reusing a root, sequence,
or thread fails. Extra signatures cannot bypass these checks.

Migration is a distinct authority-signed action that preserves vault value,
sequence, and last consumed root while moving the thread to a precommitted
successor script and incrementing bridge epoch and generation.

`EutxoZkWithdrawalReconciler` provides node-local typed states for
`PROOF_FINALIZED`, `L1_SUBMITTED`, and `L1_STABLE`. L1 rollback moves a stable
withdrawal back to submitted; this operational metadata never authorizes
funds.

Remaining limitations are explicit:

- one fixed payout address per deployment;
- one aggregate withdrawal per proved batch;
- lovelace only;
- no general claim tree or arbitrary recipient circuit; and
- availability, independent reconstruction, and exit behavior remain Z5.
