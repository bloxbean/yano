#!/usr/bin/env bash
set -euo pipefail

TARGET="${1:?target is required}"
INSTANCE="${2:?instance is required}"
HTTP_BASE="${3:?HTTP base is required}"
MARKER="$TARGET/data/showcase/$INSTANCE/showcase-identity.json"
CATALOG="$TARGET/catalog/showcase-catalog-v1.json"
[ -f "$MARKER" ] || { echo "Missing retained identity: $MARKER" >&2; exit 66; }
[ -f "$CATALOG" ] || { echo "Missing showcase catalog: $CATALOG" >&2; exit 66; }
# A public-network bootstrap may temporarily omit settlement until the
# operator-specific production profile is adopted. A qualified cluster must
# converge to the full catalog before validation succeeds.
diff -u <(python3 "$TARGET/tools/showcase_catalog.py" "$CATALOG" list) \
  <(jq -r '.chainIds[]' "$MARKER")

CLUSTER_ROOT="$TARGET/data/showcase/$INSTANCE/cluster"
for NODE in 0 1 2; do
  NODE_ROOT="$CLUSTER_ROOT/node$NODE"
  for STORE in chainstate appchain-chainstate appchain-indexers; do
    [ -d "$NODE_ROOT/$STORE" ] || {
      echo "Missing node$NODE $STORE root" >&2
      exit 66
    }
  done
  [ ! -e "$NODE_ROOT/chainstate/appchains" ] || {
    echo "Legacy app-chain index data remains inside node$NODE chainstate" >&2
    exit 65
  }
done

for PORT in "$HTTP_BASE" "$((HTTP_BASE + 1))" "$((HTTP_BASE + 2))"; do
  curl -fsS "http://127.0.0.1:$PORT/q/health/ready" \
    | jq -e '.status == "UP"' >/dev/null
done

while IFS= read -r CHAIN; do
  LEADER="$(curl -fsS \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$CHAIN/status")"
  printf '%s' "$LEADER" | jq -e \
    '.anchor.enabled == true and .anchor.mode == "script" and
     .anchor.bootstrapped == true and
     (.capabilityManifest.manifestDigest | test("^[0-9a-f]{64}$")) and
     (.anchor.lastAnchorTx | test("^[0-9a-f]{64}$"))' >/dev/null
  while IFS= read -r CAPABILITY; do
    printf '%s' "$LEADER" | jq -e --arg capability "$CAPABILITY" \
      '(.capabilityManifest.components
        | any(.id == $capability)) or
       (.capabilityManifest.crossCutting
        | any(.capabilityId == $capability and .enabled == true))' >/dev/null
  done < <(jq -r --arg chain "$CHAIN" \
    '.chains[] | select(.chainId == $chain) | .expectedCapabilities[]' "$CATALOG")

  REFERENCE=""
  for PORT in "$HTTP_BASE" "$((HTTP_BASE + 1))" "$((HTTP_BASE + 2))"; do
    STATUS="$(curl -fsS \
      "http://127.0.0.1:$PORT/api/v1/app-chain/chains/$CHAIN/status")"
    printf '%s' "$STATUS" | jq -e \
      '.running == true and .members == 3 and .threshold == 2 and
       (.stateRoot | test("^[0-9a-f]{64}$")) and
       (.stateCommitment.genesisId | test("^[0-9a-f]{64}$"))' >/dev/null
    CURRENT="$(printf '%s' "$STATUS" | jq -r \
      '[.tipHeight,.stateRoot,.stateCommitment.profile,
        .stateCommitment.genesisId,.capabilityManifest.manifestDigest] | @tsv')"
    [ -n "$REFERENCE" ] || REFERENCE="$CURRENT"
    [ "$REFERENCE" = "$CURRENT" ] || {
      echo "State disagreement for $CHAIN on HTTP $PORT" >&2
      exit 1
    }
  done

  printf '%-32s %s\n' "$CHAIN" \
    "$(printf '%s' "$LEADER" | jq -r \
      '"profile=" + .stateCommitment.profile +
       " tip=" + (.tipHeight|tostring) +
       " anchored=" + (.anchor.lastAnchoredHeight|tostring)')"
done < <(jq -r '.chainIds[]' "$MARKER")

SETTLEMENT="$(curl -fsS \
  "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/payment-chain-settlement/status")"
printf '%s' "$SETTLEMENT" | jq -e \
  '.stateCommitment.profile == "mpf-blake2b256-v1" and
   .stateCommitment.implementation.verificationTarget == "off-chain-and-on-chain" and
   .observers["bridge-deposits"].type == "eutxo-vault-deposit-v1" and
   .effects.executor.executors == ["eutxo-settlement"]' >/dev/null

for CHAIN in payments-chain payment-chain-settlement; do
  INDEX_STATUS="$(curl -fsS \
    "http://127.0.0.1:$HTTP_BASE/api/v1/plugins/com.bloxbean.cardano.yano.appchain.eutxo.indexer/index/v1/status?chain=$CHAIN")"
  printf '%s' "$INDEX_STATUS" | jq -e \
    '.projection.status != "FAILED" and
     .projection.indexedHeight <= .projection.finalizedHeight' >/dev/null
done

echo "PASS: all configured preprod chains agree across three nodes; anchors and production settlement are active"
