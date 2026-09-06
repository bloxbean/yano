# ADR-037 Phase 5 qualification — 2026-09-06

Status: **in progress; not graduated**. Preview and disabled-by-default remain.

Phase 4 merged into Yano integration at `a39025d59` and into the companion
PR branch at `a1d38c2d`. Both worktrees now use `milestone/adr-037-phase-5`.
Yano X remains isolated at `/private/tmp/yano-x-adr-037-phase-3`; its root
checkout is untouched. The companion integration branch retains the historical
name `milestone/adr-037-phase-3` because that is PR 6's existing head.

## Qualification ledger

| Gate | Evidence at start of Phase 5 | Remaining work |
| --- | --- | --- |
| Exact packages | Host `0.1.0-pre14-d3adba688` JVM/native and companion `0.1.0-adr037-03face6f` distribution checks passed locally | Rebuild/re-pin after Phase 5 changes; independently accessible CI inputs |
| Consensus model | Five-member/four-vote fault-bound and membership-transition kernel tests; complete-source subset/permutation tests | Map every ADR matrix row and close missing fault cases |
| Network integration | Five-node external median runtime; one/three-node shipment lifecycle and disk restart | Five-node actual Preprod qualification with `f=1`, rotation, omission, partitions, source failures and membership change |
| Scale/recovery | 100k committed-subscription recovery/opening bounds; journal crash and atomic commit-boundary tests | Resource soak, measured bounds and live recovery drill |
| Upgrade | New cursor writes fixed in Phase 4; retained malformed cursors explicitly not migrated there | Validate narrow committed-state-authorized recovery and operator guidance |
| Guides | Existing observation user guide and companion reference guides | Provider author guide, threat model, qualification runbook and upgrade guide |
| Review/CI | Phase 4 implementation self-review and local test/package gates passed | Independent code/protocol review and all remote CI checks |

Synthetic L1 events and local N2N tests are not Preprod qualification. Local
Maven publication is not a public artifact release. No existing cluster,
settlement artifact, anchor, spending key or retained store has been changed.

## Initial recovery work

The first Phase 5 change addresses the recognized historical raw-record-key
local L1 cursor format. Recovery must require the matching retained FINALIZED
record, cursor identity and exact cursor bytes from committed authenticated
state. It must validate the whole bounded repair batch before writing, obey
capacity, leave state roots/blocks untouched, and preserve quarantine and
callback-failure barriers. Missing/mismatched evidence is a hard failure, not
permission to erase or rebuild arbitrary journal data.

Initial focused journal suite passed in 12 seconds. Expanded journal,
observation runtime, network cluster and kernel tests passed in 37 seconds
(`/private/tmp/adr-037-phase-5-recovery-observation-tests.log`), including
100k-subscription bounds, restart/rollback guards and rejection of partial or
unauthorized cursor repair. Live qualification and final full-suite validation
remain pending.

## Coordinated CI staging work

An opt-in host workflow path stages exact Maven/JVM inputs from a clean
commit, with a manifest/checksum inventory and 14-day Actions retention. The
companion consumes these only under an independently supplied full host commit
and successful host run ID. It verifies provenance, rejects conflicting inputs
and unsafe checksum paths, and preserves normal default/release behavior.
Ten offline contract fixtures passed, including wrong commit/repository/run,
version conflict, missing pin, modified ZIP and path traversal rejection.
Both workflow files parse and shell syntax checks pass. The initial macOS
Bash contract run exposed unreliable `errexit` handling of a compound
condition; trust validations now terminate explicitly on failure.

No public release or Sonatype publication is part of this staging path. Real
cross-repository Actions access and the full dispatched consumer suite still
need validation. Provider, threat, upgrade/recovery and qualification guides
have been added; their existence does not itself satisfy live qualification.

## Remote CI iteration

Host commit-build run `34010324584` at Phase 4 merge `a39025d59` failed after
11 min 7 s: 1,509 runtime tests, one failure, five skips. The only failing case
was snapshot-test temporary-directory cleanup (`source/snap-chain`), not the
non-member snapshot rejection assertion. Its terminal teardown still called
restartable `stop()`. Terminal snapshot fixture owners now call `close()` and
the directory-copy walk is closed explicitly. Snapshot and L1 journal suites
passed after this change in 17 s
(`/private/tmp/adr-037-phase-5-snapshot-recovery-tests.log`). Remote CI must be
rerun on the updated checkpoint; this targeted pass does not replace it.

## Live Preprod constraints

Use the `yano-sync-validation` workflow for Preprod sync/restart checks. Keep
all test data in a dedicated directory, preserve logs and chainstate, stop only
identified test processes, and compare restored/repaired nonces and durable
tips. A nonce mismatch, unrepaired rollback, missing replay bodies or repeated
no-progress recovery blocks qualification. Existing retained Preprod inputs
have only been located, not opened for writing or copied into a live run.
