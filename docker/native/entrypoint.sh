#!/usr/bin/env sh
set -eu

PROFILE="${YANO_PROFILE:-preprod}"

# shellcheck disable=SC2086
exec /app/yano -Dquarkus.profile="${PROFILE}" ${YANO_EXTRA_ARGS:-} "$@"
