---
name: release-qa
description: Release QA orchestrator. Builds Yano once, runs the regression tests one at a time (L1 devnet, Haskell sync, past time travel, sparse backfill; app chain; e2e endpoints; SDK compatibility; Docker Compose distribution), triages failures, and publishes a shareable report. Arguments are passed to qa/release-qa.sh, for example --quick, --categories l1,e2e, --only haskell-sync-jvm, --build.
---

# Release QA

Runs the release regression suite through `qa/release-qa.sh`, then turns the results
into a shareable report. The individual test skills (`test-haskell-sync`,
`test-app-chain-cluster` and the rest) run single tests through the same script.

## Categories

| Category | Tests (ids from `qa/release-qa.sh --list`) | Time |
|---|---|---|
| `l1` | `epoch-crossing-{jvm,native}`, `haskell-sync-{jvm,native}`, `past-time-travel-{jvm,native}`, `sparse-backfill` | ~60-90 min |
| `appchain` | `appchain-cluster`, `appchain-extensions`, `appchain-rotation-governance`, `appchain-script-anchor` | ~20-30 min |
| `e2e` | `e2e-{jvm,native}`: endpoint smoke + devnet functional + time travel | ~15-20 min |
| `compat` | `compat-{jvm,native}`: CCL, Mesh and Evolution compat cases on a fresh node | ~20-30 min |
| `load` (opt-in) | `load-jvm`: SDK load and chained transactions | ~30-60 min |
| `docker` | `docker-dist`: Compose bundle on devnet (launcher, mounts, API, snapshots, profiles, ownership, upgrade) | ~10-15 min |
| `docker-public` (opt-in) | `docker-public`: Compose bundle syncing preprod, preview and mainnet a few epochs | ~45-60 min |

Default is `l1,appchain,e2e,compat,docker`, roughly 1 hour on the JVM and more with the native build. `--quick`
skips native variants (well under an hour for `--quick --categories l1,e2e`).

## Steps

1. **Pre-flight.** Confirm no other run is active: `pgrep -f qa/release-qa.sh` must be
   empty. Note `git status --short` so the report context is clear (uncommitted
   tracked changes are built and flagged in the report). Tell the user which
   categories will run and the expected time.
   - Native builds need GraalVM: the script uses `$QA_GRAALVM_HOME`, else `$GRAALVM_HOME`.
     If `JAVA_HOME` is a Liberica JDK, the build selects the parallel GC, so point
     `QA_GRAALVM_HOME` at a Liberica NIK rather than Oracle GraalVM.
2. **Launch** in the background (it takes hours; the Bash tool is foreground-limited):

   ```bash
   qa/release-qa.sh <args>        # run_in_background: true
   ```

   The run directory is the newest one under `qa/results/`. Progress lines go to
   `qa/results/<run-id>/progress.log` (`START`, `END <id> <status> <time>`). Do not poll
   in a loop; wait for the completion notification, and read `progress.log` only if the
   user asks for progress.
3. **If it exits 3**, pre-flight failed (missing tool or busy harness port). Report
   the message and stop. Never kill a process you did not start, even if it holds a
   harness port.
4. **Triage failures.** Read `qa/results/<run-id>/results.json`. For every test whose
   status is `FAIL` or `TIMEOUT`, launch one Agent per failure, in parallel, read-only,
   with this brief:
   - the test id, its harness script (`qa/harness/*.sh`, or
     `scripts/sparse-backfill/run-sparse-backfill-test.sh`), the test log
     `qa/results/<run-id>/logs/<id>.log`, and the node logs under
     `qa/results/<run-id>/runs/`;
   - find the first real failure, not the last symptom; cite log lines with timestamps;
   - classify it as product regression, environment, harness bug or flaky, and say
     whether it matches an entry in `qa/KNOWN-ISSUES.md` or
     `compat-tests/KNOWN-FAILS.md`;
   - answer in at most 8 lines of plain text; do not modify files or run nodes.

   Write each answer to `qa/results/<run-id>/triage/<id>.md`. `BLOCKED` tests need no
   triage; their reason is in the report.
5. **Re-render** with the triage notes: `qa/release-qa.sh --render qa/results/<run-id>`.
6. **Publish.** Read `qa/results/<run-id>/report.html` in full, then publish it with the
   Artifact tool (`file_path` = that file, `icon: "checklist"`, description such as
   "Release QA for <commit> on <branch>: <n> of <m> tests passed."). Each run is a new
   artifact.
7. **Reply** with the bottom line (all passed, or how many need attention), a short
   table of the non-passing tests with their one-line cause, the artifact link, the
   local run directory, and the re-run command printed at the end of `report.md`.

## Rules

- One run at a time. The harness uses its own ports (7171/7172, 13441/13442, 3103,
  7181, 7191, and the Haskell EKG/Prometheus ports), kills only PIDs it started, and
  never touches nodes on 7070/13337.
- Report failures as they are. Do not edit the harness, the tests, `KNOWN-ISSUES.md` or
  `KNOWN-FAILS.md` during a run to make something pass. Suggest a known-issue row when
  a failure has a tracked issue, and add it only when the user agrees.
- Results (`qa/results/`) and the build cache (`qa/work/`) are gitignored. Do not commit them.
- Status meanings: `PASS`; `KNOWN` (failed, matches `qa/KNOWN-ISSUES.md`); `FAIL`;
  `TIMEOUT` (exceeded the registry limit); `BLOCKED` (not run: build failed, native
  binary or cardano-node missing, or a harness port busy). The script exits 0 only
  when every test is `PASS` or `KNOWN`.

See `qa/README.md` for the layout, adding a test, and the report format.
