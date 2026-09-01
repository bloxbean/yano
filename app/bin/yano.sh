#!/usr/bin/env bash
#
# Yano start script
# Auto-detects JAR vs native mode and supports Quarkus profiles.
#
# Usage:
#   ./yano.sh start                # Default preprod profile
#   ./yano.sh start:preprod,relay  # Preprod with relay upstream profile
#   ./yano.sh start:preprod,projection # Preprod with the optional history archive
#   ./yano.sh start:mainnet        # Mainnet relay alias
#   ./yano.sh start:<profiles>     # Custom comma-separated Quarkus profiles
#   ./yano.sh repair pointer-index --database ./chainstate \
#       --confirm REPAIR_POINTER_INDEX
#   ./yano.sh appchain config ...  # App-chain configuration tooling
#   ./yano.sh appchain cluster ... # Local cluster launcher
#   ./yano.sh observability start  # Optional persistent metrics history
#

set -e

CALLER_DIR="$PWD"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
YANO_ROOT="$SCRIPT_DIR"

# In a release, this script lives beside yano.jar and config/. In the source
# tree, app/yano.sh delegates here while this file remains under app/bin/.
# Resolve both layouts without depending on optional Yano X tooling.
if [ "$(basename "$SCRIPT_DIR")" = "bin" ] \
    && [ -d "$SCRIPT_DIR/../config" ]; then
    YANO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
fi
export YANO_HOME="$YANO_ROOT"
cd "$YANO_ROOT"

usage() {
    cat <<EOF
Usage: ./yano.sh [start|start:<profiles>|repair|appchain|observability|help] [args...]

Examples:
  ./yano.sh start
  ./yano.sh start:preprod,relay
  ./yano.sh start:preprod,relay,praos-lite
  ./yano.sh start:preprod,projection
  ./yano.sh start:mainnet,projection
  ./yano.sh start:mainnet
  ./yano.sh start:preview
  ./yano.sh start:sanchonet
  ./yano.sh start:devnet
  ./yano.sh start:mydevnet
  ./yano.sh repair pointer-index --database ./chainstate \
      --confirm REPAIR_POINTER_INDEX
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
  ./yano.sh observability start
  ./yano.sh observability status

Environment:
  JAVA_OPTS        JVM options for jar and native distributions. The native
                   binary accepts -D, -X (-Xmx/-Xms/-Xss), -verbose and its own
                   -XX: options; run '<binary> -XX:PrintFlags=' to list them.
                   JVM agents and module-system flags cannot exist in a native
                   image and are dropped with a warning.
  YANO_NATIVE_MAX_HEAP
                   Native default maximum heap (default: 1536m). An explicit
                   -Xmx in JAVA_OPTS overrides this value.
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
  plugin inspect|validate|sign|scaffold ...
                                  Work with signed custom component catalogs
  metadata verify ...             Verify custom-plugin metadata signatures
  role ...                        Encode/sign role commands offline (seed files only)
  authenticated-map ...           Assemble governed map actions/evidence offline
  validity key generate ...       Create an encrypted local L2 session key
  validity ...                    Operate an experimental EUTxO ZeroJ testnet lifecycle

Run a local cluster:
  cluster start [members]         Start or resume a same-machine cluster
  cluster status|stop|clean       Inspect, stop, or clean the local cluster
  cluster node join <index>       Start a previously staged additional node
  cluster effect demo [message]   Submit and approve a demonstration effect

Examples:
  ./yano.sh appchain init --recipe owned-registry --network devnet --members 3
  ./yano.sh appchain config validate --mode project ./owned-registry
  ./yano.sh appchain cluster start 3
  ./yano.sh appchain validity key generate --output l2-session-key.enc --password-env YANO_L2_KEY_PASSWORD
  ./yano.sh appchain validity status --project ./payments-zk

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
        echo "Install the release-matched Yano X JVM distribution/tooling archive." >&2
        echo "Advanced users may set YANO_APPCHAIN_CLI." >&2
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
            echo "Install the release-matched Yano X JVM distribution." >&2
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

if [ "$1" = "observability" ]; then
    shift
    if [ ! -x "$YANO_ROOT/observability/observability.sh" ]; then
        echo "Error: observability/observability.sh is missing or not executable." >&2
        exit 1
    fi
    [ "$#" -gt 0 ] || set -- help
    exec "$YANO_ROOT/observability/observability.sh" "$@"
fi

# Parse profile from arguments
PROFILE=""
PASSTHROUGH_ARGS=()
MAINTENANCE_COMMAND=""

if [ "${1:-}" = "repair" ]; then
    if [ "${2:-}" != "pointer-index" ]; then
        echo "Error: supported repair command: pointer-index" >&2
        exit 2
    fi
    MAINTENANCE_COMMAND="pointer-index repair"
    PASSTHROUGH_ARGS=("$@")
fi

if [ -z "$MAINTENANCE_COMMAND" ]; then
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
fi

# Build profile system property if set
PROFILE_PROP=""
if [ -n "$PROFILE" ]; then
    validate_profile_list "$PROFILE"
    PROFILE_PROP="-Dquarkus.profile=${PROFILE}"
fi

# JAVA_OPTS is honoured by both distributions. A GraalVM native image accepts
# -D system properties, the -X memory flags, -verbose, and its own -XX:
# namespace (this image advertises 48 of them, including MaxHeapSize,
# MinHeapSize, MaximumHeapSizePercent and VerboseGC), so all of those are
# forwarded verbatim -- the launcher must not second-guess an option set that
# varies by GraalVM version. A HotSpot-only -XX: flag is rejected by the image
# itself with an immediate, precise "error: Could not find option 'X'. Use
# -XX:PrintFlags= to list all available options", which is a better outcome
# than silently discarding tuning the operator asked for.
#
# Only constructs a native image can never implement -- JVM agents and the
# module-system flags -- are dropped, and then with an explicit warning.
#
# -X is the asymmetric case: the image silently ignores an -X it does not
# understand, so a typo or a HotSpot-only flag would take no effect with no
# error. Anything beyond the verified -Xmx/-Xms/-Xss is therefore still
# forwarded, but called out so the operator is never left assuming a setting
# applied when it did not.
NATIVE_JAVA_OPTS=()
collect_native_java_opts() {
    NATIVE_JAVA_OPTS=()

    local dropped=""
    local unverified=""
    local has_max_heap="false"
    local opt
    # shellcheck disable=SC2086
    set -- $JAVA_OPTS
    for opt in "$@"; do
        case "$opt" in
            -javaagent*|-agentlib*|-agentpath*|--add-*|--enable-preview)
                dropped="${dropped} ${opt}"
                ;;
            -D*|-Xmx*|-Xms*|-Xss*|-XX:*|-verbose*)
                NATIVE_JAVA_OPTS[${#NATIVE_JAVA_OPTS[@]}]="$opt"
                case "$opt" in
                    -Xmx*) has_max_heap="true" ;;
                esac
                ;;
            -X*)
                # Forwarded so a newer GraalVM keeps working, but flagged: the
                # image will not complain if it does not understand it.
                unverified="${unverified} ${opt}"
                NATIVE_JAVA_OPTS[${#NATIVE_JAVA_OPTS[@]}]="$opt"
                ;;
            *)
                dropped="${dropped} ${opt}"
                ;;
        esac
    done

    if [ "$has_max_heap" = "false" ]; then
        NATIVE_JAVA_OPTS[${#NATIVE_JAVA_OPTS[@]}]="-Xmx${YANO_NATIVE_MAX_HEAP:-1536m}"
    fi

    if [ -n "$dropped" ]; then
        echo "Warning: dropping JAVA_OPTS entries a native image cannot implement:" >&2
        echo "        ${dropped# }" >&2
        echo "         JVM agents and module-system flags have no native equivalent." >&2
    fi
    if [ -n "$unverified" ]; then
        echo "Warning: forwarding JAVA_OPTS entries this image may ignore silently:" >&2
        echo "        ${unverified# }" >&2
        echo "         Only -Xmx, -Xms and -Xss are verified on a native image, and" >&2
        echo "         unknown -X options are accepted without error, so a typo or a" >&2
        echo "         HotSpot-only flag would take no effect. Prefer the equivalent" >&2
        echo "         -XX: option; run '<binary> -XX:PrintFlags=' to list them." >&2
    fi
}

# Auto-detect mode: native binary or JAR
if [ -f "$YANO_ROOT/yano" ]; then
    # Native binary mode
    if [ -n "$MAINTENANCE_COMMAND" ]; then
        echo "Running Yano $MAINTENANCE_COMMAND (native)..."
    else
        echo "Starting Yano (native)${PROFILE:+ with profile: $PROFILE}..."
    fi
    collect_native_java_opts
    echo "JAVA_OPTS=${JAVA_OPTS:-}"
    echo "YANO_EXTRA_ARGS=${YANO_EXTRA_ARGS:-}"
    # shellcheck disable=SC2086
    exec "$YANO_ROOT/yano" \
        -Dyano.block-producer.script-evaluator=scalus \
        "${NATIVE_JAVA_OPTS[@]}" $PROFILE_PROP ${YANO_EXTRA_ARGS:-} "${PASSTHROUGH_ARGS[@]}"
elif [ -f "$YANO_ROOT/yano.jar" ]; then
    # Uber-jar mode
    if [ -n "$MAINTENANCE_COMMAND" ]; then
        echo "Running Yano $MAINTENANCE_COMMAND (JVM)..."
    else
        echo "Starting Yano (JVM)${PROFILE:+ with profile: $PROFILE}..."
    fi
    echo "JAVA_OPTS=${JAVA_OPTS:-}"
    echo "YANO_EXTRA_ARGS=${YANO_EXTRA_ARGS:-}"
    # shellcheck disable=SC2086
    exec java ${JAVA_OPTS:-} $PROFILE_PROP -jar "$YANO_ROOT/yano.jar" ${YANO_EXTRA_ARGS:-} "${PASSTHROUGH_ARGS[@]}"
elif [ -n "$REPOSITORY_ROOT" ] && [ -f "$YANO_ROOT/build/yano" ]; then
    if [ -n "$MAINTENANCE_COMMAND" ]; then
        echo "Running Yano $MAINTENANCE_COMMAND (native)..."
    else
        echo "Starting Yano (native)${PROFILE:+ with profile: $PROFILE}..."
    fi
    collect_native_java_opts
    echo "JAVA_OPTS=${JAVA_OPTS:-}"
    echo "YANO_EXTRA_ARGS=${YANO_EXTRA_ARGS:-}"
    # shellcheck disable=SC2086
    exec "$YANO_ROOT/build/yano" \
        -Dyano.block-producer.script-evaluator=scalus \
        "${NATIVE_JAVA_OPTS[@]}" $PROFILE_PROP ${YANO_EXTRA_ARGS:-} "${PASSTHROUGH_ARGS[@]}"
elif [ -n "$REPOSITORY_ROOT" ] && [ -f "$YANO_ROOT/build/yano.jar" ]; then
    if [ -n "$MAINTENANCE_COMMAND" ]; then
        echo "Running Yano $MAINTENANCE_COMMAND (JVM)..."
    else
        echo "Starting Yano (JVM)${PROFILE:+ with profile: $PROFILE}..."
    fi
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
