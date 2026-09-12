---
title: "HTTP API"
description: "Discover release-matched OpenAPI schemas and the main API groups."
sidebar:
  order: 3
---

The default application API prefix is `/api/v1`. Management endpoints under `/q` and the console under `/ui/` sit outside that prefix.

For transaction-building SDK integrations, see [Build with any SDK](/develop/blockfrost/).

## Explore the running node

Open `http://localhost:7070/q/swagger-ui`. The node supplies these OpenAPI documents:

| Document | Scope |
| --- | --- |
| `/q/openapi-core` | Chain, ledger, transactions, evaluation, read-only node status |
| `/q/openapi-app-chain` | App-chain and plugin domain APIs |
| `/q/openapi-devnet` | Local faucet, snapshots, rollback, time controls |
| `/q/openapi-admin` | Node lifecycle, plugin operations, diagnostics |
| `/q/openapi-history` | Historical coverage and maintenance |
| `/q/openapi` | All API groups |

Download the schema from **your actual binary**:

```bash
curl -fsS 'http://localhost:7070/q/openapi?format=json' -o yano-openapi.json
```

The site provides a [source-derived route inventory](/ai/routes.json), not a substitute for a complete OpenAPI schema. Routes can be feature-gated, require credentials, or return unavailable when required data is absent.

## Common requests

```bash
curl -fsS http://localhost:7070/api/v1/node/tip
curl -fsS http://localhost:7070/api/v1/status
curl -fsS http://localhost:7070/api/v1/epochs/latest/parameters
curl -fsS http://localhost:7070/q/health/ready
```

Node lifecycle and debug operations are administrative actions. Do not expose a development node's complete API surface to untrusted clients. Consult the relevant feature guide for authentication and configuration.

Swagger UI can be disabled with `YANO_SWAGGER_UI_ENABLED=false` or `-Dquarkus.swagger-ui.always-include=false`. Disabling a documentation UI does not disable the underlying APIs.
