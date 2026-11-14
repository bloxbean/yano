# Coordinated prerelease inputs

This is a temporary artifact bootstrap for issue #245, not a release and not a
source dependency between repositories. Each consumer downloads a successful
manual workflow run pinned to its full commit, verifies its manifest and SHA-256
inventory, and then uses ordinary Maven coordinates. Maven Local stays disabled.

1. Commit and push the Yano sources. Dispatch `stage-consumer-inputs.yml` on that
   branch with `scope=foundation`. It publishes only node, Cardano, and Host API
   contracts to a retained Actions artifact. Record the successful run ID and
   full source commit.
2. Commit and push Yano-X. Dispatch its existing `build.yml` on that branch with
   `scope=framework-stage`, `yano_inputs_run_id=<foundation-run>` and
   `yano_inputs_commit=<foundation-commit>`. It tests the framework, stages its
   inventory-owned publications, and includes the verified foundation Maven
   inputs. It does not build or check out Yano.
3. Dispatch Yano `stage-consumer-inputs.yml` with `scope=host`,
   `framework_run_id=<framework-run>` and `framework_commit=<framework-commit>`.
   It builds the ordinary JVM ZIP and host publications against those inputs.
   The Host API keeps the independent version from the foundation manifest;
   staging fails if the rebuilt Host API JAR differs from the verified input.
4. Run Yano-X's normal `build.yml` with `scope=all` and the successful full-host
   run/commit as its `yano_inputs_*` inputs. Framework projects resolve to their
   normal current-checkout owners; no composite build is introduced.

For branch CI, commit `.github/ci-input-pins.json` after successful staging:

```json
{"framework":{"runId":"<successful-X-run-id>","commit":"<full-X-commit>"}}
```

Explicit action inputs take precedence over this file. Do not invent run IDs or
commit pins before the runs succeed. Artifacts are retained for 90 days; replace
these qualification pins with published dependency versions before that retention
expires. Tagged release/promotion workflows are not part of this bootstrap.

A clean developer checkout can consume exactly the same pinned inputs:

```bash
export GITHUB_WORKSPACE="$PWD"
export GITHUB_ENV="$(mktemp)"
export GH_TOKEN="$(gh auth token)"
YANO_X_INPUTS_RUN_ID=<run-id> YANO_X_INPUTS_COMMIT=<full-commit> \
  bash .github/scripts/prepare-framework-inputs.sh
while IFS='=' read -r name value; do export "$name=$value"; done < "$GITHUB_ENV"
./gradlew build -PskipSigning=true
```

The environment file contains generated paths and versions. Read it as data;
do not print or commit `GH_TOKEN`.

Local script checks (mocked artifact transport and tiny Gradle property probes;
no repository build or publication):

```bash
bash .github/scripts/tests/prepare-framework-inputs-test.sh
bash .github/scripts/tests/stage-consumer-inputs-test.sh
```
