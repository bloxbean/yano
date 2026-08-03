#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
exec "$ROOT/showcase.sh" approvals approve "${2:?proposal required}" "${3:?member node required}" --instance "${1:?instance required}"
