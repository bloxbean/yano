#!/usr/bin/env sh
set -eu

# Pinned, prebuilt validator used by CI and local release acceptance. Yano does
# not compile the third-party CDDL tool. Mount the repository at its identical
# absolute path so generated schemas and vectors remain addressable.
IMAGE="ghcr.io/anweiss/cddl-cli:0.10.5@sha256:c1b0e0c1ec57648081026172ab09cb60a592b888777b9a80210f1866fb5029af"
ROOT="$(git rev-parse --show-toplevel)"

exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  --volume "$ROOT:$ROOT" \
  --workdir "$PWD" \
  "$IMAGE" "$@"
