# ADR-NET-008A Discovery-First Bootstrap Implementation Report

## Scope

- Allowed discovery-enabled multi-peer upstream modes to satisfy client
  bootstrap requirements without explicit `yano.upstream.peers` or legacy
  `yano.remote.*`.
- Changed upstream peer construction so discovery-first mode does not silently
  fall back to legacy `yano.remote.*` when no explicit peers are configured.
- Refactored `SyncSubsystem` startup so the initial active upstream can be
  selected after synchronous discovery bootstrap.
- Added discovery fallback for the case where a configured active peer fails
  before startup completes.
- Added active-upstream failover cooldown so a recently failed trusted static
  peer does not immediately outrank discovered peers on the next recovery
  rotation.
- Added active-upstream failure memory so failover tries unfailed discovered
  peers before retrying a trusted peer that already failed in the current
  rotation.
- Added immediate retry for startup/dial failures after active-peer failover so
  the next selected peer is attempted in the same serialized recovery executor
  instead of waiting for the supervisor's normal cooldown window.
- Fixed supervisor recovery for stopped active sessions so a failed recovery
  attempt does not leave the node stuck with a stopped peer session after the
  active pointer has moved to another peer.
- Changed the multi-peer chain-selection rollback-window default from a
  hardcoded app value to a runtime value derived from Shelley genesis:
  `ceil(securityParam / activeSlotsCoeff)` slots. Explicit positive
  `yano.upstream.selection.rollback-window-slots` values still override it.
- Added retry behavior when discovery has not produced a usable peer yet.
- Added shutdown cleanup for the discovery-bootstrap retry task.
- Hardened peer-session status collection so a partially initialized peer
  client cannot prevent recovery dispatch.
- Hardened the Haskell/devnet process helper so it copies genesis/network
  assets without inheriting local operator `app/config/application*` overrides.

## Review

- The change is limited to multi-peer modes with
  `yano.upstream.discovery.enabled=true`; trusted-single mode still requires a
  configured peer or legacy remote.
- Peer snapshots are loaded before canonical sync starts, so a snapshot relay
  can become the first active upstream.
- In `p2p-relay`, an invalid or unreachable configured peer can now trigger
  snapshot/peer-sharing discovery and fail over to an eligible discovered peer.
- If the first discovered peer also fails, active-peer selection skips recently
  failed peers while another eligible peer is available.
- If the trusted static peer's cooldown has expired but unfailed discovered
  peers are still available, selection continues through those discovered peers
  before retrying the trusted static peer.
- Startup/dial failover is fast-path recovery. No-progress, keepalive-stale,
  disconnect-stale, and body-fetch-stuck recovery continue to use the supervisor
  cooldown policy.
- If a recovery attempt fails and stops the current peer session, the supervisor
  retries after its normal cooldown instead of ignoring the stopped session.
- The derived rollback window maps the Cardano common-prefix depth `k` from
  blocks to slots. Mainnet/preprod derive `43,200` slots from
  `k=2160, activeSlotsCoeff=0.05`; preview derives `8,640` slots from
  `k=432, activeSlotsCoeff=0.05`; a devnet with `k=100, f=1.0` derives
  `100` slots.
- Candidate-header memory remains bounded by the `16,384` observation cap. With
  three hot peers, two observer peers, and mainnet/preprod genesis values, the
  expected retained observations are about `2 * 2160 = 4,320`, roughly
  `2-4 MiB` on HotSpot for the current record/map shape. The cap bounds the
  worst common case to roughly `8-15 MiB`.
- If operators want a static fallback in `p2p-relay`, they should configure it
  under `yano.upstream.peers` instead of relying on legacy `yano.remote.*`.
- The sync process remains alive while waiting for discovery, avoiding a hard
  process failure when an initial bootstrap peer is absent.
- The Haskell sync harness now uses the jar's `%devnet` profile consistently,
  even when `app/config/application.yml` is set up for manual preprod relay
  testing.

## Verification

- `UpstreamConfigTest.discoveryBootstrapSatisfiesClientRemoteRequirement`
- `UpstreamConfigTest.singleActiveModeStillRequiresRemoteOrPeer`
- `SyncSubsystemTest.p2pRelayCanBootstrapActivePeerFromPeerSnapshotWithoutConfiguredPeers`
- `SyncSubsystemTest.p2pRelayFallsBackToDiscoveredPeerWhenConfiguredPeerFails`
- `SyncSubsystemTest.p2pRelaySkipsRecentlyFailedConfiguredPeerWhenDiscoveredPeerAlsoFails`
- `SyncSubsystemTest.p2pRelayTriesUnfailedDiscoveredPeersBeforeRetryingFailedTrustedPeer`
- `PeerSessionSupervisorTest.stoppedSessionTriggersRecoveryRetryAfterCooldown`
- `SyncSubsystemTest.selectionRollbackWindowDefaultsToGenesisSecurityWindowInSlots`
- `SyncSubsystemTest.explicitSelectionRollbackWindowOverridesGenesisDefault`
- `UpstreamConfigTest.zeroRollbackWindowMeansGenesisDerivedDefault`
- `UpstreamConfigTest.negativeRollbackWindowFailsFast`
- `YanoProducerTest.upstreamSelectionRollbackWindowDefaultsToGenesisDerivedSentinel`
- `YanoProducerTest.upstreamSelectionRollbackWindowOverrideIsHonored`
- `YanoAppProcessTest.devnetPreparationDoesNotCopyOperatorApplicationConfig`
- `./gradlew :runtime:test --rerun-tasks --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.p2pRelayCanBootstrapActivePeerFromPeerSnapshotWithoutConfiguredPeers" --console=plain`
- `./gradlew :runtime:test --rerun-tasks --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.p2pRelayFallsBackToDiscoveredPeerWhenConfiguredPeerFails" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.p2pRelayCanBootstrapActivePeerFromPeerSnapshotWithoutConfiguredPeers" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.trustedFailoverAdvancesActivePeerAfterStartupFailure" --console=plain`
- `./gradlew :runtime:test --rerun-tasks --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest" --tests "com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest" --console=plain`
- `./gradlew :runtime:test --rerun-tasks --console=plain`
- `./gradlew :testkit:test --rerun-tasks --tests "com.bloxbean.cardano.yano.testkit.external.YanoAppProcessTest" --console=plain`
- `./gradlew :app:haskellSyncTest --console=plain`
- `./gradlew :runtime:test --rerun-tasks --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest" --tests "com.bloxbean.cardano.yano.runtime.peer.PeerSessionSupervisorTest" --console=plain`
- `./gradlew :app:quarkusBuild --console=plain`
- `./gradlew :runtime:test --rerun-tasks --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.p2pRelaySkipsRecentlyFailedConfiguredPeerWhenDiscoveredPeerAlsoFails" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.p2pRelayTriesUnfailedDiscoveredPeersBeforeRetryingFailedTrustedPeer" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.p2pRelayFallsBackToDiscoveredPeerWhenConfiguredPeerFails" --console=plain`
- `./gradlew :runtime:test --rerun-tasks --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.selectionRollbackWindowDefaultsToGenesisSecurityWindowInSlots" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.explicitSelectionRollbackWindowOverridesGenesisDefault" --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest.zeroRollbackWindowMeansGenesisDerivedDefault" --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest.negativeRollbackWindowFailsFast" --console=plain`
- `./gradlew :runtime:test --rerun-tasks --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest" --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" :app:test --rerun-tasks --tests "com.bloxbean.cardano.yano.app.YanoProducerTest" --console=plain`
- `git diff --check`
- `./gradlew :app:quarkusBuild --console=plain`

Live preprod check:

- Started from `app` with `java -jar build/yano.jar` and
  `app/config/application.yml`.
- The invalid configured peer failed first:
  `preprod-node1.world.dev.cardano.org:30000`.
- Discovery loaded 32 official preprod snapshot relays.
- Failover moved from `relay.preprod.staging.wingriders.com:3001` to
  `relay.preprod.wingriders.com:3002`, then to `132.226.203.38:6001` instead
  of returning to the invalid configured peer.
- `132.226.203.38:6001` connected successfully, found intersection at slot
  `92921253`, started header/body sync, and processed 129 bodies during the
  validation window.

Haskell sync notes:

- Past-time-travel sync used Yano n2n port `57690` and matched cardano-node at
  slot `4899`.
- Regular block-producer sync used Yano n2n port `57767` and matched
  cardano-node at slot `1201`, crossing the epoch boundary.
