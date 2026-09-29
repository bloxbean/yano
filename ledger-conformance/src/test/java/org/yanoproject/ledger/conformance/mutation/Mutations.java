package org.yanoproject.ledger.conformance.mutation;

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
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;

import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.HaskellFailureLists;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
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
import java.util.List;
import java.util.Optional;

import static org.yanoproject.ledger.conformance.mutation.Mutation.Base.SCRIPT;
import static org.yanoproject.ledger.conformance.mutation.Mutation.Base.SIMPLE;

/**
 * The mutation matrix: the base transactions and their single-fault mutants (ADR-056 §8). Phase 2 covers the
 * UTXO and UTXOW basics, Phase 3a every UTXO and UTXOS constructor a transaction edit can produce, Phase 3b every
 * UTXOW constructor the protocol version 10 world can produce; Phases 4–5 add the rest.
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
                    s -> s.auxPlutusScripts.add(MutationWorld.MALFORMED_SCRIPT)));

    private Mutations() {
    }

    /** @return every mutation */
    public static List<Mutation> all() {
        return ALL;
    }

    public static Optional<Mutation> find(String id) {
        return ALL.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    /** @return the base spec */
    public static TxSpec base(Mutation.Base base) {
        return base == SCRIPT ? MutationWorld.scriptSpec() : MutationWorld.simpleSpec();
    }

    /** @return the base transaction */
    public static BuiltTx buildBase(Mutation.Base base) {
        return ConwayTxBuilder.build(base(base), MutationWorld.view());
    }

    /** @return the mutant of {@code mutation} */
    public static BuiltTx buildMutant(Mutation mutation) {
        TxSpec spec = base(mutation.base()).copy();
        mutation.edit().accept(spec);
        return ConwayTxBuilder.build(spec, MutationWorld.view());
    }

    /** @return the base transactions as cases (expected: valid) */
    public static List<ConformanceCase> baseCases() {
        return Arrays.stream(Mutation.Base.values())
                .map(base -> testCase("base:" + base.name().toLowerCase(), "valid base transaction (" + base + ")",
                        ConformanceCase.Kind.MUTATION_BASE, buildBase(base).cbor(), new Expected.Pass(), List.of()))
                .toList();
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
                buildMutant(mutation).cbor(), expected, mutation.haskellFailures());
    }

    private static ConformanceCase testCase(String id, String title, ConformanceCase.Kind kind, byte[] cbor,
                                            Expected expected, List<String> haskellFailures) {
        InMemoryLedgerView view = MutationWorld.view();
        return new ConformanceCase(id, title, kind, cbor, view, MutationWorld.env(), MutationWorld.network(),
                AmaruScenario.LedgerConstants.NONE, expected, haskellFailures);
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
