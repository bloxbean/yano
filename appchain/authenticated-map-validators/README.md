# Authenticated-map validator bundle

This module contains trusted first-party validator implementations for the
authenticated-map state machine. The public SPI is deliberately not defined
here: applications implement
`com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidatorFactory`
from `core-api`, while genesis descriptors remain in
`appchain-stdlib-contracts`.

The bundle currently contributes `gs1-gtin-v1`. It binds an ASCII GTIN-8,
GTIN-12, GTIN-13, or GTIN-14 application key to the same identifier encoded as
one canonical CBOR text value and checks the GS1 Mod-10 digit. It accepts only
the canonical empty parameter map.

The bundle is manifest-required, declares plugin API level 2, and must be
explicitly allow-listed. A chain may activate it only with the exact
`ARTIFACT_CLOSURE` SHA-256 digest pinned in its authenticated-map genesis.
Validator code executes in process and is trusted consensus code, not sandboxed
third-party code.

Run its unit and determinism gates with:

```bash
./gradlew :appchain-authenticated-map-validators:test \
  :appchain-devtools:test --tests '*AuthenticatedMapValidatorConformanceTest'
```
