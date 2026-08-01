#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
exec "$ROOT/showcase.sh" composite release "${2:?release required}" "${3:?order key required}" "${4:?proposal required}" --instance "${1:?instance required}"
