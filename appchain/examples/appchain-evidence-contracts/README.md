# App-chain evidence contracts

This module publishes the canonical ADR-013 evidence v1 command, event, query,
and authenticated-state codecs. It is deliberately a no-SPI library: adding it
to an application provides types and schemas but cannot discover or activate an
app-chain state machine, domain API, or executor.

Its public model uses the frozen connector contract types, so the published API
declares `appchain-integration-contracts` directly. It deliberately has no
`core-api` dependency: `EvidenceTerminalOutcome` freezes evidence-v1 wire
codes 1 through 4, and the executable registry maps Effect Runtime outcomes to
that local enum. The evidence-v1 effect ordinal ceiling is frozen locally for
the same reason.

The structural CDDL and literal vectors are included under:

```text
META-INF/yano/contracts/evidence/v1/
```

The CDDL captures array shapes and directly expressible bounds. ADR-013
section 10.2, the strict Java codecs, and their positive/negative golden tests
jointly define the normative executable contract, including nested connector
canonicality and cross-field state/receipt bindings.

The executable state machine and plugin metadata remain in
`appchain-evidence-registry`; HTTP orchestration and MPF proof verification
remain in `appchain-evidence-client`.

## Build

```bash
./gradlew :appchain-evidence-contracts:check
```

To compile the structural CDDL and validate every published positive vector
with the pinned external validator used for release evidence:

```bash
CDDL_BIN=/path/to/cddl-cli-0.10.5 ./scripts/verify-cddl.sh
```

`check` always runs the Java codec, negative-contract, artifact, and dependency
boundary tests; the external CDDL command is deliberately explicit because the
validator is not a build dependency.
