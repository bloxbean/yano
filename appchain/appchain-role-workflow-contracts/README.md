# Role-workflow v1 contracts

This artifact contains no plugin service. It freezes the portable ADR-019 wire,
state, signing, and client contracts used by the domain-actor registry and
role-aware approval workflow.

The important identity boundary is:

```text
outer AppMessage.sender      = consortium member that relayed the command
SignedActorCommandV1.actorId = business actor that authorized the exact bytes
```

`ActorStatementV1` binds the chain, action, proposal, policy revision, payload
domain/hash, deadline height, actor revision, key, and clause. `PROPOSE`,
`APPROVE`, `REJECT`, and `CANCEL` therefore cannot be substituted or replayed
into another chain, proposal, policy, payload, or clause.

The published JAR includes:

- `META-INF/yano/contracts/role-workflow/v1/role-workflow-v1.cddl`
- frozen Java/Python-verified golden vectors;
- bounded canonical codecs and stable result codes;
- Java records, encoders, signers, and verifiers; and
- `RoleWorkflowCli` for offline actor signing.

V1 accepts only preferred definite CBOR. Set-like arrays must already be in
their frozen order (roles, key IDs, clause IDs, accepted decisions, and
governance approvals); decoders reject unsorted bytes instead of silently
normalizing them. Runtime verification uses a captured strict JDK Ed25519
provider plus a concrete CCL verifier, never the mutable global CCL provider.
Malformed points, signatures, and provider failures return `false`; they do
not escape deterministic application.

Use a private, owner-readable seed file rather than placing an actor seed on
the command line:

```bash
chmod 600 manufacturer.seed

java -cp yano.jar \
  com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowCli \
  public-key --seed-file manufacturer.seed

java -cp yano.jar \
  com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowCli \
  sign \
  --action propose \
  --chain evidence-chain \
  --proposal evidence-2026-001 \
  --policy evidence-release \
  --policy-revision 1 \
  --payload-domain evidence.release.v1 \
  --payload-hash <32-byte-hex> \
  --deadline-height 1000 \
  --actor manufacturer-a \
  --actor-revision 1 \
  --key signing-1 \
  --seed-file manufacturer.seed
```

The CLI prints canonical command hex only. A normal member submission client
places those bytes on `role-approvals.command.v1`; the member relay still signs
the outer app-message envelope.

For a rotation, first obtain the new public key and create its exact
proof-of-possession:

```bash
java -cp yano.jar \
  com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowCli \
  key-proof \
  --chain evidence-chain \
  --actor auditor-a \
  --actor-revision 2 \
  --key auditor-key-v2 \
  --public-key <32-byte-public-key-hex> \
  --valid-from-height 500 \
  --valid-until-height 0 \
  --seed-file auditor-v2.seed
```

Applications construct `RegistryMutationV1` or `PolicyMutationV1` with the
typed Java contracts, then use the CLI to wrap the resulting mutation hex in
the three threshold-governance envelopes:

```bash
java -cp yano.jar \
  com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowCli \
  govern-propose \
  --mutation-id actor-a-v2 --mutation-hex <mutation-hex> --expiry-height 900

java -cp yano.jar \
  com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowCli \
  govern-approve \
  --mutation-id actor-a-v2 --mutation-hash <32-byte-mutation-hash>

java -cp yano.jar \
  com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowCli \
  govern-activate \
  --mutation-id actor-a-v2 --mutation-hash <32-byte-mutation-hash>
```

The outer member sender determines which administrator vote is counted.
Actor seed files are read locally by the signing process only. Use a
KMS/HSM/Vault implementation of the same `ActorStatementV1.signingPreimage()`
contract in production; never send a seed file to a Yano REST endpoint.
