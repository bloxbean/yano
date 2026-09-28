//! Maps Amaru's typed validation errors to Haskell rule and predicate-failure constructor names.
//!
//! Constructor names are those of `cardano-ledger` at the revision ADR-056 pins (`f649f975`; see
//! `adr/reports/adr-056-haskell-pinned-revisions.md`, "3d-table"). Where Haskell's constructor
//! depends on the protocol version, the request's major version picks it.
//!
//! Amaru's own scenario corpus uses a few different spellings (`StakeKeyRegistered` for
//! `StakeKeyRegisteredDELEG`, `TreasuryWithdrawalsAllZeros` for `ZeroTreasuryWithdrawals`, ...);
//! the fixture test keeps an explicit corpus-to-Haskell alias table.
//!
//! Every match below is exhaustive with no catch-all, down to each error's sub-variants, so an Amaru
//! bump that adds a variant is a compile error here until it is mapped. The one exception is
//! [`InvalidOutputs`], whose field is `pub(crate)` at the pinned tag: its variant is read from the
//! `Debug` form, and [`output_names`] (exhaustive over [`InvalidOutput`]) plus a unit test keep that
//! lookup honest.
//!
//! A `None` constructor means Haskell has no leaf constructor for that condition (an internal error
//! such as impossible slot arithmetic, or a state Haskell rejects elsewhere). The mapping never panics.

use amaru_kernel::{Certificate, Hash, Network, TransactionBody};
use amaru_ledger::{
    context::{DelegateError, UpdateError},
    rules::{
        block::TransactionInvalid,
        transaction::{
            phase_one::{
                InvalidCertificates, InvalidCollateral, InvalidFees, InvalidInputs, InvalidScripts,
                InvalidTransactionMetadata, InvalidValidityInterval, InvalidVerificationKeyWitness,
                InvalidVotingProcedures, InvalidWithdrawals, PhaseOneError,
                outputs::{InvalidOutput, InvalidOutputs},
                proposals::InvalidProposals,
            },
            phase_two::{PreparationError, TagMismatch},
        },
    },
};

use crate::interface::{Invalid, Phase};

const UTXO: &str = "UTXO";
const UTXOW: &str = "UTXOW";
const UTXOS: &str = "UTXOS";
const LEDGER: &str = "LEDGER";
const CERTS: &str = "CERTS";
const DELEG: &str = "DELEG";
const POOL: &str = "POOL";
const GOVCERT: &str = "GOVCERT";
const GOV: &str = "GOV";

/// Longest `detail` string carried back to the host.
const MAX_DETAIL: usize = 4096;

type Names = (&'static str, Option<&'static str>);

/// What the mapping needs besides the error: the protocol version (PV-gated constructor names) and
/// the transaction's certificates (Amaru reports a wrong stake or DRep deposit and a wrong refund
/// with one variant; Haskell distinguishes them).
pub struct MappingContext<'a> {
    pub protocol_major: u64,
    pub certificates: &'a [Certificate],
}

impl<'a> MappingContext<'a> {
    pub fn new(protocol_major: u64, body: &'a TransactionBody) -> Self {
        Self { protocol_major, certificates: body.certificates.as_deref().unwrap_or(&[]) }
    }

    /// PV > 10 gates (`hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule`,
    /// `hardforkConwayDELEGIncorrectDepositsAndRefunds`, script-integrity constructor).
    fn pv11(&self) -> bool {
        self.protocol_major > 10
    }

    /// The first certificate (Amaru fails on the first bad one) that moves `amount` as a stake
    /// deposit (`Some(false)`) or refund (`Some(true)`).
    fn stake_amount_is_refund(&self, amount: u64) -> Option<bool> {
        self.certificates.iter().find_map(|certificate| match certificate {
            Certificate::Reg(_, deposit)
            | Certificate::StakeRegDeleg(_, _, deposit)
            | Certificate::VoteRegDeleg(_, _, deposit)
            | Certificate::StakeVoteRegDeleg(_, _, _, deposit)
                if *deposit == amount =>
            {
                Some(false)
            }
            Certificate::UnReg(_, refund) if *refund == amount => Some(true),
            _ => None,
        })
    }

    fn drep_amount_is_refund(&self, amount: u64) -> Option<bool> {
        self.certificates.iter().find_map(|certificate| match certificate {
            Certificate::RegDRepCert(_, deposit, _) if *deposit == amount => Some(false),
            Certificate::UnRegDRepCert(_, refund) if *refund == amount => Some(true),
            _ => None,
        })
    }
}

pub fn from_transaction_invalid(err: &TransactionInvalid, context: &MappingContext<'_>) -> Invalid {
    match err {
        TransactionInvalid::PhaseOne(err) => from_phase_one(err, context),
        TransactionInvalid::PhaseTwo(mismatch) => {
            let description = match mismatch {
                TagMismatch::PassedUnexpectedly => "PassedUnexpectedly",
                TagMismatch::FailedUnexpectedly(_) => "FailedUnexpectedly",
            };
            Invalid {
                phase: Phase::PhaseTwo,
                rule: UTXOS.to_string(),
                constructor: Some("ValidationTagMismatch".to_string()),
                amaru_error: variant_path(err),
                detail: truncate(err.to_string()),
                tag_mismatch: Some(description.to_string()),
            }
        }
    }
}

pub fn from_phase_one(err: &PhaseOneError, context: &MappingContext<'_>) -> Invalid {
    let (rule, constructor) = phase_one_names(err, context);
    Invalid {
        phase: Phase::PhaseOne,
        rule: rule.to_string(),
        constructor: constructor.map(str::to_string),
        // Rooted like `TransactionInvalid`'s path, whichever mode produced it.
        amaru_error: format!("PhaseOne.{}", variant_path(err)),
        detail: truncate(err.to_string()),
        tag_mismatch: None,
    }
}

pub fn decoding_failure(message: String) -> Invalid {
    Invalid {
        phase: Phase::Decode,
        rule: "DECODE".to_string(),
        constructor: None,
        amaru_error: "TransactionDecoding".to_string(),
        detail: truncate(message),
        tag_mismatch: None,
    }
}

fn phase_one_names(err: &PhaseOneError, context: &MappingContext<'_>) -> Names {
    use PhaseOneError as E;
    match err {
        E::Inputs(e) => match e {
            InvalidInputs::UnknownInput(_) => (UTXO, Some("BadInputsUTxO")),
            // PV 9-10 only; Amaru stops checking at PV 11 as Haskell does.
            InvalidInputs::NonDisjointRefInputs { .. } => (UTXO, Some("BabbageNonDisjointRefInputs")),
            InvalidInputs::EmptyInputSet => (UTXO, Some("InputSetEmptyUTxO")),
            InvalidInputs::RefScriptSizeTooBig { .. } => (LEDGER, Some("ConwayTxRefScriptsSizeTooBig")),
        },
        // Haskell reports every failing output; Amaru collects them too. The first one decides.
        E::Outputs(outputs) => first_invalid_output(outputs).map(output_names).unwrap_or((UTXO, None)),
        E::Certificates(e) => certificate_names(e, context),
        E::Fees(e) => match e {
            InvalidFees::FeeTooSmall { .. } => (UTXO, Some("FeeTooSmallUTxO")),
        },
        E::Withdrawals(e) => match e {
            // Static UTXO check at every PV.
            InvalidWithdrawals::NetworkMismatch { .. } => (UTXO, Some("WrongNetworkWithdrawal")),
            InvalidWithdrawals::MissingAccountDRepDelegation(_) => (LEDGER, Some("ConwayWdrlNotDelegatedToDRep")),
            InvalidWithdrawals::AccountNotRegistered(_) if context.pv11() => {
                (LEDGER, Some("ConwayWithdrawalsMissingAccounts"))
            }
            InvalidWithdrawals::IncompleteWithdrawal { .. } if context.pv11() => {
                (LEDGER, Some("ConwayIncompleteWithdrawals"))
            }
            InvalidWithdrawals::AccountNotRegistered(_) | InvalidWithdrawals::IncompleteWithdrawal { .. } => {
                (CERTS, Some("WithdrawalsNotInRewardsCERTS"))
            }
        },
        E::VerificationKeyWitness(e) => match e {
            InvalidVerificationKeyWitness::MissingRequiredKeysOrRoots { .. } => {
                (UTXOW, Some("MissingVKeyWitnessesUTXOW"))
            }
            InvalidVerificationKeyWitness::InvalidSignatures { .. } => (UTXOW, Some("InvalidWitnessesUTXOW")),
        },
        E::Scripts(e) => match e {
            InvalidScripts::MissingRequiredScripts(_) => (UTXOW, Some("MissingScriptWitnessesUTXOW")),
            InvalidScripts::ExtraneousScriptWitnesses(_) => (UTXOW, Some("ExtraneousScriptWitnessesUTXOW")),
            InvalidScripts::UnspendableInputsNoDatums(_) => (UTXOW, Some("UnspendableUTxONoDatumHash")),
            InvalidScripts::MissingRequiredDatums { .. } => (UTXOW, Some("MissingRequiredDatums")),
            InvalidScripts::ExtraneousSupplementalDatums { .. } => (UTXOW, Some("NotAllowedSupplementalDatums")),
            InvalidScripts::ExtraneousRedeemers(_) => (UTXOW, Some("ExtraRedeemers")),
            InvalidScripts::MissingRedeemers(_) => (UTXOW, Some("MissingRedeemers")),
            InvalidScripts::TooManyExUnits { .. } => (UTXO, Some("ExUnitsTooBigUTxO")),
            InvalidScripts::ScriptWitnessNotValidatingUTXOW(_) => (UTXOW, Some("ScriptWitnessNotValidatingUTXOW")),
            InvalidScripts::ScriptIntegrityHashMismatch { .. } if context.pv11() => {
                (UTXOW, Some("ScriptIntegrityHashMismatch"))
            }
            InvalidScripts::ScriptIntegrityHashMismatch { .. } => (UTXOW, Some("PPViewHashesDontMatch")),
        },
        E::ScriptPreparation(e) => match e {
            PreparationError::MalformedScriptWitness(_) => (UTXOW, Some("MalformedScriptWitnesses")),
            // Script-context collection failures: Haskell's UTXOS `CollectErrors` (NoCostModel,
            // BadTranslation). Amaru's corpus has no scenario for them yet.
            PreparationError::MissingCostModel(_)
            | PreparationError::TransactionTranslation(_)
            | PreparationError::ScriptContextState(_) => (UTXOS, Some("CollectErrors")),
            // Plutus V3 translation refuses overlapping spending and reference inputs: at PV 10 the
            // UTXO check (`BabbageNonDisjointRefInputs`) fires first; from PV 11 it is the V3
            // translation failure, i.e. `CollectErrors` (BadTranslation).
            PreparationError::NonDisjointRefInputs { .. } => (UTXOS, Some("CollectErrors")),
            // Unreachable once phase one passed (inputs resolve; scripts decoded as witnesses).
            PreparationError::MissingInput(_) | PreparationError::ScriptDeserialization(_) => (UTXOS, None),
        },
        E::Collateral(e) => match e {
            InvalidCollateral::UnknownInput(_) => (UTXO, Some("BadInputsUTxO")),
            InvalidCollateral::TooManyInputs { .. } => (UTXO, Some("TooManyCollateralInputs")),
            InvalidCollateral::LockedAtScriptAddress(_) => (UTXO, Some("ScriptsNotPaidUTxO")),
            InvalidCollateral::InsufficientBalance { .. } => (UTXO, Some("InsufficientCollateral")),
            InvalidCollateral::DeclaredCollateralMismatch { .. } => (UTXO, Some("IncorrectTotalCollateralField")),
            InvalidCollateral::NoCollateral => (UTXO, Some("NoCollateralInputs")),
            // Amaru's corpus (Haskell-checked) asserts ValueNotConservedUTxO for this case.
            InvalidCollateral::ValueNotConserved(_) => (UTXO, Some("ValueNotConservedUTxO")),
        },
        E::Proposals(e) => match e {
            InvalidProposals::IncorrectDeposit { .. } => (GOV, Some("ProposalDepositIncorrect")),
            InvalidProposals::ReturnAddressWrongNetwork { .. } => (GOV, Some("ProposalProcedureNetworkIdMismatch")),
            InvalidProposals::ProposalReturnAccountDoesNotExist(_) => (GOV, Some("ProposalReturnAccountDoesNotExist")),
            InvalidProposals::TreasuryWithdrawalsAllZeros => (GOV, Some("ZeroTreasuryWithdrawals")),
            InvalidProposals::TreasuryWithdrawalWrongNetwork { .. } => {
                (GOV, Some("TreasuryWithdrawalsNetworkIdMismatch"))
            }
            InvalidProposals::TreasuryWithdrawalReturnAccountsDoNotExist(_) => {
                (GOV, Some("TreasuryWithdrawalReturnAccountsDoNotExist"))
            }
            InvalidProposals::ConflictingCommitteeUpdate => (GOV, Some("ConflictingCommitteeUpdate")),
            InvalidProposals::HardforkCantFollow { .. } => (GOV, Some("ProposalCantFollow")),
            InvalidProposals::MalformedProposal { .. } => (GOV, Some("MalformedProposal")),
            InvalidProposals::ExpirationEpochTooSmall { .. } => (GOV, Some("ExpirationEpochTooSmall")),
            InvalidProposals::InvalidPrevGovActionId { .. } => (GOV, Some("InvalidPrevGovActionId")),
            InvalidProposals::InvalidGuardrailsScriptHash { .. } => (GOV, Some("InvalidGuardrailsScriptHash")),
            // Slot-to-epoch conversion failed: an engine condition, not a Haskell predicate.
            InvalidProposals::EraHistory(_) => (GOV, None),
        },
        E::VotingProcedures(e) => match e {
            InvalidVotingProcedures::UnknownVoter(_) => (GOV, Some("VotersDoNotExist")),
            InvalidVotingProcedures::DisallowedVoter(_) => (GOV, Some("DisallowedVoters")),
            InvalidVotingProcedures::GovActionsDoNotExist(_) => (GOV, Some("GovActionsDoNotExist")),
            InvalidVotingProcedures::VotingOnExpiredGovAction(_) => (GOV, Some("VotingOnExpiredGovAction")),
            InvalidVotingProcedures::EraHistory(_) => (GOV, None),
        },
        E::Metadata(e) => match e {
            InvalidTransactionMetadata::MissingTransactionMetadata(_) => (UTXOW, Some("MissingTxMetadata")),
            InvalidTransactionMetadata::MissingTransactionAuxiliaryDataHash(_) => {
                (UTXOW, Some("MissingTxBodyMetadataHash"))
            }
            InvalidTransactionMetadata::ConflictingMetadataHash { .. } => (UTXOW, Some("ConflictingMetadataHash")),
            // `validateTxAuxData`: auxiliary scripts must be well formed.
            InvalidTransactionMetadata::InvalidScriptBytes(_) => (UTXOW, Some("InvalidMetadata")),
        },
        E::InvalidNetwork { .. } => (UTXO, Some("WrongNetworkInTxBody")),
        E::TooLarge { .. } => (UTXO, Some("MaxTxSizeUTxO")),
        E::TreasuryValueMismatch { .. } => (LEDGER, Some("ConwayTreasuryValueMismatch")),
        E::ValidityInterval(e) => match e {
            InvalidValidityInterval::OutsideValidityInterval { .. } => (UTXO, Some("OutsideValidityIntervalUTxO")),
            InvalidValidityInterval::OutsideForecast(_) => (UTXO, Some("OutsideForecast")),
        },
        E::ValueNotPreserved(_) => (UTXO, Some("ValueNotConservedUTxO")),
    }
}

fn certificate_names(err: &InvalidCertificates, context: &MappingContext<'_>) -> Names {
    use InvalidCertificates as C;
    match err {
        C::StakeCredentialAlreadyRegistered(_) => (DELEG, Some("StakeKeyRegisteredDELEG")),
        C::StakeCredentialInvalidPoolDelegation(e) => match e {
            DelegateError::UnknownSource(_) => (DELEG, Some("StakeKeyNotRegisteredDELEG")),
            DelegateError::UnknownTarget(_) => (DELEG, Some("DelegateeStakePoolNotRegisteredDELEG")),
            DelegateError::AlreadyResigned => (DELEG, None), // committee-only; unreachable here
        },
        C::StakeCredentialInvalidVoteDelegation(e) => match e {
            DelegateError::UnknownSource(_) => (DELEG, Some("StakeKeyNotRegisteredDELEG")),
            DelegateError::UnknownTarget(_) => (DELEG, Some("DelegateeDRepNotRegisteredDELEG")),
            DelegateError::AlreadyResigned => (DELEG, None),
        },
        C::DRepAlreadyRegistered(_) => (GOVCERT, Some("ConwayDRepAlreadyRegistered")),
        C::DRepInvalidUpdate(e) => match e {
            UpdateError::UnknownSource(_) => (GOVCERT, Some("ConwayDRepNotRegistered")),
        },
        C::CCMemberInvalidDelegation(e) => match e {
            DelegateError::UnknownSource(_) => (GOVCERT, Some("ConwayCommitteeIsUnknown")),
            DelegateError::AlreadyResigned => (GOVCERT, Some("ConwayCommitteeHasPreviouslyResigned")),
            DelegateError::UnknownTarget(_) => (GOVCERT, None), // hot credentials are never registered
        },
        C::ImpossibleSlotArithmetic(_) => (CERTS, None),
        C::PoolWrongNetwork { .. } => (POOL, Some("WrongNetworkPOOL")),
        C::PoolCostTooLow { .. } => (POOL, Some("StakePoolCostTooLowPOOL")),
        C::PoolRetirementWrongEpoch { .. } => (POOL, Some("StakePoolRetirementWrongEpochPOOL")),
        C::StakePoolUnknown(_) => (POOL, Some("StakePoolNotRegisteredOnKeyPOOL")),
        C::IncorrectStakeDeposit { provided, .. } => {
            if !context.pv11() {
                (DELEG, Some("IncorrectDepositDELEG"))
            } else if context.stake_amount_is_refund(*provided) == Some(true) {
                (DELEG, Some("RefundIncorrectDELEG"))
            } else {
                (DELEG, Some("DepositIncorrectDELEG"))
            }
        }
        C::IncorrectDRepDeposit { provided, .. } => {
            if context.drep_amount_is_refund(*provided) == Some(true) {
                (GOVCERT, Some("ConwayDRepIncorrectRefund"))
            } else {
                (GOVCERT, Some("ConwayDRepIncorrectDeposit"))
            }
        }
        C::StakeCredentialNotRegistered(_) => (DELEG, Some("StakeKeyNotRegisteredDELEG")),
        C::StakeCredentialHasRewards { .. } => (DELEG, Some("StakeKeyHasNonZeroAccountBalanceDELEG")),
        C::DRepNotRegistered(_) => (GOVCERT, Some("ConwayDRepNotRegistered")),
    }
}

/// Exhaustive over [`InvalidOutput`]: a new variant fails to compile here.
fn output_names(output: &InvalidOutput) -> Names {
    match output {
        InvalidOutput::TooSmall { .. } => (UTXO, Some("BabbageOutputTooSmallUTxO")),
        InvalidOutput::ValueTooLarge { .. } => (UTXO, Some("OutputTooBigUTxO")),
        InvalidOutput::WrongNetwork { .. } => (UTXO, Some("WrongNetwork")),
        InvalidOutput::MalformedReferenceScript(_) => (UTXOW, Some("MalformedReferenceScripts")),
        // Amaru's Haskell-checked corpus: the node reports `OutputTooBigUTxO`, which Haskell's UTXO
        // rule raises before `OutputBootAddrAttrsTooBig` for such an output.
        InvalidOutput::BootAddrAttrsTooBig { .. } => (UTXO, Some("OutputTooBigUTxO")),
    }
}

/// One value of every [`InvalidOutput`] variant, to resolve the name read from `Debug`.
fn invalid_output_samples() -> [InvalidOutput; 5] {
    [
        InvalidOutput::TooSmall { minimum_value: 0, given_value: 0 },
        InvalidOutput::ValueTooLarge { maximum_size: 0, given_size: 0 },
        InvalidOutput::WrongNetwork { expected: Network::Mainnet, actual: Network::Testnet },
        InvalidOutput::MalformedReferenceScript(Hash::from([0u8; 28].as_slice())),
        InvalidOutput::BootAddrAttrsTooBig { size: 0 },
    ]
}

/// The first failing output of an [`InvalidOutputs`].
///
/// `InvalidOutputs::invalid_outputs` is `pub(crate)` at the pinned tag, so the variant name is read
/// from the `Debug` form (`InvalidOutputs { invalid_outputs: [WithPosition { position: 0, element:
/// TooSmall { .. } }, ..] }`) and resolved against [`invalid_output_samples`].
fn first_invalid_output(outputs: &InvalidOutputs) -> Option<&'static InvalidOutput> {
    use std::sync::OnceLock;
    static SAMPLES: OnceLock<[InvalidOutput; 5]> = OnceLock::new();
    let debug = format!("{outputs:?}");
    let (_, rest) = debug.split_once("element: ")?;
    let name = leading_identifier(rest);
    SAMPLES.get_or_init(invalid_output_samples).iter().find(|sample| leading_identifier(&format!("{sample:?}")) == name)
}

fn leading_identifier(s: &str) -> &str {
    let end = s.find(|c: char| !(c.is_ascii_alphanumeric() || c == '_')).unwrap_or(s.len());
    &s[..end]
}

/// `PhaseOne.Certificates.StakeCredentialInvalidPoolDelegation.UnknownSource`: the chain of
/// variant names at the head of the error's `Debug` form, at most four deep.
fn variant_path(err: &impl std::fmt::Debug) -> String {
    let debug = format!("{err:?}");
    let mut segments = Vec::new();
    let mut rest = debug.as_str();
    while segments.len() < 4 {
        let ident = leading_identifier(rest);
        if ident.is_empty() || !ident.starts_with(|c: char| c.is_ascii_uppercase()) {
            break;
        }
        segments.push(ident);
        rest = &rest[ident.len()..];
        match rest.strip_prefix('(') {
            Some(inner) => rest = inner,
            None => break,
        }
    }
    segments.join(".")
}

fn truncate(mut s: String) -> String {
    if s.len() > MAX_DETAIL {
        let mut cut = MAX_DETAIL;
        while !s.is_char_boundary(cut) {
            cut -= 1;
        }
        s.truncate(cut);
        s.push('…');
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The `Debug` lookup in [`first_invalid_output`] resolves every [`InvalidOutput`] variant, and
    /// the samples cover distinct variants (so `output_names` sees each one).
    #[test]
    fn invalid_output_samples_cover_every_variant_by_debug_name() {
        let samples = invalid_output_samples();
        let names: std::collections::BTreeSet<_> =
            samples.iter().map(|sample| leading_identifier(&format!("{sample:?}")).to_string()).collect();
        assert_eq!(names.len(), samples.len(), "samples must be distinct variants: {names:?}");
        for sample in &samples {
            let debug =
                format!("InvalidOutputs {{ invalid_outputs: [WithPosition {{ position: 0, element: {sample:?} }}] }}");
            let (_, rest) = debug.split_once("element: ").unwrap();
            let name = leading_identifier(rest);
            let resolved = samples.iter().find(|s| leading_identifier(&format!("{s:?}")) == name).unwrap();
            assert_eq!(output_names(resolved), output_names(sample));
        }
    }

    #[test]
    fn variant_path_reads_nested_variants() {
        let err = PhaseOneError::Certificates(InvalidCertificates::StakeCredentialNotRegistered(
            amaru_kernel::Credential::KeyHash(Hash::from([1u8; 28].as_slice())),
        ));
        assert_eq!(variant_path(&err), "Certificates.StakeCredentialNotRegistered.KeyHash.Hash");
    }
}
