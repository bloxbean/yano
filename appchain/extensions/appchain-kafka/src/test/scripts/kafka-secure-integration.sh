#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
MODULE_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd -P)"
ROOT_DIR="$(cd "$MODULE_DIR/../../.." && pwd -P)"
COMPOSE_FILE="$MODULE_DIR/src/test/resources/kafka-secure-integration.compose.yaml"
# shellcheck disable=SC1091
. "$ROOT_DIR/app/appchain-effects-demo/config/images.env"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
require() { command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"; }
for command in docker openssl keytool python3; do require "$command"; done
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 is required'

WORK="$(mktemp -d "${TMPDIR:-/tmp}/yano-kafka-secure.XXXXXX")"
CERT_DIR="$WORK/certs"
mkdir -p "$CERT_DIR"
chmod 700 "$WORK" "$CERT_DIR"

free_port() {
  python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

export KAFKA_SECURE_PROJECT="yano-kafka-secure-$(openssl rand -hex 5)"
export KAFKA_SECURE_IMAGE="$DEMO_KAFKA_SECURE_TEST_IMAGE"
export KAFKA_SECURE_TLS_PORT="$(free_port)"
export KAFKA_SECURE_SASL_PORT="$(free_port)"
[ "$KAFKA_SECURE_TLS_PORT" != "$KAFKA_SECURE_SASL_PORT" ] \
  || export KAFKA_SECURE_SASL_PORT="$(free_port)"
export KAFKA_SECURE_STORE_PASSWORD="$(openssl rand -hex 16)"
export KAFKA_SECURE_SASL_PASSWORD="$(openssl rand -hex 24)"
export KAFKA_SECURE_CERT_DIR="$CERT_DIR"

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    docker compose -p "$KAFKA_SECURE_PROJECT" -f "$COMPOSE_FILE" logs \
      --no-color kafka >&2 || true
  fi
  docker compose -p "$KAFKA_SECURE_PROJECT" -f "$COMPOSE_FILE" down \
    --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$WORK"
  return "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 2 \
  -subj '/CN=Yano Kafka Integration CA' \
  -keyout "$CERT_DIR/ca.key" -out "$CERT_DIR/ca.crt" >/dev/null 2>&1
openssl req -newkey rsa:3072 -sha256 -nodes \
  -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
  -keyout "$CERT_DIR/broker.key" -out "$CERT_DIR/broker.csr" >/dev/null 2>&1
printf '%s\n' 'subjectAltName=DNS:localhost,IP:127.0.0.1' > "$CERT_DIR/broker.ext"
openssl x509 -req -sha256 -days 2 -in "$CERT_DIR/broker.csr" \
  -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
  -extfile "$CERT_DIR/broker.ext" -out "$CERT_DIR/broker.crt" >/dev/null 2>&1
openssl pkcs12 -export -name kafka-broker \
  -inkey "$CERT_DIR/broker.key" -in "$CERT_DIR/broker.crt" \
  -certfile "$CERT_DIR/ca.crt" -out "$CERT_DIR/broker.p12" \
  -passout "pass:$KAFKA_SECURE_STORE_PASSWORD" >/dev/null 2>&1
keytool -importcert -noprompt -alias yano-kafka-integration-ca \
  -file "$CERT_DIR/ca.crt" -keystore "$CERT_DIR/truststore.p12" \
  -storetype PKCS12 -storepass "$KAFKA_SECURE_STORE_PASSWORD" >/dev/null 2>&1
printf '%s\n' "$KAFKA_SECURE_STORE_PASSWORD" > "$WORK/store-password"
printf '%s\n' "$KAFKA_SECURE_SASL_PASSWORD" > "$WORK/sasl-password"
printf 'KafkaServer { org.apache.kafka.common.security.plain.PlainLoginModule required user_yano="%s"; };\n' \
  "$KAFKA_SECURE_SASL_PASSWORD" > "$CERT_DIR/kafka_server_jaas.conf"
printf '%s\n' "$KAFKA_SECURE_STORE_PASSWORD" > "$CERT_DIR/store-password"
chmod 600 "$CERT_DIR"/* "$WORK/store-password" "$WORK/sasl-password"
chmod 644 "$CERT_DIR/broker.p12" "$CERT_DIR/truststore.p12" \
  "$CERT_DIR/store-password" "$CERT_DIR/kafka_server_jaas.conf"

docker compose -p "$KAFKA_SECURE_PROJECT" -f "$COMPOSE_FILE" up \
  --detach --wait --wait-timeout 120

run_profile() {
  local profile="$1" port="$2"
  shift 2
  "$ROOT_DIR/gradlew" :appchain-kafka:test \
    --tests '*KafkaPublishRealIntegrationTest.publishesEffectsAndFinalizedBlocksThroughOneRealBroker' \
    -Dyano.kafka.integration.bootstrap="localhost:$port" \
    -Dyano.kafka.integration.security-profile="$profile" \
    -Dyano.kafka.integration.truststore-path="$CERT_DIR/truststore.p12" \
    -Dyano.kafka.integration.truststore-password-file="$WORK/store-password" \
    "$@"
}

run_profile tls "$KAFKA_SECURE_TLS_PORT"
run_profile sasl-tls "$KAFKA_SECURE_SASL_PORT" \
  -Dyano.kafka.integration.sasl-mechanism=PLAIN \
  -Dyano.kafka.integration.sasl-username=yano \
  -Dyano.kafka.integration.sasl-password-file="$WORK/sasl-password"

printf 'PASS: Kafka effect and finalized sink use real TLS and SASL/TLS broker connections\n'
