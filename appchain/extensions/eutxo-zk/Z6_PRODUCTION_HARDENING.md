# Z6 production hardening and release gates

Z6 completes the automatable hardening for the restricted EUTxO validity
profile. It does not approve production funds. Independent audits, an actual
multi-party ceremony, external reconstruction, operational L1 retention and
an accountable release decision are evidence supplied outside the build.

## What is enforced in code

- `EutxoZkReleaseManifest` creates one canonical digest over the exact
  profile, ZeroJ `0.1.0-pre10`, Julc `0.1.0-pre14`, circuit, proving key,
  verification key, validators and other named release artifacts.
- `EutxoCeremonyManifest` inventories every proving-key file. A backend loads
  a bundle only after its file digests, profile, circuit and verification-key
  identity match. Development manifests are permanently distinguishable from
  production manifests.
- A production manifest must describe a multi-party contribution with at
  least two participants. This structural check does not prove that the
  ceremony was independent or honestly conducted; that requires external
  transcript evidence.
- The deterministic fuzz corpus exercises 128 bounded settlement witnesses,
  R1CS satisfaction, canonical data round trips and mutation non-aliasing.
- `EutxoProverFailoverCoordinator` fails over only to healthy, in-capacity
  provers serving the pinned verification key, and verifies a returned proof
  before accepting it.
- `EutxoZkBudgetAssessment` compares measured CPU, memory and transaction size
  with an explicitly pinned Cardano protocol-parameter snapshot and safety
  margin. It never treats a repository default as the latest mainnet value.
- `EutxoZkReadinessAssessment` fails closed. Self-certified evidence cannot
  satisfy independent audit, ceremony, reconstruction, retention or
  production-owner gates.

The catalog exposes `settlement:zeroj-validity` as an optional JVM-only,
development/testnet capability. It keeps `rollup:zeroj-cardano`
non-selectable.

## Development ceremony

Tests may create a deterministic single-development-setup bundle through
ZeroJ:

```java
Path keys = Path.of("build/dev-ceremony");
EutxoCeremonyManifest manifest;

try (var backend =
         ZerojEutxoProofBackend.singleParticipantDevelopmentSetup(keys)) {
    manifest = EutxoCeremonyManifest.development(
            "local-dev", keys, backend.verificationKey());
}

try (var backend =
         ZerojEutxoProofBackend.loadCeremonyBundle(keys, manifest)) {
    // Development/testnet proving only.
}
```

Never relabel this output as a production ceremony.

<a id="external-gates"></a>

## External gates

The following remain open until real evidence is recorded:

- independent circuit and conservation/root-transition review;
- independent ZeroJ/witness/cryptographic-integration review;
- independent Julc validator and public-input binding review;
- independent bridge and custody review;
- production MPC ceremony and independently verified transcript;
- externally operated reference reconstruction;
- operational L1 batch-data retention and disaster-recovery exercise;
- parameter-pinned mainnet dry run without material custody; and
- separate accountable production-funds approval.

Critical and high findings must be closed or explicitly accepted by their
accountable owners. General Plutus, arbitrary recipients, multi-assets and
larger batches each require a new profile and one-at-a-time review.
