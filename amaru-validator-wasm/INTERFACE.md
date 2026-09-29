# amaru-validator-wasm interface, version 1

This is the contract between `amaru_validator.wasm` and its host (Yano's optional
`amaru-validator` Java module, ADR-057 Phase B). Yano owns it; it is independent of
Amaru's internal types and is versioned by `abi_version`.

The Rust reference implementation is `src/interface.rs`. The fixture test
(`tests/amaru_scenarios.rs`) encodes every Amaru scenario through it, and checks
that each request round-trips byte for byte.

## Module

- Target `wasm32-wasip1`, reactor model. The host calls `_initialize()` once after
  instantiation, before any other export.
- **Imports:** `wasi_snapshot_preview1` functions only. `scripts/wasm_check.py`
  enforces the allow-list:
  - `clock_time_get` (Amaru times its rule spans; the value never affects a
    verdict);
  - `random_get` (hash-map seeding);
  - `fd_write` (panic messages to stderr);
  - `proc_exit` (the host turns it into a trap);
  - `environ_get` / `environ_sizes_get` / `args_get` / `args_sizes_get` (answer
    "empty");
  - `sched_yield`.

  No file-system, socket or other capability is imported (ADR-057 invariant 2).
- **Memory:** the module defines and exports `memory`, with an 8 MiB shadow stack
  (`-zstack-size`). It grows as needed; the host bounds it with a page limit.
- **Plutus arena:** one bump arena, pre-allocated at 1 MB. It is not a cap: the
  arena grows by chunks as a script needs (the scenario gate re-runs every
  scenario with a 4 KiB arena and gets identical responses), bounded only by the
  host's page limit.
- **Features used:** MVP plus `bulk-memory`, `sign-ext` and `nontrapping-fptoint`.
  It uses no threads, atomics, SIMD, exception handling, tail calls or GC.
- **Panics** abort (`panic = "abort"`), which is a wasm trap. The host rejects the
  transaction (`AmaruEngineFailure`) and discards the instance (ADR-057
  invariant 4).
- **Extra exports.** The C library in `secp256k1-sys` exports four
  `rustsecp256k1_v0_10_0_*` symbols. They are not part of the interface; hosts
  ignore them.

## Exports

| Export | Signature (wasm) | Meaning |
|---|---|---|
| `abi_version` | `() -> i32` | Interface version, `1`. A host refuses a module whose version it does not support (invariant 5). |
| `amaru_version` | `() -> i32` (ptr) | Buffer holding the UTF-8 text of `AMARU_VERSION` (`tag=…`, `commit=…`, `toolchain=…` lines) followed by `crate=<version>`, this crate's `Cargo.toml` version. The crate version is bumped whenever host-visible behaviour changes without an Amaru upgrade (for example the failure-name mapping), so a host can refuse a stale module. |
| `alloc` | `(len: i32) -> i32` (ptr) | Allocates `len` bytes (zeroed) for the host to write into. |
| `dealloc` | `(ptr: i32, len: i32)` | Frees a buffer from `alloc` (the same `len`), or a returned buffer (`4 + payload length`). |
| `required_keys` | `(tx_ptr, tx_len, env_ptr, env_len: i32) -> i32` (ptr) | Runs Amaru's `prepare_transaction` and returns the keys the host must resolve. |
| `validate` | `(req_ptr, req_len: i32) -> i32` (ptr) | Validates one transaction against the state in the request. |

**Returned buffers** all have the same layout: `[u32 little-endian payload
length][payload]`.
- For `amaru_version` the payload is UTF-8. For the other exports it is a CBOR
  document.
- The host copies the payload out, then calls `dealloc(ptr, 4 + length)`.
- Input buffers are owned by the host. It frees them with `dealloc` after the
  call.

(ADR-057's table wrote `amaru_version(ptr) -> len`. Version 1 instead uses the same
returned-buffer convention as the other exports, so the host has only one calling
pattern.)

An instance is single-threaded and keeps no state between calls, apart from an
arena pool reused across `validate` calls. The host pools instances (one per
validation thread).

## Conventions

All documents are CBOR maps keyed by small unsigned integers.
- Keys may appear in any order.
- Definite and indefinite lengths are both accepted.
- Unknown keys, duplicate keys and missing required keys are errors. The module
  never ignores them.
- Byte-exact ledger values travel as **byte strings wrapping their original CBOR**
  (`bytes .cbor T`), so the host never re-encodes them: the transaction, UTxO
  outputs and protocol parameters.

```cddl
; ---- shared ------------------------------------------------------------------
hash28          = bytes .size 28
hash32          = bytes .size 32
credential      = [0, hash28] / [1, hash28]           ; key hash / script hash (Conway CDDL)
drep            = [0, hash28] / [1, hash28] / [2] / [3] ; key, script, abstain, no confidence
tx_in           = [transaction_id: hash32, index: uint .size 2]
gov_action_id   = [transaction_id: hash32, index: uint .size 4]
epoch           = uint
slot            = uint
coin            = uint
tx_pointer      = [slot, transaction_index: uint]
cert_pointer    = [slot, transaction_index: uint, certificate_index: uint] / null
                  ; null = not tracked by the host. Amaru's rules do not read it at the pinned tag.
```

## `validate` request

```cddl
validate_request = {
  0  : 1,                          ; abi_version
  1  : mode,
  2  : bytes .cbor transaction,    ; [body, witnesses, is_valid, auxiliary_data], as received
  3  : network_magic: uint,        ; 764824073 mainnet, 1 preprod, 2 preview, anything else = testnet
  4  : era_history,
  5  : global_parameters,
  6  : bytes .cbor amaru_protocol_parameters,
  ? 7: ledger_constants,          ; test-only: production hosts never send it
  8  : consecutive_dormant_epochs: uint,
  ? 9: guardrail_script_hash: hash28 / null,  ; enacted constitution's guardrails script
  10 : proposals_roots,
  11 : treasury: coin,
  12 : tx_pointer,                  ; slot of the block (or mempool tick) and index within it
  13 : [* utxo_entry],
  14 : [* account],
  15 : [* pool_id: hash28],
  16 : [* drep_entry],
  17 : [* committee_member],
  18 : [* proposal],
}

mode = 0   ; phase_one: Amaru's phase-one rules only (no Plutus evaluation)
     / 1   ; full: phase-one, then phase-two in Amaru's UPLC machine

era_history = [stability_window: uint, [+ era]]
era         = [start: era_bound, end: era_bound / null, epoch_size_slots: uint,
               slot_length_ms: uint, era_tag: uint]
               ; era_tag: 1 Byron, 2 Shelley, 3 Allegra, 4 Mary, 5 Alonzo, 6 Babbage,
               ;          7 Conway, 8 Dijkstra  (Amaru's EraName numbering)
era_bound   = [relative_time_ms: uint, slot, epoch]

global_parameters = [security_param_k: uint, epoch_length_scale_factor: uint,
                     active_slot_coeff_inverse: uint, max_lovelace_supply: coin,
                     slots_per_kes_period: uint, max_kes_evolution: uint .size 1,
                     system_start_ms: uint]            ; POSIX milliseconds

; TEST-ONLY. Haskell hardcodes these instead of storing them in protocol parameters, and
; Amaru's parameter layout omits them. Without key 7 the module uses the Haskell values
; 204800, 1048576, 25600 and 12/10, which is what production hosts must rely on: they never
; send key 7. It exists only because four Amaru scenarios move these values.
ledger_constants = {
  ? 0: max_ref_script_size_per_tx: uint,
  ? 1: max_ref_script_size_per_block: uint,
  ? 2: ref_script_cost_stride: uint,
  ? 3: ref_script_cost_multiplier: [numerator: uint, denominator: uint],
}

proposals_roots = [protocol_parameters: gov_action_id / null, hard_fork: gov_action_id / null,
                   constitutional_committee: gov_action_id / null, constitution: gov_action_id / null]

utxo_entry = [tx_in, bytes .cbor transaction_output]   ; see "Which output bytes must be exact"

account = [credential, deposit: coin, rewards: coin,
           pool_delegation: [pool_id: hash28, cert_pointer] / null,
           drep_delegation: [drep, cert_pointer] / null]

drep_entry = [credential, deposit: coin, registered_at: cert_pointer, valid_until: epoch]

committee_member = [cold: credential,
                    status: [0, hot: credential]   ; authorised a hot credential
                          / [1]                    ; resigned
                          / null,                  ; never authorised one
                    valid_until: epoch / null]     ; null = holds no term (e.g. candidate)

proposal = [gov_action_id, proposal_kind, valid_until: epoch]  ; valid_until = proposed epoch + lifetime
proposal_kind = [0, any_in_security_group: bool]   ; parameter change
              / [1, major: uint, minor: uint]      ; hard-fork initiation to that version
              / [2]                                ; no confidence / update committee
              / [3]                                ; new constitution
              / [4]                                ; treasury withdrawals
              / [5]                                ; info
```

### Protocol parameters (`amaru_protocol_parameters`)

Key 6 carries Amaru's own CBOR layout of `amaru_kernel::ProtocolParameters`. This
is the `cbor::Decode` impl in `crates/amaru-kernel/src/cardano/protocol_parameters.rs`
at the pinned tag. It is **not** the Conway CDDL `protocol_param_update` map. It is
a definite array of 31 items, in this order:

```cddl
amaru_protocol_parameters = [
  min_fee_a: uint, min_fee_b: uint, max_block_body_size: uint, max_transaction_size: uint,
  max_block_header_size: uint .size 2, stake_credential_deposit: coin, stake_pool_deposit: coin,
  stake_pool_max_retirement_epoch: uint, optimal_stake_pools_count: uint .size 2,
  pledge_influence: rational, monetary_expansion_rate: rational, treasury_expansion_rate: rational,
  protocol_version: [major: uint, minor: uint],
  min_pool_cost: coin, lovelace_per_utxo_byte: coin,
  cost_models: { ? 0: [* int], ? 1: [* int], ? 2: [* int] },   ; Plutus V1, V2, V3 (definite map)
  prices: [mem_price: rational, step_price: rational],
  max_tx_ex_units: [mem: uint, steps: uint], max_block_ex_units: [mem: uint, steps: uint],
  max_value_size: uint, collateral_percentage: uint .size 2, max_collateral_inputs: uint .size 2,
  pool_voting_thresholds: [5 * rational], drep_voting_thresholds: [10 * rational],
  min_committee_size: uint .size 2, max_committee_term_length: uint, gov_action_lifetime: uint,
  gov_action_deposit: coin, drep_deposit: coin, drep_expiry: uint,
  min_fee_ref_script_cost_per_byte: rational,
]
rational = #6.30([numerator: uint, denominator: uint])   ; tag 30 optional
```

The field order follows the Conway `protocol_param_update` key order, and the
threshold groups keep their CDDL order. The five `.size 2` fields are `u16` in
Amaru and fail to decode above 65535. Amaru's decoder panics on a cost-model
language other than 0, 1 or 2, so the module checks the cost-model keys first and
answers `error` instead of trapping. The Phase B golden tests pin the encoding
from the Java side. An Amaru bump that changes this layout fails the scenario gate
here (`protocol parameters do not survive the interface encoding`) instead of
mis-decoding.

### State slices and absence

The host resolves every key named by `required_keys` through its `LedgerView`,
using ADR-056's three-outcome `Lookup`:
- **Present:** the entry is in the slice.
- **Confirmed absent:** the key is left out. This is exactly how Amaru's own
  preparation represents a pool registering for the first time, an unregistered
  account, and so on. The rules report the typed ledger failure themselves.
- **Unavailable:** the host does not call `validate`
  (`LedgerStateUnavailable`).

Duplicate keys in any slice make the request an `error`.

**`required_keys` is sufficient.** The scenario gate rebuilds every scenario's
request with the UTxO, account, pool and DRep slices restricted to exactly the keys
`required_keys` returned (committee and proposals whole), and requires a response
identical to the unrestricted one. The corpus fixtures are minimal, so this shows
that nothing a scenario's verdict depends on is missing from the key set.

### Which output bytes must be exact

Yano's `LedgerView` holds decoded outputs, so a host may have to re-encode a UTxO
output. Amaru (at the pinned tag) decodes each output into a
`MemoizedTransactionOutput` and depends on original bytes in exactly these places:

| Part of the resolved output | Must be byte-exact? | Why |
|---|---|---|
| Reference script (`script_ref`, tag 24) | **Yes**: the script's own bytes | `MemoizedScript::len()` is the reference-script size (`ConwayTxRefScriptsSizeTooBig`, and the reference-script fee inside `FeeTooSmallUTxO`). The script hash, which witnesses and redeemers are matched against, is computed over those bytes. For a native script that means its **CBOR encoding**; for Plutus, the flat bytes inside the byte string. The tag-24 wrapper around them may be re-encoded. |
| Inline datum | **Yes**: the datum's CBOR | `MemoizedPlutusData` keeps its original bytes, and the datum hash is computed over them: supplemental-datum acknowledgement, `MissingRequiredDatums` / `NotAllowedSupplementalDatums`, and the Plutus V1/V2 script context. A re-encoded datum (definite vs indefinite lengths, integer width) changes the hash. |
| Datum hash | No (it is 32 bytes either way) | |
| Address | No, as long as the address **bytes** are unchanged | Only the decoded address is used (payment credential, network, bootstrap root). |
| Value (coin and multi-asset) | No, if semantically equal | Only the decoded value is used (balance, collateral, Plutus `TxInfo`). |
| Output envelope (legacy array vs post-Alonzo map, map key order, lengths) | No | `original_size` is only read for the transaction's **own** outputs (minimum-UTxO), which come from the transaction bytes. Amaru's own UTxO store re-encodes resolved outputs this way too. |

Hosts should still send the stored on-chain bytes whenever they have them. A host
that must re-encode has to carry the inline datum's and the reference script's
original bytes through unchanged.

Some slices are shipped in full, whatever `required_keys` returned (ADR-057 §1):
- **`committee`**: every member, with cold credential, status and expiry. It also
  includes each candidate of a pending `UpdateCommittee` proposal as
  `[cold, null, null]`. Amaru's preparation materialises the same set: votes name
  members by hot credential, and candidates may authorise or resign before
  election.
- **`proposals`**: all active proposals. `proposals_roots` is always present.

## `validate` response

```cddl
validate_response = ok / invalid / error

ok      = { 0: 0 }
invalid = {
  0  : 1,
  1  : phase,                   ; 0 decode, 1 phase one, 2 phase two
  2  : rule: tstr,              ; UTXO UTXOW UTXOS LEDGER CERTS DELEG POOL GOVCERT GOV, or DECODE
  ? 3: constructor: tstr,       ; Haskell predicate-failure name, see below
  4  : amaru_error: tstr,       ; Amaru variant path, e.g. "PhaseOne.Certificates.StakePoolUnknown"
  5  : detail: tstr,            ; Amaru's message (truncated to 4 KiB)
  ? 6: tag_mismatch: "PassedUnexpectedly" / "FailedUnexpectedly",  ; with ValidationTagMismatch
}
error   = { 0: 2, 7: message: tstr }  ; the request could not be processed: says nothing about the tx
```

- **`constructor`** is the Haskell predicate-failure constructor at the
  `cardano-ledger` revision ADR-056 pins (`f649f975`;
  `adr/reports/adr-056-haskell-pinned-revisions.md`, 3d-table). `rule` is the
  rule that reports it.
  - `src/failure.rs` maps every Amaru error variant and sub-variant explicitly, with
    no catch-all, so a new Amaru variant is a compile error until it is mapped.
  - **PV-gated names** follow the request's protocol version:
    - `WithdrawalsNotInRewardsCERTS` (PV ≤ 10) vs `ConwayWithdrawalsMissingAccounts`
      / `ConwayIncompleteWithdrawals` (LEDGER, PV ≥ 11);
    - `IncorrectDepositDELEG` (PV ≤ 10) vs `DepositIncorrectDELEG` /
      `RefundIncorrectDELEG` (PV ≥ 11);
    - `PPViewHashesDontMatch` (PV ≤ 10) vs `ScriptIntegrityHashMismatch` (PV ≥ 11).
  - **Deposit or refund.** Amaru reports a wrong stake or DRep deposit and a wrong
    refund with one variant each. The module tells them apart from the
    transaction's certificates (the first registration or unregistration moving
    that amount): `DepositIncorrectDELEG` / `RefundIncorrectDELEG`,
    `ConwayDRepIncorrectDeposit` / `ConwayDRepIncorrectRefund`.
  - **Amaru's corpus spells some names differently.** The fixture test keeps an
    explicit corpus-to-Haskell alias table and checks phase, rule and constructor.
    The differences are:

    | Corpus name | Haskell (rule.constructor) |
    |---|---|
    | `StakeKeyRegistered` | `DELEG.StakeKeyRegisteredDELEG` |
    | `StakeKeyHasNonZeroAccountBalance` | `DELEG.StakeKeyHasNonZeroAccountBalanceDELEG` |
    | `StakeCredentialInvalidPoolDelegation`, `StakeCredentialInvalidVoteDelegation` | `DELEG.StakeKeyNotRegisteredDELEG` |
    | `DelegateeDRepNotRegistered`, `DelegateeStakePoolNotRegistered` | `DELEG.…DELEG` |
    | `DRepAlreadyRegistered`, `CommitteeIsUnknown`, `CommitteeHasPreviouslyResigned` | `GOVCERT.Conway…` |
    | `MissingVerificationKeyWitnessesUTXOW` | `UTXOW.MissingVKeyWitnessesUTXOW` |
    | `TreasuryWithdrawalsAllZeros` | `GOV.ZeroTreasuryWithdrawals` |
    | `WrongNetworkInTxOutput` | `UTXO.WrongNetwork` |

  - Amaru's `InvalidOutput::BootAddrAttrsTooBig` maps to `OutputTooBigUTxO`, which
    is what Amaru's Haskell-checked corpus observes.
- **Missing `constructor`.** It is absent only where Haskell has no leaf
  constructor for the condition: era-history arithmetic failures, and preparation
  states that cannot occur once phase one passed. `rule` still names the rule,
  and `amaru_error` identifies the variant.
- **Several failures.** Amaru stops at the first failing rule. Where Haskell would
  report several failures, only the first is returned.
- **`phase` 0 (`DECODE`)** means the transaction bytes did not decode as a Conway
  transaction under the request's protocol version. Trailing bytes count as a
  decode failure.

## `required_keys`

`tx` is the transaction CBOR, as for `validate`. `env` is required, and trailing
bytes after it are an error:

```cddl
keys_env = { ? 0: 1,                          ; abi_version
             1: [major: uint, minor: uint] }  ; decode the tx under this protocol version
                                              ; (the one the validate request will carry)

required_keys_response = {
  0: 0,
  1: [* tx_in],             ; spent, reference and collateral inputs
  2: [* credential],        ; accounts: withdrawals, certificates, proposal return and treasury-withdrawal accounts
  3: [* hash28],            ; pools: certificates, delegations, SPO voters
  4: [* credential],        ; DReps: certificates, voters, and key/script delegation targets
  5: [* credential],        ; committee members named by cold credential (certificates)
  6: [* credential],        ; committee members named by hot credential (votes)
  7: [* gov_action_id],     ; proposals voted on or chained to
} / { 0: 2, 8: message: tstr }   ; malformed env, or the transaction does not decode
```

- Keys 5–7 are informational: the request always carries the full committee and
  all proposals.
- DRep delegation targets are resolved as DReps. This matches Amaru's
  `into_validation_context`.

## Modes and ADR-057 open question 1

Amaru's `rules::transaction::phase_one::execute` **is public** at
`v10.11.20260925`, so `mode = phase_one` runs phase one alone. No upstream change
or fork is needed.

**Limitation.** Amaru runs several phase-1 checks inside its phase-two
preparation (`phase_two::execute`), which phase-one mode does not call. Phase-one
mode therefore does not report:
- `UTXOW.MalformedScriptWitnesses`: Plutus script witnesses that do not decode
  (scenarios 00256–00258);
- `UTXOS.CollectErrors` with `NoCostModel`: a script language with no cost model
  (`PreparationError::MissingCostModel`). Haskell reports it before any script
  runs;
- `UTXOS.CollectErrors` with `BadTranslation`: `TxInfo` translation failures,
  including Amaru's Plutus V3 check that spending and reference inputs are
  disjoint (`phase_two/mod.rs`, the `NonDisjointRefInputs` preparation error). At
  PV 10 the UTXO rule's `BabbageNonDisjointRefInputs` fires first, and phase-one
  mode does report that.

In `phase2: scalus` mode (ADR-057 Phase B), the host's phase-2 engine must report
all three with these names, as phase-1 failures, before running any script. In
the scenario gate, phase-one mode matches full mode on every scenario, except
where full mode's verdict came from that phase-two code.

## Interface invariants for hosts

1. **Conway only (ADR-057 invariant 6).** The host refuses protocol versions below
   10 before building a request. The module validates whatever Amaru accepts;
   Amaru's corpus even includes a hard-fork proposal made at version 9.0.
2. **One snapshot per request.** Build each request from one ADR-056 canonical
   snapshot plus overlay (invariant 3).
3. **Bumping `abi_version`.** Any change to these schemas bumps `abi_version`. An
   Amaru bump that leaves them intact does not.
