# ADR-034: Swagger UI API Groups (Core / App Chain / Devnet / Admin)

## Status

Accepted and implemented — version 1

## Date

2026-08-23

## Related decisions

- [ADR-018](018-rest-api-prefix-and-blockfrost-compatible-ledger-apis.md) —
  immutable artifact API prefix; debug path policy
- [ADR-019](019-governance-adapot-stake-rest-api-plan.md) — governance,
  AdaPot and stake REST API
- [app-layer ADR-005](app-layer/005-yano-app-chain-framework.md) — app-chain
  framework and its REST surface (extended by the E5.x app-chain
  extensions)
- [app-layer ADR-011.3](app-layer/011.3-query-and-domain-api-extensions.md)
  and [ADR-011.4](app-layer/011.4-plugin-operations-and-observability.md) —
  plugin domain routes and the plugin operations surface
- [ADR-028](028-unified-console-ui-module.md) — console UI (consumes the
  app-chain surface; unaffected by this decision)

## Context

Every JAX-RS resource in the `app` module is rendered into a single OpenAPI
document (`/q/openapi`) and therefore into a single, very long Swagger UI
page (`/q/swagger-ui`). The surface has grown into clearly different
audiences that are now interleaved on that one page:

| Audience | Examples |
|----------|----------|
| Application developers querying chain/ledger data and submitting transactions (Blockfrost-shaped) | `/blocks`, `/txs`, `/tx/submit`, `/utxos`, `/accounts`, `/epochs`, `/governance`, `/scripts`, `/utils/txs/evaluate` |
| App-chain builders | `/app-chain/chains/{chainId}/...`, `/plugins/{bundleId}/...` |
| Devnet / test-harness users | `/devnet/rollback`, `/devnet/snapshot`, `/devnet/fund`, `/devnet/time/advance` |
| Operators | `/node/start`, `/node/stop`, `/node/recover`, `/plugin-operations`, `/api/debug/...` |

A developer looking for `GET /blocks/latest` currently has to scroll past
devnet time-travel and reward-calculation debug endpoints. Conversely, an
operator cannot see the lifecycle/diagnostic surface in isolation.

### Constraints

- **API paths must not change.** Clients, the e2e smoke suite
  (`e2e-tests/yano_endpoint_smoke.py`), the console UI and the testkits
  depend on the existing routes and on the baked artifact prefix
  (ADR-018). Only the *documentation* is regrouped.
- `/q/openapi` must keep serving the complete document: the e2e smoke
  suite fetches it for route-coverage checks and `AppChainOpenApiTest`
  asserts against it.
- The solution must work unchanged in the GraalVM native image. Yano's
  OpenAPI documents are produced at build (augmentation) time; anything
  that requires runtime reflection or a second document generator is
  undesirable.
- No new dependencies. Yano already ships `quarkus-smallrye-openapi`
  (which brings `quarkus-swagger-ui`).

### What Quarkus 3.33 offers

Quarkus `quarkus-smallrye-openapi` 3.33 (SmallRye OpenAPI 4.2) supports
**named OpenAPI documents** (verified against the 3.33.2.1 extension
binaries):

- `quarkus.smallrye-openapi."<name>".path` — defaults to
  `openapi-<name>`, i.e. the document is served at `/q/openapi-<name>`.
- `quarkus.smallrye-openapi."<name>".scan-profiles` /
  `.scan-exclude-profiles` — per-document operation selection.
- `quarkus.smallrye-openapi."<name>".info-title` / `.info-description`
  and every other per-document property.
- The unnamed (`<default>`) document at `quarkus.smallrye-openapi.path`
  (`/q/openapi`) is always generated as well.

Operation selection uses SmallRye OpenAPI *scan profiles*. An operation
carries a profile when it (or, if the method declares no `@Extension` at
all, its declaring class) has
`@Extension(name = "x-smallrye-profile-<profile>", value = "")`.
`AbstractAnnotationScanner.profileIncluded` then applies:

1. if `scan-exclude-profiles` is non-empty, the operation is included
   unless it carries one of the excluded profiles;
2. else if `scan-profiles` is empty, the operation is included;
3. else the operation is included only if it carries **at least one** of
   the listed profiles.

Rule 3 makes group membership an explicit opt-in: an endpoint without a
profile never leaks into a named group, and one endpoint can belong to
several groups. The `x-smallrye-profile-*` extensions are stripped from
the emitted documents, so no marker is visible to API consumers.

Swagger UI already renders a "Select a definition" drop-down when it is
configured with several `urls`. `quarkus-swagger-ui` builds that list
automatically from the named documents, but labels the unnamed document
literally `<default>`; `quarkus.swagger-ui.urls."<label>"=<path>` overrides
the list with human-readable labels and
`quarkus.swagger-ui.urls-primary-name` selects the pre-selected entry.

## Decision

Group the REST surface into named OpenAPI documents selected by SmallRye
scan profiles, and expose them through the Swagger UI definition
drop-down with **Core API** pre-selected. Routes, prefixes, payloads and
authentication are untouched.

### Groups

| Drop-down label | Document name | Served at | Profile constant | Resources |
|-----------------|---------------|-----------|------------------|-----------|
| Core API (default) | `core` | `/q/openapi-core` | `ApiGroup.CORE` | `BlockResource`, `TransactionResource`, `TxResource`, `UtxoResource`, `AccountStateResource`, `EpochResource`, `GenesisResource`, `GovernanceResource`, `NetworkResource`, `ScriptResource`, `StatusResource`, `EvaluationResource`; read-only node views `GET /node/status`, `GET /node/tip`, `GET /node/protocol-params` |
| App Chain API | `app-chain` | `/q/openapi-app-chain` | `ApiGroup.APP_CHAIN` | `AppChainResource`, `AppChainResource.ChainScopedResource`, `PluginDomainResource` |
| Devnet API | `devnet` | `/q/openapi-devnet` | `ApiGroup.DEVNET` | `DevnetResource` |
| Admin API | `admin` | `/q/openapi-admin` | `ApiGroup.ADMIN` | `YanoResource` (all `/node/*` including lifecycle `start`/`stop`/`recover`, `config`, `peers`, `epoch-calc-status`, `tx/submit`), `PluginOperationsResource`, `DebugSnapshotResource` (`/api/debug/*`), `EpochNonceResource` |
| All APIs | `<default>` | `/q/openapi` | — | everything (unchanged) |

Rationale for the boundary cases:

- `/node/status`, `/node/tip` and `/node/protocol-params` are useful to
  application developers (sync state, tip, current parameters) and to
  operators, so they belong to both **Core** and **Admin**.
- `POST /node/tx/submit` duplicates `POST /tx/submit`; the canonical,
  Blockfrost-shaped route is the Core one, so the `/node` alias is Admin
  only.
- `/api/debug/*` is an operator diagnostic surface (ADR-018 "Debug Path
  Policy"); it is grouped under Admin rather than a fifth group. A
  separate "Debug" group can be split out later by adding one profile
  constant — the mechanism does not need to change.
- `/app-chain/chains/{chainId}/admin/*` stays in the App Chain group: it
  is protected by the app-chain API key and is part of that product's
  contract (app-layer ADR-005 / E5.x extensions), not node administration.
- The chain-less app-chain aliases remain `@Operation(hidden = true)` and
  are absent from every document.

### Mechanism

1. `com.bloxbean.cardano.yano.app.api.ApiGroup` holds the profile
   extension names as compile-time constants
   (`x-smallrye-profile-core`, `-app-chain`, `-devnet`, `-admin`).
2. Each resource class is annotated with
   `@Extension(name = ApiGroup.<GROUP>, value = "")`. Operations that must
   appear in more than one group, or that must differ from their class,
   carry their own method-level `@Extension`s — note that a method-level
   `@Extension` **replaces** the class-level set, so every intended group
   must be repeated on the method.
3. `application.yml` declares the four named documents with their
   `scan-profiles`, `info-title` and `info-description`, and configures
   `quarkus.swagger-ui.urls` (five labels including "All APIs") and
   `quarkus.swagger-ui.urls-primary-name: Core API`.
4. A Quarkus test (`ApiGroupOpenApiTest`) asserts, for every named
   document, representative included and excluded paths, that the default
   document still contains all groups, and that the Swagger UI index
   advertises the drop-down with Core API as the primary entry.

### Contributor rule

**Every new JAX-RS resource class in `app` must carry an `ApiGroup`
extension.** Without one its operations appear only in "All APIs".
`ApiGroupOpenApiTest` counts the operations in the default document
against the union of the named documents and fails when an operation is
not reachable from any group, so the omission is caught in CI.

## Non-goals

- Changing any route, prefix, payload, status code or authentication.
- Renaming OpenAPI tags (`auto-add-tags` still derives tags from class
  names). Explicit `@Tag` naming is a separate, cosmetic follow-up.
- Hiding groups based on runtime mode (for example omitting the Devnet
  group on a public-network node). Documents are generated at build time;
  the devnet endpoints already answer 4xx outside devnet mode.
- Serving different groups from different ports or listeners.

## Consequences

- Swagger UI opens on the Core API page; other groups are one drop-down
  selection away. `/q/openapi` is unchanged for existing consumers.
- Four additional static documents are generated at build time and
  served at `/q/openapi-{core,app-chain,devnet,admin}`. They are plain
  resources, so native-image behaviour is identical to the default
  document.
- Group membership is declared in code next to the resource, reviewed
  with the resource, and enforced by a test — not maintained in a
  separate list.
- Operators who disable Swagger UI (`YANO_SWAGGER_UI_ENABLED=false`) are
  unaffected; the named documents remain available for tooling.

## Verification

- `./gradlew :app:test --tests '*ApiGroupOpenApiTest*'` and the existing
  `AppChainOpenApiTest` (both green, 2026-08-23).
- Observed on the built `yano.jar` (2026-08-23): 46 paths in `core`, 54 in
  `app-chain`, 10 in `devnet`, 25 in `admin`, 132 in the default document;
  the Swagger UI index carries the five `urls` entries with
  `urls.primaryName = Core API`.
- Manual: run the node, open `/q/swagger-ui`, confirm the "Select a
  definition" drop-down lists Core API (selected), App Chain API, Devnet
  API, Admin API, All APIs, and that `curl /q/openapi-core` contains
  `/blocks/latest` but not `/devnet/rollback`.
