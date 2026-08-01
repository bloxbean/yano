#!/usr/bin/env bash
set -euo pipefail
MODULE="$(cd "$(dirname "$0")/../../.." && pwd -P)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/yano-showcase-contract.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT INT TERM
ROOT="$WORK/showcase"
mkdir -p "$ROOT/yano/appchain-cluster" "$ROOT/yano/config" "$ROOT/yano/plugins" "$ROOT/tools" \
  "$ROOT/profiles/evidence/demo/config" "$ROOT/profiles/evidence/artifacts" "$WORK/bin"
cp -R "$MODULE/src/main/showcase/." "$ROOT/"
cp "$MODULE/src/main/showcase/config/application-appchain.yml" "$ROOT/yano/config/"
printf 'fake jar\n' > "$ROOT/yano/yano.jar"
printf 'fake plugin\n' > "$ROOT/yano/plugins/yano-appchain-showcase-test-bundle.jar"
cat > "$ROOT/yano/appchain-cluster/cluster.sh" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${SHOWCASE_STUB_LOG:?}"
exit 0
SH
cat > "$ROOT/yano/appchain-cluster/loadtest.sh" <<'SH'
#!/usr/bin/env bash
printf 'loadtest %s\n' "$*" >> "${SHOWCASE_STUB_LOG:?}"
exit 0
SH
cat > "$ROOT/yano/appchain-cluster/soaktest.sh" <<'SH'
#!/usr/bin/env bash
printf 'soaktest %s\n' "$*" >> "${SHOWCASE_STUB_LOG:?}"
exit 0
SH
cat > "$ROOT/yano/yano.sh" <<'SH'
#!/usr/bin/env bash
printf 'yano %s\n' "$*" >> "${SHOWCASE_STUB_LOG:?}"
exit 0
SH
cat > "$ROOT/profiles/evidence/demo/demo.sh" <<'SH'
#!/usr/bin/env bash
printf 'evidence %s\n' "$*" >> "${SHOWCASE_STUB_LOG:?}"
exit 0
SH
cat > "$WORK/bin/curl" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${SHOWCASE_CURL_LOG:?}"
printf '{"messageId":"%064d","chainId":"orders-chain","topic":"orders.command.v1"}' 1
SH
printf 'DEMO_EVIDENCE_ID=showcase-contract\n' > \
  "$ROOT/profiles/evidence/demo/config/common.env"
chmod +x "$ROOT/showcase.sh" "$ROOT/tools/"*.py "$ROOT/demos/"*.sh "$WORK/bin/curl" \
  "$ROOT/yano/appchain-cluster/cluster.sh" "$ROOT/yano/appchain-cluster/loadtest.sh" \
  "$ROOT/yano/appchain-cluster/soaktest.sh" "$ROOT/yano/yano.sh" \
  "$ROOT/profiles/evidence/demo/demo.sh"
export SHOWCASE_STUB_LOG="$WORK/cluster.log"

export SHOWCASE_CURL_LOG="$WORK/curl.log"
PATH="$WORK/bin:$PATH" "$ROOT/demos/submit-message-curl.sh" \
  http://127.0.0.1:7070 orders-chain orders.command.v1 text demo >/dev/null
! grep -q 'X-API-Key' "$WORK/curl.log"
YANO_APP_CHAIN_API_KEY=contract-key PATH="$WORK/bin:$PATH" \
  "$ROOT/demos/submit-message-curl.sh" \
  http://127.0.0.1:7070 orders-chain orders.command.v1 text demo >/dev/null
grep -q 'X-API-Key: contract-key' "$WORK/curl.log"

PROFILES="$("$ROOT/showcase.sh" profiles)"
grep -q '^light' <<< "$PROFILES"
grep -q 'governance activate' <<< "$("$ROOT/showcase.sh" help)"
"$ROOT/showcase.sh" prepare --instance three --nodes 3 --http-base 19770 --server-base 19370
[ -f "$ROOT/data/showcase/three/showcase-identity.json" ]
[ "$(find "$ROOT/data/showcase/three/node-config" -type f -name 'node*.properties' | wc -l | tr -d ' ')" = 3 ]
grep -q 'showcase-outbox.enabled=true' "$ROOT/data/showcase/three/node-config/node0.properties"
grep -q 'showcase-outbox.enabled=false' "$ROOT/data/showcase/three/node-config/node1.properties"
grep -q 'effects.executor.enabled=true' "$ROOT/data/showcase/three/node-config/node0.properties"
grep -q 'effects.executor.enabled=false' "$ROOT/data/showcase/three/node-config/node1.properties"
! grep -R 'signing-key\|api-key' "$ROOT/data/showcase/three/node-config" >/dev/null

"$ROOT/showcase.sh" load-test orders --count 25 --concurrency 3 \
  --payload-bytes 64 --spread --instance three
"$ROOT/showcase.sh" load-test registry --count 12 --concurrency 2 \
  --payload-bytes 32 --node 1 --instance three
"$ROOT/showcase.sh" soak-test orders --duration 3 --rate 4 --concurrency 2 \
  --payload-bytes 32 --sample 1 --report-dir "$WORK/soak" --node 0 --instance three
grep -q '^loadtest orders-chain -n 25 -c 3 -s 64 -t orders.command.v1 --spread$' \
  "$WORK/cluster.log"
grep -q '^loadtest registry-chain -n 12 -c 2 -s 32 -t kv.command.v1 --kv --node 1$' \
  "$WORK/cluster.log"
grep -q "^soaktest orders-chain --duration 3 --rate 4 --conc 2 -s 32 --sample 1 --out $WORK/soak --node 0$" \
  "$WORK/cluster.log"

if "$ROOT/showcase.sh" prepare --instance three --nodes 5 --http-base 19770 --server-base 19370 \
    >"$WORK/drift.log" 2>&1; then
  echo "identity drift was accepted" >&2; exit 1
fi
grep -q 'differs from retained showcase identity' "$WORK/drift.log"

"$ROOT/showcase.sh" prepare --instance five --nodes 5 --http-base 19870 --server-base 19470
[ "$(find "$ROOT/data/showcase/five/node-config" -type f -name 'node*.properties' | wc -l | tr -d ' ')" = 5 ]
"$ROOT/showcase.sh" config export "$WORK/export.json" --instance five
grep -q '"bootstrapNodeCount": 5' "$WORK/export.json"

"$ROOT/showcase.sh" member join 3 --instance three
"$ROOT/showcase.sh" member join 3 --instance three
"$ROOT/showcase.sh" restart --instance three
"$ROOT/showcase.sh" threshold set 3 --instance three
grep -q '^node join 3$' "$WORK/cluster.log"
[ "$(grep -c '^node resume 3$' "$WORK/cluster.log")" = 2 ]
grep -q '^threshold set 3$' "$WORK/cluster.log"
[ "$(find "$ROOT/data/showcase/three/node-config" -type f -name 'node*.properties' | wc -l | tr -d ' ')" = 4 ]

"$ROOT/showcase.sh" prepare --profile eutxo --variant ledger --instance eutxo \
  --nodes 5 --http-base 19970 --server-base 19570
"$ROOT/showcase.sh" run --profile eutxo --variant ledger --instance eutxo \
  --nodes 5 --http-base 19970 --server-base 19570
grep -q '^yano appchain eutxo demo setup --scenario ledger .* --members 5 --http-port-base 19970 --server-port-base 19570$' \
  "$WORK/cluster.log"
grep -q '^yano appchain eutxo demo round-trip --scenario ledger .* --members 5 --http-port-base 19970 --server-port-base 19570$' \
  "$WORK/cluster.log"
if "$ROOT/showcase.sh" prepare --profile eutxo --variant ledger --network preprod \
    --instance eutxo-public >"$WORK/eutxo-public.log" 2>&1; then
  echo "EUTxO public-network selection was silently accepted" >&2; exit 1
fi
grep -q 'EUTxO showcase variants are devnet-only' "$WORK/eutxo-public.log"

"$ROOT/showcase.sh" quickstart --profile evidence --variant role --instance evidence-role
"$ROOT/showcase.sh" verify --profile evidence --variant composite --instance evidence-composite
grep -q '^evidence role-lifecycle --deployment compose --machine role ' "$WORK/cluster.log"
grep -q '^evidence verify --deployment compose --machine composite .* --evidence-id showcase-contract$' \
  "$WORK/cluster.log"
if "$ROOT/showcase.sh" prepare --profile evidence --nodes 5 --instance evidence-five \
    >"$WORK/evidence-five.log" 2>&1; then
  echo "ignored evidence node override was accepted" >&2; exit 1
fi
grep -q 'evidence profile uses exactly 3 nodes with threshold 2' "$WORK/evidence-five.log"
printf '%064d\n' 1 > "$WORK/anchor.seed"
chmod 600 "$WORK/anchor.seed"
"$ROOT/showcase.sh" prepare --profile evidence --variant composite --network preprod \
  --instance evidence-preprod --anchor-key-file "$WORK/anchor.seed" \
  --confirm-public-anchor preprod
grep -q "^evidence prepare .* --network preprod .* --anchor-key-file $WORK/anchor.seed --confirm-public-anchor preprod$" \
  "$WORK/cluster.log"

echo "PASS: showcase facade preserves identity, scaling, redaction, join, and governance contracts"
