#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd -P)"
TARGET="/Users/satya/Downloads/yano-cluster/preprod-cluster"
INSTANCE="preprod"
SOURCE_STATE="/Users/satya/Downloads/yano-cluster/chainstate-preprod"
SOURCE_KEYS="/Users/satya/Downloads/yano-cluster/keys"
SKIP_BUILD=false
CONFIRM_PUBLIC=false
SMOKE=false

usage() {
  cat <<'EOF'
Usage: deploy.sh [--repo PATH] [--target PATH] [--instance NAME]
                 [--chainstate PATH] [--keys PATH] [--skip-build]
                 [--confirm-public-preprod] [--smoke]

A fresh deployment requires --confirm-public-preprod. An existing deployment
is resumed and validated without replacing retained data or identity.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --repo) REPO_ROOT="${2:?missing --repo value}"; shift 2;;
    --target) TARGET="${2:?missing --target value}"; shift 2;;
    --instance) INSTANCE="${2:?missing --instance value}"; shift 2;;
    --chainstate) SOURCE_STATE="${2:?missing --chainstate value}"; shift 2;;
    --keys) SOURCE_KEYS="${2:?missing --keys value}"; shift 2;;
    --skip-build) SKIP_BUILD=true; shift;;
    --confirm-public-preprod) CONFIRM_PUBLIC=true; shift;;
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
MARKER="$TARGET/data/showcase/$INSTANCE/showcase-identity.json"

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

if [ -e "$TARGET" ]; then
  [ -f "$MARKER" ] || {
    echo "Target exists without the expected retained identity: $TARGET" >&2
    exit 65
  }
  cd "$TARGET"
  if ! curl -fsS "http://127.0.0.1:17070/q/health/ready" \
      | jq -e '.status == "UP"' >/dev/null 2>&1; then
    ./showcase.sh up --instance "$INSTANCE"
  fi
  if [ "$SMOKE" = true ]; then
    [ "$CONFIRM_PUBLIC" = true ] || {
      echo "--smoke on preprod requires --confirm-public-preprod" >&2
      exit 64
    }
    ./showcase.sh run orders --instance "$INSTANCE"
    ./showcase.sh run registry --instance "$INSTANCE"
    ./showcase.sh run authenticated-map --instance "$INSTANCE"
    ./showcase.sh run document-review --instance "$INSTANCE"
  fi
  "$SCRIPT_DIR/validate_cluster.sh" "$TARGET" "$INSTANCE" 17070
  "$SCRIPT_DIR/write_runbook.sh" "$TARGET" "$INSTANCE"
  exit 0
fi

[ "$CONFIRM_PUBLIC" = true ] || {
  echo "Fresh preprod deployment submits L1 transactions; pass --confirm-public-preprod" >&2
  exit 64
}
[ -d "$SOURCE_STATE" ] || { echo "Missing source chainstate: $SOURCE_STATE" >&2; exit 66; }
[ -f "$SOURCE_KEYS/anchor.seed" ] || { echo "Missing anchor.seed" >&2; exit 66; }
[ -f "$SOURCE_KEYS/operator.seed" ] || { echo "Missing operator.seed" >&2; exit 66; }

mkdir -p "$(dirname "$TARGET")"
STAGE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/yano-preprod-stage.XXXXXX")"
unzip -q "$ZIP_PATH" -d "$STAGE_DIR"
RELEASE_ROOT="$(find "$STAGE_DIR" -mindepth 1 -maxdepth 1 -type d -name 'yano-showcase-*' -print -quit)"
[ -n "$RELEASE_ROOT" ] || { echo "ZIP has no showcase root" >&2; exit 65; }
mv "$RELEASE_ROOT" "$TARGET"
find "$STAGE_DIR" -depth -delete

cd "$TARGET"
mkdir -m 700 private-anchor private-settlement
cp "$SOURCE_KEYS/anchor.seed" private-anchor/anchor.seed
cp "$SOURCE_KEYS/operator.seed" private-settlement/operator.seed
chmod 600 private-anchor/anchor.seed private-settlement/operator.seed
SETTLEMENT_RECORD="settlement-deployment-payment-chain-settlement.properties"
SETTLEMENT_RETAINED_LOG="settlement-bootstrap-payment-chain-settlement.log"
SETTLEMENT_VAULT_SCRIPT="settlement-vault.script"
SETTLEMENT_SHARD_SCRIPT="settlement-shard.script"
if [ -f "$SOURCE_KEYS/$SETTLEMENT_RECORD" ]; then
  cp "$SOURCE_KEYS/$SETTLEMENT_RECORD" "private-settlement/$SETTLEMENT_RECORD"
  chmod 600 "private-settlement/$SETTLEMENT_RECORD"
fi

# Deploy the operator-specific L1 settlement identity from a disposable
# stock-catalog bootstrap instance. The final app-chain instance is created
# only after the production settlement block is in the catalog position; no
# app-chain marker, root, or ledger is migrated into the final deployment.
BOOTSTRAP_INSTANCE="${INSTANCE}-settlement-bootstrap"
./showcase.sh prepare --profile light --network preprod --nodes 3 \
  --instance "$BOOTSTRAP_INSTANCE" --http-base 17070 --server-base 17337 \
  --anchor-chain all --anchor-key-file "$PWD/private-anchor/anchor.seed" \
  --confirm-public-anchor preprod

CLUSTER_ROOT="$TARGET/data/showcase/$BOOTSTRAP_INSTANCE/cluster"
for NODE in 0 1 2; do
  mkdir -p "$CLUSTER_ROOT/node$NODE"
  cp -cR "$SOURCE_STATE" "$CLUSTER_ROOT/node$NODE/chainstate-import"
  # The retained source may predate the three-root layout. Only L1 state is
  # imported; legacy derived app-chain indexes must never enter chainstate.
  LEGACY_APPCHAINS="$CLUSTER_ROOT/node$NODE/chainstate-import/appchains"
  if [ -d "$LEGACY_APPCHAINS" ]; then
    find "$LEGACY_APPCHAINS" -depth -delete
  fi
done

# Create and retain deployment/L1 identity markers before adopting copied DBs.
./showcase.sh up --instance "$BOOTSTRAP_INSTANCE"
./showcase.sh stop --instance "$BOOTSTRAP_INSTANCE"
for NODE in 0 1 2; do
  mv "$CLUSTER_ROOT/node$NODE/chainstate" \
    "$CLUSTER_ROOT/node$NODE/chainstate-bootstrap-empty"
  mv "$CLUSTER_ROOT/node$NODE/chainstate-import" \
    "$CLUSTER_ROOT/node$NODE/chainstate"
done
./showcase.sh up --instance "$BOOTSTRAP_INSTANCE"

SETTLEMENT_LOG="$TARGET/settlement-bootstrap.log"
if [ -f "$SOURCE_KEYS/$SETTLEMENT_RETAINED_LOG" ] \
    && [ -f "$SOURCE_KEYS/$SETTLEMENT_VAULT_SCRIPT" ] \
    && [ -f "$SOURCE_KEYS/$SETTLEMENT_SHARD_SCRIPT" ]; then
  # A retained L1 snapshot can predate the already-submitted settlement mint.
  # Reuse the exact public deployment block and parameterized validators rather
  # than selecting a seed UTxO that the live network has already spent.
  cp "$SOURCE_KEYS/$SETTLEMENT_RETAINED_LOG" "$SETTLEMENT_LOG"
  cp "$SOURCE_KEYS/$SETTLEMENT_VAULT_SCRIPT" \
    "yano/config/settlement/$SETTLEMENT_VAULT_SCRIPT"
  cp "$SOURCE_KEYS/$SETTLEMENT_SHARD_SCRIPT" \
    "yano/config/settlement/$SETTLEMENT_SHARD_SCRIPT"
  echo "reusing retained preprod settlement deployment artifacts"
else
  set +e
  ./showcase.sh settlement bootstrap --instance "$BOOTSTRAP_INSTANCE" \
    --settlement-key-file "$PWD/private-settlement/operator.seed" \
    --confirm-public-settlement preprod 2>&1 | tee "$SETTLEMENT_LOG"
  SETTLEMENT_STATUS="${PIPESTATUS[0]}"
  set -e
  if [ -f "private-settlement/$SETTLEMENT_RECORD" ]; then
    cp "private-settlement/$SETTLEMENT_RECORD" "$SOURCE_KEYS/$SETTLEMENT_RECORD"
    chmod 600 "$SOURCE_KEYS/$SETTLEMENT_RECORD"
  fi
  [ "$SETTLEMENT_STATUS" -eq 0 ] || exit "$SETTLEMENT_STATUS"
  cp "$SETTLEMENT_LOG" "$SOURCE_KEYS/$SETTLEMENT_RETAINED_LOG"
  cp "yano/config/settlement/$SETTLEMENT_VAULT_SCRIPT" \
    "$SOURCE_KEYS/$SETTLEMENT_VAULT_SCRIPT"
  cp "yano/config/settlement/$SETTLEMENT_SHARD_SCRIPT" \
    "$SOURCE_KEYS/$SETTLEMENT_SHARD_SCRIPT"
  chmod 600 "$SOURCE_KEYS/$SETTLEMENT_RETAINED_LOG" \
    "$SOURCE_KEYS/$SETTLEMENT_VAULT_SCRIPT" \
    "$SOURCE_KEYS/$SETTLEMENT_SHARD_SCRIPT"
fi
python3 "$SCRIPT_DIR/adopt_settlement_config.py" \
  yano/config/application-appchain.yml "$SETTLEMENT_LOG" \
  catalog/showcase-catalog-v1.json
./showcase.sh stop --instance "$BOOTSTRAP_INSTANCE"
./showcase.sh reset --instance "$BOOTSTRAP_INSTANCE" --yes

# Create the final deployment from a clean app-chain generation. The ADR-035
# Cardano History product is already part of the stock catalog.
./showcase.sh prepare --profile light --network preprod --nodes 3 \
  --instance "$INSTANCE" --http-base 17070 --server-base 17337 \
  --cardano-history-profile params-stake \
  --l1-source-snapshot-retention-epochs 2 \
  --anchor-chain all --anchor-key-file "$PWD/private-anchor/anchor.seed" \
  --confirm-public-anchor preprod

CLUSTER_ROOT="$TARGET/data/showcase/$INSTANCE/cluster"
for NODE in 0 1 2; do
  mkdir -p "$CLUSTER_ROOT/node$NODE"
  cp -cR "$SOURCE_STATE" "$CLUSTER_ROOT/node$NODE/chainstate-import"
  LEGACY_APPCHAINS="$CLUSTER_ROOT/node$NODE/chainstate-import/appchains"
  if [ -d "$LEGACY_APPCHAINS" ]; then
    find "$LEGACY_APPCHAINS" -depth -delete
  fi
done

./showcase.sh up --instance "$INSTANCE"
./showcase.sh stop --instance "$INSTANCE"
for NODE in 0 1 2; do
  mv "$CLUSTER_ROOT/node$NODE/chainstate" \
    "$CLUSTER_ROOT/node$NODE/chainstate-bootstrap-empty"
  mv "$CLUSTER_ROOT/node$NODE/chainstate-import" \
    "$CLUSTER_ROOT/node$NODE/chainstate"
done
./showcase.sh up --instance "$INSTANCE"
./showcase.sh anchor bootstrap all --instance "$INSTANCE"

if [ "$SMOKE" = true ]; then
  ./showcase.sh run orders --instance "$INSTANCE"
  ./showcase.sh run registry --instance "$INSTANCE"
  ./showcase.sh run authenticated-map --instance "$INSTANCE"
  ./showcase.sh run document-review --instance "$INSTANCE"
fi

"$SCRIPT_DIR/validate_cluster.sh" "$TARGET" "$INSTANCE" 17070
"$SCRIPT_DIR/write_runbook.sh" "$TARGET" "$INSTANCE"
for NODE in 0 1 2; do
  EMPTY="$CLUSTER_ROOT/node$NODE/chainstate-bootstrap-empty"
  [ ! -d "$EMPTY" ] || find "$EMPTY" -depth -delete
done
shasum -a 256 "$ZIP_PATH" "$(dirname "$TARGET")/$ZIP_NAME"
