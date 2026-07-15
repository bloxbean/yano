#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPOSITORY_DIR="$(cd "${MODULE_DIR}/../.." && pwd)"
CDDL_BIN="${CDDL_BIN:-cddl}"
GENERATED_ROOT="${MODULE_DIR}/build/generated/connector-contract-resources"
AGGREGATE="${GENERATED_ROOT}/META-INF/yano/contracts/connectors/v1/connectors-v1.cddl"
VECTORS="${MODULE_DIR}/src/main/resources/META-INF/yano/contracts/connectors/v1/golden-vectors.properties"

if ! command -v "${CDDL_BIN}" >/dev/null 2>&1; then
    echo "error: CDDL validator not found: ${CDDL_BIN}" >&2
    echo "set CDDL_BIN to an anweiss cddl-cli 0.10.5 executable" >&2
    exit 2
fi
if ! command -v xxd >/dev/null 2>&1; then
    echo "error: xxd is required to decode the published hexadecimal vectors" >&2
    exit 2
fi

"${REPOSITORY_DIR}/gradlew" \
    :appchain-integration-contracts:generateConnectorAggregateCddl >/dev/null
"${CDDL_BIN}" --ci compile-cddl --cddl "${AGGREGATE}"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/yano-connector-cddl.XXXXXX")"
trap 'rm -rf "${WORK_DIR}"' EXIT

property() {
    local name="$1"
    awk -F= -v key="${name}" '$1 == key { print substr($0, index($0, "=") + 1); exit }' \
        "${VECTORS}"
}

VECTOR_NAMES=(
    kafka.command
    kafka.receipt
    object.command
    object.receipt
    ipfs.command
    ipfs.receipt
    ipfs.submitted
    kafka.detail
    object.detail
    ipfs.detail
)

for vector_name in "${VECTOR_NAMES[@]}"; do
    root="$(property "${vector_name}.cddl-root")"
    value="$(property "${vector_name}")"
    if [[ -z "${root}" || -z "${value}" ]]; then
        echo "error: incomplete CDDL metadata for ${vector_name}" >&2
        exit 1
    fi

    schema="${WORK_DIR}/${vector_name}.cddl"
    binary="${WORK_DIR}/${vector_name}.cbor"
    {
        printf 'connector-vector-root = %s\n\n' "${root}"
        sed -n '1,$p' "${AGGREGATE}"
    } >"${schema}"
    printf '%s' "${value}" | xxd -r -p >"${binary}"
    "${CDDL_BIN}" --ci validate --cddl "${schema}" --cbor "${binary}"
done

echo "Validated ${#VECTOR_NAMES[@]} connector vectors with ${CDDL_BIN}."
