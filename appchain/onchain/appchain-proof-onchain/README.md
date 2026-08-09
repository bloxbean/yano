# App-chain MPF on-chain verifier

`MpfOnChainVerifier` provides the bounded root fold plus a minimal reference validator. The
validator locates exactly one state-thread asset at the configured anchor script among reference
inputs, checks chain genesis/application/commitment identity, requires the configured physical
state key, and verifies its value against the anchor's state root.

Applications own value decoding and business semantics. Classic JMT remains off-chain-only.
The checked eUTxO conformance vector currently measures a two-fold, 504-byte redeemer at
445,952,815 CPU and 1,462,219 memory units, below the configured Cardano transaction limits.

`MpfPairOnChainVerifier` is the Cardano History reference application validator. It consumes the
same unique anchor reference input and verifies a fact plus its same-root completeness proof before
evaluating stake amount/pool/absence, proposal status/reason, or DRep amount predicates. It does not
accept a caller-supplied root as trusted data. The tests compile the validator to Plutus V3 and run
all predicates with real MPF proofs under the configured transaction limits.
