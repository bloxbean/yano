# Tutorial 5 — Domain Actors and Role-Aware Approval

[Open this outcome in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=evidence-ledger&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=docker-compose&name=role-evidence&chainId=role-evidence)

- **Level:** beginner for the stock demo, advanced for identity governance
- **Time:** about 30 minutes
- **Outcome:** authorize evidence with business actors and organization-distinct
  roles rather than treating validator nodes as employees or auditors.

This tutorial uses the stock `role-evidence` profile. The scenario separates:

- **member identity:** which consortium node relayed an envelope; and
- **actor identity:** which manufacturer, auditor, or regulator signed the
  exact business statement.

## 1. Start a fresh role profile

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

## 2. Publish actor-authorized evidence

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

## 3. Inspect and verify

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

## 4. Exercise rotation and revocation

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

## 5. Stop and retain

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
