# Yano Devnet Toolkit

`devnet-toolkit` adds optional devnet controls on top of the runtime assembly
API. It is intended for tests, local development tools, and controlled devnet
workflows that need to mutate chain state in ways a production relay should not.

## What It Provides

- `YanoDevnetAssembly`: mirrors `YanoAssembly` and returns a `Yano` handle with
  an available `DevnetControl` role for devnet recipes.
- `DevnetToolkit`: the `DevnetControl` implementation backed by runtime-owned
  devnet SPI ports.

The module exposes operations for:

- rolling back a devnet to a slot or rollback target;
- creating, restoring, listing, and deleting devnet snapshots;
- funding addresses from the devnet faucet;
- advancing devnet time by slots or seconds;
- advancing until an absolute slot;
- shifting genesis and starting the producer for past-time-travel devnets;
- catching up to wall-clock time.

## Basic Usage

```java
import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.devnet.YanoDevnetAssembly;
import com.bloxbean.cardano.yano.runtime.assembly.Yano;

YanoConfig config = YanoConfig.devnetDefault(0);

try (Yano yano = YanoDevnetAssembly.devnet(config).build()) {
    yano.start();

    DevnetControl devnet = yano.devnetControl()
            .orElseThrow(() -> new IllegalStateException("Devnet controls unavailable"));

    devnet.fundAddress("addr_test...", 1_000_000L);
    devnet.advanceTimeBySlots(10);
    devnet.createDevnetSnapshot("after-funding");
}
```

## Assembly Rules

Use this module when the caller needs `DevnetControl`. Use
`runtime:YanoAssembly` directly for normal relay, slot-leader, or app
composition.

`YanoDevnetAssembly.fromConfig(config)` decorates only devnet-capable runtime
recipes. Relay and plain slot-leader recipes still build normally, but they do
not expose `DevnetControl`.

## Boundaries

This module intentionally does not expose raw runtime internals such as
`RuntimeNode`, `ChainState`, RocksDB handles, or maintenance gates. The public
boundary remains the `Yano` handle and role interfaces from `core-api`.

Devnet mutation operations are not production APIs. They are meant for isolated
devnets where tests or local tooling own the node lifecycle.
