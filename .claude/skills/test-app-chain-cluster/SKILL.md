---
name: test-app-chain-cluster
description: App-chain smoke test: two-node Yano app-chain cluster on devnet (proposer + member, L1 anchoring on); submit messages, verify sequenced blocks, state roots, finality certificates, MPF proofs and the L1 anchor on both nodes. Runs release-QA test appchain-cluster.
---

# App-chain cluster

## Run it

```bash
qa/release-qa.sh --only appchain-cluster
```

Run it with `run_in_background` and wait for the completion notification; do not poll.
The script builds Yano if the cached build in `qa/work/bin` is not from the current
commit and working tree (building only the JVM jar), runs on the isolated harness ports listed in
`qa/README.md`, and only kills processes it started. Nodes already running
on 7070/13337 are never touched. Expected time: about 3-5 minutes.

If it exits 3, pre-flight failed (a missing tool or a busy harness port): report what it
printed and stop. Do not kill whatever holds the port.

## What it checks

Node A (proposer, anchoring) and node B (member, L1 follower of A) run on the harness
ports with the required state triple, per-node storage and an admin API key. Checks:

- A is proposer, B is member, peers connected both ways, sequencing on.
- Tips equal with identical state root; block 1 has identical roots, 2 or more certificate
  signatures, and A as proposer.
- Each node's message is visible on the other with source `PEER`; an MPF proof for A's
  message is served by B.
- An anchor is confirmed on L1; final tips identical; L1 in lock-step and advancing.

Harness: `qa/harness/appchain-cluster.sh`.

## Notes

B logs a few "empty chain state" ERRORs while it starts; they are expected.

## Report

Read `qa/results/<run-id>/report.md` (the newest directory under `qa/results/`) and reply with:

- the verdict for each test id and its duration;
- the key lines the report shows (tips, hash match, epoch transitions, check counts, ERROR counts);
- for any failure: the verdict reason, the failed checks, and the log paths
  `qa/results/<run-id>/logs/<id>.log` and `qa/results/<run-id>/runs/` (node and Haskell logs).

To investigate a failure, read those logs before re-running. Do not change the harness
to make a test pass; report what failed. The full orchestrated suite is the
`release-qa` skill.
