# Yano Testkit

`testkit` provides JVM integration-test helpers for running Yano devnets from
JUnit and plain Java tests. It builds on `devnet-toolkit` and keeps tests on the
same public role APIs used by embedders.

## What It Provides

- `YanoDevnetTestKit`: managed in-process devnet fixture.
- `YanoDevnetExtension`: JUnit 5 extension with parameter injection.
- `YanoDevnetTestConfig`: test-focused configuration builder.
- Helper facades for common workflows:
  - `YanoQueries`
  - `YanoAwait`
  - `YanoWallets`
  - `YanoFaucet`
  - `YanoSnapshots`
  - `YanoTime`
  - `YanoTransactions`
  - `YanoAssertions`
  - `YanoWalletAssertions`
- External-process helpers for heavier compatibility tests:
  - `YanoAppProcess`
  - `HaskellCardanoNodeProcess`
  - `YanoGenesisFiles`
  - `YanoExternalSyncAssertions`
  - `YanoAdaPotComparator`

## Storage And Lifecycle

The default test configuration uses real RocksDB-backed chain storage in a
test-owned temporary directory. The directory is removed when the fixture closes.
This keeps test behavior close to production storage while still making each
test isolated.

Supported storage modes:

- `temporaryRocksDbStorage()`: default, real RocksDB, test-owned cleanup.
- `persistentRocksDbStorage(path)`: real RocksDB at a caller-owned path.

Testkit devnet intentionally does not expose the runtime's in-memory storage
mode. RocksDB is required for the same ledger-state, epoch-parameter tracking,
snapshot, and restore behavior used by the regular devnet profile.

Always close `YanoDevnetTestKit` directly or let `YanoDevnetExtension` do it for
the test.

## JUnit Usage

```java
import com.bloxbean.cardano.yano.testkit.devnet.YanoDevnetExtension;
import com.bloxbean.cardano.yano.testkit.devnet.YanoDevnetTestKit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class MyDevnetTest {
    @RegisterExtension
    static YanoDevnetExtension yano = YanoDevnetExtension.devnet()
            .startNode()
            .blockTimeMillis(200)
            .epochLength(120);

    @Test
    void fundsWallet(YanoDevnetTestKit kit) {
        var wallet = kit.wallets().newWallet();

        kit.faucet().fund(wallet.address(), 10_000_000L);
        kit.time().advanceSlots(1);

        kit.assertions().nodeIsRunning()
                .wallet(wallet)
                .hasAtLeast(10_000_000L);
    }
}
```

## Plain Java Usage

```java
import com.bloxbean.cardano.yano.testkit.devnet.YanoDevnetTestConfig;
import com.bloxbean.cardano.yano.testkit.devnet.YanoDevnetTestKit;

try (YanoDevnetTestConfig config = YanoDevnetTestConfig.builder()
             .temporaryRocksDbStorage()
             .blockTimeMillis(200)
             .build();
     YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(config)) {

    kit.start();
    var snapshot = kit.snapshots().create("initial");
    kit.time().advanceSlots(10);
    kit.snapshots().restore(snapshot.name());
}
```

## External Process Helpers

The `external` package is for slower compatibility tests that need process
boundaries:

- `YanoAppProcess` starts the Quarkus app jar and talks to its HTTP API.
- `HaskellCardanoNodeProcess` starts a Haskell `cardano-node` against a Yano
  devnet n2n port.
- `YanoGenesisFiles` copies or adapts genesis files for app/Haskell process
  tests.

These helpers are opt-in. Normal JUnit integration tests should prefer the
in-process `YanoDevnetTestKit`.

## Boundaries

The testkit does not expose `RuntimeNode`, raw `ChainState`, RocksDB handles, or
maintenance gates. Tests interact through the public `Yano` role interfaces and
focused helper facades.

Use `testkit-ccl` when a test needs a Cardano Client Lib `BackendService`.
