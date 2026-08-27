# ADR-046: Serve Epoch Projection Artifacts Through Archive-Aware APIs

## Status

Proposed — implementation deferred

## Date

2026-08-27

## Related decisions

- [ADR-039](in-progress/039-canonical-projection-outbox.md) — canonical
  projection outbox, artifact identity and archive queryability.
- [ADR-044](044-selective-epoch-artifact-projection.md) — explicit selection
  of epoch artifacts.
- [ADR-045](045-epoch-artifact-gaps-and-resumable-capture.md) — durable gaps,
  dataset-local continuation and honest partial coverage.

## Context

ADR-044 lets an operator select which epoch artifacts the projection captures:

- `REWARD`;
- `EPOCH_STAKE`;
- `ADA_POT`;
- `DREP_DISTRIBUTION`; and
- `GOVERNANCE_PROPOSAL_STATUS`.

Capture selection alone does not make those rows available through the public
HTTP API. The current endpoint surface is a mixture of one archive-routed
adapter, several live RocksDB reads and one dataset with no historical endpoint.
That distinction is not visible from the endpoint names.

With the default `/api/v1` prefix, the current state is:

| Dataset | Existing HTTP surface | Current source and limitation |
|---|---|---|
| `REWARD` | `GET /accounts/{stakeAddress}/rewards` | Routes through `ArchiveAccountHistoryProvider`, but projection-written epoch artifacts publish no epoch coverage understood by the generic repository. The read therefore fails closed with incomplete coverage. |
| `EPOCH_STAKE` | `GET /accounts/{stakeAddress}/stake[/{epoch}]`; `GET /epochs/latest/stake/total`; `GET /epochs/{epoch}/stake/total`; `GET /epochs/{epoch}/stakes/{poolId}`; `GET /epochs/{epoch}/stake/pool/{poolId}` | Reads `AccountStateReadStore`, not the archive. Availability is bounded by live snapshot retention and is independent of artifact selection. |
| `ADA_POT` | `GET /epochs/latest/adapot`; `GET /epochs/{epoch}/adapot`; `GET /epochs/adapots` | Reads `LedgerStateProvider`, not the archive. Long live-store retention makes this appear historical but does not provide an archive coverage contract. |
| `DREP_DISTRIBUTION` | `GET /governance/dreps/{drepId}/distribution[/{epoch}]` | Reads the live account-state store and exposes only amount. It omits the archived expiry, activity and boundary-observation fields and cannot list a complete epoch distribution. |
| `GOVERNANCE_PROPOSAL_STATUS` | No historical endpoint | Current proposal and vote endpoints expose mutable governance state, not per-epoch proposal-status observations. |

The operational endpoints `GET /history/coverage` and
`GET /history/watermark` report archive metadata; they do not return artifact
rows.

All five datasets have archive schemas and generic repository support through
`ArchiveRepositorySet.records(dataset)`. The missing layer is an API-facing,
coverage-aware query facade and DTO mapping for epoch artifacts.

### Epoch coverage is a prerequisite

The generic archive repository accepts an `EpochRange` and reports whether that
range is complete. The canonical projection currently derives fallback coverage
from block receipts only. It deliberately leaves epoch coverage empty because a
block receipt alone does not prove which epoch artifacts were present, absent or
lost.

Consequently, merely routing the existing endpoints to DuckLake would not fix
the API. It would either continue to fail every projection-only epoch query or,
if the completeness check were bypassed, risk presenting an uncaptured artifact
as an empty result.

Epoch queryability must be derived from acknowledged artifact identity and
dataset-specific epoch evidence. ADR-045 additionally requires explicit gaps;
a range crossing a known gap cannot be advertised as complete.

## Constraints

- An unselected artifact must be reported as dataset-level `NOT_SELECTED`, not
  as an empty result. `NOT_PROJECTED` is reserved for epochs before a selected
  dataset's ADR-044 `projectedFromEpoch`.
- An incomplete, pending or gapped epoch must never be converted into `404` or
  an empty list that implies a complete negative answer.
- Existing endpoint contracts should remain stable where they already express
  the complete dataset semantics.
- A live-state fallback must not hide an archive gap or extend historical
  retention accidentally.
- Latest/current-state queries may continue to use the live store when the
  archive has not yet finalized the current epoch, but that behavior must be
  explicit and deterministic.
- Pagination must run in the archive repository. The API must not load a whole
  epoch distribution and paginate it in memory.
- Rollback and restart must not expose rows beyond the canonical acknowledged
  artifact point.
- API behavior must be driven by selected artifact identity and semantic epoch
  coverage, with no network-specific epochs or magic values.
- Core epoch processing and live ledger query interfaces remain unchanged.

## Proposed direction

This ADR reserves the work as a separate API/archive change. Exact resource
paths and response compatibility will be finalized before implementation.

### 1. Publish dataset-specific epoch coverage

Introduce a durable coverage view for selected epoch artifacts. For each
dataset it must distinguish at least:

- `NOT_SELECTED` for a dataset that is not enrolled;
- `NOT_PROJECTED` for the historical prefix before enrollment;
- complete acknowledged epochs or contiguous ranges;
- pending captured epochs that are not queryable yet;
- known gaps from ADR-045;
- the last canonical boundary point supporting each completed epoch.

Coverage must come from the artifact acknowledgement lifecycle, not be inferred
from table row counts. Zero rows can be a valid complete artifact.

The generic epoch repository and `/history/coverage` must consume the same
coverage authority so operational status and data reads cannot disagree.

This preflight is load-bearing for ADR-044 prospective enrollment and is not
deferred with the richer DTO work below. Before any table lookup or live-store
fallback, reads must apply this order:

1. a dataset outside the persisted selection returns `NOT_SELECTED`;
2. an epoch below `projectedFromEpoch` returns `NOT_PROJECTED`;
3. a pending, legacy-unknown or ADR-045 gap range returns its distinct incomplete
   outcome; and
4. only a proven `COMPLETE` range may read rows, including a valid zero-row
   result.

The ADR-044 implementation must install this coverage authority in the generic
repository and existing reward archive route even though the final HTTP error
body and richer stake/governance routes remain deferred. A prospective join
without this check is not complete.

### 2. Add an epoch-artifact query facade

Add typed application-facing readers over the archive repositories for reward,
epoch stake, AdaPot, DRep distribution and proposal-status observations. The
facade must:

- check that the artifact is selected;
- validate requested epoch coverage before reading rows;
- preserve a pinned archive generation for coverage and data;
- return explicit unavailable/incomplete/gap outcomes; and
- expose bounded, stable archive pagination.

HTTP resources should not query DuckLake tables directly.

### 3. Reuse existing semantic endpoints where possible

The existing reward, epoch-stake, AdaPot and DRep-distribution paths are the
natural public surface for the portions of the archive rows they already model.
They should become coverage-aware instead of introducing duplicate
`/history/...` data endpoints solely to select a storage backend.

For an explicitly historical epoch:

1. use the archive when that dataset/epoch is proven complete;
2. report a distinct incomplete or gap response when capture was selected but
   coverage is not complete; and
3. do not silently fall back to a retained RocksDB generation.

For `latest`, the live store may serve the current ledger view until an archive
boundary is acknowledged. The response contract or metadata must make clear
whether the value is current/live or finalized/archived if that distinction can
change its semantics.

### 4. Add endpoints where the existing shape is incomplete

New API surface is required for at least:

- a complete DRep distribution for an epoch, including archived status/expiry
  fields and paginated credential rows; and
- governance proposal-status observations by proposal and/or epoch, including
  observation phase, status, decision reason and boundary identity.

The exact paths and DTOs are deliberately deferred. They should be reviewed
against the API's existing Blockfrost-compatible shapes before being fixed as a
public contract.

The current per-DRep amount endpoint may be backed by the archive without
exposing every archived column, but that does not replace the complete dataset
endpoint.

### 5. Define honest HTTP outcomes

Before implementation, standardize responses for:

- archive or dataset not configured;
- dataset selected but not ready;
- epoch pending finalization;
- known permanent gap;
- requested epoch outside captured coverage; and
- complete query with genuinely no rows.

These outcomes must be distinguishable by clients. Status codes and a stable
error body are deferred, but ordinary `200 []` is permitted only for the last
case.

## Alternatives considered

### Keep serving live state only

Rejected as the long-term answer. It makes archival selection unrelated to API
availability and loses old epoch snapshots according to live-store retention.

### Add a generic SQL-like archive endpoint

Rejected. It would expose physical schema details, weaken API compatibility and
push coverage interpretation onto every client.

### Route to the archive and ignore coverage

Rejected. Missing evidence and a legitimate empty artifact would become
indistinguishable.

### Always prefer live RocksDB when it has the requested epoch

Rejected for historical reads. It could make a gapped archive appear healthy on
one node while returning incomplete on another with different retention.

### Create duplicate `/history` endpoints for all five datasets

Deferred rather than selected. Separate paths would make storage provenance
obvious, but duplicate already-public stake, AdaPot and DRep semantics. The API
review should prefer semantic resources unless compatibility requires an
explicit finalized-history namespace.

## Consequences

Positive:

- Epoch artifacts selected by ADR-044 become useful through public APIs.
- API answers share the same completeness and gap authority as archive status.
- Existing historical-looking endpoints no longer depend accidentally on live
  RocksDB retention.
- Empty results remain distinguishable from unavailable evidence.

Tradeoffs:

- Epoch coverage and gap representation must be implemented before archive API
  routing.
- Some current endpoints will gain explicit unavailable/incomplete outcomes.
- New DTOs and pagination contracts are needed for the richer governance
  datasets.
- Supporting a live `latest` view and finalized history requires a clearly
  documented routing boundary.

## Work deferred for the implementation ADR revision

1. Integrate ADR-044 enrollment and ADR-045 outcomes with the durable
   epoch-coverage representation fixed by those ADRs.
2. Define the query-facade interfaces and archive repository filters/orderings.
3. Decide final HTTP paths, DTOs, pagination and error status codes.
4. Specify the live-versus-archive routing rule for `latest` and explicit epochs.
5. Add archive-backed resource tests for every selected/unselected, pending,
   complete, empty and gapped state.
6. Add rollback/restart tests proving that API visibility never exceeds the
   canonical acknowledged artifact point.
7. Document the distinction between operational coverage endpoints and artifact
   data endpoints.

## Verification requirements

- A complete zero-row epoch returns success with an empty result.
- `NOT_SELECTED`, pre-enrollment `NOT_PROJECTED`, selected-but-pending and `GAP`
  produce different machine-readable outcomes.
- A query crossing a known gap never returns partial success as complete.
- Reward history works against canonical projection output without legacy
  worker coverage.
- Historical epoch stake and AdaPot remain queryable after their corresponding
  live generations are unavailable.
- DRep history exposes both amount and the archived boundary status fields.
- Proposal status can be queried across epochs, including terminal observations
  no longer present in mutable governance state.
- Kill/restart and exact-point rollback preserve the same answers and coverage.
- The behavior is identical on mainnet, preprod, preview and custom devnets.
