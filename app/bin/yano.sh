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

CALLER_DIR="$PWD"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
YANO_ROOT="$SCRIPT_DIR"
REPOSITORY_ROOT=""

# In a release, this script lives beside yano.jar, config/, and
# appchain-cluster/. In the source tree, app/yano.sh delegates here while this
# file remains under app/bin/. Resolve both layouts once so every command uses
# the same dispatch and argument handling.
if [ "$(basename "$SCRIPT_DIR")" = "bin" ] \
    && [ -d "$SCRIPT_DIR/../config" ] \
    && [ -x "$SCRIPT_DIR/../appchain-cluster/cluster.sh" ]; then
    YANO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
    REPOSITORY_ROOT="$(cd "$YANO_ROOT/.." && pwd)"
fi
cd "$YANO_ROOT"

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
  ./yano.sh appchain config validate --mode project ./owned-registry
  ./yano.sh appchain doctor ./owned-registry --distribution ./yano-release.zip
  ./yano.sh appchain cluster start 3
  ./yano.sh appchain cluster effect demo "order 42 approved"
  ./yano.sh appchain cluster node join 3

Environment:
  JAVA_OPTS        JVM options for jar distribution only
  YANO_EXTRA_ARGS  Extra runtime args for jar and native distributions

Advanced:
  YANO_APPCHAIN_CLI  Internal version-matched app-chain tooling override
EOF
}

appchain_usage() {
    cat <<'EOF'
Usage: ./yano.sh appchain <command> [args...]

Discover capabilities:
  recipes                         List out-of-box app-chain recipes
  capabilities                    List selectable components and integrations
  config explain <property>       Explain a supported configuration property

Create and update a project:
  init [options]                  Generate appchain.yaml and derived YAML config
  render [project-directory]      Regenerate safely from appchain.yaml
  migrate [project-directory]     Inspect or apply a tooling migration

Validate and operate:
  config validate|effective ...   Validate or inspect effective configuration
  doctor [project] [options]      Check project/distribution readiness
  diff <old.lock> <new.lock>      Classify a proposed configuration change
  drift [project] --peer <url>    Compare redacted live node identities
  gitops [project] [options]      Export Helm or Kustomize deployment files
  metadata verify ...             Verify custom-plugin metadata signatures
  role ...                        Encode/sign role commands offline (seed files only)

Run a local cluster:
  cluster start [members]         Start or resume a same-machine cluster
  cluster status|stop|clean       Inspect, stop, or clean the local cluster
  cluster node join <index>       Start a previously staged additional node
  cluster effect demo [message]   Submit and approve a demonstration effect

Examples:
  ./yano.sh appchain init --recipe owned-registry --network devnet --members 3
  ./yano.sh appchain config validate --mode project ./owned-registry
  ./yano.sh appchain cluster start 3

Use './yano.sh appchain <command> --help' for command-specific options.
EOF
}

appchain_cli() {
    local configured="${YANO_APPCHAIN_CLI:-}"
    local candidate
    local found=""

    if [ -n "$configured" ]; then
        case "$configured" in
            /*) ;;
            *) configured="$CALLER_DIR/$configured" ;;
        esac
        if [ ! -x "$configured" ]; then
            echo "Error: YANO_APPCHAIN_CLI is not executable: $configured" >&2
            exit 1
        fi
        printf '%s\n' "$configured"
        return
    fi

    candidate="$YANO_ROOT/tools/yano-appchain/bin/yano-appchain"
    if [ -x "$candidate" ]; then
        printf '%s\n' "$candidate"
        return
    fi
    candidate="$YANO_ROOT/yano-devtools/bin/yano-appchain"
    if [ -x "$candidate" ]; then
        printf '%s\n' "$candidate"
        return
    fi

    if [ -n "$REPOSITORY_ROOT" ]; then
        candidate="$REPOSITORY_ROOT/appchain/appchain-devtools/build/install/yano-devtools/bin/yano-appchain"
        if [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return
        fi
    fi

    for candidate in "$YANO_ROOT"/yano-devtools-*/bin/yano-appchain; do
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
        echo "Error: This app-chain command requires version-matched app-chain tooling." >&2
        if [ -n "$REPOSITORY_ROOT" ]; then
            echo "Build them with: ./gradlew :appchain-devtools:installDist" >&2
        else
            echo "The JVM distribution includes them under tools/yano-appchain." >&2
        fi
        echo "For a native distribution, extract the version-matched tooling archive" >&2
        echo "beside yano.sh. Advanced users may set YANO_APPCHAIN_CLI." >&2
        exit 1
    fi
    printf '%s\n' "$found"
}

dispatch_appchain() {
    shift
    if [ "$#" -eq 0 ]; then
        appchain_usage
        exit 0
    fi
    case "$1" in
        help|-h|--help)
            appchain_usage
            exit 0
            ;;
    esac
    if [ "$1" = "cluster" ]; then
        shift
        if [ ! -x "$YANO_ROOT/appchain-cluster/cluster.sh" ]; then
            echo "Error: appchain-cluster/cluster.sh is missing or not executable." >&2
            exit 1
        fi
        if [ "$#" -eq 0 ]; then
            set -- help
        fi
        exec "$YANO_ROOT/appchain-cluster/cluster.sh" "$@"
    fi
    local cli
    cli="$(appchain_cli)"
    cd "$CALLER_DIR"
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
if [ -f "$YANO_ROOT/yano" ]; then
    # Native binary mode
    echo "Starting Yano (native)${PROFILE:+ with profile: $PROFILE}..."
    echo "YANO_EXTRA_ARGS=${YANO_EXTRA_ARGS:-}"
    # shellcheck disable=SC2086
    exec "$YANO_ROOT/yano" \
        -Dyano.block-producer.script-evaluator=scalus \
        $PROFILE_PROP ${YANO_EXTRA_ARGS:-} "${PASSTHROUGH_ARGS[@]}"
elif [ -f "$YANO_ROOT/yano.jar" ]; then
    # Uber-jar mode
    echo "Starting Yano (JVM)${PROFILE:+ with profile: $PROFILE}..."
    echo "JAVA_OPTS=${JAVA_OPTS:-}"
    echo "YANO_EXTRA_ARGS=${YANO_EXTRA_ARGS:-}"
    # shellcheck disable=SC2086
    exec java ${JAVA_OPTS:-} $PROFILE_PROP -jar "$YANO_ROOT/yano.jar" ${YANO_EXTRA_ARGS:-} "${PASSTHROUGH_ARGS[@]}"
elif [ -n "$REPOSITORY_ROOT" ] && [ -f "$YANO_ROOT/build/yano" ]; then
    echo "Starting Yano (native)${PROFILE:+ with profile: $PROFILE}..."
    echo "YANO_EXTRA_ARGS=${YANO_EXTRA_ARGS:-}"
    # shellcheck disable=SC2086
    exec "$YANO_ROOT/build/yano" \
        -Dyano.block-producer.script-evaluator=scalus \
        $PROFILE_PROP ${YANO_EXTRA_ARGS:-} "${PASSTHROUGH_ARGS[@]}"
elif [ -n "$REPOSITORY_ROOT" ] && [ -f "$YANO_ROOT/build/yano.jar" ]; then
    echo "Starting Yano (JVM)${PROFILE:+ with profile: $PROFILE}..."
    echo "JAVA_OPTS=${JAVA_OPTS:-}"
    echo "YANO_EXTRA_ARGS=${YANO_EXTRA_ARGS:-}"
    # shellcheck disable=SC2086
    exec java ${JAVA_OPTS:-} $PROFILE_PROP -jar "$YANO_ROOT/build/yano.jar" \
        ${YANO_EXTRA_ARGS:-} "${PASSTHROUGH_ARGS[@]}"
else
    echo "Error: Neither 'yano' binary nor 'yano.jar' found in $YANO_ROOT"
    if [ -n "$REPOSITORY_ROOT" ]; then
        echo "Build the development JAR with: ./gradlew :app:quarkusBuild"
    fi
    echo "Please ensure the distribution is complete."
    exit 1
fi
