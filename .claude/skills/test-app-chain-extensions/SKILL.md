---
name: test-app-chain-extensions
description: Full regression of the app-chain extensions on a two-node devnet cluster: multi-chain, query surface, SSE, webhook sink, admin API (pause, resume, force-anchor, members), evidence bundle, snapshot, metrics. Runs release-QA test appchain-extensions.
---

# App-chain extensions

## Run it

```bash
qa/release-qa.sh --only appchain-extensions
```

Run it with `run_in_background` and wait for the completion notification; do not poll.
The script builds Yano if the cached build in `qa/work/bin` is not from the current
commit and working tree (building only the JVM jar), runs on the isolated harness ports listed in
`qa/README.md`, and only kills processes it started. Nodes already running
on 7070/13337 are never touched. Expected time: about 5-8 minutes.

If it exits 3, pre-flight failed (a missing tool or a busy harness port): report what it
printed and stop. Do not kill whatever holds the port.

## What it checks

Two ordered-log chains on a two-node cluster (the kv-registry state machine is no longer in
core). Checks:

- Both chains on both nodes; a chain-less path is rejected as ambiguous (400).
- Cross-node reads and proofs; tips and state roots identical per chain.
- Query surface (`by-topic`, `blocks?limit=5`), SSE replay and live events, webhook
  delivery in ascending heights.
- Admin API with the API key: pause rejects submits, resume accepts, force-anchor
  responds, member add to 3 (threshold 2) finalizes messages, member remove back to 2.
- Anchor confirmed, evidence bundle, snapshot, metrics for both chains, final tips
  identical, L1 lock-step.

Harness: `qa/harness/appchain-extensions.sh`.

## Report

Read `qa/results/<run-id>/report.md` (the newest directory under `qa/results/`) and reply with:

- the verdict for each test id and its duration;
- the key lines the report shows (tips, hash match, epoch transitions, check counts, ERROR counts);
- for any failure: the verdict reason, the failed checks, and the log paths
  `qa/results/<run-id>/logs/<id>.log` and `qa/results/<run-id>/runs/` (node and Haskell logs).

To investigate a failure, read those logs before re-running. Do not change the harness
to make a test pass; report what failed. The full orchestrated suite is the
`release-qa` skill.
