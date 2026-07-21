# `approvals` State Machine

`approvals` is Yano's built-in member-signature workflow for deterministic
`k-of-n` decisions. A proposer creates an item, distinct app-chain members
approve it, and the item becomes terminal when it is approved, rejected, or
expired. Every decision is replicated, threshold-finalized, and provable
against the app-chain state root.

The configured state-machine id is exactly `approvals`. Item ids and topics are
chosen by the application; they do not create new state-machine types.

## When to use it

Use `approvals` when app-chain member keys intentionally represent the people,
services, or organizations allowed to decide:

- release and deployment gates;
- consortium or treasury authorization;
- cross-organization sign-off;
- credential or document issuance approval;
- manual review before an external action; and
- a generic finalized decision that may emit an effect.

Choose a role-aware workflow or a custom state machine when business actors
must be independent of validator members, approvals have organization/role
clauses, votes carry delegated authority, or the workflow needs more states
than propose/approve/reject. A REST API key controls HTTP access; it is not an
approval identity.

## Decision model

The machine accepts three canonical CBOR command bodies:

```text
[0, itemId, payloadBytes, requiredApprovals, deadlineMillis]  PROPOSE
[1, itemId]                                                   APPROVE
[2, itemId]                                                   REJECT
```

Its rules are:

- The first `PROPOSE` for an item id creates a `PENDING` item. Later proposals
  for the same id are deterministic no-ops.
- `requiredApprovals` must be positive. Configure a reachable threshold; an
  item requiring more distinct members than are available can never approve.
- Each member public key counts at most once. Repeating an approval from the
  same member is a no-op.
- Reaching the required count changes the item to terminal `APPROVED`.
- One member rejection changes a pending item to terminal `REJECTED`.
- A command that touches a pending item after its deadline changes it to
  `EXPIRED`. Time passing alone does not create a block or mutate state.
- Approval or rejection of an unknown or terminal item is a no-op.

Deadlines are Unix epoch milliseconds compared with the finalized app-block
timestamp, not a node-local clock read during deterministic execution.

The authenticated envelope sender is the approver. With the local cluster,
submitting through ports 7070, 7071, and 7072 uses three different member
identities.

## Configuration

Configure a standalone approval chain with:

```yaml
yano:
  app-chain:
    enabled: true
    chain-id: approvals-chain
    state-machine: approvals
```

In a multi-chain deployment:

```yaml
yano:
  app-chain:
    chains[0]:
      chain-id: approvals-chain
      state-machine: approvals
      membership:
        mode: governed
      block:
        interval-ms: 1000
```

All members must use the same machine id and consensus-affecting settings.
The local launcher injects the demo member keys, threshold, proposer, and peer
addresses. Production deployments should obtain those values and signing
secrets from generated per-node configuration and secret management.

The default three-node demo already hosts `effects-chain` using `approvals`,
with a generic demonstration effect attached:

```bash
./yano.sh appchain cluster start 3
```

## Submit proposal and approvals through REST

REST transports the canonical command bytes in `bodyHex`. From the source
checkout's `app/` directory, use the maintained dependency-free tutorial
encoder:

```bash
TOOL=../docs/appchain/tutorials/tools/stdlib_command.py
ITEM=release-2026-07

PROPOSE_HEX=$(python3 "$TOOL" approvals propose "$ITEM" \
  --required 2 \
  --payload-text '{"artifact":"inventory-service:2.4.0"}')

APPROVE_HEX=$(python3 "$TOOL" approvals approve "$ITEM")
REJECT_HEX=$(python3 "$TOOL" approvals reject "$ITEM")
```

Submit the proposal through node 0:

```bash
curl -sS -X POST \
  http://127.0.0.1:7070/api/v1/app-chain/chains/effects-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$PROPOSE_HEX\"}" | jq .
```

Submit two approvals through distinct members:

```bash
curl -sS -X POST \
  http://127.0.0.1:7071/api/v1/app-chain/chains/effects-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$APPROVE_HEX\"}" | jq .

curl -sS -X POST \
  http://127.0.0.1:7072/api/v1/app-chain/chains/effects-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$APPROVE_HEX\"}" | jq .
```

Use the chain id from your configuration when it is not `effects-chain`.
The topic is an application routing label; the stock machine interprets the
CBOR command body, not the topic name.

HTTP `202` means that the ingress node accepted the signed envelope. It does
not mean the command has finalized or changed the item. Wait for finality and
inspect the state proof before treating the decision as committed.

To reject a still-pending item, submit `REJECT_HEX` through any member. Do not
submit both paths in one workflow unless the application is deliberately
testing deterministic ordering: whichever terminal transition finalizes first
governs later commands.

### Add a deadline

Pass an absolute epoch-millisecond value:

```bash
DEADLINE=$(python3 -c 'import time; print(int((time.time() + 300) * 1000))')

PROPOSE_HEX=$(python3 "$TOOL" approvals propose expiring-review-001 \
  --required 2 \
  --deadline-millis "$DEADLINE" \
  --payload-text '{"document":"policy-v3"}')
```

After the deadline, the next approval or rejection command deterministically
marks the pending item `EXPIRED`.

## Submit from Java

Use the client and standard-library artifacts with the same Yano version as
the nodes:

```groovy
implementation "com.bloxbean.cardano:yano-appchain-client:${yanoVersion}"
implementation "com.bloxbean.cardano:yano-appchain-stdlib:${yanoVersion}"
```

The standard library provides canonical encoders, so applications do not need
to assemble CBOR themselves:

```java
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.stdlib.ApprovalsStateMachine;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

var proposer = AppChainClient.builder("http://127.0.0.1:7070/api/v1")
        .chainId("effects-chain")
        .build();
var approver1 = AppChainClient.builder("http://127.0.0.1:7071/api/v1")
        .chainId("effects-chain")
        .build();
var approver2 = AppChainClient.builder("http://127.0.0.1:7072/api/v1")
        .chainId("effects-chain")
        .build();

String itemId = "release-2026-07";
byte[] payload = "{\"artifact\":\"inventory-service:2.4.0\"}"
        .getBytes(StandardCharsets.UTF_8);
long deadline = Instant.now().plusSeconds(300).toEpochMilli();

proposer.submit("approvals",
        ApprovalsStateMachine.propose(itemId, payload, 2, deadline));
approver1.submit("approvals", ApprovalsStateMachine.approve(itemId));
approver2.submit("approvals", ApprovalsStateMachine.approve(itemId));
```

When API authentication is enabled, add `.apiKey("...")` to each builder.
Using two client objects against the same node does not create two approvers;
the envelope sender remains that node's member key.

## State and proofs

Each item is stored under UTF-8 key `i/<itemId>` as canonical CBOR:

```text
[status, proposer, payloadHash, required, deadline, approvers[], rejecter]
```

Status values are `0=PENDING`, `1=APPROVED`, `2=REJECTED`, and `3=EXPIRED`.
The state retains the Blake2b-256 payload hash rather than duplicating the
proposal payload. The original command remains in finalized block history
while its body is retained; archive the command/evidence when long-term body
availability is required.

Request an MPF proof by hex-encoding the physical key:

```bash
ITEM_KEY_HEX=$(python3 -c 'print("i/release-2026-07".encode().hex())')

curl -sS \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/effects-chain/proof/$ITEM_KEY_HEX" \
  | jq .
```

The response's `valueHex` is the CBOR state entry. Verify `proofWireHex`
against an independently trusted state root before trusting that value. Java
can verify and decode it:

```java
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;

import java.util.HexFormat;

var proof = proposer.proof(ApprovalsStateMachine.itemKey(itemId)).orElseThrow();
if (!ProofVerifier.verify(proof) || proof.valueHex() == null) {
    throw new IllegalStateException("approval state is absent or unverified");
}

var item = ApprovalsStateMachine.decodeItem(
        HexFormat.of().parseHex(proof.valueHex()));
System.out.println("status=" + item.status());
System.out.println("approvals=" + item.approvers().size());
```

For audit-grade verification, pin the chain profile and obtain the expected
state root from an independently verified finality certificate or Cardano
anchor rather than accepting the serving node's root as its own authority.

## Attach a generic on-approved effect

An approval decision can optionally emit one generic effect when it first
becomes `APPROVED`:

```yaml
yano:
  app-chain:
    chains[0]:
      chain-id: approvals-chain
      state-machine: approvals
      effects:
        enabled: true
        external:
          enabled: true
        executor:
          enabled: true
          types: demo.webhook
        metrics:
          types: demo.webhook
      machines:
        approvals:
          on-approved-effect:
            enabled: true
            type: demo.webhook
            gate: app-final
            expiry-blocks: 100
          activations:
            on-approved-effect: 1
```

The proposal payload becomes the opaque effect payload. The routing `type`
tells an executor which contract to apply; the approvals machine does not
interpret it as a webhook, payment, or any other domain action. Production
types can route to packaged or custom executor plugins.

The decision and delivery lifecycle remain separate:

```text
i/<itemId>     -> APPROVED
ae/s/<itemId>  -> PENDING -> CONFIRMED | FAILED
effect scope   -> approvals/on-approved/<itemId>
```

Effect success or failure never rewrites the business decision from
`APPROVED`. Select the activation height before the first proposal that should
emit an effect; proposals finalized before activation do not stage their
payload retroactively.

The default local demo exercises the complete lifecycle:

```bash
./yano.sh appchain cluster effect demo "release 2026-07 approved"
```

It creates and approves a one-approval item, emits `demo.webhook`, acts as a
simulated external worker, reports success, and checks the finalized effect
proof. It does not call a real webhook. For a real HTTP delivery, configure
the `webhook.post` executor as shown in the webhook-effects tutorial.

## Operational and security notes

- Member signing keys are approval authority; protect and rotate them as
  consensus identities, not as ordinary API credentials.
- The proposal payload is replicated to every member and retained in block
  history. Do not submit secrets without application-level encryption.
- Business idempotency comes from a stable item id. Reusing an id with a new
  payload does not replace the original proposal.
- Adding members does not change an existing item's numeric required count.
- Switching an existing chain to different state-machine semantics requires a
  governed/versioned profile activation or a new chain, not a local YAML edit.
- External execution is at-least-once. Executors must honor the effect's
  idempotency identity and report a bounded outcome.

## Related documentation

- [First app chain and local effect demo](../tutorials/01-first-app-chain.md)
- [Stock state-machine cookbook](../tutorials/03-stock-state-machines.md)
- [Webhook effects](../tutorials/06-webhook-effects.md)
- [Domain-role approvals](../tutorials/05-domain-role-approvals.md)
- [Complete app-chain user guide](../../APP_CHAIN_USER_GUIDE.md)
- [Java app-chain client](../../../appchain/appchain-client/README.md)
