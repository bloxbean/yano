# Preprod Protocol Parameter API Monitor

Started: 2026-04-29 23:35:59 +08

Scope: compare Yano `GET /api/v1/epochs/{epoch}/parameters` with Blockfrost preprod `GET /epochs/{epoch}/parameters` while the local preprod sync progresses. This file tracks observed mismatches only; no implementation changes are made from this monitor.

## Current Status

- Latest sampled Yano epoch: 264
- Sampled epochs: 5-10, 28-29, 36, 39-40, 51-264
- Scalar/value fields: semantic values match through epoch 251; a persistent execution-memory parameter mismatch starts at epoch 252 and is tracked below.
- `cost_models_raw`: canonical arrays match Blockfrost where present.
- Log health: AdaPot verification is passing through sampled latest epoch; no protocol-parameter-related errors observed.

## Issues / Compatibility Notes

### 1. `cost_models` JSON shape differs from Blockfrost

Status: open / compatibility decision needed

Observed from epoch 7 onward, where cost models exist.

- Yano returns `cost_models` as a sequential numeric-key map:
  `{"PlutusV1":{"000":205665,"001":812,...}}`
- Blockfrost returns `cost_models` as a builtin-name-keyed map:
  `{"PlutusV1":{"addInteger-cpu-arguments-intercept":205665,...}}`
- Ordered values match Blockfrost for all sampled Plutus language entries.
- `cost_models_raw` arrays match Blockfrost, so this appears to be a response-shape compatibility issue, not a value/order issue.

Sampled affected epochs:

- 7-10: `PlutusV1`
- 28-29, 36, 39-40, 51-162: `PlutusV1`, `PlutusV2`
- 163-264: `PlutusV1`, `PlutusV2`, `PlutusV3`

### 2. `cost_models_raw` appears as an extra null field before cost models exist

Status: open / minor compatibility decision needed

Observed in epochs 5 and 6.

- Yano includes `cost_models_raw` in the response.
- Blockfrost does not include `cost_models_raw` for these sampled epochs.
- No value mismatch was found; this is only an extra field/null-shape difference.

### 3. Conway governance parameter fields use number type in Yano and string type in Blockfrost

Status: open / Blockfrost compatibility issue

Observed from epoch 163 onward, where preprod protocol version changes to 9.0 and Conway-era fields appear.

The values are semantically equal, but the JSON types differ:

- `committee_min_size`: Yano number, Blockfrost string
- `committee_max_term_length`: Yano number, Blockfrost string
- `gov_action_lifetime`: Yano number, Blockfrost string
- `gov_action_deposit`: Yano number, Blockfrost string
- `drep_deposit`: Yano number, Blockfrost string
- `drep_activity`: Yano number, Blockfrost string

Sample at epoch 163:

- Yano: `"committee_min_size": 7`, `"gov_action_deposit": 100000000000`
- Blockfrost: `"committee_min_size": "7"`, `"gov_action_deposit": "100000000000"`

Sampled affected epochs: 163-264.

### 4. Execution memory protocol parameters diverge from Blockfrost starting at epoch 252

Status: root cause identified / implementation pending

Observed from epoch 252 through the latest sampled verified epoch 264.

- `max_tx_ex_mem`: Yano `14000000`, Blockfrost `16500000`
- `max_block_ex_mem`: Yano `62000000`, Blockfrost `72000000`
- Other sampled execution-unit fields still match:
  `max_tx_ex_steps=10000000000`, `max_block_ex_steps=20000000000`
- `cost_models_raw` and ordered `cost_models` values still match in these epochs.

Log verification:

- Earlier parameter-change `49578eba/0` was ratified at boundary 231 -> 232 and enacted for epoch 233. Protocol parameter values matched Blockfrost after that enactment through epoch 251.
- Yano logs two later `PARAMETER_CHANGE_ACTION` proposals at epoch 249: `17e78f5e/0` and `158ef6b2/0`.
- Those two proposals remain `ratified=false` at boundaries 249 -> 250, 250 -> 251, 251 -> 252, 252 -> 253, 253 -> 254, and 254 -> 255.
- They expire at boundary 255 -> 256: `Ratification results: 0 ratified, 2 expired, 0 active`.
- There is no `Applied enacted governance param change` or `Enacted ParameterChange` log for either proposal.
- Blockfrost shows the execution-memory values changed effective at epoch 252, so the log points more toward a missed ratification/tally/eligibility decision than a merge failure during enactment. There was no enactment for Yano to merge for the epoch 252 change.

Yaci Store cross-check:

- `preprod.gov_action_proposal` has both epoch-249 sibling parameter-change proposals:
  - `158ef6b249b7c3ec219c62d11f0b8e766a356472d023bd7b1e736efed977f3c6/0`
  - `17e78f5e08ba112509729d81f28005caa161878238df3cfc4af983abdc96f9f3/0`
- Both proposals point to previous action `49578eba0c840e822e0688b09112f3f9baaeb51dd0e346c5a4f9d03d2cbc1953/0` and update:
  `maxTxExMem=16500000`, `maxTxExSteps=10000000000`, `maxBlockExMem=72000000`, `maxBlockExSteps=20000000000`.
- `preprod.gov_action_proposal_status` shows `158ef6b2.../0` as `RATIFIED` in epoch 251; `17e78f5e.../0` remains `ACTIVE` in epoch 251.
- Store vote stats for `158ef6b2.../0` at epoch 251: `cc_yes=3`, `cc_approval_ratio=1.0000`, `drep_approval_ratio=0.8632`, `spo_approval_ratio=0.7304`.
- `preprod.epoch_param` changes to `max_tx_ex_mem=16500000` and `max_block_ex_mem=72000000` starting at epoch 252.
- `preprod.reward_rest` contains two `proposal_refund` rows for the shared return address at `earned_epoch=251`, `spendable_epoch=252`, amount `100000000000` each.

Important correction:

- AdaPot passing does not prove the governance lifecycle is correct. Proposal ratification, expiry, and refunds affect `reward_rest` and proposal-deposit stake, which feed DRep distribution and later governance decisions.
- Yano appears to refund the two proposal deposits later via expiry: boundary 255 -> 256 expires both proposals, and boundary 256 -> 257 shows `rewardRest=200000000000` and `depositRefunds=200000000000`.
- Therefore this issue is not isolated to the protocol-parameter API. It is a governance ratification/refund timing mismatch around `158ef6b2.../0` and its sibling `17e78f5e.../0`.

Root cause found:

- The prior parameter-change `49578eba.../0` reduces `committee_min_size` from 7 to 3 effective at epoch 233. Yaci Store `epoch_param` confirms `committee_min_size=3` from epoch 233 onward.
- At the epoch 251 -> 252 boundary, Yaci Store ratifies `158ef6b2.../0` with 3 yes committee votes, DRep approval around 0.8632, and SPO approval around 0.7304.
- A read-only chainstate diagnostic using the current Yano snapshots shows the same committee/SPO inputs are sufficient: committee tally is 3 yes / 0 no and SPO ratio is above the 0.51 threshold.
- Yano still keeps `158ef6b2.../0` active because `GovernanceEpochProcessor.processRatificationPhase()` reads `committeeMinSize` and `committeeMaxTermLength` from `paramProvider` directly. That provider is the genesis/base provider, so it returns `committeeMinSize=7` instead of the effective tracked value `3`.
- `RatificationEngine.checkCommittee()` therefore fails the min-size gate (`activeCount=3 < committeeMinSize=7`) before the proposal can ratify. This prevents enqueueing the epoch-252 enactment, so `max_tx_ex_mem` and `max_block_ex_mem` remain at their old values in Yano.
- The fix should resolve committee governance parameters from the effective epoch parameter snapshot/tracker during ratification, not from the static genesis provider. The same review should cover other governance reads in the hot path that currently call `paramProvider` directly, such as `committeeMaxTermLength`, `govActionLifetime`, and `dRepActivity`.

## Transient Observations

- Epochs 192 and 200 showed a nonce-only mismatch on the first comparison while sync was moving quickly, but immediate re-reads matched Blockfrost exactly. Treat nonce mismatches near the moving local tip as provisional and recheck before filing as an open issue.
- Epoch 206 briefly appeared from `latest` with `nonce: null` before AdaPot verification completed. After `AdaPot verification PASSED for epoch 206`, the epoch 206 response matched Blockfrost semantically. For monitoring, compare settled epochs after boundary verification when possible.

## Log Observations

- `BlockSerializer` logged `redeemer does not have the same size` at blocks 3294249 and 3297688 during epoch 204-205 processing. No protocol-parameter mismatch was observed in the sampled epochs around these logs.
- The same `BlockSerializer` redeemer-size error appeared again at blocks 3517100, 3517538, 3517602, and 3517763 around epoch 218-219 processing. Protocol parameter samples through epoch 222 remain semantically aligned with Blockfrost.
- The redeemer-size serializer error continued through epoch 223-229 processing, including blocks 3601184, 3613324, 3614130, 3615833, 3617494, 3632515, 3632691, 3667392, 3667425, 3670316, 3684965, 3697345, 3697349, and 3697353. Protocol parameter samples through epoch 229 remain semantically aligned with Blockfrost.
- Additional redeemer-size serializer errors appeared at blocks 3748423, 3803778, 3803968, 3803980, and 3804475 during epoch 232-235 processing. Protocol parameter samples through epoch 240 remain semantically aligned with Blockfrost.
- Additional redeemer-size serializer errors appeared at blocks 3937542, 3937552, 3937562, 3937580, 3937904, 3937913, 3937945, 3937948, and 3979735 during epoch 242-244 processing. Protocol parameter samples through epoch 249 remain semantically aligned with Blockfrost.
- Additional redeemer-size serializer errors appeared at blocks 4149090, 4149134, 4149164, 4161565, 4171429, and 4171457 during epoch 253-255 processing.

## Clean Samples

The following sampled epochs had matching scalar protocol parameter values:

- 5-10
- 28-29
- 36
- 39-40
- 51-251
