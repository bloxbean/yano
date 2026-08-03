# Authenticated map

`authenticated-map` is the versioned ADR-025 map state machine. It is separate
from `kv-registry`; existing registry command bytes and roots are unchanged.

Phase 1 uses `mpf-blake2b256-v1`. Construct canonical genesis from the exact
`AppChainConfig` used by the node and attach the returned setting:

```java
var genesis = AuthenticatedMapGenesisFactory.mpf(
        config,
        anchorPolicyCommitment,
        128,
        65_536,
        collections,
        initialEntries);

var settings = AuthenticatedMapGenesisFactory.settings(genesis);
```

The provider rejects a chain-id, framework-consensus digest, initial membership
commitment, profile fingerprint, or retained genesis marker mismatch. There is
no node-local collection default or profile alias.

Use `StdlibAppChainClient.authenticatedMapMutate(...)` or
`authenticatedMapBatch(...)` to submit canonical commands. Point and receipt
queries use `AuthenticatedMapContract.PointResult` and `ReceiptResult`; a
revoked entry is reported as `REVOKED`, not absent.

Collections may independently use opaque values, canonical CBOR, one compiled
declarative schema, or one pinned custom validator. The explicit default remains
opaque with no validator. Use `AuthenticatedMapPreflight` or the `appchain state
validate` command for advisory checks, and see the
[validation user guide](../../docs/appchain/state-machines/authenticated-map-validation.md)
for blueprint examples, CLI inspection, plugin trust requirements, and error
codes. The state machine always repeats the check authoritatively during apply.

With a configured node running, the small curl demo submits a PUT and prints
the point-query, MPF-proof, and tip envelopes:

```bash
appchain/scripts/authenticated-map-demo.sh \
  product-registry products sku-1 metadata-v1
```

The proof is only meaningful against a root acquired through an independently
trusted finality, snapshot, or L1-anchor path. Treating the same node's root as
trusted merely because it accompanied that node's proof is circular.
