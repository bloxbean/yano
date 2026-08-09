#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd -P)"
TARGET="/Users/satya/Downloads/yano-cluster/devnet-cluster"
INSTANCE="devnet"
SKIP_BUILD=false
SMOKE=false
FRESH=false

usage() {
  cat <<'EOF'
Usage: deploy.sh [--repo PATH] [--target PATH] [--instance NAME]
                 [--skip-build] [--smoke]

Creates a fresh deployment only when TARGET does not exist. Existing retained
identity and data are resumed without replacement.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --repo) REPO_ROOT="${2:?missing --repo value}"; shift 2;;
    --target) TARGET="${2:?missing --target value}"; shift 2;;
    --instance) INSTANCE="${2:?missing --instance value}"; shift 2;;
    --skip-build) SKIP_BUILD=true; shift;;
    --smoke) SMOKE=true; shift;;
    -h|--help) usage; exit 0;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 64;;
  esac
done

for tool in curl jq python3 unzip; do
  command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 69; }
done
[ -x "$REPO_ROOT/gradlew" ] || { echo "Not a Yano repository: $REPO_ROOT" >&2; exit 66; }

ZIP_NAME="yano-showcase-0.1.0-pre11.zip"
ZIP_PATH="$REPO_ROOT/appchain/examples/appchain-showcase/build/distributions/$ZIP_NAME"

if [ "$SKIP_BUILD" = false ]; then
  (
    cd "$REPO_ROOT"
    ./gradlew :appchain-client:test --tests '*AppChainClientStateProofTest' \
      :appchain-showcase:showcaseScriptContract \
      :appchain-showcase:test \
      :appchain-showcase:distZip \
      :appchain-showcase:showcaseDistributionContract
  )
fi
[ -f "$ZIP_PATH" ] || { echo "Missing showcase ZIP: $ZIP_PATH" >&2; exit 66; }

mkdir -p "$(dirname "$TARGET")"
cp "$ZIP_PATH" "$(dirname "$TARGET")/$ZIP_NAME"

if [ ! -e "$TARGET" ]; then
  STAGE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/yano-devnet-stage.XXXXXX")"
  unzip -q "$ZIP_PATH" -d "$STAGE_DIR"
  RELEASE_ROOT="$(find "$STAGE_DIR" -mindepth 1 -maxdepth 1 -type d -name 'yano-showcase-*' -print -quit)"
  [ -n "$RELEASE_ROOT" ] || { echo "ZIP has no showcase root" >&2; exit 65; }
  mv "$RELEASE_ROOT" "$TARGET"
  find "$STAGE_DIR" -depth -delete
  FRESH=true
fi

[ -x "$TARGET/showcase.sh" ] || { echo "Invalid target: $TARGET" >&2; exit 65; }
cd "$TARGET"

if ! curl -fsS "http://127.0.0.1:7070/q/health/ready" \
    | jq -e '.status == "UP"' >/dev/null 2>&1; then
  if [ -f "data/showcase/$INSTANCE/showcase-identity.json" ]; then
    ./showcase.sh up --instance "$INSTANCE"
  else
    ./showcase.sh quickstart --profile light --nodes 3 --instance "$INSTANCE" \
      --anchor-chain all
  fi
fi

if [ "$FRESH" = true ]; then
  ./showcase.sh anchor bootstrap all --instance "$INSTANCE"
fi

if [ "$SMOKE" = true ]; then
  ./showcase.sh run orders --instance "$INSTANCE"
  ./showcase.sh run registry --instance "$INSTANCE"
  ./showcase.sh run authenticated-map --instance "$INSTANCE"
fi

"$SCRIPT_DIR/validate_cluster.sh" "$TARGET" "$INSTANCE" 7070
"$SCRIPT_DIR/write_runbook.sh" "$TARGET" "$INSTANCE"
shasum -a 256 "$ZIP_PATH" "$(dirname "$TARGET")/$ZIP_NAME"
