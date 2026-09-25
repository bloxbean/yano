---
name: test-devnet-epoch-crossing
description: Standalone Yano devnet (JVM) smoke test: shortened epochs (epochLength 50), verify block production crosses two epoch boundaries cleanly. No Haskell node. Runs release-QA test epoch-crossing-jvm.
---

# Devnet epoch crossing (JVM)

## Run it

```bash
qa/release-qa.sh --only epoch-crossing-jvm
```

Run it with `run_in_background` and wait for the completion notification; do not poll.
The script builds Yano if the cached build in `qa/work/bin` is not from the current
commit and working tree (building only the JVM jar), runs on the isolated harness ports listed in
`qa/README.md`, and only kills processes it started. Nodes already running
on 7070/13337 are never touched. Expected time: about 1 minute plus any build.

If it exits 3, pre-flight failed (a missing tool or a busy harness port): report what it
printed and stop. Do not kill whatever holds the port.

## What it checks

- Yano starts with the devnet profile and a copy of the devnet genesis with `epochLength=50`.
- Block production reaches slot 100 or later, crossing two epoch boundaries.
- No `Effective protocol parameters are unavailable` or `Error producing block` lines.

Harness: `qa/harness/epoch-crossing.sh jvm`.

## Report

Read `qa/results/<run-id>/report.md` (the newest directory under `qa/results/`) and reply with:

- the verdict for each test id and its duration;
- the key lines the report shows (tips, hash match, epoch transitions, check counts, ERROR counts);
- for any failure: the verdict reason, the failed checks, and the log paths
  `qa/results/<run-id>/logs/<id>.log` and `qa/results/<run-id>/runs/` (node and Haskell logs).

To investigate a failure, read those logs before re-running. Do not change the harness
to make a test pass; report what failed. The full orchestrated suite is the
`release-qa` skill (`.agents/skills/release-qa`).
