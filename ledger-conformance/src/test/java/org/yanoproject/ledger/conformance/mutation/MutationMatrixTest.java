package org.yanoproject.ledger.conformance.mutation;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.engines.BaselineEngines;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceEngine;
import org.yanoproject.ledger.conformance.runner.ConformanceRunner;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.fixtures.conformance.ConwayConstructorCatalogue;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mutation matrix's own checks (ADR-056 §8). Each {@code @Covers} test proves that its mutant is a single fault:
 *
 * <ul>
 *   <li>the mutant builds, differs from its base, has the exact fee its spec asks for, and carries valid signatures
 *       over its own body (except the invalid-witness mutant, whose first signature is the fault);</li>
 *   <li>with the Amaru reference engine in the build ({@code -PwithAmaru=true}), the base is valid and the mutant is
 *       rejected with the covered constructor, or, for a fault Haskell reports with several constructors
 *       ({@link Mutation#haskellFailures()}), only with constructors of that list.</li>
 * </ul>
 *
 * <p>How the other engines judge the mutants is the baseline ({@code ConformanceBaselineTest}), not a gate. From
 * Phase 3 on the Java engine must match these too.</p>
 */
class MutationMatrixTest {

    private static final Optional<ConformanceEngine> REFERENCE = BaselineEngines.amaru();

    @Test
    void testKeysAreAmarusCorpusCredentials() {
        // amaru crates/amaru-ledger/tests/data/transaction/common/test-credentials/README.md
        assertThat(TestKey.DEV_42.keyHash()).isEqualTo("93c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8");
        assertThat(TestKey.DEV_AA.keyHash()).isEqualTo("e4ff642ed686b644c5cb39c230c24b0e8a5d850701971bdde3253cec");
    }

    @Test
    void everyMutationCoversACatalogueConstructorOnce() {
        Set<String> ids = new HashSet<>();
        for (Mutation mutation : Mutations.all()) {
            assertThat(ids.add(mutation.id())).as("duplicate id %s", mutation.id()).isTrue();
            assertThat(ConwayConstructorCatalogue.get().find(mutation.covers()))
                    .as(mutation.id()).get().matches(ConwayConstructorCatalogue.Entry::inScope);
            mutation.haskellFailures().forEach(c -> assertThat(ConwayConstructorCatalogue.get().contains(c)).isTrue());
        }
        assertThat(Mutations.all()).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void baseTransactionsAreValidAndExact() {
        for (Mutation.Base base : Mutation.Base.values()) {
            BuiltTx built = Mutations.buildBase(base);
            assertThat(built.fee()).as("%s pays exactly the minimum fee", base).isEqualTo(built.minFee());
            assertSignatures(built, true);
        }
        REFERENCE.ifPresent(amaru -> Mutations.baseCases().forEach(c ->
                assertThat(ConformanceRunner.run(amaru, c).observation().valid()).as("%s under amaru", c.id()).isTrue()));
    }

    @Test
    @Covers("UTXO.FeeTooSmallUTxO")
    void feeTooSmall() {
        BuiltTx mutant = check("fee-too-small");
        assertThat(mutant.fee()).isEqualTo(mutant.minFee().subtract(BigInteger.ONE));
    }

    @Test
    @Covers("UTXO.BadInputsUTxO")
    void badInput() {
        check("bad-input");
    }

    @Test
    @Covers("UTXO.ValueNotConservedUTxO")
    void valueNotConserved() {
        check("value-not-conserved");
    }

    @Test
    @Covers("UTXO.BabbageOutputTooSmallUTxO")
    void outputTooSmall() {
        check("output-too-small");
    }

    @Test
    @Covers("UTXO.WrongNetwork")
    void wrongNetworkOutput() {
        check("wrong-network-output");
    }

    @Test
    @Covers("UTXO.OutsideValidityIntervalUTxO")
    void expired() {
        check("expired");
    }

    @Test
    @Covers("UTXOW.MissingVKeyWitnessesUTXOW")
    void missingVKeyWitness() {
        check("missing-vkey-witness");
    }

    @Test
    @Covers("UTXOW.InvalidWitnessesUTXOW")
    void invalidWitness() {
        check("invalid-witness");
    }

    @Test
    @Covers("UTXO.MaxTxSizeUTxO")
    void maxTxSize() {
        BuiltTx mutant = check("max-tx-size");
        assertThat(mutant.cbor().length - 1).isGreaterThan(MutationWorld.protocolParams().getMaxTxSize());
    }

    @Test
    @Covers("UTXO.NoCollateralInputs")
    void noCollateral() {
        check("no-collateral");
    }

    @Test
    @Covers("UTXO.TooManyCollateralInputs")
    void tooManyCollateralInputs() {
        check("too-many-collateral");
    }

    @Test
    @Covers("UTXO.WrongNetworkInTxBody")
    void wrongNetworkInTxBody() {
        check("wrong-network-in-body");
    }

    @Test
    @Covers("UTXOW.ExtraneousScriptWitnessesUTXOW")
    void extraneousScriptWitness() {
        check("extraneous-script-witness");
    }

    @Test
    @Covers("UTXOW.ConflictingMetadataHash")
    void conflictingMetadataHash() {
        check("conflicting-metadata-hash");
    }

    @Test
    @Covers("UTXOW.MissingTxBodyMetadataHash")
    void missingMetadataHash() {
        BuiltTx mutant = check("missing-metadata-hash");
        assertThat(mutant.tx().getBody().getAuxiliaryDataHash()).isNull();
        assertThat(mutant.tx().getAuxiliaryData()).isNotNull();
    }

    @Test
    @Covers("UTXOW.MissingTxMetadata")
    void missingMetadata() {
        BuiltTx mutant = check("missing-metadata");
        assertThat(mutant.tx().getBody().getAuxiliaryDataHash()).isNotNull();
        assertThat(mutant.tx().getAuxiliaryData()).isNull();
    }

    /** Builds a mutant, checks it is well formed, and has the reference engine confirm the single fault. */
    private static BuiltTx check(String id) {
        Mutation mutation = Mutations.find(id).orElseThrow();
        BuiltTx base = Mutations.buildBase(mutation.base());
        BuiltTx mutant = Mutations.buildMutant(mutation);
        assertThat(mutant.cbor()).as("%s differs from its base", id).isNotEqualTo(base.cbor());
        assertThat(mutant.fee()).as("%s fee", id).isEqualTo(mutant.minFee().add(feeAdjust(mutation)));
        assertSignatures(mutant, !id.equals("invalid-witness"));

        REFERENCE.ifPresent(amaru -> {
            ConformanceCase testCase = Mutations.mutantCase(mutation);
            Observation observation = ConformanceRunner.run(amaru, testCase).observation();
            assertThat(observation.valid()).as("%s under amaru", id).isFalse();
            List<String> failures = observation.failures().stream().map(Observation.Failure::qualifiedName).toList();
            Set<String> allowed = testCase.acceptedFirst();
            assertThat(failures).as("%s under amaru", id).isNotEmpty();
            assertThat(allowed).as("%s under amaru: only the mutation's fault", id).containsAll(failures);
        });
        return mutant;
    }

    private static BigInteger feeAdjust(Mutation mutation) {
        TxSpec spec = Mutations.base(mutation.base()).copy();
        mutation.edit().accept(spec);
        return spec.feeAdjust;
    }

    /** Every vkey witness signs the body bytes as they are in the transaction ({@link TxIdentity}). */
    private static void assertSignatures(BuiltTx built, boolean allValid) {
        byte[] bodyHash = Blake2bUtil.blake2bHash256(TxIdentity.bodyBytes(built.cbor()));
        Transaction tx = built.tx();
        List<VkeyWitness> witnesses = tx.getWitnessSet().getVkeyWitnesses();
        assertThat(witnesses).isNotEmpty();
        long valid = witnesses.stream().filter(w -> CryptoConfiguration.INSTANCE.getSigningProvider()
                .verify(w.getSignature(), bodyHash, w.getVkey())).count();
        assertThat(valid).isEqualTo(allValid ? witnesses.size() : witnesses.size() - 1);
    }
}
