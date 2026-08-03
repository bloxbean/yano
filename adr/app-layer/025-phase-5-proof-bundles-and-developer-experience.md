# ADR-025 Phase 5 — proof bundles and developer experience

Status: implemented and qualified for `mpf-blake2b256-v1` and
`jmt-blake2b256-v1` against the pinned CCL `0.8.0-pre5-dev1` development
baseline. This is not production dependency approval. The Phase 4 Poseidon
profile remains fail-closed.

## 1. Profile-aware Java verification

`appchain-client` strictly decodes the version-1 REST proof envelope and binds:

- chain id, genesis id, exact profile, finalized height, and state root;
- the release-matched backend, dependency descriptor, native proof encoding,
  backend flags, and normalized format fingerprint;
- canonical key, `PRESENT` / `ABSENT` / `TOMBSTONED` classification, value,
  and native proof bytes; and
- the canonical finalized block header, block hash, and finality certificate.

Verification dispatch is exact rather than byte-probing. MPF uses the pinned
CCL MPF proof wire verifier. Classic JMT uses the pinned CCL classic
Blake2b-256 JMT proof codec for inclusion, both native non-inclusion forms, and
tombstone inclusion. Cross-profile bytes and mismatched metadata fail closed.
The declared `jmt-poseidon-bls12381-v1` profile has no active verifier until
Phase 4 pins the released ZeroJ host contract.

`ProofVerifier.verify(proof, TrustedStateRoot)` requires a caller-supplied
chain/profile/genesis/height/root tuple. `verifyCertified` instead recomputes
the signed block hash, checks distinct Ed25519 signatures against a
caller-pinned membership set and threshold, binds the commitment identity, and
then verifies the native proof. The one-argument compatibility method is
explicitly an internal-consistency check and makes no authenticity claim.

## 2. REST and operator surfaces

The chain-scoped API exposes:

```text
GET  /state/identity
GET  /state/entry/{canonical-key}[?height=N]
GET  /state/proof/{canonical-key}[?height=N]
GET  /state/oldest-provable
GET  /state/integrity                 privileged
POST /snapshot                       privileged
```

The existing `/proof/{key}` path remains compatible and now returns the
profile-tagged envelope where the gateway supports ADR-025. Single-chain
aliases remain available. Proof construction verifies that its snapshot root,
block hash, and certificate equal the retained finalized block before emitting
bytes. Snapshot responses resolve the captured height's finalized block root,
not a potentially newer live tip.

Chain discovery, tip, status, snapshot, proof, integrity, retention, and anchor
views use one normalized commitment representation: schema, exact profile,
backend, dependency descriptor, proof encoding, flags, format fingerprint,
genesis id, legacy mode, version, root, and oldest provable height. Anchor
views label node-reported L1 provenance and warn that the referenced Cardano
transaction/payload still requires independent verification.

## 3. CLI and trust acquisition

`./yano.sh appchain state` provides current or retained-height `entry` and
`proof`, offline `verify`, `identity`, privileged `integrity`, `snapshot`, and
`oldest` commands. Offline verification requires every trust input explicitly:

```text
--trusted-root --profile --genesis-id --chain --height --root-source
```

The proof file is strictly bounded and decoded without network access. The CLI
never copies its trusted root from the proof envelope. Supported root-source
labels are a locally verified block, a certificate checked under independently
pinned membership, an independently verified Cardano anchor, or an explicit
caller pin.

An anchor returned by the same REST node is discovery evidence only. A client
must independently fetch/verify the Cardano transaction and exact anchor
metadata or datum before treating that root as trusted. Likewise, asking one
untrusted node for both a proof and its root demonstrates only that the node's
claims are internally consistent.

## 4. Evidence and observability

New runtime evidence bundles carry the exact commitment identity plus the
version/root of their final signed block. The canonical JSON codec checks the
closed profile catalog, derived descriptors/fingerprint/flags, and final-block
binding. Older bundles without this optional field remain decodable; a trust
context that pins profile/genesis rejects such an unbound legacy bundle.

Micrometer exports bounded-cardinality commitment identity, version, and
oldest-provable gauges. The current 256-bit root is represented losslessly as
eight fixed big-endian unsigned 32-bit gauges; it is never placed in a changing
metric label. The console shows exact profile/version/retention/format/genesis
data and does not auto-select a proof-served root as trusted.

## 5. Qualification

Automated coverage includes:

- classic-JMT inclusion, non-inclusion, and tombstone dispatch;
- cross-profile, wrong-root, wrong-height, wrong-genesis, and metadata
  substitution rejection;
- certificate verification under pinned membership and threshold;
- strict/bounded online and pasted proof decoding;
- REST proof/block/certificate and authorization binding;
- evidence codec round-trip, identity pinning, and runtime bundle population;
- bounded metrics and lossless root-word representation;
- CLI offline verification that refuses an omitted or substituted trusted
  root; and
- console proof parsing, trust warnings, TypeScript/Svelte diagnostics, and
  production frontend compilation.

Primary verification commands:

```bash
./gradlew :core-api:test :appchain-stdlib:test :runtime:test \
  :appchain-client:test :appchain-devtools:test :app:test
cd console-ui/frontend && npm test -- --run && npm run check
```

Phase 5 does not implement ZeroJ proof generation/verification, whole-block
validity proofs, or multiproofs. Those remain Phase 4 and Phase 6 work.
