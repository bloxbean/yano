#!/bin/bash
# Docker Compose distribution on a devnet: build the image and ZIP from the working tree,
# then check the launcher, mounts, API, snapshots, profiles, instance ownership, older
# config/env, projection history on a fresh bundle, and an in-place upgrade from the pre-project-name layout.
# Ports: A 7281/13551, B 7282/13552, upgrade 7283/13553.
source "$(dirname "$0")/docker-common.sh"
RUN=$SP/runs/docker-dist; rm -rf "$RUN"; mkdir -p "$RUN"
A=$RUN/a; B=$RUN/b; U=$RUN/upgrade
HA=7281; NA=13551; HB=7282; NB=13552; HU=7283; NU=13553
# Last commit with the unnamed Compose project and chainstate at /app/chainstate.
LEGACY_REF=${LEGACY_REF:-2747fc7785}
trap docker_cleanup EXIT

docker info >/dev/null 2>&1 || { echo "VERDICT: FAIL (Docker is not running)"; exit 1; }
assert_ports_free $HA $NA $HB $NB $HU $NU || exit 2
ensure_docker_build || { echo "VERDICT: FAIL (image or ZIP build failed)"; exit 1; }

tip_block() { api "$1" node/tip | jq -r '.blockNumber // 0'; }
block_hash() { api "$1" "blocks/$2" | jq -r '.hash // empty'; }
wait_blocks() { # <port> <min-block>
  for i in $(seq 1 60); do [ "$(tip_block "$1")" -ge "$2" ] 2>/dev/null && return 0; sleep 1; done; return 1
}

echo "== start devnet from the bundle"
extract_bundle "$A" qa-docker-a $HA $NA
GENESIS_ZIP=$(unzip -p "$DOCKER_ZIP" '*/config/network/devnet/shelley-genesis.json' | jq -r .systemStart)
(cd "$A" && ./yano.sh start:devnet) > "$RUN/a-start.log" 2>&1
check "yano.sh start:devnet" $?
wait_http_ready $HA 120; check "ready within 120 s" $?
wait_blocks $HA 20; check "producing blocks" $?
check "data folders created under data-devnet/" \
  $([ -f "$A/data-devnet/chainstate/CURRENT" ] && [ -d "$A/data-devnet/runtime-data" ] \
    && [ -d "$A/data-devnet/appchain-chainstate" ] && [ -d "$A/data-devnet/appchain-indexers" ]; echo $?)
TOP=$(cd "$A" && ls -d chainstate-* runtime-data-* appchain-* 2>/dev/null)
check "no data folders beside compose/${TOP:+ (found: $TOP)}" $([ -z "$TOP" ]; echo $?)

echo "== API sweep"
BAD=""
for p in status network genesis node/status node/tip node/config blocks/latest blocks/1 epochs/latest \
    epochs/latest/parameters epochs/0/parameters epochs/latest/adapot epochs/adapots governance/dreps \
    governance/proposals accounts/pools devnet/snapshots history/coverage app-chain/chains node/epoch-calc-status; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$HA/api/v1/$p")
  [ "$code" = 200 ] || BAD="$BAD $p=$code"
done
for p in ui/ q/metrics; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$HA/$p"); [ "$code" = 200 ] || BAD="$BAD $p=$code"
done
[ -z "$BAD" ] || echo "  non-200:$BAD"
check "22 endpoints return 200" $([ -z "$BAD" ]; echo $?)

echo "== mounts"
MOUNTS=$(docker inspect yano-qa-docker-a --format '{{range .Mounts}}{{.Type}} {{.Destination}} {{.RW}}{{"\n"}}{{end}}')
echo "$MOUNTS" | sed 's/^/  /'
check "/app/config is read-only" $(echo "$MOUNTS" | grep -qx 'bind /app/config false'; echo $?)
check "/app/config/network is writable" $(echo "$MOUNTS" | grep -qx 'bind /app/config/network true'; echo $?)
check "chainstate mounted at /app/data/chainstate" $(echo "$MOUNTS" | grep -qx 'bind /app/data/chainstate true'; echo $?)
check "no anonymous volumes" $(echo "$MOUNTS" | grep -q '^volume'; [ $? = 1 ]; echo $?)
docker exec yano-qa-docker-a sh -c 'touch /app/config/qa-probe' 2>/dev/null; check "container cannot write /app/config" $([ $? != 0 ]; echo $?)
GENESIS_HOST=$(jq -r .systemStart "$A/config/network/devnet/shelley-genesis.json")
echo "  systemStart: bundle=$GENESIS_ZIP host=$GENESIS_HOST"
check "devnet genesis update reached the host" $([ "$GENESIS_HOST" != "$GENESIS_ZIP" ]; echo $?)

echo "== snapshot create, restore, delete"
SNAP=$(curl -s -X POST "http://localhost:$HA/api/v1/devnet/snapshot" -H 'content-type: application/json' -d '{"name":"qa"}')
SNAP_BLOCK=$(echo "$SNAP" | jq -r '.block_number // empty'); SNAP_HASH=$(block_hash $HA "$SNAP_BLOCK")
echo "  snapshot: $SNAP"
check "snapshot created in runtime-data" $([ -n "$SNAP_BLOCK" ] && [ -d "$A/data-devnet/runtime-data/snapshots/qa" ]; echo $?)
wait_blocks $HA $(( ${SNAP_BLOCK:-0} + 10 ))
BEFORE=$(tip_block $HA)
RESTORE=$(curl -s -w ' HTTP%{http_code}' -X POST "http://localhost:$HA/api/v1/devnet/restore/qa")
echo "  restore at block $BEFORE: $RESTORE"
check "restore returns to the snapshot block" $(case "$RESTORE" in (*"\"block_number\":$SNAP_BLOCK"*HTTP200) echo 0;; (*) echo 1;; esac)
wait_blocks $HA $(( ${SNAP_BLOCK:-0} + 3 )); check "production resumes after restore" $?
check "snapshot block hash unchanged" $([ -n "$SNAP_HASH" ] && [ "$(block_hash $HA "$SNAP_BLOCK")" = "$SNAP_HASH" ]; echo $?)
code=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "http://localhost:$HA/api/v1/devnet/snapshot/qa")
check "snapshot deleted" $([ "$code" = 200 ]; echo $?)

echo "== profile files from the mounted config"
cp "$A/config/application-devnet.yml" "$RUN/devnet.yml.orig"
python3 - "$A/config/application-devnet.yml" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
open(p, "w").write(s.replace("yano:\n", "yano:\n  server:\n    port: 13555\n", 1))
PY
(cd "$A" && ./yano.sh restart:devnet) >> "$RUN/a-start.log" 2>&1; wait_http_ready $HA 120
check "edited application-devnet.yml applies (server port 13555)" $([ "$(api $HA node/config | jq -r .serverPort)" = 13555 ]; echo $?)
cp "$RUN/devnet.yml.orig" "$A/config/application-devnet.yml"
printf 'yano:\n  server:\n    port: 13444\n' > "$A/config/application-qaprobe.yml"
(cd "$A" && ./yano.sh restart:devnet,qaprobe) >> "$RUN/a-start.log" 2>&1; wait_http_ready $HA 120
check "new application-qaprobe.yml applies (server port 13444)" $([ "$(api $HA node/config | jq -r .serverPort)" = 13444 ]; echo $?)
rm -f "$A/config/application-qaprobe.yml"

echo "== older config/env"
H0=$(tip_block $HA); HASH0=$(block_hash $HA "$H0")
cp "$A/config/env" "$RUN/env.orig"; echo "YANO_STORAGE_PATH=/app/chainstate" >> "$A/config/env"
(cd "$A" && ./yano.sh restart:devnet) >> "$RUN/a-start.log" 2>&1; wait_http_ready $HA 120
STORAGE=$(docker exec yano-qa-docker-a sh -c 'echo $YANO_STORAGE_PATH')
check "Compose storage path wins over config/env ($STORAGE)" $([ "$STORAGE" = /app/data/chainstate ]; echo $?)
check "chain resumed from host chainstate (block $H0 unchanged)" $([ -n "$HASH0" ] && [ "$(block_hash $HA "$H0")" = "$HASH0" ]; echo $?)
cp "$RUN/env.orig" "$A/config/env"

echo "== projection history (fresh bundle: history must be enabled from genesis)"
P=$RUN/projection
extract_bundle "$P" qa-docker-proj $HB $NB
(cd "$P" && ./yano.sh start:devnet,projection) > "$RUN/projection.log" 2>&1; wait_http_ready $HB 180
sleep 10
COV=$(api $HB history/coverage); echo "  coverage: $(echo "$COV" | cut -c1-160)"
check "projection enabled without error" $(echo "$COV" | jq -e '.enabled == true and (has("error") | not)' >/dev/null; echo $?)
check "history written to data-devnet/runtime-data/history" $([ -d "$P/data-devnet/runtime-data/history/ducklake-data" ]; echo $?)
(cd "$P" && ./yano.sh stop) >> "$RUN/projection.log" 2>&1

echo "== instance ownership"
extract_bundle "$B" qa-docker-a $HB $NB
for action in start:devnet stop restart:devnet; do
  OUTB=$(cd "$B" && ./yano.sh $action 2>&1); rc=$?
  check "same INSTANCE_NAME in another folder: $action refused" \
    $([ $rc != 0 ] && echo "$OUTB" | grep -q 'distinct INSTANCE_NAME'; echo $?)
done
check "first instance still running" $([ "$(docker inspect -f '{{.State.Running}}' yano-qa-docker-a)" = true ]; echo $?)
set_env "$B" INSTANCE_NAME qa-docker-b; QA_CONTAINERS="$QA_CONTAINERS yano-qa-docker-b"
(cd "$B" && ./yano.sh start:devnet) > "$RUN/b-start.log" 2>&1; wait_http_ready $HB 120
check "distinct INSTANCE_NAME runs side by side" $?
(cd "$B" && ./yano.sh stop) >> "$RUN/b-start.log" 2>&1
check "stopping the second leaves the first running" \
  $([ "$(docker inspect -f '{{.State.Running}}' yano-qa-docker-a 2>/dev/null)" = true ]; echo $?)
# A shared COMPOSE_PROJECT_NAME with distinct instance names must also be refused.
set_env "$A" COMPOSE_PROJECT_NAME yano-qa-docker-shared; set_env "$B" COMPOSE_PROJECT_NAME yano-qa-docker-shared
(cd "$A" && ./yano.sh restart:devnet) >> "$RUN/a-start.log" 2>&1; wait_http_ready $HA 120
for action in start:devnet stop restart:devnet; do
  OUTB=$(cd "$B" && ./yano.sh $action 2>&1); rc=$?
  check "shared COMPOSE_PROJECT_NAME: $action refused" \
    $([ $rc != 0 ] && echo "$OUTB" | grep -q 'COMPOSE_PROJECT_NAME'; echo $?)
done
check "first instance survives the shared-project attempts" \
  $([ "$(docker inspect -f '{{.State.Running}}' yano-qa-docker-a 2>/dev/null)" = true ]; echo $?)
(cd "$A" && ./yano.sh stop) >> "$RUN/a-start.log" 2>&1
check "yano.sh stop removes the container" $(docker inspect yano-qa-docker-a >/dev/null 2>&1; [ $? != 0 ]; echo $?)

echo "== data path from compose/.env with a variable reference"
E=$RUN/env-path
extract_bundle "$E" qa-docker-env $HB $NB
set_env "$E" DATA_ROOT ../external
printf '%s\n' 'YANO_CHAINSTATE_PATH=${DATA_ROOT}/chainstate' >> "$E/compose/.env"
(cd "$E" && ./yano.sh start:devnet) > "$RUN/env-path.log" 2>&1; wait_http_ready $HB 120
check "node starts with YANO_CHAINSTATE_PATH=\${DATA_ROOT}/chainstate" $?
ESRC=$(docker inspect yano-qa-docker-env --format '{{range .Mounts}}{{if eq .Destination "/app/data/chainstate"}}{{.Source}}{{end}}{{end}}' 2>/dev/null)
check "chainstate mounted from the expanded external/chainstate" \
  $(case "$ESRC" in (*/external/chainstate) [ -f "$E/external/chainstate/CURRENT" ]; echo $?;; (*) echo 1;; esac)
check "no literal \${DATA_ROOT} folder created" $([ -z "$(find "$E" -name '*DATA_ROOT*' 2>/dev/null)" ]; echo $?)
(cd "$E" && ./yano.sh stop) >> "$RUN/env-path.log" 2>&1

echo "== in-place upgrade from $LEGACY_REF"
if (cd "$REPO" && git cat-file -e "$LEGACY_REF:docker/yano.sh") 2>/dev/null; then
  extract_bundle "$U" qa-docker-up $HU $NU
  mkdir -p "$RUN/new-files"; for f in compose/yano.yml compose/yano-devnet.yml yano.sh config/env; do
    mkdir -p "$RUN/new-files/$(dirname $f)"; cp "$U/$f" "$RUN/new-files/$f"; done
  for f in compose/yano.yml compose/yano-devnet.yml yano.sh config/env; do
    (cd "$REPO" && git show "$LEGACY_REF:docker/$f") > "$U/$f"; done
  (cd "$U" && ./yano.sh start:devnet) > "$RUN/upgrade.log" 2>&1; wait_http_ready $HU 120
  LEGACY_PROJECT=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' yano-qa-docker-up 2>/dev/null)
  check "legacy layout starts as project '$LEGACY_PROJECT'" $([ "$LEGACY_PROJECT" = compose ]; echo $?)
  wait_blocks $HU 15; UB=$(tip_block $HU); UH=$(block_hash $HU "$UB")
  # Upgrade the launcher and Compose files; keep the older config/env.
  for f in compose/yano.yml compose/yano-devnet.yml yano.sh; do cp "$RUN/new-files/$f" "$U/$f"; done
  OUTU=$(cd "$U" && ./yano.sh start:devnet 2>&1); rc=$?
  check "start after upgrade explains the old container" \
    $([ $rc != 0 ] && echo "$OUTU" | grep -q "Compose project 'compose'"; echo $?)
  (cd "$U" && ./yano.sh restart:devnet) >> "$RUN/upgrade.log" 2>&1; wait_http_ready $HU 120
  check "restart moves it to project yano-qa-docker-up" \
    $([ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' yano-qa-docker-up)" = yano-qa-docker-up ]; echo $?)
  check "upgraded node kept its chain (block $UB unchanged)" \
    $([ -n "$UH" ] && [ "$(block_hash $HU "$UB")" = "$UH" ]; echo $?)
  SRC=$(docker inspect yano-qa-docker-up --format '{{range .Mounts}}{{if eq .Destination "/app/data/chainstate"}}{{.Source}}{{end}}{{end}}')
  check "upgraded node keeps its top-level chainstate-devnet/ (${SRC##*/})" \
    $([ "${SRC##*/}" = chainstate-devnet ] && [ ! -e "$U/data-devnet/chainstate" ]; echo $?)
  check "launcher explains the earlier folder layout" \
    $(grep -q 'Using chainstate-devnet/ from the earlier folder layout' "$RUN/upgrade.log"; echo $?)
  (cd "$U" && ./yano.sh stop) >> "$RUN/upgrade.log" 2>&1
else
  echo "  SKIP  upgrade: $LEGACY_REF is not in this clone"
fi

echo "yano ERROR lines (node A): $(grep -c ' ERROR ' "$A"/logs/yano.log 2>/dev/null || echo 0)"
[ ${#FAILS[@]} -eq 0 ] && echo "VERDICT: PASS" || echo "VERDICT: FAIL (${FAILS[*]})"
