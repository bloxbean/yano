#!/usr/bin/env bash
#
# Yano start script
# Auto-detects JAR vs native mode and supports Quarkus profiles.
#
# Usage:
#   ./yano.sh start                # Default (preprod relay)
#   ./yano.sh start:preprod        # Preprod relay
#   ./yano.sh start:mainnet        # Mainnet relay alias
#   ./yano.sh start:<profile>      # Custom Quarkus profile alias
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

usage() {
    cat <<EOF
Usage: ./yano.sh [start|start:<profile>|help] [args...]

Examples:
  ./yano.sh start
  ./yano.sh start:preprod
  ./yano.sh start:mainnet
  ./yano.sh start:preview
  ./yano.sh start:sanchonet
  ./yano.sh start:devnet
  ./yano.sh start:mydevnet

Environment:
  JAVA_OPTS        JVM options for jar distribution only
  YANO_EXTRA_ARGS  Extra runtime args for jar and native distributions
EOF
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

if [ "$#" -eq 0 ]; then
    usage
    exit 0
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
    validate_profile_name "$PROFILE"
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
