# Observation upgrades and recovery (preview)

This guide accompanies ADR-037 qualification. It does not declare the feature
production-ready or waive the five-node Preprod and independent-review gates.

## Breaking fresh-chain cutover — release approval still required

PR #113 changes every app-chain's consensus context to v3 and commits the
observation profile at height 1, **including the disabled profile**. Disabling
observations does not preserve compatibility. An existing pre-ADR-037 app-chain
ledger without that marker refuses startup. Deleting its local database and
resyncing the same old chain does not solve the context/state-root mismatch.
Mixed old/new members cannot participate in the same consensus generation.

This includes older Preprod SCRIPT-anchor deployments. The Phase 5 qualification
cluster was created on ADR-037 code; its successful restarts are not migration
evidence for an older ledger. No automatic migration or retained-chain upgrade
path is provided. Do not delete or reset an existing ledger, signing journal,
anchor, or settlement identity to get past the guard. Preserve it and continue
using its matching software generation until an operator-approved replacement
chain and anchor/settlement transition have been planned.

The PR also starts forwarding `consensus.*` settings that were previously
ignored by configuration parsing. Audit those settings on every member before
creating a new generation; dormant fault-bound/quorum values can now become
effective. Explicit maintainer approval of this fresh-chain-only release is
still needed; implementation and test results do not supply that approval.

Review remediation pins the logical `source-id` into attested and Merkle source
digests and isolates result/tick sender-sequence domains. Preview profiles and
fixtures must be regenerated for a new generation. Old preview stores also lack
the new derived-index commit-height watermark and fail the startup audit:
preserve them for exact-version replay/repair, not silent marker backfilling.
`ObservationLedgerRebuilder` is a library API, not a turnkey operator command;
repair requires the matching state machine, profiles and height-specific
membership history, plus root-by-root verification before index installation.

The plugin host API moves from level 4 to 8 across these milestones. Rebuild and
qualify consumers against matching published/staged host artifacts; level-8
certified-header consumers cannot safely run with incomplete older proof APIs.

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

Dedicated app-peer links now leave reconnect ownership with the app subsystem,
not the transport library's synchronous close callback. A disconnected session
object is not a live connection: the underlying channel must also be active.
The existing five-second connection tick supervises one off-loop connector per
peer and interrupts stalled negotiation after its thirty-second allowance;
protocol-100 readiness must also arrive within that allowance. Established,
fully negotiated links do not expire on that timer. Retained replay entries
remain bounded and are re-offered only after the replacement's InitAck.
This policy applies only to dedicated app links, not L1 upstream clients.

When testing partitions, verify all directed links actually reconnect after
healing, then verify same-height certified roots. A four-node result while the
fifth remains disconnected proves quorum progress, not complete recovery.
Preserve peer status, logs and thread dumps before any operator restart; do not
erase prepared locks, certificates or signing journals to force catch-up.

Script-anchor compatibility qualification also found that a member excluded
from a responsive co-signing subset ignored the advance request, losing the
exact transaction identity needed for later anchor adoption. Every member now
verifies such requests against its local app history, membership threshold,
validator artifact and L1 view, and may retain a non-authoritative candidate.
Only listed required signers emit witnesses. Candidate promotion still requires
the exact verified transaction in committed L1 state; a request alone never
opens anchoring or settlement gates. No L1 core behavior or observation profile
changes are part of this correction.

A follower catching up quickly can miss the first verified advance while it
is unspent. Adoption therefore also checks retained spent outputs for the
bounded set of exact transaction hashes it previously verified. This read
runs under the same atomic committed-UTxO point/hash guard as current-output
reconciliation; the datum still has to match local app history and identity,
and collateral-return outputs are not successful script acceptance. The
historical advance establishes the identity checkpoint first, then ordinary
reconciliation can follow the current thread output on a subsequent tick.
Rollback before that checkpoint clears the identity. Raw block callbacks or
an unrelated current transaction never establish it.

If spent history has been pruned or is unavailable, adoption stays pending;
do not fabricate evidence or reset stores. A subsequent independently verified
and committed advance can supply fresh acceptance evidence. The recovery uses
the existing UTxO read API and does not change L1 storage, pruning or validation.

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
