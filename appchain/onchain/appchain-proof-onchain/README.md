# App-chain MPF on-chain verifier

`MpfOnChainVerifier` is the bounded inclusion-proof library and `MpfAnchorLib` authenticates the
unique state-thread reference input and its commitment identity. `MpfAnchorValidator` is the
minimal generic entry point that composes both libraries and fixes one physical state key.

Applications own value decoding and business semantics. Classic JMT remains off-chain-only.
The checked eUTxO conformance vector currently measures a two-fold, 504-byte redeemer at
445,952,815 CPU and 1,462,219 memory units, below the configured Cardano transaction limits.

`MpfPairOnChainVerifier` is the reusable same-root fact/completeness and semantic-predicate library;
`MpfPairAnchorValidator` is its generic anchor-bound entry point. Product validators must bind keys,
epoch, predicate, and operands as script parameters instead of trusting those redeemer fields. The
tests compile the validators to Plutus V3 and run all predicates with real MPF proofs under the
configured transaction limits.
