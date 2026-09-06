# ADR-037 Phase 3 implementation report

Integration: `feat/generic-observation-framework-adr`.
Milestone: `milestone/adr-037-phase-3`.
Status: implementation, implementation self-review and local milestone validation complete.

## Delivery and safety decision

- Canonical signed-128-bit fixed-point numbers, explicit scale 0–18, strict
  decimal parsing, exact arithmetic and bounded encodings.
- `complete-source-median-v1`: an exact intersecting reporter quorum for every
  pinned logical source, alias-group lower medians, bounded deviation filtering,
  minimum independent-group coverage, and a final lower median.
- Definition-pinned external reporter keys with independent `p/g/r` limits;
  gateway validators neither acquire nor sign external claims.
- Bounded chain-scoped external report ingress and strict, canonical inner
  signature/identity/evidence verification. HTTP 202 is queue admission only.
- Host API level 6 and synchronized catalog/conformance expectations.
- Companion Yano X SDK journal, report-submission client, manifested ADA/USD
  reference plugin, reference guide, and ADR-012 report/round migration.

The selected policy is certificate-monotonic, not a median of whichever
reports arrive first. For each required source, `2r-p>g` makes conflicting
terminal claims require an honest reporter to double-sign. Complete source
coverage fixes the vector before the pure group/filter/median function runs.
Missing/stale sources prevent certification; their absence is never an
unavailability attestation. Partial-source/latest-assertion policies remain
disabled pending a separately reviewed sequenced closure protocol.

Policy migration is explicit cancel-and-re-watch against another already
profile-authorized definition. Dynamic per-round selection of arbitrary
governed parameters is not implemented. Old rounds retain their pinned
definition and reporter authority.

## Review findings and iteration

1. The original non-monotonic oracle median needed an unavailable report-set
   closure proof. Both ADRs now select the complete terminal-source proof and
   state its liveness tradeoff explicitly; superseded wording was aligned.
2. A certificate for several sources carries `r × sources` reports, not `r`.
   The policy specifies the required count; verifier and builder agree on it.
3. A gateway member is not an external reporter. Host membership and external
   reporter authority are separately pinned and separately enforced.
4. Result `reporterCount` originally counted report records, overcounting
   multi-source reporters. It now counts distinct public keys; source count
   counts distinct logical source IDs. Tests assert 12 reports, 4 reporters
   and 3 sources.
5. A durable journal record with equal bytes might have survived a failed
   fsync. The SDK re-forces retained identity/claim files and the directory
   before allowing signing, and preserves the original failure on cleanup.
6. Queue receipts needed strict duplicate-field/trailing-token rejection in
   addition to a bounded body and exact digest/chain/status checks.
7. A test used a pre-resolution genesis identity. It now obtains the runtime's
   resolved commitment identity, as real reporters must do.
8. The reference expiry test initially stopped inside the inclusive result
   grace window. It now advances past that window and checks the terminal
   non-value result, without changing the protocol to satisfy the test.
9. This worktree's automatic snapshot hash suffix was absent. Coordinated
   artifacts are explicitly versioned from the checkpoint commit, and the
   Yano X distribution gate verifies Maven/ZIP/JAR/manifest identity equality.

## Validation evidence

- Full Yano repository `test` passed in 11m 13s before the final distinct-count
  correction. The affected core/runtime suites and signed five-node aggregate
  test passed after that correction (26-second focused run).
- All 125 combinations of four-of-five reporter subsets across three sources
  yield identical output under shuffled report order. Tests cover aliases,
  outlier removal, incomplete coverage, duplicate/conflicting/stale claims,
  scale mismatch, canonical encoding, truncation, trailing data and source order.
- Signed-128-bit extrema exercise subtraction, absolute value and ppm
  multiplication without wrapping, including negative preliminary medians.
- Two independently constructed, valid signed source-report subsets prove the
  same result ID with different certificate digests. A real signed conflicting
  source claim fails to establish a second source quorum (3-second focused run).
- Five network-connected validators (`n=5,q=4,f=1`), separate external reporters
  (`p=5,g=1,r=4`), non-proposer ingress, signed finality and cross-node root/audit
  equality passed. Gateway acquisition remains zero.
- SDK tests include abrupt child-process exit before and after signing,
  conflicting-choice refusal after restart, incomplete records, owner/identity/
  capacity checks, malformed receipts, oversized responses and HTTP rejection.
- Yano X's full SDK and standard-library suites plus artifact-inventory and
  JVM-only gates passed against the final host checkpoint in 44s.
- The reference plugin test uses real Ed25519 reports and the SDK journal,
  normal plugin provider activation, host certification, application/audit
  queries, retained-root restart, and a subsequent missing-source expiry.
- Exact host publication/JVM ZIP/catalog smoke passed in 1m 34s; the assembled
  Yano X JVM/plugin-pack distribution check passed in 1m 22s.
- Native distribution/catalog smoke passed in 2m 29s using Oracle GraalVM
  25.3.4.1+1.1 on macOS arm64. `git diff --check` passed.

## Provenance and remaining gates

Host implementation checkpoints: `c31198b24`, then corrected `1d9a6a7ff`.
Tested host Maven/ZIP version: `0.1.0-pre14-1d9a6a7ff`.
Yano X implementation checkpoint: `4335787b`; assembled version
`0.1.0-adr037-4335787b`.

Companion work is isolated in `/private/tmp/yano-x-adr-037-phase-3`, branch
`milestone/adr-037-phase-3`, with no edits to the root Yano X checkout.
Tracking: [Yano X issue #5](https://github.com/bloxbean/yano-x/issues/5) and
[PR #6](https://github.com/bloxbean/yano-x/pull/6).

The host input is currently locally published, not a public release. Remote
Yano X CI/release consumers need coordinated artifact availability before
merging that companion PR into a release branch. No remote Yano X CI success,
independent review, real market-source truth, or five-node Preprod qualification
is claimed. Those qualification/release gates remain Phase 5. The feature is
preview and disabled by default; the reference is synthetic and does not
implement the full governed oracle/Cardano publication product.

Remote [Yano X run 34006838066](https://github.com/bloxbean/yano-x/actions/runs/34006838066)
failed because repositories cannot resolve the locally published
`yano-core-api:0.1.0-pre14-1d9a6a7ff` input. The log confirms artifact
availability is the failing prerequisite; downstream release acceptance also
fails. This is retained as an open qualification dependency, not hidden by a
skipped or weakened CI gate.
