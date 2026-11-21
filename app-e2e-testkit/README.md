# Yano Application E2E Testkit

Reusable JUnit 5 / Quarkus support for end-to-end tests against a Yano devnet through its REST API. A test
extends the base class and runs either inside a Quarkus test (an in-process devnet started with the bundled
profile) or against an external Yano process given by `yano.e2e.baseUrl`.

## Artifact

`org.yanoproject:yano-app-e2e-testkit`

## Key contents

Package `org.yanoproject.app.e2e`:

| Type | Purpose |
|---|---|
| `BaseE2ETest` | Abstract `@Tag("e2e")` base class. Sets up a cardano-client-lib Blockfrost backend and `QuickTxBuilder` against Yano's REST API (`<quarkus test url>/api/v1/`, or `yano.e2e.baseUrl`), and offers `getAccount`, `fundAddress` (devnet faucet), `waitForTransaction` and `checkIfUtxoAvailable`. Subclasses implement `getAccountBaseIndex()`. |
| `DevnetTestProfile` | `QuarkusTestProfile` for the in-process case: `devnet` profile, RocksDB storage in a temporary directory, the packaged devnet Shelley genesis, plugins disabled, port 23337. With `yano.e2e.baseUrl` set it uses the `test` profile and no overrides. `yano.e2e.devnetConfigDir` points it at another genesis directory. |

Resources: `META-INF/yano/testkit/devnet/shelley-genesis.json`, copied at build time from
`app/config/network/devnet/shelley-genesis.json`.

## Dependencies and boundaries

- `api`: the Quarkus platform BOM, `quarkus-junit`, JUnit Jupiter API, and cardano-client-lib
  (`cardano-client-lib`, `backend-blockfrost`, `quicktx`). No Yano module: tests reach the node through
  HTTP only.
- Used by the `app` project's tests; it must never reach an application's compile classpath.

## Build and test

```bash
./gradlew :app-e2e-testkit:build -PskipSigning=true
# the module has no tests of its own; its users are the app e2e suites:
./gradlew :app:e2eTest
./gradlew :app:e2eTest -Dyano.e2e.baseUrl=http://localhost:7070/api/v1/   # against a running node
```
