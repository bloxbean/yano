# Tutorial 4 — Publish and Verify Immutable Evidence

[Open this outcome in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=evidence-publication&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=docker-compose&name=evidence-publication&chainId=evidence-publication)

- **Level:** beginner to advanced
- **Time:** about 30 minutes for the first build
- **Outcome:** publish one immutable document through a threshold-approved
  workflow, preserve it in S3-compatible storage and IPFS, notify Kafka, and
  verify the chain, connector, proof, and Cardano-anchor evidence.

This is Yano's most complete no-code vertical scenario. Docker Compose starts
three Yano members plus Kafka, RustFS (S3-compatible object storage), Kubo
IPFS, and the Evidence Explorer.

## 1. Start a fresh direct-continuation profile

```bash
cd app/appchain-effects-demo

./demo.sh up \
  --instance tutorial-evidence \
  --continuation direct
```

`direct` means the deterministic workflow emits the next result-driven
transition directly. `explicit` is the legacy compatibility mode where a
separate notify command advances that continuation. The choice is part of the
fresh chain's committed profile; pass the same option to every later command.

Expected local endpoints:

- Yano members: <http://127.0.0.1:7070/ui/app-chain/>, `:7071`, `:7072`
- Evidence Explorer: <http://127.0.0.1:7080/>

If startup fails, the launcher rolls back the partial deployment. Use the
reported container health/log command rather than repeatedly deleting random
directories; retained L1 and app-chain identities are deliberately checked.

## 2. Publish version 1

```bash
./demo.sh publish \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --sample-file samples/inspection-certificate.json
```

The runner waits for the dependency-ordered workflow:

```text
publish command
    ↓ finalized application state
prerequisites and approval
    ↓ authorized evidence release
object.put + ipfs.pin
    ↓ acknowledged connector results
kafka.publish
    ↓ final state/proof verification
Cardano devnet anchor
```

The source document itself is not put into consensus state. The chain commits
its business identity, version, hashes, workflow decisions, effect intents,
and acknowledged outcomes.

## 3. Verify without changing anything

```bash
./demo.sh verify \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --business-version 1
```

`verify` is read-only. It does not submit an app message, stage an object, pin
content, publish Kafka data, or force another anchor. It re-reads the immutable
object and IPFS content, checks their hashes, checks the Kafka acknowledgement,
and validates application finality, state/effect proofs, and anchor linkage.

Open the Evidence Explorer and select the record. The JSON preview is a
bounded presentation copy produced only after the runner re-downloads and
verifies the external bytes; it is not the browser's original submission.

## 4. Demonstrate immutability and versioning

Publishing different bytes under the same evidence ID and business version is
rejected as an external-state mismatch. To add a legitimate revision, create
the exact next immutable version:

```bash
./demo.sh republish \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --business-version 2 \
  --sample-file samples/inspection-certificate-product-a-v2.json

./demo.sh verify \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --business-version 1

./demo.sh verify \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --business-version 2
```

Both versions remain independently selectable and verifiable.

## 5. Demonstrate idempotent replay

```bash
./demo.sh replay \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --business-version 2 \
  --sample-file samples/inspection-certificate-product-a-v2.json
```

The replay envelope may finalize, but the accepted business record, effect
set, and logical external outcomes do not duplicate.

## 6. Optional bounded parallel workload

Start small and use a fresh prefix:

```bash
./demo.sh load \
  --instance tutorial-evidence \
  --continuation direct \
  --load-mode pipeline \
  --count 8 \
  --concurrency 8 \
  --max-in-flight 8 \
  --id-prefix tutorial-load \
  --sample-file samples/inspection-certificate.json

./demo.sh verify \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id tutorial-load-000008 \
  --business-version 1
```

This measures a full workflow with approvals, external actions, proofs, and
anchors—not raw HTTP admission TPS.

## 7. Stop and retain the scenario

```bash
./demo.sh status --instance tutorial-evidence --continuation direct
./demo.sh stop --instance tutorial-evidence --continuation direct
```

Run `up` with the same instance/profile to resume retained data. Use the
launcher's explicit `clean --scope ... --yes` workflow only when you intend to
retire that chain identity and have chosen a replacement instance.

## What is and is not proven

The scenario proves that the approved bytes and connector outcomes are bound
to threshold-finalized application state and an L1 anchor. It does not prove
that an inspection statement is factually true, that an actor had a legal
credential, or that every future copy of a referenced document remains
available. Those require domain onboarding, custody, retention, and possibly
independent real-world auditing.

## Go deeper

- Read the [plain-language evidence flow](../../EVIDENCE_CHAIN_DEMO.md).
- Compare lifecycle and pipeline scheduling.
- Stop one member and exercise catch-up using the isolated E2E gate.
- Replace RustFS with a tested S3-compatible production service while keeping
  the `object.put` contract unchanged.
- Continue with [domain-role authorization](05-domain-role-approvals.md).
