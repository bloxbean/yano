---
name: deploy-showcase-devnet-cluster
description: Build, stage, start, anchor, smoke-test, and validate the Yano app-chain showcase as a three-node devnet cluster. Use when asked to rebuild the showcase ZIP, create or resume the default devnet deployment under /Users/satya/Downloads/yano-cluster/devnet-cluster, bootstrap SCRIPT anchors for every configured chain, verify cross-node commitment identity/root agreement, or refresh its operator runbook.
---

# Deploy Showcase Devnet Cluster

Use the checked script for the repeatable path and inspect failures before
changing cluster identity or data.

## Workflow

1. Announce that this skill will build and operate a local three-node cluster.
2. Confirm the repository is `/Users/satya/work/bloxbean/yano` and inspect
   `git status --short`. Preserve unrelated work and never commit automatically.
3. Run:

   ```bash
   bash .agents/skills/deploy-showcase-devnet-cluster/scripts/deploy.sh --smoke
   ```

4. If the target already exists, the script resumes and validates it; it does
   not overwrite retained identity or data. If a new release must replace an
   existing extraction, stop it and use a separate target or make a reviewed
   preservation plan for `data/` and generated config.
5. Review `DEVNET_CLUSTER.md`, the all-chain validation output, and the first
   `ERROR` in each node log if validation fails.
6. Leave the cluster running unless the user asks otherwise. Report ports,
   ZIP checksum, anchor coverage, proof verification result, and paths.

## Defaults

- Target: `/Users/satya/Downloads/yano-cluster/devnet-cluster`
- Instance: `devnet`
- HTTP: `7070-7072`
- N2N: `13337-13339`
- Nodes/threshold: `3/2`
- Anchors: SCRIPT mode, every configured chain
- Per-node stores: `chainstate`, `appchain-chainstate`, `appchain-indexers`

Override the repository, target, or instance only when the user explicitly
places a different deployment in scope. Run `deploy.sh --help` for options.

## Safety and identity

- Never print seed contents, API keys, or node signing material.
- Never regenerate a retained chain `genesis-id`; a new ID is a new chain
  generation.
- Treat `(commitment-profile, format-fingerprint, genesis-id)` as one pinned
  state identity on all nodes.
- Validate MPF proof mechanics with a caller-pinned root, but describe a proof
  as independently trusted only when the root/height came from a separately
  verified Cardano anchor.
- Follower status may omit `.anchor`; node 0 owns the anchor writer. Require
  identical tip/root/profile/genesis values on all three nodes.
- Reject a derived-index checkpoint above its authoritative app-chain tip and
  reject any legacy derived index below the L1 chainstate root.

## Resources

- `scripts/deploy.sh` builds, stages/resumes, starts, anchors, smokes, and
  invokes validation.
- `scripts/validate_cluster.sh` performs the read-only three-node/all-chain
  commitment and leader-anchor audit.
- `scripts/write_runbook.sh` refreshes `DEVNET_CLUSTER.md` with the current
  fixed deployment layout and validation commands.
