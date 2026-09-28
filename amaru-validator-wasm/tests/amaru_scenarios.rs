//! Fixture equivalence: every Amaru transaction scenario at the pinned tag, converted into a v1
//! request by the reference encoder, must get the verdict (and the predicate) Amaru expects.
//!
//! The scenarios live in Amaru's repository under `crates/amaru-ledger/tests/data/transaction`.
//! They are found, in order, at:
//! 1. `$AMARU_FIXTURES_DIR` (CI points it at a shallow clone of Amaru at the pinned tag);
//! 2. Cargo's own git checkout of the pinned commit (`$CARGO_HOME/git/checkouts/amaru-*/<commit>`).
//!
//! Missing fixtures fail the test; they are never skipped silently.

use std::{
    collections::BTreeSet,
    env, fs,
    path::{Path, PathBuf},
    time::Duration,
};

use amaru_kernel::{
    CertificatePointer, ConstitutionalCommitteeMemberStatus, Credential, DRep, DRepRegistration, Epoch, EraBound,
    EraName, EraParams, EraSummary, GlobalParameters, Hash, PoolId, ProposalId, ProposalSlim, ProposalsRoots,
    ProtocolParameters, RationalNumber, Slot, TransactionInput, TransactionPointer, cbor, json, json::Value, to_cbor,
};
use amaru_ledger::{
    context::{AccountState, CCMember, ProposalStateSlim},
    epoch_transition::GovernanceActivity,
};
use amaru_plutus::arena_pool::ArenaPool;
use amaru_validator::{
    AMARU_VERSION, Mode, Request, RequiredKeys, Response,
    engine::{self, network_name},
    interface::{EraHistoryWire, KeysEnv, LedgerConstants, Phase},
};

/// Number of scenarios at the pinned tag; a change means the corpus moved and this gate must be
/// re-read.
const EXPECTED_SCENARIOS: usize = 276;

fn amaru_version_field(field: &str) -> String {
    AMARU_VERSION
        .lines()
        .find_map(|line| line.strip_prefix(&format!("{field}=")))
        .unwrap_or_else(|| panic!("AMARU_VERSION has no `{field}`"))
        .trim()
        .to_string()
}

fn fixtures_dir() -> PathBuf {
    if let Ok(dir) = env::var("AMARU_FIXTURES_DIR") {
        let dir = PathBuf::from(dir);
        assert!(dir.join("scenarios").is_dir(), "AMARU_FIXTURES_DIR={} has no scenarios/", dir.display());
        return dir;
    }
    let commit = amaru_version_field("commit");
    let cargo_home = env::var("CARGO_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env::var("HOME").expect("HOME")).join(".cargo"));
    let checkouts = cargo_home.join("git").join("checkouts");
    if let Ok(entries) = fs::read_dir(&checkouts) {
        for entry in entries.flatten() {
            if !entry.file_name().to_string_lossy().starts_with("amaru-") {
                continue;
            }
            let candidate = entry.path().join(&commit[..7]).join("crates/amaru-ledger/tests/data/transaction");
            if candidate.join("scenarios").is_dir() {
                return candidate;
            }
        }
    }
    panic!(
        "Amaru scenarios not found: set AMARU_FIXTURES_DIR to <amaru clone at {}>/crates/amaru-ledger/tests/data/transaction",
        amaru_version_field("tag")
    );
}

// ------------------------------------------------------------------------ fixture → v1 request

fn resolve(root: &Path, value: &Value) -> Value {
    let Some(reference) = value.get("$ref").and_then(Value::as_str) else {
        return value.clone();
    };
    let mut document: Value =
        json::from_str(&fs::read_to_string(root.join(reference)).expect("read $ref")).expect("parse $ref");
    if let Some(Value::Object(overrides)) = value.get("$override") {
        let object = document.as_object_mut().expect("$ref target is an object");
        for (k, v) in overrides {
            object.insert(k.clone(), v.clone());
        }
    }
    document
}

fn hex_bytes(value: &Value) -> Vec<u8> {
    hex::decode(value.as_str().expect("hex string")).expect("valid hex")
}

fn cbor_hex<T: for<'b> cbor::Decode<'b, ()>>(value: &Value) -> T {
    cbor::decode(&hex_bytes(value)).expect("valid CBOR")
}

fn typed<T: serde::de::DeserializeOwned>(value: &Value) -> T {
    json::from_value(value.clone()).expect("typed JSON value")
}

fn network_magic(network: &str) -> u32 {
    match network {
        "mainnet" => 764_824_073,
        "preprod" => 1,
        "preview" => 2,
        other => other.strip_prefix("testnet_").expect("known network").parse().expect("testnet magic"),
    }
}

/// The Haskell-hardcoded values Amaru's CBOR layout leaves out, when the fixture moves them.
fn ledger_constants(parameters: &ProtocolParameters) -> LedgerConstants {
    let defaults = (200 * 1024, 1024 * 1024, 25_600, RationalNumber { numerator: 12, denominator: 10 });
    LedgerConstants {
        max_ref_script_size_per_tx: (parameters.max_ref_script_size_per_tx != defaults.0)
            .then_some(parameters.max_ref_script_size_per_tx),
        max_ref_script_size_per_block: (parameters.max_ref_script_size_per_block != defaults.1)
            .then_some(parameters.max_ref_script_size_per_block),
        ref_script_cost_stride: (parameters.ref_script_cost_stride != defaults.2)
            .then_some(parameters.ref_script_cost_stride),
        ref_script_cost_multiplier: (parameters.ref_script_cost_multiplier != defaults.3)
            .then_some(parameters.ref_script_cost_multiplier),
    }
}

#[derive(Debug, Clone, PartialEq)]
enum Expected {
    Pass,
    DecodingFailure,
    Predicate { name: String, description: Option<String> },
}

struct Scenario {
    name: String,
    request: Request,
    expected: Expected,
    protocol_parameters: ProtocolParameters,
}

fn load_scenario(root: &Path, path: &Path) -> Scenario {
    let fixture: Value = json::from_str(&fs::read_to_string(path).expect("read fixture")).expect("parse fixture");
    let name = path.file_stem().unwrap().to_string_lossy().to_string();

    let network = fixture["network"].as_str().expect("network");
    let network_magic = network_magic(network);
    let global_parameters = network_name(network_magic)
        .as_global_parameters()
        .unwrap_or_else(|| panic!("{name}: no global parameters for {network}"))
        .clone();

    let era_history = resolve(root, &fixture["era_history"]);
    let era_history = EraHistoryWire {
        stability_window: era_history["stability_window"].as_u64().expect("stability_window"),
        eras: typed::<Vec<EraSummary>>(&era_history["eras"]),
    };

    let protocol_parameters: ProtocolParameters = typed(&resolve(root, &fixture["protocol_parameters"]));

    let state = &fixture["initial_state"];
    let empty = Value::Array(Vec::new());
    let list = |field: &str| state.get(field).unwrap_or(&empty).as_array().expect("array").clone();

    let utxo = list("utxo")
        .iter()
        .map(|entry| (cbor_hex::<TransactionInput>(&entry["input"]), hex_bytes(&entry["output"])))
        .collect();

    let pools = list("pools").iter().map(typed::<PoolId>).collect();

    let pointer = |value: &Value| typed::<CertificatePointer>(value);
    let accounts = list("accounts")
        .iter()
        .map(|entry| {
            let credential: Credential = cbor_hex(&entry["credential"]);
            let pool = entry
                .get("pool")
                .filter(|v| !v.is_null())
                .map(|pool| (typed::<PoolId>(&pool["id"]), pointer(&pool["delegated_at"])));
            let drep = entry
                .get("drep")
                .filter(|v| !v.is_null())
                .map(|drep| (cbor_hex::<DRep>(&drep["id"]), pointer(&drep["delegated_at"])));
            let state = AccountState {
                deposit: entry["deposit"].as_u64().expect("deposit"),
                pool,
                drep,
                rewards: entry.get("rewards").and_then(Value::as_u64).unwrap_or(0),
            };
            (credential, state)
        })
        .collect();

    let dreps = list("dreps")
        .iter()
        .map(|entry| {
            (
                cbor_hex::<Credential>(&entry["credential"]),
                DRepRegistration {
                    deposit: entry["deposit"].as_u64().expect("deposit"),
                    registered_at: pointer(&entry["registered_at"]),
                    valid_until: Epoch::new(entry["valid_until"].as_u64().expect("valid_until")),
                },
            )
        })
        .collect();

    let committee = list("committee")
        .iter()
        .map(|entry| {
            let status = entry.get("status").and_then(Value::as_str).map(|status| {
                if status == "resigned" {
                    ConstitutionalCommitteeMemberStatus::Resigned
                } else {
                    ConstitutionalCommitteeMemberStatus::DelegatedToHotCredential(cbor_hex(&Value::from(status)))
                }
            });
            let valid_until = entry.get("valid_until").and_then(Value::as_u64).map(Epoch::new);
            (cbor_hex::<Credential>(&entry["cold_credential"]), CCMember { status, valid_until })
        })
        .collect();

    let proposals = list("proposals")
        .iter()
        .map(|entry| {
            let [id, kind, valid_until] = entry.as_array().expect("proposal triple").as_slice() else {
                panic!("{name}: proposal is not a triple");
            };
            (
                typed::<ProposalId>(id),
                ProposalStateSlim {
                    action: kind.as_str().expect("proposal kind").parse::<ProposalSlim>().expect("known proposal kind"),
                    valid_until: Epoch::new(valid_until.as_u64().expect("valid_until")),
                },
            )
        })
        .collect();

    let proposals_roots: ProposalsRoots = state.get("proposals_roots").map(typed).unwrap_or_default();
    let governance_activity: GovernanceActivity = state.get("governance_activity").map(typed).unwrap_or_default();
    let treasury = state.get("pots").and_then(|pots| pots.get("treasury")).and_then(Value::as_u64).unwrap_or(0);
    let guardrail_script: Option<Hash<28>> =
        state.get("guardrail_script").filter(|v| !v.is_null()).map(typed::<Hash<28>>);

    let expected = match &fixture["expected"] {
        Value::String(pass) if pass == "Pass" => Expected::Pass,
        Value::Object(map) if map.contains_key("decoding_failure") => Expected::DecodingFailure,
        Value::Object(map) => Expected::Predicate {
            name: map["predicate"].as_str().expect("predicate").to_string(),
            description: map.get("description").and_then(Value::as_str).map(str::to_string),
        },
        other => panic!("{name}: unexpected `expected` {other}"),
    };

    let request = Request {
        mode: Mode::Full,
        transaction: hex_bytes(&fixture["transaction"]),
        network_magic,
        era_history,
        global_parameters,
        protocol_parameters: to_cbor(&protocol_parameters),
        ledger_constants: ledger_constants(&protocol_parameters),
        consecutive_dormant_epochs: governance_activity.consecutive_dormant_epochs,
        guardrail_script,
        proposals_roots,
        treasury,
        pointer: typed::<TransactionPointer>(&fixture["point"]),
        utxo,
        accounts,
        pools,
        dreps,
        committee,
        proposals,
    };

    Scenario { name, request, expected, protocol_parameters }
}

// ------------------------------------------------------------------------------------ assertions

/// Encode, call the same entry point as the `validate` export, and decode the response.
///
/// With `AMARU_DUMP_REQUESTS_DIR` set, the request and response documents are also written there
/// as `<label>.req.cbor` / `<label>.resp.cbor`, so `scripts/run_wasm_scenarios.py` can replay them
/// through the built `.wasm` and compare responses byte for byte.
fn run(label: &str, request: &Request) -> Response {
    let bytes = request.to_cbor();
    let response = engine::validate(&bytes).to_cbor();
    if let Ok(dir) = env::var("AMARU_DUMP_REQUESTS_DIR") {
        let dir = PathBuf::from(dir);
        fs::create_dir_all(&dir).expect("create dump dir");
        fs::write(dir.join(format!("{label}.req.cbor")), &bytes).expect("write request");
        fs::write(dir.join(format!("{label}.resp.cbor")), &response).expect("write response");
    }
    Response::from_cbor(&response).expect("response decodes")
}

/// Amaru's corpus predicate name → the Haskell (rule, constructor) at ADR-056's pinned
/// `cardano-ledger` revision (`adr/reports/adr-056-haskell-pinned-revisions.md`, 3d-table), for
/// the protocol versions the corpus uses (9 and 10). Written independently of `src/failure.rs`,
/// so the two must agree.
fn haskell_name(corpus: &str) -> Option<(&'static str, &'static str)> {
    Some(match corpus {
        "BabbageNonDisjointRefInputs" => ("UTXO", "BabbageNonDisjointRefInputs"),
        "BabbageOutputTooSmallUTxO" => ("UTXO", "BabbageOutputTooSmallUTxO"),
        "BadInputsUTxO" => ("UTXO", "BadInputsUTxO"),
        "CommitteeHasPreviouslyResigned" => ("GOVCERT", "ConwayCommitteeHasPreviouslyResigned"),
        "CommitteeIsUnknown" => ("GOVCERT", "ConwayCommitteeIsUnknown"),
        "ConflictingMetadataHash" => ("UTXOW", "ConflictingMetadataHash"),
        "ConwayTreasuryValueMismatch" => ("LEDGER", "ConwayTreasuryValueMismatch"),
        "ConwayTxRefScriptsSizeTooBig" => ("LEDGER", "ConwayTxRefScriptsSizeTooBig"),
        "ConwayWdrlNotDelegatedToDRep" => ("LEDGER", "ConwayWdrlNotDelegatedToDRep"),
        "DRepAlreadyRegistered" => ("GOVCERT", "ConwayDRepAlreadyRegistered"),
        "DelegateeDRepNotRegistered" => ("DELEG", "DelegateeDRepNotRegisteredDELEG"),
        "DelegateeStakePoolNotRegistered" => ("DELEG", "DelegateeStakePoolNotRegisteredDELEG"),
        "DisallowedVoters" => ("GOV", "DisallowedVoters"),
        "ExtraneousScriptWitnessesUTXOW" => ("UTXOW", "ExtraneousScriptWitnessesUTXOW"),
        "FeeTooSmallUTxO" => ("UTXO", "FeeTooSmallUTxO"),
        "GovActionsDoNotExist" => ("GOV", "GovActionsDoNotExist"),
        "IncorrectDepositDELEG" => ("DELEG", "IncorrectDepositDELEG"),
        "IncorrectTotalCollateralField" => ("UTXO", "IncorrectTotalCollateralField"),
        "InputSetEmptyUTxO" => ("UTXO", "InputSetEmptyUTxO"),
        "InsufficientCollateral" => ("UTXO", "InsufficientCollateral"),
        "InvalidGuardrailsScriptHash" => ("GOV", "InvalidGuardrailsScriptHash"),
        "InvalidPrevGovActionId" => ("GOV", "InvalidPrevGovActionId"),
        "InvalidWitnessesUTXOW" => ("UTXOW", "InvalidWitnessesUTXOW"),
        "MalformedReferenceScripts" => ("UTXOW", "MalformedReferenceScripts"),
        "MalformedScriptWitnesses" => ("UTXOW", "MalformedScriptWitnesses"),
        "MaxTxSizeUTxO" => ("UTXO", "MaxTxSizeUTxO"),
        "MissingScriptWitnessesUTXOW" => ("UTXOW", "MissingScriptWitnessesUTXOW"),
        "MissingTxBodyMetadataHash" => ("UTXOW", "MissingTxBodyMetadataHash"),
        "MissingTxMetadata" => ("UTXOW", "MissingTxMetadata"),
        "MissingVerificationKeyWitnessesUTXOW" => ("UTXOW", "MissingVKeyWitnessesUTXOW"),
        "NoCollateralInputs" => ("UTXO", "NoCollateralInputs"),
        "OutputTooBigUTxO" => ("UTXO", "OutputTooBigUTxO"),
        "OutsideForecast" => ("UTXO", "OutsideForecast"),
        "OutsideValidityIntervalUTxO" => ("UTXO", "OutsideValidityIntervalUTxO"),
        "ProposalCantFollow" => ("GOV", "ProposalCantFollow"),
        "ProposalProcedureNetworkIdMismatch" => ("GOV", "ProposalProcedureNetworkIdMismatch"),
        "ProposalReturnAccountDoesNotExist" => ("GOV", "ProposalReturnAccountDoesNotExist"),
        "ScriptsNotPaidUTxO" => ("UTXO", "ScriptsNotPaidUTxO"),
        "StakeCredentialInvalidPoolDelegation" => ("DELEG", "StakeKeyNotRegisteredDELEG"),
        "StakeCredentialInvalidVoteDelegation" => ("DELEG", "StakeKeyNotRegisteredDELEG"),
        "StakeKeyHasNonZeroAccountBalance" => ("DELEG", "StakeKeyHasNonZeroAccountBalanceDELEG"),
        "StakeKeyRegistered" => ("DELEG", "StakeKeyRegisteredDELEG"),
        "StakePoolCostTooLowPOOL" => ("POOL", "StakePoolCostTooLowPOOL"),
        "StakePoolNotRegisteredOnKeyPOOL" => ("POOL", "StakePoolNotRegisteredOnKeyPOOL"),
        "StakePoolRetirementWrongEpochPOOL" => ("POOL", "StakePoolRetirementWrongEpochPOOL"),
        "TooManyCollateralInputs" => ("UTXO", "TooManyCollateralInputs"),
        "TreasuryWithdrawalReturnAccountsDoNotExist" => ("GOV", "TreasuryWithdrawalReturnAccountsDoNotExist"),
        "TreasuryWithdrawalsAllZeros" => ("GOV", "ZeroTreasuryWithdrawals"),
        "ValidationTagMismatch" => ("UTXOS", "ValidationTagMismatch"),
        "ValueNotConservedUTxO" => ("UTXO", "ValueNotConservedUTxO"),
        "VotersDoNotExist" => ("GOV", "VotersDoNotExist"),
        "VotingOnExpiredGovAction" => ("GOV", "VotingOnExpiredGovAction"),
        "WithdrawalsNotInRewardsCERTS" => ("CERTS", "WithdrawalsNotInRewardsCERTS"),
        "WrongNetworkInTxBody" => ("UTXO", "WrongNetworkInTxBody"),
        "WrongNetworkInTxOutput" => ("UTXO", "WrongNetwork"),
        "WrongNetworkPOOL" => ("POOL", "WrongNetworkPOOL"),
        "WrongNetworkWithdrawal" => ("UTXO", "WrongNetworkWithdrawal"),
        _ => return None,
    })
}

/// Phase, rule, constructor (and tag-mismatch description) all have to match.
fn verdict_matches(expected: &Expected, response: &Response) -> Result<(), String> {
    match (expected, response) {
        (Expected::Pass, Response::Ok) => Ok(()),
        (Expected::DecodingFailure, Response::Invalid(invalid))
            if invalid.phase == Phase::Decode && invalid.rule == "DECODE" =>
        {
            Ok(())
        }
        (Expected::Predicate { name, description }, Response::Invalid(invalid)) => {
            let (rule, constructor) =
                haskell_name(name).ok_or_else(|| format!("corpus predicate {name} has no Haskell alias"))?;
            let phase = if name == "ValidationTagMismatch" { Phase::PhaseTwo } else { Phase::PhaseOne };
            if invalid.phase == phase
                && invalid.rule == rule
                && invalid.constructor.as_deref() == Some(constructor)
                && (description.is_none() || invalid.tag_mismatch == *description)
            {
                Ok(())
            } else {
                Err(format!("expected {phase:?} {rule}.{constructor} ({name}), got {invalid:?}"))
            }
        }
        _ => Err(format!("expected {expected:?}, got {response:?}")),
    }
}

/// `required_keys` through the same entry point as the export; dumped like `run` for the wasm replay.
fn keys(label: &str, transaction: &[u8], env: &[u8]) -> Vec<u8> {
    let response = engine::required_keys(transaction, env);
    if let Ok(dir) = env::var("AMARU_DUMP_REQUESTS_DIR") {
        let dir = PathBuf::from(dir);
        fs::create_dir_all(&dir).expect("create dump dir");
        fs::write(dir.join(format!("{label}.keys-tx.cbor")), transaction).expect("write tx");
        fs::write(dir.join(format!("{label}.keys-env.cbor")), env).expect("write env");
        fs::write(dir.join(format!("{label}.keys-resp.cbor")), &response).expect("write keys response");
    }
    response
}

/// The request reduced to what `required_keys` named: UTxO, accounts, pools and DReps outside the
/// key set are dropped (committee and proposals stay whole, as the interface requires).
fn restrict_to(request: &Request, keys: &RequiredKeys) -> Request {
    let inputs: BTreeSet<_> = keys.inputs.iter().collect();
    let accounts: BTreeSet<_> = keys.accounts.iter().collect();
    let pools: BTreeSet<_> = keys.pools.iter().collect();
    let dreps: BTreeSet<_> = keys.dreps.iter().collect();
    Request {
        utxo: request.utxo.iter().filter(|(input, _)| inputs.contains(input)).cloned().collect(),
        accounts: request.accounts.iter().filter(|(credential, _)| accounts.contains(credential)).cloned().collect(),
        pools: request.pools.iter().filter(|pool| pools.contains(pool)).cloned().collect(),
        dreps: request.dreps.iter().filter(|(credential, _)| dreps.contains(credential)).cloned().collect(),
        ..request.clone()
    }
}

/// Returns the expectation met, and whether restricting to `required_keys` dropped any state.
fn check(root: &Path, path: &Path) -> Result<(Expected, bool), String> {
    let scenario = load_scenario(root, path);
    let name = &scenario.name;

    // The request document round-trips exactly.
    let bytes = scenario.request.to_cbor();
    let decoded = Request::from_cbor(&bytes).map_err(|e| format!("{name}: request does not decode: {e}"))?;
    if decoded.to_cbor() != bytes {
        return Err(format!("{name}: request does not round-trip"));
    }

    // Amaru's parameter layout plus the ledger constants reproduce the fixture's parameters.
    let mut parameters: ProtocolParameters = cbor::decode(&scenario.request.protocol_parameters)
        .map_err(|e| format!("{name}: protocol parameters do not decode: {e}"))?;
    let constants = &scenario.request.ledger_constants;
    parameters.max_ref_script_size_per_tx =
        constants.max_ref_script_size_per_tx.unwrap_or(parameters.max_ref_script_size_per_tx);
    parameters.max_ref_script_size_per_block =
        constants.max_ref_script_size_per_block.unwrap_or(parameters.max_ref_script_size_per_block);
    parameters.ref_script_cost_stride = constants.ref_script_cost_stride.unwrap_or(parameters.ref_script_cost_stride);
    parameters.ref_script_cost_multiplier =
        constants.ref_script_cost_multiplier.unwrap_or(parameters.ref_script_cost_multiplier);
    if parameters != scenario.protocol_parameters {
        return Err(format!("{name}: protocol parameters do not survive the interface encoding"));
    }

    // Full mode: the verdict Amaru's corpus expects (phase, rule and Haskell constructor).
    let full = run(&format!("{name}.full"), &scenario.request);
    verdict_matches(&scenario.expected, &full).map_err(|e| format!("{name}: {e}"))?;

    // Arenas grow past their initial capacity: a 4 KiB arena gives the same response.
    let tiny = engine::validate_request_in(scenario.request.clone(), &ArenaPool::new(1, 4096));
    if tiny != full {
        return Err(format!("{name}: a 4 KiB arena gave {tiny:?}, expected {full:?}"));
    }

    let env = KeysEnv { protocol_version: scenario.protocol_parameters.protocol_version }.to_cbor();
    let keys_response = keys(name, &scenario.request.transaction, &env);
    if scenario.expected == Expected::DecodingFailure {
        return match RequiredKeys::from_cbor(&keys_response) {
            Ok(Err(_)) => Ok((scenario.expected, false)),
            other => Err(format!("{name}: required_keys accepted an undecodable transaction: {other:?}")),
        };
    }
    let keys = match RequiredKeys::from_cbor(&keys_response) {
        Ok(Ok(keys)) => keys,
        other => return Err(format!("{name}: required_keys failed: {other:?}")),
    };

    // Sufficiency: the state slices restricted to exactly the required keys give the same response.
    let restricted = restrict_to(&scenario.request, &keys);
    let dropped = restricted.to_cbor() != scenario.request.to_cbor();
    let reduced = run(&format!("{name}.restricted"), &restricted);
    if reduced != full {
        return Err(format!("{name}: restricted to required_keys gave {reduced:?}, full state gave {full:?}"));
    }

    // Phase-one mode: same verdict, except where the full verdict came from Amaru's phase-two code
    // (script evaluation or script-context preparation), which phase-one mode does not run.
    let phase_one = run(&format!("{name}.phase_one"), &Request { mode: Mode::PhaseOne, ..scenario.request.clone() });
    let from_phase_two_code = matches!(&full, Response::Invalid(invalid)
        if invalid.amaru_error.starts_with("PhaseTwo") || invalid.amaru_error.starts_with("PhaseOne.ScriptPreparation"));
    let expected_phase_one = if from_phase_two_code { Response::Ok } else { full.clone() };
    if phase_one != expected_phase_one {
        return Err(format!("{name}: phase_one mode gave {phase_one:?}, expected {expected_phase_one:?}"));
    }

    Ok((scenario.expected, dropped))
}

#[test]
fn every_amaru_scenario_matches_its_expected_verdict() {
    // Plutus evaluation recurses deeply in debug builds.
    std::thread::Builder::new()
        .stack_size(256 * 1024 * 1024)
        .spawn(|| {
            let root = fixtures_dir();
            let mut paths: Vec<PathBuf> = fs::read_dir(root.join("scenarios"))
                .expect("scenarios dir")
                .map(|entry| entry.expect("entry").path())
                .filter(|path| path.extension().is_some_and(|ext| ext == "json"))
                .collect();
            paths.sort();

            let mut failures = Vec::new();
            let (mut pass, mut fail, mut decoding, mut restricted) = (0, 0, 0, 0);
            for path in &paths {
                match check(&root, path) {
                    Ok((expected, dropped)) => {
                        restricted += usize::from(dropped);
                        match expected {
                            Expected::Pass => pass += 1,
                            Expected::DecodingFailure => decoding += 1,
                            Expected::Predicate { .. } => fail += 1,
                        }
                    }
                    Err(e) => failures.push(e),
                }
            }

            println!(
                "Amaru scenarios ({}): {} matched of {} ({pass} pass, {fail} expected failures, {decoding} decoding failures)",
                root.display(),
                paths.len() - failures.len(),
                paths.len()
            );
            println!("required_keys sufficiency: {restricted} scenario(s) carried state outside the key set; verdicts unchanged without it");
            assert!(failures.is_empty(), "{} scenario(s) diverged:\n{}", failures.len(), failures.join("\n"));
            assert_eq!(paths.len(), EXPECTED_SCENARIOS, "the scenario corpus changed size");
        })
        .expect("spawn")
        .join()
        .expect("scenario run panicked");
}

#[test]
fn amaru_version_matches_cargo_manifest_and_lockfile() {
    let tag = amaru_version_field("tag");
    let commit = amaru_version_field("commit");
    let manifest = fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("Cargo.toml")).unwrap();
    let lockfile = fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("Cargo.lock")).unwrap();
    let toolchain = fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("rust-toolchain.toml")).unwrap();

    assert!(manifest.contains(&format!("tag = \"{tag}\"")), "Cargo.toml does not pin {tag}");
    let pinned = format!("git+https://github.com/pragma-org/amaru?tag={tag}#{commit}");
    for krate in ["amaru-kernel", "amaru-ledger", "amaru-plutus"] {
        let block = lockfile
            .split("[[package]]")
            .find(|block| block.contains(&format!("name = \"{krate}\"\n")))
            .unwrap_or_else(|| panic!("{krate} not in Cargo.lock"));
        assert!(block.contains(&pinned), "{krate} is not locked to {pinned}");
    }
    assert!(
        toolchain.contains(&format!("channel = \"{}\"", amaru_version_field("toolchain"))),
        "rust-toolchain.toml does not match AMARU_VERSION"
    );
}

#[test]
fn unsupported_abi_version_is_rejected() {
    let mut buffer = Vec::new();
    let mut e = cbor::Encoder::new(&mut buffer);
    e.map(1).unwrap().u8(0).unwrap().u32(2).unwrap();
    match engine::validate(&buffer) {
        Response::Error(message) => assert!(message.contains("abi_version"), "{message}"),
        other => panic!("expected an error, got {other:?}"),
    }
}

fn scenario(file: &str) -> Scenario {
    let root = fixtures_dir();
    load_scenario(&root, &root.join("scenarios").join(file))
}

/// ADR-057 open question 2: a custom devnet (unknown magic, short epochs, 1-second slots, its own
/// global parameters) is expressible, and a Plutus V3 spend validates against it.
#[test]
fn custom_devnet_network_and_era_history() {
    let mut request = scenario("00019-pass-plutus-spend-fee-at-minimum-including-ex-units-cost.json").request;
    request.network_magic = 42;
    request.global_parameters = GlobalParameters {
        consensus_security_param: 10,
        epoch_length_scale_factor: 10,
        active_slot_coeff_inverse: 5,
        max_lovelace_supply: 45_000_000_000_000_000,
        slots_per_kes_period: 129_600,
        max_kes_evolution: 62,
        system_start: 1_760_000_000_000,
    };
    request.era_history = EraHistoryWire {
        stability_window: 150,
        eras: vec![EraSummary {
            start: EraBound { time: Duration::ZERO, slot: Slot::new(0), epoch: Epoch::new(0) },
            end: None,
            params: EraParams { epoch_size_slots: 500, slot_length: Duration::from_secs(1), era_name: EraName::Conway },
        }],
    };
    for mode in [Mode::Full, Mode::PhaseOne] {
        let response = run("devnet", &Request { mode, ..request.clone() });
        assert_eq!(response, Response::Ok, "{mode:?}");
    }
    // Same request, network name resolved from the magic: `Testnet(42)`.
    assert_eq!(network_name(42), amaru_kernel::NetworkName::Testnet(42));
}

/// ADR-057 invariant 3: a key the host confirmed absent is left out of its slice, and Amaru reports
/// the typed ledger failure itself.
#[test]
fn confirmed_absent_pool_is_a_ledger_failure_not_an_error() {
    let scenario = scenario("00005-pass-registered-credential-delegates-to-pool.json");
    let mut request = scenario.request;
    let env = KeysEnv { protocol_version: scenario.protocol_parameters.protocol_version }.to_cbor();
    let keys = match RequiredKeys::from_cbor(&engine::required_keys(&request.transaction, &env)) {
        Ok(Ok(keys)) => keys,
        other => panic!("required_keys failed: {other:?}"),
    };
    assert_eq!(keys.pools.len(), 1, "the delegation names one pool");
    request.pools.retain(|pool| !keys.pools.contains(pool));

    match run("absent-pool", &request) {
        Response::Invalid(invalid) => {
            assert_eq!(invalid.rule, "DELEG");
            assert_eq!(invalid.constructor.as_deref(), Some("DelegateeStakePoolNotRegisteredDELEG"));
        }
        other => panic!("expected a DELEG failure, got {other:?}"),
    }
}

/// Amaru's parameter decoder panics on a cost-model language other than 0-2; the module answers
/// with an `error` document instead of trapping.
#[test]
fn unknown_cost_model_language_is_a_request_error() {
    let mut request = scenario("00005-pass-registered-credential-delegates-to-pool.json").request;
    let mut parameters: ProtocolParameters = cbor::decode(&request.protocol_parameters).unwrap();
    parameters.cost_models.plutus_v3 = None;
    let mut bytes = to_cbor(&parameters);
    // Re-key the last cost model (Plutus V2, key 1) as language 3.
    let map_at = bytes.windows(2).position(|w| w == [0xa2, 0x00]).expect("cost-model map {0: .., 1: ..}");
    let key_at = map_at + 2 + {
        let mut d = cbor::Decoder::new(&bytes[map_at + 2..]);
        d.skip().unwrap();
        d.position()
    };
    assert_eq!(bytes[key_at], 0x01);
    bytes[key_at] = 0x03;
    request.protocol_parameters = bytes;
    match engine::validate(&request.to_cbor()) {
        Response::Error(message) => assert!(message.contains("cost model language 3"), "{message}"),
        other => panic!("expected an error, got {other:?}"),
    }
}

#[test]
fn required_keys_env_must_name_the_protocol_version_and_have_no_trailing_bytes() {
    let transaction = scenario("00005-pass-registered-credential-delegates-to-pool.json").request.transaction;
    let good = KeysEnv { protocol_version: amaru_kernel::ProtocolVersion::new(10, 0) }.to_cbor();
    assert!(matches!(RequiredKeys::from_cbor(&engine::required_keys(&transaction, &good)), Ok(Ok(_))));

    let mut trailing = good.clone();
    trailing.push(0x00);
    let missing_version = vec![0xa1, 0x00, 0x01]; // {0: 1}
    for env in [trailing, missing_version, Vec::new()] {
        match RequiredKeys::from_cbor(&engine::required_keys(&transaction, &env)) {
            Ok(Err(message)) => assert!(message.contains("env"), "{message}"),
            other => panic!("expected an env error for {env:02x?}, got {other:?}"),
        }
    }
}
