# Yano App-Chain Cardano Effects

Cardano **payment executor** plugin for the Yano app-chain effect system
(ADR-010).

App-chain state machines emit effect records deterministically; a separate
Effect Runtime executes finalized effects against external systems. This module
adds an executor for the `cardano.payment` effect type: it builds, signs and
submits a Cardano L1 payment for an approved effect, stamps the effect id into
the transaction metadata (an on-chain idempotency breadcrumb), and confirms the
payment by transaction hash — then the outcome (the tx hash) flows back into
app-chain state via a `~fx/result` message.

It implements `AppEffectExecutorFactory` (scheme `cardano`) and is discovered
through ServiceLoader when the plugin jar is on the node. Secrets (the payer
wallet mnemonic / account key) live in this plugin's config on the executor
node only — never in effect payloads.

See also:

- [ADR-010: deterministic effect system](../../../adr/app-layer/010-deterministic-effect-system.md) (§8.1 payment use case, FX-M4)
- [App-chain user guide §18](../../../docs/APP_CHAIN_USER_GUIDE.md) — effects, executors, and the approvals→payment flow

## Packaging model

A T3 plugin module: the node provides `core-api`; this jar brings the
cardano-client-lib tx-building and backend dependencies.

Build the jar:

```bash
./gradlew :appchain-effects-cardano:jar
```

Then place it in the node plugin directory configured by
`yaci.plugins.directory` (default `plugins/`), on the node that runs the effect
executor (`yano.app-chain.effects.executor.enabled=true`).

## Configuration

```properties
# Blockfrost-compatible backend — typically THIS node's own REST API (ADR-018)
yano.app-chain.effects.executors.cardano.backend-url=http://localhost:8080/api/v1/

# Payer wallet — a mnemonic OR a bech32/hex account key (secrets live here only)
yano.app-chain.effects.executors.cardano.signing-mnemonic=<24-word payer mnemonic>
# yano.app-chain.effects.executors.cardano.signing-account-key=<bech32-or-hex account key>

yano.app-chain.effects.executors.cardano.network=preprod        # mainnet | preprod | preview

# optional
yano.app-chain.effects.executors.cardano.metadata-label=21042   # tx metadata label
yano.app-chain.effects.executors.cardano.max-lovelace-per-tx=500000000  # blast-radius cap (0 = uncapped)
```

The executor is disabled when `backend-url` or a signing credential is missing.

## Effect payload

The `cardano.payment` effect payload is a CBOR map (what the stdlib approvals
payments flow emits) or a minimal flat JSON object:

```json
{ "to": "addr_test1...", "lovelace": 1500000, "memo": "invoice-42" }
```

`to` and a positive `lovelace` are required; `memo` (optional) becomes public
L1 metadata. The stdlib `approvals` machine emits this automatically on final
approval when `yano.app-chain.machines.approvals.payments=true` and the explicit
`yano.app-chain.machines.approvals.activations.payments=<height>` has been
reached. Use height `1` only for a new chain that enables payments from genesis;
use a future height, identical on every member, for a live-chain rollout.

Legacy migration is different: if a chain already used `payments=true` under a
binary that treated a missing activation as active, set
`activations.payments=1` on every member **before** deploying the corrected
binary, then validate replay or snapshot restoration. A future height would
change historical emission behavior.

## Execution semantics

- **Idempotency**: the effect id (`chainId/height/ordinal`) and its hash are
  written into tx metadata under the configured label. A crash/retry can be
  reconciled by that label; the executor probes a previously submitted tx by
  hash on a re-poll rather than resubmitting.
- **Confirmation-gated**: a submission that hasn't confirmed within the wait
  returns `Submitted(txHash)` and is re-polled — it never reports `Confirmed`
  for a tx that hasn't landed.
- **Failure classification**: all submit failures are retryable (UTxO
  contention succeeds on retry with fresh inputs); a true failure parks the
  effect at the attempt cap for operator review rather than injecting a
  spurious on-chain FAILED.
- **Single-flight per payer**: payments serialize on the payer wallet so
  parallel effects don't collide on the same UTxOs.
- **At-least-once**: the structural duplicate window (crash between submit and
  the runtime recording the outcome) is inherent to the effect model
  (ADR-010 §11) — fund the payer conservatively and set `max-lovelace-per-tx`.

## Security

- Wallet secrets are config on the executor node, never in effect payloads or
  replicated state.
- Set `max-lovelace-per-tx` and fund the payer wallet conservatively so a
  buggy or compromised machine cannot drain it in one payment.
- The effect id and idempotency key stamped into metadata are public on L1;
  the effect `scope` is NOT written to metadata. Do not put sensitive data in
  a payment `memo`.

## Test

```bash
./gradlew :appchain-effects-cardano:test
```
