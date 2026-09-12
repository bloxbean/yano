---
title: "Mempool-aware UTxOs"
description: "Understand unconfirmed transactions and spendable-output views."
sidebar:
  order: 5
---

The following endpoints accept `include_mempool=true` (default: `false`):

- `/api/v1/addresses/{address}/utxos`
- `/api/v1/addresses/{address}/utxos/{asset}`
- `/api/v1/credentials/{paymentCredential}/utxos`
- `/api/v1/utxos/{txHash}/{index}` (existing single-output lookup)

For example:

```sh
curl 'http://localhost:7070/api/v1/addresses/ADDRESS/utxos?include_mempool=true&page=1&count=20&order=asc'
```

Listings exclude outputs consumed by pending transactions and include matching
unspent pending outputs (including change). Intermediate outputs already spent
by pending children are excluded. Address listings also support the existing
`use_payment_credential=true` selector. Credential queries accept a payment hash
or an address, as before. Asset matching, deduplication and ordering happen
before pagination, so excluded outputs do not leave holes in pages.

Ascending overlay order is confirmed outputs by slot, transaction hash, output
index, followed by pending outputs ordered by hash and index. `order=desc`
reverses that order. Pending outputs have `block: null`, using the existing
single-output mempool DTO representation; they are not confirmed balances.
Requests without the flag retain existing confirmed-only behavior, except that
all three listing endpoints now reject `count > 100` with HTTP 400.

Overlay queries walk one snapshot-backed iterator (forward or reverse), stopping
as soon as the page is full. Point lookups for deduplication use the same snapshot.
No re-paging or whole-subject materialization occurs. Memory holds one decoded
confirmed output, at most 100 result outputs, and a bounded mempool snapshot.
Deep pages and selective filters still scan earlier rows, but never re-scan them
within a request. There is no new persistent index or sync-write overhead.

Resource limits are deliberately conservative:

- At most two concurrent overlay queries and two open storage read views per store.
- At most 100,000 scanned confirmed index rows and a five-second cooperative read deadline.
- Mempool capture refuses pools with more than 100,000 produced outputs or spent entries.
- At most 1,000 matching pending outputs and 8 MiB of their source transaction bytes
  (counted conservatively per selected output); this is not a measurement of Java heap bytes.

Subject hashes are computed at transaction projection, not per query. Payloads
are copied only for matching outputs and only after releasing the admission lock.
Busy admission, query saturation, time/work/snapshot limits, unavailable storage,
or a canonical-hash change before response completion return HTTP 503, never a
silently truncated successful page. Retry with backoff; use smaller pages where
applicable. A subject exceeding snapshot limits needs confirmation/eviction before
overlay queries can succeed. These bounds limit query amplification; they do not
constitute a measured whole-node native 1.5 GB heap guarantee.

Results are transient, not a reservation; chain and mempool changes can shift
pagination between requests. Wallets
must still reserve their own in-flight inputs and handle submission conflicts.
Unsupported overlay listings return HTTP 503 rather than silently falling back
to confirmed-only results. Eviction and relay behavior are unchanged.
