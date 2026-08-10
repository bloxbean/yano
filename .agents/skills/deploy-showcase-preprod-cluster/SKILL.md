---
name: deploy-showcase-preprod-cluster
description: Safely stage, start, anchor, smoke-test, and validate the Yano three-node preprod app-chain showcase from the retained chainstate and owner-supplied anchor/settlement keys. Use when asked to create or resume the preprod cluster on HTTP 17070-17072, clone /Users/satya/Downloads/yano-cluster/chainstate-preprod into three identity-marked nodes, deploy the production settlement chain, bootstrap all SCRIPT anchors, or rerun the preprod validation pipeline.
---

# Deploy Showcase Preprod Cluster

This workflow can submit real preprod transactions. Keep read-only resume and
validation separate from fresh deployment or smoke traffic.

## Workflow

1. Announce the public-network effects before a fresh deployment or smoke run.
2. Inspect repository status and preserve unrelated work. Never commit
   automatically.
3. For an existing deployment, resume and validate without spending:

   ```bash
   bash .agents/skills/deploy-showcase-preprod-cluster/scripts/deploy.sh
   ```

4. For a fresh target, the explicit confirmation flag is mandatory:

   ```bash
   bash .agents/skills/deploy-showcase-preprod-cluster/scripts/deploy.sh \
     --confirm-public-preprod --smoke
   ```

   This reference preprod deployment selects `params-stake-v1` so evaluators
   receive protocol parameters and the latest retained end-of-epoch stake
   snapshots out of the box. It limits L1 source snapshot reconciliation to
   the latest two completed boundaries; finalized app-chain epoch snapshots
   remain separately addressable. Experimental MPF pruning remains disabled.

5. The script never replaces an existing target. If a clean redeploy is
   requested, move the old deployment to a reviewed backup path or select a new
   `--target`; do not delete it implicitly.
6. Review the all-chain audit, settlement info, first node-log errors, and
   `PREPROD_CLUSTER.md`. Leave the cluster running unless asked otherwise.

## Fixed inputs and defaults

- Source chainstate: `/Users/satya/Downloads/yano-cluster/chainstate-preprod`
- Source keys: `/Users/satya/Downloads/yano-cluster/keys`
- Target: `/Users/satya/Downloads/yano-cluster/preprod-cluster`
- Instance: `preprod`
- HTTP: `17070-17072`
- N2N: `17337-17339`
- Nodes/threshold: `3/2`

The script uses a disposable bootstrap instance to deploy the operator-specific
production settlement identity, adopts its generated config block at the
catalog position, then creates the final app-chain generation from scratch.
The default has thirteen chains, including the ADR-035 Cardano History product
with its `params-stake-v1` preprod reference profile.
It makes three independent APFS clone-on-write L1 stores while
excluding any legacy `chainstate/appchains` content and copies keys with
owner-only permissions. No app-chain state or identity is migrated from the
bootstrap instance. Each final node must expose sibling `chainstate`,
`appchain-chainstate`, and `appchain-indexers` roots.

The non-secret settlement deployment record, emitted configuration log, and
parameterized validator scripts are retained beside the source keys. A later
clean deployment reuses those exact public artifacts. This is important when
the imported L1 snapshot predates the already-submitted one-shot mint: it must
not select a replacement seed UTxO or attempt a second settlement identity.

## Safety and proof rules

- Never print or inspect seed contents. Check only existence, ownership, mode,
  derived public addresses, and balances.
- Require `--confirm-public-preprod` before anchor bootstrap, settlement
  bootstrap, or smoke messages that can advance an anchor.
- Never regenerate retained state, anchor, or settlement identity.
- Require the three nodes to agree on tip/root/profile/genesis for each chain.
  Only node 0 needs an anchor writer view.
- Require the retained chain order and capability manifests to match the
  packaged ADR-033 catalog.
- Reject a derived-index checkpoint above its authoritative app-chain tip and
  reject any legacy derived index below the L1 chainstate root.
- A caller-pinned proof verifies mechanics, not L1 trust. Call a proof anchored
  only after separately validating the referenced Cardano transaction/datum.
- Do not use settlement deposit/withdraw as health checks; they exercise real
  preprod custody flows.

## Resources

- `scripts/deploy.sh` implements fresh identity-safe import or existing resume.
- `scripts/adopt_settlement_config.py` adopts only the generated production
  settlement block and refuses duplicate/malformed input.
- `scripts/validate_cluster.sh` performs the read-only three-node/all-chain
  commitment and leader-anchor audit.
- `scripts/write_runbook.sh` refreshes `PREPROD_CLUSTER.md` without exposing
  key material.
