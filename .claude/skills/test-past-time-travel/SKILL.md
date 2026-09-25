---
name: test-past-time-travel
description: Regression test: Yano devnet (JVM) in past-time-travel mode; shift 4 epochs, catch up to wall clock, then verify a Haskell cardano-node syncs the full chain from slot 0. Runs release-QA test past-time-travel-jvm.
---

# Past time travel with Haskell sync (JVM)

## Run it

```bash
qa/release-qa.sh --only past-time-travel-jvm
```

Run it with `run_in_background` and wait for the completion notification; do not poll.
The script builds Yano if the cached build in `qa/work/bin` is not from the current
commit and working tree (building only the JVM jar), runs on the isolated harness ports listed in
`qa/README.md`, and only kills processes it started. Nodes already running
on 7070/13337 are never touched. Expected time: about 5 minutes.

If it exits 3, pre-flight failed (a missing tool or a busy harness port): report what it
printed and stop. Do not kill whatever holds the port.

## Prerequisites

- A Haskell cardano-node in `test-data-dir/haskell-node/`:
  `scripts/haskell-compatibility/setup-haskell-test-node.sh` (default 11.0.1; override with
  `HASKELL_NODE_VERSION`). On macOS it uses the `macos-amd64` asset; the arm64 build is broken.
  Without it the test is reported `BLOCKED`.

## What it checks

- Block production is deferred at start (`Past time travel mode` log line).
- `POST /devnet/epochs/shift {"epochs":4}` returns `genesis_slot 0` and `shift_millis 960000`.
- The first 20 blocks are sequential (slot equals block number).
- `POST /devnet/epochs/catch-up` produces 4500-5100 blocks; afterwards production follows
  the wall clock (slot greater than block number).
- The Haskell node's `systemStart` is 900-1200 s in the past, it reaches Yano's tip within
  60 s, the tip hash matches, and its log has no error lines.

Harness: `qa/harness/past-time-travel.sh jvm`.

## Report

Read `qa/results/<run-id>/report.md` (the newest directory under `qa/results/`) and reply with:

- the verdict for each test id and its duration;
- the key lines the report shows (tips, hash match, epoch transitions, check counts, ERROR counts);
- for any failure: the verdict reason, the failed checks, and the log paths
  `qa/results/<run-id>/logs/<id>.log` and `qa/results/<run-id>/runs/` (node and Haskell logs).

To investigate a failure, read those logs before re-running. Do not change the harness
to make a test pass; report what failed. The full orchestrated suite is the
`release-qa` skill.
