---
title: "What is a multi-party app ledger?"
description: "Understand multi-party app ledgers, member finality, proofs, and Cardano anchoring."
sidebar:
  order: 1
---

:::caution[Experimental]
App ledgers are experimental. Configuration keys, REST endpoints, and the plugin API can change between preview releases without a migration path, and every member of a ledger must upgrade together. Don't use app ledgers to hold value yet.
:::

A multi-party app ledger is a shared record for your application: a known group of members orders messages, executes the same deterministic logic, and certifies the resulting state, with proofs and optional anchoring to Cardano. The rest of these pages call it an app ledger.

For example, several organizations can agree on the order of shipment events and later prove that a particular message was recorded. Yano's built-in `ordered-log` provides this shared history without interpreting the payload.

In configuration and APIs, app ledgers appear under the `app-chain` name: properties start with `yano.app-chain.`, REST endpoints with `/api/v1/app-chain/`, and the bundled profile is `appchain`.

## What an app ledger is not

An app ledger is similar in spirit to what other ecosystems call an appchain, but it is permissioned and tokenless:

- It is not a standalone public blockchain. Members are configured; there is no open validator set.
- It has no native token and no fee market.
- It is not a Cardano layer 2 secured by L1 consensus. Anchoring records an auditable commitment on Cardano; Cardano does not validate the app ledger's state transitions.

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

## Independent ledgers

A node may host multiple app ledgers, each identified by its chain id. Each has its own identity, member set, sequencing policy, state, storage, and optional anchor policy. Use topics within one ledger for event categories; use separate ledgers for different trust or operational boundaries.

## Trust model

Member finality relies on configured keys, quorum rules, and consensus context. A state proof establishes a mathematical relationship to a root. You still need a trusted source for that root and the ledger identity. Cardano anchoring does not make an opaque business claim true.

[Start a local app ledger](/app-chains/quickstart/) or [learn about proofs](/app-chains/proofs/).
