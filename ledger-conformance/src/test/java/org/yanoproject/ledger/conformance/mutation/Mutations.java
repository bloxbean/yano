package org.yanoproject.ledger.conformance.mutation;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.ProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.spec.ProtocolVersion;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.HardForkInitiationAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.NoConfidence;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.TreasuryWithdrawalsAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataList;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.AuthCommitteeHotCert;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakePoolId;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UpdateDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.HaskellFailureLists;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.ConwayLedgerConstants;
import org.yanoproject.ledger.rules.conway.utxo.MinFee;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;
import org.yanoproject.ledger.rules.fixtures.tx.BuiltTx;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.yanoproject.ledger.conformance.mutation.Mutation.Base.SCRIPT;
import static org.yanoproject.ledger.conformance.mutation.Mutation.Base.SIMPLE;

/**
 * The mutation matrix: the base transactions and their single-fault mutants (ADR-056 §8). Phase 2 covers the
 * UTXO and UTXOW basics, Phase 3a every UTXO and UTXOS constructor a transaction edit can produce, Phase 3b every
 * UTXOW constructor the protocol version 10 world can produce, Phase 4 every CERTS, DELEG, POOL and GOVCERT
 * constructor and, in a protocol version 11 world, the constructors that exist only from 11
 * ({@code ScriptIntegrityHashMismatch}, {@code DepositIncorrectDELEG}, {@code RefundIncorrectDELEG},
 * {@code VRFKeyHashAlreadyRegistered}, {@code ConwayWithdrawalsMissingAccounts}, {@code ConwayIncompleteWithdrawals});
 * Phase 5 every GOV and LEDGER constructor the worlds can express (all but {@code VotingOnExpiredGovAction}: the worlds are
 * at epoch 0, so no proposal can be expired; the Amaru scenarios 00225-00241 and the unit tests cover it); Phase 5b, in a
 * protocol version 9 (bootstrap) world, the two bootstrap-only GOV constructors, and {@link #BOOTSTRAP_ACCEPTED}: the
 * faults protocol version 10 rejects that the bootstrap phase accepts.
 */
public final class Mutations {

    private static final BigInteger PAYMENT = MutationWorld.PAYMENT;

    private static final List<Mutation> ALL = List.of(
            new Mutation("fee-too-small", "UTXO.FeeTooSmallUTxO", List.of(), SIMPLE,
                    "fee one lovelace below the minimum for the final bytes (change +1 keeps the balance)",
                    s -> s.feeAdjust = BigInteger.ONE.negate()),
            new Mutation("bad-input", "UTXO.BadInputsUTxO", List.of(), SIMPLE,
                    "an extra spending input that is not in the UTxO set (the balance counts only known inputs)",
                    s -> s.inputs.add(phantomInput())),
            new Mutation("value-not-conserved", "UTXO.ValueNotConservedUTxO", List.of(), SIMPLE,
                    "change one lovelace more than the inputs pay for",
                    s -> s.changeAdjust = BigInteger.ONE),
            new Mutation("output-too-small", "UTXO.BabbageOutputTooSmallUTxO", List.of(), SIMPLE,
                    "the payment output carries 0.5 ADA, below its minimum UTxO value",
                    s -> s.outputs.set(0, MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK),
                            BigInteger.valueOf(500_000)))),
            new Mutation("wrong-network-output", "UTXO.WrongNetwork", List.of(), SIMPLE,
                    "the payment goes to a mainnet address on a testnet ledger",
                    s -> s.outputs.set(0, MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(Networks.mainnet()),
                            PAYMENT))),
            new Mutation("expired", "UTXO.OutsideValidityIntervalUTxO", List.of(), SIMPLE,
                    "time-to-live equal to the current slot (the upper bound is exclusive)",
                    s -> s.ttl = MutationWorld.SLOT),
            new Mutation("missing-vkey-witness", "UTXOW.MissingVKeyWitnessesUTXOW", List.of(), SIMPLE,
                    "signed by dev-aa instead of the input owner dev-42",
                    s -> s.signers = new ArrayList<>(List.of(TestKey.DEV_AA))),
            new Mutation("invalid-witness", "UTXOW.InvalidWitnessesUTXOW", List.of(), SIMPLE,
                    "the owner's signature with one bit flipped",
                    s -> s.corruptFirstSignature = true),
            new Mutation("max-tx-size", "UTXO.MaxTxSizeUTxO", List.of(), SIMPLE,
                    "about 17 KiB of metadata (hash included, fee paid) makes the transaction exceed maxTxSize",
                    s -> s.metadata = bulkyMetadata()),
            new Mutation("no-collateral", "UTXO.NoCollateralInputs", HaskellFailureLists.NO_COLLATERAL, SCRIPT,
                    "the script transaction without collateral inputs (not a single fault: Haskell's feesOK reports "
                            + "part 5, InsufficientCollateral, for the zero balance before part 7)",
                    s -> s.collateral.clear()),
            new Mutation("too-many-collateral", "UTXO.TooManyCollateralInputs", List.of(), SCRIPT,
                    "four collateral inputs where maxCollateralInputs is 3",
                    s -> s.collateral = new ArrayList<>(List.of(MutationWorld.collateralInput(0),
                            MutationWorld.collateralInput(1), MutationWorld.collateralInput(2),
                            MutationWorld.collateralInput(3)))),
            new Mutation("wrong-network-in-body", "UTXO.WrongNetworkInTxBody", List.of(), SIMPLE,
                    "the body's network id says mainnet on a testnet ledger",
                    s -> s.bodyNetworkId = NetworkId.MAINNET),
            new Mutation("extraneous-script-witness", "UTXOW.ExtraneousScriptWitnessesUTXOW", List.of(), SIMPLE,
                    "a native script witness (satisfied by the transaction's signer) that nothing needs",
                    s -> s.nativeScripts.add(new ScriptPubkey(TestKey.DEV_42.keyHash()))),
            new Mutation("conflicting-metadata-hash", "UTXOW.ConflictingMetadataHash", List.of(), SIMPLE,
                    "metadata whose body hash is the hash of different metadata",
                    s -> {
                        s.metadata = smallMetadata();
                        s.auxDataHash = TxSpec.AuxDataHash.WRONG;
                    }),
            new Mutation("missing-metadata-hash", "UTXOW.MissingTxBodyMetadataHash", List.of(), SIMPLE,
                    "metadata attached without its hash in the body",
                    s -> {
                        s.metadata = smallMetadata();
                        s.auxDataHash = TxSpec.AuxDataHash.OMITTED;
                    }),
            new Mutation("missing-metadata", "UTXOW.MissingTxMetadata", List.of(), SIMPLE,
                    "a metadata hash in the body without the metadata",
                    s -> {
                        s.metadata = smallMetadata();
                        s.dropAuxData = true;
                    }),
            // ---- Phase 3a: UTXO and UTXOS
            new Mutation("empty-inputs", "UTXO.InputSetEmptyUTxO", HaskellFailureLists.EMPTY_INPUTS, SIMPLE,
                    "no spending input and a 5 ADA change paid from nothing (not a single fault: value conservation "
                            + "fails with it)",
                    s -> {
                        s.inputs.clear();
                        s.outputs.clear();
                        s.changeAdjust = BigInteger.valueOf(5_000_000);
                    }),
            new Mutation("not-yet-valid", "UTXO.OutsideValidityIntervalUTxO", List.of(), SIMPLE,
                    "validity interval starting one slot after the current slot",
                    s -> s.validityStart = MutationWorld.SLOT + 1),
            new Mutation("scripts-not-paid", "UTXO.ScriptsNotPaidUTxO", List.of(), SCRIPT,
                    "the collateral input is locked by the always-succeeds script",
                    s -> s.collateral = new ArrayList<>(List.of(MutationWorld.SCRIPT_COLLATERAL_INPUT))),
            new Mutation("collateral-non-ada", "UTXO.CollateralContainsNonADA", List.of(), SCRIPT,
                    "the collateral input carries a token and there is no collateral return",
                    s -> s.collateral = new ArrayList<>(List.of(MutationWorld.TOKEN_COLLATERAL_INPUT)),
                    "UTXO.ValueNotConservedUTxO"),
            new Mutation("insufficient-collateral", "UTXO.InsufficientCollateral", List.of(), SCRIPT,
                    "0.2 ADA of collateral, below 150% of the fee",
                    s -> s.collateral = new ArrayList<>(List.of(MutationWorld.SMALL_COLLATERAL_INPUT))),
            new Mutation("incorrect-total-collateral", "UTXO.IncorrectTotalCollateralField", List.of(), SCRIPT,
                    "total collateral declared one lovelace below the collateral balance",
                    s -> s.totalCollateral = MutationWorld.COLLATERAL_LOVELACE.subtract(BigInteger.ONE)),
            new Mutation("output-too-big", "UTXO.OutputTooBigUTxO", List.of(), SIMPLE,
                    "also spends " + MutationWorld.MANY_ASSETS + " tokens with 32-byte names into the change, whose "
                            + "value then serialises above maxValSize",
                    s -> s.inputs.add(MutationWorld.MANY_ASSETS_INPUT)),
            new Mutation("boot-addr-attrs-too-big", "UTXO.OutputBootAddrAttrsTooBig", List.of(), SIMPLE,
                    "the payment goes to a testnet Byron address with 70 bytes of unknown attributes",
                    s -> s.outputs.set(0, MutationWorld.output(MutationWorld.byronAddress(70), PAYMENT)),
                    "UTXO.OutputTooBigUTxO"),
            new Mutation("ex-units-too-big", "UTXO.ExUnitsTooBigUTxO", List.of(), SCRIPT,
                    "the redeemer declares 15M memory units where maxTxExUnits allows 14M (fee paid)",
                    s -> s.redeemers.set(0, MutationWorld.spendRedeemer(15_000_000))),
            new Mutation("non-disjoint-reference-inputs", "UTXO.BabbageNonDisjointRefInputs", List.of(), SIMPLE,
                    "the spent input is also a reference input (protocol version 10)",
                    s -> s.referenceInputs.add(MutationWorld.KEY_INPUT)),
            new Mutation("script-fails", "UTXOS.ValidationTagMismatch", List.of(), SCRIPT,
                    "spends from the always-fails script instead, with is_valid = true",
                    s -> {
                        s.inputs.set(1, MutationWorld.FAIL_SCRIPT_INPUT);
                        s.plutusScripts.set(0, MutationWorld.ALWAYS_FAILS);
                    }),
            new Mutation("passed-unexpectedly", "UTXOS.ValidationTagMismatch", List.of(), SCRIPT,
                    "is_valid = false although the always-succeeds script passes",
                    s -> s.isValid = false),
            // ---- Phase 3b: UTXOW
            new Mutation("missing-script-witness", "UTXOW.MissingScriptWitnessesUTXOW", List.of(), SIMPLE,
                    "also spends the native-script-locked UTxO without providing its script",
                    s -> s.inputs.add(MutationWorld.NATIVE_INPUT)),
            new Mutation("native-script-not-validating", "UTXOW.ScriptWitnessNotValidatingUTXOW", List.of(), SIMPLE,
                    "also spends the UTxO locked by a native script valid from slot SLOT + 5000, with no validity "
                            + "start",
                    s -> {
                        s.inputs.add(MutationWorld.TIMELOCK_INPUT);
                        s.nativeScripts.add(MutationWorld.TIMELOCK_SCRIPT);
                    }),
            new Mutation("unspendable-no-datum", "UTXOW.UnspendableUTxONoDatumHash", List.of(), SCRIPT,
                    "spends a PlutusV2-locked UTxO that has no datum (CIP-69 exempts PlutusV3 only)",
                    s -> {
                        s.inputs.set(1, MutationWorld.V2_SCRIPT_INPUT);
                        s.plutusScripts.clear();
                        s.plutusV2Scripts.add(MutationWorld.ALWAYS_SUCCEEDS_V2);
                    }),
            new Mutation("missing-required-datum", "UTXOW.MissingRequiredDatums", List.of(), SCRIPT,
                    "spends the PlutusV3-locked UTxO with a datum hash without providing the datum",
                    s -> s.inputs.set(1, MutationWorld.DATUM_SCRIPT_INPUT)),
            new Mutation("not-allowed-supplemental-datum", "UTXOW.NotAllowedSupplementalDatums", List.of(), SIMPLE,
                    "a witness datum that no input needs and no output or reference input names (integrity hash "
                            + "included)",
                    s -> s.datums.add(MutationWorld.DATUM)),
            new Mutation("extra-redeemer", "UTXOW.ExtraRedeemers", List.of(), SCRIPT,
                    "an additional minting redeemer although nothing is minted (fee paid)",
                    s -> s.redeemers.add(mintRedeemer())),
            new Mutation("missing-redeemer", "UTXOW.MissingRedeemers", HaskellFailureLists.MISSING_REDEEMER, SCRIPT,
                    "the script transaction without its redeemer (not a single fault: the script context collection "
                            + "also reports NoRedeemer)",
                    s -> s.redeemers.clear()),
            new Mutation("script-integrity-hash", "UTXOW.PPViewHashesDontMatch", List.of(), SCRIPT,
                    "one bit of the body's script integrity hash flipped (protocol version 10)",
                    s -> s.corruptScriptDataHash = true),
            new Mutation("malformed-script-witness", "UTXOW.MalformedScriptWitnesses", List.of(), SCRIPT,
                    "spends the UTxO locked by a PlutusV3 script whose bytes are not a program, with that script",
                    s -> {
                        s.inputs.set(1, MutationWorld.MALFORMED_SCRIPT_INPUT);
                        s.plutusScripts.set(0, MutationWorld.MALFORMED_SCRIPT);
                    }),
            new Mutation("script-trailing-bytes", "UTXOW.MalformedScriptWitnesses", List.of(), SCRIPT,
                    "spends the UTxO of a PlutusV3 script whose CBOR byte string is followed by one more byte "
                            + "(deserialiseScript's RemainderError), with that script",
                    s -> {
                        s.inputs.set(1, MutationWorld.TRAILING_BYTES_SCRIPT_INPUT);
                        s.plutusScripts.set(0, MutationWorld.TRAILING_BYTES_SCRIPT);
                    },
                    Mutation.AMARU_ACCEPTS),
            new Mutation("script-unavailable-builtin", "UTXOW.MalformedScriptWitnesses", List.of(), SCRIPT,
                    "spends the UTxO of a PlutusV3 script using expModInteger, available only from protocol "
                            + "version 11 (builtinsAvailableIn), with that script",
                    s -> {
                        s.inputs.set(1, MutationWorld.UNAVAILABLE_BUILTIN_SCRIPT_INPUT);
                        s.plutusScripts.set(0, MutationWorld.UNAVAILABLE_BUILTIN_SCRIPT);
                    }),
            new Mutation("malformed-reference-script", "UTXOW.MalformedReferenceScripts", List.of(), SIMPLE,
                    "the payment output carries a PlutusV3 reference script whose bytes are not a program",
                    s -> s.outputs.set(0, withScriptRef(MutationWorld.output(
                            TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK), PAYMENT)))),
            new Mutation("invalid-metadata", "UTXOW.InvalidMetadata", List.of(), SIMPLE,
                    "auxiliary data (hash included) carrying a PlutusV3 script whose bytes are not a program",
                    s -> s.auxPlutusScripts.add(MutationWorld.MALFORMED_SCRIPT)),
            new Mutation("script-integrity-hash-v11", "UTXOW.ScriptIntegrityHashMismatch", List.of(), SCRIPT,
                    "one bit of the body's script integrity hash flipped (protocol version 11)",
                    s -> s.corruptScriptDataHash = true).atProtocolVersion11(),
            // ---- Phase 4: CERTS, DELEG, POOL, GOVCERT. Each adds one certificate (or withdrawal) whose only fault is
            // the covered one, with the deposits, refunds and withdrawals Haskell counts balanced (changeAdjust), and
            // the credential's key as an extra signer. World: MutationWorld's certificate state.
            new Mutation("withdrawal-not-draining", "CERTS.WithdrawalsNotInRewardsCERTS", List.of(), SIMPLE,
                    "withdraws 1 ADA of dev-bb's 5 ADA reward balance (a withdrawal must drain it)",
                    s -> {
                        withdraw(s, TestKey.DEV_BB, ada(1));
                        s.changeAdjust = ada(1);
                    }),
            new Mutation("withdrawal-missing-account-v11", "LEDGER.ConwayWithdrawalsMissingAccounts", List.of(), SIMPLE,
                    "withdraws 0 from the reward account of the native script, which has no account (protocol version "
                            + "11; a script credential, so ConwayWdrlNotDelegatedToDRep does not apply)",
                    s -> {
                        s.withdrawals.add(new Withdrawal(AddressProvider.getRewardAddress(MutationWorld.NATIVE_SCRIPT,
                                MutationWorld.NETWORK).toBech32(), BigInteger.ZERO));
                        s.nativeScripts.add(MutationWorld.NATIVE_SCRIPT);
                    }).atProtocolVersion11(),
            new Mutation("withdrawal-incomplete-v11", "LEDGER.ConwayIncompleteWithdrawals", List.of(), SIMPLE,
                    "withdraws 1 ADA of dev-bb's 5 ADA reward balance (protocol version 11)",
                    s -> {
                        withdraw(s, TestKey.DEV_BB, ada(1));
                        s.changeAdjust = ada(1);
                    }).atProtocolVersion11(),
            new Mutation("reg-deposit-incorrect", "DELEG.IncorrectDepositDELEG", List.of(), SIMPLE,
                    "registers dev-42 stating a 1 ADA deposit (ppKeyDeposit is 2 ADA, which the balance pays)",
                    s -> certificate(s, new RegCert(MutationWorld.stakeCredential(TestKey.DEV_42), ada(1)), ada(-2))),
            new Mutation("unreg-refund-incorrect", "DELEG.IncorrectDepositDELEG", List.of(), SIMPLE,
                    "deregisters dev-77 stating a 1 ADA refund (the recorded deposit, credited, is 2 ADA)",
                    s -> certificate(s, new UnregCert(MutationWorld.stakeCredential(TestKey.DEV_77), ada(1)), ada(2),
                            TestKey.DEV_77)),
            new Mutation("reg-deposit-incorrect-v11", "DELEG.DepositIncorrectDELEG", List.of(), SIMPLE,
                    "registers dev-42 stating a 1 ADA deposit (protocol version 11)",
                    s -> certificate(s, new RegCert(MutationWorld.stakeCredential(TestKey.DEV_42), ada(1)), ada(-2)))
                    .atProtocolVersion11(),
            new Mutation("unreg-refund-incorrect-v11", "DELEG.RefundIncorrectDELEG", List.of(), SIMPLE,
                    "deregisters dev-77 stating a 1 ADA refund (protocol version 11)",
                    s -> certificate(s, new UnregCert(MutationWorld.stakeCredential(TestKey.DEV_77), ada(1)), ada(2),
                            TestKey.DEV_77)).atProtocolVersion11(),
            new Mutation("reg-already-registered", "DELEG.StakeKeyRegisteredDELEG", List.of(), SIMPLE,
                    "registers dev-77, which is registered",
                    s -> certificate(s, new RegCert(MutationWorld.stakeCredential(TestKey.DEV_77), ada(2)), ada(-2),
                            TestKey.DEV_77)),
            new Mutation("unreg-not-registered", "DELEG.StakeKeyNotRegisteredDELEG", List.of(), SIMPLE,
                    "deregisters dev-42, which is not registered (no refund is credited)",
                    s -> certificate(s, new UnregCert(MutationWorld.stakeCredential(TestKey.DEV_42), ada(2)),
                            BigInteger.ZERO)),
            new Mutation("unreg-non-zero-balance", "DELEG.StakeKeyHasNonZeroAccountBalanceDELEG", List.of(), SIMPLE,
                    "deregisters dev-bb, whose reward balance is 5 ADA",
                    s -> certificate(s, new UnregCert(MutationWorld.stakeCredential(TestKey.DEV_BB), ada(2)), ada(2),
                            TestKey.DEV_BB)),
            new Mutation("deleg-pool-not-registered", "DELEG.DelegateeStakePoolNotRegisteredDELEG", List.of(), SIMPLE,
                    "delegates dev-77's stake to a pool id no pool has (dev-42's key hash)",
                    s -> certificate(s, new StakeDelegation(MutationWorld.stakeCredential(TestKey.DEV_77),
                            new StakePoolId(HexUtil.decodeHexString(TestKey.DEV_42.keyHash()))), BigInteger.ZERO,
                            TestKey.DEV_77)),
            new Mutation("deleg-drep-not-registered", "DELEG.DelegateeDRepNotRegisteredDELEG", List.of(), SIMPLE,
                    "delegates dev-77's vote to dev-42's key hash, which is no DRep",
                    s -> certificate(s, new VoteDelegCert(MutationWorld.stakeCredential(TestKey.DEV_77),
                            DRep.addrKeyHash(TestKey.DEV_42.keyHash())), BigInteger.ZERO, TestKey.DEV_77)),
            new Mutation("retire-unregistered-pool", "POOL.StakePoolNotRegisteredOnKeyPOOL", List.of(), SIMPLE,
                    "retires pool dev-42, which is not registered",
                    s -> certificate(s, new PoolRetirement(HexUtil.decodeHexString(TestKey.DEV_42.keyHash()), 1),
                            BigInteger.ZERO)),
            new Mutation("retire-wrong-epoch", "POOL.StakePoolRetirementWrongEpochPOOL", List.of(), SIMPLE,
                    "retires dev-77's pool at the current epoch (it must be later, and at most eMax later)",
                    s -> certificate(s, new PoolRetirement(HexUtil.decodeHexString(TestKey.DEV_77.keyHash()),
                            MutationWorld.env().currentEpoch()), BigInteger.ZERO, TestKey.DEV_77)),
            new Mutation("pool-cost-too-low", "POOL.StakePoolCostTooLowPOOL", List.of(), SIMPLE,
                    "re-registers dev-77's pool with a cost one lovelace below minPoolCost",
                    s -> certificate(s, MutationWorld.poolRegistration(TestKey.DEV_77, MutationWorld.POOL_77_VRF,
                            MutationWorld.MIN_POOL_COST.subtract(BigInteger.ONE), MutationWorld.NETWORK, null),
                            BigInteger.ZERO, TestKey.DEV_77)),
            new Mutation("pool-wrong-network", "POOL.WrongNetworkPOOL", List.of(), SIMPLE,
                    "re-registers dev-77's pool with a mainnet reward account on a testnet ledger",
                    s -> certificate(s, MutationWorld.poolRegistration(TestKey.DEV_77, MutationWorld.POOL_77_VRF,
                            MutationWorld.MIN_POOL_COST, Networks.mainnet(), null), BigInteger.ZERO, TestKey.DEV_77)),
            new Mutation("pool-metadata-hash-too-big", "POOL.PoolMedataHashTooBig", List.of(), SIMPLE,
                    "re-registers dev-77's pool with a 33-byte metadata hash",
                    s -> certificate(s, MutationWorld.poolRegistration(TestKey.DEV_77, MutationWorld.POOL_77_VRF,
                            MutationWorld.MIN_POOL_COST, MutationWorld.NETWORK, "ab".repeat(33)), BigInteger.ZERO,
                            TestKey.DEV_77))
                    // Recorded divergence: Amaru's decoder refuses a metadata hash that is not 32 bytes; Haskell
                    // decodes any size (PoolMetadata's pmHash is a ByteArray, StakePool.hs:522-524) and POOL rejects
                    // it (Shelley/Rules/Pool.hs:245-250).
                    .withAmaruReports("ENGINE.DecodingFailure"),
            new Mutation("pool-vrf-taken-v11", "POOL.VRFKeyHashAlreadyRegistered", List.of(), SIMPLE,
                    "re-registers dev-77's pool with the VRF key hash of dev-bb's pool (protocol version 11)",
                    s -> certificate(s, MutationWorld.poolRegistration(TestKey.DEV_77, MutationWorld.POOL_BB_VRF,
                            MutationWorld.MIN_POOL_COST, MutationWorld.NETWORK, null), BigInteger.ZERO,
                            TestKey.DEV_77)).atProtocolVersion11()
                    // Recorded divergence: Amaru has no psVRFKeyHashes (the request carries pool ids only) and accepts.
                    .withAmaruReports(Mutation.AMARU_ACCEPTS),
            new Mutation("drep-already-registered", "GOVCERT.ConwayDRepAlreadyRegistered", List.of(), SIMPLE,
                    "registers dev-77 as a DRep again, with the right deposit",
                    s -> certificate(s, new RegDRepCert(MutationWorld.credential(TestKey.DEV_77), ada(500), null),
                            ada(-500), TestKey.DEV_77)),
            new Mutation("drep-deposit-incorrect", "GOVCERT.ConwayDRepIncorrectDeposit", List.of(), SIMPLE,
                    "registers dev-42 as a DRep stating 400 ADA (ppDRepDeposit is 500 ADA, which the balance pays)",
                    s -> certificate(s, new RegDRepCert(MutationWorld.credential(TestKey.DEV_42), ada(400), null),
                            ada(-500))),
            new Mutation("drep-not-registered", "GOVCERT.ConwayDRepNotRegistered", List.of(), SIMPLE,
                    "updates dev-42's DRep, which is not registered",
                    s -> certificate(s, new UpdateDRepCert(MutationWorld.credential(TestKey.DEV_42), null),
                            BigInteger.ZERO))
                    // Recorded divergence: Amaru's DRepsSlice::update does not check the registration
                    // (context/default/validation.rs:263-266 at the pinned tag) and accepts; Yano's adapter then
                    // cannot derive the update's effects and fails closed. Haskell: GovCert.hs:256-258.
                    .withAmaruReports("ENGINE.AmaruEngineFailure"),
            new Mutation("drep-refund-incorrect", "GOVCERT.ConwayDRepIncorrectRefund", List.of(), SIMPLE,
                    "deregisters dev-77's DRep stating (and crediting) 400 ADA; its deposit is 500 ADA",
                    s -> certificate(s, new UnregDRepCert(MutationWorld.credential(TestKey.DEV_77), ada(400)),
                            ada(400), TestKey.DEV_77)),
            new Mutation("committee-resigned", "GOVCERT.ConwayCommitteeHasPreviouslyResigned", List.of(), SIMPLE,
                    "dev-bb, an elected member that resigned, authorizes a hot key",
                    s -> certificate(s, new AuthCommitteeHotCert(MutationWorld.credential(TestKey.DEV_BB),
                            MutationWorld.credential(TestKey.DEV_42)), BigInteger.ZERO, TestKey.DEV_BB)),
            new Mutation("committee-unknown", "GOVCERT.ConwayCommitteeIsUnknown", List.of(), SIMPLE,
                    "dev-42, neither a member nor proposed, authorizes a hot key",
                    s -> certificate(s, new AuthCommitteeHotCert(MutationWorld.credential(TestKey.DEV_42),
                            MutationWorld.credential(TestKey.DEV_AA)), BigInteger.ZERO)),
            // ---- Phase 5: LEDGER and GOV. World: MutationWorld's governance state (two standing proposals, no enacted
            // roots, no guardrail script, dev-77 elected with hot key dev-42, an unelected member with hot key dev-aa,
            // dev-cc registered without delegations, a UTxO with a 205,000-byte reference script). A proposal spends
            // GOV_INPUT and is balanced with ppGovActionDeposit, which Haskell's value conservation counts.
            new Mutation("treasury-value-mismatch", "LEDGER.ConwayTreasuryValueMismatch", List.of(), SIMPLE,
                    "states a current treasury value one lovelace above the ledger's",
                    s -> s.currentTreasuryValue = MutationWorld.TREASURY.add(BigInteger.ONE)),
            new Mutation("ref-scripts-too-big", "LEDGER.ConwayTxRefScriptsSizeTooBig", List.of(), SIMPLE,
                    "references a UTxO with a 205,000-byte reference script (limit 200 KiB), paying its tiered fee",
                    s -> {
                        s.referenceInputs.add(MutationWorld.BIG_REFERENCE_SCRIPT_INPUT);
                        s.feeAdjust = MinFee.tierRefScriptFee(ConwayLedgerConstants.HASKELL,
                                MutationWorld.protocolParams().getMinFeeRefScriptCostPerByte(),
                                MutationWorld.BIG_REFERENCE_SCRIPT_SIZE);
                    }),
            new Mutation("withdrawal-not-delegated-to-drep", "LEDGER.ConwayWdrlNotDelegatedToDRep", List.of(), SIMPLE,
                    "withdraws dev-cc's whole 5 ADA balance; dev-cc has no DRep delegation",
                    s -> {
                        withdraw(s, TestKey.DEV_CC, MutationWorld.REWARD_BALANCE);
                        s.changeAdjust = MutationWorld.REWARD_BALANCE;
                    }),
            new Mutation("proposal-deposit-incorrect", "GOV.ProposalDepositIncorrect", List.of(), SIMPLE,
                    "an info action stating a deposit one lovelace below ppGovActionDeposit",
                    s -> propose(s, proposal(new InfoAction(), TestKey.DEV_77, MutationWorld.NETWORK,
                            MutationWorld.GOV_ACTION_DEPOSIT.subtract(BigInteger.ONE)))),
            new Mutation("proposal-return-account-missing", "GOV.ProposalReturnAccountDoesNotExist", List.of(), SIMPLE,
                    "an info action returning its deposit to dev-aa, which has no account",
                    s -> propose(s, proposal(new InfoAction(), TestKey.DEV_AA, MutationWorld.NETWORK,
                            MutationWorld.GOV_ACTION_DEPOSIT))),
            new Mutation("proposal-return-account-network", "GOV.ProposalProcedureNetworkIdMismatch", List.of(), SIMPLE,
                    "an info action returning its deposit to dev-77's mainnet account",
                    s -> propose(s, proposal(new InfoAction(), TestKey.DEV_77, Networks.mainnet(),
                            MutationWorld.GOV_ACTION_DEPOSIT))),
            new Mutation("treasury-withdrawal-network", "GOV.TreasuryWithdrawalsNetworkIdMismatch", List.of(), SIMPLE,
                    "a treasury withdrawal of 10 ADA to dev-77's mainnet account",
                    s -> propose(s, proposal(treasuryWithdrawals(TestKey.DEV_77, Networks.mainnet(), ada(10))))),
            new Mutation("treasury-withdrawal-account-missing", "GOV.TreasuryWithdrawalReturnAccountsDoNotExist",
                    List.of(), SIMPLE, "a treasury withdrawal of 10 ADA to dev-aa, which has no account",
                    s -> propose(s, proposal(treasuryWithdrawals(TestKey.DEV_AA, MutationWorld.NETWORK, ada(10))))),
            new Mutation("treasury-withdrawal-zero", "GOV.ZeroTreasuryWithdrawals", List.of(), SIMPLE,
                    "a treasury withdrawal of 0 to dev-77",
                    s -> propose(s, proposal(treasuryWithdrawals(TestKey.DEV_77, MutationWorld.NETWORK,
                            BigInteger.ZERO)))),
            new Mutation("guardrails-script-hash", "GOV.InvalidGuardrailsScriptHash", List.of(), SIMPLE,
                    "a parameter change naming the native script as its guardrail (provided) while the constitution "
                            + "has none",
                    s -> {
                        propose(s, proposal(new ParameterChangeAction(null, collateralPercent(140),
                                nativeScriptHash())));
                        s.nativeScripts.add(MutationWorld.NATIVE_SCRIPT);
                    }),
            new Mutation("malformed-proposal", "GOV.MalformedProposal", List.of(), SIMPLE,
                    "a parameter change setting maxTxSize to 0 (not ppuWellFormed)",
                    s -> propose(s, proposal(new ParameterChangeAction(null,
                            ProtocolParamUpdate.builder().maxTxSize(0).build(), null)))),
            new Mutation("hard-fork-cant-follow", "GOV.ProposalCantFollow", List.of(), SIMPLE,
                    "a hard fork to 12.0 at protocol version 10.0 (only 11.0 or 10.1 can follow)",
                    s -> propose(s, proposal(new HardForkInitiationAction(null, new ProtocolVersion(12, 0))))),
            new Mutation("invalid-prev-gov-action-id", "GOV.InvalidPrevGovActionId", List.of(), SIMPLE,
                    "a parameter change whose parent is the standing info action (no lineage purpose)",
                    s -> propose(s, proposal(new ParameterChangeAction(ccl(MutationWorld.INFO_ACTION),
                            collateralPercent(140), null)))),
            new Mutation("committee-update-conflict", "GOV.ConflictingCommitteeUpdate", List.of(), SIMPLE,
                    "an update committee proposal removing and adding dev-bb",
                    s -> propose(s, proposal(updateCommittee(Set.of(MutationWorld.credential(TestKey.DEV_BB)),
                            Map.of(MutationWorld.credential(TestKey.DEV_BB), 50))))),
            new Mutation("committee-expiration-too-small", "GOV.ExpirationEpochTooSmall", List.of(), SIMPLE,
                    "an update committee proposal adding dev-42 with expiry epoch 0, the current epoch",
                    s -> propose(s, proposal(updateCommittee(Set.of(),
                            Map.of(MutationWorld.credential(TestKey.DEV_42), 0))))),
            new Mutation("voter-does-not-exist", "GOV.VotersDoNotExist", List.of(), SIMPLE,
                    "dev-aa, not a registered DRep, votes on the standing info action",
                    s -> vote(s, VoterType.DREP_KEY_HASH, TestKey.DEV_AA, MutationWorld.INFO_ACTION)),
            new Mutation("gov-action-does-not-exist", "GOV.GovActionsDoNotExist", List.of(), SIMPLE,
                    "dev-77's DRep votes on an action that is not in the proposals",
                    s -> vote(s, VoterType.DREP_KEY_HASH, TestKey.DEV_77,
                            new org.yanoproject.ledger.rules.view.model.GovActionId("e3".repeat(32), 0))),
            new Mutation("disallowed-voter", "GOV.DisallowedVoters", List.of(), SIMPLE,
                    "dev-77's pool votes on the standing parameter change, outside the stake-pool security group",
                    s -> vote(s, VoterType.STAKING_POOL_KEY_HASH, TestKey.DEV_77,
                            MutationWorld.PARAMETER_CHANGE_ACTION)),
            new Mutation("unelected-committee-voter-v11", "GOV.UnelectedCommitteeVoters", List.of(), SIMPLE,
                    "the hot key (dev-aa) of a committee member without a term votes (protocol version 11)",
                    s -> vote(s, VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH, TestKey.DEV_AA,
                            MutationWorld.INFO_ACTION)).atProtocolVersion11()
                    // Amaru has no separate name for Haskell's UnelectedCommitteeVoters (Conway/Rules/Gov.hs:478-481)
                    // and reports VotersDoNotExist, the name its Haskell checker normalises it to
                    // (ValidatePhaseOne/Run.hs:532-533; scenario 00171). A naming alias, not a verdict difference.
                    .withAmaruReports("GOV.VotersDoNotExist"),
            new Mutation("malformed-proposal-coins-per-byte", "GOV.MalformedProposal", List.of(), SIMPLE,
                    "a parameter change setting coinsPerUTxOByte to 0 (not ppuWellFormed from protocol version 10; "
                            + "the protocol version 9 world accepts it, BOOTSTRAP_ACCEPTED)",
                    s -> propose(s, proposal(new ParameterChangeAction(null, ProtocolParamUpdate.builder()
                            .adaPerUtxoByte(BigInteger.ZERO).build(), null)))),
            // ---- Phase 5b: the protocol version 9 (bootstrap) world, the same state at protocol version 9.0. Amaru
            // refuses protocol version 9 (Mutation.AMARU_REFUSES_PV9); each mutant cites its Haskell evidence.
            new Mutation("bootstrap-proposal-v9", "GOV.DisallowedProposalDuringBootstrap", List.of(), SIMPLE,
                    "a no-confidence proposal at protocol version 9 (Gov.hs:435-444, 483: only ParameterChange, "
                            + "HardForkInitiation and InfoAction, isBootstrapAction :633-639)",
                    s -> propose(s, proposal(new NoConfidence(null)))).atProtocolVersion9(),
            new Mutation("bootstrap-treasury-withdrawal-v9", "GOV.DisallowedProposalDuringBootstrap", List.of(), SIMPLE,
                    "a treasury withdrawal to dev-aa, which has no account, returning its deposit to dev-aa, at protocol "
                            + "version 9: disallowed, and the account checks are skipped (unless "
                            + "hardforkConwayBootstrapPhase, Gov.hs:504-520), so it is the only failure",
                    s -> propose(s, proposal(treasuryWithdrawals(TestKey.DEV_AA, MutationWorld.NETWORK, ada(10)),
                            TestKey.DEV_AA, MutationWorld.NETWORK, MutationWorld.GOV_ACTION_DEPOSIT)))
                    .atProtocolVersion9(),
            new Mutation("bootstrap-drep-vote-v9", "GOV.DisallowedVotesDuringBootstrap", List.of(), SIMPLE,
                    "dev-77's DRep votes on the standing parameter change at protocol version 9 (Gov.hs:378-391, 606: "
                            + "DReps vote only on InfoAction during the bootstrap phase)",
                    s -> vote(s, VoterType.DREP_KEY_HASH, TestKey.DEV_77, MutationWorld.PARAMETER_CHANGE_ACTION))
                    .atProtocolVersion9());

    /**
     * Faults that protocol version 10 rejects and the bootstrap phase accepts (ADR-056 Phase 5b): each names a mutant
     * of the protocol version 10 world (the rejecting side, confirmed by Amaru) whose edit the protocol version 9
     * world must accept, with the Haskell gate.
     *
     * @param mutationId the protocol version 10 mutant
     * @param gate       why protocol version 9 accepts it
     */
    public record BootstrapAcceptance(String mutationId, String gate) {
    }

    public static final List<BootstrapAcceptance> BOOTSTRAP_ACCEPTED = List.of(
            new BootstrapAcceptance("withdrawal-not-delegated-to-drep",
                    "Ledger.hs:379-380: unless hardforkConwayBootstrapPhase $ validateWithdrawalsDelegated"),
            new BootstrapAcceptance("deleg-drep-not-registered",
                    "Deleg.hs:220-226: unless hardforkConwayBootstrapPhase, DelegateeDRepNotRegisteredDELEG"),
            new BootstrapAcceptance("proposal-return-account-missing",
                    "Gov.hs:504-508: unless hardforkConwayBootstrapPhase, ProposalReturnAccountDoesNotExist"),
            new BootstrapAcceptance("malformed-proposal-coins-per-byte",
                    "Conway/PParams.hs:949-950: hardforkConwayBootstrapPhase pv || coinsPerUTxOByte /= 0"));

    /** @return the edit of {@code acceptance}'s mutant, built in the protocol version 9 world */
    public static BuiltTx buildBootstrapAccepted(BootstrapAcceptance acceptance) {
        Mutation mutation = find(acceptance.mutationId()).orElseThrow();
        TxSpec spec = base(mutation.base()).copy();
        mutation.edit().accept(spec);
        return ConwayTxBuilder.build(spec, MutationWorld.view(9));
    }

    /** @return {@code acceptance}'s protocol version 9 transaction as a case (expected: valid) */
    public static ConformanceCase bootstrapAcceptedCase(BootstrapAcceptance acceptance) {
        return testCase("bootstrap-accepts:" + acceptance.mutationId(), acceptance.gate(),
                ConformanceCase.Kind.MUTATION_BASE, buildBootstrapAccepted(acceptance).cbor(), new Expected.Pass(),
                List.of(), 9);
    }

    private Mutations() {
    }

    /** @return every mutation */
    public static List<Mutation> all() {
        return ALL;
    }

    public static Optional<Mutation> find(String id) {
        return ALL.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    /** The protocol versions of the mutation worlds. */
    public static final List<Integer> WORLDS = List.of(9, 10, 11);

    /** @return the base spec */
    public static TxSpec base(Mutation.Base base) {
        return base == SCRIPT ? MutationWorld.scriptSpec() : MutationWorld.simpleSpec();
    }

    /** @return the base transaction in the protocol version 10 world */
    public static BuiltTx buildBase(Mutation.Base base) {
        return buildBase(base, 10);
    }

    /** @return the base transaction in a world */
    public static BuiltTx buildBase(Mutation.Base base, int protocolMajor) {
        return ConwayTxBuilder.build(base(base), MutationWorld.view(protocolMajor));
    }

    /** @return the mutant of {@code mutation} */
    public static BuiltTx buildMutant(Mutation mutation) {
        TxSpec spec = base(mutation.base()).copy();
        mutation.edit().accept(spec);
        return ConwayTxBuilder.build(spec, MutationWorld.view(mutation.protocolMajor()));
    }

    /** @return the base transactions of every world as cases (expected: valid) */
    public static List<ConformanceCase> baseCases() {
        List<ConformanceCase> cases = new ArrayList<>();
        for (int world : WORLDS) {
            for (Mutation.Base base : Mutation.Base.values()) {
                String id = "base:" + base.name().toLowerCase() + (world == 10 ? "" : "-v" + world);
                cases.add(testCase(id, "valid base transaction (" + base + ", protocol version " + world + ")",
                        ConformanceCase.Kind.MUTATION_BASE, buildBase(base, world).cbor(), new Expected.Pass(),
                        List.of(), world));
            }
        }
        return cases;
    }

    /** @return every mutant as a case (expected: the mutation's constructor) */
    public static List<ConformanceCase> mutantCases() {
        return ALL.stream().map(Mutations::mutantCase).toList();
    }

    public static ConformanceCase mutantCase(Mutation mutation) {
        String[] name = mutation.covers().split("\\.", 2);
        Expected expected = new Expected.Predicate(name[1], LedgerRuleName.valueOf(name[0]), name[1],
                LedgerFailure.Phase.PHASE_1, null);
        return testCase(mutation.caseId(), mutation.description(), ConformanceCase.Kind.MUTANT,
                buildMutant(mutation).cbor(), expected, mutation.haskellFailures(), mutation.protocolMajor());
    }

    private static ConformanceCase testCase(String id, String title, ConformanceCase.Kind kind, byte[] cbor,
                                            Expected expected, List<String> haskellFailures, int protocolMajor) {
        InMemoryLedgerView view = MutationWorld.view(protocolMajor);
        return new ConformanceCase(id, title, kind, cbor, view, MutationWorld.env(protocolMajor),
                MutationWorld.network(), AmaruScenario.LedgerConstants.NONE, expected, haskellFailures);
    }

    private static BigInteger ada(long amount) {
        return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(1_000_000));
    }

    /**
     * Adds {@code certificate} and {@link MutationWorld#RICH_INPUT} (for deposits), balances the implicit coin Haskell
     * counts ({@code implicit}: refunds and withdrawals minus deposits) and has the credentials' keys sign.
     */
    private static void certificate(TxSpec spec, Certificate certificate, BigInteger implicit, TestKey... signers) {
        spec.inputs.add(MutationWorld.RICH_INPUT);
        spec.certs.add(certificate);
        spec.changeAdjust = implicit;
        for (TestKey key : signers) {
            spec.signers.add(key);
        }
    }

    /** Adds a proposal and {@link MutationWorld#GOV_INPUT}, balanced with {@code ppGovActionDeposit}. */
    private static void propose(TxSpec spec, ProposalProcedure proposal) {
        spec.inputs.add(MutationWorld.GOV_INPUT);
        spec.proposals.add(proposal);
        spec.changeAdjust = MutationWorld.GOV_ACTION_DEPOSIT.negate();
    }

    private static ProposalProcedure proposal(GovAction action) {
        return proposal(action, TestKey.DEV_77, MutationWorld.NETWORK, MutationWorld.GOV_ACTION_DEPOSIT);
    }

    private static ProposalProcedure proposal(GovAction action, TestKey returnKey, Network network,
                                              BigInteger deposit) {
        return ProposalProcedure.builder()
                .deposit(deposit)
                .rewardAccount(MutationWorld.rewardAccount(returnKey, network))
                .govAction(action)
                .anchor(new Anchor("https://example.com/proposal.json", new byte[32]))
                .build();
    }

    private static TreasuryWithdrawalsAction treasuryWithdrawals(TestKey key, Network network, BigInteger amount) {
        return new TreasuryWithdrawalsAction(new ArrayList<>(List.of(new Withdrawal(
                MutationWorld.rewardAccount(key, network), amount))), null);
    }

    private static UpdateCommittee updateCommittee(Set<Credential> remove, Map<Credential, Integer> add) {
        return new UpdateCommittee(null, new LinkedHashSet<>(remove), new LinkedHashMap<>(add),
                new UnitInterval(BigInteger.TWO, BigInteger.valueOf(3)));
    }

    private static ProtocolParamUpdate collateralPercent(int value) {
        return ProtocolParamUpdate.builder().collateralPercent(value).build();
    }

    private static byte[] nativeScriptHash() {
        try {
            return MutationWorld.NATIVE_SCRIPT.getScriptHash();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static GovActionId ccl(org.yanoproject.ledger.rules.view.model.GovActionId id) {
        return new GovActionId(id.txHashHex(), id.index());
    }

    /** One vote by {@code key}'s voter of {@code type} (which signs) on {@code action}. */
    private static void vote(TxSpec spec, VoterType type, TestKey key,
                             org.yanoproject.ledger.rules.view.model.GovActionId action) {
        VotingProcedures votes = new VotingProcedures();
        votes.add(new Voter(type, Credential.fromKey(key.keyHash())), ccl(action), new VotingProcedure(Vote.YES, null));
        spec.votingProcedures = votes;
        if (!spec.signers.contains(key)) {
            spec.signers.add(key);
        }
    }

    private static void withdraw(TxSpec spec, TestKey key, BigInteger amount) {
        spec.withdrawals.add(new Withdrawal(MutationWorld.rewardAccount(key, MutationWorld.NETWORK), amount));
        spec.signers.add(key);
    }

    private static TransactionInput phantomInput() {
        char[] id = new char[64];
        Arrays.fill(id, '9');
        return new TransactionInput(new String(id), 0);
    }

    private static Redeemer mintRedeemer() {
        return Redeemer.builder()
                .tag(RedeemerTag.Mint)
                .index(BigInteger.ZERO)
                .data(ConstrPlutusData.of(0))
                .exUnits(ExUnits.builder().mem(BigInteger.valueOf(1_000)).steps(BigInteger.valueOf(1_000_000)).build())
                .build();
    }

    private static TransactionOutput withScriptRef(TransactionOutput output) {
        try {
            output.setScriptRef(MutationWorld.MALFORMED_SCRIPT.scriptRefBytes());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return output;
    }

    private static CBORMetadata smallMetadata() {
        return new CBORMetadata().put(BigInteger.valueOf(674), "ADR-056 mutation matrix");
    }

    private static CBORMetadata bulkyMetadata() {
        CBORMetadataList chunks = new CBORMetadataList();
        byte[] chunk = new byte[64];
        Arrays.fill(chunk, (byte) 0x5a);
        for (int i = 0; i < 270; i++) {
            chunks.add(chunk);
        }
        return new CBORMetadata().put(BigInteger.valueOf(674), chunks);
    }
}
