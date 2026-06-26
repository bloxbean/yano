# Yano Testkit JavaScript Smoke Test

Minimal JavaScript example for testing the published
`@bloxbean/yano-testkit` npm package. The example starts a native Yano devnet,
queries the production HTTP API, and then stops the native process.

## Requirements

- Node.js 20.8 or newer.
- A supported platform package published to npm:
  - Linux x64
  - Linux arm64
  - macOS arm64
  - Windows x64

Unsupported platforms can still run the example by setting
`YANO_TESTKIT_BINARY` to a locally built Yano native binary.

## Run

```bash
cd test-examples/yano-testkit-js
npm install
npm test
```

The example depends on `@bloxbean/yano-testkit@preview`, so it installs the
current preview/pre-release package and the matching optional native binary
package for the local OS/CPU.

## Use An Exact Version

To test a specific release:

```bash
npm install --save-dev @bloxbean/yano-testkit@0.1.0-pre7
npm test
```

## Use A Local Binary

```bash
YANO_TESTKIT_BINARY=/path/to/yano/app/build/yano npm test
```

On Windows:

```powershell
$env:YANO_TESTKIT_BINARY="C:\path\to\yano\app\build\yano.exe"
npm test
```

## Fund A Test Address

The npm wrapper exposes Yano devnet's faucet endpoint as `fundAddress(address,
ada)`. Provide an address through the environment to exercise it in this smoke
test:

```bash
YANO_TEST_ADDRESS=addr_test1... YANO_TEST_ADA=1000 npm test
```

On Windows:

```powershell
$env:YANO_TEST_ADDRESS="addr_test1..."
$env:YANO_TEST_ADA="1000"
npm test
```

The amount is in ADA. The response contains `tx_hash`, `index`, and `lovelace`.

The testkit does not publish a default JavaScript wallet or pre-funded account.
Create or load a normal test wallet in your application, pass its address to
`fundAddress()`, and then build/sign transactions with your app's Cardano
library.

## What It Checks

- Starts Yano native devnet with temporary RocksDB storage.
- Reads `/q/health/ready`.
- Reads `/api/v1/node/tip`.
- Optionally funds `YANO_TEST_ADDRESS` through `yano.fundAddress()`.
- Waits briefly and reads the tip again.
- Stops Yano in a `finally` block.
