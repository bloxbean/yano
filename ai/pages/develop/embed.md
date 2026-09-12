# Embed Yano in Java

Use public node roles and the runtime assembly API in your application.

Canonical URL: https://getyano.dev/develop/embed/

Yano is also a library. `YanoAssembly` composes the runtime and returns a `Yano` handle. The public role interfaces live under `org.yanoproject.api`; callers do not need raw runtime nodes or RocksDB handles.

For devnet mutation controls, add `yano-devnet-toolkit` and use `YanoDevnetAssembly`:

```java
import org.yanoproject.api.DevnetControl;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.devnet.YanoDevnetAssembly;
import org.yanoproject.runtime.assembly.Yano;

YanoConfig config = YanoConfig.devnetDefault(0);
try (Yano yano = YanoDevnetAssembly.devnet(config).build()) {
    yano.start();
    DevnetControl devnet = yano.devnetControl().orElseThrow();
    devnet.advanceTimeBySlots(10);
    devnet.createDevnetSnapshot("after-ten-slots");
}
```

Use `YanoAssembly.relay(config)` for a relay recipe. `YanoAssembly.fromConfig(config)` selects a recipe from configuration; explicit recipes make the intended role easier to review.

The devnet toolkit supplies rollback, snapshot, faucet, and time controls. Normal relay recipes do not expose `DevnetControl`. Always close the node handle to release storage and network resources.

## Dependency coordinates

Choose an actually published version with the `org.yanoproject` namespace, or publish this checkout locally. The current source version is in the [build manifest](/ai/manifest.json).

```groovy
repositories {
    mavenCentral()
    mavenLocal() // if you built and published this checkout locally
}

dependencies {
    implementation "org.yanoproject:yano-runtime:${yanoVersion}"
    implementation "org.yanoproject:yano-devnet-toolkit:${yanoVersion}"
}
```

Set `yanoVersion` explicitly and use matching versions across Yano modules. For tests, prefer the managed [Java testkit](/develop/java-testkit/) over assembling and cleaning up every resource yourself.
