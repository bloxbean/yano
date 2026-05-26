#!/usr/bin/env sh
set -eu

PROFILE="${YANO_PROFILE:-preprod}"

# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -Dquarkus.profile="${PROFILE}" -jar /app/yano.jar ${YANO_EXTRA_ARGS:-} "$@"
