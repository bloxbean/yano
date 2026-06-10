# Devnet AdaPot Comparison

Manual regression harness for the genesis staking AdaPot case:

```text
Yano block producer -> Haskell cardano-node -> yaci-store
```

The script starts all three products on a short-epoch devnet, waits until all
three are past the target epoch, then compares deposits, treasury, and reserves
using Haskell ledger state as the reference.

## Prerequisites

- `jq`, `curl`, `unzip`, and `java` on `PATH`
- Yano built at `app/build/yano.jar`, or run with `BUILD_YANO=1`
- yaci-store bootJar built at
  `applications/all/build/libs/yaci-store-*.jar`, or run with
  `BUILD_YACI_STORE=1`
- Haskell `cardano-node` and `cardano-cli` installed by:

```bash
bash scripts/haskell-compatibility/setup-haskell-test-node.sh
```

or run this harness with `SETUP_HASKELL=1`.

## Run

From the Yano repository:

```bash
bash scripts/devnet-adapot-comparison/run-devnet-haskell-yacistore-adapot-comparison.sh
```

Build everything first:

```bash
BUILD_YANO=1 \
BUILD_YACI_STORE=1 \
SETUP_HASKELL=1 \
bash scripts/devnet-adapot-comparison/run-devnet-haskell-yacistore-adapot-comparison.sh
```

Use non-default repo locations:

```bash
YANO_REPO=/path/to/yano \
YACI_STORE_REPO=/path/to/yaci-store \
bash scripts/devnet-adapot-comparison/run-devnet-haskell-yacistore-adapot-comparison.sh
```

## Common Configuration

```bash
TARGET_EPOCH=32
TEST_DIR=/tmp/yano-devnet-adapot-comparison
YANO_HTTP_PORT=7070
YANO_N2N_PORT=13337
HASKELL_PORT=3002
YACI_STORE_PORT=8081
KEEP_RUNNING=0
CLEAN_PORTS=1
```

For yaci-store, Java 21 is recommended:

```bash
JAVA21_HOME=$(/usr/libexec/java_home -v 21) \
BUILD_YACI_STORE=1 \
bash scripts/devnet-adapot-comparison/run-devnet-haskell-yacistore-adapot-comparison.sh
```

For Yano, use `YANO_JAVA_HOME` if your shell default Java is not the one you
want to use.

## Fixtures

The PV10-compatible devnet fixture is checked in under:

```text
scripts/devnet-adapot-comparison/fixtures/pv10/
```

The script copies that fixture into `TEST_DIR`, then patches only the copy:

- `epochLength=60`
- `securityParam=5`
- first genesis pool `margin=0.2`

The checked-in fixture files are not modified.

## Output

A passing run prints values like:

```text
COMPARISON_EPOCH=32
HASKELL deposits=502000000 treasury=... reserves=...
YANO    deposits=502000000 treasury=... reserves=...
STORE   deposits=502000000 treasury=... reserves=...
PASS deposits all == 502000000
PASS treasury all == ...
PASS reserves all == ...
PASS genesis deposits == 502000000
```

Logs are written under:

```text
$TEST_DIR/logs/
```
