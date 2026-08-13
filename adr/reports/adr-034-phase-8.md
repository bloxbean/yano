# ADR-034 Phase 8 Verification Report

Phase 8 implements the optional normalized UTXO-history projection: address
dimension, outputs, native assets, inputs/collateral consumption, and
content-addressed datum/script payloads. Lovelace remains on the output row;
native assets are separate exact `DECIMAL(38,0)` facts and the stable merged
views supplied by both backends provide convenient amount queries. Payment and
stake credentials are physical indexed columns. Tests cover a quantity above
signed `BIGINT`, normalization, and direct stake-credential query data.
