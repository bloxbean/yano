#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
exec "$ROOT/showcase.sh" load "${2:?scenario required}" --count "${3:?count required}" --instance "${1:?instance required}"
