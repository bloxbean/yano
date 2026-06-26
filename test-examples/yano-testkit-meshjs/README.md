# Yano Testkit MeshJS Transfer Test

MeshJS example for JavaScript integration tests. It starts a native Yano devnet
through `@bloxbean/yano-testkit`, creates a deterministic MeshJS wallet, funds
that wallet through the Yano faucet, builds and submits a signed self-transfer,
and verifies the result through both Yano testkit helpers and MeshJS.

## Requirements

- Node.js 20.8 or newer.
- A Yano native binary.

From this repository, build the native binary first:

```bash
export JAVA_HOME=/path/to/graalvm
./gradlew :app:quarkusBuild \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true
```

The local build creates `app/build/yano`. The example auto-detects that path
when run from this checkout and uses `app` as the working directory. If
`YANO_TESTKIT_BINARY` points at an `app/build/yano` binary, the example also
infers `app` as the working directory. For release ZIP layouts, the testkit
wrapper's normal working-directory inference is used.

## Run

```bash
cd test-examples/yano-testkit-meshjs
npm install
npm test
```

The example depends on the workspace copy of `@bloxbean/yano-testkit`:

```json
"@bloxbean/yano-testkit": "file:../../npm/yano-testkit"
```

To test a published package instead:

```bash
npm install --save-dev @bloxbean/yano-testkit@preview
npm test
```

## Configuration

```bash
YANO_TESTKIT_BINARY=/path/to/yano npm test
YANO_TESTKIT_CWD=/path/to/yano-distribution-root npm test
YANO_TESTKIT_VERBOSE=true npm test
YANO_FUNDING_ADA=20 npm test
YANO_TRANSFER_ADA=2 npm test
```

Use `TEST_MNEMONIC` to override the deterministic MeshJS wallet mnemonic. The
default mnemonic is not expected to rely on genesis funds; the example funds it
through the Yano faucet before building a transaction.

## What It Checks

- Starts Yano native devnet with temporary RocksDB storage.
- Creates a MeshJS wallet and derives a testnet address.
- Funds the MeshJS address through `yano.faucet.fundAddress`.
- Asserts funded balance through `yano.assertions.address(...)`.
- Reads protocol parameters through MeshJS `BlockfrostProvider`.
- Builds, signs, and submits a MeshJS self-transfer.
- Waits for the submitted transaction through `yano.await.untilTxVisible`.
- Verifies the submitted transaction through Yano query helpers and MeshJS.
- Stops Yano in a `finally` block.
