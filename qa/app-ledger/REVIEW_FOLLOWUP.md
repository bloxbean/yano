# Issue 245 external review follow-up

This is incremental review and local integration validation. The historical
[Phase 2 results](PHASE2_VALIDATION.md) remain tied to their original inputs;
they do not claim to cover these later changes. Final coordinated release
qualification still runs once after both phases and production pins are ready.

## Review disposition

- [Yano review](https://github.com/bloxbean/yano/pull/246#issuecomment-6875815179):
  reject all known Cardano network identities using the existing Yaci network
  inventory plus Yano's devnet default; reuse unavailable Cardano facades per
  assembly; validate standalone transport using effective per-chain settings;
  wrap the identified long lines.
- The reported retry-after-failed-start regression is not a change introduced by
  extraction. The previous plugin manager and kernel already made a failed start
  terminal. Regression tests preserve that behavior and demonstrate recovery by
  constructing a fresh node over the same stores in both modes. Clean stop/start
  remains supported. Review did uncover a separate integrated-mode defect:
  required framework plugins must start even when optional plugins are disabled.
  A real Cardano assembly test checks start/stop/restart and exactly one managed
  L1 block/rollback subscription per running cycle, with no optional callbacks.
- [X review](https://github.com/bloxbean/yano-x/pull/102#issuecomment-6875815480):
  remove the unused second protocol-registration loop. The existing host adapter
  remains the single owner, including cancellation checks.
- [X codec review](https://github.com/bloxbean/yano-x/pull/102#issuecomment-6872251025):
  derive malformed-length/UTF-8 mutation offsets from the canonical domain bytes
  and assert the intended rejection reason, avoiding false-positive tests.
- [Yaci review](https://github.com/bloxbean/yaci/pull/245#issuecomment-6875815754):
  retain the legacy Cardano constructor's list ownership/null-element semantics;
  the new app-only factory alone snapshots its factory list.
- [Yaci handshake review](https://github.com/bloxbean/yaci/pull/245#issuecomment-6872028970):
  validate app-only negotiated version and magic, gate protocol 100/103 traffic
  until negotiation succeeds, and close rejected sessions. Unsigned 32-bit magic
  and large integer decoding must not truncate or alias a valid version.
  Independent review added client-side gating and reconnect reset tests.
  The tests also exposed missing refusal serialization; Yaci now writes the
  standard `[2, reason]` message using its existing reason serializers. Golden
  bytes and wire decoding cover all three refusal reasons.
- [Publication durability review](https://github.com/bloxbean/yano/pull/246#issuecomment-6871976438):
  ADR 063 D7 now explicitly requires future adapters to replay durable finalized
  history with destination-specific progress/outbox state. This change adds no
  generic publisher; existing Cardano anchor reconciliation remains unchanged.

The unavailable upstream Yaci release/pin is deferred at the owner's request.
Local verification uses distinct immutable candidate coordinates in file-backed
Maven repositories; no released version is overwritten in Maven Local.

## Validation

The review candidate uses Yaci `0.5.0-pre15-app-only-245.3` (commit
`39901ea8`), X framework `0.1.0-245.10` (implementation commit `7edfc187`),
and the unchanged Host API `1.0.0-245.4`. X is compiled against node API
`0.1.0-245.4`. All artifacts are staged in isolated file-backed Maven repositories.

- Yaci: 111 passed, four existing skips; independent cross-review approved the
  client/server gates and refusal wire encoding.
- X: 409 framework/proof tests passed, 60 existing opt-in compiler skips;
  dependency boundaries and artifact inventory passed.
- Frozen oldest host `0.1.0-245.5` / Host API `.4`: both standalone and Cardano
  construction profiles passed with X `.10` / Yaci `.3`, using unchanged client
  bytecode and host bytes. Evidence is retained in X's supported-host fixture as
  `independent-framework-10.json`.

Refreshed Yano gates (overlapping suites, not an additive total):

| Gate | Result | Tests | Skipped |
| --- | --- | ---: | ---: |
| host | PASS | 82 | 0 |
| standalone | PASS | 13 | 0 |
| hybrid | PASS | 61 | 0 |
| plugins | PASS | 258 | 1 |
| adapter | PASS | 24 | 0 |
| configuration | PASS | 419 | 0 |

Both pre19 ABI comparisons and unchanged compiled compatibility clients pass.
Source fingerprints were unchanged during every gate. Full local evidence is in
`/private/tmp/yano-245-review-gates-2`.

An initial new lifecycle test incorrectly expected a subsystem-only status field
on the public engine gateway and did not enable sequencing. The corrected fixture
uses the public running status, enables sequencing, and preserves exact managed
L1 subscription counts across two start/stop cycles. Production behavior was not
changed to accommodate the fixture. Failed attempts remain in separate logs.

Fresh Yano JVM/native packages, X distribution validation and the requested
Haskell-sync skills follow these focused gates; their results will be recorded
separately after completion.
