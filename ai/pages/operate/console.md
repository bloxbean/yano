# Console & observability

Explore the embedded console and persistent metrics.

Canonical URL: https://getyano.dev/operate/console/

The Yano distribution embeds the unified console. Start Yano and open
`http://127.0.0.1:7070/ui/`. The node, plugin, and observability routes are
real static paths, so `/ui/status/`, `/ui/plugins/`, and `/ui/observability/`
can be bookmarked directly. Application ledger (app chain) pages are not part of
Yano's console; [Yano X](https://github.com/bloxbean/yano-x) ships its own.

The connection panel accepts a Yano API base and an optional API key. Keys
remain in memory unless the operator explicitly opts into browser-local
persistence. The plugin operations route is stricter: it ignores `?api=` and
saved base overrides, trusts only `/ui/plugins/api-prefix.json`, and retains
its privileged key in the current tab's `sessionStorage`.

## Standalone hosting

Use the embedded console in your installed release where available. A separately hosted console is an advanced deployment; see [the source-build page](/contribute/build-from-source/#standalone-console-artifact) to produce its ZIP and extract it below `/ui` on a static server. The archive has no Node.js runtime and
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
checks.

## Historical metrics

Without extra services, the node charts retain up to one hour
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

`start` scrapes `http://127.0.0.1:7070` by default. Repeat
`--target <node-origin>` to scrape other nodes instead. It prints a
preconfigured `/ui/observability/?metrics=...` link. The default retention is 15 days / 2 GB; `stop` preserves history and only
the explicit `clean --yes` command removes the marked state and labeled
volume. Docker Compose v2 is required only for this optional mode.

Production operators can set an existing Prometheus-compatible origin in the
Connection panel. The console issues only its built-in read-only queries.
Configure exact-origin CORS and access control on that endpoint. Its optional
bearer credential is retained only in the current tab, is bound to the exact
metrics origin, is never stored with or substituted for the Yano API key, and
is never sent to the node.

## Frontend development

For local frontend development, run `npm run dev` in
`console-ui/frontend`; Vite proxies `/api` and `/q` to
`http://127.0.0.1:7070`.
