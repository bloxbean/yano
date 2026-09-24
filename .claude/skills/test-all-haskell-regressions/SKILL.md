---
name: test-all-haskell-regressions
description: Regression suite: runs the four Yano-to-Haskell sync tests (JVM and native Haskell sync, JVM and native past time travel) and produces one combined report. Runs release-QA tests haskell-sync-* and past-time-travel-*.
---

# All Haskell sync regressions

## Run it

```bash
qa/release-qa.sh --only haskell-sync-jvm,haskell-sync-native,past-time-travel-jvm,past-time-travel-native
```

Run it with `run_in_background` and wait for the completion notification; do not poll.
The script builds Yano if the cached build in `qa/work/bin` is not from the current
commit and working tree (building the JVM jar and native binary), runs on the isolated harness ports listed in
`qa/README.md`, and only kills processes it started. Nodes already running
on 7070/13337 are never touched. Expected time: about 30-40 minutes, plus the native build if needed.

If it exits 3, pre-flight failed (a missing tool or a busy harness port): report what it
printed and stop. Do not kill whatever holds the port.

## Prerequisites

- GraalVM for the native build: set `QA_GRAALVM_HOME` (or `GRAALVM_HOME`). If `JAVA_HOME`
  is a Liberica JDK, the build selects the parallel GC, so use a Liberica NIK.
- A Haskell cardano-node in `test-data-dir/haskell-node/`:
  `scripts/haskell-compatibility/setup-haskell-test-node.sh` (default 11.0.1; override with
  `HASKELL_NODE_VERSION`). On macOS it uses the `macos-amd64` asset; the arm64 build is broken.
  Without it the test is reported `BLOCKED`.

## What it checks

Runs, in this order and one at a time: `test-haskell-sync`, `test-native-haskell-sync`,
`test-past-time-travel`, `test-native-past-time-travel`. A failure does not stop the
remaining tests. See each skill for its pass criteria.

## Report

Read `qa/results/<run-id>/report.md` (the newest directory under `qa/results/`) and reply with:

- the verdict for each test id and its duration;
- the key lines the report shows (tips, hash match, epoch transitions, check counts, ERROR counts);
- for any failure: the verdict reason, the failed checks, and the log paths
  `qa/results/<run-id>/logs/<id>.log` and `qa/results/<run-id>/runs/` (node and Haskell logs).

To investigate a failure, read those logs before re-running. Do not change the harness
to make a test pass; report what failed. The full orchestrated suite is the
`release-qa` skill.
