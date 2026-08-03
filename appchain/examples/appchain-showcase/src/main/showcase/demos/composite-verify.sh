#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
exec "$ROOT/showcase.sh" composite verify "${2:?release required}" --instance "${1:?instance required}"
