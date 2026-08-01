# Message Submission over HTTP

Every app chain uses the same public submission endpoint. The chain ID selects
the state machine; `topic` selects a command route; `body` carries UTF-8 text
and `bodyHex` carries canonical binary bytes.

```text
POST /api/v1/app-chain/chains/{chainId}/messages
```

The node returns HTTP `202` with `messageId`, `chainId`, and `topic`. HTTP `429`
means the pending pool applied backpressure and did not retain the message;
HTTP `503` means the chain is not currently accepting submissions. A client
should retry those responses with bounded exponential backoff and should keep
the returned message ID for finality/proof checks.

## Plain ordered-log message

```bash
BASE=http://127.0.0.1:7070

RESPONSE="$(curl -fsS -X POST \
  "$BASE/api/v1/app-chain/chains/orders-chain/messages" \
  -H 'Content-Type: application/json' \
  -d '{"topic":"orders.command.v1","body":"{\"order\":\"A-100\",\"event\":\"created\"}"}')"
MESSAGE_ID="$(printf '%s' "$RESPONSE" | jq -r .messageId)"
printf '%s\n' "$RESPONSE" | jq .
```

Wait for member-finalized inclusion and retrieve its MPF proof:

```bash
until FINALIZED="$(curl -fsS \
  "$BASE/api/v1/app-chain/chains/orders-chain/messages/$MESSAGE_ID" 2>/dev/null)"; do
  sleep 1
done
printf '%s\n' "$FINALIZED" | jq '{messageId,height,index,topic,bodyHex}'

curl -fsS \
  "$BASE/api/v1/app-chain/chains/orders-chain/proof/$MESSAGE_ID" | jq .
```

The packaged helper is the same operation, kept deliberately visible:

```bash
./demos/submit-message-curl.sh "$BASE" orders-chain orders.command.v1 text \
  '{"order":"A-101","event":"created"}'
```

## Binary commands for stateful machines

Stateful built-in machines accept deterministic CBOR command bytes. The
showcase codec creates those bytes; curl still performs the real submission.
It uses only Python's standard library.

```bash
BODY_HEX="$(python3 ./tools/showcase_codec.py kv put product:A-100 active)"

./demos/submit-message-curl.sh "$BASE" registry-chain kv.command.v1 hex \
  "$BODY_HEX"
```

The common light-profile routes are:

| Chain | Topic | Body |
|---|---|---|
| `orders-chain` | `orders.command.v1` | arbitrary UTF-8 or bytes |
| `registry-chain` | `kv.command.v1` | KV command CBOR |
| `approvals-chain` | `approvals.command.v1` | proposal/approval CBOR |
| `balances-chain` | `balances.command.v1` | mint/transfer CBOR |
| `documents-chain` | `doc-trail.command.v1` | document-trail CBOR |
| `workflow-chain` | `orders.command.v1`, `approvals.command.v1`, `showcase.release.v1` | composite component commands |
| `roles-chain` | `actors.command.v1` and role-policy topics | role workflow commands |
| `payments-chain` | `eutxo.transactions` | signed virtual EUTxO transaction bytes |

The application should normally use `appchain-client` or its own typed codec
rather than invoke the showcase codec. The wire contract remains this same
HTTP endpoint.

## API keys

The local showcase intentionally starts with broad API authentication disabled,
so reads and submissions need no key. Privileged admin operations still require
the configured unscoped local-demo key. It is known demo material and must not
be used on a public interface.

In an application deployment, enable whole-surface authentication and inject
keys through the deployment's secret mechanism:

```properties
yano.app-chain.api.auth.enabled=true
yano.app-chain.api.keys=full-secret,orders-secret=orders.command.v1
```

An unscoped key has full access. A `key=topicA|topicB` entry may read and submit
only those topics; it cannot call privileged admin/effect operations. Send the
selected key without printing it:

```bash
export YANO_APP_CHAIN_API_KEY='replace-through-your-secret-manager'
./demos/submit-message-curl.sh "$BASE" orders-chain orders.command.v1 text demo
```

The script adds `X-API-Key` only when that environment variable is set. Do not
put production keys in YAML, shell history, demo documentation, or URLs.
