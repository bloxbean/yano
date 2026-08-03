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
grep -q 'addr_test1vrld3msldls64ax7c06vu85nvhk70260q970cssxzjh0hlchc79qg' \
  "$ROOT/yano/config/application-appchain.yml"
grep -q 'member join 3' "$ROOT/docs/MASTER_DEMO.md"
grep -q 'threshold set 3' "$ROOT/docs/MASTER_DEMO.md"
grep -q 'governance activate' "$ROOT/docs/MASTER_DEMO.md"
grep -q 'POST.*messages' "$ROOT/docs/MASTER_DEMO_CURL.md"
grep -q 'load-test' "$ROOT/docs/LOAD_AND_SOAK.md"
grep -q 'anchor bootstrap' "$ROOT/docs/ANCHORING_DEMO.md"
grep -q 'Python 3 standard library only' "$ROOT/README.md"
echo "PASS: copied showcase ZIP is self-contained and documents every demo path"
