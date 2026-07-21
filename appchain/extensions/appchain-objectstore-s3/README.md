# Yano app-chain S3-compatible object-store connector

`appchain-objectstore-s3` is the first-party ADR-013 plugin bundle for the
`object.put` effect. It promotes a bounded, pre-staged object into one
configured immutable/versioned S3-compatible destination. The effect carries
only aliases, relative keys, SHA-256, size, media type, and an optional
retention-class alias; it cannot choose endpoints, credentials, buckets,
encryption keys, Object Lock deadlines, ACLs, or arbitrary request headers.

The first compatibility profile targets AWS S3, MinIO, Ceph, and compatible
providers that implement all of the following semantics:

- destination bucket versioning is `Enabled`;
- exact-key version inventory is available and bounded;
- a destination can be created atomically with `If-None-Match: *`;
- the successful create returns a non-empty printable version id other than
  S3's unversioned `null` sentinel;
- bounded `HEAD` and `GET` permit independent SHA-256 verification; and
- configured server-side encryption and optional Object Lock are enforced; and
- the archive prefix has one create-only application writer; connector and
  concurrent application principals cannot call `DeleteObject`,
  `DeleteObjectVersion`, or change bucket lifecycle configuration; no lifecycle
  expiration or noncurrent-version cleanup rule applies to the prefix; and any
  administrative lifecycle/deletion change is offline and fenced.

The executor never performs an unconditional overwrite, multipart upload,
server-side copy fallback, ACL change, arbitrary URL fetch, or delete. Before
creating a destination it checks exact-key version history; a prior version or
delete marker is a conflict. The create-only bucket-policy invariant is
essential because S3 conditional create checks only the current representation;
without it, another principal could race the history probe with create+delete.
It then reads exactly the committed source size (plus a one-byte EOF probe),
rehashes the bytes independently of ETag, and performs one conditional PUT with
the full SHA-256 checksum. A provider `FULL_OBJECT` checksum must equal that
local hash. AWS multipart `COMPOSITE` responses are accepted only in canonical
`base64-digest-partCount` form (part count 1--10,000) and are treated as a
shape-validated provider signal, never as the command's whole-object digest.
an explicit content length and SHA-256 checksum. An uncertain acknowledgement
is reconciled by probing provider state before any later mutation.

## Artifacts

- `yano-appchain-objectstore-s3-<version>.jar` is the thin artifact for
  build-time/native inclusion.
- `yano-appchain-objectstore-s3-<version>-bundle.jar` is the self-contained
  directory-loaded plugin. It contains AWS SDK v2 and privately relocated
  connector-contract/CBOR/BLAKE2b classes, but never Yano host API classes.

The SDK transport is the bounded synchronous URLConnection client. Each
created effect executor owns its own S3 client and closes it idempotently; no
mutable client is shared across chains.

V1 admits one in-flight transfer per configured target. Local contention is a
non-consuming retry, while the Effect Runtime supplies the global worker and
retry-rate bounds. To keep executor construction bounded, `local-demo` accepts
only canonical private/loopback/ULA numeric endpoint literals or the exact name
`localhost` (canonicalized directly to `127.0.0.1` without DNS). Link-local
metadata-service space and service-discovery names are rejected. Ambient
JVM/environment proxies are disabled so signed requests cannot be redirected
outside the target policy. A launcher using service discovery must resolve it
outside the executor under its own timeout and inject the resulting literal.

## Configuration shape

Configuration is target-allowlisted under:

```properties
yano.app-chain.effects.executors.objectstore-s3.targets.archive.target-id=archive-v1
yano.app-chain.effects.executors.objectstore-s3.targets.archive.endpoint=http://127.0.0.1:9000
yano.app-chain.effects.executors.objectstore-s3.targets.archive.region=us-east-1
yano.app-chain.effects.executors.objectstore-s3.targets.archive.security-profile=local-demo
yano.app-chain.effects.executors.objectstore-s3.targets.archive.path-style=true
yano.app-chain.effects.executors.objectstore-s3.targets.archive.credentials-provider=static
# Development-only placeholders: inject different generated values in a real demo.
yano.app-chain.effects.executors.objectstore-s3.targets.archive.credentials.access-key-id=demo-access-key
yano.app-chain.effects.executors.objectstore-s3.targets.archive.credentials.secret-access-key=demo-secret-key-change-me
yano.app-chain.effects.executors.objectstore-s3.targets.archive.source-bucket=evidence-staging
yano.app-chain.effects.executors.objectstore-s3.targets.archive.destination-bucket=evidence-archive
yano.app-chain.effects.executors.objectstore-s3.targets.archive.destination-prefix=verified
yano.app-chain.effects.executors.objectstore-s3.targets.archive.encryption-policy-id=sse-v1
yano.app-chain.effects.executors.objectstore-s3.targets.archive.encryption-mode=none
yano.app-chain.effects.executors.objectstore-s3.targets.archive.retention-policy-id=none-v1
yano.app-chain.effects.executors.objectstore-s3.targets.archive.require-versioning=true
yano.app-chain.effects.executors.objectstore-s3.targets.archive.max-object-bytes=16777216
```

Managed AWS profiles (no custom `endpoint`) additionally require exact AWS
account ownership guards for both buckets:

```properties
yano.app-chain.effects.executors.objectstore-s3.targets.archive.source-expected-owner=123456789012
yano.app-chain.effects.executors.objectstore-s3.targets.archive.destination-expected-owner=123456789012
```

Each value is exactly 12 decimal digits. The adapter sends the corresponding
`expectedBucketOwner` on bucket versioning, version inventory, `HEAD`, `GET`,
and `PUT`; AWS rejects a request if the named bucket belongs to another
account. If source and destination name the same bucket, the two expected
owners must match. Custom/local compatible endpoints reject these settings
because their account-ownership semantics are not AWS's.

Expected owners remain executor-local: diagnostics, effect payloads, receipts,
detail documents, metadata, and logs do not render them. They are part of the
immutable target deployment identity, so changing either expected owner
requires a new `target-id` rather than repointing an existing target.

Credentials are supplied through the configured secret/environment provider;
they are never stored in effect bytes, receipts, metadata values intended for
authenticated state, status output, or logs. Plain HTTP and static development
credentials are restricted to an explicit local-demo profile and numeric
private/local endpoints. Public profiles require TLS. Managed AWS `sse-kms`
targets use the full key ARN in the configured region; compatible endpoints
use the exact stable key identity returned by their provider. The executor
reconciles that identity locally and never includes it in user metadata,
receipts, details, or logs.

## Delivery and receipt semantics

The delivery ceiling is exactly-once result incorporation with at-least-once
external execution. A confirmed receipt commits the resolved destination,
mandatory immutable provider version id, independently verified SHA-256, and
size. Stable provider/retention details may be committed through the configured
durable detail archive. ETag is informational and is never treated as a content
hash. Provider version IDs, ETags, content types, metadata, and KMS identifiers
must be bounded printable ASCII before they can reach a receipt or detail.

All provider exceptions are reduced at the client boundary to bounded stable
classifications. Raw SDK messages, endpoint URLs, credentials, keys, response
headers, and response bodies are not copied into effect failures or receipts.

## Tests

Ordinary `check` is offline and covers the adapter boundary, policy/error
normalization, lifecycle, and thin/bundle packaging. Real S3-compatible tests
are opt-in. The release invocation starts the digest-pinned local demo backend,
keeps credentials in owner-only files, exercises restart and unavailability,
checks that no opted-in test skipped, and removes the disposable service and
its data:

```bash
app/appchain-effects-demo/tests/connector-fault-matrix.sh
```

For a separately managed AWS S3 or compatible service, opt in with
`yano.s3.integration.enabled`, `endpoint`, `run-id`, `access-key-file`, and
`secret-key-file` system properties. Both credential files must be regular,
non-symlink, owner-only files; only their paths, never their values, may appear
in Gradle or JVM arguments. Release evidence exercises versioning, exact-key
conflict/no-resurrection, checksum mismatch, conditional create,
restart/reconciliation, unavailability, and the selected retention profile
against the exact RustFS release selected by the self-contained demo; that
preview backend is not a production object-store recommendation. The fault
harness additionally
sets `yano.s3.integration.disposable-service=true`: this permits a provider's
`BucketNotEmpty` cleanup discrepancy only after the tests prove that no
user-visible object, version, or delete marker remains and only because the
entire isolated provider data directory is then destroyed.
