#!/usr/bin/env bash
# Minimal real-HTTP submission example. No showcase-private endpoint is used.
set -euo pipefail

BASE_URL="${1:?base URL required, for example http://127.0.0.1:7070}"
CHAIN_ID="${2:?chain id required}"
TOPIC="${3:?topic required}"
ENCODING="${4:?encoding required: text or hex}"
BODY="${5:?body required}"
CURL_ARGS=(-fsS -X POST -H 'Content-Type: application/json')
[ -z "${YANO_APP_CHAIN_API_KEY:-}" ] \
  || CURL_ARGS+=(-H "X-API-Key: $YANO_APP_CHAIN_API_KEY")

case "$ENCODING" in
  text) PAYLOAD="$(jq -nc --arg topic "$TOPIC" --arg body "$BODY" \
    '{topic:$topic,body:$body}')";;
  hex) PAYLOAD="$(jq -nc --arg topic "$TOPIC" --arg body "$BODY" \
    '{topic:$topic,bodyHex:$body}')";;
  *) printf 'error: encoding must be text or hex\n' >&2; exit 1;;
esac

curl "${CURL_ARGS[@]}" \
  "${BASE_URL%/}/api/v1/app-chain/chains/$CHAIN_ID/messages" -d "$PAYLOAD"
printf '\n'
