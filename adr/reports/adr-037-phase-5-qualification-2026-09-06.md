# ADR-037 Phase 5 qualification — 2026-09-06

## Latest checkpoint and CI findings

Current executable candidate: host `e95817393`, companion `bd09f322`.
The entries below are chronological evidence; older pending/failure statements
are retained history, not the current gate status. Phases 0–4 are merged into
the integration branches. Phase 5 is not yet merged or graduated.

The current staged input artifact expires at `2026-09-13T09:22:41Z`, as
reported by GitHub (not the workflow's requested retention). It is temporary
qualification evidence, not a released publication. Local exact Maven/ZIP
inputs and deployed package checksums remain recorded below.

| Current gate | Status |
| --- | --- |
| Exact host Maven/JVM input and companion distribution | Passed locally and staged under exact full host commit |
| Host CI build / integration / distribution / native | Passed: `34024476267`, `34024477578` |
| Companion full CI | `34025873379` passed composite parity but failed role workflow; harness-budget experiment `34027879714` pending |
| Packaged five-node omission, later-view inclusion and automatic heal | Round 11 passed on e958/bd09 |
| Membership and operator recovery | Live 5→6→5, graceful upgrade and abrupt process recovery evidence retained |
| 100k indexed-subscription recovery | Final e958 opt-in rerun passed, 9.275s |
| Sustained cadence / resource qualification | Rounds 0–99 complete; final 88-round run, all 500 historical proofs and bounded idle-tail checks passed |
| Independent code/protocol review | Outstanding; both PRs have no submitted reviews and report `REVIEW_REQUIRED` |

### One hundred rounds and five hundred historical proofs completed

The final uninterrupted packaged cadence ran rounds 12–99 from
17:39:29 to 17:58:07 Singapore (18m38s), exiting zero. It completed 22 cases
each of full-source success, delayed reporter, source disagreement and source
unavailability: 44 VALUE and 44 EXPIRED outcomes, each with five verified
certified proofs. Together with the retained baseline, membership, equivocation
and withholding drills, rounds 0–99 are complete. This is not a claim that all
100 rounds ran uninterrupted on one package: the earlier upgrade/crash/fault
boundaries are recorded separately above and below.

All five finished at height 994/root
`460f2b52ebbaabc2f6392212e82774be0eb18704ecdb15fc77d9426f25d50ba5`, result
`ca10b357ca1c0eee493ff0722625b5d09578dbd71e954d63cdd7ebf72bb6e73a`.
The subscription completed (`activeSubscriptions=0`), with zero open rounds,
journal bytes/entries, workers, coordinator queue/reservations and wake hints.
All twenty directed app links remained connected.

The separate read-only final audit retrieved every historical outcome from
each of the five nodes and verified all 500 certified proof packages. Trust
contexts came from the original genesis/profile pins and retained
height-specific membership history, including the six-member interval; they
were not copied from response headers. Result IDs, heights and roots matched
the retained outcome records. Evidence: `final-100-round-proof-audit.log`,
`final-100-round-proof-audit/round-<n>.json`, its SHA-256 inventory, and the
retained `Adr037FinalProofAudit.java` source. It used the exact packaged SDK and
devtools, not a sibling source dependency.

Resource evidence: `cadence-rounds-12-99.jsonl`, `final-cadence-app-status.jsonl`
and the once-per-minute `resource-samples-1.jsonl`. Two terminal snapshots
(rounds 72–73) caught asynchronous cleanup in flight: at most 18,840 journal
bytes / 26 entries, 15 coordinator tasks and 39,048 reserved bytes. Later
snapshots and the final state drained completely. Do not describe every
terminal snapshot as zero. No sampled backpressure or wake hints occurred.
These are external-reporter rounds, not a live load test of HTTPS acquisition.

At 10:01:02 UTC, 155 post-upgrade process samples covered all five nodes over
30m12s without runtime degradation, with continued L1 progress and
`upstreamValidationLevel=none`. Whole-process RSS peaked at 1,072,256 KiB;
the complete app store (including retained block/proof history) reached
19,136 KiB. Those are process/store measurements, not observation-only memory
or journal sizes. The explicit 100k indexed-subscription test and worker/journal
capacity regressions supply the separate scale/bounds evidence; this live run
does not establish 100k simultaneous network acquisitions or production capacity.

The sampler was deliberately stopped after cadence completion and idle follow-up:
425 samples (85 complete five-node sets) span 08:36:35–10:01:02 UTC across the
recorded process generations. Its configured 180-interval maximum was not run
to exhaustion; this is not a three-hour soak claim. Final summary:
`resource-summary-e958-final.json`, with the retained `resource-summary.jq`.
The four honest external reporter journals each occupied 1,108 KiB; their
retained signing history was not discarded. The deliberately equivocating fifth
reporter's fault wires are separate from those honest journals.

During round-11 partition the proxy forwarded about 96.6 MB of combined app
protocol traffic, versus 5,052 unique signed-report bytes. This includes
consensus/control/re-diffusion traffic and must not be advertised as a
report-only amplification ratio. The counter grew from 114,102,310 at isolation
to 210,698,712 at heal. Post-cadence counter samples are retained separately to
check the idle tail; substantial fault-period traffic remains an operational
cost even though bounded queues recovered.
Two subsequent idle intervals added only 128,151 and 129,866 bytes of combined
app traffic (roughly kilobytes per second); all twenty links remained connected.
The final complete app-store sizes stayed unchanged through the idle samples,
and all five epoch-311 nonce responses still matched the pre-upgrade value.
This is a bounded observed recovery/tail check, not a long-term leak proof.

All five qualification processes shut down gracefully at 18:07:52 Singapore;
the proxy and resource sampler are also stopped. Final closed app stores,
configs, honest reporter journals and public identity/membership pins have an
additional private offline backup at `offline-final-backup.qxAxzW`. Original
L1/app stores, keys, logs and proofs remain intact. No signing identity was
started from a copied journal. Shutdown logs and closed HTTP ports were checked.

### Remaining composite CI failure is not closed by the app-peer fix

Companion `bd09f322` full run `34024811396` failed composite deployment parity
with `ANCHOR_UNAVAILABLE`. Build, connector-fault and distribution jobs passed;
release acceptance correctly failed. The role step did not run in this CI job;
the separately recorded local role pass is not substituted for it.
Log: `/private/tmp/adr-037-phase-5-bd09-companion-ci-failure.log`.

All three app nodes agreed at height 6/root
`0da74155b823b6c738d49688027e7b9a1a46b5e4c5ab4987405a4cfdd9a54c36`.
Leader and follower 1 observed the final anchor at L1 slot 893, while follower 2
remained at app-anchor height 5 / L1 slot 793. All three were bootstrapped with
the same identity; follower 2 had verified and witnessed the height-6 advance.
This differs from the previously reproduced unlisted-signing-subset candidate
defect. The available snapshots did not include L1 progress, so they do not
establish whether follower sync or anchor reconciliation stalled. No causal
claim or L1 fix is inferred from them.

Companion `598a6ceb` changes only the two failure-diagnostic harnesses and their
contract test: bounded public L1 progress/peer counters and bounded recovery
logs are captured before cleanup. Shell syntax and the contract pass. Full run
`34025873379` uses the same e958 staging input and unchanged test timeouts.
A local isolated composite parity run passed with the same exact prepared
artifacts: identical artifacts, semantics and retained replay behavior across
Compose and host JVM deployment, including restarts. Its owned temporary
resources were cleaned up. This pass does not establish the earlier failure's
root cause. Log `/private/tmp/adr-037-phase-5-e958-composite-local-e2e.log`.

### Role CI failure exposes L1 progress stall; recovery-budget experiment

Run `34025873379` completed with build, connector faults and distribution
checks passing. Isolated effect failover and composite deployment parity also
passed. The mandatory role workflow failed with `STORAGE_FAILED`, and release
acceptance correctly failed. Log:
`/private/tmp/adr-037-phase-5-598-companion-ci-failure.log`.

At failure all three app nodes agreed at height 39/root
`51dbb8ad13488142f9796d6d03c6014ad4461a3707941c08f14e3b8da37981b5`,
with app peer links connected. Follower 1's L1 tip remained at slot 620/block 10,
with application-progress age 623,481 ms, fresh keepalive age 8,127 ms, peer
state `RUNNING`, and no body fetch in progress. Leader and follower 2 had
advanced to slot 3723/block 72. Follower 1's anchor remained at app height 26;
the other two were at 30. All used default validation `none`, and none reported
runtime degradation. This establishes an observed follower L1 progress stall,
not its underlying cause or a proven pre-existing core defect.

Read-only inspection confirmed the existing supervisor is wired to its default
600-second no-progress threshold and 30-second fixed-delay polling interval.
Recovery is deferred during rollback or terminal recovery failure. The original
600-second scenario deadline can expire before the next eligible poll plus
recovery completes. That is a test-budget hypothesis, not a demonstrated cause
of `STORAGE_FAILED`; the scenario also uses that code for terminal storage
failure states.

Companion `09ba6523` changes only the CI workflow, role diagnostics and contract
test. CI scenario waits are bounded at 900 seconds; all proof, anchor and
cross-node acceptance checks remain unchanged. Role diagnostics now retain
bounded recovery logs on success as well as failure. Shell syntax and the
contract passed (`/private/tmp/adr-037-phase-5-recovery-budget-contract.log`).
Full run `34027879714` tests this hypothesis using the same exact e958 host
inputs and unchanged runtime binaries. No L1 source, supervisor policy,
upstream selection, producer pace or validation setting was changed. A green
run alone would not prove that the earlier stall recovered or establish its
cause; recovery must be identified in the retained diagnostics.

At 10:36 UTC both PRs still had no submitted reviews and reported
`REVIEW_REQUIRED`. Self-review and CI do not satisfy the independent
code/protocol review gate. Phase 5 remains unmerged and preview remains
disabled by default.

### Fixed-package live omission and automatic healing passed

Round 11 isolated node 3 while it led actual view 5 at opening height 112.
The packaged helper recorded `partition-view.json` before signing. Node 3
retained a ready certificate behind the TCP partition, then the exact twelve
signed report wires were released to the honest group. Four honest nodes
certified height 113 in view 6 before healing; result
`63d9efcfe59a31579d7506d1c1c8fc79ecf44cd6fb25d5bfffe599cb4b11dccc`, root
`eaf6a8ed09a150683c690e9e622c4992f333d6abbc23ee036b15bc17c39c417d`.
After `heal`, node 3 reconnected and caught up automatically without restart,
key replacement, journal reset, or any L1 changes. The helper exited zero with
five independently verified matching certified proof packages. All twenty
directed links subsequently returned; all five journals, queues, in-flight
workers and coordinator reservations drained to zero.

Evidence: `withholding-round-11.log`, and the same-named directory containing
injection statuses, actual-view pin, retained signed wires, ready-certificate
status, four pre-heal proofs, five post-heal proofs, heal control observation,
and `healed-all-links-status.jsonl`. Round 10 additionally now has five pinned
height-103 proofs in `all-five-certified-after-upgrade.json`; that remains a
separate upgrade recovery, not an automatic-heal pass.

The exact-input local role workflow passed authorization, recovery, proofs,
retained lifecycle idempotency and one-member catch-up; its owned temporary
containers/root were cleaned up. Log:
`/private/tmp/adr-037-phase-5-bd09-role-local-e2e.log`.
The final focused e958 host suite passed in 1m47s: 41 tests, zero failures,
one optional live-HTTPS skip; the explicit 100k subscription recovery case
passed in 9.275s without skipping. Log:
`/private/tmp/adr-037-phase-5-e958-final-qualification-tests.log`.
Host integration/distribution/native run `34024477578` passed.
The long-cadence driver now continues rounds 12 through 99, with per-round
five-node certified outcomes and resource snapshots; this soak is not yet
claimed complete. Independent review and the remaining CI gates stay open.

### Exact e958/bd09 package and retained-state recovery

Host `e95817393bdd6e7c01e764ee5a955ab67706e847` published locally with
explicit version `0.1.0-pre14-e95817393`; publication, ordinary JVM ZIP and
packaged-plugin smoke passed in 1m18s. Companion `bd09f322` pins that exact
version. Full local tests, artifact inventory, JVM-only and distribution checks,
plus the prepared evidence harness passed in 3m10s. Logs are
`/private/tmp/adr-037-phase-5-e958-exact-publication.log` and
`/private/tmp/adr-037-phase-5-bd09-exact-package.log`.

The extracted companion ZIP SHA-256 is
`f7e57e1fe0977ab3a5a51fc33db557a03fd9f2baf352a8a6c39947908c5d23aa`;
both manifests were checked against the exact host version. All five retained
Preprod nodes were gracefully stopped and their closed app stores/configs
backed up in private `offline-e958-backup.i7ujHd`. No L1 state or observation
journal was reset. They restarted from the new package at 17:30 Singapore.
Node 3 caught up from 102 to the other four nodes' height 103/root
`4a8e5ce240d1fa63f4d0e2b18fc0a3b15a24468c7f3e0344fe8e38664c40967e`;
all twenty directed proxy links connected. All five before/after epoch-311
nonce samples match
`177fbb46606547da6886817e8f17eb6afab4ff15da37fad6b57951c88d682763`.
Startup restored body block 5144333 / slot 133003777.
Evidence is retained under `upgrade-e958-before-*`, `upgrade-e958-after-*`,
and `node-<n>/cluster-e958-proxy-1.log`. This is upgrade recovery, not a
retroactive automatic-heal pass for round 10. A fresh round-11 omission drill
is running against the fixed package.

Staging run `34024478828` passed. Host build `34024476267` and
integration/native run `34024477578` remain pending at this checkpoint.
Companion full workflow `34024811396` consumes the exact successful staging
run and full host SHA. The earlier dispatch `34024803850` used a mistyped SHA
and was cancelled, not counted as qualification. A local isolated role E2E is
also running with the exact prepared inputs and bounded failure diagnostics.

### Live withholding found an app-peer reconnect defect

Round 10 opened at 102 on the e84/7ce package. The manual prompt selected node 3
in view 0, but the operator handoff was delayed. No reports were signed while
waiting. A bounded control loop waited until all five again identified node 3
as current proposer, then isolated it at actual view 5 (09:01:54 UTC).
`withholding-round-10/injection-before-status.json` and
`injection-after-status.jsonl` retain the actual boundary. Node 3 accepted the
twelve reports and retained a ready certificate with all four directed peer
connections down; identical signed wires were then delivered to the honest group.

Four honest nodes certified VALUE at height 103 in view 6, proposer
`6eb5dc55d4eb35df5a5ed96e5ad862e0d040c0541d157dedc03d6eece94511b8`, result
`63e3eb0ad66950301bc185f233296f9e070cd2419aae6cd93c55d56ee53fa5cd`, root
`4a8e5ce240d1fa63f4d0e2b18fc0a3b15a24468c7f3e0344fe8e38664c40967e`.
`honest-certified-before-heal.json` retains all four independently verified full
proof packages. Their views were explicitly checked as greater than actual
injection view 5 before healing, not just greater than prompt view 0.
Companion `2ab900ea` now pins the actual partition view before signing and uses
that stricter comparison. Seventeen focused qualification tests passed in 2s.

After `heal`, the isolated member did not automatically reconnect. The proxy
was healed but retained only the twelve unaffected links; node 3 remained at
102. `partition-node3-thread-dump.txt` shows app-peer Netty threads blocked in
`Session.handshake -> NodeClient.start -> SessionListenerAdapter.disconnected`.
The exact Yaci dependency's default auto-reconnect calls blocking start from
the close callback, and its running predicate only tests whether a session
object exists. The app wrapper can consequently mistake that stranded session
for a running transport. The waiting CLI was deliberately terminated, preserving
all evidence. Honest inclusion/view-change passed; complete automatic healing
did not. A real TCP reset/heal regression and app-layer-only recovery fix are
now implemented. The regression failed before the fix after 45 seconds without
reconnection (51s Gradle run); its full failure XML is preserved as
`/private/tmp/adr-037-phase-5-app-peer-before-fix.xml`.

Dedicated app transports now disable only their own library auto-reconnect and
propagate startup failure to the existing off-loop app supervisor. Socket
liveness, not mere session-object existence, controls replacement. The existing
five-second tick interrupts one stalled connector after its thirty-second
negotiation allowance and also replaces an unacknowledged protocol-100 session;
fully ready connections do not expire on that timer. Replay/ownership guards
remain intact. No L1 networking source or core validation changed.

The real TCP regression passed after the fix. Expanded peer lifecycle, two-node
smoke, stale-lock and observation-network tests passed in 2m8s: 19 tests, zero
failures, one skipped opt-in live HTTPS test
(`/private/tmp/adr-037-phase-5-app-peer-network-review.log`). The new fixture
needed the existing Netty dependency on its test compilation path; an older
mocking library could not instrument the Java-25 TCP class, so the unit uses a
small concrete transport instead. Those failed fixture iterations remain in
`app-peer-after-fix*.log`. New exact packages and live requalification remain
required before claiming the reconnect defect resolved on Preprod.

Companion 7ce full run `34022250059` ultimately failed the role workflow with
`ANCHOR_UNAVAILABLE`, after composite parity passed. Its initial bootstrap was
visible and adopted on all three members; the later publish command failed after
eleven minutes. The role harness did not emit useful failure status/log details
before cleanup. Bounded pre-cleanup anchor/status diagnostics and their shell
contract have been added and locally passed. The cause of that role failure is
not established by the separate live transport finding.

### Live membership activation checkpoint

After node 3 completed historical catch-up, `membership-cadence-retry-2.log`
completed both rounds, independently verifying all five certified outcomes:

| Round | Scenario / outcome | Height | Result ID | State root |
| --- | --- | --- | --- | --- |
| 6 | Source disagreement / EXPIRED | 69 | `8c5040661a3e301c0dd6c97ffc384cb9cb989514caaeac35955b4b4f0f45451c` | `79c233daa663792e391d52540f2e7c661e3de0b349e3f56f7916ae8b33455741` |
| 7 | Delayed reporter / VALUE | 74 | `2fe5542436caab6cbbef18a6880d7f2aefcea952b1dfb456ef9c0c9a69248650` | `a89b416ae6fac2a51c2ef4ac4d722de89f01d764a3f70ba3b617bc4adc6ae100` |

Evidence: `cadence-rounds-6-7.jsonl`. Round 6 opened under the original five
members and finalized after six-member activation at height 64; round 7 opened
under the new context. All observation journals, queues and coordinator
reservations drained. Node 3 reported `inSync=true`, default validation `none`,
and no runtime degradation. The explicit `remove-after-rounds` stage has started
from the verified height-74 checkpoint and completed at height 99.
Four certified removal approvals finalized at 75, activating the original
five-member set at 85. Round 8 certified VALUE at 83, result
`2ca91a6fac60bd39a61fde9e4fd7cc2a486252bef6bc5fc55131b9b7eece65aa`, root
`da9dcdfd3332d254918a972bceed68f8c1200314079ed49dd264f132f7465093`.
Round 9 certified EXPIRED at 99, result
`0bd03505846f8e45486d233a2961ff29189694fadf8f4113b9846ee9ec6d002e`, root
`ea745b6aaad3abf318da72dd7443a1e06025923919ea6943e16aa78c0bd755cc`.
Both have five independently verified matching proofs and drained journals.
Retained evidence: `membership-removal-1.log`, `membership-result.json`,
`membership-approval-remove-*.json`, `membership-epochs.json`,
and `cadence-rounds-8-9.jsonl`. The 5 -> 6 -> 5 live drill is complete.

At 16:54 Singapore all five nodes were gracefully upgraded to exact host e84 /
companion 7ce. Only package/plugin paths and app-peer destinations changed;
twenty directed loopback proxy links now target the original app servers.
All five reopened ready at height 99 with the unchanged root. Direct before/after
nonce captures matched epoch 311 and nonce
`177fbb46606547da6886817e8f17eb6afab4ff15da37fad6b57951c88d682763` on every node.
Startup restored body block 5144241 / slot 133001587 on all five. Evidence:
`upgrade-e84-*`, `node-<n>/cluster-e84-proxy-1.log`, `proxy-control-1.log`.
The round-10 withholding driver is waiting for an authenticated opening before
partition/signing; no partition pass is claimed yet.

Companion run `34022250059` passed its mandatory composite deployment-parity
step on e84, along with build, connector fault matrix and distribution checks.
The role-workflow/catch-up step remains running. This is the first remote
composite pass on the reproduced unlisted-anchor adoption fix; retain the older
intermittent failures rather than substituting a passing rerun for their record.

### Actual crash recovery and exact-package follow-through

At 16:17 Singapore, the cadence driver was stopped before any round-6 signing,
then the identified node-3 process was killed with SIGKILL. The identical
bf0/a12 package reopened the same retained stores, restored its committed body
tip (block 3390923, slot 89285471), and reopened app height 59 with unchanged
root `b641479219a4f1ab333efc3b4c7652a23a7ec335b5afe20ea7249d359f42bd77`.
The first successful endpoint poll matched pre-crash epoch 210 and nonce
`8777bd4839f8711a5b4de80968cad549ee8a0695ed26acbfa4db576972f8cd38`.
Its response is explicitly transcribed in
`crash-node-3-first-poll-observation.json`; the later direct capture was already
epoch 212 and is not a same-epoch comparison. Logs retain both transitions.
Subsequent body and app advancement resumed without runtime degradation.
This is a real process-crash recovery during historical catch-up, not a
certificate-ready signing crash. No L1 core or validation setting changed.

The interrupted empty round-6 attempt is retained as
`cadence-rounds-6-7-crash-drill-interruption.jsonl`. A new packaged c78 driver
started after all five converged at height 60; it authenticated round 6 at
height 62 and submitted its twelve disagreement reports. Membership activation
and the later removal still require complete certified outcome evidence.

Companion c78 run `34020835218` repeated `ANCHOR_UNAVAILABLE` on the old bf0
host; failure log: `/private/tmp/adr-037-phase-5-c78-companion-ci-failure.log`.
Host e84 staging run `34021884882` passed and retained the exact commit-named
consumer artifact. The initial local publication inadvertently used the default
snapshot label; it is not the exact e84 input. The explicit
`-Pversion=0.1.0-pre14-e84f2693b` publication and JVM smoke rerun passed in
3m21s (`/private/tmp/adr-037-phase-5-e84-exact-publication.log`), and its ZIP
manifest confirms the matching version. Companion `7ce03187` full CI run
`34022250059` consumes host e84 from successful staging run `34021884882`.
Host build `34021882495` and integration/distribution/native `34021883730`
passed. The companion's local full-suite, artifact inventory, JVM-only and
distribution gates passed in 3m52s against that exact Maven/ZIP pair
(`/private/tmp/adr-037-phase-5-7ce-exact-package.log`). All seventeen
qualification-tool tests have zero failures/skips. The companion ZIP SHA-256 is
`d2e1566ff81c03b8d2e8eb5bb88c2c5e3130a1a3ca4878dd036b110eff98e459`,
extracted under `/private/tmp/adr037-7ce-package.2htk96/`; not deployed yet.
The companion now has a directed-TCP withholding helper with
caller-pinned proof verification before and after healing, plus bounded
anchor-adoption diagnostics for failed composite CI. Its live drill remains
pending; unit tests are not evidence of a live partition pass.

The final e84 focused host qualification rerun passed in 1m35s with
`YANO_OBSERVATION_SCALE=1`: 69 tests, zero failures/errors, one skipped opt-in
live HTTPS test. The 100k committed-subscription case ran in 10.849s with no
skip. This selection includes journal child-process crash boundaries, local L1
journal compatibility, kernel/runtime, five-node network omission, DNS/HTTPS
bounds and all nine anchor tests. Log:
`/private/tmp/adr-037-phase-5-e84-final-qualification-tests.log`.

A bounded, once-per-minute public resource sampler started at 08:36:35 UTC.
`resource-samples-1.jsonl` retains per-node process RSS, app-store KiB and L1
tips/default validation. Initial RSS was 1,518,688..1,658,896 KiB and app stores
1,944..2,412 KiB. These include ongoing historical L1 sync; do not attribute
whole-process RSS to observations or call a baseline snapshot a completed soak.

At 16:39 Singapore both PR113 and companion PR6 still had no submitted reviews
or requested reviewers and reported `REVIEW_REQUIRED`. Implementation self-review
and passing CI do not satisfy the ADR's independent code/protocol review gate.

### Reproduced app-anchor subset adoption defect

Host checkpoint `86031c3dc` passed build `34020918143` and integration /
distribution / native `34020919530`. Companion run `34020423482` at `94694638`
again failed composite parity with `ANCHOR_UNAVAILABLE`: all three nodes
agreed on height 6 and root, while node 1 still had no adopted anchor identity.
The leader and node 2 had anchored height 6. This repeated failure is retained
at `/private/tmp/adr-037-phase-5-946-companion-ci-failure.log`.

Source inspection and a failing regression reproduced a specific app-layer
defect consistent with that symptom: `ScriptAnchorService.onSignRequest`
returned before verification/adoption whenever the local member was not a
required signer. Responsive-subset advances could therefore exclude a follower
from recording the exact transaction candidate, preventing later adoption.
The regression failed on `identityCandidatePending=false` before the fix
(`/private/tmp/adr-037-phase-5-unlisted-anchor-before-fix.log`).

The fix separates observation from witness signing. Unlisted members perform
the existing full verification and may retain a non-authoritative candidate;
they emit no witness. Promotion still requires the exact verified transaction
in committed L1 state. The regression rejects a below-threshold body, checks
no witness/submission, forbids adoption before confirmation, verifies confirmed
adoption and rollback clearing. All nine script-anchor tests passed in 18s
(`/private/tmp/adr-037-phase-5-unlisted-anchor-after-fix.log`). This fixes the
reproduced subset defect; the remote composite scenario must still be rerun
against a newly staged exact host package before claiming its failure resolved.
No Cardano L1 core source or validation settings changed.

### CI teardown correction and staged membership recovery

Host test checkpoint `75e799ab3` passed integration/distribution/native run
`34020262069`. Its build run `34020259935` failed after 8m45s: 1,526 runtime
tests, one failure, five skips. `CustomSignerProviderTest` completed its
assertions but failed temporary-directory cleanup with an open `ledger/signer-chain`.
Its terminal `@AfterEach` still called restartable `stop()`, which does not
join all deferred cleanup. It now calls terminal `close()`. An audit corrected
the same terminal-only call in fifteen nearby app-chain fixtures; intentional
stop/restart operations inside tests are unchanged. The sixteen-class focused
suite passed in 2m47s (`/private/tmp/adr-037-phase-5-terminal-fixtures.log`).
These are test lifecycle changes, not runtime or L1 core changes. Retained
failure log: `/private/tmp/adr-037-phase-5-host-review-ci-failure.log`.

Companion `3506da65` full run `34019843505` passed all five jobs, including
composite parity, role workflow and release acceptance. Subsequent exact-input
runs are `34020423482` (`94694638`) and `34020835218` (`c78f39aa`).

The first membership drill exposed a qualification-tool identity assumption:
the retained public manifest predates the redundant `effectiveGenesisId` field.
`94694638` derives identity from pinned chain settings and rejects a conflicting
optional identity. The failure occurred after submitting the four add approvals;
the explicit `recover-add-approvals` path verified their existing signed envelopes
and certified block-root inclusions on all five nodes, without resubmission.
All approvals finalized at height 54, pinning six-member activation at height 64.
The plan, four public approval proof packages and membership history are retained.

That recovery's cadence later hit its five-minute wait while node 3 was still
catching up to the L1 reference for app height 59. No round-6 reports had been
signed. No node state, L1 validation or protocol deadline was changed.
`c78f39aa` adds an operator-only 1..1800-second checkpoint wait override and a
separate removal recovery requiring five certified round-7 VALUEs at height 74.
Fifteen focused qualification tests passed in 4s with zero failures/skips
(`/private/tmp/adr-037-phase-5-bounded-recovery-tools.log`). Membership activation,
removal and subsequent live rounds are still pending.

### Retained round recovery and final runtime package

Round 5 subsequently passed on the upgraded package: the fifth reporter signed
two conflicting claims while the four honest journals remained separate.
All five nodes certified VALUE at height 53, result
`ef8aeb002e037ac8a63d0973d3d092edbc3c44585bb598120c4a4103f6695d51`, root
`a3af78f3c2d78a00f0507b11c09aef648250ee32d9f93e80d7346c4fac20c741`.
The expected median was 0.501 with three complete sources. All observation
journals, queues and coordinator reservations drained to zero. Retained evidence:
`equivocation-round-5.json`, `cadence-rounds-5-5.jsonl`, and
`round-5-equivocation-retry-1.log`. The first pre-sign timeout remains below.
The guarded membership drill has now started from this certified checkpoint;
no membership-transition pass is claimed yet.

Two further test-only gaps were closed without runtime/L1 source changes:

- A five-node TCP test holds a ready certificate on all nodes, deliberately
  omits it at the faulty proposer's height 3, then fail-stops that node. Four
  honest validators include the retained certificate at height 4 through
  certified view change; the restarted node catches up. Assertions identify
  both the faulty and subsequent honest proposers, quorum signatures, result
  and root parity. Focused pass: 33s; full cluster-suite pass: 58s
  (`/private/tmp/adr-037-phase-5-network-cluster-review.log`). This is a real
  networked subsystem test, not the separate packaged Preprod omission drill.
- Envelope fitting now has an explicit regression proving that an oversized
  earlier observation result cannot be skipped in favor of a smaller later
  result, both at the head and after a fitting prefix. This tests fitting,
  not certificate validity. Identity/envelope suite: 3 tests, zero failures
  or skips, 5s (`/private/tmp/adr-037-phase-5-envelope-order-rerun.log`). The
  first compile attempt required an AssertJ wildcard-list assertion correction.

Round 4 was explicitly recovered from its certified opening at height 42,
without resetting state or replacing signing journals. All five nodes certified
VALUE at height 43, result
`4559756e6e61869cda33337b9063593391382095ce989bc68e7b9e48cb1460bf`, root
`21d4d295f8ec5f83a3df282c72d5ff77999dfd6a7488ce33de379dd6cae67f81`.
Evidence: `round-4-recovery.log`, `cadence-rounds-4-4.jsonl` with
`explicitOpeningRecovery:true` in the retained qualification directory.

At 15:32 Singapore the five nodes were gracefully upgraded to host
`bf0a1d38faac5a5a7192c1b76bd2db43fe9453d4` / Yano X `a12fcb89`:
`/private/tmp/adr037-qualified-package.ZKdHZU/yano-x-jvm-0.1.0-adr037-a12fcb89`.
The JVM ZIP SHA-256 is
`6c3fd2ecf0c61927bdfb743f2b4174972510c72acc0095d1100b1e9206ddb7b0`;
its exact host version is `0.1.0-pre14-bf0a1d38f`. All five restarted ready
at height 43 with the same root. Keys, genesis, retained stores, public upstream
and default validation were preserved. Logs are
`node-<n>/cluster-qualified-default-1.log`. No L1 core files changed.

The first round-5 equivocation attempt timed out while advancing toward its
opening, before signing or submitting reports. All five subsequently converged
at height 47 with root
`efaf894367c2390b6d03f065f0c8c7d9a3fa91ec80e20193fc7247fd2352e340`.
Its empty evidence file was archived as
`cadence-rounds-5-5-pre-sign-timeout.jsonl`; `round-5-equivocation.log` remains
unchanged. A new attempt uses `round-5-equivocation-retry-1.log`. No successful
equivocation outcome is claimed yet. L1 remains in historical catch-up.

Host runtime checkpoint gates passed: build `34015009604`, integration /
distribution / native `34015010885`, and exact input staging `34015011910`.
Companion full run `34015241678` passed all gates on `a12fcb89` against those
host inputs. The earlier intermittent composite-anchor failure remains in the
failure history; a passing rerun is not proof of a root-cause fix.

Companion `3506da65` adds explicit opening recovery, independently verified
governance-message proofs and height-specific membership pins, plus a bounded
directed TCP partition harness. Focused tests passed; full tests, artifact
inventory, JVM-only and distribution checks passed in 3m27s
(`/private/tmp/adr-037-phase-5-drills-package.log`). Remote full run
`34019843505` is in progress. Membership transitions and the TCP harness have
not yet been exercised against this retained Preprod cluster.

### User-directed default L1 configuration

The user clarified that qualification must use the normal single-public-upstream
Yano defaults, with **no L1 core changes**. The fixture's explicit `praos-ledger`,
body-level and opcert-counter overrides were removed, and a regression test now
requires no `yano.upstream.validation.*` override in generated properties.
Devtools tests passed in 4s (`/private/tmp/adr-037-phase-5-default-validation-tests.log`).
The previous stricter-validation failure is preserved below but does not mandate
a Cardano core change within ADR-037. Its proposed nonce-skew cause remains
unconfirmed; the implicated code was unchanged by ADR-037.

At 15:27 Singapore time all five retained nodes restarted with the same binaries,
keys, stores and upstream. Their effective default validation level is `none`.
All restored their body-tip nonce, matched the saved epoch-162 nonce and resumed
body advancement, with no runtime degradation on the first poll. New logs are
`node-<n>/cluster-sync-default-1.log`; exact PIDs and commands are recorded in the
local `QUALIFICATION_RUN.md`. No L1 source code was changed. This is an
observation-framework qualification run, not a strict Praos validation claim.
At this checkpoint round 4 was still partially opened; the later explicit
recovery and package upgrade above supersede that state.

### Live qualification blocked at the Praos checkpoint

All five nodes rejected the first configured validation header, slot
`69638426`, block `2651408`, at `vrf-proof` with
`Praos VRF proof does not verify`. Each has zero accepted validated headers and
two or three rejections. The durable body tips remained around slots
`68386307`–`68453182`, with body nonce epoch 162. Reconnection did not repair
progress. Under the sync-validation workflow this is a qualification blocker,
not an acceptable source-fault result. Validation was not disabled or weakened.

Source inspection identifies a likely prerequisite sync defect:
`RuntimeNode.epochNonceForHeaderValidation(slot)` directly calls the body-owned
`EpochNonceState.previewEpochNonceForSlot(slot)`. For every future epoch that
preview combines the current candidate and previous-hash nonces only once; it
does not reconstruct intervening epochs. Pipelined headers may lead bodies by
50,000 blocks. The rejected header is several epochs ahead of the body state.
This is a strong causal hypothesis, not yet a reproduced/fixed cryptographic
verification result. Resolving it requires separately scoped Cardano sync and
nonce validation work, not an observation certificate acceptance change.

The cadence driver was stopped before further fault traffic. Four nodes had
opened round 4 at app height 42 and node 2 remained at 41; all height-42 roots
matched `6b88a150b82aa3a616614afe9cd1f6119261eac2cb2c1d9fc37c82dc643ac763`.
Round 4 has no submitted driver reports and no claimed successful outcome.
All five exact owned JVM PIDs were gracefully stopped at 13:57 Singapore time;
shutdown completed normally. Stores, keys, logs and partial evidence remain
under `/Users/satya/Downloads/yano-cluster/adr037-phase5-preprod`.
Public snapshots are `validation-failure-node-<n>-status.json`, matching nonce
files, and `validation-failure-app-status.jsonl`. Do not reset or silently resume
this partly opened round through the pristine cadence entry point.

Host framing checkpoint `bf0a1d38f` published locally with catalog smoke in
2m1s and staged remotely in run `34015011910`. Companion `a12fcb89` adds an
explicit one-round adversarial fifth-reporter mode (two conflicting signed
claims, retained public wire evidence, four untouched honest journals). Its
focused tests passed; the full exact-pair tests/inventory/JVM-only/distribution
build passed in 3m4s. Log:
`/private/tmp/adr-037-phase-5-framing-companion-package.log`.
Full remote companion run `34015241678` is pending. The adversarial mode has
**not** been exercised live because the sync blocker was discovered first.
Neither this new host nor the new companion has replaced the retained live
package. Phase 5 remains incomplete and unmerged.

Companion rerun `34014598668` subsequently passed build, distribution, connector
faults, effect failover and composite deployment parity, and entered role-workflow
E2E. The earlier missing-follower-anchor run remains a recorded intermittent
failure; a passing rerun is not a demonstrated repair. Final framing host run
`34015010885` has passed integration/distribution; native and the separate build
run `34015009604` remain in progress at this checkpoint.

### HTTPS framing and scheduling follow-up

The IPv6 transport host is now unbracketed for DNS/TLS, while the HTTP Host
authority retains exactly one bracket pair and any explicit port. Endpoint
syntax is checked before consuming its host. Header validation rejects control
bytes before trimming, DEL, signed Content-Length values, and non-token version
header names. The 32 KiB header bound includes the complete terminator; an
exact-boundary response succeeds and a one-byte excess fails.

The focused selection passed in 22s: ten provider tests, six DNS tests and the
explicitly enabled real HTTPS/three-subsystem test, with zero skips/failures.
Log: `/private/tmp/adr-037-phase-5-http-framing-tests.log`.
Additional kernel regressions cover canonical equal-due ordering across bounded
batches and whole-result rollback for non-future callback subscriptions.
The complete kernel selection passed in 37s (16 tests, zero skips/failures),
including the explicitly enabled 100k-subscription case. Log:
`/private/tmp/adr-037-phase-5-kernel-framing-checkpoint.log`.

Host `7696f2c9d` integration, native and distribution run `34014402584` passed;
its staging run `34014403520` also passed. The companion exact-pair local test,
inventory, JVM-only and distribution build at `1cec9dd8` passed in 5m58s
(`/private/tmp/adr-037-phase-5-dns-companion-package.log`). These checkpoints
precede this framing follow-up and do not qualify its final artifact.

Companion run `34013848901` passed build, distribution, connector faults and
the effect-failover step, but failed composite deployment parity with
`ANCHOR_UNAVAILABLE`. All three app roots matched at height 6; leader and node 2
had confirmed height-6 anchors while node 1 still exposed no adopted identity.
The log is retained at
`/private/tmp/adr-037-phase-5-composite-parity-failure.log`. This gate remains
failed pending diagnosis; no anchor acceptance condition has been relaxed.

Live delayed-reporter round 3 produced five verified VALUE proofs at height 34,
result `a907f48c0e06a8cb7326a4edb5559e3d3dfd6556af2f40219228f2400edea319`,
root `d6a6549219be2d2204e61b098f69fda4383bfb7b48023f6683ea377f6f033e10`.
All five journals and coordinator queues drained. This remains earlier-package,
historical-catch-up evidence, not completed Phase 5 qualification.

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

### Reviewer navigation for ADR section 23

This is an entry-point map, not an independent review sign-off or a claim that
every listed subcase is live-qualified. Host test names are under `core-api`
or `runtime`; companion test names are under its stdlib/SDK/devtools modules.

| Matrix area | Concrete evidence entry points | Qualification boundary |
| --- | --- | --- |
| Codec / identity | `ObservationWireTest`, `ObservationProfileGuardTest`, `AppChainEngineIdentityValidationTest` | Canonical records, replay/domain guards and malformed envelopes are local tests |
| Signatures / policy | `ObservationWireTest`, `ObservationAttestationTest`, companion `AdaUsdReferenceRuntimeTest` | Live round 5 additionally retains conflicting fifth-reporter wires and five certified matching outcomes |
| Scheduling | `ObservationKernelTest`, `AppChainEngineIdentityValidationTest` | Includes same-due batches, non-future callbacks, inclusive grace, restart cadence, ticks and oversized earlier-envelope ordering |
| Persistence | `ObservationJournalCrashTest`, `ObservationJournalTest`, `ObservationKernelTest`, companion `ObservationReporterJournalTest` | Child-process halt boundaries are distinct from the live node-3 catch-up crash |
| Transport | `AppChainSystemTopicAdmissionTest`, `ObservationRuntimeTest`, `ObservationRuntimeClusterTest` | Exact allowlists, early activation handling and network diffusion; do not equate a local TCP test with packaged Preprod |
| Consensus | `ObservationKernelTest`, `ObservationRuntimeClusterTest`, `AppChainStaleLockTest` | Local five-node omission/view-change/recovery passed; packaged withholding is pending |
| Provider failures / security | `RestrictedHttpsObservationProviderTest`, `ObservationDnsResolverTest`, `ObservationAttestationTest` | DNS bounds, total deadline, address filtering, framing, compression/media-type rejection and forged/replayed attestation |
| Scale | `ObservationKernelTest.recoversOneHundredThousandCommittedSubscriptionsWithBoundedOpening`, journal/runtime resource tests | Opt-in 100k case was explicitly run; live resource sampling is ongoing, not a completed soak |
| Compatibility / workflows | Full host tests and packaged catalog smoke; companion full distribution and mandatory deployment-parity gates | Latest companion end-to-end gate must finish on e84; earlier bf0 anchor failures remain retained |

### Historical starting ledger

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
