#!/usr/bin/env bash
# Fails unless this commit's push CI succeeded: build.yml runs `build`, and integration.yml runs `extendedTest`,
# `distributionCheck` and the native build, which together cover everything `fullBuild` runs. The snapshot
# publishing workflows publish only such a commit instead of repeating those tests. Needs GH_TOKEN with
# `actions: read`.
set -euo pipefail

for workflow in build.yml integration.yml; do
  runs="repos/$GITHUB_REPOSITORY/actions/workflows/$workflow/runs"
  passed=$(gh api "$runs?head_sha=$GITHUB_SHA&event=push&status=success" --jq '.total_count')
  if [[ "$passed" == 0 ]]; then
    echo "::error::$workflow has no successful push run for $GITHUB_SHA. Run this again once the push CI is green." >&2
    exit 1
  fi
done
echo "build.yml and integration.yml passed for $GITHUB_SHA"
