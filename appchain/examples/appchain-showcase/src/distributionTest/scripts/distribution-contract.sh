#!/usr/bin/env bash
set -euo pipefail
ZIP="${1:?showcase zip required}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/yano-showcase-dist.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT INT TERM
unzip -q "$ZIP" -d "$WORK"
set -- "$WORK"/yano-showcase-*
[ "$#" -eq 1 ] && [ -d "$1" ]
ROOT="$1"
[ "$(find "$WORK" -mindepth 1 -maxdepth 1 -type d -print | wc -l | tr -d ' ')" = 1 ]
[ -x "$ROOT/showcase.sh" ]
[ -x "$ROOT/demos/master-demo.sh" ]
[ -x "$ROOT/demos/load-test.sh" ]
[ -x "$ROOT/demos/soak-test.sh" ]
[ -x "$ROOT/demos/submit-message-curl.sh" ]
[ -x "$ROOT/demos/submit-authenticated-map.sh" ]
[ -x "$ROOT/tools/showcase_codec.py" ]
[ -x "$ROOT/yano/yano.sh" ]
[ -x "$ROOT/yano/appchain-cluster/cluster.sh" ]
[ -x "$ROOT/yano/appchain-cluster/loadtest.sh" ]
[ -x "$ROOT/yano/appchain-cluster/soaktest.sh" ]
[ -x "$ROOT/profiles/evidence/demo/demo.sh" ]
[ -x "$ROOT/profiles/evidence/artifacts/yano-context/yano/yano.sh" ]
[ -f "$ROOT/profiles/evidence/config/network/devnet/shelley-genesis.json" ]
[ -f "$ROOT/docs/MASTER_DEMO.md" ]
[ -f "$ROOT/docs/MASTER_DEMO_CURL.md" ]
[ -f "$ROOT/docs/MESSAGE_SUBMISSION.md" ]
[ -f "$ROOT/docs/LOAD_AND_SOAK.md" ]
[ -f "$ROOT/docs/ANCHORING_DEMO.md" ]
[ -f "$ROOT/docs/JAVA_CLI_ROADMAP.md" ]
[ -f "$ROOT/docs/PRESENTERS.md" ]
[ -f "$ROOT/docs/GOVERNANCE_DEMO.md" ]
[ -f "$ROOT/docs/PREPROD_ANCHORING.md" ]
[ -f "$ROOT/yano/yano.jar" ]
[ -f "$ROOT/yano/config/application-appchain.yml" ]
[ -f "$ROOT/yano/config/application-appchain-standard.yml" ]
[ "$(find "$ROOT/yano/plugins" -maxdepth 1 -name 'yano-appchain-showcase-*-bundle.jar' | wc -l | tr -d ' ')" = 1 ]
[ -f "$ROOT/profiles/evidence/artifacts/runner.jar" ]
[ -f "$ROOT/profiles/evidence/artifacts/yano-context/yano/yano.jar" ]
[ "$(find "$ROOT/profiles/evidence/artifacts/plugins" -name '*-bundle.jar' | wc -l | tr -d ' ')" = 3 ]
ROLE_DIGEST="$(java -cp "$ROOT/profiles/evidence/artifacts/yano-context/yano/yano.jar" \
  com.bloxbean.cardano.yano.appchain.evidence.profile.RoleEvidenceProfileCli \
  --chain evidence-chain-contract \
  --members 8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c,8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394,ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1 \
  --threshold 2 --storage-gate app-final --continuation explicit \
  --evidence-capacity 8)"
[[ "$ROLE_DIGEST" =~ ^[0-9a-f]{64}$ ]]
! grep -R '/Users/satya/work/bloxbean/yano' "$ROOT" --include='*.sh' --include='*.md' >/dev/null
PROFILES="$("$ROOT/showcase.sh" profiles)"
HELP="$("$ROOT/showcase.sh" help)"
grep -q '^light' <<< "$PROFILES"
grep -q 'config show|paths|export' <<< "$HELP"
grep -q 'chain-id: "workflow-chain"' "$ROOT/yano/config/application-appchain.yml"
grep -q 'state-machine: showcase-composite' "$ROOT/yano/config/application-appchain.yml"
grep -q 'chain-id: "authenticated-map-chain"' "$ROOT/yano/config/application-appchain.yml"
grep -q 'chain-id: "authenticated-map-jmt-chain"' "$ROOT/yano/config/application-appchain.yml"
grep -q 'state-machine: authenticated-map' "$ROOT/yano/config/application-appchain.yml"
grep -q 'com.bloxbean.cardano.yano.appchain.authenticated-map-validators' \
  "$ROOT/yano/config/application-appchain.yml"
grep -q 'addr_test1vrld3msldls64ax7c06vu85nvhk70260q970cssxzjh0hlchc79qg' \
  "$ROOT/yano/config/application-appchain.yml"
grep -q 'member join 3' "$ROOT/docs/MASTER_DEMO.md"
grep -q 'threshold set 3' "$ROOT/docs/MASTER_DEMO.md"
grep -q 'governance activate' "$ROOT/docs/MASTER_DEMO.md"
grep -q 'POST.*messages' "$ROOT/docs/MASTER_DEMO_CURL.md"
grep -q 'load-test' "$ROOT/docs/LOAD_AND_SOAK.md"
grep -q 'anchor bootstrap' "$ROOT/docs/ANCHORING_DEMO.md"
grep -q 'Python 3 standard library only' "$ROOT/README.md"

# Exercise the real packaged generator against the authoritative catalog. This
# catches a release whose validator bundle, digest mode, or generator classpath
# differs from the source-level showcase contract.
"$ROOT/showcase.sh" prepare --instance distribution-contract --nodes 3 \
  --http-base 29770 --server-base 29370 >/dev/null
MAP_ROOT="$ROOT/data/showcase/distribution-contract"
[ -s "$MAP_ROOT/authenticated-map.properties" ]
[ -s "$MAP_ROOT/authenticated-map-genesis.hex" ]
[ "$(grep -c '^yano.app-chain.chains\[8\]\.' \
  "$MAP_ROOT/authenticated-map.properties")" = 4 ]
MAP_INFO="$("$ROOT/yano/yano.sh" appchain state validators \
  --genesis-file "$MAP_ROOT/authenticated-map-genesis.hex")"
printf '%s' "$MAP_INFO" | jq -e '
  .chainId == "authenticated-map-chain" and
  .profile == "mpf-blake2b256-v1" and
  .validatorCount == 2 and
  ([.collections[].id] | sort ==
    ["attachments", "canonical-events", "governed-catalog", "gtins",
     "products", "released-products"]) and
  any(.validators[];
    .id == "product-v1" and .kind == "schema") and
  any(.validators[];
    .id == "gtin-v1" and .kind == "plugin" and
    .providerId == "gs1-gtin-v1" and
    (.artifactClosureSha256 | test("^[0-9a-f]{64}$")))
' >/dev/null
[ -s "$MAP_ROOT/authenticated-map-jmt.properties" ]
[ -s "$MAP_ROOT/authenticated-map-jmt-genesis.hex" ]
[ "$(grep -c '^yano.app-chain.chains\[9\]\.' \
  "$MAP_ROOT/authenticated-map-jmt.properties")" = 4 ]
JMT_INFO="$("$ROOT/yano/yano.sh" appchain state validators \
  --genesis-file "$MAP_ROOT/authenticated-map-jmt-genesis.hex")"
printf '%s' "$JMT_INFO" | jq -e '
  .chainId == "authenticated-map-jmt-chain" and
  .profile == "jmt-blake2b256-v1" and
  .validatorCount == 0 and
  ([.collections[].id] | sort == ["documents", "kv-open", "notes"])
' >/dev/null

# ADR-UTXO-008: the packaged light profile carries the bridge chain with its
# deterministic demo vault identity and the bundles its observers/routes need.
grep -q 'chain-id: "payment-chain-settlement"' \
  "$ROOT/yano/config/application-appchain.yml"
grep -q 'com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano' \
  "$ROOT/yano/config/application-appchain.yml"
grep -q 'com.bloxbean.cardano.yano.appchain.eutxo.indexer' \
  "$ROOT/yano/config/application-appchain.yml"
grep -q 'vault-address: "addr_test1wpwhmf5cd5pm9gsg5y8xnkyk3xue2u35ral098gd8u49g3gjpdqgr"' \
  "$ROOT/yano/config/application-appchain.yml"
grep -q 'withdrawal-address: "addr_test1vrf4896s3htkc8pzytgvvm07c2e489rtcg42f23zk5r2mjs8ge5ef"' \
  "$ROOT/yano/config/application-appchain.yml"
echo "PASS: copied showcase ZIP is self-contained and documents every demo path"
