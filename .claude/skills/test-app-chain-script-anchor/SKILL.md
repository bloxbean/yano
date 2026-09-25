---
name: test-app-chain-script-anchor
description: Regression test: two-node Yano devnet app chain with script anchors and L1 observers; bootstrap the thread NFT, verify threshold co-signed anchor advances, follower identity adoption, and stability-gated L1 deposit observations. Runs release-QA test appchain-script-anchor.
---

# App-chain script anchor

## Run it

```bash
qa/release-qa.sh --only appchain-script-anchor
```

Run it with `run_in_background` and wait for the completion notification; do not poll.
The script builds Yano if the cached build in `qa/work/bin` is not from the current
commit and working tree (building only the JVM jar), runs on the isolated harness ports listed in
`qa/README.md`, and only kills processes it started. Nodes already running
on 7070/13337 are never touched. Expected time: about 5-8 minutes.

If it exits 3, pre-flight failed (a missing tool or a busy harness port): report what it
printed and stop. Do not kill whatever holds the port.

## What it checks

- Script anchoring and observers configured; the anchor wallet matches the fixture.
- Bootstrap confirmed on L1; each advance carries 2 member witnesses; B verifies and sends
  its witness; 2 or more advances confirmed.
- B adopts the chain identity (same thread policy id, no anchor config of its own).
- Anchor-transaction change observations finalize identically on A and B; a fresh
  deposit transaction (built by `qa/harness/Deposit.java`) is observed and finalized
  identically on both.
- Identical state roots at the end.

Harness: `qa/harness/appchain-script-anchor.sh`.

## Notes

When running `qa/harness/appchain-script-anchor.sh` directly, `KEEP=1` leaves the nodes
running for inspection (stop them yourself afterwards).

Triage hints:
- `Locked proposal ... has expired` repeating forever is a known engine edge (a vote-locked
  height whose proposal expired during a restart); wipe and rerun rather than debugging it.
- `fee ... is less than minimum required` means the co-sign witness pricing regressed; check
  `VKEY_WITNESS_BYTES` in `ScriptAnchorService`.

## Report

Read `qa/results/<run-id>/report.md` (the newest directory under `qa/results/`) and reply with:

- the verdict for each test id and its duration;
- the key lines the report shows (tips, hash match, epoch transitions, check counts, ERROR counts);
- for any failure: the verdict reason, the failed checks, and the log paths
  `qa/results/<run-id>/logs/<id>.log` and `qa/results/<run-id>/runs/` (node and Haskell logs).

To investigate a failure, read those logs before re-running. Do not change the harness
to make a test pass; report what failed. The full orchestrated suite is the
`release-qa` skill.
