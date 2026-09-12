---
title: "Versions & upgrades"
description: "Keep source, artifacts, storage, and plugins compatible."
sidebar:
  order: 7
---

Yano is pre-release. Pin library, node, verifier, and plugin versions together. Check the [documentation build manifest](/ai/manifest.json) for the source revision and version used to generate these docs.

The current Java namespace is `org.yanoproject.*`, and Maven artifacts use group `org.yanoproject`. Do not rename dependency packages such as `com.bloxbean.cardano.client.*` or `com.bloxbean.cardano.yaci.*`: those belong to separate projects.

## Before an upgrade

1. Record the current version, profile configuration, network/genesis identity, and plugin catalog.
2. Stop the process cleanly and keep a recoverable backup of the complete state and associated identities.
3. Check release-specific storage and commitment compatibility.
4. Validate the new artifact in an isolated environment before replacing the running installation.

Wallet index upgrades require fresh sync where completeness metadata is absent. History archive sections are selected for a fresh archive. App-chain commitment/profile changes may require a fresh chain; they are not automatically in-place migrations.

Never discard a signing journal or reuse an app-chain identity with a newly empty ledger as a recovery shortcut. It may lose persisted consensus or observation locks.

See [release migration details](/reference/upgrading/) for concrete compatibility changes.
