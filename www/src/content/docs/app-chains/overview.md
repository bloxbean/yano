---
title: "What is an app chain?"
description: "Understand application ledgers, member finality, proofs, and Cardano anchoring."
sidebar:
  order: 1
---

An app chain is a shared application ledger hosted by Yano. A configured group of members orders messages, executes the same deterministic logic, and certifies the resulting state.

For example, several organizations can agree on the order of shipment events and later prove that a particular message was recorded. Yano's built-in `ordered-log` provides this shared history without interpreting the payload.

## From message to evidence

1. Your application submits a topic and payload to a member.
2. The member validates and signs an envelope, then gossips it.
3. A proposer orders messages into an app block.
4. Members execute the state machine and certify the block at the configured threshold.
5. The committed state root supports queries and proofs.
6. Optional anchoring records a commitment on Cardano.

**Accepted, finalized, and anchored are separate states.** HTTP `202` means accepted at ingress. Finality requires the member certificate. L1 anchoring happens later and needs confirmation.

## What is included?

Yano includes the host, networking, consensus/finality, commitment and proof surfaces, plugin contracts, L1 anchoring, and one built-in machine: `ordered-log`.

[Yano X](https://github.com/bloxbean/yano-x) supplies additional stock state machines, capabilities, connectors, application products, SDKs, and JVM tooling. They are separately installed extensions; a plain Yano distribution does not automatically include them.

## Independent chains

A node may host multiple chains. Each has its own identity, member set, sequencing policy, state, storage, and optional anchor policy. Use topics within one chain for event categories; use separate chains for different trust or operational boundaries.

## Trust model

Member finality relies on configured keys, quorum rules, and consensus context. A state proof establishes a mathematical relationship to a root. You still need a trusted source for that root and the chain identity. Cardano anchoring does not make an opaque business claim true.

[Start a local app chain](/app-chains/quickstart/) or [learn about proofs](/app-chains/proofs/).
