---
name: test-docker-distribution
description: Regression test for the Docker Compose distribution. Builds the JVM image and Compose ZIP from the working tree, then checks yano.sh, mounts, API, snapshot restore, profile files, instance ownership, older config/env, projection history and an in-place upgrade on a devnet. Optionally syncs preprod, preview and mainnet. Runs release-QA tests docker-dist and docker-public.
---

# Docker distribution

## Run it

```bash
qa/release-qa.sh --only docker-dist                 # devnet, about 10-15 minutes
qa/release-qa.sh --only docker-public               # opt-in: public networks, about 45-60 minutes, needs internet
```

Run it with `run_in_background` and wait for the completion notification; do not poll.
Docker must be running (otherwise the test is reported `BLOCKED`). The test builds
`bloxbean/yano:qa-local-jvm` and the Compose ZIP unless both already exist for the current
commit and uncommitted changes, uses harness ports 7281-7283 / 13551-13553, and removes
only its own `yano-qa-docker-*` containers and networks. Your own image tags and nodes on
7070/13337 are untouched.

If it exits 3, pre-flight failed (a missing tool or a busy harness port): report what it
printed and stop.

## What it checks

`docker-dist` (devnet):
- `./yano.sh start:devnet` from the extracted bundle; ready; producing blocks.
- 22 REST, console and metrics endpoints return 200.
- `/app/config` read-only, `/app/config/network` writable, chainstate at
  `/app/data/chainstate`, no anonymous volumes; the devnet genesis update reaches the host.
- Snapshot create in `runtime-data-devnet/snapshots`, restore back to the snapshot block
  with the same hash, production resumes, delete.
- An edited `application-devnet.yml` and a new `application-qaprobe.yml` take effect.
- Instance ownership: a second folder with the same `INSTANCE_NAME` is refused on start,
  stop and restart; distinct names run side by side; stopping one leaves the other.
- An older `config/env` with `YANO_STORAGE_PATH=/app/chainstate` still resumes the chain.
- A fresh bundle with `devnet,projection` writes history to `runtime-data-devnet/history`
  (history must be enabled from genesis, so it does not reuse the first node).
- In-place upgrade: a node started with the launcher and Compose files from commit
  `2747fc7785` (unnamed project `compose`) is refused by the new `start` with guidance,
  moved to project `yano-qa-docker-up` by `restart`, and keeps its chain.

`docker-public`: preprod (`wallet,small`, `-Xmx384m`) to epoch 10 plus a restart,
preview (`small`, `-Xmx384m`) to epoch 10, and mainnet (`wallet,medium`, `-Xmx1536m`) to
epoch 3; profiles and heap reach the JVM, epoch boundaries succeed, and the wallet
first-seen index covers from origin.

Harness: `qa/harness/docker-dist.sh`, `qa/harness/docker-public.sh`.

## Report

Read `qa/results/<run-id>/report.md` (the newest directory under `qa/results/`) and reply with
the verdict, the failed checks, and the log paths `qa/results/<run-id>/logs/<id>.log` and
`qa/results/<run-id>/runs/docker-*/` (bundle folders with their `logs/yano.log`).
