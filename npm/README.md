# Yano npm Testkit Local Testing

This folder contains the JavaScript wrapper for starting a local Yano native
devnet from Node.js tests.

## Package Layout

- `yano-testkit` - main package, published as `@bloxbean/yano-testkit`.
- `yano-testkit-linux-x64` - Linux x64 native binary package.
- `yano-testkit-linux-arm64` - Linux arm64 native binary package.
- `yano-testkit-macos-arm64` - macOS arm64 native binary package.
- `yano-testkit-windows-x64` - Windows x64 native binary package.

For a normal published install, users install only the main package:

```bash
npm install --save-dev @bloxbean/yano-testkit
```

The main package declares the platform packages as optional dependencies. npm
installs the package that matches the current OS/architecture, and the wrapper
resolves the native Yano binary from that package automatically. Users do not
need `YANO_TESTKIT_BINARY` for normal npm installs.

During local development, when installing from this repository path or a local
tarball before platform packages are published, use `binaryPath` or
`YANO_TESTKIT_BINARY` to point at a locally built native binary.

## 1. Run Wrapper Unit Tests

These tests use a fake Yano process, so they do not require a native Yano build.

```bash
cd npm
npm run install:testkit
npm test
npm run typecheck
npm run pack:dry
```

`npm run install:testkit` intentionally installs only the main package
development dependencies and omits optional platform packages. This keeps local
testing portable across macOS, Linux, and Windows.

## 2. Keep Versions In Sync

The npm packages should normally use the same version as `gradle.properties`.
Release CI does this automatically before publishing, so no manual version edit
is needed for a normal release.

For local testing after the Gradle version changes, sync the npm package
manifests and lockfile once:

```bash
cd /path/to/yano
node npm/scripts/set-package-version.mjs 0.1.0-pre7
npm install --prefix npm/yano-testkit --package-lock-only --omit=optional
```

Replace `0.1.0-pre7` with the version from `gradle.properties`. Then verify:

```bash
cd npm
npm run check:versions
```

The temporary `0.0.0-oidc-bootstrap.0` version is only for one-time npm trusted
publisher bootstrap publishing. Do not leave source files at that version.

## 3. Build A Local Native Yano Binary

Use GraalVM 25 with `native-image` available.

```bash
native-image --version
java --version
```

If SDKMAN points `JAVA_HOME` at a non-GraalVM JDK, set it explicitly before the
native build:

```bash
export JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-graal
```

Build the native binary and native distribution from the repository root. If
you are still in the `npm` directory from the previous step, run `cd ..` first.

```bash
./gradlew :app:quarkusBuild :app:yanoNativeDistZip \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true
```

Expected outputs on macOS arm64:

```text
app/build/yano
app/build/distributions/yano-native-<version>-macos-arm64.zip
```

On Windows the binary is expected at:

```text
app/build/yano.exe
```

## 4. Smoke Test The Wrapper Against The Real Binary

From the repository root:

```bash
node --input-type=module -e '
  import { startYanoDevnet } from "./npm/yano-testkit/src/index.js";

  const yano = await startYanoDevnet({
    binaryPath: "app/build/yano",
    cwd: "app",
    timeoutMs: 60000,
    blockTimeMillis: 200
  });

  try {
    const response = await fetch(new URL("node/tip", yano.apiBaseUrl));
    console.log(JSON.stringify({
      type: yano.type,
      apiBaseUrl: yano.apiBaseUrl,
      n2nPort: yano.n2nPort,
      storage: yano.storage.mode,
      tipStatus: response.status
    }, null, 2));
  } finally {
    await yano.stop();
  }
'
```

For plain Node.js tests, always call `await yano.stop()` in `finally`. Runner
helpers such as `@bloxbean/yano-testkit/vitest` stop the process automatically
from the runner lifecycle.

Expected result:

```json
{
  "type": "ready",
  "apiBaseUrl": "http://127.0.0.1:59642/api/v1/",
  "n2nPort": 59643,
  "storage": "temp-rocksdb",
  "tipStatus": 200
}
```

For Windows, use `binaryPath: "app/build/yano.exe"`.

## 5. Test From Another JavaScript Project

Create or open a separate JavaScript test project, then point the wrapper at the
local binary. This is only needed for local repository/tarball testing, because
the matching platform binary package is not being installed from npm registry in
this flow:

```bash
npm install --save-dev /path/to/yano/npm/yano-testkit
YANO_TESTKIT_BINARY=/path/to/yano/app/build/yano node my-test.mjs
```

PowerShell:

```powershell
npm install --save-dev C:\path\to\yano\npm\yano-testkit
$env:YANO_TESTKIT_BINARY="C:\path\to\yano\app\build\yano.exe"
node my-test.mjs
```

Example `my-test.mjs`:

```js
import { startYanoDevnet } from "@bloxbean/yano-testkit";

const yano = await startYanoDevnet({
  cwd: "/path/to/yano/app"
});

try {
  const response = await fetch(new URL("node/tip", yano.apiBaseUrl));
  console.log(await response.json());
} finally {
  await yano.stop();
}
```

## 6. Test Local Package Tarballs

There are two kinds of tarballs:

- the main wrapper package, `@bloxbean/yano-testkit`, which is small and does
  not contain a native binary;
- one platform package, for example
  `@bloxbean/yano-testkit-macos-arm64`, which contains the native Yano binary
  and config.

Before packing a platform package locally, populate it from the native build:

```bash
cd npm
npm run populate:local-platform
```

That copies `app/build/yano` or `app/build/yano.exe` plus `app/config` into the
detected platform package. The copied `bin/` and `config/` folders are ignored
by git because release CI normally creates them.

Dry-run package creation:

```bash
npm run pack:dry
```

To create real tarballs for local install testing:

```bash
npm pack ./yano-testkit
npm pack ./yano-testkit-macos-arm64
```

Replace `yano-testkit-macos-arm64` with the package printed by
`npm run populate:local-platform` on Linux or Windows.

Install both tarballs in another project:

```bash
npm install --save-dev \
  /path/to/yano/npm/bloxbean-yano-testkit-macos-arm64-*.tgz \
  /path/to/yano/npm/bloxbean-yano-testkit-*.tgz
```

In this tarball flow, `YANO_TESTKIT_BINARY` is not needed as long as the matching
platform package tarball is installed too.

## Notes

- The default storage mode is real RocksDB in a test-owned temporary directory.
- The wrapper uses Yano's production HTTP API under `/api/v1`.
- The wrapper does not expose JVM testkit classes or runtime internals.
- Platform packages are populated from native distribution zips in release CI.
