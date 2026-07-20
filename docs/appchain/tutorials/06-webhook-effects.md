# Tutorial 6 — Invoke an External HTTP Endpoint Safely

[Open the effects-ready workflow in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=role-evidence&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=webhook-effects&chainId=webhook-effects)

- **Level:** intermediate to advanced
- **Outcome:** understand when to use a finalized-block webhook sink versus an
  acknowledged `webhook.post` effect, and run the latter from a stock approval
  transition.

Yano provides two HTTP delivery shapes with different guarantees.

| Capability | Use it for | Result committed to app state? |
|---|---|---:|
| Finalized-block webhook sink | Projection/indexing of every finalized block | No |
| `webhook.post` effect | One business action after a deterministic transition | Yes, for `CHAIN` results |

## Option A — observe every finalized block

Configure a cursor-backed sink:

```yaml
yano.app-chain.webhooks: https://projection.example/yano/finalized-blocks
```

Delivery is ordered and at-least-once. A persistent sink cursor resumes after
restart. This is the simplest option when the receiver wants all finalized
history and does not need a per-action result fed back into the state machine.

## Option B — execute one acknowledged action

The built-in executor supports `webhook.post`:

```yaml
yano.app-chain.effects.executors.webhook.url: https://erp.example/hooks/yano
yano.app-chain.effects.executors.webhook.timeout-ms: 10000
```

It sends:

- `Idempotency-Key`: deterministic effect-id hash;
- `X-App-Chain-Id`;
- `X-Effect-Id`, `X-Effect-Type`, and `X-Effect-Scope`.

Response semantics are:

- `2xx` → confirmed;
- `4xx` → failed without automatic retry; and
- `5xx` or transport failure → retryable with bounded backoff/parking.

The receiver must deduplicate by `Idempotency-Key`, because execution is
at-least-once even though outcome incorporation is exactly once.

## Runnable local approval-to-webhook exercise

The stock `approvals` machine can emit a configured effect when a proposal
reaches its threshold. This avoids writing a custom state machine for the
tutorial.

### 1. Start a receiver in a separate terminal

```bash
python3 - <<'PY'
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

seen = set()

class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        key = self.headers.get("Idempotency-Key", "")
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        duplicate = key in seen
        seen.add(key)
        print({"idempotencyKey": key, "duplicate": duplicate,
               "contentType": self.headers.get("Content-Type"),
               "body": body.decode("utf-8", errors="replace")}, flush=True)
        self.send_response(200)
        self.send_header("Location", f"local://receipt/{key}")
        self.end_headers()

    def log_message(self, *_):
        pass

ThreadingHTTPServer(("127.0.0.1", 8099), Receiver).serve_forever()
PY
```

### 2. Create private per-node overrides

Start from a clean tutorial cluster and create three owner-only property files.
All members receive the same consensus settings; only node 0 runs the
executor. Each file must begin with `config_ordinal=275`.

These are private overlays consumed only by the local cluster launcher through
`--node-config-dir`; they are not generated-project configuration. Generated
app-chain projects and `appchain config` use YAML.

Common settings for `node0.properties`, `node1.properties`, and
`node2.properties`:

```properties
config_ordinal=275
yano.app-chain.chains[0].state-machine=approvals
yano.app-chain.chains[0].effects.enabled=true
yano.app-chain.chains[0].machines.approvals.payments=true
yano.app-chain.chains[0].machines.approvals.activations.payments=1
yano.app-chain.chains[0].machines.approvals.payment-type=webhook.post
yano.app-chain.chains[0].machines.approvals.payment-gate=app-final
```

Append these node-local settings only to `node0.properties`:

```properties
yano.app-chain.chains[0].effects.executor.enabled=true
yano.app-chain.chains[0].effects.executor.types=webhook.post
yano.app-chain.chains[0].effects.executors.webhook.url=http://127.0.0.1:8099/yano
yano.app-chain.chains[0].effects.executors.webhook.timeout-ms=5000
```

The repository includes those exact tutorial files. Install owner-only copies
and start the fresh cluster:

```bash
install -d -m 700 /tmp/yano-tutorial-webhook-config
install -m 600 \
  ../docs/appchain/tutorials/config/webhook/node*.properties \
  /tmp/yano-tutorial-webhook-config/

export YANO_CLUSTER_DIR=/tmp/yano-tutorial-webhook
export YANO_CLUSTER_NODE_CONFIG_DIR=/tmp/yano-tutorial-webhook-config
./yano.sh appchain cluster start 3
```

The chain id remains `orders-chain`, but its fresh deterministic profile is
now `approvals`. Never apply this override to retained `ordered-log` state.

### 3. Encode and submit a proposal

The encoding helper and request fixture are source-checkout tutorial assets.
From the source checkout's `app/` directory, encode the supplied HTTP body and
canonical stock commands:

```bash
TOOL=../docs/appchain/tutorials/tools/stdlib_command.py
WEBHOOK_HEX=$(python3 "$TOOL" webhook \
  --body-file ../docs/appchain/tutorials/config/webhook/request.json \
  --content-type application/json)

PROPOSE_HEX=$(python3 "$TOOL" approvals propose erp-release-001 \
  --required 2 --payload-hex "$WEBHOOK_HEX")
APPROVE_HEX=$(python3 "$TOOL" approvals approve erp-release-001)

curl -s -X POST \
  http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$PROPOSE_HEX\"}" | jq .

curl -s -X POST \
  http://127.0.0.1:7071/api/v1/app-chain/chains/orders-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$APPROVE_HEX\"}" | jq .

curl -s -X POST \
  http://127.0.0.1:7072/api/v1/app-chain/chains/orders-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$APPROVE_HEX\"}" | jq .
```

After finality and the executor tick, the receiver prints exactly one logical
effect identity. A crash at the acknowledgement boundary may produce another
physical POST with the same idempotency key; that is why the receiver keeps a
deduplication set.

Inspect effect records and execution status:

```bash
sleep 6
curl -s \
  -H 'X-API-Key: yano-local-cluster-full-key' \
  'http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/effects?fromHeight=1&limit=20' \
  | jq .

curl -s \
  http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/status \
  | jq '.effects.executor | {executed, openOnChain, executionTotals, executorOperations}'
```

The tested success shape has one confirmed execution and zero open on-chain
effects after the `~fx/result` message is incorporated.

### 4. Clean up

```bash
./yano.sh appchain cluster clean
unset YANO_CLUSTER_DIR YANO_CLUSTER_NODE_CONFIG_DIR
rm -rf /tmp/yano-tutorial-webhook-config
```

Stop the receiver with `Ctrl-C`. Remove the private override directory after
the cluster is stopped.

## Security and product boundary

`webhook.post` is intentionally small: one POST to a configured endpoint. It
does not yet provide named target aliases, OAuth/API-key/mTLS profiles,
arbitrary methods, response-body contracts, or asynchronous operation polling.
Keep `allow-payload-url=false` unless a separately reviewed allow-list and SSRF
boundary exists.

For a product API such as uVerify, prefer a dedicated executor when durable
acceptance requires several API calls, polling, reconciliation, or a typed
receipt. Use the generic webhook only when one idempotent POST and its immediate
status are the real business contract.

## Go deeper

- Read [Effects §18](../../APP_CHAIN_USER_GUIDE.md) for quarantine, requeue,
  cancellation, proof, and external executor APIs.
- Kill the receiver temporarily, observe retries/parking, restore it, and use
  the operator requeue endpoint.
- Build a custom executor plugin with named target aliases and secret-backed
  authentication rather than putting URLs or credentials in replicated
  payloads.
