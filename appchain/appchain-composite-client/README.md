# Governed composite profile verifier

`yano-appchain-composite-client` verifies an ADR-015 profile snapshot at an
offline trust boundary. It combines four checks that must not be separated:

1. the supplied app block is finalized by an independently pinned chain member
   set and threshold;
2. the current epoch pointer, every profile epoch, and active profile marker
   are MPF inclusions under that finalized block's exact state root;
3. the authenticated epoch chain starts at the caller-pinned genesis profile
   and ends at the proven active marker; and
4. every non-genesis proposal is accepted by a mandatory caller authorization
   policy.

Use `GovernedCompositeVerifier.requirePinnedProposalHashes(...)` when proposal
hashes are approved and distributed through an independent release/governance
process. More advanced deployments can supply a policy that reconstructs the
bound membership epoch and member approvals/readiness from independently
verified finalized history.

Do not use a policy that unconditionally returns `true` at a trust boundary;
that reduces the result to structural verification.
