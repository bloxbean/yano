#!/bin/bash
# Copy the running Yano devnet's genesis files into the Haskell test node folder.
#
# IMPORTANT: Run this AFTER Yano starts, because Yano updates systemStart
# dynamically on every startup.
#
# Source : <project-root>/app/config/network/devnet/{shelley,byron,alonzo}-genesis.json
#          <project-root>/test-data-dir/genesis-overrides/conway-genesis.json
#                                       (251-entry preprod variant; the bundled
#                                        Yano devnet conway-genesis.json has 297
#                                        plutusV3 entries which cardano-node
#                                        11.0.x rejects)
# Target : <project-root>/test-data-dir/haskell-node/files/

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# This script lives at <project-root>/scripts/haskell-compatibility/, so the
# project root is two levels up.
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

YANO_GENESIS_DIR="${1:-$PROJECT_ROOT/app/config/network/devnet}"
GENESIS_OVERRIDE_DIR="$PROJECT_ROOT/test-data-dir/genesis-overrides"
HASKELL_FILES="$PROJECT_ROOT/test-data-dir/haskell-node/files"

if [ ! -f "$YANO_GENESIS_DIR/shelley-genesis.json" ]; then
  echo "ERROR: Genesis files not found at $YANO_GENESIS_DIR" >&2
  echo "Make sure Yano devnet has been started at least once." >&2
  exit 1
fi
if [ ! -f "$GENESIS_OVERRIDE_DIR/conway-genesis.json" ]; then
  echo "ERROR: Conway override not found at $GENESIS_OVERRIDE_DIR/conway-genesis.json" >&2
  echo "Run scripts/setup-haskell-test-node.sh first." >&2
  exit 1
fi

mkdir -p "$HASKELL_FILES"
# shelley/byron/alonzo come from Yano so systemStart, network magic, initial funds line up
cp "$YANO_GENESIS_DIR/shelley-genesis.json" "$HASKELL_FILES/"
cp "$YANO_GENESIS_DIR/byron-genesis.json"   "$HASKELL_FILES/"
cp "$YANO_GENESIS_DIR/alonzo-genesis.json"  "$HASKELL_FILES/"
# Conway uses the cost-model-trimmed override (must match the file Yano was
# started with via -Dyano.genesis.conway-genesis-file)
cp "$GENESIS_OVERRIDE_DIR/conway-genesis.json" "$HASKELL_FILES/"

echo "Genesis copied -> $HASKELL_FILES"
echo "  shelley/byron/alonzo : from $YANO_GENESIS_DIR"
echo "  conway               : from $GENESIS_OVERRIDE_DIR (251-entry plutusV3CostModel)"
