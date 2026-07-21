# App-chain script anchor — Aiken implementation (opt-in)

The opt-in twin of the bundled julc/Java anchor validators
(ADR app-layer/008.4; ABI: `core-api/src/main/cddl/appchain/anchor-v1.cddl`).
Same datum/redeemer ABI, same rules — both implementations pass the SAME
conformance vectors (`appchain-anchor-onchain` test suite runs both artifacts
on julc-vm-java).

Both the consumed and continuing datum must have the exact
`Constr(0, [7 fields])` v1 shape. The validators enforce version `1`, a
`chain-id` of 1..128 bytes, a non-negative height, 32-byte block hash and state
root, 1..32 strictly sorted unique 32-byte member keys, and a threshold in the
member range. Plutus exposes `chain-id` only as bytes, so the on-chain scripts
cannot prove that it is UTF-8 or reject an embedded NUL. The public/off-chain
`AnchorDatumV1` codec enforces canonical UTF-8 and rejects NUL before encoding
or after decoding.

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
| anchor validator (spend) | 1572 B | 3318 B |
| thread policy (mint) | 362 B | 1303 B |

Values are the checked artifact JSON `sizeBytes` fields. They are identity and
deployment diagnostics, not execution-budget measurements; the shared
conformance suite separately checks both implementations against Cardano's
per-transaction CPU and memory limits.
