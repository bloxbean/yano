# Submitting app-chain messages

Submit ordinary commands with `POST /api/v1/app-chain/chains/{chainId}/messages`
(under the configured API prefix), or Java `AppChainGateway.submit(topic, body)`.
The request accepts `topic` and either text `body` or encoded `bodyHex`.
Authentication and topic permissions still apply.

| Response | Meaning | Next step |
|---|---|---|
| **202** with `messageId` | Locally admitted, retained, and offered for diffusion | Wait for inclusion and inspect the application's result; this is not finality or business success |
| **400** with `code` (and sometimes `details`) | The selected application rejected local admission before pool retention or relay | Correct the command or applicable configuration before submitting again |
| **429** | The local pending pool is full; the message was not relayed | Retry after backpressure clears |
| **503** | Application admission is unavailable, the chain is stopped/paused, or this node is not a member at the next height (for example a joiner whose membership epoch is scheduled but not yet active) | Investigate node health and operator diagnostics before retrying; a joiner can submit once status `memberActiveForNextBlock` is `true` |

Malformed HTTP input and envelope limits can also produce **400** through the
existing request validation path; these responses need not carry an application
`code`. Declared application reasons use at most 32 ASCII uppercase letters or
underscores. Other reason text is replaced with `APPLICATION_REJECTED`; the
response never includes arbitrary plugin rejection prose or callback exception
text. Symbolic reasons must be public diagnostics, not secrets encoded as codes.

A rejection can also carry structured `details` that name what refused it, for
example a declarative admission rule:

```json
{"code": "ADMISSION_RULE_DENIED",
 "details": {"rule": "transfer-limit", "deny": "TRANSFER_LIMIT_EXCEEDED"}}
```

Only three detail keys exist, each with a fixed grammar: `rule`
(`[a-z][a-z0-9-]{0,62}`), `deny` (`[A-Z][A-Z0-9_]{0,62}`) and `write` (an
integer 0..65535, the index of a refused write in a batch). Unknown keys and
values that fail their grammar are dropped, never echoed, and a reason replaced
by `APPLICATION_REJECTED` keeps no details. The body has no `details` member
when none survived, so it keeps its historical `{"code": ...}` shape. Plugins
supply details with `AdmissionResult.reject(code, details)`; the host never
parses them from the reason text. Details describe local admission only; they
are not a finalized receipt.

## What local admission checks

For **all sequenced machines**, the host calls `validateForBlock` using the next
candidate height and one coherent committed-state snapshot. This uses the
current height-selected application configuration, not the genesis profile.
The callback is read-only, may run concurrently, and must not retain its reader.
Unexpected callback exceptions or a null result produce generic unavailability,
not a malformed-command or business-rejection result.

Explicit diffusion-only configurations do not create an application ledger or
candidate block. They retain the transport-only submission behavior and skip
the application callback. Reserved framework commands have separate privileged
entry points; ordinary submission cannot use `~` topics.

## Accepted is not finalized

Proposal selection checks admission again against its actual predecessor state.
State can change between submission and selection. A message accepted locally
may therefore never be included, and even an included message can fail an
application rule. Check inclusion and the application's receipt/state rather
than treating **202** as success.

A finalized business rejection is different from an early **400**: its message
ID is already consumed. Submit a new signed envelope to retry after correcting
the cause; replaying the old ID does not rerun the command. The host does not
fabricate a finalized receipt for an early local rejection.

See [ADR-008.1](../../adr/app-layer/008.1-iteration1-correctness-operator-safety.md)
for the admission and backpressure contract.

## Operator diagnostics

The Prometheus endpoint `/q/metrics` exposes these per-chain function counters
(Micrometer names use dots and omit `_total`):

| Metric | Tags | Meaning |
|---|---|---|
| `yano_appchain_admission_rejected_total` | `chain`, `code="APPLICATION_REJECTED"` | One increment for each rejected ordinary local application admission, before pool retention or relay |
| `yano_appchain_admission_unavailable_total` | `chain`, `code="CALLBACK_FAILED"` | One increment when the admission callback throws a nonfatal failure or returns null |
| `yano_appchain_admission_unavailable_total` | `chain`, `code="STATE_UNAVAILABLE"` | One increment when sequenced local admission has no ledger available |

Codes are a fixed host-owned vocabulary. All plugin rejection reasons, including
valid symbolic response codes, aggregate into `APPLICATION_REJECTED`; plugin
prose, exception messages, topics, and message IDs never become metric tags.
The counters use runtime-owned atomic totals and the existing cached status
snapshot (approximately one second). Scraping does not invoke admission, and
failed or missing status reads retain the last observed total. Totals live for
the subsystem instance; a node restart resets them. Metrics collection is outside
the submission path and cannot change its result.

These counters exclude proposal-time revalidation, incoming diffusion, privileged
system submission, pool-full rejection, and transport/request validation. Explicit
diffusion-only mode skips local application admission and leaves these totals at
zero. Unavailability is separate from application rejection; these are not counts
of every HTTP 400/503 response (for example, stopped/paused checks and snapshot
capture failures are outside these counters). REST mapping and repeated scrapes
do not count a decision again.

The logger category `org.yanoproject.runtime.appchain.AppChainSubsystem.admission`
records unexpected callback failures at **WARN**, including candidate height,
exception/cause class names, and bounded stack locations. It does not attach the
Throwable or include exception messages, source bodies, authentication proofs,
or keys. REST continues to return only generic unavailability.

**DEBUG** on that category logs declared rejection codes and up to 512 characters
of escaped rejection prose. It is disabled at ordinary INFO/WARN configurations.
Control characters and non-ASCII text are escaped, and long reasons are truncated
to prevent multi-line injection or unbounded output. This is **not redaction**:
application-provided prose may contain sensitive data. Enable it narrowly for
trusted operator diagnosis; do not casually enable, retain, or export these logs.
