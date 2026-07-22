# Yano app-chain evidence profile

This first-party product module owns the evidence-specific assemblies built on
the generic composite and role-workflow frameworks:

- the `composite` provider's `evidence-v1` and `evidence-v1-gated` profiles;
- the `role-evidence` provider;
- evidence release and notification workflows; and
- the evidence-specific role query projection.

Generic composition, actor, role, policy, and approval code belongs to
`appchain-composite` and `appchain-role-workflow`. Those modules must never
depend on this product module.

## Selecting a profile

The `composite` provider supports `evidence-v1-gated` (recommended) and the
direct-command compatibility preset `evidence-v1`. The `role-evidence`
provider uses the committed `evidence-role-v1` profile.

```yaml
yano:
  app-chain:
    effects:
      enabled: true
      max-per-block: 128
    chains:
      - id: evidence-chain
        state-machine: role-evidence
        machines:
          composite:
            preset: role-evidence-v1
            profile-mode: governed
            evidence-capacity-per-block: 8
          kv-registry:
            value-format: raw
        membership:
          mode: governed
```

The role profile commits the generic registry, actor, approvals and document
components together with the evidence registry and evidence-specific release
workflows. Its evidence-to-proposal link remains an evidence-owned index under
`components/role-approvals/evidence-approval`; it is not part of the generic
role artifact.

The bundle-owned read-only JSON API is served below:

```text
/api/v1/plugins/com.bloxbean.cardano.yano.appchain.evidence-profile/
```

It exposes `organizations/{id}`, `actors/{id}`, `policies/{id}`,
`proposals/{id}`, `evidence/{evidence_id}/versions/{version}/approval`, and
`stats`. Successful record responses include the exact proof key and encoded
record value needed for MPF verification.

Capacity is consensus data. For gated capacity `C`, the release workflow
reserves `2C`, notification reserves `C`, and the evidence component reserves
`C` effect slots. Startup rejects a profile whose reserved quotas exceed the
chain consensus limits. The effective canonical profile is authenticated under
`~composite/profile/v1`; proof clients must pin its reviewed digest.

## Build

```bash
./gradlew :appchain-evidence-profile:check
./gradlew -q :appchain-evidence-profile:roleEvidenceProfileDigest \
  --args="--chain evidence-chain --members <member1>,<member2>,<member3> \
  --threshold 2 --storage-gate app-final --continuation explicit \
  --evidence-capacity 8"
```
