# Yano Console UI

Source of the Yano web console: a SvelteKit (Svelte 5, Tailwind CSS 4, Vite) static site with the
landing page and the `status`, `plugins` and `observability` routes. The build produces static assets
under `META-INF/resources/ui`, which `app-core` copies into its jar so every Yano distribution serves the
console at `/ui/`. It can also be packaged as a standalone ZIP for any static web server.

## Artifact

Not published. The Gradle project builds a jar that only [`app-core`](../app-core/README.md) consumes
(through its `consoleUi` configuration), and a standalone archive:
`console-ui/build/distributions/yano-console-ui-<version>.zip`.

## Key contents

- `frontend/` - the SvelteKit project (`src/routes`, `src/lib`, `static`, `svelte.config.js`,
  `vite.config.ts`). Node.js 22.12.0 is downloaded by the Gradle Node plugin into `.gradle/`.
- `build.gradle` - npm tasks and the packaging checks:
  - `buildFrontend` writes `build/generated/console-ui/META-INF/resources/ui`;
  - `checkFrontend` (`svelte-check`) and `testFrontend` (Vitest);
  - `verifyEmbeddedAssetBudget` keeps the embedded console under 1 MiB gzip;
  - `verifyStaticConsoleContract` checks the required pages, viewport, landmarks and responsive CSS, and
    that no `app-chain/` path is packaged;
  - `consoleZip` builds the standalone archive.

## Dependencies and boundaries

- No Java dependencies. Only `app-core` depends on it.
- The application-ledger (app-chain) console is a separate downstream application served at
  `/ui/app-chain/`; this module keeps the generic pages and builds nothing under that path
  ([ADR-064](../adr/) D9).

Operator guide: [Yano console](../docs/console-ui.md). Design:
[ADR-028 unified console UI module](../adr/028-unified-console-ui-module.md).

## Build and test

```bash
./gradlew :console-ui:build       # frontend build, check, tests, budget and contract checks, ZIP
./gradlew :console-ui:consoleZip  # standalone archive only
cd console-ui/frontend && npm ci && npm run dev   # local development server (needs Node.js)
```
