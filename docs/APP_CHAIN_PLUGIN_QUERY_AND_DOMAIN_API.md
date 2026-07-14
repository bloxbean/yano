# App-chain query and domain API plugin guide

This guide covers ADR-011.3's two read-side extension points:

- a state machine can answer a bounded query against one committed state root;
- the same manifested bundle can publish framework-neutral domain routes that
  call that query through a constrained host facade.

Start with the working project in `scaffolds/plugin-template`. It contains the
provider classes, both ServiceLoader descriptors, one manifest, unit tests, and
a production-catalog launch probe described below.

## 1. Keep the two execution planes separate

`AppStateMachine.apply(...)` remains deterministic consensus execution.
`AppStateMachine.query(..., AppQueryContext)` is off-consensus and read-only.
The query callback may overlap a later `apply()` on another thread, so do not
mutate state-machine fields or use the query callback as a command path.
Its result must depend only on the path, parameters, and supplied snapshot;
external I/O, wall-clock time, or randomness would produce a payload that the
reported state root does not attest.

The context's committed height, state root, and all key reads come from one
root-fixed snapshot. The context expires when the callback returns. Do not
retain it, create child work, emit effects, write state, perform unbounded CPU
work, or depend on a callback continuing after its deadline.

```java
@Override
public byte[] query(String path, byte[] params, AppQueryContext state) {
    if (!"passport/read".equals(path)) {
        throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                "unknown passport query");
    }
    if (!validAssetId(params)) {
        throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                "invalid asset id");
    }
    return state.get(passportKey(params)).orElse(new byte[0]);
}
```

A state-machine query may deliberately report only `UNSUPPORTED` or
`INVALID_REQUEST`. The host owns `BUSY`, `TIMEOUT`, `RESULT_TOO_LARGE`,
`UNAVAILABLE`, and `FAILED`; it bounds admission, one decoded request to 64
KiB, one result to 1 MiB, and execution time. Unexpected plugin exceptions are
logged by type, redacted, and exposed as `FAILED`.

The generic HTTP adapter is:

```text
POST /api/v1/app-chain/chains/{chainId}/query/{queryPath}
Content-Type: application/json

{"paramsHex":"<canonical lowercase even-length hex>"}
```

The response binds the opaque payload to `chainId`, `stateMachineId`,
`committedHeight`, and the 32-byte `stateRoot`. Query paths are normalized
relative paths of unreserved ASCII segments. Percent escapes, empty/dot
segments, leading/trailing slashes, and aliases are rejected.

## 2. Publish a constrained domain API product

Implement `DomainApiProvider`. Its stable id is the containing bundle id, not
a short state-machine selector:

```java
public final class PassportDomainApiProvider implements DomainApiProvider {
    public static final String BUNDLE_ID = "com.example.product-passport";

    @Override
    public String id() {
        return BUNDLE_ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new PassportDomainApi(context.queryService());
    }
}
```

The context exposes only a bounded `DomainQueryService`. It does not expose a
JAX-RS router, request identity, message submission, effects, mutable runtime
services, or administration. In production ADR-011.3 v1,
`DomainApiContext.bundleConfig()` is deliberately an empty map until Yano has a
typed, secret-safe configuration/reference contract. Do not put a domain API's
operation or security behind assumed configuration values.

### Routes and deterministic matching

Return at most 64 immutable `DomainApiRoute` entries and validate the exact set
in plugin tests with `DomainApiRouteSet.validateAndOrder(...)`. The host applies
the same validator before publication. Route ids are bundle-local stable
identifiers. Templates use literal segments and whole-segment lowercase
parameters such as `passports/{asset_id}`.

When routes overlap, the first segment at which one route is literal and the
other is a variable decides precedence; the literal wins. Parameter names do
not affect matching. Routes with the same method and structural shape are a
startup error, for example `claims/{id}` and `claims/{claim_id}`.

```java
private static final List<DomainApiRoute> ROUTES =
        DomainApiRouteSet.validateAndOrder(List.of(
                new DomainApiRoute("passport.read", DomainHttpMethod.GET,
                        "passports/{asset_id}", DomainApiAccess.READ),
                new DomainApiRoute("passport.report", DomainHttpMethod.POST,
                        "operator/report/{asset_id}", DomainApiAccess.PRIVILEGED),
                new DomainApiRoute("debug", DomainHttpMethod.GET,
                        "internal/debug", DomainApiAccess.INTERNAL)));
```

The v1 access classes are:

- `READ`: available under the host's read policy. A topic-scoped API key may
  call it.
- `PRIVILEGED`: requires API-key auth to be enabled and an unscoped full key.
  If that safe auth configuration is absent, the HTTP route is hidden as 404
  and the handler is not invoked.
- `INTERNAL`: reserved, secret-free inventory only. It is not dispatchable by
  HTTP or the public host/library gateway in v1.

The host owns `/api/v1/plugins/{bundleId}/{relativePath}`, GET/POST method
selection, authentication, raw request bounds, queueing, deadlines, response
validation, and error redaction. A plugin must not start another HTTP server or
assume its route can override a host endpoint.

### Handle requests and encode output safely

Dispatch on `request.routeId()`, not on untrusted raw path text. The request
contains immutable validated path/query maps and a defensive body copy. GET
has no body; POST is limited to 64 KiB. A response is limited to 1 MiB and can
be JSON or octet-stream. Plugins may return `200`, `400`, `404`, `409`, `410`,
or `422`; every other status is host-owned so redirects, authentication,
admission, no-content/partial-content, and server semantics cannot be forged.

If returning JSON, use a JSON library already packaged in the plugin or a
tested encoder. Never concatenate `chainId`, a query result, a parameter, or an
exception message directly into a JSON string. The scaffold's `JsonSupport`
is a small dependency-free example and its domain response hex-encodes opaque
bytes before JSON encoding. The host rejects malformed or trailing JSON.

Translate a committed-query failure by stable code and use a generic message:

```java
static DomainApiException translate(AppQueryException failure) {
    DomainApiException.Code code = switch (failure.code()) {
        case INVALID_REQUEST, REQUEST_TOO_LARGE ->
                DomainApiException.Code.INVALID_REQUEST;
        case UNSUPPORTED -> DomainApiException.Code.NOT_FOUND;
        case BUSY -> DomainApiException.Code.BUSY;
        case TIMEOUT -> DomainApiException.Code.TIMEOUT;
        case RESULT_TOO_LARGE -> DomainApiException.Code.RESULT_TOO_LARGE;
        case UNAVAILABLE -> DomainApiException.Code.UNAVAILABLE;
        case FAILED -> DomainApiException.Code.FAILED;
    };
    return new DomainApiException(code, "passport query failed", failure);
}
```

Do not copy `failure.getMessage()` to the new exception or response. The
runtime preserves a plugin-thrown `DomainApiException` reason code but replaces
its message with canonical host-owned safe text. Any other exception becomes
`FAILED`.

`DomainApi` is lifecycle-owned. `routes()` is snapshotted during construction,
callbacks are bounded and serialized per bundle in v1, and `close()` runs after
admission is sealed and callbacks drain. Make close idempotent, reject work
after close, release only resources owned by the product, and never close a
host facade.

## 3. Declare both ServiceLoader and manifest metadata

Create this exact resource:

```text
META-INF/services/com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider
```

Its content is the provider's binary class name:

```text
com.example.passport.PassportDomainApiProvider
```

Then declare the same class in the bundle-qualified manifest. For schema v1,
the domain contribution `name`, `DomainApiProvider.id()`, manifest `id`, and
manifest filename id must all be identical:

```json
{
  "schemaVersion": 1,
  "id": "com.example.product-passport",
  "version": "1.0.0",
  "yanoApi": { "min": 1, "max": 1, "minLevel": 1 },
  "dependencies": [],
  "contributions": [
    {
      "kind": "app-state-machine",
      "name": "passport",
      "provider": "com.example.passport.PassportStateMachineProvider"
    },
    {
      "kind": "domain-api",
      "name": "com.example.product-passport",
      "provider": "com.example.passport.PassportDomainApiProvider"
    }
  ]
}
```

The schema-v1 `yanoApi.minLevel` field is required. Compatibility requires both
a host API major within `min`/`max` and a host global API level at least
`minLevel`; an incompatible bundle is rejected before any provider is
constructed. The level advances for additive public plugin APIs (including new
contribution kinds) and never resets when the major changes. It is independent
of the bundle's `version` SemVer.

ServiceLoader remains the behavior-instantiation contract. The manifest is
identity, compatibility, policy, ownership, and inventory metadata; it is not
an arbitrary constructor list. A missing/mismatched descriptor or provider
fails catalog validation before product activation.

## 4. Build, deploy, and secure

Package one self-contained reproducible plugin JAR. Compile against
`yano-core-api` as `compileOnly` and never bundle
`com/bloxbean/cardano/yano/api/**`. Shade third-party runtime dependencies into
the same JAR; adjacent thin dependency JARs are not one catalog bundle.

Copy the JAR into `yaci.plugins.directory`. If an allow-list is configured,
allow the bundle id. Select the state-machine contribution by its short
selector on the app chain. The domain contribution is activated as part of the
selected bundle and is addressed by bundle id.

For privileged routes configure:

```properties
yano.app-chain.api.auth.enabled=true
yano.app-chain.api.keys=<unscoped-full-key>,<submit-key>=topic-a|topic-b
```

Send the unscoped key in `X-API-Key`. Topic-scoped keys can read and submit only
to their topics; they cannot call privileged routes. Treat all plugin JARs as
trusted in-process code: manifest validation and centralized HTTP auth do not
sandbox Java code.

## 5. Test before deployment

At minimum:

1. Test `apply()` determinism and replay with `StateMachineConformance`.
2. Test contextual queries with an in-memory `AppQueryContext`: unsupported and
   invalid input codes, no mutation, root/height envelope, request/result size.
3. Test the complete route set with `DomainApiRouteSet.validateAndOrder`,
   including structural collisions and literal-before-variable precedence.
4. Test every route id/access class, query-code translation, JSON injection,
   response bounds, close idempotence, and post-close rejection.
5. Inspect the built JAR for both ServiceLoader descriptors and the
   bundle-qualified manifest; reject bundled Yano API classes.
6. Launch the JAR through Yano's production directory catalog and resolve both
   providers. Then run packaged JVM/native smoke and an HTTP test with auth
   enabled and disabled.

The scaffold wires steps 1–5 into its Gradle `check` lifecycle and is the
reference authoring baseline. For event-listener and `NodePlugin` lifecycle
contributions, see `runtime/docs/events-and-plugins-guide.md`.
