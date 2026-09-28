//! The two operations behind the exports: `required_keys` and `validate`.

use std::{
    collections::{BTreeMap, BTreeSet},
    ops::Deref,
    sync::OnceLock,
};

use amaru_kernel::{
    MemoizedTransactionOutput, NetworkName, ProtocolParameters, ProtocolVersion, Transaction, TransactionInput,
    cbor::{Decoder, HasProtocolVersion},
    drep, from_cbor_no_leftovers_with,
};
use amaru_ledger::{
    context::{DefaultPreparationContext, DefaultValidationContext},
    epoch_transition::GovernanceActivity,
    rules::{block::validate_transaction, prepare_transaction, transaction::phase_one},
};
use amaru_plutus::arena_pool::ArenaPool;

use crate::{
    failure,
    interface::{KeysEnv, LedgerConstants, Mode, Request, RequiredKeys, Response, into_unique_map},
};

const MAINNET_MAGIC: u32 = 764_824_073;
const PREPROD_MAGIC: u32 = 1;
const PREVIEW_MAGIC: u32 = 2;

/// One arena is enough: the module is single-threaded, so rayon runs the per-script closures
/// sequentially and each returns its arena before the next is acquired. Natively (tests) other
/// threads simply wait for it.
///
/// The capacity is only a pre-allocation: a bumpalo arena has no allocation limit and grows by
/// chunks as a script needs, then keeps its largest chunk after `reset`. Memory is bounded by the
/// host's page limit (`max-memory-pages`). The Amaru node's `(3, 20 MiB)` pool is sized for
/// parallel block validation; a single 1 MB arena suffices here, and the scenario gate also runs
/// every Plutus scenario through a 4 KiB arena to prove growth.
fn arena_pool() -> &'static ArenaPool {
    static POOL: OnceLock<ArenaPool> = OnceLock::new();
    POOL.get_or_init(|| ArenaPool::new(1, 1_024_000))
}

pub fn network_name(magic: u32) -> NetworkName {
    match magic {
        MAINNET_MAGIC => NetworkName::Mainnet,
        PREPROD_MAGIC => NetworkName::Preprod,
        PREVIEW_MAGIC => NetworkName::Preview,
        other => NetworkName::Testnet(other),
    }
}

fn decode_transaction<C: HasProtocolVersion>(bytes: &[u8], ctx: &mut C) -> Result<Transaction, String> {
    from_cbor_no_leftovers_with(bytes, ctx).map_err(|e| e.to_string())
}

// ------------------------------------------------------------------------------------ required_keys

/// `required_keys`: what Amaru's `prepare_transaction` asks the host to resolve.
pub fn required_keys(transaction: &[u8], env: &[u8]) -> Vec<u8> {
    let env = match KeysEnv::from_cbor(env) {
        Ok(env) => env,
        Err(e) => return RequiredKeys::error_to_cbor(&format!("malformed env: {e}")),
    };

    let mut protocol_version = env.protocol_version;
    let transaction = match decode_transaction(transaction, &mut protocol_version) {
        Ok(transaction) => transaction,
        Err(e) => return RequiredKeys::error_to_cbor(&format!("transaction does not decode: {e}")),
    };

    keys_of(&transaction).to_cbor()
}

pub fn keys_of(transaction: &Transaction) -> RequiredKeys {
    let mut context = DefaultPreparationContext::new();
    prepare_transaction(&mut context, &transaction.body);

    // Mirrors `DefaultPreparationContext::into_validation_context`: delegation targets are
    // resolved as DReps too.
    let dreps: BTreeSet<_> = context
        .dreps
        .iter()
        .map(|credential| credential.clone().into_owned())
        .chain(context.drep_delegations.iter().filter_map(|target| drep::to_stake_credential(target)))
        .collect();

    RequiredKeys {
        inputs: context.utxo.iter().map(|input| **input).collect(),
        accounts: context.accounts.iter().map(|credential| credential.clone().into_owned()).collect(),
        pools: context.pools.iter().map(|pool| **pool).collect(),
        dreps: dreps.into_iter().collect(),
        committee_cold: context.committee.iter().map(|credential| **credential).collect(),
        committee_hot: context.committee_voters.iter().copied().collect(),
        proposals: context.proposals.iter().copied().collect(),
    }
}

// ----------------------------------------------------------------------------------------- validate

/// `validate`: decode the request, build Amaru's validation context and run the rules.
pub fn validate(request: &[u8]) -> Response {
    match Request::from_cbor(request) {
        Ok(request) => validate_request(request),
        Err(e) => Response::Error(format!("malformed request: {e}")),
    }
}

pub fn validate_request(request: Request) -> Response {
    validate_request_in(request, arena_pool())
}

/// [`validate_request`] with an explicit arena pool (tests use a tiny one to show arenas grow).
pub fn validate_request_in(request: Request, arena_pool: &ArenaPool) -> Response {
    if let Err(e) = check_cost_model_languages(&request.protocol_parameters) {
        return Response::Error(format!("protocol_parameters: {e}"));
    }
    let mut protocol_parameters: ProtocolParameters =
        match from_cbor_no_leftovers_with(&request.protocol_parameters, &mut ()) {
            Ok(parameters) => parameters,
            Err(e) => return Response::Error(format!("protocol_parameters do not decode: {e}")),
        };
    apply_ledger_constants(&mut protocol_parameters, &request.ledger_constants);

    // Conway only (ADR-057 invariant 6) is enforced by the host's request builder, which refuses
    // protocol versions below 10. The module itself validates whatever Amaru accepts: Amaru's own
    // scenarios include a hard-fork proposal chained from major version 9.
    let mut protocol_version: ProtocolVersion = protocol_parameters.protocol_version;

    let transaction = match decode_transaction(&request.transaction, &mut protocol_version) {
        Ok(transaction) => transaction,
        Err(e) => return Response::Invalid(failure::decoding_failure(e)),
    };

    let mut context = match build_context(&request, protocol_version) {
        Ok(context) => context,
        Err(e) => return Response::Error(e),
    };

    let network = network_name(request.network_magic);
    let era_history = request.era_history.to_era_history();
    let governance_activity = GovernanceActivity { consecutive_dormant_epochs: request.consecutive_dormant_epochs };
    let mapping = failure::MappingContext::new(protocol_version.major(), &transaction.body);
    let transaction = transaction.tx_ref();

    match request.mode {
        Mode::Full => match validate_transaction(
            &mut context,
            arena_pool,
            network,
            &protocol_parameters,
            &era_history,
            &request.global_parameters,
            governance_activity,
            request.guardrail_script,
            request.pointer,
            transaction,
        ) {
            Ok(()) => Response::Ok,
            Err(e) => Response::Invalid(failure::from_transaction_invalid(&e, &mapping)),
        },
        Mode::PhaseOne => match phase_one::execute(
            &mut context,
            arena_pool,
            network,
            &protocol_parameters,
            &era_history,
            governance_activity,
            request.guardrail_script,
            request.pointer,
            transaction.is_expected_valid,
            transaction.body.clone(),
            transaction.witnesses.deref(),
            transaction.auxiliary_data,
            transaction.len(),
        ) {
            Ok(_consumed) => Response::Ok,
            Err(e) => Response::Invalid(failure::from_phase_one(&e, &mapping)),
        },
    }
}

fn apply_ledger_constants(parameters: &mut ProtocolParameters, constants: &LedgerConstants) {
    if let Some(value) = constants.max_ref_script_size_per_tx {
        parameters.max_ref_script_size_per_tx = value;
    }
    if let Some(value) = constants.max_ref_script_size_per_block {
        parameters.max_ref_script_size_per_block = value;
    }
    if let Some(value) = constants.ref_script_cost_stride {
        parameters.ref_script_cost_stride = value;
    }
    if let Some(value) = constants.ref_script_cost_multiplier {
        parameters.ref_script_cost_multiplier = value;
    }
}

/// Amaru's `ProtocolParameters` decoder hits `unreachable!` on a cost-model language key other
/// than 0, 1 or 2 (a trap in wasm). Reject such parameters as a malformed request instead.
fn check_cost_model_languages(bytes: &[u8]) -> Result<(), String> {
    let mut d = Decoder::new(bytes);
    let fields = d.array().map_err(|e| e.to_string())?;
    if fields != Some(31) {
        return Err(format!("expected a definite array of 31 fields, got {fields:?}"));
    }
    // min_fee_a .. lovelace_per_utxo_byte precede the cost models (15 items).
    for _ in 0..15 {
        d.skip().map_err(|e| e.to_string())?;
    }
    let entries = d.map().map_err(|e| format!("cost models: {e}"))?;
    let Some(entries) = entries else {
        return Err("cost models must be a definite-length map".to_string());
    };
    for _ in 0..entries {
        let language = d.u64().map_err(|e| format!("cost model language: {e}"))?;
        if language > 2 {
            return Err(format!("unknown cost model language {language} (expected 0, 1 or 2)"));
        }
        d.skip().map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn build_context(request: &Request, mut protocol_version: ProtocolVersion) -> Result<DefaultValidationContext, String> {
    // Inputs the host confirmed absent are simply not in `request.utxo`, and the rules report
    // `BadInputsUTxO`. This is Haskell's behaviour and matches Amaru's
    // `UnresolvedInputPolicy::Defer` (block validation); Amaru's own mempool uses `Reject`, which
    // refuses before running the rules. Keeping the rules in charge preserves the Haskell
    // predicate and its precedence.
    let mut utxo: BTreeMap<TransactionInput, MemoizedTransactionOutput> = BTreeMap::new();
    for (input, output) in &request.utxo {
        let output: MemoizedTransactionOutput = from_cbor_no_leftovers_with(output, &mut protocol_version)
            .map_err(|e| format!("utxo output for {input} does not decode: {e}"))?;
        if utxo.insert(*input, output).is_some() {
            return Err("duplicate key in `utxo` slice".to_string());
        }
    }

    let mut pools = BTreeSet::new();
    for pool in &request.pools {
        if !pools.insert(*pool) {
            return Err("duplicate key in `pools` slice".to_string());
        }
    }

    Ok(DefaultValidationContext::new(
        utxo,
        pools,
        into_unique_map(request.accounts.clone(), "accounts")?,
        into_unique_map(request.dreps.clone(), "dreps")?,
        into_unique_map(request.committee.clone(), "committee")?,
        into_unique_map(request.proposals.clone(), "proposals")?,
        request.proposals_roots.clone(),
        request.treasury,
    ))
}
