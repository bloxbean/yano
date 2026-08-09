# Functional Load, Burst Load, and Soak Runs

The showcase provides three different tests. They answer different questions;
do not compare their rates as if they were the same workload.

## Functional scenario repetition

`load` repeats a complete state-machine scenario sequentially and verifies its
result/proof. Use it to demonstrate correctness under repeated business flows.

```bash
./showcase.sh load orders --count 25 --instance demo
./showcase.sh load document-review --count 5 --instance demo
./showcase.sh load composite --count 10 --instance demo
```

Approval, document-review, and composite iterations intentionally contain several dependent
messages and finality waits. Their iteration rate is not raw message throughput.

## Parallel burst

`load-test` submits exactly `N` messages concurrently, waits for the chain to
drain, and reports API acceptance, `429` backpressure, certified finalization,
and messages per block.

```bash
./showcase.sh load-test orders --count 1000 --concurrency 20 \
  --payload-bytes 128 --spread --instance demo

./showcase.sh load-test registry --count 500 --concurrency 10 \
  --payload-bytes 64 --spread --instance demo
```

`orders` uses opaque ordered-log records. `registry` generates unique,
well-formed KV CBOR PUT commands, so it measures real state updates rather than
rejected random bytes. The wrapper form is equivalent:

```bash
./demos/load-test.sh demo orders --count 1000 --concurrency 20 --spread
```

Use `--node 0` instead of `--spread` to target one ingress node. A burst can
saturate a laptop; start with 100–1000 messages and increase deliberately.

## Sustained soak

`soak-test` holds a target offered rate on the ordered-log chain, samples tips
and pool depth, drains pending work, verifies cross-node root consistency, and
writes a CSV time series. Showcase defaults are presentation-safe: 60 seconds,
25 messages/second, 20 workers, and a five-second sample interval.

```bash
./showcase.sh soak-test orders --duration 60 --rate 25 \
  --concurrency 4 --payload-bytes 128 --sample 5 --spread --instance demo
```

For a longer rehearsal:

```bash
./demos/soak-test.sh demo orders --duration 900 --rate 100 \
  --concurrency 12 --sample 10 --spread
```

Reports default to:

```text
data/showcase/<instance>/reports/soak-<timestamp>/
```

Choose another location with `--report-dir /absolute/or/relative/path`. The
report includes accepted, throttled, and error counts; finalized throughput;
steady pool depth; node tips; and state-root parity. A `429` is controlled
backpressure, not a finalized message. Investigate non-HTTP errors and divergent
roots before treating a throughput number as valid.

These scripts are demo and regression drivers, not a distributed benchmark.
For capacity claims, isolate the host, record CPU/disk/network limits, warm up
the JVM, repeat runs, and use external load generators from separate machines.
