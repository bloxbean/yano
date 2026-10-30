# ADR-061: Ledger-Rule Block Validation Modes (off, observe, enforce)

**Status:** Proposed
**Date:** 2026-10-30
**Authors:** Claude Code (Opus 5.5)
**Related:** ADR-056 (Conway Java ledger rules, Phase 7a shadow sync), ADR-057 (Amaru wasm validator),
ADR-CONSENSUS-002 (validation-aware chain selection), ADR-NET-008 (upstream and chain selection)

## Context

Yano 0.1.0-pre19 can check every synced Conway block against the ledger rules, but only as **shadow sync**
(`yano.validation.shadow-sync=true`, ADR-056 Phase 7a). Shadow sync observes only: it validates each block against
its pre-block state, then reports disagreements, and the block is applied either way. Runs on preprod, preview and
mainnet from the Conway hard fork to the tip found 0 false rejections (mainnet: about 31M Conway transactions,
2026-10-29).

Yano is meant to become a real relay and block producer, where each capability can be switched on or off. A real
node applies ledger rules on the main path: a block that fails them is not adopted. Operators of different
deployments want different things:

- **Indexer or library behind a trusted upstream:** no validation cost.
- **Node behind its own Haskell relay:** validation is useful to find Yano bugs, but the relay has already validated
  every block. Here a rejection from Yano is almost certainly a Yano false rejection. Acting on it stalls the node.
- **Relay or producer with several untrusted peers:** an invalid block must be refused, as the Haskell node does.

This ADR defines one setting with three modes for ledger-rule block validation. It also defines exactly what
"enforce" does, by following the Haskell node. Scope is the block body (the ledger rules); header validation already
exists and only gains the known-invalid check (D3).

### What Yano has today (main `dc1a2ac4e`)

| Piece | Where | Behaviour |
|---|---|---|
| Shadow sync | `runtime/.../validation/shadowsync/ShadowSyncValidator.java` | The first `BlockAppliedEvent` listener (priority `Integer.MIN_VALUE`). It runs inside the block's canonical write section, after the epoch boundary is committed and before any of the block's own changes. It captures the pre-block state, then validates on a virtual thread (at most `shadow-sync-max-in-flight` blocks at once). It never changes what is applied. When no slot frees within `shadow-sync-max-wait-ms` (30 s), the block is skipped and reported. |
| Verdict classes | `SyncBlockValidator.Kind` | `AGREED`; `DISAGREED` (a ledger-rule rejection or the wrong phase-2 verdict, so the engine would have rejected the block); `ENGINE_FAILURE` (only `LedgerRuleName.ENGINE` failures: state unavailable, decoding, unsupported era, an engine that threw). |
| Rules covered | ADR-056 Phase 7a | Rule `LEDGER` for every transaction, with Plutus. `invalid_transactions` must come out phase-2 invalid. Block rules (`BBODY`): `BodyRefScriptsSizeTooBig`, `TooManyExUnits`, `WrongBlockBodySizeBBODY`, `InvalidBodyHashBBODY`. Conway only (protocol version 9 and later). |
| Preconditions | `ShadowSyncPreconditions` | Needs account state, the UTxO store and synchronous UTxO apply. If one is missing, the node logs a WARN and runs without shadow sync. |
| Post-store failure compensation | `BodyFetchManager.compensateFailedPostStoreApply` (line 1246) | A block whose apply fails after `storeBlock` is rolled back to its parent inside the same write section, and a `RollbackEvent` is published. Every post-store apply failure already takes this path. |
| Listener failures | `PropagatingEventBus` | A failing synchronous `BlockAppliedEvent` listener fails the publish (`EventDeliveryException`), so the apply fails. |
| Apply failure → peer | `LedgerApplyProcessor` (`Outcome.FAILED`) | The generation fails and peer recovery is requested: disconnect, then restart from the durable tip. |
| Header validation | `yano.upstream.validation.level` (`HeaderSyncManager.rollforward`, line 139) | `none` by default. Any other level rejects a bad header, and `PipelineDataListener.handleHeaderApplyFailure` (line 523) fails the generation and requests recovery. Already "enforce". |
| Body-validator hook | `yano.upstream.validation.body-level`, `BodyValidator` | Supports only `none`. It runs before the write section and before the epoch boundary, so it cannot see the pre-block state (ADR-056 Phase 7a, "Not reused"). It stays a library hook for pre-store checks. |
| Upstream presets | `UpstreamPreset` | `trusted-single`, `trusted-failover` (one active peer), and `static-multi`, `rooted-relay`, `p2p-relay` (multi-peer). Failover advances the active peer after a recovery failure (`SyncSubsystem.advanceActiveUpstreamAfterFailure`). |
| Producer | `SlotLeaderBlockProducer.produceBlock` | Applies the epoch boundary in its own section. Selects mempool transactions with the admission engine over a block-local overlay (ADR-056 Phase 6b). Stores the block, then publishes `BlockAppliedEvent` through `BlockProducerHelper.publishEvent`. |

## Haskell reference behaviour

Pinned: ouroboros-consensus `ce582c6c4bfa9f6bbabd5f93451b7c620c850f7f` (2026-10-29), cardano-ledger
`c82be4c1b72c52e87f21485e7d8e8838c4ce9852` (2026-10-29), cardano-node `8edbc1c041ccaeb989347184024662493119f7d9`
(2026-10-28).

Path prefixes:
- `OC/` = `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/`
- `OCD/` = `ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/`
- `CS` = `OC/MiniProtocol/ChainSync/Client.hs`

The Haskell node has **no** observe-only mode for live blocks. `--validate-db` only switches the on-disk ImmutableDB
and VolatileDB integrity checks (`OCD/Node.hs:1256-1258`, `OC/Storage/ChainDB/Impl/Args.hs:158-171`). The only path
that skips ledger rules is the reapply of blocks it already validated (H8). Validation is always on, and it always
acts.

| Ref | Behaviour | Where |
|---|---|---|
| H1 | **Header errors disconnect.** A received header is validated against a ledger view forecast from the intersection. Any failure throws `HeaderError`, a `ChainSyncClientException`, which "will bring down all miniprotocols in both directions with that node". Near-future headers wait (Chronos); headers beyond the 2 s clock skew disconnect. | `CS:1926-1932`, `CS:2270-2325`, `CS:1703-1705`, `OC/MiniProtocol/ChainSync/Client/InFutureCheck.hs:160-168` |
| H2 | **The whole block is invalid when any rule fails.** BBODY runs LEDGERS over every transaction, and any failure is `Left (BlockTransitionError ..)`. There is no partial acceptance. The only failing transaction allowed in a valid block is one tagged phase-2 invalid whose scripts do fail (collateral taken). A wrong tag in either direction is `ValidationTagMismatch`. | cardano-ledger `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Bbody.hs:106-113`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Validation.hs:151-154`, `eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxos.hs:149-155, 212-221` |
| H3 | **An invalid block is not adopted and is remembered.** Chain selection validates the candidate (`validateCandidate`). On a ledger error it truncates the candidate to the last valid block and records `InvalidBlockInfo` (reason and slot) under the block hash. The valid prefix is still adopted if it is preferred over the current chain. Descendants are not inserted into the map; they become unreachable because candidates drop invalid successors. | `OC/Storage/ChainDB/Impl/ChainSel.hs:1292-1352` (failure at 1318-1321), `:1365-1370`, `:1180-1188`, `:1413-1420`; `OC/Storage/ChainDB/Impl/Types.hs:545-554` |
| H4 | **The invalid-block map lives in memory only.** It starts empty on every open and is garbage-collected with the VolatileDB (entries below the GC slot are dropped). After a restart, blocks still in the VolatileDB are validated again. | `OC/Storage/ChainDB/Impl.hs:218`, `OC/Storage/ChainDB/Impl/Background.hs:494` |
| H5 | **The peer that delivered it is disconnected.** The punishment is `BlockItself` when the invalid block is the one this peer added, otherwise `BlockPrefix`. BlockFetch disconnects on `BlockPrefix` always, and on `BlockItself` unless diffusion pipelining forgives one tentative block (H9). The exception is `PeerSentAnInvalidBlockException`. | `OC/Storage/ChainDB/Impl/ChainSel.hs:1331-1340`; `OC/MiniProtocol/BlockFetch/ClientInterface.hs:194-230`; `OC/Storage/ChainDB/API/Types/InvalidBlockPunishment.hs:26-62` |
| H6 | **Peers offering a known-invalid chain are disconnected at the header.** Every roll-forward header is checked against the map (`checkKnownInvalid`). Whenever the map changes, each peer's candidate fragment is scanned (`invalidBlockRejector`). Both throw `InvalidBlock`. | `CS:1500`, `CS:1646-1692`, `CS:2088-2163` |
| H7 | **A forged invalid block is dropped, with all its transactions.** The forging loop adds its block with `noPunishment`. If the block is not adopted and is in the invalid map, it traces `TraceForgedInvalidBlock` and removes **every** transaction of the block from the mempool (`removeTxsEvenIfValid`), "otherwise we'll run the risk of forging the same invalid block again". Before forging, the mempool snapshot is re-validated against the ledger state it extends (`getSnapshotFor`). | `OCD/NodeKernel/Forge.hs:285-345` (321-332), `:492-510`; `OC/Mempool/API.hs:184-192` |
| H8 | **Reapply skips validation only for blocks already validated.** ImmutableDB replay at startup reapplies with `ValidateNone`. Live blocks use `ApplyVal` (full validation) unless they are in the in-memory previously-applied set. | `OC/Ledger/Abstract.hs:128-138`; `OC/Storage/LedgerDB/API.hs:660-697`; `OC/Storage/LedgerDB/Forker.hs:350-356` |
| H9 | **Diffusion pipelining.** A node may announce one tentative header before validating its body. If the body is invalid (a "trap header"), the header is cleared and downstream peers get a rollback. Downstream nodes forgive `BlockItself` once per issuer and block number; `BlockPrefix` is never forgiven. | `OC/Block/SupportsDiffusionPipelining.hs:40-62`; `OC/Storage/ChainDB/Impl/ChainSel.hs:1214-1224, 1376-1394`; `OC/Storage/ChainDB/API/Types/InvalidBlockPunishment.hs:64-87` |

## Decision

### D1. One setting, three modes

```yaml
yano:
  validation:
    block-validation: observe   # off | observe | enforce
```

| Mode | Work done | Effect on the chain |
|---|---|---|
| `off` | none | none |
| `observe` (default) | Exactly today's shadow sync (ADR-056 Phase 7a), unchanged: asynchronous, `shadow-sync-engines`, backpressure, skip after `shadow-sync-max-wait-ms`, report. | none |
| `enforce` | Every Conway block, received or forged, is validated synchronously by the admission engine (`yano.validation.engine`) before it can become canonical. | An invalid block is not adopted (D3, D4). |

- `yano.validation.shadow-sync` is removed. If it is set, startup fails and names `block-validation`. ADR-056
  Phase 8 retired the engine id `java` the same way. `observe` is what `shadow-sync=true` was.
- The `shadow-sync-*` settings keep their names and tune `observe`. `enforce` reads only `shadow-sync-max-wait-ms`
  and `shadow-sync-report` (D2, D3).
- `enforce` uses one engine, the admission engine. A block and a mempool transaction are then judged by the same
  rules, as in Haskell, where the mempool and chain selection share one ledger. Startup fails when `enforce` is
  combined with an engine that has no block validation (`scalus`, the legacy validator).
- `enforce` fails startup when `ShadowSyncPreconditions` are unmet. `observe` keeps today's WARN-and-continue
  behaviour.
- The setting sits under `yano.validation`, not `yano.upstream.validation`, because it also covers forged blocks
  (D5).

Rejected alternatives:

- **Separate settings for received and forged blocks.** Haskell validates both through the same chain selection
  (`ChainSel`). No deployment needs them to differ.
- **Reuse `upstream.validation.body-level: ledger-block`,** as ADR-CONSENSUS-002 planned. That hook runs before the
  epoch boundary, so it cannot see the pre-block state. It also covers only received blocks.
- **Keep `shadow-sync` as an alias.** That would be a compatibility shim (AGENTS.md "Leave nothing behind").

### D2. Where enforce validates

**Received blocks.** Validation happens at the same capture point as observe: the first `BlockAppliedEvent`
listener, inside the block's write section, after the boundary and before any of the block's changes.

- The validator captures the pre-block state and validates on a virtual thread. The apply thread waits for the
  verdict for up to `shadow-sync-max-wait-ms`.
- There are no permits and no skipping. Blocks validate one at a time, as in Haskell chain selection, because the
  next block's pre-block state depends on this verdict.
- An invalid verdict throws a typed `InvalidBlockException` that carries the ledger failures. `PropagatingEventBus`
  fails the publish. The existing compensation rolls the block back to its parent, together with the epoch boundary
  committed in the same section, and the existing apply failure fails the generation. Neither the pipeline order nor
  the rollback path changes.

**Forged blocks.** See D5.

### D3. What enforce does with an invalid block (Haskell behaviour)

1. **Not adopted (H3).** The compensating rollback leaves the node on the parent block (D2). Yano applies blocks
   one at a time, so the valid prefix is already adopted. Nothing built on the invalid block is applied, because its
   parent never becomes canonical.
2. **Remembered (H3, H4).** An in-memory invalid-block set maps each block hash to its slot and failure reason.
   - Only the rejected block itself is entered, as in Haskell; its descendants are not.
   - Like Haskell's map, it is not persisted. After a restart, a rejected block is fetched and validated again.
     That is the same outcome as Haskell re-validating its VolatileDB, because Yano never stored the block.
   - Entries are pruned once their slot falls below the immutable tip (the rollback window derived from Shelley
     genesis), as Haskell prunes with the VolatileDB.
3. **Checked on headers (H6).** `HeaderSyncManager.rollforward` rejects a header before storing it when its hash is
   in the set (`checkKnownInvalid`), or its previous hash is. The previous-hash check stands in for Haskell's scan of
   the candidate fragment (`invalidBlockRejector`): Yano keeps no fragment for its active peer, and a chain built on
   a known-invalid block must present that block's hash as its previous hash. The rejection uses the existing
   header-failure path. So a peer that serves a known-invalid chain is disconnected at the header, and the body is
   not fetched again.
4. **Peer disconnected (H5).** A new `PeerRecoveryReason.INVALID_BLOCK` is used for the generation failure.
   - **`trusted-failover` and the multi-peer presets:** it advances the active upstream through
     `advanceActiveUpstreamAfterFailure`, like Haskell disconnecting the peer that sent the block.
   - **`trusted-single`:** it reconnects to the same peer after the normal cooldown, and the header check (item 3)
     rejects the chain again. The node stalls on its last valid block until the upstream moves to a valid chain or
     an operator acts. This is the expected cost of enforce with one peer.
   - `INVALID_BLOCK` recoveries do not count toward the terminal recovery limit. A node stalled on an invalid chain
     keeps checking, as Haskell keeps reconnecting.
5. **Reported.**
   - A WARN log line with the block, the peer and the failures.
   - A `block-rejected` line in `shadow-sync-report`, when that is set.
   - `yano_validation_block_rejected_total{rule}`.
   - The invalid-block count and the last rejection in the upstream status.

Haskell's diffusion-pipelining exception (forgiving one tentative block) does not apply. Yano does not implement
diffusion pipelining, so Haskell's pipelining-off branch applies: the peer is disconnected for the invalid block
itself as well as for one built on it.

### D4. Engine failure is not an invalid block

In enforce, an `ENGINE_FAILURE` verdict or a verdict timeout (`shadow-sync-max-wait-ms`) fails the apply **without**
marking the block invalid and **without** `INVALID_BLOCK`. The existing `APPLY_FAILED` recovery retries the same
peer, and a persistent engine fault ends in the existing terminal state. Haskell has no such case, because its
ledger cannot be unavailable. Operators who prefer availability over enforcement run `observe`. There is no extra
setting.

### D5. Forged blocks

- **Observe:** unchanged. Shadow sync sees the producer's `BlockAppliedEvent`.
- **Enforce:** `SlotLeaderBlockProducer` validates the built block before `storeOnUnchangedTip`, inside its store
  section. The producer applied the boundary in an earlier section, so the published state there is the pre-block
  state. If the block is invalid:
  - the block is discarded through `BlockProducerHelper.discardBuiltBlock` (which rolls back the pending nonce
    state);
  - **every** transaction of the block is removed from the mempool, as Haskell's `removeTxsEvenIfValid` does (H7),
    so the producer does not forge the same invalid block again;
  - an ERROR is logged.

  The removal goes through `invalidateSelectedTransaction`, the call `UnfitBlockTransactionException` already
  uses. Yano's selection step already re-validates mempool candidates against the forge-slot state with the same
  engine (ADR-056 Phase 6b), which is Haskell's `getSnapshotFor` (H7). So a forged invalid block means the two
  disagree, exactly as Haskell's comment says.

### D6. Scope that does not change

- **Rules:** what ADR-056 engines check (the rule list in Context). Completeness of the rules stays ADR-056's
  concern. Of the Conway BBODY failures (H2), only `HeaderProtVerTooHigh` is not checked (ADR-056 Phase 7a, "Out of
  scope").
- **Pre-Conway blocks** (protocol version below 9) are not validated in any mode. They are trusted history, as with
  a Mithril-restored node.
- **Header checks** stay under `yano.upstream.validation.level`. Any level other than `none` already rejects and
  disconnects; D3 adds only the known-invalid check.
- **Mempool admission** is already enforced and does not change.

### D7. Recommended settings (for the docs)

| Deployment | `block-validation` | Header `level` | Why |
|---|---|---|---|
| Indexer or library, trusted upstream | `off` or `observe` | `none` | Trusted source; validation is optional. |
| Relay or producer behind own Haskell relays (`trusted-single`, `trusted-failover`) | `observe` | `none` or `praos-lite` | The relay already validated; an enforce rejection would be a Yano false rejection and stall the node. |
| Relay with untrusted peers (`static-multi`, `rooted-relay`, `p2p-relay`) | `enforce` | `praos-ledger` | Refuse invalid blocks and switch peer, as the Haskell node does. |

### D8. Later milestone: validated multi-peer chain selection

Full Haskell behaviour also needs:

- **Candidate rejection:** observer sessions (ADR-NET-008 Phase 4) and the candidate header store check the
  invalid-block set and drop or disconnect candidates that contain an invalid block, like Haskell's
  `invalidBlockRejector` (H6).
- **Validated chain selection:** chain selection prefers candidates whose blocks validated, and switches away from a
  chain that turns invalid. This is ADR-CONSENSUS-002 Phases 7 and 8.

This ADR only makes the invalid-block set and the `INVALID_BLOCK` reason available to that work (M4).

## Invariants

| ID | Invariant | Decisions |
|---|---|---|
| I1 | `observe` never changes what is applied (unchanged from ADR-056 Phase 7a). | D1 |
| I2 | In `enforce`, after a rejected block's apply attempt completes, chain state, UTxO, account state, governance and epoch-boundary state equal their state at the parent block. | D2, D3 |
| I3 | A block rejected in `enforce` is not served to a downstream peer (see Risks: serving reads `ChainState` directly). | D2 |
| I4 | An engine failure or timeout never adds a block to the invalid-block set and never uses `INVALID_BLOCK`. | D4 |
| I5 | A header whose hash, or previous hash, is in the invalid-block set is rejected before it is stored, and its body is not fetched. | D3 |
| I6 | `enforce` fails startup when its preconditions or engine are missing; `observe` never fails startup. | D1 |
| I7 | `off` does no validation work and behaves as `shadow-sync=false` did. | D1 |
| I8 | Blocks below protocol version 9 are never rejected by ledger validation. | D6 |
| I9 | A forged block rejected in `enforce` is not stored or announced, its nonce state is rolled back, and all its transactions leave the mempool. | D5 |

## Threat model and trust

- **Untrusted:** peers in multi-peer presets. A peer may send invalid blocks, or a chain built on one.
- **Trusted:** `trusted-single` and `trusted-failover` upstreams (operator-run relays).
- **False rejection** (Yano rejects a valid block) costs liveness: the node stalls or switches peer. Today's
  evidence that this risk is small is shadow sync's 0 false rejections on three networks. Continued observe runs
  remain the gate for new rules.
- **False acceptance** (Yano accepts an invalid block) costs safety. Neither shadow sync nor enforce can detect it
  on a valid chain. Coverage comes from ADR-056 conformance vectors and the negative tests in M1.
- **Denial of service:** re-sending a known-invalid chain costs one header check per header (D3, item 3), not a
  body validation.

## Implementation milestones

| ID | Content | Entry gate | Exit criteria |
|---|---|---|---|
| M1 | D1, D2, D4: the setting, synchronous enforce for received blocks, `InvalidBlockException`, metrics and status, removal of `shadow-sync`. | ADR accepted | I1, I2, I3, I4, I6, I7, I8 tested; devnet negative tests (Verification) pass; observe results unchanged on the ADR-056 devnet gate. |
| M2 | D3: invalid-block set, header check, `INVALID_BLOCK`, active-upstream rotation, terminal-limit exemption. | M1 | I5 tested; on a two-peer devnet, a peer serving an invalid block is disconnected and sync continues from the other peer. |
| M3 | D5: forged-block enforce. | M1 | I9 tested with a producer forced to forge an invalid block. |
| M4 | D8: candidate rejection in observer sessions; hand-off to ADR-CONSENSUS-002 Phases 7–8. | M2 | Separate ADR revision or phase report. |
| M5 | D7 docs, and public-network runs in `enforce`: preprod, preview and mainnet from the Conway hard fork to the tip with 0 rejections, recording per-block validation time on Plutus-heavy mainnet blocks. | M1–M3 | Results recorded in this ADR. |

## Verification

- **Negative cases on a devnet,** each checked against an independent judge: the same block is fed to a Haskell
  `cardano-node` follower (the `haskell-sync` QA harness), which must reject it too. The invalid blocks come from a
  test-scope block builder. Cases:
  - a bad VKey witness;
  - `is_valid=true` with a failing script;
  - `is_valid=false` with a passing script (`UTXOS.ValidationTagMismatch`);
  - a wrong body hash;
  - the sum of ex-units above `maxBlockExUnits`;
  - a reference-script size above the block limit.
- **First-of-epoch rejection:** a rejected block that crosses an epoch boundary restores the pre-boundary state
  exactly (I2). This compares store contents with a node that never saw the block.
- **Downstream:** a downstream follower (Yano and Haskell) of an enforcing node never receives a rejected block
  (I3).
- **Engine failure:** an engine that throws, and an engine that hangs (as with julc #266), give `APPLY_FAILED`
  recovery and an empty invalid-block set (I4).
- **Positive:** the M5 public-network runs. Those chains were adopted by Haskell nodes, so every rejection there is
  a Yano false rejection.

## Consequences

- One setting covers all three operator needs. The default (`observe`) adds validation work that only reports.
- `enforce` makes block application serial and synchronous for Conway blocks, so catch-up from the Conway hard fork
  runs at validation speed. M5 measures this.
- With `enforce`, a Yano false rejection is a liveness bug. With one upstream it stalls the node, which is why D7
  recommends `observe` there.
- Removing `shadow-sync` breaks configs that set it. The error message names the replacement.

## Risks

| Risk | Mitigation |
|---|---|
| A stored-then-rejected block is visible to a downstream reader between `storeBlock` and the compensating rollback, because n2n serving reads `ChainState` directly, not the published tip. | M1 must test I3. If the block is observable, the server reads the published canonical tip. |
| Validation time on Plutus-heavy mainnet blocks is unmeasured (ADR-056 Phase 7a "Open"). | M5 measures it. The verdict timeout turns a pathological block into `APPLY_FAILED`, not a hang. |
| The default `observe` adds CPU load on every node. | `off` remains available. See Open questions, Q1. |

## Open questions

- **Q1. Default mode.** The ADR proposes `observe`, as requested. This turns shadow sync on for every node that
  meets the preconditions (it is off today). It costs CPU and slows Conway catch-up to validation speed.
  Alternative: default `off`, with `observe` recommended in the docs. Lean: `observe`, because it surfaces false
  rejections early, before anyone enables `enforce`.
- **Q2. Header observe mode.** Header checks have no observe mode: any level other than `none` rejects. Lean: do not
  add one (KISS). `opcert-counter-mode: compat` already covers the one header check that needed a soft mode.
- **Q3. Terminal limit for `INVALID_BLOCK`.** D3 exempts it so a stalled node keeps checking. Alternative: count it,
  so a stalled single-upstream node ends in the terminal state and needs an operator restart. Lean: exempt.

## Related findings (out of scope)

- `BlockProducerHelper.publishEvent` catches every exception from the producer's `BlockAppliedEvent` publish and
  logs it at DEBUG, except for block 0. A failing ledger listener on the producer path (UTxO apply, account state)
  therefore leaves the block stored with its state unapplied. That contradicts `PropagatingEventBus`'s fail-closed
  contract. This needs its own issue.
- Upstream peer recovery goes terminal after 10 `STARTUP_FAILED` attempts within minutes. A producer whose local
  relay takes longer to start never reconnects without a restart. Seen 2026-10-29; handled separately.

## Revision history

- r1 (2026-10-30): initial version.
