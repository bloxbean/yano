#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
exec "$ROOT/showcase.sh" run orders --instance "${1:?instance required}" "${2:-demo-order}"
