# ADR-028: Unified Console UI Module (Embedded and Standalone Node Console)

## Status

Proposed — version 4 (implementation-ready for UI-M1)

Version 4 incorporates the 2026-07-22 historical-data decision. Plain Yano
uses bounded browser history with no server dependency; Yano also ships an
optional, one-command Prometheus Docker bundle for persistent local history;
and production consoles can use an operator-managed Prometheus-compatible
query service. The revision adds the missing bounded L1 Micrometer contract,
an observability route/provider, safe lifecycle commands, and moves direct raw
`/q/metrics` parsing out of the browser design.

## Date

2026-07-22

## Related decisions

- [app-layer ADR-022](app-layer/022-out-of-box-appchain-capabilities-and-extensible-product-catalog.md)
  proposes the out-of-box capability catalog. Its review identified the existing
  generic app-chain console as an uncatalogued bundled capability and proposed
  extending it with generic effect, approval, and proof panels. This ADR owns
  the delivery vehicle for that console.
- [ADR-023](023-docker-images-and-compose-release.md) owns distribution
  packaging; the embedded UI ships inside the same artifacts.
- [app-layer ADR-DX-0001](app-layer/dx/0001-unified-appchain-onboarding-configuration-and-lifecycle.md)
  owns Studio, the static configuration wizard. Studio remains a separate,
  pre-deployment tool; this ADR covers the runtime console.
- [app-layer ADR-011.4](app-layer/011.4-plugin-operations-and-observability.md)
  owns the plugin operations surface consumed by the plugins page.

## 1. Problem

Yano ships three hand-written runtime UI pages under
`app/src/main/resources/META-INF/resources/ui/`, served by Quarkus static
resources at `/ui/…`:

| Page | Files | Size | Notes |
|---|---|---|---|
| `/ui/status/` | one `index.html` | 1,319 lines | L1 node console, fully inline CSS+JS |
| `/ui/app-chain/` | one `index.html` | 1,358 lines | generic app-chain console, fully inline CSS+JS |
| `/ui/plugins/` | `index.html` + `app.js` + `styles.css` | ~1,000 lines | plugin catalog/operations viewer |

Verified problems with the current shape:

- The two large pages duplicate their design system by hand. The app-chain
  page literally carries the comment "Same design tokens as /ui/status (keep
  in sync)" (`ui/app-chain/index.html:19`). Every visual change is made twice.
- Single-file inline HTML at this size is hostile to human maintenance,
  review, and contribution. There is no component reuse, no type checking,
  no lint, and no test surface.
- The planned growth is significant: the ADR-022 review calls for effect
  lifecycle panels, role-approval/proposal panels over the committed query
  surface, a portable-evidence and MPF-proof viewer, a verified-payload
  widget, and eventually capability-driven domain panels. Hand-growing
  1,400-line inline files in that direction is not viable.
- The pages are embedded-only. Operators cannot host the console separately
  and point it at a remote node: CORS is not configured anywhere in the repo,
  so any cross-origin deployment fails in the browser today.
- All chart history is held only in page-local JavaScript arrays. The status
  page initializes an empty `history` object, the app-chain page initializes
  an empty `hist` object, and both append one point per five-second REST poll.
  Reloading the page therefore discards every prior point. The Prometheus
  scrape endpoint does not itself solve this because it exposes one current
  sample set, not stored historical samples.

At the same time, the current approach has real strengths that must be
preserved: zero-configuration out-of-box serving in both JVM and native
images, no server-side rendering runtime, no framework CVE surface at
runtime, same-origin API calls with an existing `?api=` override hook, and
API-key support via `fetch` headers (including for SSE, which the app-chain
page consumes via `fetch` streaming rather than `EventSource` precisely so
the `X-API-Key` header can be attached).

## 2. Verified current state (facts the design relies on)

- **Static serving.** Pure Quarkus/Vert.x default static resources. Anything
  under `META-INF/resources/` on the classpath — including inside dependency
  jars — is served at `/`. No custom servlet or route exists for `/ui/`.
- **Native image.** The `quarkus-vertx-http` extension auto-registers all
  `META-INF/resources/` content as native-image resources at build time. The
  existing pages are served in native today with no entry in
  `resource-config.json`; a new bundle under the same root inherits this.
- **API prefix.** `/api/v1` is baked per artifact and guarded at startup
  (`ApiPrefixContract` fails boot if `quarkus.http.root-path` ≠ `/`). The UI
  can default to same-origin `/api/v1`; `status` and `app-chain` already
  support `?api=` overrides (`app-chain/index.html:646`,
  `status/index.html:837`).
- **Auth.** `AppChainApiKeyFilter` protects only `AppChainResource`,
  `PluginDomainResource`, and `PluginOperationsResource` via `X-API-Key`.
  Reads are public unless broad auth is enabled; privileged operations
  require an unscoped key once keys are configured. All other REST resources
  are unauthenticated.
- **CORS.** No `quarkus.http.cors` configuration and no CORS code exists
  anywhere. External hosting is currently impossible cross-origin.
- **Plugins CSP.** A path filter applies a strict same-origin CSP
  (`connect-src 'self'`, no-store, DENY framing) to `/ui/plugins/.*` only
  (`application.yml:14-24`). The plugins page additionally fails closed by
  reading the baked `api-prefix.json` and rejecting `?api=` overrides.
- **Build precedent (this repo).** `appchain-studio` already uses
  `com.github.node-gradle.node` 7.1.0 with Node 22.12.0 downloaded by Gradle.
  The `app` module already bundles generated resources by adding
  `build/generated/<x>` directories via `sourceSets.main.resources.srcDir`.
- **Current live-data behavior.** `/ui/status/` polls `/node/status`,
  `/node/peers`, and `/status`; `/ui/app-chain/` polls chain `status` and
  `blocks` and separately consumes the chain SSE stream. These typed JSON and
  SSE contracts already expose the authoritative values used by the cards.
- **Current Prometheus coverage.** The existing `/q/metrics` endpoint is
  supplied by `quarkus-micrometer-registry-prometheus`. Yano registers
  app-chain gauges for tip, pool, peers, anchor/sink lag, composite workflow,
  and effects; counters for finalized blocks/messages, drops, executions, and
  executor outcomes; and timers for block intervals and effect latency. It
  also exposes plugin-operation metrics. It does **not** currently project the
  L1 status-page peer, mempool, transaction-diffusion, UTXO-lag, or sync fields
  as Yano Micrometer meters; those remain available from the JSON status
  endpoints.
- **Scrape versus history.** `/q/metrics` is Prometheus/OpenMetrics exposition:
  one scrape contains current gauges and cumulative counter/timer values. A
  separate Prometheus-compatible server stores successive scrapes and exposes
  historical ranges through its query API. No time-series database is bundled
  in a Yano distribution.
- **Build precedent (yaci-dataprover).** The `ui` module there is the target
  pattern: a Gradle module wrapping `frontend/` (SvelteKit 2 + Svelte 5 +
  TypeScript + Tailwind + `@sveltejs/adapter-static`), a `buildFrontend`
  NpmTask (`vite build`) writing into `src/main/resources`, wired via
  `processResources.dependsOn buildFrontend`, packaged as a plain jar the
  application depends on. Dev mode uses a Vite proxy to the backend; the API
  client uses a relative `/api/v1` base.
- **Doc references.** Fifteen documentation references to `/ui/` URLs exist
  (user guide, tutorials, plugin operations, build distributions). Keeping
  the public URL paths stable avoids a doc migration.

## 3. Decision

### 3.1 One frontend module, one SPA, two run modes

Create a top-level Gradle module `console-ui` producing the jar
`yano-console-ui`:

```text
console-ui/
  build.gradle              # node-gradle wrapper, jar packaging
  frontend/
    package.json            # SvelteKit 2, Svelte 5, TypeScript, Tailwind 4
    svelte.config.js        # adapter-static, base '/ui'
    vite.config.ts          # @tailwindcss/vite; dev proxy: /api -> http://localhost:7070
    src/
      routes/               # +page.svelte per console page
        status/             # L1 node console        -> /ui/status/
        app-chain/          # app-chain console      -> /ui/app-chain/
        plugins/            # plugin catalog/ops     -> /ui/plugins/
        observability/      # historical/aggregate   -> /ui/observability/
      lib/
        api/                # typed API client (base-url aware, X-API-Key)
        components/         # shared cards, tables, charts, badges, dialogs
        telemetry/          # browser history + Prometheus JSON query provider
        theme/              # single source of design tokens (Tailwind 4 @theme)
```

Stack note: the frontend uses **Tailwind 4** — a deliberate deviation from
dataprover's Tailwind 3.4 for this new codebase. Tailwind 4 is CSS-first:
tokens are declared in CSS via `@theme` (replacing the current hand-kept
inline design-token blocks with one canonical file), configuration needs no
`tailwind.config.js`, and the `@tailwindcss/vite` plugin removes the
PostCSS/autoprefixer toolchain entirely. Its supported operator-browser floor
is therefore Tailwind 4's modern baseline (Chrome 111, Safari 16.4, and
Firefox 128 or newer), which is tested and documented rather than implied.

The same build output serves both modes:

- **Embedded (default, out of the box).** The build emits the static site
  into the jar under `META-INF/resources/ui/`. The `app` module adds
  `implementation project(':console-ui')` and the console is served at
  `/ui/` on the node port in JVM and native images with zero Java changes
  and zero new configuration.
- **Standalone.** The identical `vite build` output (also published as a zip
  by the module, like `studioZip`) can be hosted on any static server and
  pointed at a remote Yano base URL. Developer mode is `npm run dev` with
  the Vite proxy.

### 3.2 Static-only, prerendered, no server runtime

- `@sveltejs/adapter-static` with **every route prerendered**. The root layout
  exports `prerender = true` and `trailingSlash = 'always'` while retaining
  SvelteKit's normal SSR/prerender pass. Dynamic state (selected chain,
  pagination, filters) lives in **query parameters**, not path parameters —
  exactly as the current pages do (`?chain=`, `?api=`). Consequence: every
  deep link resolves to a real prerendered `index.html`; no SPA fallback
  rewrite, no Quarkus routing code, no hash-based URLs.
- No SSR, no Node.js at runtime, no Quinoa. The Quarkus app never runs
  JavaScript; it serves bytes from the classpath.
- URL paths are preserved: `/ui/status/`, `/ui/app-chain/`, `/ui/plugins/`,
  plus a new `/ui/` landing page that links the consoles. All fifteen
  existing doc references remain valid.

### 3.3 API base resolution and auth

The typed API client resolves its base URL in this order:

1. `?api=` query parameter (existing convention, kept);
2. persisted operator choice (localStorage), settable from a small
   connection panel in the UI;
3. the immutable same-origin `/ui/api-prefix.json` generated by the `app`
   build for embedded mode;
4. same-origin `/api/v1` fallback (primarily the standalone default before an
   operator chooses a remote node).

The shared discovery document extends the existing baked-prefix pattern used
by `/ui/plugins/api-prefix.json`; it prevents a Yano artifact built with
`-PyanoApiPrefix=<path>` from silently connecting its console to `/api/v1`.
Discovery is fetched without redirects and accepted only from the exact
same-origin path. The status and app-chain routes retain the intentional
operator override precedence above. The plugins route continues to use its
own stricter discovery document and ignores every override.

`X-API-Key` is entered in the connection panel, held in memory (opt-in
localStorage persistence with an explicit warning), and attached to all
`fetch` calls including SSE streams (the client keeps the current
`fetch`-streaming approach for SSE so the header can be sent — native
`EventSource` cannot carry headers).

**Node identity awareness.** The `/ui/` landing page and the persistent
shell header on every route display the connected node's identity — network
(protocol magic / network name), node version, and current tip — fetched
from the node surface for the resolved base URL. The current tip comes from
`GET /node/status`. `GET /node/config` already returns the safe
`protocolMagic`; UI-M1 extends that existing response with the configured
`network` and `version` (`quarkus.application.version`). This is an additive,
backward-compatible response change, not a new endpoint. The badge makes it
immediately visible which node a console tab is operating on, preventing
wrong-node actions when multiple consoles are open (e.g. a local devnet and
a remote preprod node side by side). When the resolved base URL or the
reported network changes between refreshes, the shell surfaces the change
prominently instead of silently continuing.

Exception: the **plugins route keeps its fail-closed posture**. It continues
to read the baked `api-prefix.json`, ignores `?api=`/stored overrides, and
remains covered by the existing strict CSP filter on `/ui/plugins/.*`. The
plugin operations surface is privileged; same-origin-only is a feature, not
a limitation.

### 3.4 Live data, chart continuity, and metrics

The console uses a small typed telemetry boundary rather than letting route
components fetch and aggregate ad hoc:

| Layer | Initial source | Responsibility |
|---|---|---|
| Authoritative snapshot | Existing JSON REST endpoints | Current node/chain state and card values |
| Event stream | Existing app-chain SSE endpoint | Finalized app messages and low-latency updates |
| Short history | Bounded `sessionStorage` store | Chart continuity across refresh in the same browser tab |
| Metrics exposition | Node `/q/metrics` | Stable scrape contract for Yano, app-chain, effect, and plugin meters |
| Durable history | Prometheus JSON API | Stored ranges, rates, and multi-node/multi-chain aggregation |

#### 3.4.1 REST and SSE remain authoritative

REST snapshots and SSE are the default out-of-box sources. A card must not
disappear or become stale merely because `/q/metrics` is disabled, hidden by
a reverse proxy, protected by different infrastructure credentials, or fails
to parse. Polling is single-flight (no overlapping `setInterval` requests),
uses an abort timeout and bounded retry/backoff, pauses high-frequency work
while the page is hidden, and resumes immediately when it becomes visible.

#### 3.4.2 Chart history survives refresh without a server dependency

Every sample written to a chart also enters a versioned, bounded history
store in `sessionStorage`, keyed by normalized API base, protocol magic, and
chain id where applicable. UI-M1 uses a one-hour/720-sample ceiling and a
512-KiB total console-history ceiling; older or malformed data is discarded.
No API keys, payloads, message bodies, plugin text, or other sensitive values
enter this store.

Samples use compact numeric tuples rather than objects with repeated field
names. Persistence is batched no more often than every 10–15 seconds and the
oldest tuples are evicted first. The hard serialized ceiling is 512 KiB; with
the parsed arrays and browser string representation, expected live memory is
below 1 MiB and the conservative budget is 2 MiB per open console tab. This
uses browser memory/storage only and adds no Yano JVM or native-image heap.

On reload the route validates and restores the matching samples before the
first network response, then appends live samples. A node/network/chain
identity change clears the incompatible series. Counter decreases, process
restarts, rollbacks, and long sample gaps create a visible discontinuity
rather than a misleading line or negative rate. Charts may downsample for
display, but cards always show the newest authoritative REST value.

This is deliberately short-lived continuity, not an embedded monitoring
database. Closing the tab may discard it, and the UI labels the visible time
window and data source honestly.

#### 3.4.3 Complete the bounded Yano metrics contract

`AppChainMetrics` and `PluginMetrics` already provide the app-chain, effect,
sink, executor, composite, and plugin-operation meters needed for historical
views. UI-M1 adds a host-owned `NodeMetrics` projection for the missing L1
series used by the current status charts:

- local/remote tip and non-negative sync-gap blocks;
- bounded peer connection/governor counts;
- mempool transaction/byte gauges and configured limits;
- transaction-diffusion cumulative outcomes and in-flight gauges; and
- UTXO last-applied height and non-negative lag blocks.

The projection memoizes one runtime/status snapshot per scrape, following the
existing `AppChainMetrics` pattern. Labels come only from fixed enums such as
direction, state, and outcome; no peer id, address, transaction id, chain
payload, exception text, or plugin-controlled string becomes a label. Current
state is a gauge, monotonic process-lifetime values are function counters,
and restart/counter reset behavior is tested. Metrics remain operational
telemetry, not consensus state, and are never written into a rollback-managed
ledger store.

A checked-in metric descriptor/query map and parity tests bind the exported
Micrometer names to the console's fixed queries. Adding an unknown meter does
not automatically create a panel. Plain mode does not make the browser parse
the raw exposition document; `/q/metrics` remains the scrape interface for
Prometheus and other monitoring systems.

#### 3.4.4 Three supported history levels

| Mode | Configuration | History behavior |
|---|---|---|
| Plain Yano | None | REST/SSE current values plus browser-collected, one-hour session history |
| Yano observability bundle | `./yano.sh observability start` | Persistent Prometheus history and aggregate views |
| Production | Operator query endpoint | Operator retention, access control, and multi-node history |

The same typed `PrometheusHistoryProvider` serves the second and third modes.
Existing status and app-chain charts transparently prefer a healthy durable
provider for their selected time range and fall back to matching browser
history without disappearing. A separate `/ui/observability/` route shows
source health, scrape targets, time-range selection, L1 aggregates, and
multi-node/multi-chain app-chain/effect history. When no durable provider is
configured, the route explains plain mode and the one-command upgrade instead
of showing an error or empty charts.

#### 3.4.5 Prometheus JSON query provider

The durable provider consumes only the stable JSON instant/range query API
(`GET /api/v1/query` and `GET /api/v1/query_range`). Queries are selected from
fixed console-owned templates and parameters are validated against known
node/chain identities; neither URL input, plugin metadata, nor metric labels
can inject arbitrary PromQL. Each request has a timeout and explicit bounds
(4 MiB response, 64 series, 4,096 points per series), and unknown fields are
ignored for compatible evolution.

The Yano app-chain `X-API-Key` is never sent to Prometheus. A production query
URL is configured explicitly in the connection panel and may be persisted;
any observability credential is held only in memory/session storage, is bound
to the exact normalized query origin, and is cleared when that origin
changes. An authenticated cross-origin deployment must configure its
Prometheus/reverse-proxy CORS and read-only access policy. The console never
enables or invokes Prometheus administrative, lifecycle, remote-write, or
delete APIs.

#### 3.4.6 Optional one-command observability bundle

Ship an `observability/` directory in every distribution that carries
`yano.sh`. It contains a Docker Compose file, a generated scrape-config
template, and no secrets. The launcher exposes a node-level command (not an
`appchain` subcommand, because it can observe both L1 and app-chain behavior):

```text
./yano.sh observability start [--target <node-origin>]... [--retention <duration>] [--retention-size <size>]
./yano.sh observability status
./yano.sh observability stop
./yano.sh observability clean --yes
```

`start` checks Docker/Compose, discovers the maintained local app-chain
cluster targets when available, otherwise defaults to the local node on port
7070, and lets explicit repeatable `--target` values replace discovery. It
renders only normalized HTTP(S) origins; user-info, non-root paths, queries,
and fragments are rejected. Host-loopback targets are translated to the
supported Docker host gateway, including the Linux `host-gateway` mapping.
The maintained bundle scrapes each target's `/q/metrics`; authenticated or
non-standard production scrape configurations remain operator-managed.

The Compose profile runs one Prometheus container—no Grafana—from a
release-pinned image digest, mounts the generated read-only configuration,
persists `/prometheus` in a labeled named volume, and defaults retention to
15 days and 2 GB. Retention arguments are strictly parsed and bounded to
1 hour–90 days and 256 MB–20 GB before they reach Compose. Administrative,
lifecycle, remote-write, and OTLP receivers remain disabled, and the query API
binds to loopback by default. Its generated CORS origin is restricted to the
selected local console origins rather than Prometheus's permissive default.

`start` prints the Prometheus URL and a ready-to-open Yano console URL carrying
the normalized query endpoint in `?metrics=`. The console validates that value
and associates it with the selected node connection; it does not silently
probe localhost or scan ports. The operator can later replace it in the
connection panel. The output also lists the selected targets and retention.

`stop` preserves the named volume. `clean --yes` removes only a state directory
with the Yano observability marker and the exact labeled volume owned by that
instance; it refuses unresolved, broad, unmarked, or foreign targets. Docker
absence produces an actionable diagnostic and never affects plain mode.

### 3.5 Server-side changes: node identity fields and opt-in CORS

Standalone hosting requires the node to emit CORS headers. Add opt-in
configuration (default **off**, preserving today's behavior):

```yaml
# default: disabled. Enable only when hosting the console UI externally.
quarkus:
  http:
    cors:
      enabled: false
      # origins: https://console.example.com     # exact origins, never '*' with auth
      # methods: GET,POST,OPTIONS
      # headers: Content-Type,X-API-Key
```

Documented rules: never `*` origins when API keys are in use; CORS applies
to SSE responses as well; enabling CORS does not weaken the API-key filter.
The only server-side changes in this ADR are the safe additive
`network`/`version` fields in `/node/config` (§3.3), the shared immutable API
prefix discovery asset, the bounded `NodeMetrics` projection (§3.4.3), and
this opt-in CORS configuration. No history database or Prometheus query proxy
runs inside Yano.

### 3.6 Migration and deletion

Port pages one at a time; a route ships only when it reaches feature parity,
then the corresponding legacy file is deleted in the same PR. No long-lived
duplicate consoles. Order: `status` (simplest, pure reads) → `app-chain`
(largest, SSE) → `plugins` (CSP + fail-closed specifics) → `observability`
(new route, no legacy page). The legacy pages remain untouched until their
replacement lands.

### 3.7 Capability-aware growth (the ADR-022 connection)

The SPA is the delivery vehicle for the generic panels identified in the
ADR-022 review, added after parity:

- effect lifecycle panel (`…/effects`, `…/effects/stats`, requeue/cancel for
  privileged keys);
- proposal/approval panel over the committed query surface
  (`POST …/query/{path}`) once the generic `role-approvals` provider lands
  (ADR-022 M2);
- portable evidence bundle and MPF proof viewer
  (`…/evidence/{messageIdHex}`, `…/proof/{keyHex}`) with in-browser SHA-256
  payload re-verification (ported concept from the evidence demo UI);
- panels render **conditionally on discovered capability**: driven by chain
  `status`/profile fields and the plugin catalog, never by hardcoded
  deployment assumptions. A chain without effects shows no effects panel.

Domain-specific pages (e.g. an evidence overlay) are explicitly **later and
optional**, gated on the ADR-022 capability/product-catalog model — ideally
driven by the data-only UI hints (display names, query paths, field labels)
proposed there for the component product catalog. No plugin-contributed
executable UI code in this ADR's scope.

### 3.8 Build and release integration

- `com.github.node-gradle.node` 7.1.0 with Node 22.12.0 and `download = true`
  — identical versions to `appchain-studio`, so one Node toolchain per repo.
- `npm ci` against a committed `package-lock.json` for reproducible builds;
  `buildFrontend` (NpmTask) writes only below
  `build/generated/console-ui/META-INF/resources/ui/`; that directory is added
  to the module's main resources, `processResources.dependsOn buildFrontend`,
  and `clean` removes it. Builds never write generated files into `src/`.
- Frontend checks wired into `check`: `svelte-check` (types) and `vitest`
  unit tests for the API client and view-model logic, mirroring the
  `testStudio` NodeTask precedent.
- `console-ui` is added to `nonLibraryModules` (not published to Maven
  Central); it is an internal artifact consumed by `app` and released inside
  the distributions plus as a standalone zip.
- Distribution assembly includes the maintained `observability/` Compose
  assets beside `yano.sh`; plain node start, build, and tests never require
  Docker. The Prometheus image is pinned by digest and recorded in the release
  dependency/SBOM inventory. ADR-023 owns the final archive and image wiring.
- UI-M4 adds `observability:prometheus` to ADR-022's release catalog as
  `FIRST_PARTY_OPTIONAL`, `preview`, `scope: distribution`, and
  `selectable: false`. It is an operator companion selected after deployment,
  never a chain capability, blueprint input, or app-chain lock entry.
- Native image needs **no changes** (§2, static resources auto-registration).
- CI note: the node-gradle `download = true` fetch needs network on first
  build; CI must cache `console-ui/.gradle/nodejs` and the npm cache the
  same way it should for `appchain-studio` today.

## 4. Non-goals

- No server-side rendering, no Node.js runtime dependency in any
  distribution, no Quinoa extension.
- No login/session system beyond the existing `X-API-Key` model; no secrets
  ever baked into the bundle or blueprints.
- No plugin-contributed executable UI code (data-only UI hints per ADR-022
  remain the extension path).
- No replacement of Studio: Studio stays a pre-deployment configuration
  wizard with its own packaging. Migrating Studio onto this module's
  toolchain and component library is an **accepted later direction**
  (decided in review), but explicitly out of this ADR's scope and
  milestones.
- No new Yano REST endpoint or query proxy; the UI consumes existing surfaces
  plus the additive fields on `/node/config`.
- No Prometheus server or time-series database embedded in the Yano process,
  jar, or native image. Prometheus exists only in the explicitly started
  optional Docker companion or in operator-managed infrastructure.
- No claim that a direct `/q/metrics` scrape contains historical samples; the
  browser never parses raw exposition as its UI data contract.
- No arbitrary PromQL, plugin-supplied metric queries, or Prometheus
  administrative/write operations. Production query credentials are never
  persisted to localStorage or sent to Yano.
- No relaxation of the plugins-page CSP or its fail-closed API prefix.

## 5. Alternatives considered

1. **Keep hand-written inline HTML.** Rejected: 2,700 lines already
   duplicated across two files with "keep in sync" comments; the ADR-022
   panel roadmap roughly doubles the UI surface; no types, no components,
   no tests.
2. **Quarkus Quinoa.** Rejected: couples the frontend build to the Quarkus
   augmentation lifecycle, complicates the standalone/zip output, and the
   repo already has a working node-gradle precedent. Plain Gradle wiring is
   simpler and identical to the proven dataprover setup.
3. **Standalone-only UI (no embedding).** Rejected: loses the
   zero-configuration out-of-box console, which is exactly the product gap
   ADR-022 exists to close.
4. **React/Next or Angular.** Rejected: heavier toolchains, SSR-oriented
   defaults; Svelte+Tailwind is the user-stated direction and the in-house
   precedent (dataprover), with the smallest static output.
5. **SPA fallback routing (single index.html + rewrite).** Rejected: needs a
   server-side rewrite rule in Quarkus and breaks "serve bytes from
   classpath with zero Java changes". Full prerendering with query-param
   state achieves deep links for free.
6. **Use `/q/metrics` as the only console data source.** Rejected: it is a
   text scrape rather than a typed UI contract, lacks current L1 projections,
   may be independently protected, and contains no stored history. It is a
   useful monitoring export, not the browser's availability dependency.
7. **Embed a Yano time-series database or metrics-query proxy.** Rejected: it
   would add storage, retention, query, security, and operational concerns to
   every node. Bounded browser continuity solves refreshes with no server
   dependency, while the optional Prometheus companion and production systems
   provide durable history outside the node.
8. **Silently probe a conventional localhost Prometheus port.** Rejected:
   hidden browser requests are surprising and do not work for remote or
   non-default deployments. The launcher prints an explicit preconfigured
   console URL, and the connection panel owns subsequent selection.

## 6. Delivery plan

### UI-M1 — Scaffold and status parity
Module, toolchain, `@theme` tokens, typed API client (base-url + key
handling), connection panel, `/ui/` landing page with the node
identity/network badge in the shared shell; additive `network`/`version`
fields in `/node/config`; typed telemetry boundary, single-flight polling,
bounded session chart history, and bounded `NodeMetrics`; port `/ui/status/`
to parity; delete legacy file; metric descriptor/parity tests; CI wiring
(`npm ci`, svelte-check, vitest, caching).

### UI-M2 — App-chain console parity
Port `/ui/app-chain/` (cards, charts, SSE stream via fetch-streaming,
recent blocks, message dialog, multi-chain selector via `?chain=`); extend the
bounded browser-history provider to chain/effect series; delete legacy file.

### UI-M3 — Plugins parity and standalone mode
Port `/ui/plugins/` preserving CSP + fail-closed prefix; standalone zip
artifact; opt-in CORS config + documented external-hosting guide; verify
JVM, native, Docker, and external hosting.

### UI-M4 — Durable observability (optional)
Add the fixed-query `PrometheusHistoryProvider`, `/ui/observability/` route,
provider health/fallback indicators, `?metrics=` handoff, connection-panel
configuration, and the `./yano.sh observability` Compose lifecycle. Verify the
same provider against the maintained bundle and a separately managed
Prometheus-compatible query endpoint.

### UI-M5 — Generic capability panels (post-parity, tracks ADR-022)
Effects panel; committed-query/proposal panel (after ADR-022 M2);
evidence-bundle + MPF proof viewer with client-side hash re-verification;
capability-conditional rendering.

## 7. Acceptance gates

- Embedded: `/ui/` works in JVM uber-jar **and** native image with no new
  configuration; total embedded asset size budget ≤ 1 MB gzipped.
- Routing: the build produces real `/ui/index.html`,
  `/ui/status/index.html`, `/ui/app-chain/index.html`, and
  `/ui/plugins/index.html` files with no fallback document or server rewrite;
  UI-M4 adds a real `/ui/observability/index.html`.
- Parity: each ported page reproduces the legacy page's data and actions
  before the legacy file is deleted; doc URLs remain valid.
- Standalone: the same build output served from a different origin works
  against a CORS-enabled node, including SSE and `X-API-Key` flows; a
  non-CORS node fails with a clear in-UI diagnostic, not a blank page.
- Security: no secrets in the bundle; plugins route unreachable with
  overridden API base; CSP filter still applies; CORS remains off by
  default and `*` origins are rejected in documentation and samples.
- API prefix: default and custom-prefix artifacts resolve their baked prefix
  through the immutable same-origin discovery asset; redirects, malformed
  discovery, and query-steered discovery fail safely. Standalone fallback and
  explicit operator overrides are tested separately.
- Identity: every route's shell shows the connected node's network and
  version; changing the resolved base URL visibly updates the badge, and a
  network change between refreshes is surfaced rather than ignored.
- Live updates: REST polling is single-flight and charts continue to append
  without a manual reload; hidden-page pause/resume, timeout, backoff, and SSE
  reconnect behavior are covered by deterministic tests.
- Chart continuity: a refresh in the same tab restores only the matching,
  bounded, non-sensitive history; identity changes, malformed stored data,
  counter resets, and gaps cannot produce a false continuous series. The
  serialized store never exceeds 512 KiB, its parsed-memory budget is 2 MiB
  per tab, and it adds no server heap allocation.
- Metrics contract: `/q/metrics` exports every declared bounded L1,
  app-chain, effect, and plugin metric used by fixed console queries. Metric
  descriptor/query parity tests fail on missing, renamed, or unbounded-label
  dependencies. The plain console remains fully usable when metrics are
  disabled or unreachable and never parses raw exposition.
- Durable provider: only fixed parameterized instant/range queries are sent;
  malformed URLs, query parameters, responses over 4 MiB, more than 64
  series, or more than 4,096 points per series fail safely. The Yano API key,
  plugin-provided strings, and arbitrary PromQL never reach the query service.
  Provider source, health, range, and fallback to session history are visible.
- Observability bundle: lifecycle contract tests cover Docker absence,
  explicit/discovered targets, Linux host-gateway translation, pinned image,
  read-only config, 15-day/2-GB defaults, loopback binding, restricted CORS,
  persistent stop/start, and marker/labeled-volume-only cleanup. Node startup
  and plain mode work without Docker.
- Production observability: the same provider works against an explicitly
  configured operator endpoint with read-only CORS access; credentials stay
  memory/session-bound to the exact origin and are cleared on origin change.
- Reproducibility: build is `npm ci`-locked; CI builds the frontend from a
  cold cache; native and JVM distributions carry byte-identical UI assets.
- Regression safety: the current source-string UI tests are replaced, not
  merely deleted, by component/view-model tests plus packaged Quarkus HTTP
  tests for stable routes, API-prefix handling, CSP/security headers, auth,
  SSE, and JVM/native resource inclusion.
- Browser support: automated browser smoke tests cover the documented modern
  browser baseline implied by Tailwind 4; unsupported browsers get a plain
  diagnostic rather than a permanently blank shell where feature detection
  can do so reliably.

## 8. Open questions

No blocking design questions remain for UI-M1 through UI-M4.

Resolved in the version 3 readiness review: build the zip task from UI-M1 so
the artifact is continuously testable, but publish the standalone zip as a
release artifact only after UI-M3 proves CORS, SSE, API-key, and external
hosting behavior.

Resolved in version 4: plain mode uses only REST/SSE plus bounded
`sessionStorage`; `/q/metrics` is a scrape contract, not a browser data
contract or history store. UI-M4 adds one optional Prometheus-only Docker
companion (no Grafana), persistent named-volume history, fixed JSON range
queries, and a separate observability route. The same provider accepts an
explicit operator-managed endpoint for production. The bundle binds locally,
defaults to 15 days/2 GB, preserves data on `stop`, and deletes it only via
`clean --yes`. The console never performs implicit localhost discovery.

Resolved in the 2026-07-22 review: module name is `console-ui`; Tailwind 4
(§3.1); the landing page and shell surface node identity/network (§3.3);
`appchain-studio` migration onto this toolchain and component library is an
accepted later follow-up, out of this ADR's scope (§4).
