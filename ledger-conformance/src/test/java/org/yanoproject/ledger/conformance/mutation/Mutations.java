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
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;

import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.HaskellFailureLists;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;
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
 * UTXO and UTXOW basics; Phases 3–5 add a mutant per constructor.
 */
public final class Mutations {

    /** The payment every base makes. */
    static final BigInteger PAYMENT = BigInteger.valueOf(10_000_000);
    private static final long TTL = MutationWorld.SLOT + 10_000;

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
                    }));

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
        TxSpec spec = new TxSpec();
        spec.inputs.add(MutationWorld.KEY_INPUT);
        spec.outputs.add(MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK), PAYMENT));
        spec.changeAddress = TestKey.DEV_42.enterpriseAddress(MutationWorld.NETWORK);
        spec.ttl = TTL;
        spec.signers.add(TestKey.DEV_42);
        if (base == SCRIPT) {
            // Inputs are a set ordered by (id, index): KEY_INPUT (11…) sorts before SCRIPT_INPUT (22…), so the
            // spending redeemer points at index 1.
            spec.inputs.add(MutationWorld.SCRIPT_INPUT);
            spec.collateral.add(MutationWorld.collateralInput(0));
            spec.plutusScripts.add(MutationWorld.ALWAYS_SUCCEEDS);
            spec.redeemers.add(Redeemer.builder()
                    .tag(RedeemerTag.Spend)
                    .index(BigInteger.ONE)
                    .data(ConstrPlutusData.of(0))
                    .exUnits(ExUnits.builder().mem(BigInteger.valueOf(100_000))
                            .steps(BigInteger.valueOf(50_000_000)).build())
                    .build());
        }
        return spec;
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
