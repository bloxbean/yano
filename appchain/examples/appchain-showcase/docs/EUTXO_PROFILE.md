# EUTxO Profile — Operator Walkthrough

## Table of contents

1. [Ledger variant](#1-ledger-variant)
2. [Bridge variant](#2-bridge-variant)
3. [ZK (validity) variant](#3-zk-validity-variant)
4. [Everyday operations](#4-everyday-operations)
5. [Submitting transactions yourself](#5-submitting-transactions-yourself)
   — more rounds, and the staged deposit → L2 transactions → withdrawal flow
6. [Gotchas](#6-gotchas)
7. [Deeper reading](#7-deeper-reading)

The dedicated EUTxO profile delegates to Yano's maintained demo CLI
(`yano.sh appchain eutxo demo`) and runs three scenarios, each on its own
disposable local devnet. All three are **devnet-only by design** — the facade
rejects a public-network selection, every wallet is generated at setup, and
no real funds are ever involved. (The light profile merely keeps one
out-of-box `eutxo-ledger` chain visible; this profile is the full round-trip
experience.)

| Variant | What it demonstrates | Typical time |
|---|---|---|
| `ledger` | Experimental virtual EUTxO ledger: signed L2 payment, ACCEPTED receipt, UTxO snapshot, verified MPF proof | setup+up ~2-3 min, round-trip seconds |
| `bridge` | Cardano custody/federation boundary: real devnet L1 deposits into a native-script vault, mirrored L2 spends, withdrawal claim, operator payout, stability-gated confirmation | ~1-3 min per round after up |
| `zk` | Maintained validity flow: Jubjub-signed L2 envelopes, Groth16 batch proof (`cardano-payment-b16`), then the bridge settle/verify | ceremony ~2 min, ~2-4 min per round |

The showcase maps `prepare`→`setup`, `run`→`round-trip`, and passes
`--nodes`, `--http-base`, `--server-base` through. Node 0 runs the L1 devnet
block producer; the other nodes follow it. Each variant keeps an isolated
workspace under `data/showcase/<instance>/eutxo-<variant>/`.

## 1. Ledger variant

```bash
./showcase.sh quickstart --profile eutxo --variant ledger --instance ledger \
  --http-base 27170 --server-base 23470
```

What happens: workspace + disposable wallets are generated, a project is
rendered and validated, the nodes start (watch `EUTXO_LEDGER_CLUSTER_STARTED`
with per-node index status), then the round-trip runs: Alice's 100 ADA
virtual genesis is spent as a zero-fee signed payment → 25 ADA to the
recipient, 75 ADA change. The run ends with
`EUTXO_LEDGER_DEMO_ROUND_TRIP_PASS` showing the transaction id, app height,
state root, ACCEPTED receipt code, the created outpoint, and the verified
proof root.

Then explore:

```bash
# browser lifecycle explorer (URL is also printed by the CLI):
open http://127.0.0.1:27170/ui/app-chain/eutxo/?chain=payments-eutxo

# pure re-verification (read-only for this variant; see §4 for why the
# profile/variant/port flags must always be repeated):
./showcase.sh verify all --profile eutxo --variant ledger --instance ledger \
  --http-base 27170 --server-base 23470
```

Artifacts: `.../eutxo-ledger/artifacts/l2/ledger-transfer.cbor` (the exact
signed transaction) and `artifacts/reports/ledger.json`.

## 2. Bridge variant

```bash
./showcase.sh quickstart --profile eutxo --variant bridge --instance bridge \
  --http-base 27180 --server-base 23480
```

The round-trip drives five journaled steps per round — fund, deposit,
transfer, settle, verify:

1. **fund** — the devnet faucet funds Alice, Bob, and the operator (100 ADA
   each) on the local L1.
2. **deposit** — Alice (20 ADA) and Bob (10 ADA) each pay into the **vault**:
   a native-script address controlled only by the operator key, with a datum
   binding their L2 identity. The mirrored L2 deposits appear after L1
   stability depth 2.
3. **transfer** — Alice signs an L2 spend 20 → [Alice 10, Bob 10]; Bob then
   spends 10 → [Bob 7, payout 3] where the 3 ADA output carries a withdrawal
   datum. That creates an irrevocable L2 withdrawal claim.
4. **settle** — only the operator can unlock the vault: it pays 3 ADA to
   Bob's separate L1 payout address and returns the change to the vault.
5. **verify** — the claim must reconcile to `CONFIRMED` once the L1 payout is
   observed at stability depth.

The custody boundary is the point: users sign L2 spends, but L1 funds move
only through the federation operator's script — and the demo labels itself
honestly (`federated disposable-devnet native-script custody; no validity
proof`). Re-running the round-trip is a journaled **no-op** once a round is
STABLE — new activity needs a higher round count (see §5 for copy-paste
commands).

Note: for this variant `verify` is **not** read-only — it resumes the
journaled pipeline and completes any unfinished step.

## 3. ZK (validity) variant

```bash
./showcase.sh quickstart --profile eutxo --variant zk --instance zk \
  --http-base 27190 --server-base 23490
```

`quickstart` runs the one-time **ceremony** automatically between `up` and
the round-trip (for an existing instance: `./showcase.sh ceremony
--profile eutxo --variant zk --instance zk --http-base 27190
--server-base 23490`). The ceremony is ZeroJ's explicitly insecure single-participant
development trusted setup (~2 min) and writes the proving/verification keys
under the workspace's `project/runtime/validity/`.

The round-trip then: deposits as in the bridge variant (with each user's
Jubjub session key bound into the vault datum) → Alice signs a
**Jubjub-authorized L2 envelope** 20 → [10, 10] and Bob a withdrawal envelope
→ both finalized transitions are proved as **one Groth16 batch**
(`cardano-payment-b16`, ~15 s proving, 192-byte proof) and verified locally →
the bridge settle/verify completes the payout. The pass line reports
`proofSystem=groth16`, the batch profile, and the full journal.

Honest scope: the demo's payout still goes through the native-script vault,
not the reviewed proof-withdrawal validator; the trust line printed is
`trusted-prover disposable-devnet; demo-native vault payout`.

## 4. Everyday operations

Unlike the light profile, the EUTxO profile keeps **no showcase identity
marker** — so every command must repeat the profile, variant, instance, and
(non-default) port flags; a bare `--instance` call would fall through to the
light-profile path:

```bash
FLAGS="--profile eutxo --variant ledger --instance ledger --http-base 27170 --server-base 23470"
./showcase.sh status $FLAGS       # node health + index + journal (+ zk lifecycle stage)
./showcase.sh stop   $FLAGS       # SIGTERM nodes; ALL state/artifacts survive
./showcase.sh up     $FLAGS       # resume from the same workspace
./showcase.sh verify all $FLAGS
./showcase.sh run eutxo $FLAGS    # another round-trip
```

**Stop** (nodes down; chainstate, journal, artifacts, and secrets all
survive — `up` resumes exactly where you left off):

```bash
./showcase.sh stop --profile eutxo --variant bridge --instance bridge \
  --http-base 27180 --server-base 23480
```

**Full teardown and recreate.** `reset --yes` stops the nodes and then
deletes the whole workspace (journal, rounds, wallets, chainstate —
everything). After it, the same quickstart recreates the scenario from
scratch with fresh keys and a fresh round 1:

```bash
yano/yano.sh appchain eutxo demo reset --scenario bridge \
  --workspace data/showcase/bridge/eutxo-bridge --yes

./showcase.sh quickstart --profile eutxo --variant bridge --instance bridge \
  --http-base 27180 --server-base 23480
```

(Same pattern for the other variants — adjust `--variant`, the workspace
path `eutxo-ledger`/`eutxo-zk`, and the port bases. Do not use the showcase's
generic `reset` for eutxo instances: without an identity marker it takes the
light-profile path and deletes the directory without stopping the demo
nodes.)

Workspace map (under `data/showcase/<instance>/eutxo-<variant>/`):

```
project/logs/node<i>.log            node logs (first stop for failures)
project/data/node<i>/chainstate                 authoritative L1 state
project/data/node<i>/appchain-chainstate        authoritative app-chain state
project/data/node<i>/appchain-indexers/...      rebuildable eutxo-lifecycle.db
project/runtime/validity/           zk ceremony keys, lifecycle state, proofs
runtime/journal/operations.json     the resumable step journal
artifacts/{l1,l2,proofs,reports}/   retained transactions, transitions, reports
secrets/                            generated demo keys (0600, git-ignored)
```

## 5. Submitting transactions yourself

All commands below assume the §2 quickstart (instance `bridge`, ports
27180/23480) and are complete — copy and paste from the extract root.

**Run more rounds.** Rounds are journaled: once round N is STABLE, re-running
it is a no-op. New activity = a higher `--count` (max 16). Round 1 ran during
quickstart, so:

```bash
# round 2 (full fund → deposit → transfer → settle → verify cycle):
./showcase.sh run eutxo --profile eutxo --variant bridge --instance bridge \
  --http-base 27180 --server-base 23480 --count 2

# later, round 3, round 4, ...:
./showcase.sh run eutxo --profile eutxo --variant bridge --instance bridge \
  --http-base 27180 --server-base 23480 --count 3
```

**Staged flow: deposits first, then the L2 transactions, then the
withdrawal.** Requesting a named step runs the journaled pipeline *up to*
that step per round, so you can pause between stages and inspect the console
after each one. (This exact sequence is live-verified; `--count 3` means
"rounds 1..3" — already-complete rounds skip, so with rounds 1-2 done only
round 3 executes.)

```bash
# 1. Deposits only: faucet-fund the demo users, both deposit into the vault,
#    wait for the mirrored L2 deposits (~l1/bridge-deposits messages):
yano/yano.sh appchain eutxo demo deposit --scenario bridge \
  --workspace data/showcase/bridge/eutxo-bridge --members 3 \
  --http-port-base 27180 --server-port-base 23480 --count 3

# 2. L2 transactions: Alice's payment 20 → [Alice 10, Bob 10], then Bob's
#    spend 10 → [Bob 7, payout 3] which creates the withdrawal claim
#    (two eutxo.transactions messages per round):
yano/yano.sh appchain eutxo demo transfer --scenario bridge \
  --workspace data/showcase/bridge/eutxo-bridge --members 3 \
  --http-port-base 27180 --server-port-base 23480 --count 3

# 3. Withdrawal: the operator unlocks the vault and pays Bob's L1 payout
#    address, then the claim must reconcile to CONFIRMED:
yano/yano.sh appchain eutxo demo settle --scenario bridge \
  --workspace data/showcase/bridge/eutxo-bridge --members 3 \
  --http-port-base 27180 --server-port-base 23480 --count 3
yano/yano.sh appchain eutxo demo verify --scenario bridge \
  --workspace data/showcase/bridge/eutxo-bridge --members 3 \
  --http-port-base 27180 --server-port-base 23480 --count 3

# journal state + retained artifacts any time:
yano/yano.sh appchain eutxo demo status --scenario bridge \
  --workspace data/showcase/bridge/eutxo-bridge --members 3 \
  --http-port-base 27180 --server-port-base 23480
```

Want *more* L2 transactions between deposit and withdrawal? Each round
contributes two, so run stage 1 and stage 2 with a higher `--count` (e.g.
`--count 6` = 12 L2 transactions across six rounds) before settling them all
in stage 3. Steps are idempotent — you can stop mid-flow and resume, and the
retained signed transactions land under `artifacts/l2/` for inspection.

**External depositor** (bring your own L1 wallet instead of the generated
demo users): build an unsigned vault deposit, sign it with your own tooling,
submit it back:

```bash
$CLI deposit-build  $ARGS --address <your addr_test...> --amount 20000000 \
  --output my-deposit.cbor
# sign my-deposit.cbor with your wallet/CLI, then:
$CLI deposit-submit $ARGS --signed-transaction my-deposit-signed.cbor
```

The node-side builder accepts either a pure-ADA input or a wallet input that
also contains native assets. It returns all native assets as change while the
vault deposit itself remains ADA-only. Signing stays in the external wallet.

For the ledger variant the write operation is `transfer` (alias of the
round-trip: one signed virtual payment) and `verify`/`reconcile` re-checks
the retained proof — there is no faucet or vault involved.

## 6. Gotchas

- **`setup` is not idempotent** — a non-empty workspace is refused. To start
  over: `yano/yano.sh appchain eutxo demo reset --workspace <dir> --yes`.
  Everything *within* a workspace is journal-guarded and resumable; a second
  concurrent operation is rejected by the workspace lock.
- Ports: exactly `--nodes` consecutive ports from each base. Pick distinct
  bases per variant (as above) to run them side by side.
- The chain id is `payments-eutxo` for all variants — isolation comes from
  the workspace and ports, not the chain id.
- Startup failures print `CLUSTER_START_FAILED: YANO_STARTUP_FAILURE
  code=...` extracted from the node log; the log path is the first place to
  look.
- All index replicas must agree (`lagBlocks == 0`, identical digests) before
  operations proceed — a mismatch fails fast rather than demoing stale data.

## 7. Deeper reading

- `appchain/extensions/eutxo/DEMO.md` — the maintained CLI's own quick start.
- `appchain/extensions/eutxo/README.md`, `INDEXER_OPERATIONS.md` — ledger
  family and lifecycle-index operations.
- `appchain/extensions/eutxo-zk/GETTING_STARTED.md`,
  `DEVNET_WALKTHROUGH.md`, `D3_BATCH_PROFILES.md` — the manual ZK lifecycle,
  full narrative, and measured proving costs.
- `adr/app-layer/utxo/002-unified-eutxo-demo-experience.md` — the design
  behind this CLI (workspace layout, amounts, output contract).
