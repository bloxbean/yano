#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
exec "$ROOT/showcase.sh" approvals propose "${2:?proposal required}" "${3:?payload required}" "${4:-2}" --instance "${1:?instance required}"
