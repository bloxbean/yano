# Historical serialized block fixtures

Copied unchanged from [txpipe/pallas](https://github.com/txpipe/pallas/tree/ac316278bcc14a8c4473c2fe2bb1669a4736deeb/test_data)
at commit `ac316278bcc14a8c4473c2fe2bb1669a4736deeb` (`test_data/<same filename>`).
The upstream Apache-2.0 license is retained in `LICENSE-pallas`.

These are era-wrapped hex CBOR blocks from Shelley, Allegra, Mary, Alonzo,
Babbage and Conway. The selected blocks include withdrawals, stake certificates,
pool owners/reward accounts, MIR recipients and governance proposal refunds.
`expected.json` pins source byte digests and exact per-transaction credential-set
counts/digests computed by the independent raw-CBOR walker `reference.py`.
The Java test does not execute the generator or derive expectations from Yaci.

These non-contiguous blocks lack their preceding UTxOs. This suite verifies
serialized output/event extraction and filter membership, not historical input
resolution, full-chain scan completeness, or sync throughput. Effective input and
invalid-transaction semantics have separate runtime tests.
