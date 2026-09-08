# Transactional UTxO index plugin

Build with `./gradlew :utxo-output-index-example:jar` from the repository root.
This demonstration module builds a standalone plugin JAR; it is excluded from
Yano's library publishing and signing tasks.
Copy the JAR from this module's `build/libs/` into the configured JVM plugin
directory, allow the bundle through the usual plugin policy, and restart with:

```yaml
yano:
  utxo:
    enabled: true
    index-contributors:
      - type: example.output-index
        enabled: true
        config: {}
```

Use a fresh sync database for complete history. Discovery alone does not enable
the index. The core API is supplied by Yano; do not bundle it or RocksDB in this
JAR. Native deployments must include the provider at build time.

The contributor stores effective created addresses by block/transaction/output.
Rollback deletes facts above the canonical target in the owner's transaction;
the host separately maintains availability and its undo. This example has no
HTTP endpoint, background worker, or historical backfill. It demonstrates the
extension point without changing DefaultUtxoStore.

Reads through `UtxoIndexContext.read` are committed-only, namespace-scoped and
short-lived. Check `available()` before serving authoritative results. For mutable
aggregates, collect repeated-key updates locally and persist your own prior-value
undo in the same batch; the host does not infer feature undo.
