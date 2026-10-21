//! Interface version 1: the CBOR request and response documents exchanged with the host.
//!
//! The schema is owned by Yano, not by Amaru, and is specified (with CDDL) in `INTERFACE.md`.
//! Every document is a CBOR map with small unsigned integer keys. Unknown keys are rejected, so an
//! encoder bug fails loudly instead of being ignored. Byte-exact ledger values (the transaction,
//! UTxO outputs, protocol parameters) travel as byte strings wrapping their CBOR, so the host never
//! has to re-encode them.

use std::{collections::BTreeMap, time::Duration};

use amaru_kernel::{
    CertificatePointer, ConstitutionalCommitteeMemberStatus, Credential, DRep, DRepRegistration, Epoch, EraBound,
    EraHistory, EraName, EraParams, EraSummary, GlobalParameters, Hash, Lovelace, PoolId, ProposalId, ProposalSlim,
    ProposalsRoots, ProtocolVersion, RationalNumber, Slot, TransactionInput, TransactionPointer,
    cbor::{self, Decoder, Encoder, data::Type},
    size::SCRIPT,
};
use amaru_ledger::context::{AccountState, CCMember, ProposalStateSlim};

/// The interface version implemented by this module; returned by the `abi_version` export and
/// carried in every request.
pub const ABI_VERSION: u32 = 1;

pub type DecodeError = cbor::decode::Error;

// ------------------------------------------------------------------------------------------ request

/// How much of Amaru's validation to run.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    /// Amaru's phase-one rules only (`rules::transaction::phase_one::execute`). Plutus scripts are
    /// not run, so the host runs phase-2 itself.
    PhaseOne = 0,
    /// Amaru's full `rules::block::validate_transaction`: phase-one, then phase-two through Amaru's
    /// own UPLC machine.
    Full = 1,
}

/// Values the Haskell ledger hardcodes instead of reading from protocol parameters. Amaru's CBOR
/// layout for [`ProtocolParameters`](amaru_kernel::ProtocolParameters) does not carry them; each
/// one left out keeps Amaru's (= Haskell's) constant.
///
/// Test-only: production hosts never send these (request key 7). They exist because four of Amaru's
/// scenarios move the constants.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct LedgerConstants {
    pub max_ref_script_size_per_tx: Option<u32>,
    pub max_ref_script_size_per_block: Option<u32>,
    pub ref_script_cost_stride: Option<u32>,
    pub ref_script_cost_multiplier: Option<RationalNumber>,
}

/// One `validate` request: everything the transaction needs, resolved by the host from a single
/// ledger snapshot plus overlay.
///
/// Absence is expressed by omission: a key that the host looked up and confirmed absent is simply
/// not in the corresponding slice (ADR-057 invariant 3).
#[derive(Debug, Clone)]
pub struct Request {
    pub mode: Mode,
    /// The full transaction, `[body, witnesses, is_valid, auxiliary_data]`, as received.
    pub transaction: Vec<u8>,
    pub network_magic: u32,
    pub era_history: EraHistoryWire,
    pub global_parameters: GlobalParameters,
    /// Amaru's CBOR layout of `ProtocolParameters` (see INTERFACE.md).
    pub protocol_parameters: Vec<u8>,
    pub ledger_constants: LedgerConstants,
    pub consecutive_dormant_epochs: u32,
    pub guardrail_script: Option<Hash<SCRIPT>>,
    pub proposals_roots: ProposalsRoots,
    pub treasury: Lovelace,
    pub pointer: TransactionPointer,
    /// Resolved UTxO entries: input → the output's CBOR bytes, exactly as stored on chain.
    pub utxo: Vec<(TransactionInput, Vec<u8>)>,
    pub accounts: Vec<(Credential, AccountState)>,
    pub pools: Vec<PoolId>,
    pub dreps: Vec<(Credential, DRepRegistration)>,
    pub committee: Vec<(Credential, CCMember)>,
    pub proposals: Vec<(ProposalId, ProposalStateSlim)>,
}

/// An era history as carried on the wire (Amaru keeps the stability window private).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EraHistoryWire {
    pub stability_window: u64,
    pub eras: Vec<EraSummary>,
}

impl EraHistoryWire {
    pub fn to_era_history(&self) -> EraHistory {
        EraHistory::new(&self.eras, Slot::new(self.stability_window))
    }
}

mod key {
    pub const ABI_VERSION: u64 = 0;
    pub const MODE: u64 = 1;
    pub const TRANSACTION: u64 = 2;
    pub const NETWORK_MAGIC: u64 = 3;
    pub const ERA_HISTORY: u64 = 4;
    pub const GLOBAL_PARAMETERS: u64 = 5;
    pub const PROTOCOL_PARAMETERS: u64 = 6;
    pub const LEDGER_CONSTANTS: u64 = 7;
    pub const GOVERNANCE_ACTIVITY: u64 = 8;
    pub const GUARDRAIL_SCRIPT: u64 = 9;
    pub const PROPOSALS_ROOTS: u64 = 10;
    pub const TREASURY: u64 = 11;
    pub const POINTER: u64 = 12;
    pub const UTXO: u64 = 13;
    pub const ACCOUNTS: u64 = 14;
    pub const POOLS: u64 = 15;
    pub const DREPS: u64 = 16;
    pub const COMMITTEE: u64 = 17;
    pub const PROPOSALS: u64 = 18;
}

type EncodeResult = Result<(), cbor::encode::Error<std::convert::Infallible>>;
type Enc<'a> = Encoder<&'a mut Vec<u8>>;

impl Request {
    /// Encode as a v1 request document. This is the reference encoder: hosts must produce
    /// byte-for-byte the same structure (field order does not matter, key set does).
    pub fn to_cbor(&self) -> Vec<u8> {
        let mut buffer = Vec::new();
        // Writing into a Vec cannot fail.
        #[allow(clippy::expect_used)]
        self.encode(&mut Encoder::new(&mut buffer)).expect("infallible");
        buffer
    }

    fn encode(&self, e: &mut Enc<'_>) -> EncodeResult {
        let optional = [self.guardrail_script.is_some(), self.ledger_constants != LedgerConstants::default()]
            .iter()
            .filter(|present| **present)
            .count() as u64;
        e.map(17 + optional)?;

        e.u64(key::ABI_VERSION)?.u32(ABI_VERSION)?;
        e.u64(key::MODE)?.u8(self.mode as u8)?;
        e.u64(key::TRANSACTION)?.bytes(&self.transaction)?;
        e.u64(key::NETWORK_MAGIC)?.u32(self.network_magic)?;

        e.u64(key::ERA_HISTORY)?;
        encode_era_history(e, &self.era_history)?;

        e.u64(key::GLOBAL_PARAMETERS)?;
        encode_global_parameters(e, &self.global_parameters)?;

        e.u64(key::PROTOCOL_PARAMETERS)?.bytes(&self.protocol_parameters)?;

        if self.ledger_constants != LedgerConstants::default() {
            e.u64(key::LEDGER_CONSTANTS)?;
            encode_ledger_constants(e, &self.ledger_constants)?;
        }

        e.u64(key::GOVERNANCE_ACTIVITY)?.u32(self.consecutive_dormant_epochs)?;

        if let Some(hash) = &self.guardrail_script {
            e.u64(key::GUARDRAIL_SCRIPT)?.bytes(hash.as_ref())?;
        }

        e.u64(key::PROPOSALS_ROOTS)?;
        encode_proposals_roots(e, &self.proposals_roots)?;

        e.u64(key::TREASURY)?.u64(self.treasury)?;

        e.u64(key::POINTER)?;
        encode_transaction_pointer(e, &self.pointer)?;

        e.u64(key::UTXO)?.array(self.utxo.len() as u64)?;
        for (input, output) in &self.utxo {
            e.array(2)?;
            encode_input(e, input)?;
            e.bytes(output)?;
        }

        e.u64(key::ACCOUNTS)?.array(self.accounts.len() as u64)?;
        for (credential, account) in &self.accounts {
            encode_account(e, credential, account)?;
        }

        e.u64(key::POOLS)?.array(self.pools.len() as u64)?;
        for pool in &self.pools {
            e.bytes(pool.as_ref())?;
        }

        e.u64(key::DREPS)?.array(self.dreps.len() as u64)?;
        for (credential, drep) in &self.dreps {
            e.array(4)?;
            encode_credential(e, credential)?;
            e.u64(drep.deposit)?;
            encode_certificate_pointer(e, &drep.registered_at)?;
            e.u64(drep.valid_until.as_u64())?;
        }

        e.u64(key::COMMITTEE)?.array(self.committee.len() as u64)?;
        for (cold, member) in &self.committee {
            e.array(3)?;
            encode_credential(e, cold)?;
            match &member.status {
                None => {
                    e.null()?;
                }
                Some(ConstitutionalCommitteeMemberStatus::DelegatedToHotCredential(hot)) => {
                    e.array(2)?.u8(0)?;
                    encode_credential(e, hot)?;
                }
                Some(ConstitutionalCommitteeMemberStatus::Resigned) => {
                    e.array(1)?.u8(1)?;
                }
            }
            match &member.valid_until {
                None => e.null()?,
                Some(epoch) => e.u64(epoch.as_u64())?,
            };
        }

        e.u64(key::PROPOSALS)?.array(self.proposals.len() as u64)?;
        for (id, proposal) in &self.proposals {
            e.array(3)?;
            encode_proposal_id(e, id)?;
            encode_proposal_kind(e, &proposal.action)?;
            e.u64(proposal.valid_until.as_u64())?;
        }

        Ok(())
    }

    /// Decode a v1 request document.
    pub fn from_cbor(bytes: &[u8]) -> Result<Self, DecodeError> {
        let mut d = Decoder::new(bytes);
        let request = Self::decode(&mut d)?;
        if d.position() != bytes.len() {
            return Err(DecodeError::message("trailing bytes after the request document"));
        }
        Ok(request)
    }

    fn decode(d: &mut Decoder<'_>) -> Result<Self, DecodeError> {
        let mut abi_version = None;
        let mut mode = None;
        let mut transaction = None;
        let mut network_magic = None;
        let mut era_history = None;
        let mut global_parameters = None;
        let mut protocol_parameters = None;
        let mut ledger_constants = LedgerConstants::default();
        let mut consecutive_dormant_epochs = None;
        let mut guardrail_script = None;
        let mut proposals_roots = None;
        let mut treasury = None;
        let mut pointer = None;
        let mut utxo = None;
        let mut accounts = None;
        let mut pools = None;
        let mut dreps = None;
        let mut committee = None;
        let mut proposals = None;

        for_each_map_entry(d, |d, k| {
            match k {
                key::ABI_VERSION => abi_version = Some(d.u32()?),
                key::MODE => {
                    mode = Some(match d.u8()? {
                        0 => Mode::PhaseOne,
                        1 => Mode::Full,
                        other => return Err(DecodeError::message(format!("unknown mode {other}"))),
                    })
                }
                key::TRANSACTION => transaction = Some(d.bytes()?.to_vec()),
                key::NETWORK_MAGIC => network_magic = Some(d.u32()?),
                key::ERA_HISTORY => era_history = Some(decode_era_history(d)?),
                key::GLOBAL_PARAMETERS => global_parameters = Some(decode_global_parameters(d)?),
                key::PROTOCOL_PARAMETERS => protocol_parameters = Some(d.bytes()?.to_vec()),
                key::LEDGER_CONSTANTS => ledger_constants = decode_ledger_constants(d)?,
                key::GOVERNANCE_ACTIVITY => consecutive_dormant_epochs = Some(d.u32()?),
                key::GUARDRAIL_SCRIPT => guardrail_script = decode_nullable(d, decode_hash28)?,
                key::PROPOSALS_ROOTS => proposals_roots = Some(decode_proposals_roots(d)?),
                key::TREASURY => treasury = Some(d.u64()?),
                key::POINTER => pointer = Some(decode_transaction_pointer(d)?),
                key::UTXO => {
                    utxo = Some(decode_array(d, |d| {
                        expect_array(d, 2)?;
                        Ok((decode_input(d)?, d.bytes()?.to_vec()))
                    })?)
                }
                key::ACCOUNTS => accounts = Some(decode_array(d, decode_account)?),
                key::POOLS => pools = Some(decode_array(d, decode_hash28)?),
                key::DREPS => {
                    dreps = Some(decode_array(d, |d| {
                        expect_array(d, 4)?;
                        let credential = decode_credential(d)?;
                        let deposit = d.u64()?;
                        let registered_at = decode_certificate_pointer(d)?;
                        let valid_until = Epoch::new(d.u64()?);
                        Ok((credential, DRepRegistration { deposit, registered_at, valid_until }))
                    })?)
                }
                key::COMMITTEE => {
                    committee = Some(decode_array(d, |d| {
                        expect_array(d, 3)?;
                        let cold = decode_credential(d)?;
                        let status = decode_nullable(d, |d| {
                            let len = d.array()?;
                            match (d.u8()?, len) {
                                (0, Some(2)) => Ok(ConstitutionalCommitteeMemberStatus::DelegatedToHotCredential(
                                    decode_credential(d)?,
                                )),
                                (1, Some(1)) => Ok(ConstitutionalCommitteeMemberStatus::Resigned),
                                (tag, len) => Err(DecodeError::message(format!(
                                    "invalid committee member status: tag {tag}, length {len:?}"
                                ))),
                            }
                        })?;
                        let valid_until = decode_nullable(d, |d| Ok(Epoch::new(d.u64()?)))?;
                        Ok((cold, CCMember { status, valid_until }))
                    })?)
                }
                key::PROPOSALS => {
                    proposals = Some(decode_array(d, |d| {
                        expect_array(d, 3)?;
                        let id = decode_proposal_id(d)?;
                        let action = decode_proposal_kind(d)?;
                        let valid_until = Epoch::new(d.u64()?);
                        Ok((id, ProposalStateSlim { action, valid_until }))
                    })?)
                }
                other => return Err(DecodeError::message(format!("unknown request key {other}"))),
            }
            Ok(())
        })?;

        let abi_version = required(abi_version, "abi_version")?;
        if abi_version != ABI_VERSION {
            return Err(DecodeError::message(format!(
                "unsupported abi_version {abi_version}; this module implements {ABI_VERSION}"
            )));
        }

        Ok(Request {
            mode: required(mode, "mode")?,
            transaction: required(transaction, "transaction")?,
            network_magic: required(network_magic, "network_magic")?,
            era_history: required(era_history, "era_history")?,
            global_parameters: required(global_parameters, "global_parameters")?,
            protocol_parameters: required(protocol_parameters, "protocol_parameters")?,
            ledger_constants,
            consecutive_dormant_epochs: required(consecutive_dormant_epochs, "governance_activity")?,
            guardrail_script,
            proposals_roots: required(proposals_roots, "proposals_roots")?,
            treasury: required(treasury, "treasury")?,
            pointer: required(pointer, "transaction_pointer")?,
            utxo: required(utxo, "utxo")?,
            accounts: required(accounts, "accounts")?,
            pools: required(pools, "pools")?,
            dreps: required(dreps, "dreps")?,
            committee: required(committee, "committee")?,
            proposals: required(proposals, "proposals")?,
        })
    }
}

// ------------------------------------------------------------------------------ required_keys env

/// The `env` argument of `required_keys`: `{ ? 0: abi_version, 1: [major, minor] }`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KeysEnv {
    /// Protocol version the transaction is decoded under (the one `validate` will use).
    pub protocol_version: ProtocolVersion,
}

impl KeysEnv {
    pub fn to_cbor(&self) -> Vec<u8> {
        let mut buffer = Vec::new();
        let mut e = Encoder::new(&mut buffer);
        #[allow(clippy::expect_used)]
        (|| -> EncodeResult {
            e.map(2)?;
            e.u64(0)?.u32(ABI_VERSION)?;
            e.u64(1)?.array(2)?.u64(self.protocol_version.major())?.u64(self.protocol_version.minor())?;
            Ok(())
        })()
        .expect("infallible");
        buffer
    }

    pub fn from_cbor(bytes: &[u8]) -> Result<Self, DecodeError> {
        let mut d = Decoder::new(bytes);
        let mut protocol_version = None;
        for_each_map_entry(&mut d, |d, k| {
            match k {
                0 => {
                    let version = d.u32()?;
                    if version != ABI_VERSION {
                        return Err(DecodeError::message(format!("unsupported abi_version {version}")));
                    }
                }
                1 => {
                    expect_array(d, 2)?;
                    protocol_version = Some(ProtocolVersion::new(d.u64()?, d.u64()?));
                }
                other => return Err(DecodeError::message(format!("unknown env key {other}"))),
            }
            Ok(())
        })?;
        if d.position() != bytes.len() {
            return Err(DecodeError::message("trailing bytes after the env document"));
        }
        Ok(KeysEnv { protocol_version: required(protocol_version, "protocol_version")? })
    }
}

/// The key set returned by `required_keys`: what Amaru's `prepare_transaction` asks for.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RequiredKeys {
    /// Spent, reference and collateral inputs.
    pub inputs: Vec<TransactionInput>,
    pub accounts: Vec<Credential>,
    pub pools: Vec<PoolId>,
    /// DReps named by certificates or votes, plus the key/script DReps that delegations point to.
    pub dreps: Vec<Credential>,
    /// Committee members named by cold credential (certificates).
    pub committee_cold: Vec<Credential>,
    /// Committee members named by hot credential (votes).
    pub committee_hot: Vec<Credential>,
    pub proposals: Vec<ProposalId>,
}

// ----------------------------------------------------------------------------------------- response

/// Which part of validation rejected the transaction.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Phase {
    /// The transaction bytes did not decode as a Conway transaction.
    Decode = 0,
    PhaseOne = 1,
    PhaseTwo = 2,
}

/// A ledger rejection, named after the Haskell rule and predicate failure where known.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Invalid {
    pub phase: Phase,
    /// Haskell rule: `UTXO`, `UTXOW`, `UTXOS`, `LEDGER`, `CERTS`, `DELEG`, `POOL`, `GOVCERT`, `GOV`,
    /// or `DECODE` for undecodable transactions.
    pub rule: String,
    /// Haskell predicate-failure constructor at ADR-056's pinned `cardano-ledger` revision, chosen
    /// for the request's protocol version. `None` only where Haskell has no leaf constructor for
    /// the condition (see `failure.rs`).
    pub constructor: Option<String>,
    /// Amaru's own error variant path, for diagnostics (not a contract).
    pub amaru_error: String,
    /// Human-readable detail (Amaru's `Display`).
    pub detail: String,
    /// For `ValidationTagMismatch`: `PassedUnexpectedly` or `FailedUnexpectedly`.
    pub tag_mismatch: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Response {
    Ok,
    Invalid(Invalid),
    /// The request itself could not be processed (malformed request, unsupported version, ...). This
    /// says nothing about the transaction; the host treats it as an engine failure.
    Error(String),
}

impl Response {
    pub fn to_cbor(&self) -> Vec<u8> {
        let mut buffer = Vec::new();
        let mut e = Encoder::new(&mut buffer);
        #[allow(clippy::expect_used)]
        (|| -> EncodeResult {
            match self {
                Response::Ok => {
                    e.map(1)?.u8(0)?.u8(0)?;
                }
                Response::Invalid(invalid) => {
                    let entries =
                        5 + u64::from(invalid.constructor.is_some()) + u64::from(invalid.tag_mismatch.is_some());
                    e.map(entries)?;
                    e.u8(0)?.u8(1)?;
                    e.u8(1)?.u8(invalid.phase as u8)?;
                    e.u8(2)?.str(&invalid.rule)?;
                    if let Some(constructor) = &invalid.constructor {
                        e.u8(3)?.str(constructor)?;
                    }
                    e.u8(4)?.str(&invalid.amaru_error)?;
                    e.u8(5)?.str(&invalid.detail)?;
                    if let Some(tag_mismatch) = &invalid.tag_mismatch {
                        e.u8(6)?.str(tag_mismatch)?;
                    }
                }
                Response::Error(message) => {
                    e.map(2)?.u8(0)?.u8(2)?.u8(7)?.str(message)?;
                }
            }
            Ok(())
        })()
        .expect("infallible");
        buffer
    }

    pub fn from_cbor(bytes: &[u8]) -> Result<Self, DecodeError> {
        let mut d = Decoder::new(bytes);
        let mut status = None;
        let mut phase = None;
        let mut rule = None;
        let mut constructor = None;
        let mut amaru_error = None;
        let mut detail = None;
        let mut tag_mismatch = None;
        let mut message = None;
        for_each_map_entry(&mut d, |d, k| {
            match k {
                0 => status = Some(d.u8()?),
                1 => {
                    phase = Some(match d.u8()? {
                        0 => Phase::Decode,
                        1 => Phase::PhaseOne,
                        2 => Phase::PhaseTwo,
                        other => return Err(DecodeError::message(format!("unknown phase {other}"))),
                    })
                }
                2 => rule = Some(d.str()?.to_string()),
                3 => constructor = Some(d.str()?.to_string()),
                4 => amaru_error = Some(d.str()?.to_string()),
                5 => detail = Some(d.str()?.to_string()),
                6 => tag_mismatch = Some(d.str()?.to_string()),
                7 => message = Some(d.str()?.to_string()),
                other => return Err(DecodeError::message(format!("unknown response key {other}"))),
            }
            Ok(())
        })?;
        match required(status, "status")? {
            0 => Ok(Response::Ok),
            1 => Ok(Response::Invalid(Invalid {
                phase: required(phase, "phase")?,
                rule: required(rule, "rule")?,
                constructor,
                amaru_error: required(amaru_error, "amaru_error")?,
                detail: required(detail, "detail")?,
                tag_mismatch,
            })),
            2 => Ok(Response::Error(required(message, "message")?)),
            other => Err(DecodeError::message(format!("unknown status {other}"))),
        }
    }
}

impl RequiredKeys {
    pub fn to_cbor(&self) -> Vec<u8> {
        let mut buffer = Vec::new();
        let mut e = Encoder::new(&mut buffer);
        #[allow(clippy::expect_used)]
        (|| -> EncodeResult {
            e.map(8)?;
            e.u8(0)?.u8(0)?;
            e.u8(1)?.array(self.inputs.len() as u64)?;
            for input in &self.inputs {
                encode_input(&mut e, input)?;
            }
            for (k, credentials) in
                [(2u8, &self.accounts), (4, &self.dreps), (5, &self.committee_cold), (6, &self.committee_hot)]
            {
                e.u8(k)?.array(credentials.len() as u64)?;
                for credential in credentials {
                    encode_credential(&mut e, credential)?;
                }
            }
            e.u8(3)?.array(self.pools.len() as u64)?;
            for pool in &self.pools {
                e.bytes(pool.as_ref())?;
            }
            e.u8(7)?.array(self.proposals.len() as u64)?;
            for id in &self.proposals {
                encode_proposal_id(&mut e, id)?;
            }
            Ok(())
        })()
        .expect("infallible");
        buffer
    }

    /// Decode a `required_keys` response. Returns `Err` with the module's message when the module
    /// answered with an error document.
    pub fn from_cbor(bytes: &[u8]) -> Result<Result<Self, String>, DecodeError> {
        let mut d = Decoder::new(bytes);
        let mut status = None;
        let mut message = None;
        let mut keys = RequiredKeys::default();
        for_each_map_entry(&mut d, |d, k| {
            match k {
                0 => status = Some(d.u8()?),
                1 => keys.inputs = decode_array(d, decode_input)?,
                2 => keys.accounts = decode_array(d, decode_credential)?,
                3 => keys.pools = decode_array(d, decode_hash28)?,
                4 => keys.dreps = decode_array(d, decode_credential)?,
                5 => keys.committee_cold = decode_array(d, decode_credential)?,
                6 => keys.committee_hot = decode_array(d, decode_credential)?,
                7 => keys.proposals = decode_array(d, decode_proposal_id)?,
                8 => message = Some(d.str()?.to_string()),
                other => return Err(DecodeError::message(format!("unknown required_keys key {other}"))),
            }
            Ok(())
        })?;
        match required(status, "status")? {
            0 => Ok(Ok(keys)),
            2 => Ok(Err(required(message, "message")?)),
            other => Err(DecodeError::message(format!("unknown status {other}"))),
        }
    }

    pub fn error_to_cbor(message: &str) -> Vec<u8> {
        let mut buffer = Vec::new();
        let mut e = Encoder::new(&mut buffer);
        #[allow(clippy::expect_used)]
        e.map(2).and_then(|e| e.u8(0)?.u8(2)?.u8(8)?.str(message)).map(|_| ()).expect("infallible");
        buffer
    }
}

// ------------------------------------------------------------------------------------ leaf codecs

fn required<T>(value: Option<T>, field: &str) -> Result<T, DecodeError> {
    value.ok_or_else(|| DecodeError::message(format!("missing required field `{field}`")))
}

fn expect_array(d: &mut Decoder<'_>, expected: u64) -> Result<(), DecodeError> {
    match d.array()? {
        Some(len) if len == expected => Ok(()),
        len => Err(DecodeError::message(format!("expected a definite array of {expected} elements, got {len:?}"))),
    }
}

fn for_each_map_entry<'b>(
    d: &mut Decoder<'b>,
    mut f: impl FnMut(&mut Decoder<'b>, u64) -> Result<(), DecodeError>,
) -> Result<(), DecodeError> {
    let mut seen = std::collections::BTreeSet::new();
    let mut visit = |d: &mut Decoder<'b>| -> Result<(), DecodeError> {
        let k = d.u64()?;
        if !seen.insert(k) {
            return Err(DecodeError::message(format!("duplicate key {k}")));
        }
        f(d, k)
    };
    match d.map()? {
        Some(len) => {
            for _ in 0..len {
                visit(d)?;
            }
        }
        None => {
            while d.datatype()? != Type::Break {
                visit(d)?;
            }
            d.skip()?;
        }
    }
    Ok(())
}

fn decode_array<'b, T>(
    d: &mut Decoder<'b>,
    mut item: impl FnMut(&mut Decoder<'b>) -> Result<T, DecodeError>,
) -> Result<Vec<T>, DecodeError> {
    let mut items = Vec::new();
    match d.array()? {
        Some(len) => {
            for _ in 0..len {
                items.push(item(d)?);
            }
        }
        None => {
            while d.datatype()? != Type::Break {
                items.push(item(d)?);
            }
            d.skip()?;
        }
    }
    Ok(items)
}

fn decode_nullable<'b, T>(
    d: &mut Decoder<'b>,
    item: impl FnOnce(&mut Decoder<'b>) -> Result<T, DecodeError>,
) -> Result<Option<T>, DecodeError> {
    if d.datatype()? == Type::Null {
        d.skip()?;
        return Ok(None);
    }
    item(d).map(Some)
}

fn decode_hash<const N: usize>(d: &mut Decoder<'_>) -> Result<Hash<N>, DecodeError> {
    let bytes = d.bytes()?;
    if bytes.len() != N {
        return Err(DecodeError::message(format!("expected a {N}-byte hash, got {} bytes", bytes.len())));
    }
    Ok(Hash::from(bytes))
}

fn decode_hash28(d: &mut Decoder<'_>) -> Result<Hash<28>, DecodeError> {
    decode_hash::<28>(d)
}

/// `credential = [0, addr_keyhash] / [1, script_hash]` (Conway CDDL `credential`).
pub(crate) fn encode_credential(e: &mut Enc<'_>, credential: &Credential) -> EncodeResult {
    match credential {
        Credential::KeyHash(hash) => e.array(2)?.u8(0)?.bytes(hash.as_ref())?,
        Credential::ScriptHash(hash) => e.array(2)?.u8(1)?.bytes(hash.as_ref())?,
    };
    Ok(())
}

pub(crate) fn decode_credential(d: &mut Decoder<'_>) -> Result<Credential, DecodeError> {
    expect_array(d, 2)?;
    match d.u8()? {
        0 => Ok(Credential::KeyHash(decode_hash28(d)?)),
        1 => Ok(Credential::ScriptHash(decode_hash28(d)?)),
        other => Err(DecodeError::message(format!("unknown credential tag {other}"))),
    }
}

/// `drep = [0, addr_keyhash] / [1, script_hash] / [2] / [3]` (Conway CDDL `drep`).
fn encode_drep(e: &mut Enc<'_>, drep: &DRep) -> EncodeResult {
    match drep {
        DRep::Key(hash) => e.array(2)?.u8(0)?.bytes(hash.as_ref())?,
        DRep::Script(hash) => e.array(2)?.u8(1)?.bytes(hash.as_ref())?,
        DRep::Abstain => e.array(1)?.u8(2)?,
        DRep::NoConfidence => e.array(1)?.u8(3)?,
    };
    Ok(())
}

fn decode_drep(d: &mut Decoder<'_>) -> Result<DRep, DecodeError> {
    let len = d.array()?;
    match (d.u8()?, len) {
        (0, Some(2)) => Ok(DRep::Key(decode_hash28(d)?)),
        (1, Some(2)) => Ok(DRep::Script(decode_hash28(d)?)),
        (2, Some(1)) => Ok(DRep::Abstain),
        (3, Some(1)) => Ok(DRep::NoConfidence),
        (tag, len) => Err(DecodeError::message(format!("invalid drep: tag {tag}, length {len:?}"))),
    }
}

/// `transaction_input = [transaction_id, index]`.
fn encode_input(e: &mut Enc<'_>, input: &TransactionInput) -> EncodeResult {
    e.array(2)?.bytes(input.transaction_id.as_ref())?.u16(input.index)?;
    Ok(())
}

fn decode_input(d: &mut Decoder<'_>) -> Result<TransactionInput, DecodeError> {
    expect_array(d, 2)?;
    Ok(TransactionInput { transaction_id: decode_hash::<32>(d)?, index: d.u16()? })
}

/// `gov_action_id = [transaction_id, index]`.
fn encode_proposal_id(e: &mut Enc<'_>, id: &ProposalId) -> EncodeResult {
    e.array(2)?.bytes(id.transaction_id.as_ref())?.u32(id.proposal_index)?;
    Ok(())
}

fn decode_proposal_id(d: &mut Decoder<'_>) -> Result<ProposalId, DecodeError> {
    expect_array(d, 2)?;
    Ok(ProposalId { transaction_id: decode_hash::<32>(d)?, proposal_index: d.u32()? })
}

/// `transaction_pointer = [slot, transaction_index]`.
fn encode_transaction_pointer(e: &mut Enc<'_>, pointer: &TransactionPointer) -> EncodeResult {
    e.array(2)?.u64(pointer.slot.as_u64())?.u64(pointer.transaction_index as u64)?;
    Ok(())
}

fn decode_transaction_pointer(d: &mut Decoder<'_>) -> Result<TransactionPointer, DecodeError> {
    expect_array(d, 2)?;
    let slot = Slot::new(d.u64()?);
    let transaction_index = usize::try_from(d.u64()?).map_err(|e| DecodeError::message(e.to_string()))?;
    Ok(TransactionPointer { slot, transaction_index })
}

/// `certificate_pointer = [slot, transaction_index, certificate_index] / null` (null = unknown;
/// Amaru's validation rules do not read it at the pinned tag).
fn encode_certificate_pointer(e: &mut Enc<'_>, pointer: &CertificatePointer) -> EncodeResult {
    e.array(3)?
        .u64(pointer.transaction.slot.as_u64())?
        .u64(pointer.transaction.transaction_index as u64)?
        .u64(pointer.certificate_index as u64)?;
    Ok(())
}

fn decode_certificate_pointer(d: &mut Decoder<'_>) -> Result<CertificatePointer, DecodeError> {
    Ok(decode_nullable(d, |d| {
        expect_array(d, 3)?;
        let slot = Slot::new(d.u64()?);
        let transaction_index = usize::try_from(d.u64()?).map_err(|e| DecodeError::message(e.to_string()))?;
        let certificate_index = usize::try_from(d.u64()?).map_err(|e| DecodeError::message(e.to_string()))?;
        Ok(CertificatePointer { transaction: TransactionPointer { slot, transaction_index }, certificate_index })
    })?
    .unwrap_or_default())
}

/// `account = [credential, deposit, rewards, pool_delegation / null, drep_delegation / null]`.
fn encode_account(e: &mut Enc<'_>, credential: &Credential, account: &AccountState) -> EncodeResult {
    e.array(5)?;
    encode_credential(e, credential)?;
    e.u64(account.deposit)?;
    e.u64(account.rewards)?;
    match &account.pool {
        None => {
            e.null()?;
        }
        Some((pool, pointer)) => {
            e.array(2)?.bytes(pool.as_ref())?;
            encode_certificate_pointer(e, pointer)?;
        }
    }
    match &account.drep {
        None => {
            e.null()?;
        }
        Some((drep, pointer)) => {
            e.array(2)?;
            encode_drep(e, drep)?;
            encode_certificate_pointer(e, pointer)?;
        }
    }
    Ok(())
}

fn decode_account(d: &mut Decoder<'_>) -> Result<(Credential, AccountState), DecodeError> {
    expect_array(d, 5)?;
    let credential = decode_credential(d)?;
    let deposit = d.u64()?;
    let rewards = d.u64()?;
    let pool = decode_nullable(d, |d| {
        expect_array(d, 2)?;
        Ok((decode_hash28(d)?, decode_certificate_pointer(d)?))
    })?;
    let drep = decode_nullable(d, |d| {
        expect_array(d, 2)?;
        Ok((decode_drep(d)?, decode_certificate_pointer(d)?))
    })?;
    Ok((credential, AccountState { deposit, pool, drep, rewards }))
}

/// `proposal_kind`:
///   `[0, any_in_security_group: bool]`   parameter change
///   `[1, major, minor]`                   hard fork to that version
///   `[2]` no confidence / update committee, `[3]` new constitution,
///   `[4]` treasury withdrawals, `[5]` info.
fn encode_proposal_kind(e: &mut Enc<'_>, kind: &ProposalSlim) -> EncodeResult {
    match kind {
        ProposalSlim::ProtocolParameters(security_group) => {
            e.array(2)?.u8(0)?.bool(bool::from(*security_group))?;
        }
        ProposalSlim::HardFork(version) => {
            e.array(3)?.u8(1)?.u64(version.major())?.u64(version.minor())?;
        }
        ProposalSlim::ConstitutionalCommittee => {
            e.array(1)?.u8(2)?;
        }
        ProposalSlim::Constitution => {
            e.array(1)?.u8(3)?;
        }
        ProposalSlim::Orphan(is_treasury_withdrawals) => {
            e.array(1)?.u8(if bool::from(*is_treasury_withdrawals) { 4 } else { 5 })?;
        }
    }
    Ok(())
}

fn decode_proposal_kind(d: &mut Decoder<'_>) -> Result<ProposalSlim, DecodeError> {
    let len = d.array()?;
    match (d.u8()?, len) {
        (0, Some(2)) => Ok(ProposalSlim::ProtocolParameters(d.bool()?.into())),
        (1, Some(3)) => Ok(ProposalSlim::HardFork(ProtocolVersion::new(d.u64()?, d.u64()?))),
        (2, Some(1)) => Ok(ProposalSlim::ConstitutionalCommittee),
        (3, Some(1)) => Ok(ProposalSlim::Constitution),
        (4, Some(1)) => Ok(ProposalSlim::Orphan(true.into())),
        (5, Some(1)) => Ok(ProposalSlim::Orphan(false.into())),
        (tag, len) => Err(DecodeError::message(format!("invalid proposal kind: tag {tag}, length {len:?}"))),
    }
}

/// `proposals_roots = [pparams, hard_fork, committee, constitution]`, each `gov_action_id / null`.
fn encode_proposals_roots(e: &mut Enc<'_>, roots: &ProposalsRoots) -> EncodeResult {
    e.array(4)?;
    for root in [&roots.protocol_parameters, &roots.hard_fork, &roots.constitutional_committee, &roots.constitution] {
        match root {
            None => {
                e.null()?;
            }
            Some(id) => encode_proposal_id(e, id)?,
        }
    }
    Ok(())
}

fn decode_proposals_roots(d: &mut Decoder<'_>) -> Result<ProposalsRoots, DecodeError> {
    expect_array(d, 4)?;
    Ok(ProposalsRoots {
        protocol_parameters: decode_nullable(d, decode_proposal_id)?,
        hard_fork: decode_nullable(d, decode_proposal_id)?,
        constitutional_committee: decode_nullable(d, decode_proposal_id)?,
        constitution: decode_nullable(d, decode_proposal_id)?,
    })
}

/// `global_parameters = [security_param_k, epoch_length_scale_factor, active_slot_coeff_inverse,
///                       max_lovelace_supply, slots_per_kes_period, max_kes_evolution, system_start_ms]`
fn encode_global_parameters(e: &mut Enc<'_>, p: &GlobalParameters) -> EncodeResult {
    e.array(7)?
        .u64(p.consensus_security_param)?
        .u64(p.epoch_length_scale_factor)?
        .u64(p.active_slot_coeff_inverse)?
        .u64(p.max_lovelace_supply)?
        .u64(p.slots_per_kes_period)?
        .u8(p.max_kes_evolution)?
        .u64(p.system_start)?;
    Ok(())
}

fn decode_global_parameters(d: &mut Decoder<'_>) -> Result<GlobalParameters, DecodeError> {
    expect_array(d, 7)?;
    Ok(GlobalParameters {
        consensus_security_param: d.u64()?,
        epoch_length_scale_factor: d.u64()?,
        active_slot_coeff_inverse: d.u64()?,
        max_lovelace_supply: d.u64()?,
        slots_per_kes_period: d.u64()?,
        max_kes_evolution: d.u8()?,
        system_start: d.u64()?,
    })
}

/// `era_history = [stability_window, [+ era]]`
/// `era = [start: era_bound, end: era_bound / null, epoch_size_slots, slot_length_ms, era_tag]`
/// `era_bound = [relative_time_ms, slot, epoch]`
fn encode_era_history(e: &mut Enc<'_>, history: &EraHistoryWire) -> EncodeResult {
    e.array(2)?.u64(history.stability_window)?.array(history.eras.len() as u64)?;
    for era in &history.eras {
        e.array(5)?;
        encode_era_bound(e, &era.start)?;
        match &era.end {
            None => {
                e.null()?;
            }
            Some(end) => encode_era_bound(e, end)?,
        }
        e.u64(era.params.epoch_size_slots)?;
        e.u64(duration_to_millis(era.params.slot_length))?;
        e.u8(era.params.era_name as u8)?;
    }
    Ok(())
}

fn encode_era_bound(e: &mut Enc<'_>, bound: &EraBound) -> EncodeResult {
    e.array(3)?.u64(duration_to_millis(bound.time))?.u64(bound.slot.as_u64())?.u64(bound.epoch.as_u64())?;
    Ok(())
}

fn duration_to_millis(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

fn decode_era_history(d: &mut Decoder<'_>) -> Result<EraHistoryWire, DecodeError> {
    expect_array(d, 2)?;
    let stability_window = d.u64()?;
    let eras = decode_array(d, |d| {
        expect_array(d, 5)?;
        let start = decode_era_bound(d)?;
        let end = decode_nullable(d, decode_era_bound)?;
        let epoch_size_slots = d.u64()?;
        let slot_length = Duration::from_millis(d.u64()?);
        let tag = d.u8()?;
        let era_name = EraName::try_from(tag).map_err(|e| DecodeError::message(e.to_string()))?;
        Ok(EraSummary { start, end, params: EraParams { epoch_size_slots, slot_length, era_name } })
    })?;
    if eras.is_empty() {
        return Err(DecodeError::message("era_history has no eras"));
    }
    Ok(EraHistoryWire { stability_window, eras })
}

fn decode_era_bound(d: &mut Decoder<'_>) -> Result<EraBound, DecodeError> {
    expect_array(d, 3)?;
    Ok(EraBound { time: Duration::from_millis(d.u64()?), slot: Slot::new(d.u64()?), epoch: Epoch::new(d.u64()?) })
}

/// `ledger_constants = { ? 0: max_ref_script_size_per_tx, ? 1: max_ref_script_size_per_block,
///                       ? 2: ref_script_cost_stride, ? 3: [numerator, denominator] }`
fn encode_ledger_constants(e: &mut Enc<'_>, c: &LedgerConstants) -> EncodeResult {
    let entries = [
        c.max_ref_script_size_per_tx.is_some(),
        c.max_ref_script_size_per_block.is_some(),
        c.ref_script_cost_stride.is_some(),
        c.ref_script_cost_multiplier.is_some(),
    ]
    .iter()
    .filter(|present| **present)
    .count() as u64;
    e.map(entries)?;
    if let Some(value) = c.max_ref_script_size_per_tx {
        e.u8(0)?.u32(value)?;
    }
    if let Some(value) = c.max_ref_script_size_per_block {
        e.u8(1)?.u32(value)?;
    }
    if let Some(value) = c.ref_script_cost_stride {
        e.u8(2)?.u32(value)?;
    }
    if let Some(value) = &c.ref_script_cost_multiplier {
        e.u8(3)?.array(2)?.u64(value.numerator)?.u64(value.denominator)?;
    }
    Ok(())
}

fn decode_ledger_constants(d: &mut Decoder<'_>) -> Result<LedgerConstants, DecodeError> {
    let mut constants = LedgerConstants::default();
    for_each_map_entry(d, |d, k| {
        match k {
            0 => constants.max_ref_script_size_per_tx = Some(d.u32()?),
            1 => constants.max_ref_script_size_per_block = Some(d.u32()?),
            2 => constants.ref_script_cost_stride = Some(d.u32()?),
            3 => {
                expect_array(d, 2)?;
                let numerator = d.u64()?;
                let denominator = d.u64()?;
                if denominator == 0 {
                    return Err(DecodeError::message("ref_script_cost_multiplier has a zero denominator"));
                }
                constants.ref_script_cost_multiplier = Some(RationalNumber { numerator, denominator });
            }
            other => return Err(DecodeError::message(format!("unknown ledger_constants key {other}"))),
        }
        Ok(())
    })?;
    Ok(constants)
}

/// Collect a slice into the map Amaru's context wants, rejecting duplicate keys (a host bug).
pub(crate) fn into_unique_map<K: Ord + std::fmt::Debug, V>(
    entries: Vec<(K, V)>,
    slice: &str,
) -> Result<BTreeMap<K, V>, String> {
    let mut map = BTreeMap::new();
    for (k, v) in entries {
        if let Some(_previous) = map.insert(k, v) {
            return Err(format!("duplicate key in `{slice}` slice"));
        }
    }
    Ok(map)
}
