# ADR-NET-008H Implementation Report: Phases 0-4

## Date

2026-07-01

## Scope

Implemented ADR-NET-008H phases 0 through 4:

- Phase 0: added the `:p2p` Gradle module and no-runtime dependency guard;
- Phase 1: moved connection manager and peer primitives;
- Phase 2: moved peer governor, peer store, and address policy;
- Phase 3: moved Yaci peer discovery;
- Phase 4: moved relay peer-sharing provider.

Runtime still owns `RuntimeNode`, `SyncSubsystem`, `PeerSession`,
`PeerSessionSupervisor`, `ServeSubsystem`, transaction admission, chain apply,
storage integration, and candidate-header chain-selection execution.

## Module Shape

New module:

```text
:p2p
```

Main packages:

```text
com.bloxbean.cardano.yano.p2p.connection
com.bloxbean.cardano.yano.p2p.peer
com.bloxbean.cardano.yano.p2p.governor
com.bloxbean.cardano.yano.p2p.discovery
com.bloxbean.cardano.yano.p2p.peersharing
```

Dependency direction:

```text
runtime -> p2p -> core-api
```

`p2p` depends on Yaci, Jackson, SLF4J, and Netty as allowed by the ADR. Runtime
depends on `p2p` through `api project(':p2p')`.

## Phase Details

### Phase 0

Added:

- `p2p/build.gradle`;
- `include 'p2p'` in `settings.gradle`;
- ArchUnit core test dependency;
- `P2pArchitectureTest`, which asserts no `p2p` class imports
  `com.bloxbean.cardano.yano.runtime..`.

ArchUnit is used as a normal JUnit test through `archunit` core, not
`archunit-junit5`, to avoid conflicting with the repository's pinned JUnit
Platform version.

### Phase 1

Moved to `p2p.connection`:

- connection identity/state records;
- default relay connection manager;
- connection listener/event/snapshot types;
- connection-tracking peer-client factory.

Moved to `p2p.peer`:

- endpoint and peer-client factory types;
- local-bind resolver;
- peer failure/health/recovery primitives;
- peer session status/state snapshots.

`PeerSession`, `PeerSessionSupervisor`, and their runtime tests stayed in
`runtime` and now import the moved `p2p.peer` primitives.

### Phase 2

Moved to `p2p.governor`:

- peer governor;
- peer descriptor/source/state/use models;
- peer store interfaces and file/in-memory implementations;
- source-aware peer address policy.

Added focused `PeerGovernorTest` in `:p2p`.

The existing mixed `MultiPeerScaffoldingTest` remains in `runtime` as a
runtime/candidate-header integration guard and imports the moved governor
types.

### Phase 3

Moved `YaciPeerDiscoveryService` to `p2p.discovery`.

Added focused `YaciPeerDiscoveryServiceTest` in `:p2p` for:

- topology file ingestion;
- peer snapshot ingestion;
- peer snapshot NetworkMagic rejection.

Runtime still owns when discovery is started and how discovered peers are fed
into the runtime sync/governor flow.

### Phase 4

Moved `RelayPeerSharingProvider` to `p2p.peersharing`.

The provider is now a public `p2p` type with public constructors and public
`peers(int)` method so `ServeSubsystem` can inject it back into the Yaci
`PeerSharingServerAgent`.

Moved `RelayPeerSharingProviderTest` to `:p2p`.

## Verification

Phase-level verification:

```text
./gradlew :p2p:compileJava :p2p:test
./gradlew :p2p:test :runtime:compileJava :runtime:compileTestJava
./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.peer.PeerSessionHealthTest' --tests 'com.bloxbean.cardano.yano.runtime.peer.PeerSessionSupervisorTest'
./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest'
./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.server.ServeSubsystemTest'
```

Final verification:

```text
./gradlew :p2p:test :runtime:test
./gradlew :app:quarkusBuild
```

Both final commands completed successfully.

Static checks:

- no production code in `:p2p` imports `com.bloxbean.cardano.yano.runtime..`;
- stale runtime package references for moved types were removed from source
  code;
- remaining occurrences of historical runtime package names are in ADR/report
  documentation only.

## Notes

No runtime sync behavior was intentionally changed. This was a module/package
extraction with focused visibility changes for the peer-sharing provider.

`PeerSession`, candidate-header chain-selection, transaction diffusion, and
runtime server orchestration remain intentionally in `runtime` for later phases
or later ADRs.
