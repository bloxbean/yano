# Sparse backfill regression scripts

Runner and helpers for the `test-sparse-backfill` skill
(`.claude/skills/test-sparse-backfill/SKILL.md` — read that first). They validate
`yano.block-producer.backfill-block-interval-slots` on a devkit devnet and prove a
downstream Haskell `cardano-node` syncs the resulting history from genesis and keeps
following live blocks.

```bash
./scripts/sparse-backfill/run-sparse-backfill-test.sh            # default devkit matrix
./scripts/sparse-backfill/run-sparse-backfill-test.sh --help
```

| file | role |
|---|---|
| `run-sparse-backfill-test.sh` | driver: case matrix, orchestration, report, exit code |
| `lib/common.sh` | logging, probed ports, tracked PIDs, HTTP/JSON helpers |
| `lib/yano.sh` | isolated Yano devnet process (all paths inside the case directory) |
| `lib/haskell.sh` | per-case `cardano-node` instance (own db, socket, ports, topology) |
| `tools/backfill_expect.py` | mirror of `BackfillPolicy` + `DevnetBlockProducer.nextBackfillSlot`, with the Java test vectors as `--self-test` |
| `tools/prepare_genesis.py` | copy `app/config/network/devnet/pv10/` out and patch Shelley params |
| `tools/verify_chain.py` | Yano-side verification (derived slot sequence, epochs, hashes, speed) |
| `tools/haskell_check.py` | Haskell-side verification (adopted tips, hash match, live follow) |
| `tools/render_report.py` | combined `report.md` |

Related: `scripts/haskell-compatibility/` supplies the shared `cardano-node` binary
(downloaded once); this runner never reuses its database or configuration for a test.

When `DevnetBlockProducer.nextBackfillSlot` or `BackfillPolicy` changes, update
`tools/backfill_expect.py` and its self-test vectors in the same commit — the runner
refuses to proceed when the self-test fails.
