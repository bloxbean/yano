# `ordered-log` State Machine

`ordered-log` is Yano's built-in append-only state machine for opaque events.
It gives applications one threshold-finalized order, replicated history,
provable message positions, and an optionally Cardano-anchored state root. It
does not parse payloads or implement business rules.

The configured id is exactly `ordered-log`. Names such as `orders-chain` are
chain ids chosen by the operator; they do not create an order-specific data
model.

## When to use it

Use `ordered-log` when participants need to agree that an event was finalized
at a particular position, while applications interpret the event body:

- cross-organization event and audit logs;
- order, shipment, case, or compliance histories;
- immutable evidence and document-hash journals;
- a shared event stream consumed by external services; and
- notarization of arbitrary application bytes.

Choose another state machine when the chain must maintain or enforce business
state. `ordered-log` does not enforce schemas, unique order ids, lifecycle
transitions, balances, ownership, approvals, or application-level
authorization.

## Mental model: chain, topic, and payload

These are independent concepts:

| Concept | Example | Meaning |
|---|---|---|
| Chain id | `orders-chain` | Independent ledger, ordering, finality, state root, proofs, and optional anchor |
| State-machine id | `ordered-log` | Deterministic logic applied to finalized blocks |
| Topic | `order-created` | Caller-supplied label used for routing and filtering |
| Payload | `{"orderId":"A-1001"}` | Opaque bytes owned and interpreted by the application |

One chain can carry many topics:

```text
orders-chain
├── order-created
├── order-paid
├── order-shipped
└── order-cancelled
```

The state machine does not assign meaning to those topic names. Ordinary
topics may be any valid UTF-8 value within the framework limit; names starting
with `~` are reserved for Yano.

## Start the out-of-the-box demo

From an extracted release directory, or from `app/` after building the source
tree:

```bash
./yano.sh appchain cluster start 3
```

The demo hosts `orders-chain` as an `ordered-log` chain. Submit through member
1:

```bash
./yano.sh appchain cluster submit orders-chain order-created \
  '{"orderId":"A-1001","quantity":4}' \
  --node 1
```

The command shape is:

```text
./yano.sh appchain cluster submit <chain-id> <topic> <payload> [--node <index>]
```

`--node 1` selects the ingress node. That member authenticates and signs the
envelope, then gossips it to the cluster. It does not make node 1 the
sequencer, state owner, or business-event processor.

The payload need not be JSON:

```bash
./yano.sh appchain cluster submit orders-chain notes \
  'order A-1001 was checked manually'
```

The launcher submits command-line payloads as UTF-8 text. Use the REST
`bodyHex` field or the Java client's byte-array method for arbitrary binary
payloads.

## Configuration

A single configured chain can select the machine directly:

```yaml
yano:
  app-chain:
    enabled: true
    chain-id: orders-chain
    state-machine: ordered-log
```

The multi-chain form is:

```yaml
yano:
  app-chain:
    chains[0]:
      chain-id: orders-chain
      state-machine: ordered-log
      membership:
        mode: governed

    chains[1]:
      chain-id: shipments-chain
      state-machine: ordered-log
      membership:
        mode: governed
```

Both chains use the same implementation but remain independent. Each has its
own blocks, pending pool, finality certificates, state root, membership,
sequencing policy, storage, proofs, and optional L1 anchor.

Use multiple topics in one chain when the events share the same membership,
finality, retention, anchoring, and operational lifecycle. Use separate chains
when any of those boundaries should differ.

The local cluster launcher reads `app/config/application-appchain.yml` and
injects node-specific member keys, peer addresses, threshold, and proposer.
Production deployments must supply those values through their generated
per-node configuration and secret-management flow.

### Operational tuning

`ordered-log` has no machine-specific settings. It uses the common chain
settings for capacity, latency, expiry, retention, sequencing, membership, and
anchoring. For example:

```yaml
yano:
  app-chain:
    chains[0]:
      chain-id: orders-chain
      state-machine: ordered-log
      max-message-bytes: 65536
      default-ttl-seconds: 600
      max-ttl-seconds: 3600
      block:
        interval-ms: 1000
        max-bytes: 4194304
        max-messages: 5000
      pool:
        max-messages: 10000
      retention:
        enabled: true
        keep-blocks: 1000
```

Keep consensus-affecting settings identical across members. When retention is
enabled, eligible old message bodies below the confirmed anchor horizon may be
stripped, while headers, ids, roots, and certificates remain so inclusion
evidence is preserved. Archive bodies or evidence bundles separately when the
original content must remain independently verifiable.

## Submit through REST

In the default local cluster, node indices 0, 1, and 2 use HTTP ports 7070,
7071, and 7072. This request is equivalent to CLI submission with `--node 1`:

```bash
RESPONSE=$(curl -sS -X POST \
  http://127.0.0.1:7071/api/v1/app-chain/chains/orders-chain/messages \
  -H 'Content-Type: application/json' \
  -d '{
    "topic":"order-created",
    "body":"{\"orderId\":\"A-1001\",\"quantity\":4}"
  }')

echo "$RESPONSE" | jq .
MESSAGE_ID=$(echo "$RESPONSE" | jq -r .messageId)
```

A successful submission returns HTTP `202`:

```json
{
  "messageId": "<64 lowercase hex characters>",
  "chainId": "orders-chain",
  "topic": "order-created"
}
```

For arbitrary bytes, send hexadecimal data instead:

```bash
curl -sS -X POST \
  http://127.0.0.1:7071/api/v1/app-chain/chains/orders-chain/messages \
  -H 'Content-Type: application/json' \
  -d '{"topic":"binary-event","bodyHex":"010203ff"}' | jq .
```

When REST authentication is enabled, also send `X-API-Key`. A topic-scoped API
key can restrict which topics a caller may submit to, but that is an ingress
policy rather than an `ordered-log` consensus rule.

## Submit from Java

Use the lightweight `yano-appchain-client` artifact with the same version as
the Yano nodes:

```groovy
implementation "com.bloxbean.cardano:yano-appchain-client:${yanoVersion}"
```

```java
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;

AppChainClient client = AppChainClient
        .builder("http://127.0.0.1:7071/api/v1")
        .chainId("orders-chain")
        // .apiKey("secret") // when REST authentication is enabled
        .build();

String payload = """
        {"event":"order-created","orderId":"A-1001","quantity":4}
        """.strip();

var submitted = client.submitText("orders", payload);
System.out.println(submitted.messageId());
```

Use `client.submit(topic, byte[])` for arbitrary bytes or
`client.submitTyped(topic, value, encoder)` with an application-owned JSON,
CBOR, or protobuf encoder.

Submitting identical payload bytes twice normally creates two messages. The
signed envelope also contains sender sequence and expiry data, so each
submission has its own message id. Business-level idempotency, such as
uniqueness by `orderId`, requires application logic or a custom state machine.

## What happens after submission

1. The ingress node checks framework bounds, signs the envelope with its member
   key, retains it in the pending pool, and gossips it.
2. The current proposer orders pending messages into an app block.
3. Every voting member deterministically executes `ordered-log` and derives
   the same state root.
4. The configured threshold certifies the block.
5. Members commit the block, message index, state, and finality certificate.
6. If anchoring is enabled, a later anchor commits the certified application
   root to Cardano.

HTTP `202` means the ingress node accepted the envelope; it does not mean the
message is finalized. Confirm finalization before treating the event as
committed:

```bash
until curl -sf \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/messages/$MESSAGE_ID" \
  | jq .; do
  sleep 1
done
```

Applications can also follow finalized messages through the SSE endpoint:

```text
GET /api/v1/app-chain/chains/orders-chain/stream?fromHeight=0&topic=orders
```

The Java client exposes the same behavior through `subscribe(...)` and
`subscribeTyped(...)`.

## Committed state and proofs

For each finalized message, `ordered-log` writes:

```text
message-id -> cbor([block-height, message-index, topic, sender])
```

It also maintains:

```text
~tip -> cbor(block-height)
```

The message body remains in finalized block history and the message index; it
is not duplicated in the state value. The state key is the 32-byte message id,
which lets a client request an MPF inclusion proof directly:

```bash
curl -s \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/proof/$MESSAGE_ID" \
  | jq .
```

That proof binds the message's finalized position, topic, and sender to the
returned committed state root. For audit-grade verification, verify it against
an independently trusted root, such as the matching Cardano anchor, rather
than trusting a root supplied by the same node.

Other useful endpoints are:

```text
GET /chains/{chainId}/messages/{messageId}
GET /chains/{chainId}/messages/by-topic/{topic}?fromHeight=0&limit=100
GET /chains/{chainId}/blocks?from=1&limit=100
GET /chains/{chainId}/evidence/{messageId}
GET /chains/{chainId}/status
```

All paths above are relative to `/api/v1/app-chain`.

## Bounds and security behavior

The public submission path and default framework profile apply these
constraints:

- a non-empty body on REST and CLI submission;
- body size at most 65,536 bytes by default (`max-message-bytes`);
- topic size at most 256 UTF-8 bytes with no NUL character;
- topics starting with `~` are reserved;
- envelope signature and current member authorization;
- pending-pool capacity and message expiry; and
- structural, replay, block-size, and finality checks.

These checks protect the protocol. They do not validate a JSON schema or any
business meaning. Do not put secrets or unnecessary personal data in payloads:
every member receives the body and finalized history may be retained or
exported. Encrypt application bodies before submission when confidentiality is
required, and manage decryption keys outside consensus.

## Customization choices

### Use payload conventions when validation is external

Applications may agree on a versioned envelope such as:

```json
{
  "schemaVersion": 1,
  "event": "order-created",
  "eventId": "evt-9001",
  "orderId": "A-1001",
  "quantity": 4
}
```

Producers and consumers can validate this schema without changing Yano. The
chain still accepts other bytes, so this is appropriate only when business
validation is deliberately outside consensus.

### Use topics for routing, not enforcement

Topics let consumers filter one ordered history. They are useful for event
families such as `order-created`, `order-paid`, and `order-shipped`, but the
machine does not enforce a topic allow-list or a topic-specific payload shape.

### Use separate chains for isolation

Run multiple `ordered-log` chains when applications require distinct member
sets, sequencing, retention, anchoring, or failure boundaries. Reusing the
same state-machine implementation does not share state between chains.

### Write a custom state-machine plugin for business rules

Use a custom `AppStateMachine` when consensus must enforce rules such as:

- strict payload decoding and bounds;
- unique order ids;
- allowed transitions such as `CREATED -> PAID -> SHIPPED`;
- sender- or role-based authorization; or
- committed current state keyed by `orderId`.

Package the implementation behind `AppStateMachineProvider`, a ServiceLoader
entry, and a Yano plugin manifest. Deploy the identical bundle to every voting
member of a JVM cluster and select its id with `state-machine`. Start with the
[plugin template](../../../scaffolds/plugin-template/README.md).

Admission hooks improve feedback and keep malformed commands out of blocks
built by honest proposers, but deterministic `apply()` logic remains the
consensus authority. It must re-decode input and safely handle invalid or
stale commands without external I/O, randomness, wall-clock reads, or other
node-local behavior.

Do not switch an existing `ordered-log` ledger to incompatible application
semantics in place. Use a fresh chain id/storage or a deliberately designed,
versioned migration and activation plan.

### Use consumers or effects for external actions

`ordered-log` itself does not call webhooks, Kafka, an ERP, or another external
system. A consumer can subscribe to finalized messages and perform idempotent
off-chain work. When an external action and its outcome must participate in
the committed workflow, use an effect-emitting stock/composite machine or a
custom state-machine and executor plugin.

Never perform network, database, filesystem, or other external I/O from a
state machine's deterministic `apply()` method.

## Related documentation

- [Your first app chain](../tutorials/01-first-app-chain.md)
- [Stock state-machine cookbook](../tutorials/03-stock-state-machines.md)
- [Plugins and composites](../tutorials/08-plugins-and-composites.md)
- [Complete app-chain user guide](../../APP_CHAIN_USER_GUIDE.md)
- [Consensus and state-machine internals](../../APP_CHAIN_CONSENSUS_GUIDE.md)
- [Java app-chain client](../../../appchain/appchain-client/README.md)
