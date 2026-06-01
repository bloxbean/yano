# ADR-028: Conway Committee Ratification Semantics

## Status

Proposed

## Date

2026-05-31

## Context

Yano's Conway governance ratification path must distinguish two different ledger
concepts that look similar if we only inspect the current committee member map:

```text
Committee absent
  Haskell ledger representation: SNothing
  Meaning: no-confidence state

Committee present
  Haskell ledger representation: SJust Committee
  Meaning: normal committee state, even when the member map is empty or all
           members are currently inactive
```

This matters for governance action ratification. The Haskell ledger chooses
some thresholds and committee-vote behavior from committee presence, not from
whether there are currently active voters.

Yano previously inferred committee state from member activity. That worked for
some real-network cases, but it diverges from Haskell semantics and breaks
Yano-only devkit scenarios where Conway genesis intentionally starts with an
empty committee and committee threshold `0/1` so governance actions can be
enacted quickly.

Yaci Store's runtime path already handles this more explicitly: it stores a
committee state and feeds ratification with committee members that are already
active and hot-key authorized.

Yano has not been released yet, so it is acceptable to change governance state
persistence and require affected users to resync from genesis instead of adding
a compatibility migration for old unreleased state.

## Problem

Yano has four committee-related divergences from ledger behavior:

1. Committee state is partly inferred from active member count.
2. `UpdateCommittee` DRep and SPO thresholds always use the normal committee
   threshold, even after a `NoConfidence` action.
3. Committee min-size checks count non-expired, non-resigned cold members even
   when they have no authorized hot key.
4. Devkit validation can be misleading if the runtime local-cluster genesis has
   empty committee threshold `0/1`, while a tracked Yano devnet genesis has
   empty committee threshold `2/3`.

These issues are related but not identical. The fix should keep the blast
radius small and preserve real-network behavior.

## Examples

### Example 1: Empty Genesis Committee Should Still Be Committee-Present

Devkit Yano-only mode can start Conway with:

```json
{
  "committee": {
    "members": {},
    "threshold": { "numerator": 0, "denominator": 1 }
  }
}
```

Ledger meaning:

```text
committee = SJust Committee { members = {}, threshold = 0/1 }
state     = NORMAL / committee-present
```

Wrong behavior:

```text
members = {}
Yano derives committee state as NO_CONFIDENCE
ParameterChange committee check fails
PV11 protocol parameter action remains active
latest epoch params still show old PV10 cost models
```

Required behavior:

```text
committeePresent = true from Conway genesis bootstrap
members = {}
committee threshold = 0/1
committee vote auto-passes
ParameterChange can ratify and enact when DRep/SPO rules also pass
```

### Example 2: UpdateCommittee After NoConfidence Uses NoConfidence Thresholds

Assume protocol thresholds:

```text
dvtCommitteeNormal       = 0.67
dvtCommitteeNoConfidence = 0.60
```

After a `NoConfidence` action is enacted:

```text
committeePresent = false
```

An `UpdateCommittee` action then receives DRep support:

```text
yes / (yes + no) = 0.62
```

Wrong behavior:

```text
Yano evaluates UPDATE_COMMITTEE with dvtCommitteeNormal = 0.67
0.62 < 0.67
proposal fails or remains active
```

Ledger-aligned behavior:

```text
Previous committee state is NO_CONFIDENCE
Use dvtCommitteeNoConfidence = 0.60
0.62 >= 0.60
DRep threshold passes
```

The same rule applies to SPO thresholds:

```text
pvtCommitteeNormal vs pvtCommitteeNoConfidence
```

### Example 3: Hot-Key Authorization Affects Active Committee Size

Assume:

```text
committeeMinSize = 3
committee members:
  cold1 -> hot1
  cold2 -> hot2
  cold3 -> no hot key
  cold4 -> no hot key
  cold5 -> no hot key
```

Wrong behavior:

```text
Yano min-size activeCount = 5
committeeMinSize check passes
```

Ledger-aligned behavior:

```text
activeCommittee excludes members without hot-key authorization
activeCount = 2
2 < 3
committee check fails
```

Yano's vote tally already excludes members without hot keys. The min-size check
must use the same active committee definition.

### Example 4: Present But All Inactive Is Not NoConfidence

Assume committee is present, but all members are expired or resigned:

```text
committeePresent = true
members = {
  cold1 expired,
  cold2 resigned
}
```

Wrong behavior:

```text
Yano derives NO_CONFIDENCE because no active members exist
UpdateCommittee threshold selection may use no-confidence thresholds
Other committee-gated proposals fail for the wrong reason
```

Ledger-aligned behavior:

```text
committeePresent = true
state = NORMAL
committee voting fails through active-size or threshold checks
```

This keeps the representation aligned with the ledger: absence is
no-confidence; inactivity is handled by committee voting rules.

## Decision

Yano will use explicit committee presence as the source of truth for committee
state and will keep vote/min-size calculations aligned with Haskell's active
committee semantics.

The implementation should be minimal:

1. Persist committee presence in `GovernanceStateStore`.
2. Set `committeePresent = true` during Conway genesis bootstrap whenever a
   committee object exists in genesis, including an empty member map.
3. Set `committeePresent = false` when a `NoConfidence` action is enacted.
4. Set `committeePresent = true` when an `UpdateCommittee` action is enacted,
   even if the resulting committee member map is empty.
5. Resolve committee state from presence only:

   ```text
   committeePresent == false -> NO_CONFIDENCE
   committeePresent == true  -> NORMAL
   ```

6. Select `UpdateCommittee` DRep/SPO thresholds from the persisted committee
   presence value read at the start of Phase 2 ratification, after Phase 1
   enactment has committed. Every `UpdateCommittee` proposal evaluated in that
   Phase 2 pass sees the same post-Phase-1 committee state snapshot. This
   matches Haskell ledger behavior where RATIFY reads the enact state after
   previously ratified actions have been enacted, but before any action
   ratified in the current boundary is enacted.

   ```text
   committeePresent == true  -> committeeNormal threshold
   committeePresent == false -> committeeNoConfidence threshold
   ```

7. Count only active, non-resigned, hot-key-authorized members for
   post-bootstrap committee min-size checks.
8. Keep threshold-zero behavior explicit:

   ```text
   committee threshold 0/1 -> committee vote passes, even with zero voters
   non-zero threshold and zero denominator participation -> fails
   ```
9. Align the tracked Yano devnet Conway genesis with the Yano-only devkit
   intent: an empty genesis committee used to remove the committee gate must
   have committee threshold `0/1`. Real network genesis files must not be
   changed for this fix.

## Non-Goals

- Do not add a migration for old unreleased Yano governance state.
- Do not refactor the full governance ratification engine.
- Do not change real-network genesis defaults.
- Do not change DRep, SPO, proposal expiry, deposit refund, or enactment
  ordering outside the committee semantics needed here.

## Consequences

### Positive

- Yano-only devkit can enact PV11 protocol parameter governance actions with an
  empty genesis committee and threshold `0/1`.
- `NoConfidence` followed by `UpdateCommittee` uses the lower
  no-confidence recovery thresholds when protocol parameters define them.
- Committee min-size checks match the same active committee definition used by
  vote tallying.
- Present-but-inactive committees are represented as normal committee state,
  leaving downstream checks to fail for the correct reason.

### Risk

- Persisted governance state shape changes. Since Yano has not been released,
  users running prior builds should resync from genesis.
- If a network profile accidentally sets empty committee threshold to `2/3`,
  an empty present committee will still fail committee voting. That is correct
  ledger behavior, but it can look like the original bug unless the config is
  checked.
- Threshold selection must use the post-Phase-1 committee state read before
  Phase 2 starts, not the state that would exist after enacting any proposal
  ratified during the same Phase 2 pass.

## Regression Boundaries

The fix must not weaken real-network governance checks:

- A missing Conway genesis committee threshold should continue to fail closed,
  not silently default to `0/1` or `2/3`.
- A non-zero committee threshold with no eligible committee voters should fail.
- A `committeePresent=true` state with stored threshold `0/1` auto-passes only
  committee approval. It must not bypass committee min-size, DRep checks, SPO
  checks, previous-action checks, lifecycle checks, or enactment ordering.
- Members without authorized hot keys should not help satisfy min-size.
- `NoConfidence` should clear committee presence.
- `UpdateCommittee` should restore committee presence.
- Bootstrap phase behavior should remain unchanged except where threshold `0/1`
  explicitly auto-passes.

## Implementation Notes

Suggested implementation order:

1. Simplify committee state resolution so `committeePresent=true` always maps to
   `NORMAL` and `committeePresent=false` maps to `NO_CONFIDENCE`.
2. Add hot-key authorization to the post-bootstrap committee min-size filter.
3. Make `UpdateCommittee` threshold resolution state-dependent. The current
   static threshold map helpers can either take committee presence as an
   argument or be wrapped by instance methods that read the post-Phase-1
   committee state before Phase 2 ratification.
4. Keep the already implemented persistence behavior for genesis bootstrap,
   `NoConfidence`, `UpdateCommittee`, and threshold-zero committee vote
   handling.
5. Align only the tracked Yano devnet Conway genesis threshold for the empty
   committee case. Do not change mainnet, preprod, preview, or sanchonet
   genesis files.

The existing ratification API still passes committee state as the strings
`NORMAL` and `NO_CONFIDENCE`. That is acceptable for this fix if the string is
derived from the explicit boolean source of truth at a single well-defined
boundary point. A later cleanup may replace the string parameter with a boolean
or enum.

## Verification Plan

Add focused tests for:

1. Conway genesis with empty committee and threshold `0/1` stores
   `committeePresent = true`.
2. Empty present committee resolves to `NORMAL`.
3. Present committee with all members expired or resigned still resolves to
   `NORMAL`.
4. Enacted `NoConfidence` stores `committeePresent = false`.
5. Enacted `UpdateCommittee` stores `committeePresent = true`.
6. `UpdateCommittee` DRep voting uses `dvtCommitteeNoConfidence` when
   post-Phase-1 committee state is no-confidence.
7. `UpdateCommittee` SPO voting uses `pvtCommitteeNoConfidence` when
   post-Phase-1 committee state is no-confidence.
8. `UpdateCommittee` uses normal DRep and SPO thresholds when committee state is
   normal.
9. Phase 1 -> Phase 2 visibility: if a `NoConfidence` action is pending
   enactment and an `UpdateCommittee` action is active at the same boundary,
   Phase 2 must ratify the `UpdateCommittee` using no-confidence thresholds
   after Phase 1 commits `committeePresent=false`.
10. Committee min-size excludes members without hot keys.
11. Threshold `0/1` passes committee voting with zero eligible voters.
12. Threshold `2/3` fails committee voting with zero eligible voters.
13. Tracked Yano devnet Conway genesis uses empty committee threshold `0/1`;
    real network genesis files retain their published values.

Run focused governance tests:

```bash
./gradlew :ledger-state:test \
  --tests 'com.bloxbean.cardano.yano.ledgerstate.governance.*' \
  --tests 'com.bloxbean.cardano.yano.ledgerstate.governance.epoch.*' \
  --tests 'com.bloxbean.cardano.yano.ledgerstate.governance.ratification.*'
```

Validate with Yano-only devkit:

```text
1. Start Yano and Yaci Store in Yano-only devkit mode.
2. Submit PV11 protocol parameter governance action.
3. Let Yano time-travel to wall clock and cross epoch boundaries.
4. Confirm proposal list is empty or action is enacted.
5. Confirm /api/v1/epochs/latest/parameters returns PV11 cost model lengths.
6. Confirm Yaci Store and Yano agree on enacted epoch.
```

## Reviewer Notes

The most important review question is whether committee state is evaluated at
the same point in the epoch-boundary pipeline as Haskell ledger ratification.
The intended model is:

```text
Phase 1: enact previously ratified actions
         NoConfidence/UpdateCommittee updates committee presence

Phase 2: ratify currently active proposals
         threshold selection uses committee presence after Phase 1 enactment
```

All proposals evaluated in the same Phase 2 pass must see that same
post-Phase-1 snapshot. They must not observe state changes from other proposals
ratified during that Phase 2 pass, because those proposals are only enacted at a
later boundary.

Because the state is epoch-boundary state, tests should make the epoch used for
ratification explicit. Ambiguous tests can pass while still using the wrong
previous/current state.
