---
name: test-native-devnet-epoch-crossing
description: Standalone Yano devnet (GraalVM native) smoke test: shortened epochs (epochLength 50), verify block production crosses two epoch boundaries cleanly. No Haskell node. Runs release-QA test epoch-crossing-native.
---

# Devnet epoch crossing (native)

## Run it

```bash
qa/release-qa.sh --only epoch-crossing-native
```

Run it with `run_in_background` and wait for the completion notification; do not poll.
The script builds Yano if the cached build in `qa/work/bin` is not from the current
commit and working tree (building the JVM jar and native binary), runs on the isolated harness ports listed in
`qa/README.md`, and only kills processes it started. Nodes already running
on 7070/13337 are never touched. Expected time: about 1 minute, plus about 3-10 minutes if the native binary must be built.

If it exits 3, pre-flight failed (a missing tool or a busy harness port): report what it
printed and stop. Do not kill whatever holds the port.

## Prerequisites

- GraalVM for the native build: set `QA_GRAALVM_HOME` (or `GRAALVM_HOME`). If `JAVA_HOME`
  is a Liberica JDK, the build selects the parallel GC, so use a Liberica NIK.

## What it checks

- The native binary starts with the devnet profile and `epochLength=50`.
- Block production reaches slot 100 or later, crossing two epoch boundaries.
- No block-production errors and no native-image initialization errors
  (`NoClassDefFoundError`, `MissingReflectionRegistrationError` and similar).

Harness: `qa/harness/epoch-crossing.sh native`.

## Report

Read `qa/results/<run-id>/report.md` (the newest directory under `qa/results/`) and reply with:

- the verdict for each test id and its duration;
- the key lines the report shows (tips, hash match, epoch transitions, check counts, ERROR counts);
- for any failure: the verdict reason, the failed checks, and the log paths
  `qa/results/<run-id>/logs/<id>.log` and `qa/results/<run-id>/runs/` (node and Haskell logs).

To investigate a failure, read those logs before re-running. Do not change the harness
to make a test pass; report what failed. The full orchestrated suite is the
`release-qa` skill (`.agents/skills/release-qa`).
