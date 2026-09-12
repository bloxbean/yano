---
title: "Historical archive"
description: "Enable optional DuckLake history projection on the JVM."
sidebar:
  order: 6
---

The optional history archive keeps historical projections outside authoritative block application. Yano writes a canonical projection outbox in RocksDB and drains eligible data into **DuckLake**, which stores table data in Parquet.

## Enable at fresh sync

From a JVM distribution, with empty node storage:

```bash
./yano.sh start:preprod,projection
```

History is disabled by default and unsupported by the native distribution. It needs a fresh sync from genesis; enabling it on an already populated ordinary node does not backfill the missing past.

The `wallet` profile enables wallet indexes and **does not enable this archive**. Use `projection` when you want archival history.

## Select data deliberately

Block sections include transactions, UTxO history, account events, and address transactions. Section selection is part of archive identity and cannot be changed retroactively on a populated archive. Epoch artifacts include rewards, epoch stake, DRep distribution, Ada pots, and governance proposal status; newly enrolled epoch artifacts are prospective.

DuckLake uses a SQLite metadata catalog, a separate rebuildable transaction locator, and Parquet data. SQLite is not an alternative history engine. Archive progress trails chain tip according to its finality gate.

## Operate within disk bounds

The packaged `projection` profile includes drain cadence, maintenance, retention, and disk soft/hard/free-space thresholds. Read the profile values in the [configuration catalog](/reference/configuration-catalog/) and inspect `/q/openapi-history` for coverage and maintenance APIs.

Archive coverage is not the same as live ledger coverage. Inspect the recorded archive range and progress before treating a history query as complete.
