# Z4 proof-gated aggregate withdrawals

The first validity-settlement profile intentionally supports one aggregate
withdrawal per proved bounded batch. It is not a general per-user withdrawal
tree.

For this profile, the public `withdrawalCommitment` scalar is the canonical
unsigned 32-byte encoding of the sum of every payment's second output. The
ZeroJ circuit computes that sum from the private bounded-batch witness and
constrains the public input to it. A prover therefore cannot authorize more
lovelace than those outputs without producing an invalid proof.

That statement applies to the original bounded
`EutxoKeyPaymentSettlementCircuit`. The integrated
`zeroj-jubjub-dev-v1` b16 lifecycle is intentionally weaker: it proves the
exact finalized validity-root transition and Jubjub authorization, while its
settlement context, ordered transaction-manifest commitment, and aggregate
withdrawal scalar are derived and matched by the trusted-prover host
boundary. The current circuit exposes those values as proof public inputs but
does not yet derive the aggregate withdrawal from Cardano-shaped transaction
CBOR in-circuit. Consequently the integrated profile remains
`EXPERIMENTAL_TESTNET_ONLY`, requires a trusted prover and disposable funds,
and must not be described as a permissionless or economically secure
withdrawal system. The future hardened profile must constrain the same
transaction/withdrawal relationship in-circuit before graduation.

`EutxoProofWithdrawalVaultValidator` is a proof-controller validator. It
authorizes release of that aggregate amount from a separately identified
accepted-funds vault to one deployment-fixed Cardano address. It requires
exactly one reference input carrying the validity-root thread token at the
proof validator's script and exactly one input/output transition at the
configured funds-vault script. The root's chain, bridge epoch, generation,
height, and proved withdrawal amount must match the claim.

The vault's own thread datum records:

- chain and bridge epoch;
- next withdrawal sequence;
- last consumed validity-root height; and
- migration generation.

The claim is permissionless. A successful transaction must consume exactly
one proof-controller thread, reproduce its value exactly in one continuing
controller output, increase the sequence, move to a strictly newer root
height, consume and recreate exactly one accepted-funds-vault UTxO, pay
exactly one matching output, and preserve every non-withdrawn funds-vault
asset. Reusing a root, sequence, or thread fails. Extra signatures cannot
bypass these checks.

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
