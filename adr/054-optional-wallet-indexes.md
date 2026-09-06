# ADR-054: Optional wallet discovery indexes

Status: implementation in progress for #119. Supersedes the untracked ADR-036
credential-filter proposal for this implementation; incorporates its independent review.

## Decision

Add independent `yano.address-first-seen.enabled` and `yano.scan.index.enabled`
capabilities, both off by default. No archive backend, global transaction-location
index, historical backfill, or missing-filter brute-force fallback is required.
Enable both in the wallet profile only after runtime/API integration is tested.

First-seen keys are complete decoded addresses. Entries survive spending and body
pruning; block undo records contain only newly introduced addresses. Filter keys
are canonical block numbers. Values carry a version, hash seed, element count and
Golomb-Rice bitstream. Candidate matches are always verified against block bodies.
The seed is inline to keep the filter walk sequential.

Both capabilities record their own coverage and canonical progress in the UTxO
database, atomically with block application. Only fresh genesis initialization may
establish complete historical coverage. Late enablement or a missed interval must
not result in a definitive first-seen null. Scans may only read continuously covered
intervals with retained bodies. Reorgs explicitly undo index changes and invalidate
affected scan cursors by block hash. Existing snapshot/recovery paths must preserve
or invalidate these proofs, never infer them from current UTxOs.

The initial filter codec uses P=13 and M=12288, with bounded element counts and a
strict canonical decoder. Credentials carry role and key/script tags. Empty filters
require no hashing. Query values are rehashed and sorted for each block, so total
query preparation includes O(Q log Q), followed by an O(N+Q) merge.

Initial wallet scans seed genesis outputs and track relevant effective outputs.
Resumed scans require bounded outpoint state bound to an exact cursor. Confirmed
transactions include coordinates and effective input/output data, including assets.
The wallet verifies payment ownership: a matching stake credential alone is not
proof that an output belongs to it. Epoch reward-account credits are outside the
transaction scan contract.

## Implementation and validation sequence

1. Codec, format validation, independent vectors and malformed-input tests.
2. Persistent coverage, genesis, first-seen, block undo and runtime integration.
3. Effective credential extraction, filter persistence and scan API.
4. Wallet consumption, cursor/outpoint persistence and rollback integration.
5. Configuration, documentation, end-to-end tests, benchmark and review.

Test all flag combinations, late enablement and disable/re-enable gaps, slot zero,
fully spent addresses, shared payment credentials, collateral returns and same-block
spends, restart/crash recovery, pruning, reorg/replay and active-scan invalidation.
Compare scan results with a brute-force test oracle (not a production fallback).

Benchmark baseline and all enabled combinations on equivalent data; include at
least one million physically stored filters, query sizes 1/10/200, JVM/native,
warm/cold reads, sync contention, allocation, disk/compaction and cache pressure.
The historical estimates of 2–3 GB filters and 100–200 bytes per distinct address
are planning figures, not measured production requirements. Do not claim the
earlier 1–5 minute full-scan target without end-to-end evidence.

## Version 1 extraction matrix

Credential elements are 29 bytes: one role/type byte and the raw 28-byte hash.
Tags are payment/key=0, payment/script=1, stake/key=2, stake/script=3,
drep/key=4, drep/script=5. The tag is part of the filter element, so identical
hash bytes in different roles or key/script namespaces do not alias.

| Source | Included credentials | Effective semantics |
| --- | --- | --- |
| Base address types 0–3 | Payment and stake, with their respective key/script tags | Created outputs and resolved consumed outputs |
| Pointer address types 4–5 | Payment only | Pointer-to-stake resolution is outside this scan contract |
| Enterprise address types 6–7 | Payment only | No stake credential exists in the address |
| Reward address types 14–15 | Stake | Withdrawals and the account fields below |
| Byron addresses | None | No Shelley credential; exact first-seen still uses the complete decoded address |
| StakeRegistration, StakeDeregistration, StakeDelegation | Subject stake credential | Valid transactions only |
| RegCert, UnregCert | Subject stake credential | Valid transactions only |
| StakeRegDelegCert, StakeVoteDelegCert, StakeVoteRegDelegCert, VoteDelegCert, VoteRegDelegCert | Subject stake credential | Delegation target DRep is not included |
| RegDrepCert, UnregDrepCert, UpdateDrepCert | Subject DRep credential | Valid transactions only |
| PoolRegistration | Reward-account stake credential and every owner key hash as stake/key | Pool operator and VRF keys are not address credentials |
| MIR distribution | Every recipient stake credential | Pot-to-pot transfers have no recipient credential |
| Proposal procedure | Return/reward-account stake credential | Deposit refunds credited outside transactions are not scan transactions |
| Phase-2-invalid transaction | Consumed collateral and effective collateral-return addresses | Ordinary inputs, ordinary outputs, certificates, withdrawals and proposals are excluded |

Committee credentials, voting witnesses/voters, required signers, delegation
DRep targets, pool retirements, genesis delegations, and credentials embedded in
scripts/datums are outside version 1. DRep queries therefore discover the listed
DRep certificate subjects, not every governance interaction. Epoch rewards and
nontransaction refunds require the existing account/reward data source.

`WalletCredentialsTest` independently enumerates all ten supported stake
certificate variants, key/script DRep subjects, all Shelley address headers,
withdrawals, pool owners/reward accounts, MIR recipients and proposal return
accounts. Runtime collateral and same-block tests validate effective ledger
selection before extraction. This matrix does not replace representative-era
serialized-body oracle tests, which remain a validation gate.

### Format reference checks

The strict address validator checks Shelley lengths/pointer framing and the
Byron CBOR envelope, payload root and CRC32 described by
[CIP-19's Byron CDDL](https://raw.githubusercontent.com/cardano-foundation/CIPs/master/CIP-0019/CIP-0019-byron-addresses.cddl).
It does not use the display-address constructor as proof of structural validity.
Reward-account event fields accept Yaci's decoded hex representation as well as
Bech32 display strings; both preserve the same typed stake credential.

The GCS golden test combines the published
[SipHash reference vectors](https://github.com/veorq/SipHash/blob/master/vectors.h)
with an independently calculated multiply-high mapping and Rice bitstream.
The coding primitives follow [BIP-158](https://github.com/bitcoin/bips/blob/master/bip-0158.mediawiki),
with Yano's P=13/M=12288 and its own versioned framing (not Bitcoin wire compatibility).
