# ADR-037 independent-review remediation

Review base: PR #113, `5333e4e3967708a30e22ec5aa98bbd3bbd08d42d`.
Working branch: `fix/adr037-review-remediation`.
This report supersedes any earlier claim that green Phase 5 qualification alone
established implementation acceptance. Claude's independent review requested changes.

## Verified findings and changes

| Finding | Verification and remediation |
| --- | --- |
| B1: cancellation overwritten by a certificate | Confirmed. The new observation-callback regression failed on the reviewed code (two callbacks instead of one). Certificate conflict classification still uses committed state; mutation now consults staged subscription status/round first. Tests cover both codecs, observation and effect callbacks, terminal counters, restart audit and root-equal offline replay. |
| M1: unpinned attested/Merkle logical source | Confirmed. Both source-digest helpers now include explicit `sourceId`; custom attested adapters use `attestedSourceDigest`. Settings pin and verifiers enforce the source, and HTTPS acquisition rejects another source. Tests include otherwise-valid signatures/Merkle paths for an unconfigured source. Preview profiles need regeneration. |
| M2: result envelopes retire pending ordinary messages | Confirmed. Result and tick topics each have a separate member replay domain in admission, strict block validation and atomic ledger-floor updates. All retain positive sequences. Restart restores the maximum owned floor; ephemeral observation diffusion uses a separate allocator. Regression tests cover ordinary floor preservation and strict ordering within, but not across, those domains. |
| M3: re-diffusion storm | Confirmed. Profile-derived per-second count/byte budgets, bounded FIFO/recent caches, ten-second content cooldown, rotated round starting points and unexpired outer-envelope reuse replace unbounded re-sign/re-relay on every tick. Queued output rechecks terminal state before sending. Durable journals remain the retry source. |
| M4: anonymous shared-coordinator ingress | Confirmed. Report/wake routes again require an unscoped full API key regardless of broad-auth mode. External reports reserve a separate eight-check budget and at most 16 admissions/second before decoding/crypto; overload is 429 and does not consume the member queue. Retry tests use identical signed bytes with backoff. |
| M5: missing tick admission rules | Confirmed. Tick identity ignores envelope sequence; one latest anchor is retained per member for 60 seconds, including briefly after finalization. Profile-derived pool cap is at most 1024/16 blocks, further restricted to one quarter of the ordinary pool. Block cap remains enforced. Malformed/disabled/zero-sequence generic inputs fail admission. |
| M6: empty codec-v1 observation index passes startup audit | Confirmed. Every commit now stages an observation-index height watermark in the same batch. Audit requires it to match the ledger tip whenever the authenticated profile exists. Wiped-CF tests cover both codecs; replay/repair carries the watermark. No silent backfill from the current tip is allowed. |

Additional justified changes:

- A6: bound round/grace durations to signed 32-bit heights instead of allowing
  malicious configuration to overflow the first scheduling calculation.
- A7: document that offline rebuilding is a library operation requiring the
  matching machine, profiles and height-specific membership, not an existing CLI.
- B2/B3: generic certificate verification enforces the round freshness window
  independently of a custom evidence verifier; VALUE records validate their
  payload digest. New negative tests isolate both checks.
- B4: correct the v2 subscription field indexes and profile definition-count
  bound; document numeric/ID and canonical re-encoding constraints.
- C6: positive sequences are enforced for generic system inputs even when
  ordinary sender-sequence enforcement is disabled.
- D4: add private/special IPv4 range coverage, including 172.16/12 and 192.168/16.
- D9: document the API-level 4-to-8 compatibility boundary.
- D10: move artifact staging to its own workflow. A staging-only dispatch no
  longer creates skipped integration/distribution/native jobs. The Yano X
  provenance fixture accepts the new workflow (and retains historical staging
  artifacts from the prior workflow). Read-only GitHub checks found no required
  status checks on the active `main` ruleset; repository protection was not changed.

## Release and review boundaries

D1 is a real release decision, not a code defect to hide. The operator guide
now states that the consensus-context/profile cutover affects **all** app chains,
including disabled observations and pre-existing Preprod SCRIPT anchors. Wiping
and resyncing an old chain does not make it compatible. Previously ignored
`consensus.*` settings become effective. No migration, chain reset, new genesis,
anchor reset, or retained-cluster restart has been performed or authorized by
this remediation. Maintainer approval of the fresh-chain-only release remains
outstanding. The ADR is Proposed / implementation under review, not self-accepted.

The existing one-candidate-per-round journal remains a deliberately stronger
signing constraint for exact-source acquisition. The new source binding makes
the certificate's source identity explicit; it does not prove that a trusted
attestor never lies or equivocates.

Not every minor observation justifies a semantic change here. Hash compositions
already framed by the surrounding domain-separated records are not being
redesigned merely to add another domain string. Committed-state reads used to
stage derived index deltas remain serialized with commit; no independent
consensus read path is being introduced. Open-round scanning is bounded by the
profile, but is not a claim of constant work per block. Non-cooperative custom
provider/signer shutdown remains a trusted-plugin lifecycle limitation and
needs a separate lifecycle design before returning while code could still
touch a closing ledger. These are not marked fixed by this patch.

The prior out-of-scope Praos/VRF hypothesis remains unverified and is not an
ADR-037 defect diagnosis. L1 core, validation mode, upstream selection and
retained Preprod data are unchanged. The previously observed native Kafka crash
is not attributed to this framework or claimed fixed.

## Validation record (in progress)

- Original B1 regression: failed before the fix; passed after it.
- Initial focused observation-kernel suite: passed (100k opt-in skipped).
- Source binding, index audit and sender-floor focused suite: passed before
  subsequent rate-limit/negative-test additions.
- Expanded runs exposed outdated tests expecting unbounded ingress/re-diffusion;
  tests were changed to exercise the intended bounds, not remove assertions.
- Core API full suite passed after correcting a negative-test reporter fixture.
- Yano X staging-input shell contract: all ten cases passed.
- `YANO_OBSERVATION_SCALE=1 ./gradlew :core-api:test :runtime:test
  --tests '*Observation*Test' --tests '*AppChainSenderSeqTest'
  --tests '*AppChainSystemTopicAdmissionTest' --tests '*AppChainKeyRotationTest'
  :app:test --console=plain --continue`: PASS. Core API 310/310; selected runtime
  111 tests, zero failures, one opt-in live-HTTPS skip; HTTP/application 339/339.
  The 100k test was enabled and passed (kernel class: 20 tests, no skips).
- Final tick-clock/expiry and attestation-fixture adjustments:
  `./gradlew :runtime:test --tests '*ObservationAdmissionLimitsTest'
  --tests '*ObservationAttestationTest' --console=plain`: PASS.
- Earlier full runtime run: 1538 tests, one failure, five skips. The failure was
  `AppChainKeyRotationTest.stagedRotation_guards_andPersistsAcrossRestart`:
  RocksDB LOCK still held at immediate restart. It passed in the focused rerun;
  six isolated runs on untouched head `5333e4e3` also passed. Exact attribution
  remains unproven. Full baseline comparison and final package validation are
  still in progress; do not relabel the initial full run green.
- Initial full-run XML is retained at
  `/private/tmp/adr037-review-results.3Kn4hZ/runtime-full`.
- Workflow YAML parsing, shell syntax and `git diff --check`: PASS.
- PR #113 title/body now describe the implementation and outstanding review,
  not a docs-only change. The historical Phase 5 report has an explicit
  independent-review correction and no verified Praos diagnosis is claimed.

Do not treat this working report as a green final validation or independent
approval of the remediation. Final commands and results will be appended once
the current tree has been tested.
