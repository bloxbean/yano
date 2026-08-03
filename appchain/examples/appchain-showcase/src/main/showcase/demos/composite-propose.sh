#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
exec "$ROOT/showcase.sh" composite propose "${2:?proposal required}" "${3:?order JSON required}" --instance "${1:?instance required}"
