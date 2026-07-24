# Z2 durable prover pipeline

Z2 keeps proof generation entirely outside deterministic app-chain apply.
The runtime adapter hands a bounded batch to `EutxoFinalizedBatchIngestor`
only after that batch is final. The ingestor derives one canonical public
statement and uses its SHA-256 digest as the durable job ID, so replay is
idempotent and redundant provers agree on what they are proving.

The store layout is:

```text
<prover-home>/
  jobs/          secret-free job metadata
  statements/    canonical public inputs and profile identity
  batches/       canonical public batch data
  witnesses/     private proving witnesses (0700 directory, 0600 files)
  proofs/        compressed proof envelopes
  keys/          compressed verification keys
```

Writes use an atomic replace where the filesystem supports it. A process
restart changes a retained `RUNNING` job back to `QUEUED`; it never marks a
batch proved without a locally verified artifact. Failed and timed-out jobs
remain visible and require an explicit retry or cancellation. A proof failure
cannot mutate consensus state.

`ZerojEutxoProofBackend.singleParticipantDevelopmentSetup(path)` creates the
ZeroJ single-participant development ceremony bundle allowed by the ADR.
The setup is deliberately guarded by
`-Dzeroj.allowInsecureTrustedSetup=true`. Additional prover processes use
`loadCeremonyBundle(path)` so they share the same verification key. Production
activation remains blocked until Z6 replaces this bundle with an approved
ceremony.

The service exposes typed job metrics and health, while
`appchain-eutxo-zk-client` exposes transport-neutral statement, batch-data,
verification-key, and proof lookup states. A REST adapter can map these types
without placing HTTP types in the proving modules.

Operator checks:

1. The verification-key digest equals the profile-pinned digest.
2. No retained `RUNNING` job remains after restart recovery.
3. Every `PROVED` job has a proof whose digest and local verification match.
4. The queue stays below its configured capacity.
5. The witness directory and files remain owner-only.

Proof timeout is cooperative. ZeroJ native work that ignores interruption
keeps the single bounded worker occupied; restart the prover process after
capturing diagnostics rather than starting unbounded replacement workers.
