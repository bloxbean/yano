# Known failures

Cases listed here are expected to behave as stated. Anything **not** listed is
expected to `PASS`.

`run-suite.sh` parses the table below, so the format matters:

- one row per case, first column a backticked case id (`stack:case`),
- the **Expected** column must be exactly `PASS` or `FAIL`.

The runner fails the suite in both directions:

- a case expected to `PASS` that fails is a **REGRESSION** — Yano or the SDK broke;
- a case expected to `FAIL` that passes is an **UNEXPECTED-PASS** — the upstream bug
  was fixed. That is good news, but it means this file is stale: delete the row and
  re-run, so the case is protected from then on.

Run `./run-suite.sh --list` to see every case id.

| id | Stack | Case | Expected | Evidence |
| --- | --- | --- | --- | --- |
| `mesh:chained` | MeshJS 1.9.1 | chained transactions reach full depth | FAIL | `MeshTxBuilder.complete()` re-resolves inputs through the provider even when they are supplied explicitly, and Yano's `/txs/{hash}` and `/txs/{hash}/utxos` are canonical-only, so an unconfirmed parent is invisible. Observed `chains.fullDepth = 0` with `byStop` dominated by `{"error":"Transaction not found"}`. CCL and Evolution chain fine against the same node, which is what rules out a Yano-side defect. |
| `evolution:awaitTx` | Evolution SDK 2.0.1 | `lucid.awaitTx()` confirms a transaction | FAIL | The provider polls `GET /txs/{hash}/cbor`, which Yano does not implement. Worse, unknown routes under `/api/v1` fall through to Quarkus's default **HTML** 404 page, so the SDK's polling interval dies on `JSON.parse('<html>…')` — an unhandled `SyntaxError`, not a catchable API error. Two independent fixes: add the route, or add a catch-all JSON error mapper under `/api/v1` (which would help every JS SDK). `evolution:compat` polls `/utxos/{hash}/{index}` instead, so the rest of the provider walk still asserts. |

## Not a known fail, but worth knowing

- **`ccl:load` and the two `*:load` cases never assert.** They emit reports for a human
  to compare between two runs. A load case is marked `RECORDED`, never `OK`.
- **`ccl:plutusProbe` and `ccl:queryOverlay` are exploratory.** They map which Plutus
  flows and which read paths this node supports. Also `RECORDED`.
