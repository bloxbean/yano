# Observation upgrades and recovery (preview)

This guide accompanies ADR-037 qualification. It does not declare the feature
production-ready or waive the five-node Preprod and independent-review gates.

## Preserve generation identity

Keep an offline backup of the complete app-chain store and matching public
configuration before an upgrade. Record the exact host/plugin versions,
genesis, state-commitment profile/fingerprint, consensus and observation
profiles, membership history, finalized height/root and plugin catalog identity.
Preserve signing journals and their ownership; never copy a reporter journal
to a second concurrently active reporter.

Use matching versioned Maven artifacts and ordinary JVM ZIPs for Yano X.
API 8 certified headers add view, consensus context, proposer and justification
digest. Update proof clients together: they verify a domain-separated COMMIT
digest and independently pin the height-specific consensus context. Old
incomplete proof headers must fail closed, not fall back to bare-hash signatures.

Changing a genesis-pinned observation profile is not an in-place operational
configuration edit. Replay retained generations using their original profiles.
Do not infer wall-clock cadence from an APP_HEIGHT interval.

## Historical local L1 cursor writer defect

Phase 4 fixed new writes that previously stored a raw journal record key where
acknowledgment/rollback expected a length-prefixed key and observation identity.
The authenticated cursor encoding was already correct; the defect was local
derived journal state. Phase 5 adds narrowly scoped startup recovery, which
must be consumed from a checkpoint containing that change, not the earlier
Phase 4 package.

Only the recognized legacy value can be repaired. The runtime requires all of:

- the exact retained record and a FINALIZED journal state;
- the configured identity context and matching observer cursor key;
- the expected record key recomputed from the canonical observation;
- an exact matching cursor in the committed authenticated state;
- enough configured journal capacity for the additional 36 bytes per cursor.

All candidate repairs are validated before one atomic local write. Recovery
does not change authenticated state, blocks, certificates, source tombstones or
observation identities. Status field `recoveredLegacyCursors` counts repairs in
the current journal instance; a subsequent clean restart reports zero.

Recovery never clears quarantine or callback-failure markers. A callback
failure still requires replay of its exact failed L1 slot through the existing
validated replay path. A deep finalized rollback remains a hard stop. Do not
delete markers to make a health check green.

`L1_CURSOR_RECOVERY_REQUIRES_COMMITTED_EVIDENCE` means the automatic path cannot
establish authority. Preserve the store and investigate the matching retained
generation/replay material. Do not fabricate a cursor, substitute an unrelated
record, switch profiles, or reset the journal. `L1_CURSOR_RECOVERY_EXCEEDS_CAPACITY`
leaves the repair unapplied; review resource limits before a bounded capacity
adjustment. Neither condition justifies deleting chainstate.

## Post-upgrade checks

Compare the retained finalized height/root and generation identity, then verify
historical state proofs under independently pinned trust. Inspect journal
health, cursor recovery count, pending records and preserved failure barriers.
After normal work resumes, compare roots at the same height across nodes,
rather than comparing tips sampled at different times.

For live Preprod recovery, preserve/rotate logs, stop only the dedicated test
process, and verify nonce restore/repair and continued block application after
graceful and abrupt restarts. Missing replay bodies, mismatched nonces or an
unrepaired rollback stop qualification. No generic-observation certificate or
effect receipt can override Cardano validation or repair such a failure.
