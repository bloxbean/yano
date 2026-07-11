//// Yano app-chain script anchor — OPT-IN Aiken implementation
//// (ADR app-layer/008.4; ABI: core-api/src/main/cddl/appchain/anchor-v1.cddl).
////
//// Drop-in interchangeable with the bundled julc/Java implementation: same
//// datum/redeemer ABI, same rules, verified by the SAME conformance vectors
//// (appchain-anchor-onchain test suite runs both artifacts). Select it via
////   yano.app-chain.anchor.script.validator: file:/.../anchor-validator.plutus.json
////   yano.app-chain.anchor.script.thread-policy: file:/.../thread-policy.plutus.json
////
//// Advance rules (spend):
////   1. exactly one continuing output to this script, carrying the
////      state-thread NFT, with an inline datum
////   2. out.height > in.height; chain-id and version unchanged
////   3. >= in.threshold of in.member-keys signed the tx (blake2b-224 key
////      hashes vs required signers — member Ed25519 keys double as L1 keys)
////   4. membership may evolve (rule 3 authorizes against the INPUT datum);
////      1 <= out.threshold <= len(out.member-keys)
////   5. locked lovelace is preserved or increased
////
//// The redeemer is a RESERVED action discriminant: v1 has a single action
//// (Advance) and does not inspect the value.

use aiken/collection/dict
use aiken/collection/list
use aiken/crypto.{blake2b_224}
use cardano/assets.{PolicyId, lovelace_of, tokens}
use cardano/transaction.{InlineDatum, Output, OutputReference, Transaction}

/// anchor-datum per anchor-v1.cddl — field order is the ABI; Aiken encodes
/// this record as Constr(0, fields), same as the julc record codec.
pub type AnchorDatum {
  version: Int,
  chain_id: ByteArray,
  height: Int,
  block_hash: ByteArray,
  state_root: ByteArray,
  member_keys: List<ByteArray>,
  threshold: Int,
}

validator anchor(thread_policy_id: ByteArray) {
  spend(
    datum: Option<AnchorDatum>,
    _redeemer: Data,
    own_ref: OutputReference,
    self: Transaction,
  ) {
    expect Some(current) = datum
    expect Some(own_input) =
      list.find(self.inputs, fn(input) { input.output_reference == own_ref })
    let own_address = own_input.output.address

    // Rule 1: exactly one continuing output carrying the thread NFT,
    // with an inline datum
    expect [next] =
      list.filter(self.outputs, fn(output) { output.address == own_address })
    let thread_continues = carries_thread_token(next, thread_policy_id)
    expect InlineDatum(next_data) = next.datum
    expect next_datum: AnchorDatum = next_data

    // Rules 2 + 4 over the continuing (output) datum
    let monotonic = next_datum.height > current.height
    let same_chain = next_datum.chain_id == current.chain_id
    let same_version = next_datum.version == current.version
    let threshold_sane =
      next_datum.threshold >= 1 && next_datum.threshold <= list.length(
        next_datum.member_keys,
      )

    // Rule 3: threshold of the INPUT datum's members signed this tx
    let signers =
      list.count(
        current.member_keys,
        fn(member_key) {
          list.has(self.extra_signatories, blake2b_224(member_key))
        },
      )
    let threshold_signed = signers >= current.threshold

    // Rule 5: no draining
    let value_preserved =
      lovelace_of(next.value) >= lovelace_of(own_input.output.value)

    thread_continues && monotonic && same_chain && same_version && threshold_sane && threshold_signed && value_preserved
  }

  else(_) {
    fail
  }
}

/// Exactly one token with quantity exactly 1 under the thread policy —
/// mirrors the julc `countTokensWithQty(value, policy, 1) == 1` check.
fn carries_thread_token(output: Output, thread_policy_id: ByteArray) -> Bool {
  let qty_one_tokens =
    tokens(output.value, thread_policy_id)
      |> dict.to_pairs()
      |> list.count(fn(pair) { pair.2nd == 1 })
  qty_one_tokens == 1
}

/// One-shot state-thread NFT policy (ADR 008.4 §2.3): minting is valid only
/// when the parameterized bootstrap seed UTxO is consumed, and the mint under
/// this policy is EXACTLY {empty-name: +1} — no burn, no extra names at any
/// quantity. Burning is never permitted; the thread lives forever.
validator anchor_thread(seed_tx_id: ByteArray, seed_index: Int) {
  mint(_redeemer: Data, policy_id: PolicyId, self: Transaction) {
    let seed_consumed =
      list.any(
        self.inputs,
        fn(input) {
          input.output_reference.transaction_id == seed_tx_id && input.output_reference.output_index == seed_index
        },
      )
    let mint_exact =
      when tokens(self.mint, policy_id) |> dict.to_pairs() is {
        [Pair(name, qty)] -> name == "" && qty == 1
        _ -> False
      }
    seed_consumed && mint_exact
  }

  else(_) {
    fail
  }
}
