#!/usr/bin/env bash
set -euo pipefail
MODULE="$(cd "$(dirname "$0")/../../.." && pwd -P)"
REPO="$(cd "$MODULE/../../.." && pwd -P)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/yano-showcase-contract.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT INT TERM
ROOT="$WORK/showcase"
mkdir -p "$ROOT/yano/appchain-cluster" "$ROOT/yano/config" "$ROOT/yano/plugins" "$ROOT/tools" \
  "$ROOT/catalog" \
  "$ROOT/profiles/evidence/demo/config" "$ROOT/profiles/evidence/artifacts" "$WORK/bin"
cp -R "$MODULE/src/main/showcase/." "$ROOT/"
cp "$MODULE/src/main/showcase/config/application-appchain.yml" "$ROOT/yano/config/"
cp "$MODULE/src/main/showcase/config/showcase-catalog-v1.json" "$ROOT/catalog/"
printf 'fake jar\n' > "$ROOT/yano/yano.jar"
printf 'fake plugin\n' > "$ROOT/yano/plugins/yano-appchain-showcase-test-bundle.jar"
printf 'fake history plugin\n' > \
  "$ROOT/yano/plugins/yano-appchain-cardano-history-test-bundle.jar"
cat > "$ROOT/yano/appchain-cluster/cluster.sh" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${SHOWCASE_STUB_LOG:?}"
if [ "${1:-}" = keys ]; then
  printf '%s\n' 'node  seed(hex, 32 bytes)                                              member-pubkey'
  for ((i=0;i<${2:-3};i++)); do
    printf '%-4d  %064x  %064x\n' "$i" "$((i + 1))" "$((i + 1))"
  done
fi
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
case "$*" in
  */status*) printf '%s\n' '{"anchor":{"bootstrapped":true}}'; exit 0;;
esac
printf '{"messageId":"%064d","chainId":"orders-chain","topic":"orders.command.v1"}' 1
SH
cat > "$WORK/bin/java" <<'SH'
#!/usr/bin/env bash
case "$*" in
  *ShowcaseAuthenticatedMapConfig*authenticated-map-jmt-chain*)
    printf '%s\n' \
      'yano.app-chain.chains[9].machines.authenticated-map.genesis-cbor-hex=830103' \
      'yano.app-chain.chains[9].state.commitment-profile=jmt-blake2b256-v1' \
      "yano.app-chain.chains[9].state.format-fingerprint=$(printf '33%.0s' {1..32})" \
      "yano.app-chain.chains[9].state.genesis-id=$(printf '44%.0s' {1..32})"
    exit 0;;
  *ShowcaseAuthenticatedMapConfig*)
    printf '%s\n' \
      'yano.app-chain.chains[8].machines.authenticated-map.genesis-cbor-hex=830102' \
      'yano.app-chain.chains[8].state.commitment-profile=mpf-blake2b256-v1' \
      "yano.app-chain.chains[8].state.format-fingerprint=$(printf '11%.0s' {1..32})" \
      "yano.app-chain.chains[8].state.genesis-id=$(printf '22%.0s' {1..32})"
    exit 0;;
  -version)
    printf '%s\n' 'openjdk version "25"' >&2; exit 0;;
  *) exit 0;;
esac
SH
printf 'DEMO_EVIDENCE_ID=showcase-contract\n' > \
  "$ROOT/profiles/evidence/demo/config/common.env"
chmod +x "$ROOT/showcase.sh" "$ROOT/tools/"*.py "$ROOT/demos/"*.sh "$WORK/bin/curl" \
  "$WORK/bin/java" \
  "$ROOT/yano/appchain-cluster/cluster.sh" "$ROOT/yano/appchain-cluster/loadtest.sh" \
  "$ROOT/yano/appchain-cluster/soaktest.sh" "$ROOT/yano/yano.sh" \
  "$ROOT/profiles/evidence/demo/demo.sh"
export SHOWCASE_STUB_LOG="$WORK/cluster.log"
export PATH="$WORK/bin:$PATH"

[ "$(python3 "$ROOT/tools/showcase_codec.py" \
  authmap-value event created 1)" = "82676372656174656401" ]
[ "$(python3 "$ROOT/tools/showcase_codec.py" \
  authmap-value product sku-1 5 active)" = \
  "a363736b7565736b752d316673746174757366616374697665687175616e7469747905" ]
[ "$(python3 "$ROOT/tools/showcase_codec.py" \
  authmap state-key products sku-1)" = \
  "0101000870726f647563747300000005736b752d31" ]

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
jq -e '.chainIds | length == 13' \
  "$ROOT/data/showcase/three/showcase-identity.json" >/dev/null
jq -e '.chainIds[10] == "payment-chain-settlement" and .chainIds[11] == "document-review-chain" and .chainIds[12] == "cardano-history-chain"' \
  "$ROOT/data/showcase/three/showcase-identity.json" >/dev/null
jq -e '.authenticatedMapConfigSha256 | test("^[0-9a-f]{64}$")' \
  "$ROOT/data/showcase/three/showcase-identity.json" >/dev/null
jq -e '.authenticatedMapJmtConfigSha256 | test("^[0-9a-f]{64}$")' \
  "$ROOT/data/showcase/three/showcase-identity.json" >/dev/null
jq -e '.cardanoHistory.enabled == true and .cardanoHistory.profile == "params-only-v1" and (.cardanoHistory.bundleSha256 | test("^[0-9a-f]{64}$"))' \
  "$ROOT/data/showcase/three/showcase-identity.json" >/dev/null
[ "$(find "$ROOT/data/showcase/three/node-config" -type f -name 'node*.properties' | wc -l | tr -d ' ')" = 3 ]
grep -q 'showcase-outbox.enabled=true' "$ROOT/data/showcase/three/node-config/node0.properties"
grep -q 'showcase-outbox.enabled=false' "$ROOT/data/showcase/three/node-config/node1.properties"
grep -q 'effects.executor.enabled=true' "$ROOT/data/showcase/three/node-config/node0.properties"
grep -q 'effects.executor.enabled=false' "$ROOT/data/showcase/three/node-config/node1.properties"
grep -q 'chains\[8\].machines.authenticated-map.genesis-cbor-hex=' \
  "$ROOT/data/showcase/three/node-config/node0.properties"
[ -s "$ROOT/data/showcase/three/authenticated-map-genesis.hex" ]
[ -s "$ROOT/data/showcase/three/authenticated-map-jmt-genesis.hex" ]
grep -q 'chains\[9\].machines.authenticated-map.genesis-cbor-hex=' \
  "$ROOT/data/showcase/three/node-config/node0.properties"
! grep -R 'signing-key\|api-key' "$ROOT/data/showcase/three/node-config" >/dev/null
! grep -R 'authenticated-snapshots.enabled=true' \
  "$ROOT/data/showcase/three/node-config" >/dev/null

# ADR-028 M8c: the fresh-instance-only flag selects only declared series,
# records their profile in deployment identity, and uses node-local archives.
"$ROOT/showcase.sh" prepare --instance snapshots --http-base 19470 --server-base 19070 \
  --cardano-history-profile full \
  --enable-authenticated-snapshots=cardano-history-chain \
  --authenticated-snapshot-profile jmt-blake2b256-v1
jq -e '.authenticatedSnapshots.chainIds == ["cardano-history-chain"] and .authenticatedSnapshots.mpfPruningChainIds == [] and .authenticatedSnapshots.profile == "jmt-blake2b256-v1"' \
  "$ROOT/data/showcase/snapshots/showcase-identity.json" >/dev/null
grep -q 'chains\[12\].capabilities.authenticated-snapshots.enabled=true' \
  "$ROOT/data/showcase/snapshots/node-config/node0.properties"
grep -q 'chains\[12\].machines.epoch-stake.snapshot-profile=jmt-blake2b256-v1' \
  "$ROOT/data/showcase/snapshots/node-config/node1.properties"
grep -q '/node0/appchain-snapshot-archives' \
  "$ROOT/data/showcase/snapshots/node-config/node0.properties"
grep -q '/node1/appchain-snapshot-archives' \
  "$ROOT/data/showcase/snapshots/node-config/node1.properties"
"$ROOT/showcase.sh" prepare --instance snapshot-pruning --http-base 19570 \
  --server-base 19170 --cardano-history-profile full \
  --enable-authenticated-snapshots=cardano-history-chain \
  --enable-authenticated-snapshot-mpf-pruning=cardano-history-chain
jq -e '.authenticatedSnapshots.mpfPruningChainIds == ["cardano-history-chain"] and .authenticatedSnapshots.profile == "mpf-blake2b256-v1"' \
  "$ROOT/data/showcase/snapshot-pruning/showcase-identity.json" >/dev/null
grep -q 'capabilities.authenticated-snapshots.storage.mpf-pruning-enabled=true' \
  "$ROOT/data/showcase/snapshot-pruning/node-config/node0.properties"
if "$ROOT/showcase.sh" prepare --instance snapshot-pruning-jmt --http-base 19670 \
    --server-base 19270 --enable-authenticated-snapshots=cardano-history-chain \
    --cardano-history-profile full \
    --authenticated-snapshot-profile jmt-blake2b256-v1 \
    --enable-authenticated-snapshot-mpf-pruning=cardano-history-chain \
    >"$WORK/snapshot-pruning-jmt.log" 2>&1; then
  echo "JMT snapshot profile accepted MPF pruning" >&2; exit 1
fi
grep -q 'MPF pruning requires mpf-blake2b256-v1' "$WORK/snapshot-pruning-jmt.log"
if "$ROOT/showcase.sh" prepare --instance invalid-snapshots \
    --enable-authenticated-snapshots=orders-chain >"$WORK/snapshot-invalid.log" 2>&1; then
  echo "unsupported authenticated snapshot chain was accepted" >&2; exit 1
fi
grep -q 'does not declare authenticated snapshot series' "$WORK/snapshot-invalid.log"

# ADR-035 product presets are retained identity, configure only their selected
# observer/components, and reject snapshot selection for params-only.
"$ROOT/showcase.sh" prepare --instance history-stake --http-base 19670 \
  --server-base 19270 --cardano-history-profile params-stake
jq -e '.cardanoHistory.profile == "params-stake-v1"' \
  "$ROOT/data/showcase/history-stake/showcase-identity.json" >/dev/null
grep -q 'observers.epoch-stake.type=l1-epoch-stake-v1' \
  "$ROOT/data/showcase/history-stake/node-config/node0.properties"
! grep -q 'observers.epoch-governance.type=' \
  "$ROOT/data/showcase/history-stake/node-config/node0.properties"
if "$ROOT/showcase.sh" prepare --instance history-params-snapshot \
    --enable-authenticated-snapshots=cardano-history-chain \
    >"$WORK/history-params-snapshot.log" 2>&1; then
  echo "params-only Cardano History accepted snapshots" >&2; exit 1
fi
grep -q 'require a Cardano History stake or governance profile' \
  "$WORK/history-params-snapshot.log"

# A retained instance must restart from its marker without repeating the
# original non-default ports, membership, network, or chain list.
"$ROOT/showcase.sh" up --instance three
grep -q '^start 3 ' "$WORK/cluster.log"

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

# ADR-UTXO-009: the settlement group prints the pinned deploy identity on
# any network. The chain settles autonomously, so there is no operator
# "settle" verb to exercise here.
SETTLEMENT_INFO="$("$ROOT/showcase.sh" settlement info --instance three)"
grep -q 'federated L1 settlement' <<< "$SETTLEMENT_INFO"
grep -q 'machine profile       : yano-eutxo-v3-bridge-settlement-devnet' <<< "$SETTLEMENT_INFO"
grep -q 'nullifier shards' <<< "$SETTLEMENT_INFO"
grep -q 'No single key is custody' <<< "$SETTLEMENT_INFO"
grep -q 'NOT bootstrapped' <<< "$SETTLEMENT_INFO"

if "$ROOT/showcase.sh" prepare --instance three --nodes 5 --http-base 19770 --server-base 19370 \
    >"$WORK/drift.log" 2>&1; then
  echo "identity drift was accepted" >&2; exit 1
fi
grep -q 'differs from retained showcase identity' "$WORK/drift.log"

# ADR-033: selected/all index scopes are canonical, retained, and exported to
# every node as consensus settings. Drift and malformed scopes fail closed.
"$ROOT/showcase.sh" prepare --instance indexed --http-base 19570 --server-base 19170 \
  --enable-finalized-message-index=documents-chain,workflow-chain
jq -e '.finalizedMessageIndex.chainIds == ["documents-chain", "workflow-chain"]' \
  "$ROOT/data/showcase/indexed/showcase-identity.json" >/dev/null
grep -q 'chains\[4\].machines.finalized-message-index.enabled=true' \
  "$ROOT/data/showcase/indexed/node-config/node0.properties"
grep -q 'chains\[5\].machines.finalized-message-index.enabled=true' \
  "$ROOT/data/showcase/indexed/node-config/node2.properties"
if "$ROOT/showcase.sh" prepare --instance indexed \
    --enable-finalized-message-index=workflow-chain >"$WORK/index-drift.log" 2>&1; then
  echo "finalized-message index drift was accepted" >&2; exit 1
fi
grep -q 'differs from retained showcase identity' "$WORK/index-drift.log"
if "$ROOT/showcase.sh" prepare --instance invalid-index \
    --enable-finalized-message-index=documents-chain,documents-chain \
    >"$WORK/index-invalid.log" 2>&1; then
  echo "duplicate finalized-message index scope was accepted" >&2; exit 1
fi
grep -q 'duplicate finalized-message index chain' "$WORK/index-invalid.log"

# Anchoring is an additive retained-identity migration. Start with the default
# workflow scope, add one existing chain, then expand to all chains without
# replacing app-chain data or changing the signer.
"$ROOT/showcase.sh" prepare --instance anchor-expand --anchor \
  --http-base 19670 --server-base 19270
ANCHOR_ROOT="$ROOT/data/showcase/anchor-expand"
# Fresh instances anchor-ENABLE every chain by default (config-only; spending
# starts at per-chain bootstrap).
jq -e '.schemaVersion == 2 and (.anchor.chainIds | length == 13)' \
  "$ANCHOR_ROOT/showcase-identity.json" >/dev/null
# Downgrade the retained scope to the legacy workflow-only shape so the
# additive enable migrations below keep their pre-default coverage.
python3 - "$ANCHOR_ROOT/showcase-identity.json" <<'PY'
import json, pathlib, sys
marker = pathlib.Path(sys.argv[1])
doc = json.loads(marker.read_text())
doc["schemaVersion"] = 1
doc["anchor"].pop("chainIds", None)
doc["anchor"]["chainId"] = "workflow-chain"
marker.write_text(json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n")
PY
mkdir -m 700 "$ANCHOR_ROOT/cluster"
python3 - "$ANCHOR_ROOT/cluster/cluster-appchain-identity.json" <<'PY'
import hashlib,json,pathlib,sys
members = ["01" * 32, "02" * 32, "03" * 32]
fingerprint = hashlib.sha256(
    b"yano-cluster-anchor-signer-v1\0" + ("30" * 32).encode("ascii")
).hexdigest()
document = {
    "schemaVersion": 1,
    "kind": "yano.cluster.appchain-identity",
    "network": "devnet",
    "memberCount": 3,
    "members": members,
    "threshold": 2,
    "proposer": members[0],
    "chainIds": ["orders-chain", "registry-chain", "approvals-chain", "balances-chain",
                 "documents-chain", "workflow-chain", "roles-chain", "payments-chain",
                 "authenticated-map-chain", "authenticated-map-jmt-chain",
                 "payment-chain-settlement", "document-review-chain",
                 "cardano-history-chain"],
    "anchor": {"enabled": True, "mode": "script", "signerFingerprint": fingerprint,
               "chainId": "workflow-chain"},
}
path = pathlib.Path(sys.argv[1])
path.write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n")
path.chmod(0o600)
PY
printf '%s\n' 'NETWORK=devnet' 'ENABLE_ANCHOR=1' 'ANCHOR_MODE=script' \
  'ANCHOR_CHAIN=workflow-chain' 'HTTP_BASE=19670' 'SERVER_BASE=19270' \
  > "$ANCHOR_ROOT/cluster/cluster.env"
chmod 600 "$ANCHOR_ROOT/cluster/cluster.env"

"$ROOT/showcase.sh" anchor enable registry-chain --instance anchor-expand
jq -e '.schemaVersion == 2 and
  .anchor.chainIds == ["registry-chain", "workflow-chain"]' \
  "$ANCHOR_ROOT/showcase-identity.json" >/dev/null
jq -e '.schemaVersion == 2 and
  .anchor.chainIds == ["registry-chain", "workflow-chain"]' \
  "$ANCHOR_ROOT/cluster/cluster-appchain-identity.json" >/dev/null
grep -q '^ANCHOR_CHAINS=registry-chain,workflow-chain$' \
  "$ANCHOR_ROOT/cluster/cluster.env"
grep -q -- '--anchor-chain registry-chain --anchor-chain workflow-chain' "$WORK/cluster.log"

"$ROOT/showcase.sh" anchor enable all --instance anchor-expand
jq -e '.anchor.chainIds | length == 13' "$ANCHOR_ROOT/showcase-identity.json" >/dev/null
jq -e '.anchor.chainIds | length == 13' \
  "$ANCHOR_ROOT/cluster/cluster-appchain-identity.json" >/dev/null
PATH="$WORK/bin:$PATH" "$ROOT/showcase.sh" anchor bootstrap all --instance anchor-expand
[ "$(grep -c '^anchor-bootstrap .*' "$WORK/cluster.log")" = 13 ]

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

# A fresh preprod light deployment removes the packaged devnet-only settlement
# chain before retaining its identity. Prepare is idempotent, and `all` means
# every chain that is actually configured (the production settlement chain is
# added only after its operator-specific L1 bootstrap).
"$ROOT/showcase.sh" prepare --profile light --network preprod \
  --instance light-preprod --http-base 17070 --server-base 17337 \
  --anchor-chain all --anchor-key-file "$WORK/anchor.seed" \
  --confirm-public-anchor preprod
"$ROOT/showcase.sh" prepare --profile light --network preprod \
  --instance light-preprod --http-base 17070 --server-base 17337 \
  --anchor-chain all --anchor-key-file "$WORK/anchor.seed" \
  --confirm-public-anchor preprod
jq -e '.network == "preprod" and (.chainIds | length) == 12
  and (.anchor.chainIds | length) == 12
  and (.chainIds | index("payment-chain-settlement")) == null' \
  "$ROOT/data/showcase/light-preprod/showcase-identity.json" >/dev/null
! grep -q 'chain-id: "payment-chain-settlement"' \
  "$ROOT/yano/config/application-appchain.yml"
python3 - "$ROOT/yano/config/application-appchain.yml" <<'PY'
import pathlib,re,sys
config = pathlib.Path(sys.argv[1]).read_text()
indexes = [int(value) for value in re.findall(r'^    chains\[(\d+)]\s*:', config, re.MULTILINE)]
assert indexes == list(range(12)), indexes
PY

# The production settlement ConfigBlock has nested observer/indexer chain-id
# fields. Adoption validates only declarations directly under chains[n], so a
# valid public bootstrap is not rejected after it has submitted its L1 tx.
cat > "$WORK/settlement-bootstrap.log" <<'LOG'
ConfigBlock : chains[0]:
      chain-id: "payment-chain-settlement"
      state-machine: "eutxo-ledger"
      state:
        commitment-profile: "mpf-blake2b256-v1"
      machines:
        eutxo:
          profile: "yano-eutxo-v3-bridge-settlement"
          expected-profile-digest: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
          l1:
            deposits:
              chain-id: "nested-deposit-source"
            withdrawals:
              chain-id: "nested-withdrawal-source"
          settlement:
            enabled: true
            operator-key-id: "contract"
            vault-script-hash: "0123456789abcdef"
            thread-policy-id: "0123456789abcdef"
            thread-asset-name: "contract"
            confirmations: 1
            scan-depth: 100
            effect-type: "eutxo.settlement.v1"
            indexer-enabled: true
            observer-enabled: true

LOG
python3 "$REPO/.agents/skills/deploy-showcase-preprod-cluster/scripts/adopt_settlement_config.py" \
  "$ROOT/yano/config/application-appchain.yml" "$WORK/settlement-bootstrap.log" \
  "$ROOT/catalog/showcase-catalog-v1.json" >/dev/null
python3 - "$ROOT/yano/config/application-appchain.yml" \
  "$ROOT/catalog/showcase-catalog-v1.json" <<'PY'
import json,pathlib,re,sys
config = pathlib.Path(sys.argv[1]).read_text()
actual = re.findall(r'^ {6}chain-id:\s*["\']?([^"\'\s]+)', config, re.MULTILINE)
expected = [row['chainId'] for row in json.loads(pathlib.Path(sys.argv[2]).read_text())['chains']]
assert actual == expected, (actual, expected)
indexes = [int(value) for value in re.findall(r'^    chains\[(\d+)]\s*:', config, re.MULTILINE)]
assert indexes == list(range(len(expected))), indexes
PY

# Product installation and chain activation are independent: disabling the
# chain removes it from identity/config while retaining the distributable jar.
"$ROOT/showcase.sh" prepare --instance history-disabled --disable-cardano-history \
  --http-base 18070 --server-base 18337
jq -e '.cardanoHistory.enabled == false
  and (.chainIds | index("cardano-history-chain")) == null' \
  "$ROOT/data/showcase/history-disabled/showcase-identity.json" >/dev/null
! grep -q 'chain-id: "cardano-history-chain"' \
  "$ROOT/yano/config/application-appchain.yml"

echo "PASS: showcase facade preserves catalog, index identity, scaling, redaction, join, and governance contracts"
