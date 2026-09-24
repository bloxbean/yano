---
name: test-haskell-sync
description: Regression test: Yano devnet (JVM) in regular block-producer mode with a Haskell cardano-node following it; verify the Haskell node stays in lock-step for 2 epochs. Runs release-QA test haskell-sync-jvm.
---

# Yano to Haskell sync (JVM)

## Run it

```bash
qa/release-qa.sh --only haskell-sync-jvm
```

Run it with `run_in_background` and wait for the completion notification; do not poll.
The script builds Yano if the cached build in `qa/work/bin` is not from the current
commit and working tree (building only the JVM jar), runs on the isolated harness ports listed in
`qa/README.md`, and only kills processes it started. Nodes already running
on 7070/13337 are never touched. Expected time: about 10 minutes.

If it exits 3, pre-flight failed (a missing tool or a busy harness port): report what it
printed and stop. Do not kill whatever holds the port.

## Prerequisites

- A Haskell cardano-node in `test-data-dir/haskell-node/`:
  `scripts/haskell-compatibility/setup-haskell-test-node.sh` (default 11.0.1; override with
  `HASKELL_NODE_VERSION`). On macOS it uses the `macos-amd64` asset; the arm64 build is broken.
  Without it the test is reported `BLOCKED`.

## What it checks

- Uses the pv10 devnet genesis (epochLength 1200, 0.2 s slots, so 2 epochs = slot 2400).
- A Haskell cardano-node (`test-data-dir/haskell-node/bin`) syncs from Yano.
- Pass: the Haskell tip reaches slot 2400 or later, the block hash at the Haskell tip
  matches Yano's block at the same height, and the Haskell log has no error, invalid
  or reject lines.
- About 4% missed 200 ms slots under load is normal jitter, not a failure.

Harness: `qa/harness/haskell-sync.sh jvm`.

## Report

Read `qa/results/<run-id>/report.md` (the newest directory under `qa/results/`) and reply with:

- the verdict for each test id and its duration;
- the key lines the report shows (tips, hash match, epoch transitions, check counts, ERROR counts);
- for any failure: the verdict reason, the failed checks, and the log paths
  `qa/results/<run-id>/logs/<id>.log` and `qa/results/<run-id>/runs/` (node and Haskell logs).

To investigate a failure, read those logs before re-running. Do not change the harness
to make a test pass; report what failed. The full orchestrated suite is the
`release-qa` skill (`.agents/skills/release-qa`).
