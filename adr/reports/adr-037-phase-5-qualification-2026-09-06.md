# ADR-037 Phase 5 qualification — 2026-09-06

## Latest checkpoint and CI findings

### Provider-boundary review iteration

Review of the required failure matrix found that the socket timeout began only
after synchronous DNS resolution. The built-in adapter now uses a bounded
host-wide resolver (four daemon workers, 64 queued lookups), removes cancelled
queued tasks, and shares one deadline across DNS/connect/TLS/response reads.
Caller interruption releases acquisition promptly even if a simulated native
resolver ignores interruption. Shared DNS workers do not inherit caller
thread-local state or context class loaders. Saturation fails locally, with no
signed failure report or change to committed logical deadlines.

The ADR and provider/threat guides now state this boundary explicitly. Both
attestation modes request/require `application/cbor`; raw-exact response bytes
remain opaque and never select a parser through their media type. Redirects,
429/error statuses, compression, duplicate framing/encoding/type headers,
oversized/truncated bodies, mixed public/private DNS answers and rebinding-like
answer changes have focused regressions. This is implementation self-review,
not the independent review required for graduation.

Initial DNS/provider/runtime tests passed in 38s; the expanded provider/network
selection passed in 1m25s. The opt-in real HTTPS test then passed in 15s through
three networked subsystems against an immutable public source. The final
caller-context isolation regression passed in 19s. The fresh exact-source
provider/live-HTTPS selection passed in 16s: six DNS cases, seven provider
boundary cases and one real HTTPS/network case, all with zero skips/failures.
Final log: `/private/tmp/adr-037-phase-5-provider-exact-final.log`.
Logs: `/private/tmp/adr-037-phase-5-dns-timeout-tests.log`,
`/private/tmp/adr-037-phase-5-provider-boundary-tests-final.log`,
`/private/tmp/adr-037-phase-5-dns-live-https.log`, and
`/private/tmp/adr-037-phase-5-dns-context-tests.log`.

### Continued live fault evidence

Round 1 (one unavailable source) retained eight reports but no sufficient
certificate. The original cadence driver timed out at 90 seconds while waiting
for height 18; all five nodes subsequently converged there. Its failure log and
empty `cadence-rounds-1-4.jsonl` remain preserved. An explicit one-height advance,
guarded by five matching certified round proofs at that retained boundary,
produced five verified `EXPIRED` proofs at height 19: result
`d0b48af33879adbde39f67a44ff72e8740b8d0b8ad650a3519d86c73ad3c4f92`, root
`e766c66d9086fa02593c4edfb33d17e6866cdf46e76f3840f1ddb686de2c8125`.
No reports were re-signed and no state was reset. Evidence:
`round-1-expiry-recovery.json` and `expiry-recovery-2.log` in the dedicated run.

The driver wait was increased to five minutes to accommodate multiple consensus
views during catch-up; protocol deadlines remain unchanged. Round 2 split one
source's reporters 2+2, below its threshold of four, and all five certified the
same expiry at height 29: result
`dffca35d22b5ec0f4596e3cb66e0699d9cf7624b10b624a2d35bde274d161179`, root
`0ab825c1ddae8fb2424057e76d967616aeadd40e8f7b4033f49430ac04ef7b87`.
All five journals and coordinator queues drained to zero. This is recorded in
`cadence-rounds-2-4.jsonl`; delayed-reporter and complete-source rounds continue.
These runs still use the earlier exact package and historical L1 catch-up, not
the new DNS package or completed post-checkpoint Preprod qualification.

### Companion CI follow-through

At `0d4ec822`, anchor reconciliation passed. CI then exposed stale harness
expectations (24 contributions/API level 4 versus 26/API level 8) and a balances
fixture temp-directory teardown failure. Companion `8e0e8ce4` pins the correct
exact inventory, checks counts against source manifests, and uses terminal
`close()` for stdlib fixture owners. Stdlib/devtools tests passed in 2m19s;
effect-failover and deployment-parity contracts passed. The full rerun
[34013848901](https://github.com/bloxbean/yano-x/actions/runs/34013848901)
has passed the effect-failover E2E and moved on to deployment parity.
A follow-up terminal-owner audit found equivalent ZK/composite fixture cleanup;
those focused tests passed in 1m45s. Discarded subsystem instances now release
fully before a new instance opens their retained ledger; restart coverage stays.

Host checkpoint `01f990e85` passed the full build
[34011971201](https://github.com/bloxbean/yano/actions/runs/34011971201)
and all three integration/distribution/native jobs in
[34011972777](https://github.com/bloxbean/yano/actions/runs/34011972777).
These include the Phase 5 configuration correction below.

Companion checkpoint `737fc450` passed commit-build, distribution-check and
connector-fault-matrix in
[34012021380](https://github.com/bloxbean/yano-x/actions/runs/34012021380).
Effect failover reached healthy nodes and bootstrap, but timed out during
anchor adoption. Checkpoint `728b67ee` adds bounded public-counter diagnostics
without relaxing acceptance; its full rerun
[34013047087](https://github.com/bloxbean/yano-x/actions/runs/34013047087)
failed at the same anchor boundary, now with actionable diagnostics. All three
nodes had height/root zero, empty pools and no submitted/stored application
messages, while automatic consensus diffusion incremented received/relayed/
duplicate/seen-ID counters. Host routing explicitly bypasses the application
pool/history for these control messages. Companion `0d4ec822` therefore keeps
all counter type/bounds checks but requires zero only for tip, pool, submitted
and stored application messages (plus the zero root). Anchor identities,
topology, adoption-height checks and no-downgrade rules remain unchanged.
Regression fixtures reproduce the observed counters and independently reject
each nonzero application-state guard. Full rerun
[34013386814](https://github.com/bloxbean/yano-x/actions/runs/34013386814)
is pending. Phase 5 is not ready to merge.

The dedicated non-spending five-node experiment at
`/Users/satya/Downloads/yano-cluster/adr037-phase5-preprod` passed its first
synthetic-source round with independently pinned certified proofs on all five
nodes. Exact deployed host: `0.1.0-pre14-8a0f31f7a`; companion:
`0.1.0-adr037-737fc450`; ZIP SHA-256:
`93230b75728590a4e3d13933d3d93c977c9d7a364014c5037709989b9704d76c`.
Height 3 result `3894baead2208adba017a164947caf62602055486b4c2ec31327ea272899c74c`
has median `0.501000`, three sources and common state root
`ed96f7c8f712e9e12b42f61d2f56c4dbdd9e49548f6ada365f868ec1507b561b`.
All 12 externally signed reports were durably journaled before signing, with
no wake hints. Journals drained from 18730 bytes / 26 entries to zero on
finalization; all five coordinator queues and reservations were zero.
The package-boundary fixture needed dependency-complete manifest allow-lists,
explicit API authentication and governed membership, all fixed in the helper.

The retained seed was incompatible with the current epoch-boundary format.
It and its five qualification copies were preserved; only the two documented
retired account-history column families were cleaned from those new copies.
Qualification switched to fresh `chainstate-v1` directories, without bypassing
the compatibility marker. A held-tip graceful restart reproduced the durable
epoch-29 nonce before cloning the stopped current-format seed to five nodes.
The detailed local `QUALIFICATION_RUN.md` records exact paths and evidence.
Current catch-up is below the Conway validation gate; validated-header counts
are zero, so this baseline is **not** the completed live Preprod gate.
Fault injection, membership transition, long cadence, resource soak and
independent review remain pending.

The report retry regression now explicitly replays 100 duplicate valid reports,
checks no extra immediate report diffusion or journal growth, checks one report
per periodic tick, and checks that finalization stops both retry diffusion and
late duplicate persistence. `ObservationRuntimeTest` passed in 19s:
`/private/tmp/adr-037-phase-5-rediffusion-regression.log`. This is a bounded unit
regression, not sustained network resource-soak evidence.

Host configuration checkpoint `8a0f31f7a6ed42047b78b0a3e7c29b15651d43e3`
is pushed. Exact Maven Local/JVM ZIP publication and packaged catalog smoke
passed in 1m18s (`/private/tmp/adr-037-phase-5-config-publication.log`). Remote
staging [34011585670](https://github.com/bloxbean/yano/actions/runs/34011585670)
also passed. Phase 4's separate host integration/distribution/native run
[34010324585](https://github.com/bloxbean/yano/actions/runs/34010324585) passed
all three jobs; these do not substitute for final Phase 5 gate results.
The host build workflow now permits explicit milestone dispatch, so its full
build can be checked before merging a milestone into integration.

The companion's first exact-input remote run
[34011404923](https://github.com/bloxbean/yano-x/actions/runs/34011404923)
passed distribution and connector-fault checks, but failed its full build and
effect-failover gates. Preserve these as real failures:

- The evidence harness could not start: default-enabled Cardano historical
  projections attempted `/app/history` on its read-only root filesystem.
  Both harness topology templates now explicitly disable this unprovisioned,
  out-of-scope historical projection service. App-chain history, L1 state,
  effects and connectors remain enabled as configured; root filesystem
  protection was not weakened. The failover behavior itself was not exercised.
- The retained settlement templates were reproduced locally by an artifact
  labelled Julc `pre14`, but it differs from published `pre14`. Local compiler
  SHA-1 was `14b6cf0b2884c441dadc6ad86cdedeabd596bbd4`; published `pre14` was
  `605e9c7729531078f77f97aa3223fc8b0d285dbe`. This corrects Phase 4's assumption
  that a version-only local compiler pin was independently reproducible.
  Excluding **all Julc modules from Maven Local**, published `pre15` reproduced
  all four templates exactly (zero skips/failures, 2s diagnostic run).
  Its compiler SHA-1 matches Maven Central:
  `87ac86fa5cc95c1e04c92419247f691cf2c345c7`. The mandatory isolated verification
  task now pins published `pre15`; current-compiler conformance remains
  separate. No settlement source/template/script hash was regenerated.

Logs: `/private/tmp/adr-037-phase-5-effect-failover-remote.log`,
`/private/tmp/adr-037-phase-5-companion-remote-failure-complete.log`, and
`/private/tmp/adr-037-phase-5-published-julc-pre15.log`.

Companion local full tests/inventory/JVM-only gates against the new host passed
in 2m5s (223 tasks, 41 executed, 182 up-to-date) before the remote findings were
fixed. New fixture generation tests and all devtools tests also passed. The
schema golden change was diffed against the retained Phase 4 distribution:
only the two newly declared namespaces differ. The new configuration-only
fixture refuses existing targets, protects test keys with POSIX permissions,
uses five validators (`q=4,f=1`) and synthetic reporters, and disables all
spending/anchors. It does not launch nodes or prove live qualification.

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
restart/rollback guards and rejection of partial or unauthorized cursor repair.
The opt-in 100k-subscription test was skipped in this selection; a fresh scale
run with `YANO_OBSERVATION_SCALE=1` is required before claiming Phase 5 scale evidence.
Live qualification and final full-suite validation
remain pending.

## Coordinated CI staging work

Host staging run [34010953807](https://github.com/bloxbean/yano/actions/runs/34010953807)
passed for `7adff6bd8d0cf7eab7fa71627cfd6b1baafd5db5`. Companion full CI
[34011404923](https://github.com/bloxbean/yano-x/actions/runs/34011404923)
was dispatched against that exact commit and staged version. Staging-only
dispatch deliberately skips the ordinary integration/distribution/native jobs;
it is not evidence that those independent gates passed.
The artifact API reports an expiry of **2026-09-13T04:13:24Z** for this run,
shorter than the requested 14-day retention; use the actual deadline.

## Packaged configuration boundary finding

The full host suite passed in 11m47s (124 tasks: 39 executed, 2 cached,
83 up-to-date), logged at `/private/tmp/adr-037-phase-5-host-full-tests.log`.
That run began before the configuration fix, so it does not validate the new
configuration changes. The first focused forwarding regression failed because
the new flat fixture omitted its mandatory chain ID; the fixture was corrected
before rerunning. All four companion jobs successfully prepared the exact host
inputs, confirming cross-repository artifact access on the actual CI runners.

Preparing real-node configuration exposed missing `observations.*` and
`consensus.*` forwarding in both the REST-host adapter and shared chain parser.
Subsystem tests supplied plugin settings directly and therefore did not cover
this boundary. Without the fix, an ordinary configured host could silently lose
the enabled observation profile and the requested Byzantine fault bound.
The host adapter now shares the parser's namespace list, and the configuration
catalog declares both namespaces as core-owned with partial validation coverage.
Regression tests follow flat and indexed settings through both adapters into
the runtime configuration object. The existing singular `observation.*` L1
identity namespace is preserved. A new exact artifact checkpoint is required
before live qualification; the first staging checkpoint predates this fix.

Final focused rerun passed in 32s:
`/private/tmp/adr-037-phase-5-config-scale-tests-final.log`. This includes the
configuration module, host forwarding tests, parser-backed three/five-node
network tests, and the explicitly enabled 100k test. The latter has zero skips
and zero failures in JUnit XML; the unrelated opt-in live HTTPS case remains
skipped. Converting the network fixture also required its full existing state
identity (including fingerprint), now copied through `identity.settings()`.
These are configuration-boundary and local network checks, not live Preprod
or a sustained resource soak.

## Staging implementation

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
