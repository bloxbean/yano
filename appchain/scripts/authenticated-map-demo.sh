#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
  echo "usage: $0 <chain-id> <collection> <key-text> <value-text> [api-base]" >&2
  exit 2
fi

CHAIN_ID=$1
COLLECTION=$2
KEY_TEXT=$3
VALUE_TEXT=$4
API_BASE=${5:-http://127.0.0.1:8080/api/v1}

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }

read -r COMMAND_HEX QUERY_HEX STATE_KEY_HEX < <(python3 - "$COLLECTION" "$KEY_TEXT" "$VALUE_TEXT" <<'PY'
import struct
import sys

collection, key_text, value_text = sys.argv[1:]
collection_bytes = collection.encode("ascii")
key = key_text.encode("utf-8")
value = value_text.encode("utf-8")

def head(major, value):
    if value < 24:
        return bytes([(major << 5) | value])
    if value <= 0xff:
        return bytes([(major << 5) | 24, value])
    if value <= 0xffff:
        return bytes([(major << 5) | 25]) + struct.pack(">H", value)
    return bytes([(major << 5) | 26]) + struct.pack(">I", value)

def uint(value):
    return head(0, value)

def bstr(value):
    return head(2, len(value)) + value

def text(value):
    encoded = value.encode("ascii")
    return head(3, len(encoded)) + encoded

def array(*items):
    return head(4, len(items)) + b"".join(items)

# command = [v1, single, [[PUT, collection, key, value, rev0, hash-empty, controller-empty]]]
mutation = array(uint(0), text(collection), bstr(key), bstr(value),
                 uint(0), bstr(b""), bstr(b""))
command = array(uint(1), uint(0), array(mutation))

# current point query = [v1, current, height0, collection, key]
query = array(uint(1), uint(0), uint(0), text(collection), bstr(key))

# canonical backend key = version || namespace || collection length/value || key length/value
state_key = b"\x01\x01" + struct.pack(">H", len(collection_bytes)) + collection_bytes
state_key += struct.pack(">I", len(key)) + key

print(command.hex(), query.hex(), state_key.hex())
PY
)

CHAIN_PATH="$API_BASE/app-chain/chains/$CHAIN_ID"
SUBMITTED=$(curl -fsS -X POST "$CHAIN_PATH/messages" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc \
    --arg topic 'authenticated-map.command.v1' \
    --arg body "$COMMAND_HEX" \
    '{topic:$topic,bodyHex:$body}')")

echo "Submitted canonical authenticated-map PUT:"
echo "$SUBMITTED" | jq .

echo "Current root-attested point DTO envelope:"
curl -fsS -X POST "$CHAIN_PATH/query/authenticated-map/entry-v1" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg params "$QUERY_HEX" '{paramsHex:$params}')" | jq .

echo "Current native proof envelope:"
curl -fsS "$CHAIN_PATH/state/proof/$STATE_KEY_HEX" | jq .

echo "Chain tip (obtain the trusted root independently before relying on proof verification):"
curl -fsS "$CHAIN_PATH/tip" | jq .
