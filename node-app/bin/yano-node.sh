#!/usr/bin/env bash
#
# Yano Node start script
# Auto-detects JAR vs native mode and supports Quarkus profiles.
#
# Usage:
#   ./yano-node.sh                      # Default (preprod relay)
#   ./yano-node.sh --devnet             # Local devnet with block production
#   ./yano-node.sh --mainnet            # Mainnet relay
#   ./yano-node.sh --preview            # Preview relay
#   ./yano-node.sh --profile=<name>     # Custom Quarkus profile
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
if [ -f "$SCRIPT_DIR/yano-node" ]; then
    # Native binary mode
    echo "Starting Yano Node (native)${PROFILE:+ with profile: $PROFILE}..."
    exec "$SCRIPT_DIR/yano-node" \
        -Dyaci.node.block-producer.script-evaluator=scalus \
        $PROFILE_PROP "${PASSTHROUGH_ARGS[@]}"
elif [ -f "$SCRIPT_DIR/yano-node.jar" ]; then
    # Uber-jar mode
    echo "Starting Yano Node (JVM)${PROFILE:+ with profile: $PROFILE}..."
    exec java ${JAVA_OPTS:-} $PROFILE_PROP -jar "$SCRIPT_DIR/yano-node.jar" "${PASSTHROUGH_ARGS[@]}"
else
    echo "Error: Neither 'yano-node' binary nor 'yano-node.jar' found in $SCRIPT_DIR"
    echo "Please ensure the distribution is complete."
    exit 1
fi
