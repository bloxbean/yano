# Yano console

The Yano distribution embeds the unified console. Start Yano and open
`http://127.0.0.1:7070/ui/`. The node, app-chain, and plugin routes are real
static paths, so `/ui/status/`, `/ui/app-chain/`, and `/ui/plugins/` can be
bookmarked directly.

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

For local frontend development, run `npm run dev` in
`console-ui/frontend`; Vite proxies `/api` and `/q` to
`http://127.0.0.1:7070`.
