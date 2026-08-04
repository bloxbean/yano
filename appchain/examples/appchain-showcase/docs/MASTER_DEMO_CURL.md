# Master Demo Using curl and the Public HTTP API

This version keeps cluster lifecycle behind `showcase.sh`, but submits and
queries application messages through the same public endpoints a real client
uses. There is no showcase-private submission route.

## 1. Start and inspect the cluster

```bash
./showcase.sh doctor --profile light
./showcase.sh quickstart --profile light --instance curl-demo --nodes 3
./showcase.sh config show --instance curl-demo
./showcase.sh ui --instance curl-demo
```

`quickstart` also proves the devnet script anchor. The HTTP ports are
7070–7072; submitting through a port uses that member as the application
sender.

## 2. Define reusable curl helpers

Paste this block in the extracted showcase root:

```bash
HTTP_BASE=7070
CODEC=./tools/showcase_codec.py
HEADERS=(-H 'Accept: application/json')

# Leave APP_API_KEY unset for the local showcase. Set it only when broad API
# authentication is enabled by the application deployment.
[ -z "${APP_API_KEY:-}" ] || HEADERS+=(-H "X-API-Key: $APP_API_KEY")

submit_text() {
  local port="$1" chain="$2" topic="$3" body="$4" response
  response="$(curl -fsS -X POST \
    "http://127.0.0.1:$port/api/v1/app-chain/chains/$chain/messages" \
    -H 'Content-Type: application/json' "${HEADERS[@]}" \
    -d "$(jq -nc --arg topic "$topic" --arg body "$body" \
      '{topic:$topic,body:$body}')")"
  printf '%s' "$response" | jq -r .messageId
}

submit_hex() {
  local port="$1" chain="$2" topic="$3" body_hex="$4" response
  response="$(curl -fsS -X POST \
    "http://127.0.0.1:$port/api/v1/app-chain/chains/$chain/messages" \
    -H 'Content-Type: application/json' "${HEADERS[@]}" \
    -d "$(jq -nc --arg topic "$topic" --arg body "$body_hex" \
      '{topic:$topic,bodyHex:$body}')")"
  printf '%s' "$response" | jq -r .messageId
}

wait_finalized() {
  local chain="$1" id="$2" response
  while :; do
    response="$(curl -fsS \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$chain/messages/$id" \
      2>/dev/null || true)"
    if [ -n "$response" ]; then printf '%s\n' "$response"; return; fi
    sleep 1
  done
}
```

The literal submission contract used below is:

```text
POST /api/v1/app-chain/chains/{chainId}/messages
```

## 3. Ordered log

```bash
ORDER_ID="$(submit_text 7070 orders-chain orders.command.v1 \
  '{"order":"A-200","event":"created"}')"
wait_finalized orders-chain "$ORDER_ID" | jq '{messageId,height,index,topic}'

curl -fsS \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/proof/$ORDER_ID" \
  | jq '{chainId,committedHeight,stateRoot,finalizedAtHeight}'
```

Explain that HTTP acceptance is not finality. The sequencer proposes the order;
the 2-of-3 member certificate finalizes it and binds the proof to the state
root.

## 4. Key/value registry

```bash
KV_HEX="$(python3 "$CODEC" kv put product:A-200 active)"
KV_ID="$(submit_hex 7070 registry-chain kv.command.v1 "$KV_HEX")"
wait_finalized registry-chain "$KV_ID" | jq '{messageId,height,topic}'

KEY_HEX="$(printf '%s' product:A-200 | od -An -v -tx1 | tr -d ' \n')"
curl -fsS \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/registry-chain/proof/$KEY_HEX" \
  | jq '{chainId,committedHeight,stateRoot,valueHex}'
```

The codec only constructs deterministic command bytes. curl is sending the
actual API request a typed application client would send.

## 5. Authenticated map with schema and plugin validation

Submit a canonical-CBOR product that matches the genesis-bound schema:

```bash
PRODUCT_KEY=sku-curl-200
VALUE_HEX="$(python3 "$CODEC" authmap-value product \
  "$PRODUCT_KEY" 5 active 'curl demo product')"
BODY_HEX="$(python3 "$CODEC" authmap put products \
  "$PRODUCT_KEY" "$VALUE_HEX")"
ID="$(submit_hex 7070 authenticated-map-chain \
  authenticated-map.command.v1 "$BODY_HEX")"
wait_finalized authenticated-map-chain "$ID" | jq '{messageId,height,topic}'
```

Submit a canonical-CBOR GTIN text value checked by the first-party validator
plugin:

```bash
GTIN=95012346
VALUE_HEX="$(python3 "$CODEC" authmap-value gtin "$GTIN")"
BODY_HEX="$(python3 "$CODEC" authmap put gtins "$GTIN" "$VALUE_HEX")"
ID="$(submit_hex 7070 authenticated-map-chain \
  authenticated-map.command.v1 "$BODY_HEX")"
wait_finalized authenticated-map-chain "$ID" >/dev/null
```

Run the logical point query and retrieve the native MPF proof:

```bash
QUERY_HEX="$(python3 "$CODEC" authmap query products "$PRODUCT_KEY")"
curl -fsS -X POST \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/authenticated-map-chain/query/authenticated-map/entry-v1" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg params "$QUERY_HEX" '{paramsHex:$params}')" \
  | jq '{chainId,stateMachineId,committedHeight,stateRoot,payloadHex}'

STATE_KEY="$(python3 "$CODEC" authmap state-key products "$PRODUCT_KEY")"
curl -fsS \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/authenticated-map-chain/proof/$STATE_KEY" \
  | jq '{chainId,committedHeight,stateRoot,valueHex,proofWireHex}'
```

Use `./demos/submit-authenticated-map.sh curl-demo` for the complete four-
collection path plus deliberate schema and GTIN pre-finality rejection checks.
Those invalid submissions first receive normal HTTP `202` ingress acceptance;
the scenario then proves they never finalize and their keys remain absent.

## 6. Proposal and application approvals as separate messages

```bash
PROPOSAL=approval-A-200
BODY_HEX="$(python3 "$CODEC" approval propose "$PROPOSAL" \
  '{"order":"A-200","action":"release"}' --required 2)"
ID="$(submit_hex 7070 approvals-chain approvals.command.v1 "$BODY_HEX")"
wait_finalized approvals-chain "$ID" >/dev/null

BODY_HEX="$(python3 "$CODEC" approval approve "$PROPOSAL")"
ID="$(submit_hex 7071 approvals-chain approvals.command.v1 "$BODY_HEX")"
wait_finalized approvals-chain "$ID" >/dev/null

ID="$(submit_hex 7072 approvals-chain approvals.command.v1 "$BODY_HEX")"
wait_finalized approvals-chain "$ID" >/dev/null

STATE_KEY="$(python3 "$CODEC" state-key approval "$PROPOSAL")"
curl -fsS \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/approvals-chain/proof/$STATE_KEY" \
  | jq '{chainId,committedHeight,stateRoot,valueHex}'
```

Node 1 and node 2 each submit a business approval. Those are ordinary app
messages and are different from the member signatures certifying every block.

## 7. Balances and document trail

```bash
MEMBER0="$(curl -fsS \
  http://127.0.0.1:7070/api/v1/app-chain/chains/balances-chain/status \
  | jq -r .memberKey)"
BODY_HEX="$(python3 "$CODEC" balance mint "$MEMBER0" 1000)"
ID="$(submit_hex 7070 balances-chain balances.command.v1 "$BODY_HEX")"
wait_finalized balances-chain "$ID" >/dev/null

BODY_HEX="$(python3 "$CODEC" balance transfer demo-recipient 250)"
ID="$(submit_hex 7070 balances-chain balances.command.v1 "$BODY_HEX")"
wait_finalized balances-chain "$ID" | jq '{messageId,height}'

BODY_HEX="$(python3 "$CODEC" document case-A-200 demo-document-v1 \
  --reference demo://document/A-200)"
ID="$(submit_hex 7070 documents-chain doc-trail.command.v1 "$BODY_HEX")"
wait_finalized documents-chain "$ID" | jq '{messageId,height}'
```

## 8. Composite order → approval → effect

Keep the order bytes identical at registration and proposal:

```bash
ORDER_KEY=order-C-200
PROPOSAL=proposal-C-200
RELEASE=release-C-200
ORDER_JSON='{"id":"order-C-200","amount":42,"currency":"USD"}'
CANONICAL_ORDER="$(python3 "$CODEC" order "$ORDER_JSON")"

BODY_HEX="$(python3 "$CODEC" kv put "$ORDER_KEY" "$CANONICAL_ORDER")"
ID="$(submit_hex 7070 workflow-chain orders.command.v1 "$BODY_HEX")"
wait_finalized workflow-chain "$ID" >/dev/null

BODY_HEX="$(python3 "$CODEC" approval propose "$PROPOSAL" \
  "$CANONICAL_ORDER" --required 2)"
ID="$(submit_hex 7070 workflow-chain approvals.command.v1 "$BODY_HEX")"
wait_finalized workflow-chain "$ID" >/dev/null

BODY_HEX="$(python3 "$CODEC" approval approve "$PROPOSAL")"
ID="$(submit_hex 7071 workflow-chain approvals.command.v1 "$BODY_HEX")"
wait_finalized workflow-chain "$ID" >/dev/null
ID="$(submit_hex 7072 workflow-chain approvals.command.v1 "$BODY_HEX")"
wait_finalized workflow-chain "$ID" >/dev/null

BODY_HEX="$(python3 "$CODEC" release "$RELEASE" "$ORDER_KEY" "$PROPOSAL")"
ID="$(submit_hex 7070 workflow-chain showcase.release.v1 "$BODY_HEX")"
wait_finalized workflow-chain "$ID" | jq '{messageId,height}'
```

Wait for and inspect the finalized effect, executor result, and commitment
proof:

```bash
while :; do
  EFFECTS="$(curl -fsS \
    'http://127.0.0.1:7070/api/v1/app-chain/chains/workflow-chain/effects?fromHeight=0&limit=1000')"
  EFFECT="$(printf '%s' "$EFFECTS" | jq -c --arg scope \
    "showcase/order-release/$RELEASE" \
    '.effects[]? | select(.scope==$scope and .type=="showcase.outbox.write")' \
    | tail -1)"
  [ -n "$EFFECT" ] && break
  sleep 1
done
HEIGHT="$(printf '%s' "$EFFECT" | jq -r .height)"
ORDINAL="$(printf '%s' "$EFFECT" | jq -r .ordinal)"

while :; do
  DETAIL="$(curl -fsS \
    "http://127.0.0.1:7070/api/v1/app-chain/chains/workflow-chain/effects/$HEIGHT/$ORDINAL")"
  [ "$(printf '%s' "$DETAIL" | jq -r '.execution.status // empty')" = DONE ] \
    && break
  sleep 1
done
printf '%s' "$DETAIL" | jq '{record,execution}'
curl -fsS \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/workflow-chain/effects/$HEIGHT/$ORDINAL/proof" \
  | jq '{effectHashHex,effectsRootHex,stateRootHex}'
```

Only node 0 executes the demo outbox effect. All nodes validate the deterministic
intent and the finalized result. Empty Effect Executors panels on other chains
are expected.

## 9. Load, governance, anchoring, and restart

The dedicated drivers use the same submission endpoint and add pacing/reporting:

```bash
./showcase.sh load-test orders --count 500 --concurrency 10 \
  --spread --instance curl-demo
./showcase.sh soak-test orders --duration 60 --rate 25 \
  --concurrency 4 --spread --instance curl-demo
```

Govern a fourth node and a 3-of-4 threshold:

```bash
./showcase.sh member join 3 --instance curl-demo
./showcase.sh governance activate --instance curl-demo
./showcase.sh threshold set 3 --instance curl-demo
./showcase.sh governance activate --instance curl-demo
./showcase.sh verify all --instance curl-demo
```

Inspect the anchor and restart without re-bootstrap:

```bash
curl -fsS \
  http://127.0.0.1:7070/api/v1/app-chain/chains/workflow-chain/status \
  | jq '{tipHeight,stateRoot,anchor}'
./showcase.sh restart --instance curl-demo
./showcase.sh verify all --instance curl-demo
```

For manual devnet bootstrap and fee-paying preprod setup, follow
[ANCHORING_DEMO.md](ANCHORING_DEMO.md).

## 10. Authentication behavior

The local showcase has broad API auth disabled; curl reads/submissions therefore
omit a key. Privileged admin endpoints require the configured full local-demo
key. In a real deployment set `yano.app-chain.api.auth.enabled=true`, inject
`yano.app-chain.api.keys` as a secret, and export `APP_API_KEY` before defining
the helpers so they add `X-API-Key`. Topic-scoped keys can submit only their
declared topics and cannot perform admin/effect mutations. See
[MESSAGE_SUBMISSION.md](MESSAGE_SUBMISSION.md).
