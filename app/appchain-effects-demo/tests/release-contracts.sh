#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"

bash -n "$DEMO_DIR/demo.sh" "$DEMO_DIR"/tools/*.sh "$SCRIPT_DIR"/*.sh

for test in \
  anchor-binding-test.sh \
  anchor-funding-test.sh \
  compose-contract.sh \
  demo-launcher-test.sh \
  deployment-parity-contract.sh \
  effect-failover-contract.sh \
  key-material-test.sh \
  lifecycle-tool-test.sh \
  managed-process-test.sh \
  operation-lock-test.sh \
  rustfs-iam-spec-test.sh \
  secret-file-test.sh
do
  bash "$SCRIPT_DIR/$test"
done

printf '%s\n' 'PASS: ADR-013 release contracts'
