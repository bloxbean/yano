# Z1 bounded batch result

Status: implemented for the key-controlled validity profile; pre-production.

The fixed-shape Groth16 circuit accepts one to four payments. It proves:

- prefix-ordered slot activation and the exact public batch size;
- unsigned 64-bit input and output amounts;
- positive inputs and per-payment value conservation;
- zero canonical padding for unused slots;
- knowledge of the owner-key preimage;
- deterministic Poseidon transition, batch, and final-root commitments.

The differential suite runs 64 deterministic host/circuit vectors across all
batch sizes. Adversarial tests reject public-root and batch-digest changes as
well as an inflated witness. A four-payment proof contains 16,233 constraints
and 28,489 wires in the current reviewed dependency line. The same compressed
proof verifies through the precompiled Julc Plutus V3 Groth16 verifier; the
recorded local VM budget is approximately 3.41 billion CPU and 315,089 memory.
Budgets are test observations, not production limits.

The circuit profile digest is locked to
`d495d0ad6a1d7babd00ba53de5bd9019224ac81fb3c68f33dd902e5e5e9282b3`.
The profile deliberately excludes Plutus-script and bridge transitions until
later milestones define and test their proof semantics.
