#!/usr/bin/env bash
set -euo pipefail

archive="${1:?Usage: final-distribution-stock-outcomes.sh YANO_ZIP}"
work="$(mktemp -d "${TMPDIR:-/tmp}/yano-stock-outcomes.XXXXXX")"
export YANO_CLUSTER_DIR="$work/cluster-state"
export YANO_CLUSTER_HTTP_BASE="${YANO_STOCK_ACCEPTANCE_HTTP_BASE:-19070}"
export YANO_CLUSTER_SERVER_BASE="${YANO_STOCK_ACCEPTANCE_SERVER_BASE:-24337}"

cleanup() {
  if [ -n "${yano_home:-}" ] && [ -x "$yano_home/yano.sh" ]; then
    "$yano_home/yano.sh" appchain cluster clean >/dev/null 2>&1 || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT

unzip -q "$archive" -d "$work/distribution"
yano_home="$(find "$work/distribution" -mindepth 1 -maxdepth 1 -type d | head -1)"
export YANO_HOME="$yano_home"

"$yano_home/yano.sh" appchain cluster start 3 >/dev/null
api="http://127.0.0.1:${YANO_CLUSTER_HTTP_BASE}/api/v1/app-chain/chains"

# recipe-audit-log: threshold-finalized ordered record and message proof.
audit="$(curl -fsS -H 'content-type: application/json' \
  -d '{"topic":"acceptance","body":"packaged-audit-outcome"}' \
  "$api/orders-chain/messages")"
audit_id="$(jq -er '.messageId' <<<"$audit")"
for _ in $(seq 1 60); do
  curl -fsS "$api/orders-chain/proof/$audit_id" 2>/dev/null \
    | jq -e --arg id "$audit_id" '.key == $id and .proofWireHex != ""' >/dev/null \
    && break
  sleep 1
done
curl -fsS "$api/orders-chain/proof/$audit_id" \
  | jq -e --arg id "$audit_id" '.key == $id and .proofWireHex != ""' >/dev/null

# recipe-owned-registry: first writer owns a committed value with a state proof.
"$yano_home/yano.sh" appchain cluster kv registry-chain set supplier-42 active --node 1 \
  >/dev/null
registry_key="$(printf 'supplier-42' | od -An -tx1 | tr -d ' \n')"
for _ in $(seq 1 60); do
  registry="$(curl -fsS "$api/registry-chain/proof/$registry_key" 2>/dev/null || true)"
  [ "$(jq -r '.valueHex // empty' <<<"$registry" 2>/dev/null)" != "" ] && break
  sleep 1
done
jq -e --arg key "$registry_key" \
  '.key == $key and .valueHex != null and .proofWireHex != ""' <<<"$registry" >/dev/null

# recipe-approval-workflow: proposal reaches APPROVED; the demo additionally
# proves effect emission/result incorporation without weakening the stock decision.
"$yano_home/yano.sh" appchain cluster effect demo \
  "packaged approval outcome" >/dev/null

"$yano_home/yano.sh" appchain cluster stop >/dev/null
echo "PASS: final distribution stable recipe outcomes (audit, registry, approval/effect)"
