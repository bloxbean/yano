# App-chain proof contracts

`MpfNormalizedProof` is the implementation-neutral, bounded ABI for consuming an
`mpf-proof-wire-v1` inclusion proof on Cardano. It reconstructs exactly the
`mpf-blake2b256-v1` root and caps keys at 256 bytes, values at 8 KiB and folds at 32.

Use `MpfProofConverter` from `appchain-client` to convert a strict, already verified native
proof. The converter repeats root reconstruction before returning the normalized model.
Provider names are conformance metadata and do not appear in this contract.
