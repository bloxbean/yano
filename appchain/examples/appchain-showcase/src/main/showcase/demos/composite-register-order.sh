#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
exec "$ROOT/showcase.sh" composite register-order "${2:?order key required}" "${3:?order JSON required}" --instance "${1:?instance required}"
