#!/usr/bin/env bash
#
# Yano start script
# Auto-detects JAR vs native mode and supports Quarkus profiles.
#
# Usage:
#   ./yano.sh start                # Default preprod profile
#   ./yano.sh start:preprod,relay  # Preprod with relay upstream profile
#   ./yano.sh start:mainnet        # Mainnet relay alias
#   ./yano.sh start:<profiles>     # Custom comma-separated Quarkus profiles
#   ./yano.sh appchain config ...  # App-chain configuration tooling
#   ./yano.sh appchain cluster ... # Local cluster launcher
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

usage() {
    cat <<EOF
Usage: ./yano.sh [start|start:<profiles>|appchain|help] [args...]

Examples:
  ./yano.sh start
  ./yano.sh start:preprod,relay
  ./yano.sh start:preprod,relay,praos-lite
  ./yano.sh start:mainnet
  ./yano.sh start:preview
  ./yano.sh start:sanchonet
  ./yano.sh start:devnet
  ./yano.sh start:mydevnet
  ./yano.sh appchain config validate --mode template \\
      --template-contract builtin:cluster config/application-appchain.yml
  ./yano.sh appchain config explain block.max-bytes
  ./yano.sh appchain init --recipe owned-registry --network devnet --members 3
  ./yano.sh appchain render ./owned-registry
  ./yano.sh appchain cluster start 3

Environment:
  JAVA_OPTS        JVM options for jar distribution only
  YANO_EXTRA_ARGS  Extra runtime args for jar and native distributions
  YANO_APPCHAIN_CLI  Optional path to a version-matched yano-appchain launcher
EOF
}

appchain_cli() {
    local configured="${YANO_APPCHAIN_CLI:-}"
    local candidate
    local found=""

    if [ -n "$configured" ]; then
        if [ ! -x "$configured" ]; then
            echo "Error: YANO_APPCHAIN_CLI is not executable: $configured" >&2
            exit 1
        fi
        printf '%s\n' "$configured"
        return
    fi

    candidate="$SCRIPT_DIR/tools/yano-appchain/bin/yano-appchain"
    if [ -x "$candidate" ]; then
        printf '%s\n' "$candidate"
        return
    fi
    candidate="$SCRIPT_DIR/yano-devtools/bin/yano-appchain"
    if [ -x "$candidate" ]; then
        printf '%s\n' "$candidate"
        return
    fi

    for candidate in "$SCRIPT_DIR"/yano-devtools-*/bin/yano-appchain; do
        if [ -x "$candidate" ]; then
            if [ -n "$found" ] && [ "$found" != "$candidate" ]; then
                echo "Error: Multiple yano-devtools installations found." >&2
                echo "Set YANO_APPCHAIN_CLI to the version-matched launcher." >&2
                exit 1
            fi
            found="$candidate"
        fi
    done
    if [ -z "$found" ]; then
        echo "Error: App-chain developer tools were not found." >&2
        echo "The JVM distribution includes them under tools/yano-appchain." >&2
        echo "For a native distribution, extract the version-matched yano-devtools archive" >&2
        echo "beside yano.sh or set YANO_APPCHAIN_CLI." >&2
        exit 1
    fi
    printf '%s\n' "$found"
}

dispatch_appchain() {
    shift
    if [ "$#" -eq 0 ]; then
        echo "Usage: ./yano.sh appchain {init|render|recipes|config|cluster} ..." >&2
        exit 64
    fi
    if [ "$1" = "cluster" ]; then
        shift
        if [ ! -x "$SCRIPT_DIR/appchain-cluster/cluster.sh" ]; then
            echo "Error: appchain-cluster/cluster.sh is missing or not executable." >&2
            exit 1
        fi
        exec "$SCRIPT_DIR/appchain-cluster/cluster.sh" "$@"
    fi
    local cli
    cli="$(appchain_cli)"
    exec "$cli" "$@"
}

validate_profile_name() {
    local profile="$1"
    case "$profile" in
        ''|*/*|*..*|*[!abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-]*)
            echo "Invalid profile name: $profile" >&2
            echo "Use letters, numbers, dot, underscore, and dash only." >&2
            exit 1
            ;;
    esac
}

validate_profile_list() {
    local profile_list="$1"
    local old_ifs="$IFS"
    local profile
    case "$profile_list" in
        ''|*,|,*|*,,*)
            echo "Invalid profile list: $profile_list" >&2
            echo "Use comma-separated profile names without empty segments." >&2
            exit 1
            ;;
    esac
    IFS=','
    read -ra profiles <<< "$profile_list"
    IFS="$old_ifs"
    for profile in "${profiles[@]}"; do
        validate_profile_name "$profile"
    done
}

if [ "$#" -eq 0 ]; then
    usage
    exit 0
fi

if [ "$1" = "appchain" ]; then
    dispatch_appchain "$@"
fi

# Parse profile from arguments
PROFILE=""
PASSTHROUGH_ARGS=()

for arg in "$@"; do
    case "$arg" in
        help|-h|--help)
            usage
            exit 0
            ;;
        start)
            PROFILE="preprod"
            ;;
        start:preprod|--preprod)
            PROFILE="preprod"
            ;;
        start:devnet)
            PROFILE="devnet"
            ;;
        start:mainnet)
            PROFILE="mainnet"
            ;;
        start:preview)
            PROFILE="preview"
            ;;
        start:sanchonet)
            PROFILE="sanchonet"
            ;;
        start:*)
            PROFILE="${arg#start:}"
            ;;
        --devnet)
            PROFILE="devnet"
            ;;
        --mainnet)
            PROFILE="mainnet"
            ;;
        --preview)
            PROFILE="preview"
            ;;
        --sanchonet)
            PROFILE="sanchonet"
            ;;
        --profile=*)
            PROFILE="${arg#--profile=}"
            ;;
        *)
            PASSTHROUGH_ARGS+=("$arg")
            ;;
    esac
done

# Build profile system property if set
PROFILE_PROP=""
if [ -n "$PROFILE" ]; then
    validate_profile_list "$PROFILE"
    PROFILE_PROP="-Dquarkus.profile=${PROFILE}"
fi

# Auto-detect mode: native binary or JAR
if [ -f "$SCRIPT_DIR/yano" ]; then
    # Native binary mode
    echo "Starting Yano (native)${PROFILE:+ with profile: $PROFILE}..."
    echo "YANO_EXTRA_ARGS=${YANO_EXTRA_ARGS:-}"
    # shellcheck disable=SC2086
    exec "$SCRIPT_DIR/yano" \
        -Dyano.block-producer.script-evaluator=scalus \
        $PROFILE_PROP ${YANO_EXTRA_ARGS:-} "${PASSTHROUGH_ARGS[@]}"
elif [ -f "$SCRIPT_DIR/yano.jar" ]; then
    # Uber-jar mode
    echo "Starting Yano (JVM)${PROFILE:+ with profile: $PROFILE}..."
    echo "JAVA_OPTS=${JAVA_OPTS:-}"
    echo "YANO_EXTRA_ARGS=${YANO_EXTRA_ARGS:-}"
    # shellcheck disable=SC2086
    exec java ${JAVA_OPTS:-} $PROFILE_PROP -jar "$SCRIPT_DIR/yano.jar" ${YANO_EXTRA_ARGS:-} "${PASSTHROUGH_ARGS[@]}"
else
    echo "Error: Neither 'yano' binary nor 'yano.jar' found in $SCRIPT_DIR"
    echo "Please ensure the distribution is complete."
    exit 1
fi
