# ADR-034 Phase 8 Verification Report

Phase 8 implements the optional normalized UTXO-history projection: address
dimension, outputs, native assets, inputs/collateral consumption, and
content-addressed datum/script payloads. Lovelace remains on the output row;
native assets are separate exact `DECIMAL(38,0)` facts and the stable merged
views supplied by both backends provide convenient amount queries. Payment and
stake credentials are physical indexed columns. Tests cover a quantity above
signed `BIGINT`, normalization, and direct stake-credential query data.

The integrated worker also records Byron/AVVM and Shelley `initialFunds` once
as typed genesis outputs using the same canonical outpoint convention as the
independent address resolver; no parent transaction is invented. Pointer
coordinates are always retained. Shelley-through-Babbage pointer credentials
come from a private sequential registration projection with exact undo, while
Conway/later pointer addresses are explicitly marked `pointer_not_effective`.
Stored chain-era metadata is used when the deserialized block does not carry
its era.
