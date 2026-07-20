#!/usr/bin/env bash
# Source-tree entry point for development and testing only.
#
# This wrapper is not packaged in Yano release ZIPs. The distribution build
# packages app/bin/yano.sh as <distribution-root>/yano.sh. Delegating to that
# same maintained launcher here keeps source-tree and extracted-ZIP commands
# consistent without duplicating their implementation.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/bin/yano.sh" "$@"
