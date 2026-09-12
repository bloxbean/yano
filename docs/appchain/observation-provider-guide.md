# Observation provider author guide (preview)

An acquisition provider obtains candidate bytes outside deterministic block
execution. It does not decide consensus, open rounds, authenticate physical
truth, or sign reports on behalf of validators. Start with the bundled restricted
HTTPS adapters unless the source requires an operator-approved custom plugin.

## Contract and packaging

Implement `ObservationProviderFactory`: `type()` is the exact configured
`observations.providers.<definition-id>.type` selector; `create()` receives
the definition ID and operational settings and returns an `ObservationProvider`.
Register the factory's class in the normal Java service descriptor for
`org.yanoproject.api.appchain.observation.ObservationProviderFactory`
and declare the same selector/class as an `observation-provider` contribution
in the schema-1 plugin manifest. Use the normal catalog, not ad-hoc class loading.

Pin the required host API major/level and exact published dependency version.
Package third-party dependencies using the repository's plugin-bundle rules;
do not bundle host implementation classes or load sibling source projects.
The host conformance fixture provides an executable registration/lifecycle
example. Yano X extensions use the ordinary JVM host; native host support must
be independently demonstrated by native catalog conformance, not assumed.

## Acquisition lifecycle

`acquire(ObservationRequest)` receives the pinned definition, subscription and
currently open round. Return an `ObservationCandidate` containing source ID,
canonical value, evidence, source version and freshness anchor. Host validation
and report signing happen afterward. The broad candidate envelope limits do
not override narrower definition/profile/verifier limits.

Calls to one provider instance are serialized; distinct providers may run
concurrently. Bound DNS/connection/read time, parsing depth, response bytes, retries
and memory. Release sockets/executors in `close()`, honor interruption, and
avoid background operations that outlive the provider generation. Do not hold
unbounded queues or assume a late result will still be eligible for its round.

On acquisition failure, fail locally. Never manufacture a value, reuse an
expired result, switch sources silently, or reinterpret an outage as a
consensus cancellation. The ordinary scheduler and round rules determine
whether another attempt is possible and whether the round expires.

## Evidence and source identity

For exact attested HTTPS, pin endpoint/method/logical-source-ID/attestor configuration with the
corresponding `ObservationSourceConfiguration` digest. For signed Merkle
receipts, use `merkleAttestedHttpsSourceDigest` and verifier
`ed25519-merkle-inclusion-v1`. Leaves bind parameter digest, source and value;
branches bind ordering; the root attestation binds round, definition, source
version and freshness. Supply canonical bounded evidence, not a new encoding
that merely produces the same application value.

Both HTTPS digest helpers require `sourceId` before the attestor-key list.
Set `observations.providers.<definition>.source-id` to that exact ASCII value;
custom attested adapters use `attestedSourceDigest(sourceId, keys)`. A signed
claim or Merkle leaf for another logical source is rejected, even if its
signature and inclusion proof are otherwise valid.

Select a definition's verifier/reconciliation policy at genesis. Custom
acquisition does not authorize changing either. External reporters are a
different trust mode: their identities/quorums are explicitly pinned and their
durable signing journals must prevent a second incompatible report. Do not
count several reporters querying one endpoint as independent sources.

For numeric sources use exact bounded integer/fixed-point encodings. Reject
overflow, ambiguous decimal scales and disagreement according to the pinned
policy. Do not use floating-point rounding or mutable process defaults to
construct a value shared by consensus participants.

## Network and operational trust

The bundled restricted HTTPS path validates public destinations, resolves and
pins acceptable addresses, verifies TLS and bounds redirects/framing/body
handling. Do not bypass those checks to reach loopback, metadata services or
private networks. A custom plugin is trusted operator-installed code: it does
not inherit a sandbox simply because it implements the provider interface.
Its network policy requires its own review.

The bundled adapter shares one timeout budget across DNS, connect, TLS and
response reads. Its host-wide DNS isolator permits four daemon workers and 64
queued lookups; timed-out queued work is cancelled and removed. A platform DNS
call that ignores interruption cannot hold an acquisition worker or retain an
app-chain generation, though it may occupy a bounded shared resolver worker.
Capacity exhaustion is a local failure, not a signed observation outcome.
Both attestation adapters require `Content-Type: application/cbor`; raw-exact
mode handles opaque bounded bytes without selecting a parser from that header.
Redirects and compressed responses are rejected in every built-in mode.

Keep credentials in operator-managed configuration, not subscription params,
evidence or logs. Sanitize errors; never log bearer tokens, private signing
material or unrestricted response bodies. Prefer public source identities that
can be audited without access to credentials.

Webhooks may call the wake-hint endpoint with an existing subscription ID.
They convey no value or authority and cannot bypass rate limits or collection
windows. Always retain periodic acquisition as the fallback.

## Required provider tests

Exercise stale/wrong-source/wrong-round/replayed evidence, malformed/truncated/
oversized payloads, parser bounds, timeouts, unavailable/DNS-changing endpoints,
redirect escape, cancellation, close during acquisition and late completion.
Check that invalid candidates are rejected before journal claim/signing, and
that absent webhooks do not prevent periodic recovery. Test exact packaged
catalog loading and multi-node result/root agreement using the intended profile.
Passing an adapter unit test is not qualification of an external source's truth.
