#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
REPO_ROOT="$(cd "$DEMO_DIR/../.." && pwd -P)"
SOURCE="$SCRIPT_DIR/deployment-parity-e2e.sh"
WORKFLOW="$REPO_ROOT/.github/workflows/build.yml"
RELEASE_CONTRACTS="$SCRIPT_DIR/release-contracts.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/yano-deployment-parity-contract.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail 'docker is required'
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 is required'
[ -x "$SOURCE" ] || fail 'deployment-parity E2E must be executable'
bash -n "$SOURCE"

python3 - "$SOURCE" "$WORKFLOW" "$RELEASE_CONTRACTS" <<'PY'
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
workflow = Path(sys.argv[2]).read_text(encoding="utf-8")
release_contracts = Path(sys.argv[3]).read_text(encoding="utf-8")

for required in (
        '[ "${YANO_RUN_DEPLOYMENT_PARITY_E2E:-false}" = true ]',
        'yano-deployment-parity-e2e-v1',
        'COMPOSE_DATA_BASE="$ROOT/compose-data"',
        'COMPOSE_SECRET_BASE="$ROOT/compose-secrets"',
        'COMPOSE_RUNTIME_BASE="$ROOT/compose-runtime"',
        'HOST_DATA_BASE="$ROOT/host-data"',
        'HOST_SECRET_BASE="$ROOT/host-secrets"',
        'HOST_RUNTIME_BASE="$ROOT/host-runtime"',
        'if len(set(resolved)) != 6:',
        'actual.parent != root',
        'info.st_uid != os.geteuid()',
        'stat.S_IMODE(info.st_mode) != 0o700',
        'require_decimal_range COMPOSE_HTTP_BASE "$COMPOSE_HTTP_BASE" 1 65533',
        'require_decimal_range HOST_HTTP_BASE "$HOST_HTTP_BASE" 1 65533',
        'require_decimal_range SCENARIO_TIMEOUT "$SCENARIO_TIMEOUT" 10 3600',
        "coexisting deployment parity requires distinct local chain ids",
        "coexisting deployment parity requires distinct external object keys",
        'CONTINUATION_MODE="${YANO_DEPLOYMENT_PARITY_CONTINUATION_MODE:-explicit}"',
        'MACHINE_MODE="${YANO_DEPLOYMENT_PARITY_MACHINE:-standalone}"',
        '--continuation "$CONTINUATION_MODE"',
        '--machine "$MACHINE_MODE"',
        'EXPECTED_STATE_MACHINE=composite',
        'EXPECTED_WORKFLOW_CHECK=COMPOSITE_EVIDENCE_RELEASE_WORKFLOW',
        'EXPECTED_WORKFLOW_CHECK=DIRECT_EVIDENCE_SUBMISSION',
        'EXPECTED_CONTINUATION_CHECK=DIRECT_RESULT_CONTINUATION',
        'EXPECTED_CONTINUATION_CHECK=EXPLICIT_NOTIFY_CONTINUATION',
        "the object connector binds target id and effect id into immutable destination",
        'subnet_check=(python3 "$SUBNET_TOOL" subnet-check',
        'NODE_IMAGE="yano-adr013-parity-node:$RUN_TOKEN"',
        'RUNNER_IMAGE="yano-adr013-parity-runner:$RUN_TOKEN"',
        'KUBO_IMAGE="${DEMO_KUBO_IMAGE:?missing pinned Kubo demo image}"',
        'RUSTFS_IMAGE="${DEMO_RUSTFS_IMAGE:?missing pinned RustFS demo image}"',
        'printf \'header = "X-API-Key: %s"\\n\' "$api_key"',
        '--max-filesize "$max_bytes"',
        '/api/v1/plugin-operations/bundles?limit=100',
        'sample("yano_appchain_tip_height", f\'chain="{chain}"\')',
        'sample("yano_appchain_effects_open", f\'chain="{chain}"\')',
        'sample("yano_plugin_bundles", \'state="selected"\')',
        '.stats.executors == ["ipfs-pin", "kafka-publish", "objectstore-s3-object-put"]',
        'assert_executor_activity()',
        'executor metric inventory is incorrect',
        'if not 21 <= len(samples) <= 48:',
        'and .attempts >= 1 and .successes >= 1 and .inFlight == 0',
        'and .chain.stateProofsVerified == 6 and .chain.effectProofsVerified == 3',
        'and .chain.finalityBundlesVerified == $finalityBundles',
        'and passed($continuationCheck)',
        'and passed($workflowCheck)',
        '.anchor.required == true and .anchor.portableLinkageVerified == true',
        '.anchor.portableTransactionsVisibleOnAllMembers == true',
        '.anchor.portableDatumCommitmentsVerified == true',
        '.anchor.memberObservedTransactionVisibleOnAllMembers == true',
        '.anchor.memberObservedDatumCommitmentVerified == true',
        'passed("PORTABLE_ANCHOR_TXS_VISIBLE_ON_ALL_MEMBERS")',
        'passed("PORTABLE_ANCHOR_DATUM_COMMITMENTS_VERIFIED")',
        'passed("MEMBER_OBSERVED_ANCHOR_TX_VISIBLE_ON_ALL_MEMBERS")',
        'passed("MEMBER_OBSERVED_ANCHOR_DATUM_COMMITMENT_VERIFIED")',
        'stable_receipt_signature()',
        'capture_effect_inventory()',
        'cluster_state_signature()',
        'cluster_tip()',
        'assert_replay_advanced_only_tip()',
        'map(del(.tipHeight))',
        "strictly later consensus tip",
        '/effects?fromHeight=0&limit=100',
        '== ["ipfs.pin", "kafka.publish", "object.put"]',
        'and ([.effects[].effectIdHashHex] | unique | length) == 3',
        '.chainId == $chain',
        'Compose immediate replay changed the logical effect inventory',
        'host immediate replay changed the logical effect inventory',
        'Compose retained restart/replay changed the logical effect inventory',
        'host retained restart/replay changed the logical effect inventory',
        "'Compose immediate replay'",
        "'host immediate replay'",
        'Compose retained restart/replay changed app-chain state or membership',
        'host retained restart/replay changed app-chain state or membership',
        'semantic_signature()',
        'checks: ([.checks[] | {name, status}] | sort_by(.name))',
        'artifact_manifest()',
        'max_file_bytes = 512 * 1024 * 1024',
        'max_total_bytes = 1024 * 1024 * 1024',
        'while chunk := os.read(descriptor, 1024 * 1024):',
        'if consumed != before.st_size or identity(after) != identity(before):',
        'cmp -s "$ROOT/compose-artifacts.json" "$ROOT/host-artifacts.json"',
        'Compose retained restart changed its staged binary artifacts',
        'host retained restart changed its staged binary artifacts',
        'Compose retained replay did not produce exactly three attempt reports',
        'host retained replay did not produce exactly three attempt reports',
        'Compose and host connector target identities are not six distinct aliases',
        'host workflow restarted or stopped the isolated Compose connector',
        'Compose and host reports are not semantically equivalent PASS outcomes',
        'fresh isolated Compose broker did not assign the first exact Kafka offset',
        'host Kafka receipt is not the next exact record on the shared broker',
        'assert_ui_latest compose-post-restart',
        'assert_ui_latest host-post-restart',
        'cmp -s "$ROOT/compose-pre-restart-state.json" "$ROOT/compose-restarted-state.json"',
        'cmp -s "$ROOT/host-pre-restart-state.json" "$ROOT/host-restarted-state.json"',
        'WARN: isolated test diagnostics retained after uncertain cleanup',
        'if [ "$uncertain" = true ] && [ "$status" -eq 0 ]; then status=1; fi',
):
    if required not in source:
        raise SystemExit(f"missing deployment-parity contract: {required}")

numeric_validation = source.index(
    'require_decimal_range COMPOSE_HTTP_BASE "$COMPOSE_HTTP_BASE" 1 65533')
first_arithmetic = source.index('$((COMPOSE_HTTP_BASE + 1))')
if numeric_validation >= first_arithmetic:
    raise SystemExit("numeric validation must precede Bash arithmetic")
if "SUBNET_ARGS" in source:
    raise SystemExit("subnet preflight regressed to a Bash-3.2-unsafe empty array")
if '-H "X-API-Key:' in source or '--header "X-API-Key:' in source:
    raise SystemExit("private API keys must never be placed in curl argv")
if source.count('[[ "$bytes" =~ ^[0-9]+$ ]]') != 1:
    raise SystemExit("bounded response validation must have one shared implementation")
sentinel = source.index('chmod 600 "$SENTINEL"')
early_trap = source.index('trap early_cleanup EXIT INT TERM', sentinel)
first_owned_failure = source.index('mkdir -m 700 "$COMPOSE_DATA_BASE"', sentinel)
full_trap = source.index('trap cleanup EXIT INT TERM', early_trap)
if not sentinel < early_trap < first_owned_failure < full_trap:
    raise SystemExit("ownership-safe cleanup must be armed before any owned preflight can fail")
for required in (
        'remove_owned_images || uncertain=true',
        'WARN: isolated preflight diagnostics retained after uncertain cleanup',
        'NODE_IMAGE_CLAIMED=false',
        'NODE_IMAGE_CLAIMED=true',
        '[ "$claimed" = true ] || return 0',
        'actual_id="$(docker image inspect --format \'{{.Id}}\' -- "$image")"',
        'docker image rm "$image" >/dev/null 2>&1 || return 1',
        '! docker image inspect "$image" >/dev/null 2>&1'):
    if required not in source:
        raise SystemExit(f"preflight image cleanup is not fail-closed: {required}")
if "read_bytes()" in source[source.index("artifact_manifest() {"):source.index(
        "verify_live_generation() {")]:
    raise SystemExit("artifact hashing regressed to an unbounded whole-file read")
if 'local phase="$1" port="$2" latest="$3" output="$ROOT/$phase' in source:
    raise SystemExit("assert_ui_latest regressed to a set -u same-declaration expansion")
if source.count("verify_live_generation ") != 4:
    raise SystemExit("both deployments must prove ownership/inventory/metrics before and after restart")
if source.count("run_and_capture compose ") != 3:
    raise SystemExit("Compose must run once, replay immediately, and replay after restart")
if source.count("run_and_capture host ") != 3:
    raise SystemExit("host must run once, replay immediately, and replay after restart")
if source.count("assert_ui_latest ") != 4:
    raise SystemExit("both UIs must be checked before and after retained restart")
if source.count("capture_effect_inventory ") != 6:
    raise SystemExit("each deployment run/replay generation must prove its exact logical effects")
if source.count("capture_cluster_state ") != 10:
    raise SystemExit("every first/replay/restart generation must prove exact cluster state")
if source.count("assert_replay_advanced_only_tip ") != 4:
    raise SystemExit("every immediate/retained replay must preserve logical state while advancing tip")
if source.count("assert_executor_activity ") != 2:
    raise SystemExit("both deployments must prove live executor status and bounded metrics")
stable_start = source.index("stable_receipt_signature() {")
stable_end = source.index("capture_effect_inventory() {", stable_start)
stable_signature = source[stable_start:stable_end]
for dynamic_field in ("committedHeight", "stateRoot", "portableTransactionHashes",
                      "memberObservedAnchoredHeight", "memberObservedTransactionHash"):
    if dynamic_field in stable_signature:
        raise SystemExit(f"replay receipt signature includes dynamic observation field: {dynamic_field}")
for deployment in ("compose", "host"):
    report_variable = "$COMPOSE_REPORTS" if deployment == "compose" else "$HOST_REPORTS"
    post_run = source.index(
        f"run_and_capture {deployment} \"{report_variable}\"",
        source.index(f"note 'Restarting the exact {deployment if deployment == 'host' else 'Compose'}"))
    post_state = source.index(f"capture_cluster_state {deployment}-post-restart-replay", post_run)
    if post_state <= post_run:
        raise SystemExit(f"{deployment} state must be captured after its post-restart replay")

markers = (
    'demo_compose prepare',
    "capture_owned_image_ids || fail 'prepared Compose image tags are missing or changed identity'",
    'COMPOSE_PREPARED=true',
    'demo_compose config > "$ROOT/compose-preflight.yaml"',
    'COMPOSE_PROJECT="$(sed -n',
    'demo_compose up',
    'run_and_capture compose "$COMPOSE_REPORTS"',
    "note 'Restarting the exact Compose stack from retained data'",
    'demo_compose stop',
    'demo_compose up',
    '# The Compose stack deliberately remains live here.',
    'demo_host prepare',
    'demo_host up',
    'run_and_capture host "$HOST_REPORTS"',
    "note 'Restarting the exact host deployment from retained data'",
    'demo_host stop',
    'demo_host up',
    "note 'Stopping isolated host first, then the exact Compose project'",
    'activate_compose',
    'demo_compose stop',
    'remove_owned_root',
)
position = -1
for marker in markers:
    try:
        next_position = source.index(marker, position + 1)
    except ValueError as error:
        raise SystemExit(f"missing or misordered live workflow marker: {marker}") from error
    if next_position <= position:
        raise SystemExit(f"misordered live workflow marker: {marker}")
    position = next_position

cleanup_start = source.index("\ncleanup() {") + 1
cleanup_end = source.index("trap cleanup EXIT INT TERM", cleanup_start)
cleanup = source[cleanup_start:cleanup_end]
host_cleanup = cleanup.index("demo_host stop")
compose_cleanup = cleanup.index("demo_compose stop")
uncertain_guard = cleanup.index('if [ "$uncertain" = false ]')
root_cleanup = cleanup.index("remove_owned_root")
if not host_cleanup < compose_cleanup < uncertain_guard < root_cleanup:
    raise SystemExit("failure cleanup must stop host, stop exact Compose, then conditionally remove state")


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
        "timeout-minutes: 180",
        "YANO_RUN_DEPLOYMENT_PARITY_E2E: 'true'",
        "YANO_DEPLOYMENT_PARITY_CONTINUATION_MODE: direct",
        "run: app/appchain-effects-demo/tests/deployment-parity-e2e.sh",
):
    if required not in e2e_job:
        raise SystemExit(f"mandatory deployment-parity CI contract is missing: {required}")
failover_run = e2e_job.index("run: app/appchain-effects-demo/tests/effect-failover-e2e.sh")
parity_run = e2e_job.index("run: app/appchain-effects-demo/tests/deployment-parity-e2e.sh")
if failover_run >= parity_run:
    raise SystemExit("deployment parity must run after the fenced-failover E2E")
if workflow.count("run: app/appchain-effects-demo/tests/deployment-parity-e2e.sh") != 1:
    raise SystemExit("mandatory deployment parity must have exactly one workflow invocation")
if "- effect-failover-e2e" not in job_block("milestone-1-release-acceptance"):
    raise SystemExit("Milestone 1 join no longer requires the job containing deployment parity")
if release_contracts.count("deployment-parity-contract.sh") != 1 \
        or 'bash "$SCRIPT_DIR/$test"' not in release_contracts:
    raise SystemExit("release-contracts.sh no longer invokes this static contract")
PY

ARITHMETIC_MARKER="$TMP/arithmetic-injection-executed"
MALICIOUS_HTTP_BASE='1+$(touch '"$ARITHMETIC_MARKER"')'
if YANO_RUN_DEPLOYMENT_PARITY_E2E=true \
    YANO_DEPLOYMENT_PARITY_COMPOSE_HTTP_BASE="$MALICIOUS_HTTP_BASE" \
    "$SOURCE" >"$TMP/arithmetic-injection.out" 2>&1; then
  fail 'command-substitution-shaped numeric input unexpectedly passed'
fi
[ ! -e "$ARITHMETIC_MARKER" ] || fail 'shell arithmetic executed attacker-controlled input'
grep -Fq 'COMPOSE_HTTP_BASE must be a canonical bounded decimal integer' \
  "$TMP/arithmetic-injection.out" \
  || fail 'numeric injection rejection diagnostic is missing'

# Exercise early collision cleanup without touching the real Docker daemon.
FAKE_BIN="$TMP/fake-bin"
mkdir -m 700 "$FAKE_BIN"
cat > "$FAKE_BIN/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
last="${!#}"
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
case "${1:-}:${2:-}" in
  compose:version) exit 0 ;;
  image:inspect)
    if [ "${FAKE_PREEXISTING_COLLISION:-false}" = true ] \
        && [[ "$last" == yano-adr013-parity-node:* ]]; then
      printf '%s\n' "$FAKE_IMAGE_ID"
      exit 0
    fi
    if [ -f "$FAKE_TAG_STATE" ] && [ "$last" = "$(cat "$FAKE_TAG_STATE")" ]; then
      printf '%s\n' "$FAKE_IMAGE_ID"
      exit 0
    fi
    exit 1
    ;;
  image:rm)
    [ -f "$FAKE_TAG_STATE" ] && [ "$3" = "$(cat "$FAKE_TAG_STATE")" ]
    if [ "${FAKE_RM_FAIL:-false}" = true ]; then exit 1; fi
    rm -f "$FAKE_TAG_STATE"
    exit 0
    ;;
  network:ls) exit 0 ;;
  network:inspect) exit 1 ;;
  *) printf 'unexpected fake docker invocation: %s\n' "$*" >&2; exit 2 ;;
esac
EOF
chmod 700 "$FAKE_BIN/docker"
FAKE_IMAGE_ID=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

COLLISION_PARENT="$TMP/collision-parent"
COLLISION_LOG="$TMP/collision-docker.log"
COLLISION_STATE="$TMP/collision-tag-state"
mkdir -m 700 "$COLLISION_PARENT"
: > "$COLLISION_LOG"
if PATH="$FAKE_BIN:$PATH" FAKE_IMAGE_ID="$FAKE_IMAGE_ID" \
    FAKE_DOCKER_LOG="$COLLISION_LOG" FAKE_TAG_STATE="$COLLISION_STATE" \
    FAKE_PREEXISTING_COLLISION=true YANO_RUN_DEPLOYMENT_PARITY_E2E=true \
    YANO_DEPLOYMENT_PARITY_TMPDIR="$COLLISION_PARENT" \
    "$SOURCE" >"$TMP/collision.out" 2>&1; then
  fail 'pre-existing random image-tag collision unexpectedly passed'
fi
grep -Fq 'random isolated image tag already exists:' "$TMP/collision.out" \
  || fail 'pre-existing random image-tag collision diagnostic is missing'
if grep -Eq '^image rm ' "$COLLISION_LOG"; then
  fail 'collision cleanup attempted to remove an unclaimed pre-existing image tag'
fi
[ -z "$(find "$COLLISION_PARENT" -mindepth 1 -maxdepth 1 -print -quit)" ] \
  || fail 'collision preflight retained a root despite making no owned image change'

printf '%s\n' 'PASS: isolated Compose/host deployment-parity E2E contract'
