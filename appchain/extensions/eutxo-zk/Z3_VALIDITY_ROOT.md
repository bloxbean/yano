# Z3 proof-verified validity root

Z3 advances a Cardano state-thread output only when a Groth16 proof verifies
for the exact settlement statement. It does not accept committee signatures
as an alternative to a proof.

The original Z1 bounded-payment circuit remains the arithmetic primitive.
Review found that its five public inputs did not bind enough L1 context, so Z3
adds the `yano-eutxo-zk-z3` profile. Z5 advances that profile to version 2
and the `eutxo-key-payment-settlement-v2` circuit so the circuit derives the
canonical batch-data commitment. Its ordered public inputs remain:

1. previous validity root;
2. next validity root;
3. transition digest;
4. owner commitment;
5. batch size;
6. settlement context;
7. batch-data commitment; and
8. withdrawal commitment.

The settlement context is a canonical SHA-256 commitment to the chain ID,
bridge epoch, Z3 profile digest, and compressed verification-key digest,
reduced into the BLS12-381 scalar field. Private mirror variables constrain
the three new public inputs so the Groth16 verification key has a non-infinity
IC basis for every input. This prevents a proof from being replayed for a
different chain, bridge epoch, profile, circuit/VK deployment, published
batch, or withdrawal commitment.

`EutxoValidityRootValidator` is the Julc spending validator. Its normal action:

- is permissionless;
- requires exactly one continuing thread-token output;
- preserves the chain, bridge epoch, settlement context, and generation;
- requires a strictly increasing finalized height;
- binds the current and next roots and both commitments to the proof inputs;
- verifies the compressed Groth16 proof against the deployment-pinned VK.

Migration is a separate action. It requires the configured migration
authority, moves the thread token to one precommitted successor script,
preserves the height, validity root, and commitments, increments bridge epoch
and generation, and requires a changed settlement context. An ordinary
advance cannot perform a profile, circuit, or VK upgrade.

The release-pinned Julc compiler compiles the validator. A real
single-participant-development-ceremony proof verifies through the composed
validator at approximately:

```text
CPU:    4,195,247,881
Memory:     1,078,650
```

These are development measurements, not a production budget approval.
Deployment artifacts, script hashes, network cost-model checks, and an
approved ceremony remain later release gates.
