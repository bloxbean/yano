# Tutorial 3 — Choose a Stock State Machine

[Open a stock approval workflow in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=approval-workflow&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=approval-workflow&chainId=approval-workflow)

- **Level:** beginner for selection, advanced for wire integration
- **Outcome:** choose the smallest built-in deterministic model that matches
  your application before writing a plugin.

Selecting a state machine is a consensus decision. Every member must use the
same id and deterministic settings. For a new chain it is configuration; for
an existing chain, changing semantics requires a governed/versioned profile
activation or a new chain.

## Selection guide

| Machine | Use it when | Authorization model | Proven state |
|---|---|---|---|
| [`ordered-log`](../state-machines/ordered-log.md) | You need immutable ordered opaque events | Any admitted member | Message by message ID |
| `kv-registry` | You need mutable named records | First writer owns a key | Current owner/value per key |
| `approvals` | Validator members are the approvers | Distinct member keys | Status and decision trail |
| `balances` | You need internal credits/netting | A member spends its own account | Balance per account |
| `doc-trail` | You need ordered history per product/case | Admitted member appends | Count and chained trail head |
| `evidence-v1-gated` | Approval coordinates S3/IPFS/Kafka publication | Stock composite workflow | One root across components/effects |
| `role-evidence` | Business actors differ from validator members | Governed actors, organizations, roles | Registry, policy, decisions, evidence |

## Configure a new standalone chain

The local launcher reads `app/config/application-appchain.yml`. A standalone
deployment can configure one machine directly:

```yaml
yano:
  app-chain:
    chain-id: workflow-chain
    state-machine: approvals
    members: <comma-separated-member-public-keys>
    threshold: 2
    signing-key: <this-member-seed-from-a-secret-source>
```

The cluster launcher's multi-chain form is:

```yaml
yano:
  app-chain:
    chains[2]:
      chain-id: workflow-chain
      state-machine: approvals
      block:
        interval-ms: 1000
```

The launcher injects members, threshold, signing key, peers, and fixed
proposer. Do not duplicate that injected material in the shared demo YAML.

## Typed command contracts

Stock machines interpret bounded CBOR commands. Use their Java encoders or a
compatible implementation; do not serialize arbitrary Java objects.

### Member approvals

```text
[0, itemId, payloadBytes, requiredApprovals, deadlineMillis]  propose
[1, itemId]                                                   approve
[2, itemId]                                                   reject
```

The state key is UTF-8 `i/<itemId>`. Approvers are deduplicated by member key,
one rejection is terminal, and deadlines use the finalized block timestamp.
Use this only when consortium node members intentionally are the business
approvers.

### Balances

```text
[0, destinationAccount, positiveAmount]  mint
[1, destinationAccount, positiveAmount]  transfer from sender's account
```

The state key is UTF-8 `b/<account>`. A configured minter can restrict minting;
a transfer that would overdraw is a deterministic no-op.

### Document trail

```text
[entityId, entryHashBytes, optionalReference]
```

The state key is UTF-8 `e/<entityId>`. The machine stores a count and running
Blake2b-256 head over previous head, entry hash, and author. Documents remain
off chain; retrieve event bodies from block history and prove the current head.

## Configuration is not arbitrary workflow composition

Configuration can select and parameterize already implemented semantics. It
cannot safely express arbitrary component order or terminal transitions,
because those affect every member's state root.

Use:

1. **stock configuration** when one machine/profile already matches;
2. **a small composite plugin** when existing components need a new committed
   order or transition; or
3. **a custom state-machine plugin** for new business state or rules.

## Common mistakes

- Treating a REST API key as an approval identity.
- Changing machine settings on one member only.
- Considering an accepted envelope proof that the command changed state.
- Putting large or secret documents in replicated command bodies.
- Adding network, wall-clock, DNS, or random behavior inside `apply()`.
- Reusing a machine id after changing its deterministic behavior.

## Go deeper

- The dedicated [`ordered-log` reference](../state-machines/ordered-log.md)
  covers topics, payloads, multiple instances, REST/Java submission, proofs,
  and customization paths.
- Exact state layouts and Java helper methods are documented in the
  [consensus guide](../../APP_CHAIN_CONSENSUS_GUIDE.md).
- The [user guide](../../APP_CHAIN_USER_GUIDE.md) covers configuration and
  payment-effect activation for `approvals`.
- For organization-distinct business authorization, continue with
  [domain-role approvals](05-domain-role-approvals.md).
- For coordinated document publication, continue with
  [the evidence scenario](04-evidence-publication.md).
