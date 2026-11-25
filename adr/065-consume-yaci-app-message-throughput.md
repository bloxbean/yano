# ADR-065: Consume Yaci app-message throughput fixes without a Host API change

- Status: Proposed
- Date: 2026-11-25
- Depends on: Yaci ADR 0019 (app-message submission throughput and priority)
- Downstream: Yano X ADR-060 (app-message gossip throughput and consensus priority)
- Risk: **low**. This is a dependency bump. It changes no Yano code, Host API, wire format or persisted state.

## Context

Yano X app ledgers gossip app messages, including their consensus traffic, over Yaci's protocol-100
mini-protocol (`AppMsgSubmission`). A Yano X throughput study on Yano 0.1.0-pre22 found that protocol 100
limited a chain to about 2,500 msg/s and lost messages under load:
- the window was fixed at 10;
- a missing body desynchronized the FIFO acknowledgements;
- the client queue scanned linearly;
- consensus messages shared the ordinary queue and were rejected when it was full.

Yaci ADR 0019 fixes these in Yaci. The question for Yano is what Yano itself must change.

Yano does not configure protocol 100:
- Host API 1.2 `N2NExtensionRegistry` takes Yaci `AgentFactory` instances (`registerInbound`) and
  decorates the outbound `PeerClientFactory` (`decorateOutbound`).
- The plugin, Yano X, builds the `AppMsgSubmissionConfig` and the agents. Yano passes them through.
- `ConnectionTrackingPeerClientFactory.enableAppMsg(config)` forwards the config to Yaci unchanged.

Separately, the `batchSize(10)` in `ServeSubsystem` belongs to the Cardano `TxSubmissionConfig`. It is not
app-message gossip and is not changed here.

## Decision

### D1. Bump Yaci to the release that carries ADR 0019

Yano takes the Yaci release with ADR 0019 and changes nothing else.

The Yaci changes are additive and binary compatible for Yano and its plugins:
- the new `AppMsgSubmissionConfig` fields have defaults equal to today's behaviour, for example a window
  of 10 and a pending capacity of 1,000;
- `AppMsgValidator.validateAll` is a default method;
- the existing agent constructors stay.

A plugin built against the older Yaci therefore runs unchanged on this Yano and keeps today's rate. Yano X
opts into the larger window and the priority lane through the config it already supplies.

### D2. No Host API or plugin-catalog change

The Host API keeps exposing Yaci's protocol-100 types as it does today. No `HostApiVersion` change is
needed: no Yano interface or catalog contract changes.

## Consequences

- Yano X can raise its gossip rate and protect consensus traffic without a Yano code change.
- Yano's own build and tests run on the new Yaci. Its protocol-100 users are a test registry, a
  conformance fixture and the forwarding peer-client factory.
- Until Yaci publishes the release, the branch pins a locally published Yaci snapshot and must be
  re-pinned before merge.

## Validation

- Yano `test` passes on the Yaci snapshot.
- Yano X's full build and its throughput scenarios ran on a Yano built from this branch (Yano X
  `perf-test/RESULTS-2026-11-25-gossip.md`).
