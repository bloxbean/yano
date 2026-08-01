#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
INSTANCE="${1:?instance required}"
shift
exec "$ROOT/showcase.sh" soak-test "$@" --instance "$INSTANCE"
