#!/bin/bash
# Download (if needed) and configure a Haskell cardano-node for compatibility tests.
#
# Idempotent: re-running is safe. Skips download when an existing binary already
# satisfies the required version. Otherwise fetches the requested release from
# https://github.com/IntersectMBO/cardano-node and installs into:
#
#   <project-root>/test-data-dir/haskell-node/
#
# Usage:
#   bash scripts/setup-haskell-test-node.sh                   # uses default version 11.0.1
#   HASKELL_NODE_VERSION=11.1.0 bash scripts/setup-haskell-test-node.sh
#
# After this script:
#   test-data-dir/haskell-node/bin/cardano-node          (executable)
#   test-data-dir/haskell-node/configuration.json
#   test-data-dir/haskell-node/files/topology.json
#   test-data-dir/haskell-node/files/{shelley,byron,alonzo,conway}-genesis.json
#                                                         (copied separately by
#                                                          copy-devnet-genesis-to-haskell.sh)

set -euo pipefail

HASKELL_NODE_VERSION="${HASKELL_NODE_VERSION:-11.0.1}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# This script lives at <project-root>/scripts/haskell-compatibility/, so the
# project root is two levels up.
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TEST_DATA_DIR="$PROJECT_ROOT/test-data-dir"
HASKELL_NODE_DIR="$TEST_DATA_DIR/haskell-node"
BIN="$HASKELL_NODE_DIR/bin/cardano-node"

UNAME_S="$(uname -s)"
UNAME_M="$(uname -m)"
case "$UNAME_S" in
  Darwin) ASSET_OS="macos" ;;
  Linux)  ASSET_OS="linux" ;;
  *) echo "ERROR: unsupported OS: $UNAME_S" >&2; exit 1 ;;
esac
# On macOS, prefer the amd64 build (runs under Rosetta 2): the arm64 release
# has known compatibility issues for these tests. On Linux, follow native arch.
if [ "$ASSET_OS" = "macos" ]; then
  ASSET_ARCH="amd64"
else
  case "$UNAME_M" in
    arm64|aarch64) ASSET_ARCH="arm64" ;;
    x86_64|amd64)  ASSET_ARCH="amd64" ;;
    *) echo "ERROR: unsupported arch: $UNAME_M" >&2; exit 1 ;;
  esac
fi

current_version() {
  [ -x "$BIN" ] || return 1
  "$BIN" --version 2>/dev/null | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
}

# returns 0 if $1 >= $2 (semver)
version_ge() {
  [ "$1" = "$2" ] && return 0
  local higher
  higher=$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)
  [ "$higher" = "$1" ]
}

NEED_DOWNLOAD=1
if CUR=$(current_version 2>/dev/null) && [ -n "$CUR" ]; then
  if version_ge "$CUR" "$HASKELL_NODE_VERSION"; then
    echo "[setup-haskell] Existing cardano-node $CUR satisfies required $HASKELL_NODE_VERSION; skipping download."
    NEED_DOWNLOAD=0
  else
    echo "[setup-haskell] Existing cardano-node $CUR < required $HASKELL_NODE_VERSION; will upgrade."
  fi
fi

if [ "$NEED_DOWNLOAD" = "1" ]; then
  mkdir -p "$TEST_DATA_DIR" "$HASKELL_NODE_DIR/files"
  TAG="$HASKELL_NODE_VERSION"
  ASSET_NAME="cardano-node-${TAG}-${ASSET_OS}-${ASSET_ARCH}.tar.gz"
  ASSET_URL="https://github.com/IntersectMBO/cardano-node/releases/download/${TAG}/${ASSET_NAME}"

  TMP_TGZ="$TEST_DATA_DIR/${ASSET_NAME}"
  echo "[setup-haskell] Downloading ${ASSET_URL}"
  if ! curl -fsSL --retry 3 -o "$TMP_TGZ" "$ASSET_URL"; then
    echo "ERROR: download failed for $ASSET_URL" >&2
    echo "Inspect available assets at: https://github.com/IntersectMBO/cardano-node/releases/tag/${TAG}" >&2
    exit 1
  fi

  echo "[setup-haskell] Extracting"
  rm -rf "$HASKELL_NODE_DIR/.extract" "$HASKELL_NODE_DIR/bin"
  mkdir -p "$HASKELL_NODE_DIR/.extract" "$HASKELL_NODE_DIR/bin"
  tar -xzf "$TMP_TGZ" -C "$HASKELL_NODE_DIR/.extract"

  EXTRACTED=$(find "$HASKELL_NODE_DIR/.extract" -maxdepth 6 -type f -name cardano-node | head -1)
  if [ -z "$EXTRACTED" ]; then
    echo "ERROR: cardano-node binary not found in extracted tarball" >&2
    exit 1
  fi
  EXTRACTED_DIR="$(dirname "$EXTRACTED")"
  cp -a "$EXTRACTED_DIR/." "$HASKELL_NODE_DIR/bin/"
  chmod +x "$HASKELL_NODE_DIR/bin/cardano-node"
  rm -rf "$HASKELL_NODE_DIR/.extract" "$TMP_TGZ"

  echo "[setup-haskell] Installed: $("$BIN" --version 2>/dev/null | head -1)"
fi

# --- Genesis overrides ---
# cardano-node 11.0.x is strict about the conway-genesis.json plutusV3CostModel
# size (251 entries). Yano's bundled devnet conway-genesis.json carries 297
# entries from a different upstream source, which 11.0.x rejects with:
#   "Number of parameters supplied 297 does not match the expected number of 251"
# We pull the canonical preprod conway-genesis.json (251 entries) into
# test-data-dir/genesis-overrides/ so both Yano (via -Dyano.genesis.conway-genesis-file)
# and the Haskell node (via files/conway-genesis.json) load the same bytes.
GENESIS_OVERRIDE_DIR="$TEST_DATA_DIR/genesis-overrides"
CONWAY_OVERRIDE="$GENESIS_OVERRIDE_DIR/conway-genesis.json"
CONWAY_SOURCE_URL="${CONWAY_OVERRIDE_URL:-https://book.play.dev.cardano.org/environments/preprod/conway-genesis.json}"

if [ ! -f "$CONWAY_OVERRIDE" ]; then
  mkdir -p "$GENESIS_OVERRIDE_DIR"
  echo "[setup-haskell] Fetching conway-genesis override from $CONWAY_SOURCE_URL"
  if ! curl -fsSL --retry 3 -o "$CONWAY_OVERRIDE" "$CONWAY_SOURCE_URL"; then
    echo "ERROR: failed to download conway-genesis override from $CONWAY_SOURCE_URL" >&2
    exit 1
  fi
fi
echo "[setup-haskell] Conway override: $CONWAY_OVERRIDE"

echo "[setup-haskell] Writing configuration.json and files/topology.json"
mkdir -p "$HASKELL_NODE_DIR/files"

# Dijkstra genesis only exists from cardano-node 11.0.0 onward
INCLUDE_DIJKSTRA=0
if version_ge "$HASKELL_NODE_VERSION" "11.0.0"; then
  INCLUDE_DIJKSTRA=1
fi

if [ "$INCLUDE_DIJKSTRA" = "1" ]; then
  DIJKSTRA_LINE='  "DijkstraGenesisFile": "./files/dijkstra-genesis.json",'
else
  DIJKSTRA_LINE=''
fi

cat > "$HASKELL_NODE_DIR/configuration.json" <<EOF
{
  "AlonzoGenesisFile": "./files/alonzo-genesis.json",
  "ByronGenesisFile": "./files/byron-genesis.json",
  "ConwayGenesisFile": "./files/conway-genesis.json",
${DIJKSTRA_LINE:+$DIJKSTRA_LINE
}  "EnableP2P": true,
  "LastKnownBlockVersion-Alt": 0,
  "LastKnownBlockVersion-Major": 2,
  "LastKnownBlockVersion-Minor": 0,
  "LedgerDB": {
    "Backend": "V2InMemory",
    "NumOfDiskSnapshots": 2,
    "QueryBatchSize": 100000,
    "SnapshotInterval": 4320
  },
  "PeerSharing": true,
  "Protocol": "Cardano",
  "RequiresNetworkMagic": "RequiresMagic",
  "ShelleyGenesisFile": "./files/shelley-genesis.json",
  "TestShelleyHardForkAtEpoch": 0,
  "TestAllegraHardForkAtEpoch": 0,
  "TestMaryHardForkAtEpoch": 0,
  "TestAlonzoHardForkAtEpoch": 0,
  "TestBabbageHardForkAtEpoch": 0,
  "TestConwayHardForkAtEpoch": 0,
  "ExperimentalHardForksEnabled": true,
  "ExperimentalProtocolsEnabled": true,
  "TargetNumberOfActivePeers": 20,
  "TargetNumberOfEstablishedPeers": 40,
  "TargetNumberOfKnownPeers": 100,
  "TargetNumberOfRootPeers": 100,
  "TraceAcceptPolicy": true,
  "TraceBlockFetchClient": true,
  "TraceBlockFetchDecisions": true,
  "TraceBlockFetchProtocol": true,
  "TraceBlockFetchProtocolSerialised": false,
  "TraceBlockFetchServer": false,
  "TraceChainDb": true,
  "TraceChainSyncBlockServer": false,
  "TraceChainSyncClient": true,
  "TraceChainSyncHeaderServer": false,
  "TraceChainSyncProtocol": false,
  "TraceConnectionManager": true,
  "TraceDNSResolver": true,
  "TraceDNSSubscription": true,
  "TraceDiffusionInitialization": true,
  "TraceErrorPolicy": true,
  "TraceForge": true,
  "TraceHandshake": true,
  "TraceInboundGovernor": true,
  "TraceIpSubscription": true,
  "TraceLedgerPeers": true,
  "TraceLocalChainSyncProtocol": false,
  "TraceLocalConnectionManager": true,
  "TraceLocalErrorPolicy": true,
  "TraceLocalHandshake": true,
  "TraceLocalRootPeers": true,
  "TraceLocalTxSubmissionProtocol": false,
  "TraceLocalTxSubmissionServer": false,
  "TraceMempool": true,
  "TraceMux": false,
  "TracePeerSelection": true,
  "TracePeerSelectionActions": true,
  "TracePublicRootPeers": true,
  "TraceServer": true,
  "TraceTxInbound": false,
  "TraceTxOutbound": false,
  "TraceTxSubmissionProtocol": false,
  "TracingVerbosity": "NormalVerbosity",
  "TurnOnLogMetrics": true,
  "TurnOnLogging": true,
  "UseTraceDispatcher": false,
  "defaultBackends": [
    "KatipBK"
  ],
  "defaultScribes": [
    [
      "StdoutSK",
      "stdout"
    ]
  ],
  "hasEKG": 12788,
  "hasPrometheus": [
    "127.0.0.1",
    12798
  ],
  "minSeverity": "Info",
  "options": {
    "mapBackends": {
      "cardano.node.metrics": [
        "EKGViewBK"
      ],
      "cardano.node.resources": [
        "EKGViewBK"
      ]
    },
    "mapSubtrace": {
      "cardano.node.metrics": {
        "subtrace": "Neutral"
      }
    }
  },
  "rotation": {
    "rpKeepFilesNum": 10,
    "rpLogLimitBytes": 5000000,
    "rpMaxAgeHours": 24
  },
  "setupBackends": [
    "KatipBK"
  ],
  "setupScribes": [
    {
      "scFormat": "ScText",
      "scKind": "StdoutSK",
      "scName": "stdout",
      "scRotation": null
    }
  ],
  "TraceChainDB": true,
  "MinBigLedgerPeersForTrustedState": 0
}
EOF

cat > "$HASKELL_NODE_DIR/files/dijkstra-genesis.json" <<'EOF'
{
  "maxRefScriptSizePerBlock": 1048576,
  "maxRefScriptSizePerTx": 204800,
  "refScriptCostStride": 25600,
  "refScriptCostMultiplier": 1.2
}
EOF

cat > "$HASKELL_NODE_DIR/files/topology.json" <<'EOF'
{
  "bootstrapPeers": [
    {"address": "127.0.0.1", "port": 13337}
  ],
  "localRoots": [
    {
      "accessPoints": [
        {"address": "127.0.0.1", "port": 13337}
      ],
      "valency": 1
    }
  ],
  "publicRoots": [],
  "useLedgerAfterSlot": -1
}
EOF

# Convenience wrapper to check the running node's tip via cardano-cli.
# Uses the bundled cardano-cli + the node socket created by ./bin/cardano-node run.
cat > "$HASKELL_NODE_DIR/tip.sh" <<'EOF'
#!/bin/bash
# Query the local Haskell cardano-node tip via the bundled cardano-cli.
# Run this from any directory; it resolves paths relative to its own location.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SOCKET="$DIR/db/node.socket"
NETWORK_MAGIC="${NETWORK_MAGIC:-42}"
if [ ! -S "$SOCKET" ]; then
  echo "ERROR: socket not found at $SOCKET — is the Haskell node running?" >&2
  exit 1
fi
CARDANO_NODE_SOCKET_PATH="$SOCKET" \
  "$DIR/bin/cardano-cli" query tip --testnet-magic "$NETWORK_MAGIC"
EOF
chmod +x "$HASKELL_NODE_DIR/tip.sh"

echo "[setup-haskell] Ready: $HASKELL_NODE_DIR"
echo "[setup-haskell] Binary: $BIN"
echo "[setup-haskell] Tip check: $HASKELL_NODE_DIR/tip.sh"
