# Z0 feasibility result

Status: passed for development and test use; not production-ready.

The spike proves one bounded key-authorized transition with Groth16 over
BLS12-381. The circuit binds:

- the previous Poseidon validity root;
- the transition digest and next Poseidon root;
- knowledge of the owner-key preimage;
- an exact batch size of one.

The test suite performs ZeroJ's explicitly enabled single-participant
development setup, generates a real proof, verifies it with the portable
off-chain pairing implementation, rejects a modified public root, compresses
the proof and verification key, and verifies the same proof with the
precompiled Julc Plutus V3 program in the Julc VM. It also measures setup,
proof, and on-chain execution budgets on every run.

Run:

```bash
./gradlew :appchain-eutxo-zk-zeroj:test \
  :appchain-eutxo-zk-onchain:test \
  :appchain-eutxo-zk-testkit:check
```

The profile digest is locked to
`f2478e0573535b9c0de7e66d66a7e671565999c6c36096d1d7d1413fa2b0e406`.
ZeroJ is pinned to `0.1.0-pre10`; Julc is pinned to `0.1.0-pre14`.
Development setup output is never accepted as a production ceremony artifact.
