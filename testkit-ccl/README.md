# Yano Testkit CCL

`testkit-ccl` adapts `YanoDevnetTestKit` to Cardano Client Lib's
`BackendService` API. It is useful for Java tests where application code already
depends on CCL abstractions and should run against an in-process Yano devnet.

This module is intentionally separate from `testkit` so the base testkit does
not pull in `cardano-client-backend`.

## What It Provides

- `YanoBackendService.from(kit)`: creates a CCL `BackendService` backed by a
  `YanoDevnetTestKit`.

Implemented service areas include the pieces needed for common devnet
transaction workflows:

- `UtxoService`
- `TransactionService`
- `EpochService`
- `BlockService`

Unsupported CCL services fail loudly instead of returning misleading empty
results.

## Basic Usage

```java
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.yano.testkit.ccl.YanoBackendService;
import com.bloxbean.cardano.yano.testkit.devnet.YanoDevnetExtension;
import com.bloxbean.cardano.yano.testkit.devnet.YanoDevnetTestKit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CclIntegrationTest {
    @RegisterExtension
    static YanoDevnetExtension yano = YanoDevnetExtension.devnet().startNode();

    @Test
    void usesCclBackendService(YanoDevnetTestKit kit) throws Exception {
        BackendService backendService = YanoBackendService.from(kit);

        var tip = backendService.getBlockService().getLatestBlock().getValue();
        var params = backendService.getEpochService().getProtocolParameters().getValue();

        // Pass backendService to application code that expects CCL.
    }
}
```

## Embedded Only

`YanoBackendService` is embedded and in-process. It reads and writes through the
public Yano testkit roles. It does not start a Yano app process and it does not
use HTTP.

For tests that need the production HTTP surface, use `testkit`'s
`YanoAppProcess` and a normal HTTP-backed CCL backend such as `BFBackendService`.

## Protocol Parameters

Protocol parameters are mapped from Yano's runtime snapshots when available. The
fallback JSON mapper supports both Cardano node-style camel-case protocol params
and Blockfrost-compatible snake-case protocol params.

## Boundaries

This adapter does not expose runtime internals and does not attempt to implement
the full CCL backend surface. Add support only when a real Yano devnet workflow
needs it, and prefer failing loudly for unsupported methods.
