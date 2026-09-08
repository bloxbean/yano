# ADR-037 Phase 1 implementation report

Date: 2026-09-04 (updated 2026-09-05)

Milestone branch: `milestone/adr-037-phase-1`

Integration branch: `feat/generic-observation-framework-adr`

## Outcome

Phase 1 implements bounded one-shot `APP_HEIGHT` observations end to end. An
application can deterministically create or cancel a subscription, active
members acquire a candidate outside consensus, journal and diffuse signed
reports, any node can assemble an exact-value certificate, and a proposer can
place the certificate in the canonical system-input region. The ordinary Yano
app-block protocol remains the only finality mechanism.

The feature remains disabled by default and is a fresh-chain profile. An
enabled chain supplies the canonical `ObservationProfileV1` bytes through
`observations.profile-cbor-hex`.

## Delivered

- Observation-aware application transition and result callback overloads,
  deterministic `watch`/`cancel`, a shared block emission ordinal, terminal
  `VALUE`, `EXPIRED`, and audit-only `CANCELLED` records.
- Replicated subscription, due-index, round, result, and counter records in a
  dedicated RocksDB column family, staged atomically with the finalized block
  and mirrored into authenticated application state.
- Exact system-input order: mandatory L1 prefix, observation results, other
  inputs; effect results, observation results, effect expiry, observation
  close/expiry/open, then application messages.
- Active-round certificate validation against the opening-height membership;
  malformed input rejects before state lookup, while canonical stale or
  duplicate certificates are no-ops. Conflicting valid results for one active
  round reject the block.
- Identity-bound synchronous node-local journal. A no-equivocation lock and
  report are one durable write before diffusion. Restart restores reports and
  the lexicographically selected ready certificate; terminal rounds are
  pruned.
- Bounded coordinator and worker pools, retry after local provider failure,
  periodic report/certificate re-diffusion, exact-value grouping, and compact
  lowest-reporter certificate construction.
- Plugin API level 5 and the privileged-local `observation-provider`
  contribution, including catalog inspection, TCCL/lifecycle fencing, native
  resource metadata, and conformance-fixture coverage.
- An Ed25519 attestation wire object that binds definition, subscription,
  round, source, claim, source version, and freshness. The reference HTTPS
  adapter sends round identity headers and verifies the returned binding.
- A raw exact HTTPS adapter whose fixed endpoint/source/version-header identity
  is profile-committed. It permits only HTTPS GET/POST, never lets application
  parameters choose an endpoint, denies redirects/proxies/encoded bodies,
  bounds time and bytes, rejects private/reserved address classes, validates
  every DNS answer, and pins the TLS connection to a validated public address
  to close DNS-rebinding TOCTOU.

## Review findings incorporated

1. The original external attestation did not bind a subscription round. The
   wire contract now signs both identifiers; cross-round and selectively
   refreshed evidence tests fail closed.
2. Resolve-before and resolve-after checks did not prevent a resolver from
   changing only during connect. HTTPS now connects to the selected validated
   address while retaining SNI and hostname verification for the configured
   authority, with environment proxies disabled.
3. Multiple sufficient certificates for the same result could occupy the
   ready queue. The journal retains audit copies but exposes one deterministic
   ready pointer per `(subscription, round, resultId)`.
4. Terminal cleanup originally iterated only the active-round index and could
   never see completed rounds. The runtime now enumerates bounded retained
   journal round identities and removes terminal material.
5. Runtime shutdown could close providers and the ledger after a fixed wait
   while a provider callback still ran. Close now fences provider disposal on
   worker termination and preserves interruption.
6. A heavily backlogged due item could open after its nominal lifetime and
   construct an invalid round. Its absolute maximum is now deterministically
   clamped to at least the opening height, producing expiry rather than a
   divergent exception.
7. The first storage layout inserted observation column families ahead of the
   retained JMT/snapshot handles. They are now strictly appended as handles 18
   and 19, preserving every pre-ADR-037 handle index and upgrade compatibility.
8. Duplicate inbound reports/certificates triggered immediate re-diffusion,
   allowing echo loops. Only newly retained objects trigger immediate sends;
   periodic re-diffusion remains the recovery mechanism. Coordinator submissions
   now have a fixed admission bound.
9. A crash after invoking a signer but before storing its report could permit a
   different value on restart. Canonical candidate bytes are now synchronously
   pinned before signing; restart resumes those exact bytes without reacquisition.
10. The networked subsystem test exposed indexing/startup/plugin wrappers that
    forwarded only legacy state-machine callbacks. All wrappers now explicitly
    forward observation-aware apply/effect callbacks and observation results.
    The test queries committed application state after actual threshold finality.
11. Built-in evidence verification now rejects freshness outside the round's
    logical window, including correctly signed but stale or future attestations.
    The raw adapter pins freshness to the due anchor. IPv6 translation/tunneling
    addresses are rejected, and a total socket-exchange deadline prevents a
    slow response from extending the per-read timeout indefinitely.

## Verification

Focused automated coverage includes:

- one-shot create/open/certify/callback and canonical result layout;
- inclusive report deadline and result grace followed by deterministic expiry;
- cancellation idempotence and absence of a cancellation callback;
- malformed-old versus canonical-stale behavior and conflicting certificates;
- durable signing lock, copied-owner rejection, restart restoration,
  equivocation rejection, transient acquisition failure, and terminal pruning;
- forged, cross-round, and selectively refreshed external attestations;
- private, loopback, link-local, carrier-grade NAT, metadata, IPv6 loopback,
  and IPv6 ULA endpoint rejection;
- plugin catalog and packaged/native conformance; and
- a deterministic three-node showcase: partitioned nodes cannot meet the
  threshold, differently ordered reports converge on one result ID after the
  partition heals, and a certificate supplied by a rotated proposer commits
  identical state roots on all nodes.
- a separate three-node network test with actual subsystem startup, peer
  transport, PREPARE/COMMIT signatures, observation callback forwarding, and
  committed-state queries; and
- an opt-in network test acquiring an immutable public GitHub file through the
  real restricted HTTPS adapter (`YANO_OBSERVATION_HTTPS_LIVE=1`).

Commands used during the milestone:

```text
./gradlew :core-api:test :plugin-catalog:test :runtime:test --tests '*Observation*'
./gradlew :appchain-plugin-conformance:test
YANO_OBSERVATION_HTTPS_LIVE=1 ./gradlew :runtime:test --tests '*ObservationRuntimeClusterTest'
./gradlew test
git diff --check
```

The full repository `./gradlew test` passed on 2026-09-05 in 11m 43s
(124 actionable tasks). The staged whitespace check also passed.
Final lifecycle review additionally ensures provider cleanup when runtime
construction fails, preserving the original failure with suppressed cleanup errors.

On 2026-09-05 the focused core/runtime/catalog/conformance command passed.
The opt-in three-node network qualification passed all four cluster tests,
including raw HTTPS acquisition with hostname-verified TLS to a validated
public address and actual two-of-three app-block finality. The public fixture
is pinned to Yano commit `ed5de419da68b524bdad2a3848c3156c419d0476`.
Use `--rerun-tasks` when toggling the live-test environment variable so an
earlier cached skipped run cannot satisfy this manual qualification gate.

## Deliberate Phase 1 limits

- Only one-shot `APP_HEIGHT` subscriptions are admitted. Phase 1 requires the
  report deadline and subscription expiry anchor to match because the frozen
  subscription record has one retained expiry field. Recurrence and distinct
  round deadlines are Phase 2 work.
- Phase 1 accepts only active-member exact reconciliation. External reporter
  sets, closure-certified aggregation, and source-diversity arithmetic remain
  Phase 3 work.
- Timeouts and invalid/unavailable acquisition remain local diagnostics and do
  not become signed negative reports.
