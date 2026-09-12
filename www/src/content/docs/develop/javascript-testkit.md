---
title: "JavaScript & TypeScript testkit"
description: "Manage a native devnet process from JavaScript tests."
sidebar:
  order: 3
---

`@bloxbean/yano-testkit` starts a local native Yano devnet, waits for HTTP readiness, and returns connection details and helpers. Its npm scope is unchanged by the Java namespace rename.

```bash
npm install --save-dev @bloxbean/yano-testkit
```

Use Node.js 20.8 or later for the testkit. Platform packages cover Linux x64/arm64, macOS arm64, and Windows x64. A matching published platform package or a locally built native binary is required.

## Own the lifecycle

```js
import { startYanoDevnet } from '@bloxbean/yano-testkit';

const yano = await startYanoDevnet();
try {
  const response = await fetch(new URL('node/tip', yano.apiBaseUrl));
  if (!response.ok) throw new Error(`Tip query failed: ${response.status}`);
  console.log(await response.json());
} finally {
  await yano.stop();
}
```

## Vitest

```js
import { describe, expect, test } from 'vitest';
import { yanoDevnet } from '@bloxbean/yano-testkit/vitest';

const yano = yanoDevnet();

describe('Cardano integration', () => {
  test('reads the chain tip', async () => {
    const response = await fetch(new URL('node/tip', yano.apiBaseUrl));
    expect(response.ok).toBe(true);
  });
});
```

The helper starts the process in `beforeAll` and stops it in `afterAll`.

## Downloaded binary and storage

```js
const yano = await startYanoDevnet({
  binaryPath: '/absolute/path/to/yano-native-0.1.0-pre12-macos-arm64/yano',
  cwd: '/absolute/path/to/yano-native-0.1.0-pre12-macos-arm64',
  blockTimeMillis: 200,
  httpPort: 0,
  n2nPort: 0,
});
```

The example uses the downloaded macOS arm64 archive; substitute your own platform directory and use `yano.exe` on Windows. Match the testkit version to the binary; newer helpers can require newer endpoints.

Port `0` allocates an available port. The default temporary RocksDB mode isolates each run; persistent mode is available when a test needs to retain state. Set `YANO_TESTKIT_BINARY` to override binary discovery.

The fixture can fund and query addresses, but wallet creation and signing stay in your application. See [the complete JavaScript guide](/reference/javascript/) for faucet units, snapshots, time controls, and transaction helpers.
