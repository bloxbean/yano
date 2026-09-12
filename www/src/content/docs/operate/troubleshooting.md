---
title: "Troubleshooting"
description: "Diagnose startup, synchronization, unavailable data, and app-chain progress."
sidebar:
  order: 8
---

## The process will not start

Check `java -version` (JDK 25), the working directory, configured storage ownership, and whether ports `7070` or `13337` are already in use. Start the launcher inside the complete extracted distribution; Java is needed only for the JVM ZIP.

Do not run two processes against one RocksDB directory. Keep node and app-chain storage, network configuration, and member identity together when moving an installation.

## The node is healthy but still behind

Read `/api/v1/status` and `/api/v1/node/tip`. Readiness means the application is ready to serve; public-network synchronization can still be in progress. Look at the local/remote tips, selected peer, recovery reason, rejected validation stage, and last progress in logs.

Confirm the genesis/network magic and upstream reachability. Repeatedly restarting or deleting state can hide the original error; preserve logs and configuration first.

## A wallet query returns unavailable

Wallet indexes must be enabled before a fresh sync. An existing database does not gain historical completeness when you flip a flag. Scan queries also need retained block bodies. A `503` is not an empty account or an unused address. See [wallet indexes](/node/wallet-indexes/).

## A message is accepted but not finalized

`202` is ingress acceptance. Check chain status, configured proposer, member keys, threshold, peer connectivity, expiry, and backpressure. Make sure voting nodes agree on the chain configuration and commitment identity. An idle chain need not produce empty blocks.

## A proof is valid but the claim is still uncertain

A valid proof only binds data to its expected root. Establish where that root came from, which chain it belongs to, and whether the associated finality and L1 evidence are independently trusted. See [proofs and anchors](/app-chains/proofs/).

## Report a problem

Include the source revision or artifact version, JVM/native platform, enabled profiles, redacted configuration, steps to reproduce, expected behavior, and relevant logs. Do not include signing keys, API keys, or private payloads. Open an issue in [the Yano repository](https://github.com/bloxbean/yano/issues).
