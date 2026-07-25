# Yano console

The Yano distribution embeds the unified console. Start Yano and open
`http://127.0.0.1:7070/ui/`. The node, app-chain, plugin, and observability
routes are real static paths, so `/ui/status/`, `/ui/app-chain/`,
`/ui/plugins/`, and `/ui/observability/` can be bookmarked directly.

The connection panel accepts a Yano API base and an optional API key. Keys
remain in memory unless the operator explicitly opts into browser-local
persistence. The plugin operations route is stricter: it ignores `?api=` and
saved base overrides, trusts only `/ui/plugins/api-prefix.json`, and retains
its privileged key in the current tab's `sessionStorage`.

## Standalone hosting

Build the standalone artifact with:

```bash
./gradlew :console-ui:consoleZip
```

Extract `console-ui/build/distributions/yano-console-ui-<version>.zip` below
the `/ui` path of a static HTTP server. The archive has no Node.js runtime and
contains no credentials. For the plugin page, route `/api/v1` to the Yano node
on the same origin (the archive's fixed plugin discovery document intentionally
does not accept a query-controlled remote base).

For the other console routes, a cross-origin node can be selected from the
connection panel. CORS is disabled in Yano by default. Enable it only for the
exact console origin, for example:

```yaml
quarkus:
  http:
    cors:
      enabled: true
      origins: https://console.example.com
      methods: GET,POST,OPTIONS
      headers: Accept,Content-Type,X-API-Key
```

The equivalent environment settings are
`YANO_HTTP_CORS_ENABLED=true`,
`QUARKUS_HTTP_CORS_ORIGINS=https://console.example.com`,
`QUARKUS_HTTP_CORS_METHODS=GET,POST,OPTIONS`, and
`QUARKUS_HTTP_CORS_HEADERS=Accept,Content-Type,X-API-Key`.

Never configure `origins: "*"` when API keys are used. CORS controls which
browsers may call the node; it does not replace or weaken Yano's API-key
checks. The fetch-based app-chain stream uses the same CORS and `X-API-Key`
rules as normal JSON requests.

## Historical metrics

Without extra services, the node and app-chain charts retain up to one hour
of bounded history in the current browser tab. This short history survives a
refresh but is not a monitoring database.

For persistent local history, the JVM and native distributions include a
pinned Prometheus companion:

```bash
./yano.sh observability start
./yano.sh observability status
./yano.sh observability stop
./yano.sh observability clean --yes
```

`start` discovers a running maintained local app-chain cluster, or defaults
to `http://127.0.0.1:7070`. Repeat `--target <node-origin>` to replace
discovery. It prints a preconfigured `/ui/observability/?metrics=...` link.
The default retention is 15 days / 2 GB; `stop` preserves history and only
the explicit `clean --yes` command removes the marked state and labeled
volume. Docker Compose v2 is required only for this optional mode.

Production operators can set an existing Prometheus-compatible origin in the
Connection panel. The console issues only its built-in read-only queries.
Configure exact-origin CORS and access control on that endpoint. Its optional
bearer credential is retained only in the current tab, is bound to the exact
metrics origin, is never stored with or substituted for the Yano API key, and
is never sent to the node.

## Capability panels

The App chains page discovers optional capabilities from the selected chain's
status and, when authorized, confirms first-party bundles against the plugin
catalog. It does not assume that every deployment has effects or role-aware
approvals.

- An effects-enabled chain shows emitted effects, statistics, composed proofs,
  and confirmed requeue/cancel actions. Mutations require the appropriate
  operator API key.
- A `role-approvals` or `role-evidence` chain queries the proposal through the
  root-fixed committed-query surface and adds the decoded domain projection
  when available. The committed result remains visible if that convenience
  projection is unavailable.
- Every running app chain exposes the portable evidence-bundle and MPF-proof
  tools. The browser can SHA-256 the exact finalized message payload or
  included proof value and compare each with its own optional expected digest.
  A proof can be loaded at the current tip, loaded at the exact height of the
  latest anchor confirmed by this node, or pasted as JSON. **Verify proof**
  sends only the bounded key/value/proof/root fields to the connected node's
  release-matched MPF verifier and reports three facts independently:

  - whether the MPF path is mathematically valid for the expected root;
  - whether the proof envelope's root and optional height match that source;
  - where the expected root came from.

`L1-confirmed by this node` means Yano observed the anchor transaction and
bound its persisted confirmation back to the exact finalized app block. It is
not an independent Cardano lookup. For an audit decision, resolve the shown
transaction through an independent Cardano source, validate the metadata or
script datum/output, and pin the expected chain, membership, threshold, and
script identity. The browser digest remains a byte-integrity check rather than
finality, anchor, or MPF verification. Custom component-specific panels remain
data-only future catalog work; plugins cannot inject executable console code.

For local frontend development, run `npm run dev` in
`console-ui/frontend`; Vite proxies `/api` and `/q` to
`http://127.0.0.1:7070`.
