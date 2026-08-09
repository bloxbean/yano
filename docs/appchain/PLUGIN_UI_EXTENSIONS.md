# Plugin UI extensions

Yano plugin bundles can contribute an optional, sandboxed console experience without compiling
product code into the main console. The backend contribution and its static frontend assets live
in the same bundle jar.

## Security boundary

The runtime accepts `ui-extension` only from a manifested, selected bundle. On every runtime start
it validates the immutable descriptor, asset count and sizes, normalized paths, declared media
types, and SHA-256 digest of every byte. The HTTP namespace contains the bundle ID and the digest
of the complete asset inventory, so a stale URL stops resolving when the bundle is removed or
changed.

The console mounts the entrypoint in an iframe with `sandbox="allow-scripts"`. The frame has an
opaque origin, no parent DOM access, no forms, no popups, and a CSP that denies network access.
The only host interface is UI API v1's `postMessage` bridge. Each request is checked against:

- the exact frame window and opaque origin;
- a random session nonce, selected chain, API version, and request identifier;
- the contribution's declared closed permission set;
- the selected chain's actual capability manifest; and
- request/response size, concurrency, and timeout limits.

V1 exposes bounded read operations for status, domain queries, application queries, proofs, and
anchors. It does not expose raw network fetch, administration, message submission, credentials,
keys, seeds, browser storage, or cross-plugin calls.

## Bundle contract

Implement `UiExtensionProvider` and register it in both the bundle manifest and
`META-INF/services/com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionProvider`. Assets must be
relative paths under the provider's packaged UI root and must appear exactly once in the v1 asset
manifest. Current limits are 256 assets, 4 MiB per asset, and 16 MiB for the complete extension.

The host publishes active entries at `GET /api/v1/ui-plugins/catalog`. Validated content is served
from `/api/v1/ui-plugins/{bundleId}/{assetsDigest}/{path}`. Applications should use the catalog URL
and must not construct or retain asset URLs independently.
