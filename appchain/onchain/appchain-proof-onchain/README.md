# App-chain MPF on-chain verifier

`MpfOnChainVerifier` provides the bounded root fold plus a minimal reference validator. The
validator locates exactly one state-thread asset at the configured anchor script among reference
inputs, checks chain genesis/application/commitment identity, requires the configured physical
state key, and verifies its value against the anchor's state root.

Applications own value decoding and business semantics. Classic JMT remains off-chain-only.
The checked eUTxO conformance vector currently measures a two-fold, 504-byte redeemer at
445,952,815 CPU and 1,462,219 memory units, below the configured Cardano transaction limits.
