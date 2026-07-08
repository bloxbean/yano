# Preview DRep Distribution Comparison

Date: 2026-04-18

## Scope

This document compares Yano preview `drep_dist.parquet` exports against the db-sync preview parquet export:

- Yano: `app/data-preview/epoch=*/drep_dist.parquet`
- db-sync: `/Users/satya/work/dbsync-parquet/dbsync-preview-parquet/drep_distr_from646.parquet`

Comparison is for **DRep distribution amounts only**.

Ignored on purpose:

- expiry fields
- active/inactive flags

## Normalization

The two sides do not use the same `drep_id` encoding.

- Yano export includes:
  - `drep_hash`
  - CIP-129 style `drep_id`
- db-sync preview parquet includes:
  - `drep_id`
  - no physical `drep_hash` column

To compare them reliably:

1. decode db-sync `drep_id` from bech32
2. convert the payload to bytes
3. normalize to the raw 28-byte DRep credential hash
4. compare that hash to Yano `drep_hash`

Virtual DReps were normalized to:

- `__ABSTAIN__`
- `__NO_CONFIDENCE__`

## Coverage

- db-sync parquet coverage: `647..1262`
- Yano export coverage at comparison time: through `1271`
- shared comparison range: `647..1262`

## Overall result

Shared epochs compared: `616`

Mismatch epochs: `150`

Mismatch range:

- `681..830`

Clean ranges:

- `647..680`
- `831..1262`

Meaning:

- for every shared epoch from `831` through `1262`, Yano DRep distribution amounts match db-sync exactly

## Sample exact-match checkpoints

Verified explicitly:

- `epoch 962`: exact amount match
- `epoch 1262`: exact amount match

## Unique mismatched DReps

There are `5` unique mismatch DRep hashes across the `681..830` band.

### 1. `ecdb7a5a456419f3bb665fc216cc5248cf846def18f26d6d0b2c35a9`

- Yano `drep_id`:
  - `drep1ytkdk7j6g4jpnuamve0uy9kv2fyvlprdauv0ymtdpvkrt2gla0chu`
- db-sync `drep_id`:
  - `drep1andh5kj9vsvl8wmxtlppdnzjfr8cgm00rrex6mgt9s66jqt49s0`
- mismatch type:
  - `amount mismatch`
- affected epochs:
  - `122`
- first seen at:
  - `709`
- first observed values:
  - Yano: `29973616446`
  - db-sync: `9997629266`

### 2. `fa6a8dc2635dddcf9af495cb144f7eb4ff845866fe48695ad7cb65d3`

- Yano `drep_id`:
  - `drep1ytax4rwzvdwamnu67j2uk9z00660lpzcvmlys6266l9kt5c262k7m`
- db-sync `drep_id`:
  - `drep1lf4gmsnrthwulxh5jh93gnm7knlcgkrxleyxjkkhedjaxyh85ax`
- mismatch type:
  - `only in Yano` in early epochs, then amount mismatch
- affected epochs:
  - `76`
- first seen at:
  - `682`
- first observed values:
  - Yano: `7973069204`
  - db-sync: `NULL`

### 3. `ea87ec24b249060c054dedd2e575d4fb92d8ac9b637072518feb55aa`

- Yano `drep_id`:
  - `drep1yt4g0mpykfysvrq9fhka9et46nae9k9vnd3hquj33l44t2s4nf9ac`
- db-sync `drep_id`:
  - `drep1a2r7cf9jfyrqcp2dahfw2aw5lwfd3tymvdc8y5v0ad2652mfcw7`
- mismatch type:
  - `only in db-sync`
- affected epochs:
  - `20`
- first seen at:
  - `743`
- first observed values:
  - Yano: `NULL`
  - db-sync: `9484128580`

### 4. `f0ed00410031f3288d7889aa896cfdad79a7441885d3bae8982ac151`

- Yano `drep_id`:
  - `drep1ytcw6qzpqqclx2yd0zy64ztvlkkhnf6yrzza8whgnq4vz5gh89626`
- db-sync `drep_id`:
  - `drep17rksqsgqx8ej3rtc3x4gjm8a44u6w3qcshfm46yc9tq4zeffuzj`
- mismatch type:
  - `only in Yano`
- affected epochs:
  - `3`
- first seen at:
  - `681`
- first observed values:
  - Yano: `308329066387`
  - db-sync: `NULL`

### 5. `21c803d27b8daf2c581179478ee5fa9da0ef70023e11081d5dfe978c`

- Yano `drep_id`:
  - `drep1ygsusq7j0wx67tzcz9u50rh9l2w6pmmsqglpzzqathlf0rqm87es5`
- db-sync `drep_id`:
  - `drep1y8yq85nm3khjckq309rcae06nksw7uqz8cgss82al6tccd70xth`
- mismatch type:
  - `amount mismatch`
- affected epochs:
  - `2`
- first seen at:
  - `730`
- first observed values:
  - Yano: `15488493353`
  - db-sync: `4994942842`

## Interpretation

The current tip-sync preview result is strong on DRep distribution amounts:

- all shared epochs from `831..1262` match db-sync exactly

The remaining DRep-distribution amount drift is confined to an earlier historical band:

- `681..830`

That band should be debugged separately if exact historical preview parity is required for all Conway epochs.
