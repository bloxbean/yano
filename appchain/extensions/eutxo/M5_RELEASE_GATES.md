# EUTxO M5 stabilization evidence

Status: **implemented alpha; not approved for production funds**

This file separates reproducible implementation evidence from operational and
independent-review gates. Passing the automated tests below does not turn the
federated bridge into a trustless bridge, validity rollup, or audited custody
product.

## Automated implementation gates

| Gate | Evidence | Status |
|---|---|---|
| Accepted-root threshold and profile preservation | `RootAndNullifierValidatorConformanceTest` | PASS |
| Exact monotonic nullifier and explicit migration | `RootAndNullifierValidatorConformanceTest` | PASS |
| Real Yano MPF proof conversion and root reconstruction | `MpfProofConverterTest` | PASS |
| On-chain claim inclusion, destination, payout, vault continuation, and replay rejection | `ProofVaultValidatorConformanceTest` | PASS |
| Current-root selection and old/ambiguous-root rejection | `ProofWithdrawalTransactionBuilderTest` | PASS |
| Permissionless relayer body integrity and exact-byte retry | `ProofWithdrawalTransactionBuilderTest` | PASS |
| Deterministic three-member replay, restart, and snapshot roots | `EutxoStateMachineTest` | PASS |
| JVM plugin manifest and packaged distribution inclusion | `app:verifyFirstPartyPluginBundles` and app-chain distribution tests | PASS |
| Ledger-only and bridge runtime graphs exclude ZeroJ and Julc | Gradle artifact-boundary verification tasks | PASS |
| Native packaged execution | No compatible packaged-native test result | UNSUPPORTED |

The two-fold real-proof conformance vector currently records:

- proof redeemer: 504 bytes;
- Plutus CPU: 445,952,815;
- Plutus memory: 1,462,219; and
- unsigned transaction body and proof redeemer below the test ceiling of
  16 KiB.

These are regression vectors, not permanent Cardano protocol limits. A release
must compare them with the target network's pinned protocol parameters and
configured safety margin.

## Open release gates

The following evidence cannot be substituted by unit tests and remains
**UNSATISFIED**:

- prolonged multi-node devnet/preprod operation with repeated root advances,
  deposits, proof withdrawals, restart, catch-up, and rollback exercises;
- an independently maintained implementation or external schema exercise;
- independent review of the staging, vault, root, nullifier, and proof
  validators and their compiled identities;
- independent review of observer, reserve, proof conversion, transaction
  construction, and relay behavior;
- custody, signer/root-member rotation, pause, disaster recovery, and
  deep-rollback drills with named owners;
- mainnet parameter and budget dry runs without material custody; and
- closure or explicit owner acceptance of all critical/high review findings.

Until those gates close:

- capability, profile, proof, datum, redeemer, and contract schemas remain
  `v1alpha1`;
- the catalog maturity remains `experimental`;
- native compatibility remains `unsupported`;
- compiled contract identities are deployment-owned and must be pinned and
  reviewed; and
- no documentation or distribution may claim production-funds readiness.

## Trust boundary

Proof-gated settlement removes the per-withdrawal custody signature, but the
federated root threshold can still fabricate an accepted state root. The
nullifier prevents replay of an accepted settlement sequence; it does not make
a dishonest root truthful. The M4 external-signer settlement path remains
independently usable, and neither M4 nor M5 requires ZeroJ.
