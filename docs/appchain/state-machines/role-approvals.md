# `role-approvals` State Machine

`role-approvals` is Yano's application-neutral authorization product for cases
where business actors are not the app-chain validator members. Governed
organizations, actors, public keys, roles and policies authorize an exact
payload hash; the finalized proposal and decision trail are committed under
the app-chain state root.

It is bundled for JVM and native distributions and currently has `preview`
maturity.

## When to use it

Use `role-approvals` when:

- an employee, auditor, regulator, service or device must sign independently
  of the member node relaying its command;
- a policy needs role checks, minimum counts, or distinct organizations;
- actor keys and policies must rotate through governed revisions; and
- the application can act on a provably approved payload hash.

Use [`approvals`](approvals.md) instead when validator members themselves are
the approvers. Use `role-evidence` when you specifically need Yano's complete
evidence release and publication workflow. Use a reviewed composite plugin
when approval must atomically drive another deterministic domain transition.

The generic machine does not store arbitrary payload bytes, execute them, or
emit an effect. An approval proves authorization of `(payloadDomain,
payloadHash)`; the application remains responsible for binding those bytes to
its next action.

## Create a project

From an extracted release or the source `app/` directory:

```bash
./yano.sh appchain init --non-interactive \
  --recipe role-approval \
  --network devnet \
  --members 3 \
  --runtime jvm \
  --deployment host \
  --name role-approval-chain \
  --chain-id role-approval-chain \
  --output role-approval-chain
```

Add the reviewed public member keys to the generated blueprint, render, and
validate it:

```bash
./yano.sh appchain render role-approval-chain
./yano.sh appchain config validate --mode project role-approval-chain
./yano.sh appchain doctor role-approval-chain --distribution .
```

The generated `bootstrap/role-approvals-plan.yaml` is a non-secret plan for
organizations, actors, proof-of-possession, policies, governance and
verification. Replace its placeholders with public values only. Private actor
keys belong in the actor application, KMS, HSM or vault—not in Yano, Studio,
the blueprint or the bootstrap plan.

Bootstrap operations are fail-closed and idempotent:

1. Query the committed record and verify its proof.
2. If the exact revision/value already exists, record the proof and skip it.
3. If it is absent, submit `PROPOSE`, collect member approvals to the configured
   threshold, then `ACTIVATE`.
4. If an existing revision or value differs, stop; never replace it silently.

## Identity model

```text
outer AppMessage sender = consortium member that relayed the command
signed actor identity   = business actor authorizing the payload hash
```

REST authentication only controls access to an endpoint. It is neither a
member vote nor a business-actor signature.

An actor statement binds its action, chain, proposal, policy revision, payload
domain/hash, deadline, actor revision, key and policy clause. A valid signature
therefore cannot be replayed for a different chain, payload, role decision or
policy revision.

## Topics and state

| Purpose | Topic or committed query |
|---|---|
| Govern organizations, actors and keys | `actors.command.v1` |
| Submit actor proposals and decisions | `role-approvals.command.v1` |
| Current organization | `components/domain-actors/organization-current`, params `id` |
| Organization revision | `components/domain-actors/organization`, params `id@revision` |
| Current actor | `components/domain-actors/actor-current`, params `id` |
| Actor revision | `components/domain-actors/actor`, params `id@revision` |
| Current policy | `components/role-approvals/policy-current`, params `id` |
| Policy revision | `components/role-approvals/policy`, params `id@revision` |
| Proposal | `components/role-approvals/proposal`, params `proposalId` |
| Proposal statistics | `components/role-approvals/stats`, empty params |

The physical state keys are namespaced by the committed composite profile.
Use the proof key returned by the domain API rather than constructing a
physical key from a display identifier.

## Sign and submit a decision

Hash the exact canonical application bytes before asking actors to sign them.
The payload domain must identify that byte contract, for example
`com.example.order.v1`.

```bash
PAYLOAD_HASH=$(openssl dgst -sha256 -binary approved-order.cbor | xxd -p -c 256)

COMMAND_HEX=$(./yano.sh appchain role sign \
  --action approve \
  --chain role-approval-chain \
  --proposal order-a-1001 \
  --policy order-release \
  --policy-revision 1 \
  --payload-domain com.example.order.v1 \
  --payload-hash "$PAYLOAD_HASH" \
  --deadline-height 1000 \
  --actor reviewer-a \
  --actor-revision 1 \
  --key reviewer-key-v1 \
  --clause reviewers \
  --seed-file /owner-only/reviewer.seed)
```

Submit the canonical CBOR through any member. The member relays the command;
the embedded actor signature determines whose decision is evaluated:

```bash
curl -sS -X POST \
  http://127.0.0.1:7070/api/v1/app-chain/chains/role-approval-chain/messages \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: $YANO_APPCHAIN_API_KEY" \
  -d "{\"topic\":\"role-approvals.command.v1\",\"bodyHex\":\"$COMMAND_HEX\"}" | jq .
```

HTTP `202` means accepted for sequencing, not approved. Wait for finality and
query the proposal.

## Query through REST and verify

The generic bundle exposes read-only JSON projections:

```bash
BASE=http://127.0.0.1:7070/api/v1
BUNDLE=com.bloxbean.cardano.yano.appchain.role-workflow

curl -sS \
  -H "X-API-Key: $YANO_APPCHAIN_API_KEY" \
  "$BASE/plugins/$BUNDLE/proposals/order-a-1001?chain=role-approval-chain" | jq .

curl -sS \
  -H "X-API-Key: $YANO_APPCHAIN_API_KEY" \
  "$BASE/plugins/$BUNDLE/stats?chain=role-approval-chain" | jq .
```

Organization, actor and policy routes follow the same pattern:

```text
organizations/{id}?chain={chainId}[&revision=N]
actors/{id}?chain={chainId}[&revision=N]
policies/{id}?chain={chainId}[&revision=N]
```

A response includes `committedHeight`, `stateRoot`, `proofKey` and
`recordValue`. When the route resolves a current revision, it also returns the
current-pointer proof key/value. Verify both proofs at the same height/root:
the record proof establishes that a revision exists; the pointer proof
establishes that it was current.

## Submit from Java

Use the dependency-light contracts plus the generic client:

```groovy
implementation "com.bloxbean.cardano:yano-appchain-client:${yanoVersion}"
implementation "com.bloxbean.cardano:yano-appchain-role-workflow-contracts:${yanoVersion}"
```

```java
var statement = new ActorStatementV1(
        ActorStatementV1.Action.APPROVE,
        "role-approval-chain", "order-a-1001", "order-release", 1,
        "com.example.order.v1", payloadHash, 1000,
        "reviewer-a", 1, "reviewer-key-v1", "reviewers");

byte[] command = SignedActorCommandV1.sign(statement, actorSeed).encode();

var client = AppChainClient.builder("http://127.0.0.1:7070/api/v1")
        .chainId("role-approval-chain")
        .apiKey(System.getenv("YANO_APPCHAIN_API_KEY"))
        .build();

var submitted = client.submit("role-approvals.command.v1", command);
System.out.println(submitted.messageId());
```

Production code should implement `ActorStatementV1.signingPreimage()` in its
KMS/HSM signer rather than loading a raw seed into application memory.

## Deterministic behavior and limits

- Roles are normalized strings, not a fixed evidence enum.
- Policies support proposer roles, bounded AND clauses, minimum counts,
  distinct actors or organizations, rejection behavior and lifetimes.
- Actor and policy revisions are immutable; current pointers are governed.
- V1 retains at most 16 key epochs per actor.
- Pending proposals and governed mutations are bounded.
- Invalid, stale, ineligible or wrongly signed finalized commands are
  deterministic no-ops; verify resulting state rather than message inclusion.
- Proposal expiration is materialized when a later command touches the pending
  proposal after its block-height deadline; there is no unbounded global scan.

## Effects and application transitions

The stock generic profile deliberately emits no effect because an approved
hash is not executable payload bytes. An application can:

1. query the terminal proposal and act idempotently off chain;
2. submit a separately validated domain command bound to the proposal; or
3. install a reviewed composite profile that atomically consumes the approval.

Do not attach an arbitrary executor and assume it knows which bytes were
approved. A new automatic effect or domain transition needs a versioned
contract that binds exact executable bytes to the approved hash.

## Operations and recovery

- Monitor pending, rejected and expired proposal counts.
- Rotate keys by governing the next actor revision with proof-of-possession.
- Suspend or revoke compromised actors, then govern cancellation of affected
  pending proposals.
- Historical approved decisions remain immutable after rotation or revocation.
- Treat a changed profile digest, component order, route or state identity as
  a consensus upgrade requiring governed activation or a fresh chain.

For the complete wire, governance and recovery model, see
[Domain Actors and Role-Aware Approvals](../../APP_CHAIN_DOMAIN_ROLES.md), the
[portable contracts](../../../appchain/appchain-role-workflow-contracts/README.md),
and the [generic implementation guide](../../../appchain/appchain-role-workflow/README.md).
