# Plugin Operations Guide

Yano exposes a host-owned, read-only view of the selected plugin catalog and
node-local plugin lifecycle, health, and metrics. The HTTP adapters read a
bounded runtime cache; they do not invoke plugin callbacks while serving a
request or metrics scrape.

## Protect the operations API

The plugin operations endpoints are privileged even though they are `GET`
requests. These examples use the default artifact prefix `/api/v1`:

```text
GET /api/v1/plugin-operations
GET /api/v1/plugin-operations/bundles?after=<bundle-id>&limit=<1..100>
GET /api/v1/plugin-operations/bundles/<bundle-id>
```

Configure at least one unscoped full key. Prefer environment variables or an
external secret provider instead of
putting production keys in a committed configuration file:

```bash
export YANO_APP_CHAIN_API_KEYS='replace-with-a-long-random-full-key'
```

The equivalent Java properties are:

```properties
yano.app-chain.api.keys=replace-with-a-long-random-full-key
```

The key list may also contain topic-scoped submit keys in the form
`key=topic-a|topic-b`. Such keys receive `403` from plugin operations; only an
unscoped full key is accepted.

```bash
curl -H 'X-API-Key: replace-with-a-long-random-full-key' \
  http://127.0.0.1:7070/api/v1/plugin-operations
```

The surface fails closed: a missing full-key configuration returns `503`, a
missing or invalid request key returns `401`, a scoped key returns
`403`, and an unscoped full key returns `200`. API keys are resolved at runtime
for JVM/native parity, but the parsed key set is cached; restart the node after
rotating keys.

Set `YANO_APP_CHAIN_API_AUTH_ENABLED=true` too only when READ and SUBMIT routes
must require keys; privileged plugin operations require a full key in either mode.

## Dashboard

Open `/ui/plugins/`, enter an unscoped full key, and use **Forget key** when
finished. The input is cleared after submit; the key is retained only in
JavaScript memory or session storage and is never put in the URL or rendered
back into the page. Before enabling credential entry, it discovers the
artifact-baked API prefix through the immutable same-origin document
`/ui/plugins/api-prefix.json`. It fails closed when that document is missing or
invalid. A stored key is bound to the exact verified API prefix; URL query
parameters cannot steer its destination.

Reverse proxies must serve `/ui/plugins/api-prefix.json` from the same packaged
Yano artifact and must not synthesize it from a request header, query parameter,
or other client-controlled value. The canonical root prefix `/` is supported
and maps the operations API to `/plugin-operations`.

The prefix is fixed when the artifact is built. Its only supported input is
`-PyanoApiPrefix=<path>` (default `/api/v1`); do not put a public prefix in
launch configuration. The path is limited to 256 characters and must be `/` or
a canonical absolute path of unescaped `[A-Za-z0-9._~-]+` segments, with no
empty, `.` or `..` segment or trailing slash. A custom artifact is built, for
example, with:

```bash
./gradlew :app:yanoDistZip -PyanoApiPrefix=/desired/path \
  -PskipSigning=true
```

The build generates literal REST configuration, the raw
`META-INF/yano-api-prefix-v1` marker, and the immutable dashboard discovery
document from that one input. It reserves `quarkus.http.root-path=/`.
Runtime-style build inputs for `yano.api-prefix`, `quarkus.resteasy.path`, or
`quarkus.http.root-path` are rejected. Launch-time drift in any of those values
aborts before node or plugin initialization.

The dashboard loads at most 500 bundles, in pages of 100, so one refresh uses
at most five inventory requests. It displays an explicit cap indicator at that
boundary. The REST API remains cursor-paginated across the full catalog (up to
4,096 bundles) for automation that needs complete inventory.

The dashboard is an operator view, not an authenticated ledger proof. A
contribution marked `CATALOG VALID · LIFECYCLE NOT OBSERVED` was accepted by
the selected catalog but its callback lifecycle has not been observed by the
operations registry. Compare `observedContributionCount` with
`contributionCount` before treating the runtime view as complete.

The summary and paginated inventory remain bounded and omit individual health
checks. Expanding one bundle fetches its privileged detail response, including
the activation-frozen check ids/descriptions and cached status. `UNKNOWN` means
that no valid result has been observed; `stale` retains the last-good status
after a timeout, callback failure, or invalid whole-source snapshot.

The protected summary also publishes `pluginApiMajor`, the globally monotonic
`pluginApiLevel`, and the selected catalog fingerprint. Compare all three when
checking a multi-node rollout; a matching fingerprint already commits to the
host major/level and each selected manifest's minimum required level.

## Health and metrics

Plugin operator health is available at:

```text
/q/health/group/plugins
```

It is deliberately separate from node liveness and readiness. An optional
plugin can therefore be degraded or down without forcing
`/q/health/ready` down. Alert on this health group independently.

Prometheus-format metrics are available from `/q/metrics`. Standard families
start with `yano.plugin`; custom plugin metrics are mapped into bounded
host-owned families with exactly `plugin=<bundle-id>` and
`metric=<descriptor-id>` tags rather than request or error text. Health-check
ids do not create Prometheus series. Counter and timer exports stay monotonic
across runtime generations.
Authoritatively absent series are unregistered; explicitly stale sources keep
their last-good value and stale status.

The app-chain API key does **not** protect `/q/health/group/plugins` or
`/q/metrics`. Restrict management endpoints with the deployment listener,
firewall, ingress, or authenticated reverse proxy. Do not expose them publicly
by relying on the plugin operations credential.

## Validate a plugin before deployment

The JVM distribution includes the offline, resource-only catalog inspector:

```bash
./tools/yano-plugins/bin/yano-plugins validate plugins/example.jar
./tools/yano-plugins/bin/yano-plugins inspect --format json plugins/example.jar
```

It validates manifests and service descriptors without loading providers. See
[`plugin-catalog/README.md`](../plugin-catalog/README.md) for policy flags and
stable exit codes. Native distributions intentionally omit this JVM tool and
cannot load dropped plugin JARs; validate artifacts on a JDK 25 host before
including trusted providers at native build time.

Each explicit JAR, or all regular files in one exploded artifact, is limited
to an aggregate 1 GiB immutable scan snapshot. Inputs over that boundary are
rejected before temporary capture and rechecked while streaming.

## Shutdown behavior

Yano bounds plugin callbacks and reports stale or failed sources from cached
state. Plugins are nevertheless trusted in-process Java code. A malicious or
broken callback can ignore interruption indefinitely; the runtime does not use
unsafe thread termination. If a provider prevents clean shutdown after the
configured grace period, terminate the node process and replace or disable the
bundle before restarting.
