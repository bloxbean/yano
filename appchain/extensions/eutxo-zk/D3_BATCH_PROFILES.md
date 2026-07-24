# D3 fixed batch profiles

The experimental Jubjub development profile has three immutable candidate
batch identities:

| Profile | Maximum L2 transactions | Status |
|---|---:|---|
| `cardano-payment-b16` | 16 | measured development default |
| `cardano-payment-b32` | 32 | unmeasured candidate |
| `cardano-payment-b64` | 64 | unmeasured candidate |

Each identity binds its circuit ID, batch bound, Groth16/BLS12-381 proof
system, ZeroJ `0.1.0-pre10`, and `zeroj-jubjub-dev-v1` authorization-profile
digest. A different bound or hardened authorization gadget requires new R1CS,
ceremony, proving key, verification key, validator, and project-lock
identities. Batch size is not a runtime property.

## Reproducible local measurement

The build gate

```bash
./gradlew :appchain-eutxo-zk-zeroj:test \
  --tests '*EutxoJubjubBatchCircuitTest.b16Maximum*' \
  --rerun-tasks
```

compiled and proved a full 16-transaction batch using Java 25, a 4 GiB test
heap, ZeroJ's explicitly insecure single-participant development setup, and
the real Groth16 prover:

| Metric | Result |
|---|---:|
| constraints | 356,915 |
| wires | 731,869 |
| public inputs | 4 |
| development setup | 99,210 ms |
| proof generation | 14,883 ms |
| compressed proof | 192 bytes |

Timing is one local observation, not a performance guarantee. Peak resident
memory, persisted proving-key size, canonical L1 batch-data size, Cardano
transaction size, and Julc execution units were not captured by this run.
Preview and Preprod measurements are `NOT_EXERCISED`. Therefore b16 is only
the development default; b32 and b64 cannot be selected or described as
supported profiles.

## Backpressure

`EutxoFinalizedBatchScheduler` queues canonical finalized-transition witnesses
under a selected immutable profile. It:

- preserves finalized order;
- drains at most the profile bound;
- validates every transition digest before queuing;
- exposes transaction and batch backlog counts; and
- rejects before the configured queue capacity is exceeded.

It never changes the circuit bound, silently splits one proof statement, or
relabels a candidate profile.
