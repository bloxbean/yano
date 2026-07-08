# Preprod Full Sync Watch - 2026-04-30

Scope: observe only. No code changes should be made from this watch.

## Run Context

- Network/profile: preprod
- Process observed: `java -Dquarkus.profile=preprod -jar build/yano.jar`
- Data export: enabled by user before run
- Main checks:
  - Protocol parameter API vs Blockfrost preprod
  - Governance ratification for protocol parameter update proposal `158ef6b2/0`
  - AdaPot verification around epochs 251/252

## Observations

- 2026-04-30 01:33 SGT: clean full sync is running from genesis. Current log is in Babbage era around slot `16M` / early epoch 40s. Conway governance epochs not reached yet.
- 2026-04-30 01:38-01:53 SGT: protocol params matched Blockfrost at checkpoint epochs 70, 80, 90, 100, 110, 120, 130, and 140. Cost model raw languages were `PlutusV1` and `PlutusV2`.
- 2026-04-30 01:55 SGT: epoch 150 initially showed a single scalar mismatch: `nonce`, but an immediate direct recheck showed Yano and Blockfrost nonce values match exactly. Treating the first line as a transient read during sync/API update unless it reproduces.
- 2026-04-30 02:00 SGT: epoch 170 protocol params matched scalar fields, but `cost_models_raw.PlutusV3` mismatched Blockfrost. Direct recheck for epochs 170-172 reproduced the mismatch. Both sides have 251 PlutusV3 entries, but values diverge starting at raw index 14. Yano exposes sequential numeric `cost_models.PlutusV3` keys (`000`, `001`, ...), while Blockfrost exposes named keys; the raw array comparison uses Blockfrost named keys sorted lexicographically.
- 2026-04-30 02:02 SGT: proposal `b52f0228/0` was enacted for epoch 180 with changed fields `[costModels, costModelsHash]`. Protocol params at epoch 180 still only mismatched Blockfrost for `cost_models_raw.PlutusV3`.
- 2026-04-30 02:06 SGT: epoch 190 initially showed `nonce` plus `PlutusV3` mismatch, but direct recheck showed nonce and all scalar fields match. Treating nonce as another transient read. PlutusV3 mismatch remains reproducible.
- 2026-04-30 02:08 SGT: epoch 200 protocol params still only mismatched Blockfrost for `cost_models_raw.PlutusV3`.
- 2026-04-30 02:09 SGT: yaci `BlockSerializer` logged two errors: `block: 3294249 redeemer does not have the same size` and `block: 3297688 redeemer does not have the same size`. Sync continued.
- 2026-04-30 02:11 SGT: epoch 210 initially showed `nonce` plus `PlutusV3` mismatch, but direct recheck showed nonce and all scalar fields match. PlutusV3 mismatch remains.
- 2026-04-30 02:14 SGT: epoch 220 protocol params still only mismatched Blockfrost for `cost_models_raw.PlutusV3`.
- 2026-04-30 02:13-02:17 SGT: additional yaci `BlockSerializer` errors with `redeemer does not have the same size` appeared across multiple blocks. Sync continued.
- 2026-04-30 02:17 SGT: proposal `49578eba/0` enacted for epoch 233 with changed fields `[committeeMinSize]`. This is the expected predecessor governance update needed for `committeeMinSize=3` at epoch 251.
- 2026-04-30 02:19-02:21 SGT: epochs 240 and 245-250 protocol params matched Blockfrost for scalar fields; only `cost_models_raw.PlutusV3` mismatched. Epoch 250 ratification context showed `committeeMinSize=3`; `158ef6b2/0` was still active because it had no votes yet at that boundary. AdaPot verification passed for epoch 250.
- 2026-04-30 02:21 SGT: epoch 251 clean-sync ratification succeeded for `158ef6b2/0`: `status=RATIFIED reason=accepted`, committee `yes=3/no=0`, active committee `3`, min `3`, DRep ratio `0.8632151862901632207456914637943772`, SPO ratio `0.7304294875710241314141761874174051`. AdaPot verification passed for epoch 251.
- 2026-04-30 02:22 SGT: epoch 252 enacted `158ef6b2/0` with changed fields `[maxBlockExMem, maxBlockExSteps, maxTxExMem, maxTxExSteps]`. AdaPot verification passed for epoch 252. Protocol-param scalar fields at epoch 252 matched Blockfrost; only `cost_models_raw.PlutusV3` mismatched.
- 2026-04-30 02:22 SGT: epoch 253 protocol-param scalar fields matched Blockfrost; only `cost_models_raw.PlutusV3` mismatched. Monitor stopped at epoch 253.
- 2026-04-30 later check: current sync reached epoch 285. Spot checks for epochs 260, 270, 280, and 285 showed scalar protocol-param fields match Blockfrost; only `cost_models_raw.PlutusV3` mismatched.

## Potential Issues

- PlutusV3 cost model raw projection may be using the wrong ordering or wrong source order from Conway genesis. Need review tomorrow; no code change made during watch.
- The previous epoch 251 AdaPot mismatch appears to have been rollback residue. In clean full sync, epoch 251 and 252 AdaPot verification both passed after the committee-min-size ratification fix.

## Pending Checks

- Confirm no reward/AdaPot failure before epoch 251.
- At epoch 251, verify:
  - `Ratification context: epoch=251` has `committeeMinSize=3`.
  - `Ratification decision: 158ef6b2/0` is `status=RATIFIED reason=accepted`.
  - `Phase 2: ratified -> pending enactment 158ef6b2/0 at 250 -> 251`.
- At epoch 252, verify the proposal is enacted and protocol params reflect the update.
- Compare `/api/v1/epochs/{epoch}/parameters` to Blockfrost for key epochs around 248-253 and later sample epochs.
