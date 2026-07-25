# ADR-UTXO-004: Capability-Gated EUTxO Console Explorer

- Status: Implemented
- Version: v2
- Date: 2026-07-25
- Owners: App-chain / EUTxO / Console
- Related: ADR-UTXO-001, ADR-022, ADR-028

## 1. Context

The generic app-chain console correctly treats message bodies as opaque bytes.
For an EUTxO chain this makes otherwise familiar Cardano-shaped transactions
appear only as CBOR. Users need to see inputs, outputs, addresses, lovelace,
authorization type, acceptance status, deposit/withdrawal relationships, and
proof identity. They must also be able to find a transaction by either its L2
transaction ID or app-chain message ID.

Embedding EUTxO-specific parsing into the generic page would couple the console
to one state machine. Loading executable JavaScript from arbitrary plugins
would weaken the console's CSP and supply-chain boundary.

## 2. Decision

### 2.1 Preserve the generic console

The existing app-chain status page remains the generic operational view. When
the selected chain reports the stock `eutxo-ledger` state-machine identity, it
shows one additional link to an EUTxO Explorer. Other state machines receive no
layout or behavior changes.

The existing message dialog may show a `Decoded transaction` link only when a
known decoder/API is available. Raw bounded hexadecimal bytes remain available.

### 2.2 Server-owned decoding and finalized projection

The EUTxO state machine creates a bounded deterministic transaction summary for
every finalized attempt. Accepted summaries include:

- transaction and app-message IDs;
- status, code, block height, ordinal, and L1 slot;
- direct Cardano or Jubjub authorization profile;
- resolved input outpoints, owners, and lovelace;
- created output outpoints, recipients, and lovelace.

Withdrawal markers, credential/key-epoch details, and deposit, proof, or claim
cross-links are safe follow-on projections. Existing typed bridge and validity
queries remain authoritative for those records.

The state machine stores:

- summary by transaction ID;
- summary by app-message ID; and
- a monotonic finalized summary index for newest-first bounded pagination.

The projection is part of committed app state, inherits snapshot/restart/
rollback behavior, and is covered by MPF proofs. The page never scans the
generic message ledger or implements Cardano/ZK CBOR parsing in JavaScript.

Rejected attempts retain only safe decoded fields that were deterministically
available; malformed payloads never cause unbounded parsing or error disclosure.

### 2.3 Bundle-owned read-only domain API

The EUTxO plugin contributes a read-only domain API beneath its manifested
bundle namespace:

```text
GET /api/v1/plugins/com.bloxbean.cardano.yano.appchain.eutxo/transactions
GET /api/v1/plugins/com.bloxbean.cardano.yano.appchain.eutxo/transactions/{transactionId}
GET /api/v1/plugins/com.bloxbean.cardano.yano.appchain.eutxo/messages/{messageId}
```

`chain`, `limit`, and `before` are bounded query parameters. Responses include
the chain ID, state-machine ID, committed height, state root, and bounded
projection. Query identity mismatches and corrupt records fail closed. The
host continues to own authentication, routing, size bounds, error redaction,
and CSP.

### 2.4 Data-only console contribution

The first implementation is a console-owned built-in EUTxO explorer selected
by discovered state-machine capability and domain-API availability. No plugin
JavaScript is executed.

The reusable extension contract is data-only:

```text
capability/state-machine identity
domain API identity and version
display labels
supported stock view type
```

Future stock/custom state machines can select reviewed console-owned view types.
Specialized custom applications remain free to ship a separate UI.

### 2.5 Explorer experience

The explorer provides:

- chain/profile/state-root header;
- newest-first recent transaction table;
- accepted/rejected status and direct/ZK authorization badges;
- search accepting transaction ID or message ID;
- decoded input/output and value details;
- full identifiers with browser-native selection and copy behavior; and
- raw identifiers without exposing secrets or full witness signatures.

The list is bounded to 50 records per request and defaults to 20. The API
accepts a newest-before index for bounded pagination; the unified console
requests 20 records at a time. No browser-side historical database is created.

## 3. Safety and invariants

- No arbitrary plugin HTML, script, style, or executable descriptor.
- No secret, full private witness, or unbounded datum rendering.
- JSON is generated from typed contracts with correct escaping.
- Every response is rooted at one committed height/state root.
- Search IDs are canonical lowercase 32-byte hex.
- Unknown state machines and missing APIs degrade to the unchanged generic UI.
- Projection writes are deterministic for multiple messages in one block.

## 4. Milestones and acceptance

### UI-E1 — Typed projection

- Add canonical summary/page contracts and state keys.
- Project direct and Jubjub transactions, withdrawals, and rejections.
- Test multi-message blocks, restart, snapshot, and proof availability.

### UI-E2 — Domain API

- Add manifested provider and read-only routes.
- Test transaction/message lookup, pagination, identity mismatch, malformed
  IDs, corrupt payloads, and response bounds.

### UI-E3 — Explorer

- Add the CSP-safe Svelte console route and capability-gated generic-console
  link.
- Add responsive table/detail/search/copy behavior.
- Preserve generic page tests for non-EUTxO chains.

### UI-E4 — Acceptance

- Exercise direct and ZK multi-user transactions.
- Find each by transaction ID and message ID.
- Confirm decoded Alice/Bob inputs, outputs, amount, authorization, withdrawal,
  and root on a packaged JVM distribution.

## 5. Consequences

Users receive an out-of-box EUTxO explorer without turning the generic console
into a state-machine-specific application. Committed summaries increase state
size by a bounded amount per finalized attempt; retention/pruning may be added
later as a separately versioned consensus rule. Custom UI executable code
remains outside the trusted embedded console.

## 6. Implementation notes

The explorer is a built-in route in the unified Svelte console and is served
at `/ui/app-chain/eutxo/`. The stock app-chain page shows its link only when
runtime status reports `eutxo-ledger`; the route also verifies that identity
before querying the bundle-owned domain API. Summary state uses one
block-local monotonic counter, so multiple messages finalized in one block
receive deterministic, collision-free indexes.
