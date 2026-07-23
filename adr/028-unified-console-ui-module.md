# ADR-028: Unified Console UI Module (Embedded and Standalone Node Console)

## Status

Proposed — version 2

Version 2 incorporates the 2026-07-22 review decisions: module name
`console-ui`, Tailwind 4, node-identity display in the console shell, and
Studio toolchain convergence accepted as a later follow-up.

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
      lib/
        api/                # typed API client (base-url aware, X-API-Key)
        components/         # shared cards, tables, charts, badges, dialogs
        theme/              # single source of design tokens (Tailwind 4 @theme)
```

Stack note: the frontend uses **Tailwind 4** — a deliberate deviation from
dataprover's Tailwind 3.4 for this new codebase. Tailwind 4 is CSS-first:
tokens are declared in CSS via `@theme` (replacing the current hand-kept
inline design-token blocks with one canonical file), configuration needs no
`tailwind.config.js`, and the `@tailwindcss/vite` plugin removes the
PostCSS/autoprefixer toolchain entirely.

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

- `@sveltejs/adapter-static` with **every route prerendered**. Dynamic state
  (selected chain, pagination, filters) lives in **query parameters**, not
  path parameters — exactly as the current pages do (`?chain=`, `?api=`).
  Consequence: every deep link resolves to a real prerendered
  `index.html`; no SPA fallback rewrite, no Quarkus routing code, no
  hash-based URLs.
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
3. same-origin `/api/v1` default.

`X-API-Key` is entered in the connection panel, held in memory (opt-in
localStorage persistence with an explicit warning), and attached to all
`fetch` calls including SSE streams (the client keeps the current
`fetch`-streaming approach for SSE so the header can be sent — native
`EventSource` cannot carry headers).

**Node identity awareness.** The `/ui/` landing page and the persistent
shell header on every route display the connected node's identity — network
(protocol magic / network name), node version, and current tip — fetched
from the node status surface for the resolved base URL. The badge makes it
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

### 3.4 Server-side change for standalone mode: opt-in CORS

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
This is the only server-side change in the whole ADR.

### 3.5 Migration and deletion

Port pages one at a time; a route ships only when it reaches feature parity,
then the corresponding legacy file is deleted in the same PR. No long-lived
duplicate consoles. Order: `status` (simplest, pure reads) → `app-chain`
(largest, SSE) → `plugins` (CSP + fail-closed specifics). The legacy pages
remain untouched until their replacement lands.

### 3.6 Capability-aware growth (the ADR-022 connection)

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

### 3.7 Build and release integration

- `com.github.node-gradle.node` 7.1.0 with Node 22.12.0 and `download = true`
  — identical versions to `appchain-studio`, so one Node toolchain per repo.
- `npm ci` against a committed `package-lock.json` for reproducible builds;
  `buildFrontend` (NpmTask) → output into `src/main/resources/META-INF/resources/ui/`
  (git-ignored); `processResources.dependsOn buildFrontend`;
  `clean` removes the generated output.
- Frontend checks wired into `check`: `svelte-check` (types) and `vitest`
  unit tests for the API client and view-model logic, mirroring the
  `testStudio` NodeTask precedent.
- `console-ui` is added to `nonLibraryModules` (not published to Maven
  Central); it is an internal artifact consumed by `app` and released inside
  the distributions plus as a standalone zip.
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
- No new REST endpoints; the UI consumes existing surfaces only.
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

## 6. Delivery plan

### UI-M1 — Scaffold and status parity
Module, toolchain, `@theme` tokens, typed API client (base-url + key
handling), connection panel, `/ui/` landing page with the node
identity/network badge in the shared shell; port `/ui/status/` to parity;
delete legacy file; CI wiring (`npm ci`, svelte-check, vitest, caching).

### UI-M2 — App-chain console parity
Port `/ui/app-chain/` (cards, charts, SSE stream via fetch-streaming,
recent blocks, message dialog, multi-chain selector via `?chain=`); delete
legacy file.

### UI-M3 — Plugins parity and standalone mode
Port `/ui/plugins/` preserving CSP + fail-closed prefix; standalone zip
artifact; opt-in CORS config + documented external-hosting guide; verify
JVM, native, Docker, and external hosting.

### UI-M4 — Generic capability panels (post-parity, tracks ADR-022)
Effects panel; committed-query/proposal panel (after ADR-022 M2);
evidence-bundle + MPF proof viewer with client-side hash re-verification;
capability-conditional rendering.

## 7. Acceptance gates

- Embedded: `/ui/` works in JVM uber-jar **and** native image with no new
  configuration; total embedded asset size budget ≤ 1 MB gzipped.
- Parity: each ported page reproduces the legacy page's data and actions
  before the legacy file is deleted; doc URLs remain valid.
- Standalone: the same build output served from a different origin works
  against a CORS-enabled node, including SSE and `X-API-Key` flows; a
  non-CORS node fails with a clear in-UI diagnostic, not a blank page.
- Security: no secrets in the bundle; plugins route unreachable with
  overridden API base; CSP filter still applies; CORS remains off by
  default and `*` origins are rejected in documentation and samples.
- Identity: every route's shell shows the connected node's network and
  version; changing the resolved base URL visibly updates the badge, and a
  network change between refreshes is surfaced rather than ignored.
- Reproducibility: build is `npm ci`-locked; CI builds the frontend from a
  cold cache; native and JVM distributions carry byte-identical UI assets.

## 8. Open questions

1. Publish the standalone zip as a release artifact from day one, or only
   after UI-M3 proves the external-hosting flow?

Resolved in the 2026-07-22 review: module name is `console-ui`; Tailwind 4
(§3.1); the landing page and shell surface node identity/network (§3.3);
`appchain-studio` migration onto this toolchain and component library is an
accepted later follow-up, out of this ADR's scope (§4).
