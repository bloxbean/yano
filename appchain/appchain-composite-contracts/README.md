# App-chain composite contracts

This lightweight artifact contains the public, no-SPI contracts required to
submit to or verify a composite chain without loading Yano state-machine or
plugin implementations. It intentionally contributes no plugin manifest or
`ServiceLoader` entry.

Public v1 surfaces are:

- `CompositeCommitmentV1` — profile marker key, 64 KiB profile bound,
  domain-separated profile digest, and component-local to physical MPF key
  mapping;
- `AggregateQueryCodecV1` and `AggregateQueryLimitsV1` — the one canonical,
  bounded `composite/aggregate-v1` request/response wire used by both clients
  and runtime; and
- `EvidenceReleaseCommandV1` and prerequisite encoders — frozen stock workflow
  submission contracts for approved `SUBMIT` and `REPUBLISH` commands;
  `NOTIFY` remains on its dedicated gated route.

Example aggregate request:

```java
byte[] request = AggregateQueryCodecV1.encodeRequest(List.of(
        new AggregateQueryCodecV1.Subquery(
                "evidence", "get", evidenceGetRequest)),
        AggregateQueryLimitsV1.DEFAULT);
```

Example proof key and profile trust root:

```java
byte[] markerKey = CompositeCommitmentV1.profileMarkerKey();
byte[] profileDigest = CompositeCommitmentV1.profileDigest(canonicalProfile);
byte[] recordProofKey = CompositeCommitmentV1.componentKey(
        "evidence", evidenceRecordKey);
```

The digest proves which canonical profile was committed; it does not by itself
establish that the root is finalized. Bind the proof root and height to a
trusted threshold finality certificate or Cardano L1 anchor.

```bash
./gradlew :appchain-composite-contracts:check
```
