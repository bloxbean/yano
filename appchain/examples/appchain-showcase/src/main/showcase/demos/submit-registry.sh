#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
exec "$ROOT/showcase.sh" run registry --instance "${1:?instance required}" "${2:?key required}" "${3:?value required}"
