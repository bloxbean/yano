# ADR-CONSENSUS-002 Phase 5 Implementation Report

Date: 2026-07-01

## Scope

This report covers Phase 5: Praos VRF Proof Validation.

Implemented:

- `praos-lite` header validation profile.
- `vrf-proof` validation stage after `structural`, `kes-signature`, and
  `opcert-signature`.
- Read-only epoch nonce provider wiring from runtime nonce tracking into header
  validation.
- Era-shape support for Babbage+ Praos single VRF cert and Shelley-through-Alonzo
  TPraos leader/nonce VRF certs.
- Config support for `yano.upstream.validation.level=praos-lite`.

Still not implemented:

- Leader threshold validation.
- Pool VRF-key registration and active stake checks.
- Op-cert counter/state validation.
- Full untrusted public-relay adoption based on Praos ledger view.

## Implementation Summary

`HeaderValidationPipeline` now exposes `praos-lite`, which installs:

```text
structural -> kes-signature -> opcert-signature -> vrf-proof
```

The VRF stage uses CCL crypto-ext:

- `CardanoVrfInput.mkInputVrf(slot, epochNonce)` for Praos/Babbage+ headers.
- `CardanoVrfInput.mkSeedLeader(slot, epochNonce)` and
  `CardanoVrfInput.mkSeedNonce(slot, epochNonce)` for TPraos headers.
- `CryptoExtConfiguration.INSTANCE.getVrfVerifier()` for proof verification.

`ShelleyHeaderView` now preserves both leader and nonce VRF certs. For Babbage+
headers the nonce VRF fields are empty because the header has one Praos VRF cert.

`RuntimeNode` passes a read-only nonce supplier into `SyncSubsystem`, and
`SyncSubsystem` passes it into `HeaderValidatorFactory`. If nonce tracking is
unavailable, `praos-lite` fails closed at `vrf-proof`; other profiles are not
affected.

## Verification

Focused tests passed:

```bash
./gradlew :runtime:test \
  --tests com.bloxbean.cardano.yano.runtime.sync.validation.ShelleyHeaderValidatorTest \
  --tests com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationPipelineTest \
  --tests com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest \
  --tests com.bloxbean.cardano.yano.runtime.HeaderSyncManagerTest \
  --tests com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest
```

App build passed:

```bash
./gradlew :app:quarkusBuild
```

## Real Preprod Smoke Test

Yano was started from `app/` with the freshly built jar and a validation-level
override:

```bash
java \
  -Dyano.upstream.validation.level=praos-lite \
  -Dquarkus.http.port=7073 \
  -Dyano.server.port=13339 \
  -Dyano.relay.advertised-port=13339 \
  -jar build/yano.jar
```

Observed status from `/api/v1/node/status`:

```text
running: true
syncing: true
statusMessage: Node is running (phase: STEADY_STATE) [gap: 0 blocks]
localTipSlot: 127222463
localTipBlockNumber: 4885744
remoteTipSlot: 127222463
remoteTipBlockNumber: 4885744
upstreamMode: p2p-relay
upstreamActivePeer: relay.preprod.staging.wingriders.com:3001
upstreamValidationLevel: praos-lite
upstreamValidationAcceptedHeaders: 69
upstreamValidationRejectedHeaders: 0
upstreamValidationLastRejectedStage: null
upstreamValidationLastRejectedReason: null
runtimeDegraded: false
maintenanceActive: false
```

Logs confirmed accepted Shelley+ header validation entries with:

```text
profile=praos-lite, stages=[structural, kes-signature, opcert-signature, vrf-proof]
```

The smoke process was stopped cleanly after validation.

## Review Notes

`praos-lite` is useful for rejecting headers with impossible VRF proofs. It is
not sufficient for untrusted public-relay chain adoption because it does not
prove the issuer is an active pool, that the VRF key is registered for that
pool, or that the leader threshold is satisfied. Those remain Phase 6 work.
