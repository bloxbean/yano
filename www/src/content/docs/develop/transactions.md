---
title: "Query and submit transactions"
description: "Use the REST API with your existing Cardano transaction library."
sidebar:
  order: 1
---

Yano supplies chain queries, transaction submission, and script evaluation. Your application or Cardano library owns address generation, keys, transaction construction, and signing.

## Query spendable outputs

Replace `<address>` with a real address on your selected network:

```bash
curl -fsS 'http://localhost:7070/api/v1/addresses/<address>/utxos'
curl -fsS http://localhost:7070/api/v1/epochs/latest/parameters
```

Preserve lovelace and native-asset quantities without floating-point rounding. Build and sign using the returned UTxOs and protocol parameters.

## Submit signed CBOR

```bash
curl -fsS -X POST http://localhost:7070/api/v1/tx/submit \
  -H 'Content-Type: application/cbor' \
  --data-binary @tx.cbor
```

Hex-encoded CBOR is also accepted with `Content-Type: text/plain`. A submission response does not prove inclusion. Query or await confirmation and handle rollback in your application.

## Evaluation

The evaluation route is `/api/v1/utils/txs/evaluate`. Use the running node's [OpenAPI document](/reference/http-api/) for its accepted request/response shapes. The launcher selects Aiken for the JVM and Scalus for native execution; `yano.block-producer.script-evaluator` can override the choice where supported. Aiken's JNA evaluator is not supported by native image.

## Integration choices

- Java: use Cardano Client Lib; [the CCL testkit adapter](/develop/ccl/) provides an in-process backend for tests.
- JavaScript and TypeScript: use the [JavaScript testkit](/develop/javascript-testkit/) alongside your transaction library.
- Indexers: consume N2N blocks on the configured server port and handle rollbacks.

Some REST shapes are Blockfrost-compatible. This does not mean every Blockfrost API is implemented. Inspect the endpoints and schemas of the artifact you run.
