#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd -P)"
COMPOSE_FILE="$SCRIPT_DIR/connector-fault-matrix.compose.yaml"
GRADLEW="$REPO_ROOT/gradlew"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || fail "docker is required"
docker compose version >/dev/null 2>&1 || fail "docker compose is required"
command -v python3 >/dev/null 2>&1 || fail "python3 is required"
command -v curl >/dev/null 2>&1 || fail "curl is required"
[ -x "$GRADLEW" ] || fail "Gradle wrapper is not executable"
[ -f "$COMPOSE_FILE" ] || fail "connector fault-matrix Compose file is missing"

# Do not let ambient Java option variables put credentials or unrelated agent
# configuration into process arguments. This harness passes only explicit,
# non-secret integration properties and secret-file paths.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS

# shellcheck disable=SC1091
. "$DEMO_DIR/config/images.env"
FAULT_KAFKA_IMAGE="${DEMO_KAFKA_IMAGE:?missing Kafka image pin}"
FAULT_RUSTFS_IMAGE="${DEMO_RUSTFS_IMAGE:?missing RustFS image pin}"
FAULT_KUBO_IMAGE="${DEMO_KUBO_IMAGE:?missing Kubo image pin}"
export FAULT_KAFKA_IMAGE FAULT_RUSTFS_IMAGE FAULT_KUBO_IMAGE

RUN_ID="$(python3 - <<'PY'
import secrets
print(secrets.token_hex(6))
PY
)"
PROJECT="yano-connector-fault-$RUN_ID"
FAULT_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/yano-connector-fault.XXXXXX")"
FAULT_ROOT="$(cd "$FAULT_ROOT" && pwd -P)"
FAULT_HOST_UID="$(id -u)"
FAULT_HOST_GID="$(id -g)"
[[ "$FAULT_HOST_UID" =~ ^[1-9][0-9]*$ ]] || fail "fault harness requires a non-root host user"
[[ "$FAULT_HOST_GID" =~ ^[1-9][0-9]*$ ]] || fail "fault harness requires a non-root primary group"
KAFKA_STATE_FILE="$FAULT_ROOT/kafka-submitted-ref"
export FAULT_ROOT FAULT_HOST_UID FAULT_HOST_GID

chmod 0700 "$FAULT_ROOT"
mkdir -m 0700 "$FAULT_ROOT/data" "$FAULT_ROOT/data/kafka" \
  "$FAULT_ROOT/data/rustfs" "$FAULT_ROOT/data/kubo" "$FAULT_ROOT/secrets" \
  "$FAULT_ROOT/logs"
python3 - "$FAULT_ROOT/kafka-passwd" "$FAULT_ROOT/kafka-group" \
  "$FAULT_HOST_UID" "$FAULT_HOST_GID" <<'PY'
import os
import sys

uid, gid = map(int, sys.argv[3:])
documents = {
    sys.argv[1]: f"root:!:0:0:root:/root:/sbin/nologin\nyano-kafka:!:{uid}:{gid}:Yano Kafka:/nonexistent:/sbin/nologin\n",
    sys.argv[2]: f"root:!:0:\nyano-kafka:!:{gid}:\n",
}
for path, content in documents.items():
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(descriptor, content.encode("ascii"))
    finally:
        os.close(descriptor)
PY
python3 - "$FAULT_ROOT/secrets/s3-access-key" \
  "$FAULT_ROOT/secrets/s3-secret-key" "$FAULT_ROOT/secrets/s3-iam-master-key" "$RUN_ID" <<'PY'
import os
import secrets
import sys

access = "yano" + sys.argv[4]
secret = secrets.token_hex(24)
values = ((sys.argv[1], access), (sys.argv[2], secret), (sys.argv[3], secrets.token_hex(32)))
for path, value in values:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(descriptor, (value + "\n").encode("ascii"))
    finally:
        os.close(descriptor)
PY
touch "$KAFKA_STATE_FILE"
chmod 0600 "$KAFKA_STATE_FILE"

compose() {
  docker compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"
}

STACK_ATTEMPTED=false
cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  if [ "$status" -ne 0 ] && [ "$STACK_ATTEMPTED" = true ]; then
    printf '\nConnector service status at failure:\n' >&2
    compose ps >&2 || true
    printf '\nBounded connector logs at failure:\n' >&2
    compose logs --no-color --tail=80 kafka rustfs kubo >&2 || true
  fi
  # Teardown is intentionally unconditional. A signal or daemon failure can
  # interrupt `compose up` after it has created only part of this exact project.
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  if [ "${YANO_KEEP_CONNECTOR_FAULT_ROOT:-false}" = true ]; then
    printf 'Retained isolated connector root: %s\n' "$FAULT_ROOT" >&2
  else
    rm -rf "$FAULT_ROOT"
  fi
  exit "$status"
}

handle_signal() {
  signal_status="$1"
  trap - HUP INT TERM
  exit "$signal_status"
}

trap cleanup EXIT
trap 'handle_signal 129' HUP
trap 'handle_signal 130' INT
trap 'handle_signal 143' TERM

allocate_ports() {
  ports="$(python3 - <<'PY'
import socket

sockets = []
try:
    for _ in range(3):
        sock = socket.socket()
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
        sock.bind(("127.0.0.1", 0))
        sockets.append(sock)
    print(" ".join(str(sock.getsockname()[1]) for sock in sockets))
finally:
    for sock in sockets:
        sock.close()
PY
)"
  read -r FAULT_KAFKA_PORT FAULT_S3_PORT FAULT_KUBO_PORT <<EOF
$ports
EOF
  export FAULT_KAFKA_PORT FAULT_S3_PORT FAULT_KUBO_PORT
}

wait_healthy() {
  service="$1"
  attempts=0
  while [ "$attempts" -lt 75 ]; do
    container="$(compose ps -q "$service" 2>/dev/null || true)"
    if [ -n "$container" ]; then
      state="$(docker inspect --format \
        '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "$container" 2>/dev/null || true)"
      [ "$state" != healthy ] || return 0
      case "$state" in
        exited|dead) return 1 ;;
      esac
    fi
    attempts=$((attempts + 1))
    sleep 2
  done
  return 1
}

wait_rustfs_ready() {
  attempts=0
  while [ "$attempts" -lt 120 ]; do
    if curl --noproxy '*' --max-time 2 -fsS \
        "http://127.0.0.1:$FAULT_S3_PORT/health/ready" >/dev/null 2>&1; then
      return 0
    fi
    container="$(compose ps -q rustfs 2>/dev/null || true)"
    if [ -n "$container" ]; then
      state="$(docker inspect --format '{{.State.Status}}' "$container" 2>/dev/null || true)"
      case "$state" in exited|dead) return 1;; esac
    fi
    attempts=$((attempts + 1))
    sleep 1
  done
  return 1
}

docker pull "$FAULT_RUSTFS_IMAGE" >/dev/null \
  || fail "pinned multi-architecture RustFS image could not be pulled"
image_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' \
  "$FAULT_RUSTFS_IMAGE")"
server_platform="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')"
case "$image_platform:$server_platform" in
  linux/amd64:linux/amd64|linux/amd64:linux/x86_64|\
  linux/arm64:linux/arm64|linux/arm64:linux/aarch64) ;;
  *) fail "pulled RustFS image is not native to the Docker server" ;;
esac

docker pull "$FAULT_KUBO_IMAGE" >/dev/null \
  || fail "pinned multi-architecture Kubo image could not be pulled"
kubo_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' \
  "$FAULT_KUBO_IMAGE")"
case "$kubo_platform:$server_platform" in
  linux/amd64:linux/amd64|linux/amd64:linux/x86_64|\
  linux/arm64:linux/arm64|linux/arm64:linux/aarch64) ;;
  *) fail "pulled Kubo image is not native to the Docker server" ;;
esac

started=false
attempt=1
while [ "$attempt" -le 3 ]; do
  allocate_ports
  STACK_ATTEMPTED=true
  if compose up -d >/dev/null 2>&1; then
    if wait_healthy kafka && wait_rustfs_ready && wait_healthy kubo; then
      started=true
      break
    fi
  fi
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  attempt=$((attempt + 1))
done
[ "$started" = true ] || fail "isolated connector services did not become healthy"

KAFKA_ENDPOINT="127.0.0.1:$FAULT_KAFKA_PORT"
S3_ENDPOINT="http://127.0.0.1:$FAULT_S3_PORT"
KUBO_ENDPOINT="http://127.0.0.1:$FAULT_KUBO_PORT"
S3_ACCESS_FILE="$FAULT_ROOT/secrets/s3-access-key"
S3_SECRET_FILE="$FAULT_ROOT/secrets/s3-secret-key"

KAFKA_MODULE=":appchain-kafka"
S3_MODULE=":appchain-objectstore-s3"
IPFS_MODULE=":appchain-ipfs"
RUNNER_MODULE=":appchain-evidence-demo-runner"
KAFKA_EXEC_CLASS="com.bloxbean.cardano.yano.appchain.kafka.effects.KafkaPublishExecutorTest"
KAFKA_REAL_CLASS="com.bloxbean.cardano.yano.appchain.kafka.effects.KafkaPublishRealIntegrationTest"
KAFKA_AUDIT_REAL_CLASS="com.bloxbean.cardano.yano.appchain.examples.evidence.demo.KafkaDemoClientRealIntegrationTest"
S3_EXEC_CLASS="com.bloxbean.cardano.yano.appchain.objectstore.s3.effects.S3ObjectPutExecutorTest"
S3_REAL_CLASS="com.bloxbean.cardano.yano.appchain.objectstore.s3.effects.S3ObjectPutExecutorS3IntegrationTest"
S3_AWS_CLASS="com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.aws.AwsS3ObjectStoreClientTest"
S3_AWS_REAL_CLASS="com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.aws.AwsS3RealIntegrationTest"
S3_SECRET_CLASS="com.bloxbean.cardano.yano.appchain.objectstore.s3.testing.IntegrationSecretFilesTest"
S3_BOOTSTRAP_REAL_CLASS="com.bloxbean.cardano.yano.appchain.examples.evidence.demo.S3BucketBootstrapperRealIntegrationTest"
IPFS_EXEC_CLASS="com.bloxbean.cardano.yano.appchain.ipfs.effects.IpfsPinExecutorTest"
IPFS_KUBO_CLASS="com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo.KuboIpfsPinClientTest"
IPFS_REAL_CLASS="com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo.KuboIpfsPinClientRealIntegrationTest"

module_dir() {
  case "$1" in
    "$KAFKA_MODULE") printf '%s\n' "$REPO_ROOT/appchain/extensions/appchain-kafka" ;;
    "$S3_MODULE") printf '%s\n' "$REPO_ROOT/appchain/extensions/appchain-objectstore-s3" ;;
    "$IPFS_MODULE") printf '%s\n' "$REPO_ROOT/appchain/extensions/appchain-ipfs" ;;
    "$RUNNER_MODULE") printf '%s\n' "$REPO_ROOT/appchain/examples/appchain-evidence-demo-runner" ;;
    *) fail "unknown Gradle module: $1" ;;
  esac
}

assert_junit() {
  module="$1"
  class_name="$2"
  minimum="$3"
  expected="$4"
  result="$(module_dir "$module")/build/test-results/test/TEST-$class_name.xml"
  python3 - "$result" "$minimum" "$expected" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

path = pathlib.Path(sys.argv[1])
minimum = int(sys.argv[2])
expected = [name for name in sys.argv[3].split("|") if name]
if not path.is_file():
    raise SystemExit(f"missing JUnit XML: {path}")
root = ET.parse(path).getroot()
tests = int(root.attrib.get("tests", "0"))
skipped = int(root.attrib.get("skipped", "0"))
failures = int(root.attrib.get("failures", "0"))
errors = int(root.attrib.get("errors", "0"))
cases = root.findall(".//testcase")
names = {case.attrib.get("name", "") for case in cases}
if tests < minimum or len(cases) < minimum:
    raise SystemExit(f"JUnit suite ran only {tests} tests: {path}")
if skipped or failures or errors:
    raise SystemExit(
        f"JUnit suite not clean (skip={skipped}, fail={failures}, error={errors}): {path}")
for case in cases:
    if case.find("skipped") is not None or case.find("failure") is not None \
            or case.find("error") is not None:
        raise SystemExit(f"JUnit testcase was not executed successfully: {path}")
missing = [name for name in expected if name not in names]
if missing:
    raise SystemExit(f"JUnit suite did not execute expected tests {missing}: {path}")
PY
}

assert_log_has_no_credentials() {
  log_file="$1"
  python3 - "$log_file" "$S3_ACCESS_FILE" "$S3_SECRET_FILE" <<'PY'
import pathlib
import sys

log = pathlib.Path(sys.argv[1]).read_bytes()
for secret_path in sys.argv[2:]:
    secret = pathlib.Path(secret_path).read_bytes().strip()
    if secret and secret in log:
        raise SystemExit(1)
PY
}

run_gradle() {
  label="$1"
  module="$2"
  shift 2
  printf '\n[%s]\n' "$label"
  log_name="$(printf '%s' "$label" | tr '[:upper:] /' '[:lower:]--' \
    | tr -cd 'a-z0-9._-')"
  log_file="$FAULT_ROOT/logs/$log_name.log"
  if "$GRADLEW" --console=plain --stacktrace \
      "${module}:cleanTest" "${module}:test" "$@" >"$log_file" 2>&1; then
    assert_log_has_no_credentials "$log_file" \
      || fail "a connector test log retained credential material"
    grep -E '^BUILD SUCCESSFUL' "$log_file" | tail -n 1
  else
    if ! assert_log_has_no_credentials "$log_file"; then
      fail "a failing connector test log retained credential material"
    fi
    printf 'Gradle/JUnit failure (last 160 lines):\n' >&2
    tail -n 160 "$log_file" >&2
    return 1
  fi
}

run_real_case() {
  label="$1"
  module="$2"
  class_name="$3"
  method="$4"
  shift 4
  run_gradle "$label" "$module" --tests "$class_name.$method" "$@"
  assert_junit "$module" "$class_name" 1 "$method()"
}

printf 'Connector fault matrix project: %s\n' "$PROJECT"
printf 'Connector ports: Kafka=%s S3=%s Kubo=%s\n' \
  "$FAULT_KAFKA_PORT" "$FAULT_S3_PORT" "$FAULT_KUBO_PORT"

# Deterministic seams cover provider states that are unsafe or unreliable to
# induce against local real services. These are production adapter/executor
# paths, and the XML checks make missing or skipped coverage fatal.
run_gradle "Kafka deterministic authentication/timeout/retry seams" \
  "$KAFKA_MODULE" --tests "$KAFKA_EXEC_CLASS"
assert_junit "$KAFKA_MODULE" "$KAFKA_EXEC_CLASS" 22 \
  "authentication|unknown acknowledgement|transient network|submittedReceiptIsStrictlyValidatedAndNeverRepublished()"

run_gradle "S3 deterministic auth/timeout/unknown-ack seams" \
  "$S3_MODULE" --tests "$S3_EXEC_CLASS" --tests "$S3_AWS_CLASS" \
  --tests "$S3_SECRET_CLASS"
S3_EXEC_EXPECTED="unknownAcknowledgementReconcilesCommittedVersionWithoutDuplicate()"
S3_EXEC_EXPECTED+="|normalizedProviderErrorsPreserveFrozenDisposition()"
assert_junit "$S3_MODULE" "$S3_EXEC_CLASS" 20 \
  "$S3_EXEC_EXPECTED"
S3_AWS_EXPECTED="normalizesProviderAndTransportFailuresByOperation()"
S3_AWS_EXPECTED+="|sanitizesCredentialProviderFailure()"
S3_AWS_EXPECTED+="|reconcilesMalformedAcknowledgementAfterProviderReturned()"
assert_junit "$S3_MODULE" "$S3_AWS_CLASS" 25 \
  "$S3_AWS_EXPECTED"
S3_SECRET_EXPECTED="readsOwnerOnlyRegularFileAndStripsOneTrailingLineEnding()"
S3_SECRET_EXPECTED+="|rejectsSymlinkAndNonPrintableContent()"
S3_SECRET_EXPECTED+="|rejectsGroupReadablePosixFile()"
assert_junit "$S3_MODULE" "$S3_SECRET_CLASS" 3 \
  "$S3_SECRET_EXPECTED"

run_gradle "Kubo/IPFS deterministic auth/timeout/unknown-ack seams" \
  "$IPFS_MODULE" --tests "$IPFS_EXEC_CLASS" --tests "$IPFS_KUBO_CLASS"
assert_junit "$IPFS_MODULE" "$IPFS_EXEC_CLASS" 9 \
  "unknownAcknowledgementReconcilesOnlyObservedState()|disabledTargetAndProviderFailuresUseFrozenCodes()"
IPFS_KUBO_EXPECTED="normalizesProbeAndMutationStatusesWithoutProviderText()"
IPFS_KUBO_EXPECTED+="|boundsRequestTimeoutsAndClassifiesMutationUncertainty()"
IPFS_KUBO_EXPECTED+="|treatsMalformedOrMismatchedSuccessfulAddAcknowledgementsAsUnknown()"
assert_junit "$IPFS_MODULE" "$IPFS_KUBO_CLASS" 24 \
  "$IPFS_KUBO_EXPECTED"

KAFKA_PROPS=(
  "-Dyano.kafka.integration.enabled=true"
  "-Dyano.kafka.integration.bootstrap=$KAFKA_ENDPOINT"
  "-Dyano.kafka.integration.topic=yano-fault-$RUN_ID"
  "-Dyano.kafka.integration.run-id=$RUN_ID"
  "-Dyano.kafka.integration.state-file=$KAFKA_STATE_FILE"
)
KAFKA_AUDIT_PROPS=(
  "-Dyano.kafka.audit.integration.enabled=true"
  "-Dyano.kafka.audit.integration.bootstrap=$KAFKA_ENDPOINT"
  "-Dyano.kafka.audit.integration.topic=yano-audit-$RUN_ID"
)
S3_PROPS=(
  "-Dyano.s3.integration.enabled=true"
  "-Dyano.s3.integration.endpoint=$S3_ENDPOINT"
  "-Dyano.s3.integration.access-key-file=$S3_ACCESS_FILE"
  "-Dyano.s3.integration.secret-key-file=$S3_SECRET_FILE"
  "-Dyano.s3.integration.run-id=$RUN_ID"
  "-Dyano.s3.integration.disposable-service=true"
)
IPFS_PROPS=(
  "-Dyano.ipfs.integration.enabled=true"
  "-Dyano.ipfs.integration.endpoint=$KUBO_ENDPOINT"
  "-Dyano.ipfs.integration.run-id=$RUN_ID"
)

run_real_case "Kafka real acknowledgement and sink/effect coexistence" \
  "$KAFKA_MODULE" "$KAFKA_REAL_CLASS" \
  publishesEffectsAndFinalizedBlocksThroughOneRealBroker "${KAFKA_PROPS[@]}"
run_real_case "Kafka bounded physical retry audit" \
  "$RUNNER_MODULE" "$KAFKA_AUDIT_REAL_CLASS" \
  auditsExactRetryWindowAndRejectsRecordOrPartitionDrift "${KAFKA_AUDIT_PROPS[@]}"
run_real_case "Kafka seed before broker restart" \
  "$KAFKA_MODULE" "$KAFKA_REAL_CLASS" seedsAcknowledgedPublishForBrokerRestart \
  "${KAFKA_PROPS[@]}" -Dyano.kafka.integration.phase=seed
compose restart kafka >/dev/null
wait_healthy kafka || fail "Kafka did not recover after restart"
run_real_case "Kafka reconcile after broker restart" \
  "$KAFKA_MODULE" "$KAFKA_REAL_CLASS" \
  reconcilesAcknowledgedPublishAfterBrokerRestartWithoutRepublishing \
  "${KAFKA_PROPS[@]}" -Dyano.kafka.integration.phase=reconcile
compose stop kafka >/dev/null
run_real_case "Kafka unavailable service" \
  "$KAFKA_MODULE" "$KAFKA_REAL_CLASS" \
  unavailableBrokerReturnsUnknownAcknowledgementWithoutReceipt \
  "${KAFKA_PROPS[@]}" -Dyano.kafka.integration.phase=unavailable
compose start kafka >/dev/null
wait_healthy kafka || fail "Kafka did not recover after unavailable-service test"

run_gradle "RustFS real conditional creation, conflict, checksum, versioning and Object Lock" \
  "$S3_MODULE" \
  --tests "$S3_REAL_CLASS.executorConfirmsOnceReconcilesAfterRestartAndNeverResurrectsDeletedKey" \
  --tests "$S3_AWS_REAL_CLASS.conditionalVersionedPromotionAndNoResurrectionProfile" \
  --tests "$S3_AWS_REAL_CLASS.governanceObjectLockIsActuallyEnforcedAndBypassIsExplicit" \
  "${S3_PROPS[@]}"
assert_junit "$S3_MODULE" "$S3_REAL_CLASS" 1 \
  "executorConfirmsOnceReconcilesAfterRestartAndNeverResurrectsDeletedKey()"
assert_junit "$S3_MODULE" "$S3_AWS_REAL_CLASS" 2 \
  "conditionalVersionedPromotionAndNoResurrectionProfile()|governanceObjectLockIsActuallyEnforcedAndBypassIsExplicit()"
run_real_case "RustFS retention-control drift fails closed" \
  "$RUNNER_MODULE" "$S3_BOOTSTRAP_REAL_CLASS" \
  retentionControlDriftFailsClosedAgainstRealRustFs \
  -Dyano.s3.bootstrap.integration.enabled=true \
  -Dyano.s3.bootstrap.integration.endpoint="$S3_ENDPOINT" \
  -Dyano.s3.bootstrap.integration.access-key-file="$S3_ACCESS_FILE" \
  -Dyano.s3.bootstrap.integration.secret-key-file="$S3_SECRET_FILE"
run_real_case "RustFS seed before service restart" \
  "$S3_MODULE" "$S3_REAL_CLASS" seedsDurableObjectForServiceRestart \
  "${S3_PROPS[@]}" -Dyano.s3.integration.phase=seed
compose restart rustfs >/dev/null
wait_rustfs_ready || fail "RustFS did not recover after restart"
run_real_case "RustFS reconcile after service restart" \
  "$S3_MODULE" "$S3_REAL_CLASS" \
  reconcilesDurableObjectAfterServiceRestartWithoutAnotherVersion \
  "${S3_PROPS[@]}" -Dyano.s3.integration.phase=reconcile
compose stop rustfs >/dev/null
run_real_case "RustFS unavailable service" \
  "$S3_MODULE" "$S3_REAL_CLASS" unavailableServiceFailsClosedWithRetryableNormalizedCode \
  "${S3_PROPS[@]}" -Dyano.s3.integration.phase=unavailable
compose start rustfs >/dev/null
wait_rustfs_ready || fail "RustFS did not recover after unavailable-service test"

run_real_case "Kubo real new/already/indirect-upgrade/missing pin paths" \
  "$IPFS_MODULE" "$IPFS_REAL_CLASS" \
  provesNewExistingIndirectUpgradeAndMissingPinPathsAgainstRealKubo \
  "${IPFS_PROPS[@]}"
run_real_case "Kubo seed before daemon restart" \
  "$IPFS_MODULE" "$IPFS_REAL_CLASS" seedsDurablePinForDaemonRestart \
  "${IPFS_PROPS[@]}" -Dyano.ipfs.integration.phase=seed
compose restart kubo >/dev/null
wait_healthy kubo || fail "Kubo did not recover after restart"
run_real_case "Kubo reconcile after daemon restart" \
  "$IPFS_MODULE" "$IPFS_REAL_CLASS" reconcilesDurablePinAfterDaemonRestart \
  "${IPFS_PROPS[@]}" -Dyano.ipfs.integration.phase=reconcile
compose stop kubo >/dev/null
run_real_case "Kubo unavailable service" \
  "$IPFS_MODULE" "$IPFS_REAL_CLASS" unavailableDaemonNormalizesProbeAndMutationUncertainty \
  "${IPFS_PROPS[@]}" -Dyano.ipfs.integration.phase=unavailable
compose start kubo >/dev/null
wait_healthy kubo || fail "Kubo did not recover after unavailable-service test"

printf '\nPASS: connector fault matrix (real restart/reconciliation/unavailability + deterministic seams)\n'
