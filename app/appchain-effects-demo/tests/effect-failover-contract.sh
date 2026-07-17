#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$DEMO_DIR/../.." && pwd)"
TOOL="$DEMO_DIR/tools/effect_failover.py"
FAULT_MODEL="$SCRIPT_DIR/support/compose-post-ack-fault.yaml"
FAILOVER_MODEL="$SCRIPT_DIR/support/compose-fenced-failover.yaml"
WORKFLOW="$REPO_ROOT/.github/workflows/build.yml"
RELEASE_CONTRACTS="$SCRIPT_DIR/release-contracts.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/yano-effect-failover-contract.XXXXXX")"
TMP="$(cd "$TMP" && pwd -P)"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then stat -c '%a' "$1"; else stat -f '%Lp' "$1"; fi
}

command -v docker >/dev/null 2>&1 || fail 'docker is required'
command -v jq >/dev/null 2>&1 || fail 'jq is required'
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 is required'

bash -n "$SCRIPT_DIR/effect-failover-e2e.sh"
python3 - "$SCRIPT_DIR/effect-failover-e2e.sh" "$WORKFLOW" \
  "$DEMO_DIR/demo.sh" "$RELEASE_CONTRACTS" <<'PY'
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
workflow = Path(sys.argv[2]).read_text(encoding="utf-8")
demo = Path(sys.argv[3]).read_text(encoding="utf-8")
release_contracts = Path(sys.argv[4]).read_text(encoding="utf-8")
preflight = '"$DEMO_DIR/demo.sh" config --deployment compose'
resolve = 'PROJECT_NAME="$(sed -n \'s/^DEMO_PROJECT_NAME=//p\' "$ENV_FILE")"'
startup = '"$DEMO_DIR/demo.sh" up --deployment compose'
try:
    positions = tuple(source.index(marker) for marker in (preflight, resolve, startup))
except ValueError as error:
    raise SystemExit(f"missing failover preflight marker: {error}") from error
if positions != tuple(sorted(positions)) or len(set(positions)) != 3:
    raise SystemExit("Compose project must be resolved before the destructive startup")
validation = source.index(
    'require_decimal_range DEMO_HTTP_BASE "$DEMO_HTTP_BASE" 1 65533')
first_arithmetic = source.index('"$((DEMO_HTTP_BASE + 1))"')
if validation >= first_arithmetic:
    raise SystemExit("numeric environment validation must precede shell arithmetic")
for required in (
        "info.st_uid != os.geteuid()",
        "info.st_nlink != 1",
        "stat.S_IMODE(info.st_mode) != 0o600",
        "require_decimal_range DEMO_SERVER_BASE \"$DEMO_SERVER_BASE\" 1 65533",
        "require_decimal_range DEMO_SCENARIO_TIMEOUT_SECONDS",
        "[ \"$(grep -c '^DEMO_PROJECT_NAME=' \"$ENV_FILE\")\" -eq 1 ]",
        "if [ -n \"$PROJECT_NAME\" ] && [ -f \"$ENV_FILE\" ]; then"):
    if required not in source:
        raise SystemExit(f"missing failover cleanup precondition: {required}")
if 'subnet_check=(python3 "$FAILOVER_TOOL" subnet-check' not in source:
    raise SystemExit("failover subnet check must use an always-nonempty Bash 3.2 command array")
if "SUBNET_ARGS" in source:
    raise SystemExit("failover subnet check regressed to an unsafe empty Bash 3.2 array")
if "/opt/kafka/" + "bin" in source or "kafka_" + "end_offset" in source:
    raise SystemExit("failover E2E must not depend on Kafka image CLI scripts")
for required in (
        "audit-kafka --config /run/demo/runner.properties",
        "dc_base run --rm --no-deps -T connector-init",
        "--expected-records \"$expected\" --expected-effect-id \"$effect_id\"",
        "and .topic == \"evidence.available.v1\"",
        "and ((.records | map(.recordDigest) | unique | length) == 1)",
        "and ((.records | [.[].offset]) == [range(0; $expected)])"):
    if required not in source:
        raise SystemExit(f"missing bounded Kafka audit contract: {required}")

for required in (
        "bounded_get() {",
        "bounded_authenticated_get() {",
        "--max-filesize \"$max_bytes\"",
        "printf 'header = \"X-API-Key: %s\"\\n' \"$api_key\"",
        "/api/v1/plugin-operations/bundles?limit=100",
        'and .totals.selectedBundles == 6',
        'and .totals.failedBundles == 0',
        'sample("yano_appchain_tip_height", f\'chain="{chain}"\')',
        'sample("yano_appchain_effects_open", f\'chain="{chain}"\')',
        'sample("yano_plugin_bundles", \'state="selected"\')',
        "/api/v1/reports/latest",
        'cmp -s "$LATEST_REPORT" "$output"',
        'and .chain.membersVerified == 3',
        'and .chain.effectProofsVerified == 3',
        'wait_cluster_agreement pre-restart "$PRE_RESTART_STATE"',
        'cmp -s "$PRE_RESTART_STATE" "$POST_RESTART_STATE"',
        'audit_kafka_exact 2 "$EFFECT_ID" "$ROOT/kafka-after-restart.json"',
        'export DEMO_OBSERVABILITY=false',
        '.[0].Config.Labels["com.docker.compose.project"] == $project',
        'connector-init evidence-ui kafka kubo leader-warmup rustfs',
        'capture_failover_container_ownership "$RESTART_OWNERSHIP_BEFORE"',
        'dc_failover stop --timeout 30',
        'assert_owned_containers_stopped "$RESTART_OWNERSHIP_BEFORE"',
        'dc_failover up --detach --wait --wait-timeout 360',
        'cmp -s "$RESTART_OWNERSHIP_BEFORE" "$RESTART_OWNERSHIP_AFTER"',
        "for node in 0 2; do",
        'effects.executor.identity=evidence-executor-1',
        'node 1 did not remain sole owner of the exact executor partition',
        'assert_failover_effect_topology pre-restart',
        'assert_failover_effect_topology restarted',
        '= "$RETAINED_EFFECT_OWNER"',
        'assert_plugin_operations_all_nodes restarted',
        'assert_metrics_all_nodes restarted'):
    if required not in source:
        raise SystemExit(f"missing live Phase 1.7 acceptance evidence: {required}")
if source.count('--continuation explicit') != 6:
    raise SystemExit("the legacy failover suite must pin the explicit continuation profile")
if source.count("assert_initial_effect_topology ") != 1:
    raise SystemExit("initial node-0 effect ownership must be asserted exactly once")
if source.count("assert_failover_effect_topology ") != 2:
    raise SystemExit("transferred node-1 ownership must be asserted before and after restart")
if source.count("assert_plugin_operations_all_nodes ") != 2:
    raise SystemExit("plugin inventory/operations must be asserted on both live generations")
if source.count("assert_metrics_all_nodes ") != 2:
    raise SystemExit("metrics must be asserted on both live generations")
if source.count("assert_evidence_ui_report ") != 2:
    raise SystemExit("evidence UI must be asserted before and after restart")
if source.count('"$DEMO_DIR/demo.sh" up --deployment compose') != 1:
    raise SystemExit("base demo up is allowed only for the initial node-0 topology")
if source.count('"$DEMO_DIR/demo.sh" stop --deployment compose') != 1:
    raise SystemExit("demo stop must be reserved for final lease-aware cleanup")
if source.count("dc_failover stop --timeout 30") != 1 \
        or source.count("dc_failover up --detach --wait --wait-timeout 360") != 1:
    raise SystemExit("retained restart must stop and start exactly once through the fenced overlay")
if '-H "X-API-Key:' in source or '--header "X-API-Key:' in source:
    raise SystemExit("private API key must not be placed in curl argv")

initial_topology = source.index("assert_initial_effect_topology initial")
fault_recreate = source.index("dc_fault up --detach")
pre_transferred_topology = source.index("assert_failover_effect_topology pre-restart")
pre_restart = source.index("assert_evidence_ui_report pre-restart")
restart_stop = source.index("note 'Stopping and restarting the complete isolated stack", pre_restart)
lease_probe = source.index('"$DEMO_DIR/demo.sh" probe --deployment compose', restart_stop)
ownership_before = source.index(
    'capture_failover_container_ownership "$RESTART_OWNERSHIP_BEFORE"', lease_probe)
overlay_stop = source.index("dc_failover stop --timeout 30", ownership_before)
stopped_proof = source.index(
    'assert_owned_containers_stopped "$RESTART_OWNERSHIP_BEFORE"', overlay_stop)
overlay_up = source.index(
    "dc_failover up --detach --wait --wait-timeout 360", stopped_proof)
restart_probe = source.index('"$DEMO_DIR/demo.sh" probe --deployment compose', overlay_up)
ownership_after = source.index(
    'capture_failover_container_ownership "$RESTART_OWNERSHIP_AFTER"', restart_probe)
ownership_cmp = source.index(
    'cmp -s "$RESTART_OWNERSHIP_BEFORE" "$RESTART_OWNERSHIP_AFTER"', ownership_after)
transferred_topology = source.index("assert_failover_effect_topology restarted", ownership_cmp)
post_restart = source.index(
    'cmp -s "$PRE_RESTART_STATE" "$POST_RESTART_STATE"', transferred_topology)
final_stop = source.index('"$DEMO_DIR/demo.sh" stop --deployment compose', post_restart)
owned_cleanup = source.index("remove_owned_root", final_stop)
if not (initial_topology < fault_recreate < pre_transferred_topology < pre_restart
        < restart_stop < lease_probe
        < ownership_before < overlay_stop < stopped_proof < overlay_up < restart_probe
        < ownership_after < ownership_cmp < transferred_topology < post_restart
        < final_stop < owned_cleanup):
    raise SystemExit("fenced retained restart and lease-aware isolated cleanup are misordered")
if source.find('"$DEMO_DIR/demo.sh" up --deployment compose', pre_restart) != -1:
    raise SystemExit("retained restart must never implicitly fail ownership back through base up")
if source.index('export DEMO_FENCED_NODE0_CONFIG="$FENCED_CONFIG"') >= overlay_up \
        or source.index('export DEMO_REPLACEMENT_NODE1_CONFIG="$REPLACEMENT_CONFIG"') >= overlay_up:
    raise SystemExit("fenced private configs must be exported before overlay restart")


def job_block(name):
    lines = workflow.splitlines()
    marker = f"  {name}:"
    try:
        start = lines.index(marker)
    except ValueError as error:
        raise SystemExit(f"workflow has no {name} job") from error
    end = len(lines)
    for index in range(start + 1, len(lines)):
        if re.fullmatch(r"  [a-z0-9][a-z0-9-]*:", lines[index]):
            end = index
            break
    return "\n".join(lines[start:end])


e2e_job = job_block("effect-failover-e2e")
for required in (
        "runs-on: ubuntu-24.04",
        "timeout-minutes: 180",
        "github.event_name == 'schedule'",
        "inputs.scope == 'connector-runtime' || inputs.scope == 'all'",
        "actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5",
        "actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9",
        "YANO_RUN_EFFECT_FAILOVER_E2E: 'true'",
        "run: app/appchain-effects-demo/tests/effect-failover-e2e.sh"):
    if required not in e2e_job:
        raise SystemExit(f"mandatory devnet E2E workflow contract is missing: {required}")
if "\n    if: >-" not in e2e_job:
    raise SystemExit("devnet E2E scheduling must remain explicit")
for forbidden in ("connector-rustfs-", "rustfs-release-image-import",
                  "DEMO_USE_PREBUILT_RUSTFS_IMAGE", "DEMO_PREBUILT_RUSTFS_IMAGE_ID"):
    if forbidden in workflow:
        raise SystemExit(f"CI still carries a removed third-party build path: {forbidden}")
if workflow.count("run: app/appchain-effects-demo/tests/effect-failover-e2e.sh") != 1:
    raise SystemExit("mandatory devnet E2E must have exactly one workflow invocation")
commit_build_job = job_block("commit-build")
for required in (
        "- name: Verify ADR-013 demo release contracts",
        "run: bash app/appchain-effects-demo/tests/release-contracts.sh"):
    if required not in commit_build_job:
        raise SystemExit(f"commit build omits the ADR-013 release contract gate: {required}")
if workflow.count("run: bash app/appchain-effects-demo/tests/release-contracts.sh") != 1:
    raise SystemExit("ADR-013 release contracts must run exactly once in commit-build")
if release_contracts.count("effect-failover-contract.sh") != 1 \
        or 'bash "$SCRIPT_DIR/$test"' not in release_contracts:
    raise SystemExit("release-contracts.sh no longer invokes the failover static contract")

acceptance_job = job_block("milestone-1-release-acceptance")
required_acceptance_needs = """needs:
      - commit-build
      - native-plugin-conformance
      - connector-fault-matrix
      - effect-failover-e2e"""
if required_acceptance_needs not in acceptance_job:
    raise SystemExit("Milestone 1 acceptance does not depend on every release gate")
for required in (
        "always()",
        "github.event_name != 'schedule'",
        "(github.event_name != 'workflow_dispatch' || inputs.scope == 'all')",
        "COMMIT_BUILD_RESULT: ${{ needs.commit-build.result }}",
        "NATIVE_PLUGIN_RESULT: ${{ needs.native-plugin-conformance.result }}",
        "CONNECTOR_FAULT_RESULT: ${{ needs.connector-fault-matrix.result }}",
        "EFFECT_FAILOVER_RESULT: ${{ needs.effect-failover-e2e.result }}",
        'test "$COMMIT_BUILD_RESULT" = success',
        'test "$NATIVE_PLUGIN_RESULT" = success',
        'test "$CONNECTOR_FAULT_RESULT" = success',
        'test "$EFFECT_FAILOVER_RESULT" = success'):
    if required not in acceptance_job:
        raise SystemExit(f"Milestone 1 acceptance is missing fail-closed evidence: {required}")

for required in ('docker pull "$image"',
                 'for image in "$DEMO_RUSTFS_IMAGE" "$DEMO_KUBO_IMAGE"'):
    if required not in demo:
        raise SystemExit(f"demo launcher does not pull pinned dependencies: {required}")
for forbidden in ("Dockerfile.rustfs", "Dockerfile.kubo", "rustfs_local_image.sh",
                  "rustfs_prebuilt_image.sh", "kubo_source.py"):
    if forbidden in demo:
        raise SystemExit(f"demo launcher still builds a third-party dependency: {forbidden}")
PY

ARITHMETIC_MARKER="$TMP/arithmetic-injection-executed"
MALICIOUS_HTTP_BASE='1+$(touch '"$ARITHMETIC_MARKER"')'
if YANO_RUN_EFFECT_FAILOVER_E2E=true \
    YANO_EFFECT_FAILOVER_HTTP_BASE="$MALICIOUS_HTTP_BASE" \
    "$SCRIPT_DIR/effect-failover-e2e.sh" \
    >"$TMP/arithmetic-injection.out" 2>&1; then
  fail 'command-substitution-shaped numeric input unexpectedly passed'
fi
[ ! -e "$ARITHMETIC_MARKER" ] \
  || fail 'shell arithmetic executed attacker-controlled input'
grep -Fq 'DEMO_HTTP_BASE must be a canonical bounded decimal integer' \
  "$TMP/arithmetic-injection.out" \
  || fail 'numeric injection rejection diagnostic is missing'

python3 "$TOOL" subnet-check --candidate 172.30.114.0/24 \
  --existing 172.17.0.0/16 --existing 172.30.115.0/24 >/dev/null \
  || fail 'disjoint subnet was rejected'
if python3 "$TOOL" subnet-check --candidate 172.30.114.0/24 \
    --existing 172.30.114.128/25 >"$TMP/subnet.out" 2>&1; then
  fail 'overlapping Docker subnet unexpectedly passed'
fi
grep -Fq 'overlaps existing subnet' "$TMP/subnet.out" \
  || fail 'overlap rejection diagnostic is missing'
if python3 "$TOOL" subnet-check --candidate 172.30.114.1/24 \
    >"$TMP/noncanonical.out" 2>&1; then
  fail 'non-canonical candidate subnet unexpectedly passed'
fi
grep -Fq 'malformed or non-canonical' "$TMP/noncanonical.out" \
  || fail 'non-canonical subnet diagnostic is missing'

INVALID_ROOT="$TMP/invalid-evidence"
if DEMO_SKIP_BUILD=true DEMO_DATA_ROOT="$INVALID_ROOT/data" \
    DEMO_SECRET_ROOT="$INVALID_ROOT/secrets" DEMO_RUNTIME_ROOT="$INVALID_ROOT/runtime" \
    "$DEMO_DIR/demo.sh" config --instance invalid-evidence --evidence-id 'Not-Lowercase' \
    >"$TMP/invalid-evidence.out" 2>&1; then
  fail 'invalid evidence id unexpectedly passed'
fi
grep -Fq 'DEMO_EVIDENCE_ID must match' "$TMP/invalid-evidence.out" \
  || fail 'invalid evidence id diagnostic is missing'
[ ! -e "$INVALID_ROOT" ] || fail 'invalid evidence id created a managed root'

SOURCE="$TMP/source"
DERIVED="$TMP/derived"
mkdir -p "$SOURCE" "$DERIVED"
chmod 700 "$SOURCE" "$DERIVED"
cat > "$SOURCE/node0.properties" <<'EOF'
config_ordinal=275
yano.app-chain.chains[0].signing-key=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
yano.app-chain.chains[0].members=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb,cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
yano.app-chain.chains[0].effects.result.signers=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
yano.app-chain.chains[0].effects.executor.enabled=true
yano.app-chain.chains[0].effects.executor.identity=evidence-executor-0
yano.app-chain.chains[0].effects.executor.types=object.put,ipfs.pin,kafka.publish
yano.app-chain.chains[0].effects.executor.tick-ms=500
yano.app-chain.chains[0].effects.executors.kafka.enabled=true
yano.app-chain.chains[0].effects.executors.kafka.targets.primary.secret=test-secret
yano.app-chain.chains[0].effects.executors.objectstore-s3.enabled=true
yano.app-chain.chains[0].effects.executors.ipfs.enabled=true
EOF
cat > "$SOURCE/node1.properties" <<'EOF'
config_ordinal=275
yano.block-producer.enabled=false
yano.app-chain.chains[0].signing-key=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
yano.app-chain.chains[0].members=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb,cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
yano.app-chain.chains[0].effects.result.signers=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
yano.app-chain.chains[0].effects.executor.enabled=false
EOF
chmod 600 "$SOURCE"/*.properties

ln -s "$SOURCE" "$TMP/source-link"
if python3 "$TOOL" derive --node0 "$TMP/source-link/node0.properties" \
    --node1 "$SOURCE/node1.properties" --output-directory "$DERIVED" \
    >"$TMP/symlink-parent.out" 2>&1; then
  fail 'derive accepted a config reached through a symlinked parent directory'
fi
grep -Fq 'config path must be absolute and non-symlink' "$TMP/symlink-parent.out" \
  || fail 'symlinked-parent rejection diagnostic is missing'

cp "$SOURCE/node1.properties" "$SOURCE/node1-bad-signers.properties"
sed -i.bak 's/,bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb$/,cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc/' \
  "$SOURCE/node1-bad-signers.properties"
rm "$SOURCE/node1-bad-signers.properties.bak"
chmod 600 "$SOURCE/node1-bad-signers.properties"
if python3 "$TOOL" derive --node0 "$SOURCE/node0.properties" \
    --node1 "$SOURCE/node1-bad-signers.properties" --output-directory "$DERIVED" \
    >"$TMP/mismatched-signers.out" 2>&1; then
  fail 'derive accepted a divergent or over-broad result signer policy'
fi
grep -Fq 'result signer policy' "$TMP/mismatched-signers.out" \
  || fail 'result signer mismatch diagnostic is missing'

python3 "$TOOL" derive --node0 "$SOURCE/node0.properties" \
  --node1 "$SOURCE/node1.properties" --output-directory "$DERIVED" \
  > "$TMP/derived.env"
FENCED="$(sed -n 's/^FENCED_CONFIG=//p' "$TMP/derived.env")"
REPLACEMENT="$(sed -n 's/^REPLACEMENT_CONFIG=//p' "$TMP/derived.env")"
[ "$(mode "$FENCED")" = 600 ] && [ "$(mode "$REPLACEMENT")" = 600 ] \
  || fail 'derived configuration is not owner-only'
grep -Fxq 'yano.app-chain.chains[0].effects.executor.enabled=false' "$FENCED" \
  || fail 'fenced config does not disable execution'
if grep -Fq 'test-secret' "$FENCED"; then
  fail 'fenced config retained connector credentials'
fi
grep -Fxq 'yano.app-chain.chains[0].effects.executor.identity=evidence-executor-1' \
  "$REPLACEMENT" || fail 'replacement identity is wrong'
grep -Fxq 'yano.app-chain.chains[0].effects.executor.types=object.put,ipfs.pin,kafka.publish' \
  "$REPLACEMENT" || fail 'replacement partition is not exact'
grep -Fq 'test-secret' "$REPLACEMENT" \
  || fail 'replacement lost its private connector configuration'
python3 "$TOOL" verify --fenced "$FENCED" --replacement "$REPLACEMENT" >/dev/null

if python3 "$TOOL" derive --node0 "$SOURCE/node0.properties" \
    --node1 "$SOURCE/node1.properties" --output-directory "$DERIVED" \
    >"$TMP/rederive.out" 2>&1; then
  fail 'derive overwrote an existing private failover config'
fi

export DEMO_SKIP_BUILD=true
export DEMO_DATA_ROOT="$TMP/demo-data"
export DEMO_SECRET_ROOT="$TMP/demo-secrets"
export DEMO_RUNTIME_ROOT="$TMP/demo-runtime"
export DEMO_HTTP_BASE=38070
export DEMO_UI_PORT=38080
export DEMO_KAFKA_PORT=39092
export DEMO_S3_PORT=39000
export DEMO_IPFS_PORT=35001
export DEMO_PROMETHEUS_PORT=39090
export DEMO_GRAFANA_PORT=33000
export DEMO_CONNECTOR_SUBNET=172.30.214.0/24
export DEMO_S3_IP=172.30.214.10
export DEMO_KUBO_IP=172.30.214.11
INSTANCE=failover-contract
"$DEMO_DIR/demo.sh" config --instance "$INSTANCE" \
  --evidence-id fresh-failover-contract > /dev/null
RUNTIME="$DEMO_RUNTIME_ROOT/networks/devnet/$INSTANCE/compose"
SECRETS="$DEMO_SECRET_ROOT/networks/devnet/$INSTANCE/compose"
ENV_FILE="$RUNTIME/compose.env"
PROJECT="$(sed -n 's/^DEMO_PROJECT_NAME=//p' "$ENV_FILE")"
grep -Fxq 'demo.evidence-id=fresh-failover-contract' \
  "$RUNTIME/runner-compose.properties" || fail 'custom evidence id was not rendered'

mkdir -p "$SECRETS/effect-fault" "$SECRETS/derived"
chmod 700 "$SECRETS/effect-fault" "$SECRETS/derived"
python3 "$TOOL" derive --node0 "$SECRETS/nodes-compose/node0.properties" \
  --node1 "$SECRETS/nodes-compose/node1.properties" \
  --output-directory "$SECRETS/derived" > "$TMP/compose-derived.env"
export DEMO_EFFECT_FAULT_DIRECTORY="$SECRETS/effect-fault"
export DEMO_FENCED_NODE0_CONFIG="$(sed -n 's/^FENCED_CONFIG=//p' "$TMP/compose-derived.env")"
export DEMO_REPLACEMENT_NODE1_CONFIG="$(sed -n 's/^REPLACEMENT_CONFIG=//p' "$TMP/compose-derived.env")"

docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$DEMO_DIR/compose.yaml" \
  config --format json > "$TMP/base.json"
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$DEMO_DIR/compose.yaml" \
  -f "$FAULT_MODEL" config --format json > "$TMP/fault.json"
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$DEMO_DIR/compose.yaml" \
  -f "$FAILOVER_MODEL" config --format json > "$TMP/failover.json"

jq -e '
  .services."yano-0".environment.JAVA_OPTS
  | contains("yano.test.effect-runtime") | not
' "$TMP/base.json" >/dev/null || fail 'ordinary Compose model activates the test seam'
jq -e --arg source "$DEMO_EFFECT_FAULT_DIRECTORY" '
  (.services."yano-0".environment.JAVA_OPTS
    | contains("-Dyano.test.enabled=true")
      and contains("post-confirmed-pause=v1")
      and contains("post-confirmed-pause.type=kafka.publish"))
  and (.services."yano-0".volumes
    | any(.source == $source and .target == "/run/yano-effect-fault"))
' "$TMP/fault.json" >/dev/null || fail 'fault overlay is not explicit and isolated'
jq -e --arg fenced "$DEMO_FENCED_NODE0_CONFIG" \
  --arg replacement "$DEMO_REPLACEMENT_NODE1_CONFIG" '
  (.services."yano-0".volumes
    | any(.source == $fenced and .target == "/run/demo/node.properties"
      and .read_only == true))
  and (.services."yano-1".volumes
    | any(.source == $replacement and .target == "/run/demo/node.properties"
      and .read_only == true))
  and (.services."yano-1".networks | has("connectors"))
  and (.services."yano-2".networks | has("connectors") | not)
' "$TMP/failover.json" >/dev/null || fail 'fenced failover Compose model is incorrect'

printf '%s\n' 'PASS: effect failover contracts, subnet overlap guard, and Compose overlays'
