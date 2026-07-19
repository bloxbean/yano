# Tutorial 9 — From Local Demo to a Permissioned Pilot

[Open a pilot starting point in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=audit-log&network=preprod&members=3&finality=all&sequencing=rotating&runtime=jvm&deployment=host&name=permissioned-pilot&chainId=permissioned-pilot)

- **Level:** platform, security, and application leads
- **Outcome:** turn a successful tutorial into an explicit deployment plan
  without inheriting local-demo assumptions.

This is a decision checklist rather than one launch command. Yano remains
pre-release; the target posture is a controlled permissioned pilot, not an
unqualified production or trustless-public-chain claim.

## 1. Freeze the application contract

Record and review:

- chain id and network;
- member public keys, threshold, proposer/sequencer mode;
- state-machine or composite profile id and digest;
- deterministic limits and activation schedule;
- command/state/effect contract versions;
- anchor mode, validator identity, cadence, and stability depth; and
- effect outcome trust policy.

All consensus-affecting values must be identical and profile-committed where
required. Node-local YAML drift must not decide application semantics.

## 2. Separate every identity and secret

| Material | Purpose | Recommended owner/storage |
|---|---|---|
| Member signing key | App-block votes and envelopes | One per member, KMS/HSM/secret manager |
| Business actor key | Domain authorization | Actor organization or delegated signing service |
| API key | REST authorization/scopes | API gateway/secret manager |
| Anchor wallet key | Cardano fees/collateral | Capped hot wallet on anchor leader |
| Connector credential | Kafka/S3/IPFS/API access | Executor host only |
| TLS key/trust roots | Transport identity | Platform PKI |

Never copy the deterministic demo keys, launcher API key, sample actor seeds,
or local connector credentials into a shared environment.

## 3. Choose the trust statement

Document what the system actually proves:

- threshold members finalized exact application state;
- actor signatures authorized exact statements under a governed policy;
- an MPF proof connects a record to a state root;
- an L1 anchor connects a certified descendant to Cardano; and
- an effect result is a member/executor attestation unless independently
  verified.

Do not claim that an inspection, oracle value, shipment, API response, or
payment outcome is independently true unless a separate auditor/source check
establishes it.

## 4. Replace demo infrastructure deliberately

| Demo component | Pilot decision |
|---|---|
| RustFS | AWS S3 or reviewed compatible service; versioning/retention policy |
| Single Kubo | Managed/redundant IPFS pinning and retrieval policy |
| Local Kafka | TLS/mTLS/SASL cluster, ACLs, consumer deduplication |
| Local webhook | Named allow-listed target, authentication, idempotent receiver |
| Devnet anchor wallet | Dedicated funded preview/preprod key with spend cap |
| Docker-local secrets | KMS/HSM/Vault or orchestrator secret mounts |

Changing an executor destination does not change deterministic effect intent,
but it does change operational identity, reconciliation, and credentials.

## 5. Establish operations before traffic

- Health/readiness for every member and plugin.
- Root/profile parity gate across members.
- Metrics and alerts for app lag, finality, pool pressure, anchor lag, effect
  backlog/age/retries/parking, sink lag, and disk pressure.
- Snapshot, restore, member onboarding, and retained-state identity runbooks.
- Member-key and actor-key rotation/revocation exercises.
- Connector outage and executor crash recovery.
- Anchor-leader failure/recovery.
- Evidence/proof archival before configured pruning horizons.
- Explicit maintenance and governed-upgrade process.

## 6. Run acceptance in layers

1. Clean deterministic unit/conformance suites.
2. Three-member packaged local cluster.
3. Restart one member and prove catch-up/root parity.
4. Stop/restart the full deployment from retained state.
5. Connector fault matrix and duplicate-boundary tests.
6. Load/soak test with recorded topology, rate, payloads, duration, lag,
   resources, and failures.
7. Preview/preprod anchor smoke with independent L1 verification.
8. Restore rehearsal from the retained backup procedure.

Keep acceptance artifacts with the release rather than relying on screenshots
or a remembered manual session.

## 7. Know the current escalation gates

- Material Cardano funds require the production action hardening tracked for
  `cardano.payment` and native assets.
- Semi-trusted members/executors require governed result-signer policy and
  independent outcome auditing before receipts are marketed as independently
  verified.
- Regulated personal data requires encryption/erasure guidance; immutable
  object/IPFS/on-chain digests are not a deletion mechanism.
- Public validator participation and general BFT view change are outside the
  current permissioned model.

## 8. Choose the deployment shape

- **JVM distribution:** supports plugin-directory installation and is the
  simplest extensible pilot shape.
- **Native image:** plugins must be included at build time; validate each
  advertised runtime/platform and plugin contribution.
- **Embedded library:** appropriate when an application team owns lifecycle,
  configuration, APIs, and dependency integration in one Java service.

## Go deeper

- [Full user/operations guide](../../APP_CHAIN_USER_GUIDE.md)
- [Profile-governance runbook](../../APP_CHAIN_PROFILE_GOVERNANCE.md)
- [Canonical app-layer open items](../../../adr/app-layer/open_item.md)
- [Evidence flow and trust limits](../../EVIDENCE_CHAIN_DEMO.md)
- [Domain-role production signing and recovery](../../APP_CHAIN_DOMAIN_ROLES.md)
