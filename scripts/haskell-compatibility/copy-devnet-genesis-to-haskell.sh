#!/bin/bash
# Copy the running Yano devnet's genesis files into the Haskell test node folder.
#
# IMPORTANT: Run this AFTER Yano starts, because Yano updates systemStart
# dynamically on every startup.
#
# Source : <YANO_GENESIS_DIR>/{shelley,byron,alonzo,conway}-genesis.json
#          Defaults to <project-root>/app/config/network/devnet/.
#          If <YANO_GENESIS_DIR>/conway-genesis.json is missing, the script
#          falls back to <project-root>/test-data-dir/genesis-overrides/
#          for conway (legacy flow for the bundled PV11 devnet folder).
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

# Decide where conway-genesis.json comes from. Prefer the source dir (e.g.
# app/config/network/devnet/pv10/ already has a 251-entry Haskell-compatible
# conway). Fall back to the downloaded override for legacy callers that point
# at the PV11 devnet folder whose conway is rejected by cardano-node 11.0.x.
if [ -f "$YANO_GENESIS_DIR/conway-genesis.json" ]; then
  CONWAY_SRC_DIR="$YANO_GENESIS_DIR"
elif [ -f "$GENESIS_OVERRIDE_DIR/conway-genesis.json" ]; then
  CONWAY_SRC_DIR="$GENESIS_OVERRIDE_DIR"
else
  echo "ERROR: conway-genesis.json not found in $YANO_GENESIS_DIR or $GENESIS_OVERRIDE_DIR" >&2
  echo "Run scripts/haskell-compatibility/setup-haskell-test-node.sh first, or" >&2
  echo "point YANO_GENESIS_DIR at a folder that contains conway-genesis.json." >&2
  exit 1
fi

mkdir -p "$HASKELL_FILES"
# shelley/byron/alonzo come from Yano so systemStart, network magic, initial funds line up
cp "$YANO_GENESIS_DIR/shelley-genesis.json" "$HASKELL_FILES/"
cp "$YANO_GENESIS_DIR/byron-genesis.json"   "$HASKELL_FILES/"
cp "$YANO_GENESIS_DIR/alonzo-genesis.json"  "$HASKELL_FILES/"
cp "$CONWAY_SRC_DIR/conway-genesis.json"    "$HASKELL_FILES/"

echo "Genesis copied -> $HASKELL_FILES"
echo "  shelley/byron/alonzo : from $YANO_GENESIS_DIR"
echo "  conway               : from $CONWAY_SRC_DIR"
