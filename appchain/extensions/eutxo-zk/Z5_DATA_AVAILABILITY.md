# Z5 data availability, reconstruction, and recovery

The bounded settlement profile uses L1-enforced publication. A normal
validity-root advance carries the exact canonical `EutxoZkBatchData` bytes in
its redeemer and must create exactly one output at the deployment-pinned data
availability script with those bytes as its inline datum.

The root validator:

- requires the exact 101-byte v2 format: version, count, four fixed payment
  slots, and zero-filled unused slots;
- computes BLAKE2b-256 over the bytes and reduces it into the BLS12-381
  scalar field;
- compares that value with the proof's `batchDataCommitment` public input;
- requires the ZeroJ circuit to derive that same BLAKE2b-256 commitment from
  its constrained payment witness rather than accepting a mirrored value;
- requires exactly one matching inline datum in the same transaction; and
- rejects missing, duplicate, mutated, non-canonical, or incorrectly addressed
  publications.

The publication is therefore atomic with the accepted root. The L1
transaction history, rather than a transient app-chain or prover endpoint, is
the authoritative publication source. Operators still need a Cardano history
source that retains and serves transactions for their declared horizon.

`EutxoZkBatchPublication` is the portable transaction/output identity plus
typed statement and batch view. `EutxoDataAvailabilityMonitor` reports
available, missing, corrupt, and outside-declared-horizon states without
guessing. `EutxoReferenceReconstructor` is a small, separate implementation
in the testkit. It does not call the production circuit host helpers: it
decodes canonical data, checks continuity and conservation, recomputes the
Poseidon transition digest and validity roots, and supports replay from
genesis or a verified snapshot.

`EutxoZkRecoveryBundle` contains only public batch data, proof, and
verification key. Once a proof exists, any holder can verify the bundle and
relay the validity-root and aggregate-withdrawal transactions; the original
operator is not required. Before a proof exists, a party must possess the
private witness and have access to a compatible prover/proving key. This
profile does not provide forced inclusion for a censored, unfinalized
transaction.

Automated adversarial tests cover:

- missing, corrupt, duplicate, and reordered publications;
- reconstruction from genesis and from a verified snapshot;
- proof-bundle recovery after the originating operator disappears;
- unavailable data, key, and proof states; and
- permissionless post-proof root and withdrawal relay planning.

These tests complete the in-repository Z5 implementation, but they do not by
themselves prove external retention or implementation independence. The
`rollup:zeroj-cardano` label remains blocked until another implementation
reconstructs the vectors and an operational exercise demonstrates the
declared L1-history horizon and recovery path.
