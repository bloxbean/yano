# Tutorial 5 — Generic Domain Actors and Role-Aware Approval

[Open generic role approval in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=role-approval&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=role-approval&chainId=role-approval)

- **Level:** application developer; advanced for identity governance
- **Time:** about 30 minutes
- **Outcome:** authorize an application payload hash with governed business
  actors and organization-distinct roles, then see how the evidence product
  consumes the same generic primitives.

Both paths separate:

- **member identity:** which consortium node relayed an envelope; and
- **actor identity:** which application actor signed the exact business
  statement.

## Track A — Generic `role-approvals`

Create a release-pinned project without selecting the evidence product:

```bash
./yano.sh appchain init --non-interactive \
  --recipe role-approval --network devnet --members 3 \
  --runtime jvm --deployment host \
  --name role-approval --chain-id role-approval \
  --output role-approval

./yano.sh appchain config validate --mode project role-approval
```

Because public member identities have not been supplied, the first validation
correctly reports the bootstrap acknowledgement. Add the three reviewed member
keys to `role-approval/appchain.yaml`, render, and run `doctor` against the
distribution before starting nodes.

Review `role-approval/bootstrap/role-approvals-plan.yaml`. It defines a safe,
non-secret sequence for governing organizations, actors and a policy. Replace
the sample role names (`proposer`, `reviewer`) with application roles when
needed; roles are strings, not evidence-specific enums. Generate public keys
and proof-of-possession with `./yano.sh appchain role`, then submit governed
records on `actors.command.v1`.

After activation, hash the exact application command or document bytes and
submit an actor-signed proposal/decision on `role-approvals.command.v1`:

```bash
COMMAND_HEX=$(./yano.sh appchain role sign \
  --action approve --chain role-approval --proposal order-a-1001 \
  --policy order-release --policy-revision 1 \
  --payload-domain com.example.order.v1 --payload-hash <64-hex> \
  --deadline-height 1000 --actor reviewer-a --actor-revision 1 \
  --key reviewer-key-v1 --clause reviewers \
  --seed-file /owner-only/reviewer.seed)
```

Any member may relay those bytes; the embedded actor signature carries the
business identity. Query the terminal proposal and its proof-oriented fields:

```bash
curl -sS \
  -H "X-API-Key: $YANO_APPCHAIN_API_KEY" \
  "http://127.0.0.1:7070/api/v1/plugins/com.bloxbean.cardano.yano.appchain.role-workflow/proposals/order-a-1001?chain=role-approval" | jq .
```

The generic machine proves the approved payload domain/hash and emits no
effect. Your application may act idempotently after verifying that result, or
a reviewed composite plugin may consume it atomically. The complete REST,
Java, proof, bootstrap and recovery walkthrough is in the dedicated
[`role-approvals` reference](../state-machines/role-approvals.md).

## Track B — Evidence-specific demonstration

[Open the evidence outcome in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=evidence-ledger&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=docker-compose&name=role-evidence&chainId=role-evidence)

The following runnable demo uses the stock `role-evidence` profile. It fixes
manufacturer, auditor and regulator roles for its evidence scenario and adds
the evidence transition and optional publication effects to the generic actor
and approval foundations.

### B1. Start a fresh role profile

```bash
cd app/appchain-effects-demo

./demo.sh up \
  --instance tutorial-roles \
  --machine role \
  --continuation direct
```

The profile commits its organization/actor registry, policy, component order,
routes, effect workflow, administrator threshold, and deterministic limits.
It is not a local YAML toggle for an existing chain.

### B2. Publish actor-authorized evidence

```bash
./demo.sh publish \
  --instance tutorial-roles \
  --machine role \
  --continuation direct \
  --evidence-id regulated-product-001 \
  --sample-file samples/inspection-certificate.json
```

The stock policy requires a manufacturer proposal, two independent auditor
organizations, and a regulator. The runner also submits negative controls:

- an actor with the wrong role;
- an approval bound to the wrong payload; and
- two actors from the same organization attempting to satisfy an
  organization-distinct clause.

Those envelopes can finalize, but they are deterministic no-ops. Only eligible
signed decisions contribute to the policy result.

### B3. Inspect and verify

```bash
./demo.sh verify \
  --instance tutorial-roles \
  --machine role \
  --continuation direct \
  --evidence-id regulated-product-001
```

Open <http://127.0.0.1:7080/>. The report distinguishes relay member, actor,
organization, role, policy revision, clause, and signed decision. Current
actor/policy projections include both the immutable revision proof and the
same-root current-pointer proof, so “this revision exists” cannot be confused
with “this is the current governed revision.”

### B4. Exercise rotation and revocation

```bash
./demo.sh role-lifecycle \
  --instance tutorial-roles \
  --machine role \
  --continuation direct
```

The idempotent lifecycle uses a dedicated recovery actor and demonstrates:

1. governed onboarding with proof-of-possession;
2. signing-key rotation;
3. rejection of the old actor revision/key;
4. acceptance of the new revision/key;
5. revocation;
6. rejection after revocation; and
7. historical revision and decision proofs.

This is not app-chain membership rotation. Business credentials and validator
membership are intentionally governed through separate mechanisms.

### B5. Stop and retain

```bash
./demo.sh stop \
  --instance tutorial-roles \
  --machine role \
  --continuation direct
```

## Reuse levels

### Configuration only

Use a stock profile when its action and terminal transition already match.
Governed data can define actors, organizations, roles, policies, counts,
organization distinctness, deadlines, and connector targets.

### Small composite plugin

Use existing registry/approval/evidence/payment components in a new explicit
order or connect approval to a different terminal action. Component order and
cross-component transitions affect consensus, so they remain reviewed Java
composition rather than dynamic YAML wiring.

### Custom state-machine plugin

Use this only for new state or business rules, such as insurance claim
calculation or supply-chain ownership transfer.

## Production boundaries

- The demo uses locally held deterministic actor seeds. Production signing
  belongs in KMS/HSM/Vault or an actor-owned signing service.
- Yano proves that registered keys authorized exact bytes under a governed
  policy. Legal identity and real-world truth remain onboarding/audit duties.
- The v1 actor record retains at most 16 key epochs. Plan successor identity or
  governed contract evolution before reaching that limit.
- Administrator authority is profile-committed; changing it uses governed
  profile evolution, not an ordinary actor mutation.

## Go deeper

- Read the complete [domain-role guide](../../APP_CHAIN_DOMAIN_ROLES.md).
- Verify signed actor commands independently with the published golden vectors.
- Inspect the plugin domain API and exact MPF proof keys.
- Run the isolated three-member restart/catch-up gate documented in the role
  guide.
