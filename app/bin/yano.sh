#!/usr/bin/env bash
#
# Yano start script
# Auto-detects JAR vs native mode and supports Quarkus profiles.
#
# Usage:
#   ./yano.sh                      # Default (preprod relay)
#   ./yano.sh --devnet             # Local devnet with block production
#   ./yano.sh --mainnet            # Mainnet relay
#   ./yano.sh --preview            # Preview relay
#   ./yano.sh --profile=<name>     # Custom Quarkus profile
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Parse profile from arguments
PROFILE=""
PASSTHROUGH_ARGS=()

for arg in "$@"; do
    case "$arg" in
        --devnet)
            PROFILE="devnet"
            ;;
        --mainnet)
            PROFILE="mainnet"
            ;;
        --preview)
            PROFILE="preview"
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
    PROFILE_PROP="-Dquarkus.profile=${PROFILE}"
fi

# Auto-detect mode: native binary or JAR
if [ -f "$SCRIPT_DIR/yano" ]; then
    # Native binary mode
    echo "Starting Yano (native)${PROFILE:+ with profile: $PROFILE}..."
    exec "$SCRIPT_DIR/yano" \
        -Dyano.block-producer.script-evaluator=scalus \
        $PROFILE_PROP "${PASSTHROUGH_ARGS[@]}"
elif [ -f "$SCRIPT_DIR/yano.jar" ]; then
    # Uber-jar mode
    echo "Starting Yano (JVM)${PROFILE:+ with profile: $PROFILE}..."
    exec java ${JAVA_OPTS:-} $PROFILE_PROP -jar "$SCRIPT_DIR/yano.jar" "${PASSTHROUGH_ARGS[@]}"
else
    echo "Error: Neither 'yano' binary nor 'yano.jar' found in $SCRIPT_DIR"
    echo "Please ensure the distribution is complete."
    exit 1
fi
