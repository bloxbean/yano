---
title: "Certified observations"
description: "Understand the preview observation framework and its trust model."
sidebar:
  order: 7
---

Certified observations bring bounded reports from configured sources or reporters into an app chain. They are **preview functionality, disabled by default**. Agreement on reports does not establish that a source is correct.

## Configure an explicit profile

`observations.profile-cbor-hex` supplies a canonical observation profile. Its bytes are committed separately from the consensus profile. Omitting it selects the disabled profile. A retained chain rejects mismatched profiles; this is not an in-place feature toggle for arbitrary existing ledgers.

The framework supports one-shot and recurring exact-value observations and complete-source numeric aggregation. Scheduling uses app heights, or verified L1 slots when the selected logical-time version and stable L1 feed support it. Height cadence is chain progress, not elapsed minutes.

## Separate collection from consensus

Application callbacks emit deterministic intents. Bounded workers acquire data outside deterministic execution. Reports and certificates are made durable and are consumed under the configured rules. Status reports acquisition failures, queue pressure, journal usage, active subscriptions, and readiness.

`observations.workers` defaults to `4` and supports `1–64`. The signing journal has bounded entry and byte limits. Never copy a signing journal across member identities or erase it to bypass a recovery failure.

## Audit and recovery

Committed observation queries use the reserved `yano/observations/` prefix and return a height/root. They read authenticated state, not the node-local worker's current view.

For certificate verification, use the domain-separated commit digest and independently trusted height-specific consensus context. Older incomplete certified headers must not be treated as equivalent evidence. Keep provider/plugin API levels matched to the host.

Recovery must preserve finalized history, original profiles, membership, and signing locks. Offline replay tools verify every reconstructed root before installation. A missing journal or a divergent replay is not repaired by starting an empty ledger with the same signing key.

Provider implementation contracts live under `org.yanoproject.api.appchain`; extension packages and their qualification remain separately versioned. See [extension development](/app-chains/extensions/) for the plugin boundary.
