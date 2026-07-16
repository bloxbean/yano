#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/yano-compose-contract.XXXXXX")"
TMP="$(cd "$TMP" && pwd -P)"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || fail "docker is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

export DEMO_SKIP_BUILD=true
export DEMO_DATA_ROOT="$TMP/data"
export DEMO_SECRET_ROOT="$TMP/secrets"
export DEMO_RUNTIME_ROOT="$TMP/runtime"

"$DEMO_DIR/demo.sh" config --instance contract --observability >/dev/null
ENV_FILE="$TMP/runtime/networks/devnet/contract/compose/compose.env"
JSON="$TMP/compose.json"
docker compose --env-file "$ENV_FILE" -f "$DEMO_DIR/compose.yaml" \
  --profile observability --profile tools config --format json > "$JSON"

PROJECT_NAME="$(sed -n 's/^DEMO_PROJECT_NAME=//p' "$ENV_FILE")"
PROJECT_SUFFIX="${PROJECT_NAME##*-}"
[[ "$PROJECT_SUFFIX" =~ ^[0-9a-f]{8}$ ]] \
  || fail "Compose project lacks a bounded path-derived suffix"
[ "$PROJECT_NAME" = "yano-effects-devnet-contract-$PROJECT_SUFFIX" ] \
  || fail "Compose project is not scoped by network, instance and data path"
jq -e --arg project "$PROJECT_NAME" '
  .name == $project
  and .services."yano-0".environment.YANO_PROFILE == "devnet"
  and .services."yano-1".environment.YANO_PROFILE == "devnet"
  and .services."yano-2".environment.YANO_PROFILE == "devnet"
  and .services."leader-warmup".command == ["sleep 25"]
' "$JSON" >/dev/null || fail "Compose profile/warmup/project propagation is incorrect"

jq -e '
  . as $root
  | ["kafka","minio","minio-init","kubo","prometheus","grafana"] as $thirdParty
  | all($thirdParty[]; . as $name
      | ($root.services[$name].image
          | test("^[^@]+@sha256:[0-9a-f]{64}$")))
' "$JSON" >/dev/null || fail "a third-party image is not digest pinned"

jq -e '
  [ .services[] | .ports[]? ]
  | length > 0 and all(.[]; .host_ip == "127.0.0.1")
' "$JSON" >/dev/null || fail "all published ports must bind to loopback"

jq -e '
  . as $root
  | all(.services[];
      if ((.ports // []) | length) > 0 then
        all((.networks | keys)[]; ($root.networks[.].internal // false) == false)
      else true end)
' "$JSON" >/dev/null \
  || fail "a published service is attached to an internal, host-unroutable network"

jq -e '
  all(.services[];
      ((.privileged // false) == false)
      and ((.network_mode // "") != "host")
      and ([.volumes[]?.source // ""] | all(.[]; contains("docker.sock") | not)))
' "$JSON" >/dev/null || fail "privileged, host-network, or Docker-socket access found"

jq -e '
  .services.minio.networks.connectors.ipv4_address == "172.30.13.10"
  and .services.kubo.networks.connectors.ipv4_address == "172.30.13.11"
  and (.services."yano-0".networks | has("connectors"))
  and (.services."yano-1".networks | has("connectors") | not)
  and (.services."yano-2".networks | has("connectors") | not)
  and (.services."connector-init".networks | keys == ["connectors"])
  and (.services."connector-init".networks | has("cluster") | not)
' "$JSON" >/dev/null || fail "connector network ownership is incorrect"

jq -e '
  . as $root
  | ["kafka","minio","kubo","yano-0","yano-1","yano-2","evidence-ui",
   "prometheus","grafana"] as $longRunning
  | all($longRunning[]; . as $name
      | $root.services[$name].read_only == true
        and ($root.services[$name].security_opt | index("no-new-privileges:true") != null)
        and ($root.services[$name].cap_drop | index("ALL") != null)
        and ($root.services[$name].healthcheck.test | length > 0))
' "$JSON" >/dev/null || fail "a long-running service misses a hardening or health contract"

jq -e '
  (.services.kafka.tmpfs | any(startswith("/opt/kafka/config:")))
  and (.services.kafka.tmpfs | any(startswith("/opt/kafka/logs:")))
  and all([.services."yano-0",.services."yano-1",.services."yano-2"][];
      .tmpfs | any(startswith("/tmp:") and contains("exec")))
' "$JSON" >/dev/null || fail "Kafka writable tmpfs mounts are incomplete"

jq -e '
  (.services."evidence-ui".healthcheck.test | index("http://127.0.0.1:7080/healthz") != null)
  and .services."connector-init".command[0] == "init-connectors"
  and .services."yano-1".depends_on."leader-warmup".condition == "service_completed_successfully"
  and .services."leader-warmup".depends_on."yano-0".condition == "service_healthy"
  and all([.services."yano-0",.services."yano-1",.services."yano-2"][];
      .environment.QUARKUS_CONFIG_LOCATIONS == "file:/run/demo/node.properties")
' "$JSON" >/dev/null || fail "startup ordering/config/health contract is incorrect"

jq -e '
  [ .services."yano-0", .services."yano-1", .services."yano-2"
    | .volumes[]
    | select(.target == "/run/demo/shelley-genesis.json") ] as $genesis
  | ($genesis | length) == 3
    and ($genesis | map(.source) | unique | length) == 1
    and all($genesis[]; .read_only == true)
' "$JSON" >/dev/null || fail "nodes do not share one read-only Shelley genesis"

jq -e '
  . as $root
  | all([0,1,2][]; . as $i
    | ($root.services["yano-" + ($i|tostring)].volumes
      | any(.target == "/app/chainstate/app-chain")))
' "$JSON" >/dev/null || fail "L1 and app-chain stores are not separate nested mounts"

EXPECTED_L1="$TMP/data/networks/devnet/l1/compose"
EXPECTED_APP="$TMP/data/networks/devnet/instances/contract/compose/app-chain"
EXPECTED_GENESIS="$TMP/data/networks/devnet/l1/shared/shelley-genesis.json"
jq -e --arg l1 "$EXPECTED_L1" --arg app "$EXPECTED_APP" --arg genesis "$EXPECTED_GENESIS" '
  . as $root
  | all([0,1,2][]; . as $i
      | ($i | tostring) as $n
      | ($root.services["yano-" + $n].volumes
          | any(.source == ($l1 + "/node" + $n) and .target == "/app/chainstate"))
        and ($root.services["yano-" + $n].volumes
          | any(.source == ($app + "/node" + $n) and .target == "/app/chainstate/app-chain"))
        and ($root.services["yano-" + $n].volumes
          | any(.source == $genesis and .target == "/run/demo/shelley-genesis.json"
              and .read_only == true)))
' "$JSON" >/dev/null || fail "network/shared-L1/instance app-chain bind layout is incorrect"

jq -e '
  .services.scenario.profiles == ["tools"]
  and .services.prometheus.profiles == ["observability"]
  and .services.grafana.profiles == ["observability"]
' "$JSON" >/dev/null || fail "optional profiles are not explicit"

grep -Fq 'verify_compose_loopback_surfaces' "$DEMO_DIR/demo.sh" \
  || fail "Compose startup does not verify host-loopback reachability"

jq -e '
  .services."evidence-ui" as $ui
  | ($ui.networks | keys == ["evidence_ui"])
    and ($ui.networks | has("cluster") | not)
    and ($ui.networks | has("connectors") | not)
    and ([ $ui.volumes[] | select(.target | startswith("/run/secrets")) ] | length == 0)
    and ([ $ui.volumes[] | select(.target == "/var/lib/yano-demo/reports"
        and .read_only == true) ] | length == 1)
    and ([ $ui.volumes[] | select(.target == "/run/demo/runner.properties"
        and .read_only == true) ] | length == 1)
' "$JSON" >/dev/null || fail "evidence UI is not credential-free/read-only"

jq -e '
  all([.services.scenario,.services."connector-init"][];
      [.volumes[] | select(.target == "/run/secrets/yano-api-key")] | length == 0)
' "$JSON" >/dev/null || fail "scenario tooling received the full Yano admin key"

NODE_DIR="$TMP/secrets/networks/devnet/contract/compose/nodes-compose"
[ "$(grep -hF 'effects.executor.enabled=true' "$NODE_DIR"/*.properties | wc -l | tr -d ' ')" -eq 1 ] \
  || fail "exactly one node must own the executor"
grep -Fxq 'yano.app-chain.chains[0].anchor.max-interval-minutes=60' \
  "$NODE_DIR/node0.properties" \
  || fail "Compose anchor does not use the network profile safety interval"
jq -e '
  .anchor.enabled == true
  and .anchor.everyBlocks == 1
  and .anchor.maxIntervalMinutes == 60
' "$TMP/data/networks/devnet/instances/contract/compose/appchain-identity.json" >/dev/null \
  || fail "Compose app-chain identity does not pin its anchor cadence"
grep -Fq 'objectstore-s3.targets.archive.endpoint=http://172.30.13.10:9000' \
  "$NODE_DIR/node0.properties" || fail "node 0 lacks numeric MinIO endpoint"
grep -Fq 'ipfs.targets.local.api-url=http://172.30.13.11:5001' \
  "$NODE_DIR/node0.properties" || fail "node 0 lacks numeric Kubo endpoint"
if grep -Eq 'effects\.executors\.(kafka|objectstore-s3|ipfs)' \
    "$NODE_DIR/node1.properties" "$NODE_DIR/node2.properties"; then
  fail "a follower received connector configuration"
fi

RUNNER="$TMP/runtime/networks/devnet/contract/compose/runner-compose.properties"
grep -Fq '/run/secrets/minio-runner-access-key' "$RUNNER" \
  || fail "runner does not use its dedicated S3 identity"
if grep -Eq '^ui\.' "$RUNNER"; then
  fail "scenario runner configuration contains UI-only keys"
fi
if grep -Fq 'demo.yano.api-key-file' "$RUNNER"; then
  fail "scenario runner configuration contains the full Yano admin key path"
fi
grep -Fq 'minio-executor' "$RUNNER" && fail "runner received executor credentials"
grep -Fq 'minio-runner' "$NODE_DIR/node0.properties" \
  && fail "executor node received runner credentials"

for spec in \
  "s3.target-id|objectstore-s3.targets.archive.target-id|s3-compose-devnet-contract-$PROJECT_SUFFIX" \
  "ipfs.target-id|ipfs.targets.local.target-id|ipfs-compose-devnet-contract-$PROJECT_SUFFIX" \
  "kafka.target-id|kafka.targets.primary.target-id|kafka-compose-devnet-contract-$PROJECT_SUFFIX"; do
  IFS='|' read -r runner_key executor_key expected <<EOF
$spec
EOF
  runner_id="$(sed -n "s/^${runner_key}=//p" "$RUNNER")"
  executor_id="$(sed -n "s/^.*${executor_key}=//p" "$NODE_DIR/node0.properties")"
  [ "$runner_id" = "$expected" ] && [ "$executor_id" = "$expected" ] \
    || fail "runner/executor target identity mismatch for $runner_key"
done

for secret in "$TMP/secrets/networks/devnet/contract/compose"/*; do
  [ -f "$secret" ] || continue
  value="$(tr -d '\r\n' < "$secret")"
  ! grep -Fq "$value" "$ENV_FILE" || fail "secret value appears in Compose env"
  ! grep -Fq "$value" "$JSON" || fail "secret value appears in resolved Compose model"
done

for policy in "$DEMO_DIR/config/services/minio-runner-policy.json" \
  "$DEMO_DIR/config/services/minio-executor-policy.json"; do
  jq -e '[.Statement[].Action[]] | all(.[];
      test("Delete|Lifecycle|Admin|PutObjectRetention") | not)' "$policy" >/dev/null \
    || fail "forbidden MinIO action in $(basename "$policy")"
done
jq -e '[.Statement[].Action[]] | index("s3:PutObject") != null' \
  "$DEMO_DIR/config/services/minio-runner-policy.json" >/dev/null \
  || fail "runner cannot stage an object"
jq -e '.Statement[] | select(.Resource | type == "string" and contains("evidence-archive"))
    | .Action | index("s3:PutObject") == null' \
  "$DEMO_DIR/config/services/minio-runner-policy.json" >/dev/null \
  || fail "runner can write archive objects"
jq -e '.Statement[] | select(.Resource | type == "string" and contains("evidence-staging"))
    | .Action | index("s3:PutObject") == null' \
  "$DEMO_DIR/config/services/minio-executor-policy.json" >/dev/null \
  || fail "executor can write staging objects"

jq -e '.panels[] | select(.title == "Open effects")
    | .targets[0].expr == "max(yano_appchain_effects_open)"' \
  "$DEMO_DIR/dashboards/evidence-effects.json" >/dev/null \
  || fail "logical open effects are summed across replicas"

DATASOURCE="$DEMO_DIR/config/services/grafana/provisioning/datasources/prometheus.yml"
[ -f "$DATASOURCE" ] || fail "Grafana Prometheus datasource provisioning is missing"
grep -Fq 'uid: yano-prometheus' "$DATASOURCE" \
  || fail "Grafana datasource UID does not match the dashboard"
grep -Fq 'url: http://prometheus:9090' "$DATASOURCE" \
  || fail "Grafana datasource does not target the Compose Prometheus service"
jq -e 'all(.panels[]; .datasource.uid == "yano-prometheus")' \
  "$DEMO_DIR/dashboards/evidence-effects.json" >/dev/null \
  || fail "a dashboard panel uses an unprovisioned datasource"

printf 'PASS: Compose deployment contract\n'
