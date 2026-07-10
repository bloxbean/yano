# App-chain script anchor — Aiken implementation (opt-in)

The opt-in twin of the bundled julc/Java anchor validators
(ADR app-layer/008.4; ABI: `core-api/src/main/cddl/appchain/anchor-v1.cddl`).
Same datum/redeemer ABI, same rules — both implementations pass the SAME
conformance vectors (`appchain-anchor-onchain` test suite runs both artifacts
on julc-vm-java).

## Using it

Point the anchor config at the checked-in artifacts (script hash and address
always derive from the configured artifact, never from source):

```yaml
yano:
  app-chain:
    anchor:
      mode: script
      script:
        validator: file:/path/to/artifacts/anchor-validator.plutus.json
        thread-policy: file:/path/to/artifacts/thread-policy.plutus.json
```

## Building

Requires the [Aiken](https://aiken-lang.org) toolchain (compiled with
v1.1.21, stdlib v2.2.0). The checked-in artifacts are what ships — rebuild
only when the source changes, and re-run the conformance suite:

```bash
aiken build                       # regenerates plutus.json
# re-extract artifacts/ (double-CBOR cborHex envelopes) — see git history
# then: ./gradlew :appchain-anchor-onchain:test   (runs vectors on BOTH artifacts)
```

`artifacts/*.plutus.json` wrap the blueprint `compiledCode` (single-CBOR flat
UPLC) in one more CBOR byte-string layer, matching the Cardano text-envelope
`cborHex` convention the runtime artifact loader expects.

## Sizes (informational)

| script | Aiken | julc |
|---|---|---|
| anchor validator (spend) | 1191 B | 1988 B |
| thread policy (mint) | 382 B | 748 B |
