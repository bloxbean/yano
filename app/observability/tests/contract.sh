#!/usr/bin/env bash
# observability-launcher-contract
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/observability/observability.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
STATE="$TMP/state"
FAKE="$TMP/docker"
LOG="$TMP/docker.log"
VOLUME_FLAG="$TMP/volume"

cat > "$FAKE" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
if [ "${1:-}" = compose ] && [ "${2:-}" = version ]; then exit 0; fi
if [ "${1:-}" = compose ]; then
  case " $* " in
    *" up "*) touch "$FAKE_DOCKER_VOLUME";;
    *" ps "*) printf 'prometheus running\n';;
  esac
  exit 0
fi
if [ "${1:-}" = volume ] && [ "${2:-}" = inspect ]; then
  [ -f "$FAKE_DOCKER_VOLUME" ] || exit 1
  if [ "${3:-}" = --format ]; then
    case "${4:-}" in
      *owner*) printf 'observability\n';;
      *instance*) sed -n 's/^YANO_OBSERVABILITY_INSTANCE=//p' "$FAKE_STATE_DIR/compose.env";;
    esac
  fi
  exit 0
fi
if [ "${1:-}" = volume ] && [ "${2:-}" = rm ]; then rm -f "$FAKE_DOCKER_VOLUME"; exit 0; fi
exit 0
SH
chmod +x "$FAKE"

run() {
  YANO_OBSERVABILITY_DIR="$STATE" YANO_OBSERVABILITY_DOCKER="$FAKE" \
  FAKE_DOCKER_LOG="$LOG" FAKE_DOCKER_VOLUME="$VOLUME_FLAG" FAKE_STATE_DIR="$STATE" \
    "$SCRIPT" "$@"
}

run start --target http://127.0.0.1:7070 --target https://node.example:7443 \
  --retention 24h --retention-size 512MB > "$TMP/start.out"
grep -q 'host.docker.internal:7070' "$STATE/prometheus.yml"
grep -q 'node.example:7443' "$STATE/prometheus.yml"
grep -q 'prom/prometheus:v3.13.1@sha256:' "$STATE/compose.env"
grep -q '^YANO_PROMETHEUS_RETENTION=24h$' "$STATE/compose.env"
grep -q '^YANO_PROMETHEUS_RETENTION_SIZE=512MB$' "$STATE/compose.env"
grep -q 'ui/observability/?metrics=' "$TMP/start.out"
grep -q 'Console:    http://127.0.0.1:7070/ui/observability/' "$TMP/start.out"
run status | grep -q 'prometheus running'
run stop | grep -q 'history was preserved'
[ -f "$VOLUME_FLAG" ]

if run start --target 'http://user:secret@localhost:7070' >/dev/null 2>&1; then
  echo 'credential-bearing target was accepted' >&2; exit 1
fi
if run start --retention 91d >/dev/null 2>&1; then
  echo 'out-of-bounds retention was accepted' >&2; exit 1
fi

touch "$STATE/foreign"
if run clean --yes >/dev/null 2>&1; then echo 'unsafe cleanup succeeded' >&2; exit 1; fi
[ -f "$VOLUME_FLAG" ]
rm "$STATE/foreign"
run clean --yes | grep -q 'history and launcher state removed'
[ ! -e "$STATE" ] && [ ! -f "$VOLUME_FLAG" ]

printf 'observability launcher contract passed\n'
