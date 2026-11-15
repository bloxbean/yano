# Staged Yano inputs for downstream branch CI

Yano depends on no downstream product (ADR-064). Downstream projects, such as
the application-ledger framework, build against Yano's published Maven
artifacts. When a downstream branch needs unreleased Yano changes, its CI can
consume a staged copy instead of a snapshot. Staging is not a release, and
Maven Local stays disabled.

Dispatch `stage-consumer-inputs.yml` on a pushed Yano commit:

- `scope=foundation` publishes only the node, Cardano and Host API contracts
  (`yano-node-api`, `yano-cardano-api`, `yano-host-api`).
- `scope=host` (the default) publishes every Yano publication, builds the JVM
  ZIP and runs the packaged plugin-catalog smoke.

The run uploads `yano-inputs-<commit>` as an Actions artifact retained for 90
days. It contains a file Maven repository (`maven/`), the ZIP for the host
scope, `yano-inputs.json` (commit, version, Host API version, scope) and a
`SHA256SUMS` inventory. Versions are `<base>-<commit prefix>`, so staged
artifacts never collide with snapshots or releases. The Host API keeps its own
version (`hostApiVersion`, default `1.0.0-<commit prefix>`).

A consumer records the run ID and full commit, verifies the manifest and the
checksum inventory, and points an ordinary Maven repository at `maven/`.
Replace staged versions with published ones before the artifact expires.

Local check of the staging script (mocked Gradle and git; no build or
publication):

```bash
bash .github/scripts/tests/stage-consumer-inputs-test.sh
```
