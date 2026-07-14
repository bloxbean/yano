# ADR-011.2 plugin conformance fixture

This build-only bundle covers the `NodePlugin` lifecycle and all six typed
app-chain plugin SPIs in both runtime modes without peers or external work.

- The JVM test opens the fixture JAR through `PluginRuntimeEnvironment` and
  constructs every product through the catalog-owned provider registry.
- The packaged JVM and native smoke tasks auto-start an isolated one-member
  app chain configured with all six selectors. They require running structured
  status for the fixture signer, state machine, sequencer, observer, finalized
  sink, and effect executor. This exercises the same assembly and lifecycle
  path as an operator-configured chain.
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
to the six structured app-chain assertions, the smoke requires exactly one
node-plugin lifecycle marker:

```text
ADR-011.2 node-plugin conformance activated through catalog
```
