# ADR-011.2 plugin conformance fixture

This build-only bundle covers the `NodePlugin` lifecycle and all nine typed
app-chain plugin SPIs in both runtime modes without peers or external work.

Its schema-v1 manifest declares the initial global plugin API `minLevel` of
`1`. The JVM verifier requires the runtime catalog to publish the current API
major and level before constructing providers; the broader ADR-011.2 tests
also prove that an out-of-range major or a host level below `minLevel` fails
before construction and yields the same result in offline inspection.

- The JVM test opens the fixture JAR through `PluginRuntimeEnvironment` and
  constructs every product through the catalog-owned provider registry.
- The packaged JVM and native smoke tasks auto-start an isolated one-member
  app chain configured with every applicable selector. They require running structured
  status for the fixture signer, state machine, sequencer, observer, finalized
  sink, effect executor, domain API, health source, and metrics source. This
  exercises the same assembly, sampling, and lifecycle path as an
  operator-configured chain.
- Every fixture factory poisons its provider-callback TCCL before returning.
  The first callback on each returned product poisons it again, and every
  later callback rejects a missing TCCL. Catalog facades restore the caller
  and reinstall the plugin loader at both boundaries; a raw returned product
  leaks the first poison and fails its next callback. The smoke therefore
  proves product mediation, not merely factory mediation.
- `NativePluginConformanceVerifier` verifies only its own catalog-managed
  `NodePlugin` lifecycle. It never resolves another SPI and no fixture-specific
  registry backdoor is exposed.

The fixture is absent from stock artifacts. Include it only in a disposable
verification build:

```bash
./gradlew :app:quarkusBuild \
  -PincludeNativePluginConformanceFixture=true \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true

./gradlew :app:nativePluginCatalogSmoke \
  -PincludeNativePluginConformanceFixture=true \
  -PskipSigning=true
```

The app's explicit build-time bundle mapping also includes the fixture's
compile-only `core-api` dependency in `ARTIFACT_CLOSURE` evidence. This keeps
the native reachability fixture subject to the same complete executable-input
accounting as supported build-time plugins.

The build and smoke invocations must use the same property value. In addition
to the structured app-chain and query/domain API assertions, the smoke requires exactly one
node-plugin lifecycle marker:

```text
ADR-011.2 node-plugin conformance activated through catalog
```

For multi-node operations tests, the same fixture JAR can expose node-local
health outcomes with `-Dyano.plugin.conformance.health-mode=UP|DEGRADED|DOWN|HANG`.
The default is `UP`. `HANG` deliberately ignores interruption while sampling;
use it only in a disposable process and terminate that process forcibly after
the timeout behavior is verified. Selector text never becomes a metric label,
provider identity, or public check identity.
