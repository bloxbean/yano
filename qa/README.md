# Release QA

`qa/release-qa.sh` builds Yano once, runs the regression tests one at a time on
isolated ports, and writes a report you can share. The Claude Code skill
`release-qa` wraps it (launch, failure triage, publishing), and each `test-*` skill
runs a single test through it.

```bash
qa/release-qa.sh                              # l1, appchain, e2e, compat, docker; JVM and native
qa/release-qa.sh --quick                      # JVM variants only
qa/release-qa.sh --categories l1,e2e
qa/release-qa.sh --only haskell-sync-jvm,compat-jvm
qa/release-qa.sh --list                       # test ids, categories, time limits
qa/release-qa.sh --render qa/results/<run-id> # re-render after adding triage notes
```

## Tests

| Category | Test ids | What they prove |
|---|---|---|
| `l1` | `epoch-crossing-{jvm,native}` | Devnet block production crosses two epoch boundaries |
| | `haskell-sync-{jvm,native}` | A Haskell cardano-node follows Yano for 2 epochs with matching hashes |
| | `past-time-travel-{jvm,native}` | Epoch shift, catch-up to wall clock, Haskell sync from slot 0 |
| | `sparse-backfill` | Backfill interval matrix; Haskell syncs the history and follows live blocks |
| `appchain` | `appchain-cluster` | Two-node cluster: sequencing, proofs, finality, L1 anchor |
| | `appchain-extensions` | Multi-chain, query, SSE, webhooks, admin API, evidence, snapshot, metrics |
| | `appchain-rotation-governance` | Rotating sequencer and governed membership |
| | `appchain-script-anchor` | Script anchors, follower identity adoption, L1 deposit observations |
| `e2e` | `e2e-{jvm,native}` | `e2e-tests/` endpoint smoke and devnet functional suites, incl. time travel |
| `compat` | `compat-{jvm,native}` | `compat-tests/` CCL, Mesh and Evolution compat cases on a fresh node |
| `load` (opt-in) | `load-jvm` | `compat-tests/` load and chained-transaction cases on a fresh node |
| `docker` | `docker-dist` | Compose bundle on devnet: launcher, read-only config and writable network mounts, API sweep, snapshot restore, edited and new profiles, instance ownership, older `config/env`, projection history, in-place upgrade from the unnamed-project layout |
| `docker-public` (opt-in) | `docker-public` | Compose bundle syncing preprod (`wallet,small`), preview (`small`) and mainnet (`wallet,medium`) to a target epoch; needs internet |

A full default run takes about 2-3 hours, most of it in the Haskell and app-chain tests.

## Prerequisites

- JDK 25 (`JAVA_HOME`), `jq`, `python3`, `curl`, `lsof`, `unzip`, GNU `timeout`
  (`brew install coreutils` on macOS).
- A Haskell cardano-node in `test-data-dir/haskell-node/bin` for the Haskell and
  sparse-backfill tests: `scripts/haskell-compatibility/setup-haskell-test-node.sh`.
  Override the location with `HASKELL_NODE_DIR` (the sparse-backfill runner always uses
  `test-data-dir/haskell-node`). On macOS the setup script uses the `macos-amd64` asset;
  the arm64 build is broken.
- Docker with Compose v2 for the `docker` categories. They build `bloxbean/yano:qa-local-jvm`
  from the working tree (your own image tags are untouched) and reuse it while the commit
  and uncommitted changes stay the same.
- `node` and `npm` for the `compat` and `load` categories. The suite runs `npm ci` for
  each JavaScript stack, so it needs network access.
- GraalVM for native tests: set `QA_GRAALVM_HOME` (or `GRAALVM_HOME`). If `JAVA_HOME`
  is a Liberica JDK the build selects the parallel GC, which needs a Liberica NIK.

Missing prerequisites stop the run before any test (exit 3), except a missing
cardano-node, native binary or Docker daemon, which marks the affected tests `BLOCKED`.
Docker tests remove only their own `yano-qa-docker-*` containers and networks.

## Isolation

Tests run one at a time. The harness uses its own ports (HTTP 7171/7172, N2N
13441/13442, Haskell 3103 with EKG 12889 and Prometheus 12899, e2e 7181/13451, compat
7191/13461, app-chain webhook sink 9199, Docker 7281-7283/13551-13553), and the sparse-backfill runner picks free
ports itself. The run stops
before starting if a harness port is busy. It never kills by port: each test records
the PIDs it starts, and only those are stopped, so nodes you run on 7070/13337 are safe.
Every mutable path (chainstate, history, genesis copies, logs) lives in the run
directory; tracked files under `app/config` are not modified.

## Output

```text
qa/results/<run-id>/
  report.html     shareable page: verdict, per-category tables, failure details
  report.md       the same for a PR comment
  results.json    run metadata, per-test status, key log lines
  progress.log    START/END lines as the run goes
  logs/<id>.log   each test's harness output; build-*.log for the build step
  runs/           node, Haskell and suite logs per test
  triage/<id>.md  failure notes added by the release-qa skill (optional)
```

`qa/results/` and the build cache `qa/work/` are gitignored. The build is reused
when it was made from the same commit and the same uncommitted tracked changes;
`--build` forces a rebuild and `--no-build` uses whatever is cached.

Statuses: `PASS`; `KNOWN` (a failure listed in `KNOWN-ISSUES.md`); `FAIL`; `TIMEOUT`
(exceeded the registry limit); `BLOCKED` (not run). The script exits 0 only when every
test is `PASS` or `KNOWN`. The report also marks each test `regressed`, `fixed` or
`new` compared with the previous run that has results.

## Adding a test

1. Write the runner in `qa/harness/` (source `common.sh`; use its ports, `start_yano`,
   `track_pid` and `kill_tracked`; keep mutable paths under `$SP/runs/<name>`).
2. End its output with `VERDICT: PASS` or `VERDICT: FAIL (<short reason>)`. Lines such
   as `  PASS  <check>` / `  FAIL  <check>` are counted in the report.
3. Add a line to `REGISTRY` in `release-qa.sh`: id, category, mode, title, time limit
   in minutes, and the command.
4. Optionally add a `test-*` skill that runs `qa/release-qa.sh --only <id>`.
