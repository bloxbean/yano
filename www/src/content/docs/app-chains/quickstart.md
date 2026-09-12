---
title: "Your first app chain"
description: "Start the built-in ordered log and submit your first event."
sidebar:
  order: 2
---

Download and extract the **[JVM distribution](/start/installation/)**, recommended for app chains for now. From its extracted directory:

```bash
./yano.sh start:devnet,appchain
```

This activates the bundled app-chain profile, including `orders-chain`, a **single-member** `ordered-log` demo. Pre12 also includes a `registry-chain`; the current-source profile keeps `orders-chain` only. Its deterministic key is for local testing only. It is not a multi-member security demonstration and does not turn on L1 anchoring by itself.

## 1. Submit an event

```bash
curl -fsS -X POST \
  http://localhost:7070/api/v1/app-chain/chains/orders-chain/messages \
  -H 'Content-Type: application/json' \
  -d '{"topic":"order-created","body":"order A-1001"}'
```

Save the returned `messageId`. The response is HTTP `202`: the event has been accepted, but may not yet be finalized.

## 2. Read the finalized message

Replace `<message-id>` with the returned value. Query again after the proposer has had time to create a block:

```bash
curl -fsS   'http://localhost:7070/api/v1/app-chain/chains/orders-chain/messages/<message-id>'
curl -fsS \
  http://localhost:7070/api/v1/app-chain/chains/orders-chain/status
```

## 3. Follow the event stream

```bash
curl -N   'http://localhost:7070/api/v1/app-chain/chains/orders-chain/stream?fromHeight=0&topic=order-created'
```

SSE streams finalized messages. Design consumers for reconnects and duplicate delivery using durable application checkpoints.

## What the chain guarantees

`ordered-log` records opaque bytes in a shared finalized order. It does not enforce unique order IDs, a JSON schema, balances, or an order lifecycle. Two submissions of the same payload can produce different message IDs because their envelopes include sender sequence and expiry.

Use a custom state machine when consensus must enforce business rules. See [ordered-log in depth](/app-chains/ordered-log/) and [extension development](/app-chains/extensions/).
