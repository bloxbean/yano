# Local mempool administration

Manual eviction is a recovery tool, not transaction cancellation. Check the chain
and upstream node before evicting a transaction that appears stuck.

The endpoint is disabled by default, including in the wallet profile. Enable it
explicitly and configure a dedicated secret (prefer environment configuration):

```sh
export YANO_MEMPOOL_ADMIN_ENABLED=true
export YANO_MEMPOOL_ADMIN_API_KEY="$(openssl rand -hex 32)"
export QUARKUS_HTTP_HOST=127.0.0.1
```

The corresponding properties are `yano.mempool.admin.enabled` and
`yano.mempool.admin.api-key`. Both enablement and a nonblank key are required;
keys must be at least 32 characters; generate a random secret, not a password.
Enabling without a sufficiently long key fails closed. The host setting above
binds the whole HTTP server to loopback for a locally managed wallet node. The
wallet launcher should set it explicitly. For remote administration use HTTPS; never transmit the key over
unencrypted public HTTP. Do not expose this key to untrusted wallet dApps.

```sh
curl -X DELETE \
  -H "X-Admin-API-Key: $YANO_MEMPOOL_ADMIN_API_KEY" \
  http://localhost:7070/api/v1/admin/mempool/transactions/TRANSACTION_HASH
```

A successful response contains `txHash`, `evictedTxHashes` (including pending
descendants), and a `warning`. An absent transaction returns HTTP 200 with an
empty list, so repeated requests are safe. Disabled: 404; missing/wrong key: 401;
invalid hash: 400; missing/short configured key, busy admission, or unavailable runtime: 503.
Eviction fails fast when either admission lock is busy; retry with backoff.

Eviction atomically removes the transaction and its dependent pending
transactions from the local mempool, releasing their input reservations and
cleaning their output, reference-script, and dependency indexes. Successful
administrative requests are logged with the requested hash, total count and up to ten evicted hashes, never
the key. It does not alter canonical ledger state or wallet indexes.

**This does not cancel a transaction on the network.** The upstream submission
API has no cancellation hook: queued/in-flight submissions and transactions
already held by peers may still propagate and confirm. A peer may announce an
evicted transaction again and it can be readmitted. Eviction is not a blacklist.
Eviction also cannot recall a transaction already selected for a locally produced block.
Reusing released inputs creates a competing spend, not a guaranteed replacement.
Automatic confirmation, conflict, expiry, and retention cleanup remain unchanged.
