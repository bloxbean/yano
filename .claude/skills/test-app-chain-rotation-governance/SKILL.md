---
name: test-app-chain-rotation-governance
description: Regression test: two-node Yano devnet app chain with a rotating sequencer and governed membership; verify proposership rotates across L1-slot windows and a governed member-add activates identically on both nodes. Runs release-QA test appchain-rotation-governance.
---

# App-chain rotation and governance

## Run it

```bash
qa/release-qa.sh --only appchain-rotation-governance
```

Run it with `run_in_background` and wait for the completion notification; do not poll.
The script builds Yano if the cached build in `qa/work/bin` is not from the current
commit and working tree (building only the JVM jar), runs on the isolated harness ports listed in
`qa/README.md`, and only kills processes it started. Nodes already running
on 7070/13337 are never touched. Expected time: about 4-6 minutes.

If it exits 3, pre-flight failed (a missing tool or a busy harness port): report what it
printed and stop. Do not kill whatever holds the port.

## What it checks

- Rotating mode with a positive window, proposer in {A, B}, no split votes; peers connected.
- Tips identical; every block has 2 or more certificate signatures; at least 2 distinct
  proposers across blocks.
- One approval alone changes nothing; the governed member-add activates on both nodes
  (3 members) at the same activation height.
- Ordinary messages still finalize afterwards; final tips identical.

Harness: `qa/harness/appchain-rotation-governance.sh` (window 50 slots, about 10 s at 0.2 s slots).

## Notes

B logs a few "empty chain state" ERRORs while it starts; they are expected.
On a slow host, widen the rotation window with `WINDOW=<slots>` (default 50, about 10 s).

## Report

Read `qa/results/<run-id>/report.md` (the newest directory under `qa/results/`) and reply with:

- the verdict for each test id and its duration;
- the key lines the report shows (tips, hash match, epoch transitions, check counts, ERROR counts);
- for any failure: the verdict reason, the failed checks, and the log paths
  `qa/results/<run-id>/logs/<id>.log` and `qa/results/<run-id>/runs/` (node and Haskell logs).

To investigate a failure, read those logs before re-running. Do not change the harness
to make a test pass; report what failed. The full orchestrated suite is the
`release-qa` skill.
