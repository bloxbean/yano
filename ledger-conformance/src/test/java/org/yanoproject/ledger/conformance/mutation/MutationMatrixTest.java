package org.yanoproject.ledger.conformance.mutation;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.engines.BaselineEngines;
import org.yanoproject.ledger.conformance.engines.JavaViewEngine;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceEngine;
import org.yanoproject.ledger.conformance.runner.ConformanceRunner;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.fixtures.conformance.ConwayConstructorCatalogue;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.BuiltTx;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;

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
 *       ({@link Mutation#haskellFailures()}), only with constructors of that list; where Amaru names the fault
 *       differently from Haskell ({@link Mutation#amaruReports()}, a recorded divergence), with that name;</li>
 *   <li>the Java engine ({@code java-engine}) accepts the bases and rejects every mutant of the families it
 *       implements (Phase 3: {@code UTXOW}, {@code UTXO}, {@code UTXOS}; Phase 4: {@code CERTS}, {@code DELEG},
 *       {@code POOL}, {@code GOVCERT}) with exactly Haskell's failure list, or the single covered constructor.</li>
 * </ul>
 *
 * <p>Mutants of constructors that exist only from protocol version 11 are built and validated in the protocol
 * version 11 world ({@link Mutation#protocolMajor()}), whose bases must be valid too.</p>
 *
 * <p>How the other engines judge the mutants is the baseline ({@code ConformanceBaselineTest}), not a gate.</p>
 */
class MutationMatrixTest {

    private static final Optional<ConformanceEngine> REFERENCE = BaselineEngines.amaru();
    private static final ConformanceEngine JAVA = new JavaViewEngine();
    /**
     * The rule families the Java engine implements so far (ADR-056 Phases 3 and 4; of {@code LEDGER} only the two
     * protocol-version-11 withdrawal checks, the only {@code LEDGER} mutants until Phase 5).
     */
    private static final Set<String> JAVA_FAMILIES = Set.of("UTXO", "UTXOW", "UTXOS", "CERTS", "DELEG", "POOL",
            "GOVCERT", "LEDGER");

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
        for (int world : Mutations.WORLDS) {
            for (Mutation.Base base : Mutation.Base.values()) {
                BuiltTx built = Mutations.buildBase(base, world);
                assertThat(built.fee()).as("%s pays exactly the minimum fee", base).isEqualTo(built.minFee());
                assertSignatures(built, true);
            }
        }
        REFERENCE.ifPresent(amaru -> Mutations.baseCases().forEach(c ->
                assertThat(ConformanceRunner.run(amaru, c).observation().valid()).as("%s under amaru", c.id()).isTrue()));
        Mutations.baseCases().forEach(c -> assertThat(ConformanceRunner.run(JAVA, c).observation().label())
                .as("%s under the java engine", c.id()).isEqualTo("Valid"));
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

    @Test
    @Covers("UTXO.InputSetEmptyUTxO")
    void emptyInputs() {
        assertThat(check("empty-inputs").tx().getBody().getInputs()).isEmpty();
    }

    @Test
    @Covers("UTXO.OutsideValidityIntervalUTxO")
    void notYetValid() {
        check("not-yet-valid");
    }

    @Test
    @Covers("UTXO.ScriptsNotPaidUTxO")
    void scriptsNotPaid() {
        check("scripts-not-paid");
    }

    @Test
    @Covers("UTXO.CollateralContainsNonADA")
    void collateralContainsNonAda() {
        check("collateral-non-ada");
    }

    @Test
    @Covers("UTXO.InsufficientCollateral")
    void insufficientCollateral() {
        check("insufficient-collateral");
    }

    @Test
    @Covers("UTXO.IncorrectTotalCollateralField")
    void incorrectTotalCollateral() {
        assertThat(check("incorrect-total-collateral").tx().getBody().getTotalCollateral())
                .isEqualTo(MutationWorld.COLLATERAL_LOVELACE.subtract(BigInteger.ONE));
    }

    @Test
    @Covers("UTXO.OutputTooBigUTxO")
    void outputTooBig() {
        check("output-too-big");
    }

    @Test
    @Covers("UTXO.OutputBootAddrAttrsTooBig")
    void bootAddrAttrsTooBig() {
        check("boot-addr-attrs-too-big");
    }

    @Test
    @Covers("UTXO.ExUnitsTooBigUTxO")
    void exUnitsTooBig() {
        check("ex-units-too-big");
    }

    @Test
    @Covers("UTXO.BabbageNonDisjointRefInputs")
    void nonDisjointReferenceInputs() {
        check("non-disjoint-reference-inputs");
    }

    @Test
    @Covers("UTXOS.ValidationTagMismatch")
    void scriptFails() {
        check("script-fails");
    }

    @Test
    @Covers("UTXOS.ValidationTagMismatch")
    void passedUnexpectedly() {
        assertThat(check("passed-unexpectedly").tx().isValid()).isFalse();
    }

    @Test
    @Covers("UTXOW.MissingScriptWitnessesUTXOW")
    void missingScriptWitness() {
        check("missing-script-witness");
    }

    @Test
    @Covers("UTXOW.ScriptWitnessNotValidatingUTXOW")
    void nativeScriptNotValidating() {
        check("native-script-not-validating");
    }

    @Test
    @Covers("UTXOW.UnspendableUTxONoDatumHash")
    void unspendableNoDatum() {
        check("unspendable-no-datum");
    }

    @Test
    @Covers("UTXOW.MissingRequiredDatums")
    void missingRequiredDatum() {
        check("missing-required-datum");
    }

    @Test
    @Covers("UTXOW.NotAllowedSupplementalDatums")
    void notAllowedSupplementalDatum() {
        assertThat(check("not-allowed-supplemental-datum").tx().getBody().getScriptDataHash()).isNotNull();
    }

    @Test
    @Covers("UTXOW.ExtraRedeemers")
    void extraRedeemer() {
        check("extra-redeemer");
    }

    @Test
    @Covers("UTXOW.MissingRedeemers")
    void missingRedeemer() {
        check("missing-redeemer");
    }

    @Test
    @Covers("UTXOW.PPViewHashesDontMatch")
    void scriptIntegrityHash() {
        check("script-integrity-hash");
    }

    @Test
    @Covers("UTXOW.MalformedScriptWitnesses")
    void malformedScriptWitness() {
        check("malformed-script-witness");
    }

    @Test
    @Covers("UTXOW.MalformedScriptWitnesses")
    void scriptTrailingBytes() {
        check("script-trailing-bytes");
    }

    @Test
    @Covers("UTXOW.MalformedScriptWitnesses")
    void scriptUnavailableBuiltin() {
        check("script-unavailable-builtin");
    }

    @Test
    @Covers("UTXOW.MalformedReferenceScripts")
    void malformedReferenceScript() {
        check("malformed-reference-script");
    }

    @Test
    @Covers("UTXOW.InvalidMetadata")
    void invalidMetadata() {
        assertThat(check("invalid-metadata").tx().getAuxiliaryData()).isNotNull();
    }

    // ------------------------------------------------------------------ Phase 4 (and the protocol version 11 world)

    @Test
    @Covers("UTXOW.ScriptIntegrityHashMismatch")
    void scriptIntegrityHashV11() {
        check("script-integrity-hash-v11");
    }

    @Test
    @Covers("CERTS.WithdrawalsNotInRewardsCERTS")
    void withdrawalNotDraining() {
        check("withdrawal-not-draining");
    }

    @Test
    @Covers("LEDGER.ConwayWithdrawalsMissingAccounts")
    void withdrawalMissingAccountV11() {
        check("withdrawal-missing-account-v11");
    }

    @Test
    @Covers("LEDGER.ConwayIncompleteWithdrawals")
    void withdrawalIncompleteV11() {
        check("withdrawal-incomplete-v11");
    }

    @Test
    @Covers("DELEG.IncorrectDepositDELEG")
    void registrationDepositIncorrect() {
        check("reg-deposit-incorrect");
    }

    @Test
    @Covers("DELEG.IncorrectDepositDELEG")
    void deregistrationRefundIncorrect() {
        check("unreg-refund-incorrect");
    }

    @Test
    @Covers("DELEG.DepositIncorrectDELEG")
    void registrationDepositIncorrectV11() {
        check("reg-deposit-incorrect-v11");
    }

    @Test
    @Covers("DELEG.RefundIncorrectDELEG")
    void deregistrationRefundIncorrectV11() {
        check("unreg-refund-incorrect-v11");
    }

    @Test
    @Covers("DELEG.StakeKeyRegisteredDELEG")
    void registrationOfARegisteredCredential() {
        check("reg-already-registered");
    }

    @Test
    @Covers("DELEG.StakeKeyNotRegisteredDELEG")
    void deregistrationOfAnUnregisteredCredential() {
        check("unreg-not-registered");
    }

    @Test
    @Covers("DELEG.StakeKeyHasNonZeroAccountBalanceDELEG")
    void deregistrationWithARewardBalance() {
        check("unreg-non-zero-balance");
    }

    @Test
    @Covers("DELEG.DelegateeStakePoolNotRegisteredDELEG")
    void delegationToAnUnregisteredPool() {
        check("deleg-pool-not-registered");
    }

    @Test
    @Covers("DELEG.DelegateeDRepNotRegisteredDELEG")
    void delegationToAnUnregisteredDRep() {
        check("deleg-drep-not-registered");
    }

    @Test
    @Covers("POOL.StakePoolNotRegisteredOnKeyPOOL")
    void retirementOfAnUnregisteredPool() {
        check("retire-unregistered-pool");
    }

    @Test
    @Covers("POOL.StakePoolRetirementWrongEpochPOOL")
    void retirementAtTheCurrentEpoch() {
        check("retire-wrong-epoch");
    }

    @Test
    @Covers("POOL.StakePoolCostTooLowPOOL")
    void poolCostTooLow() {
        check("pool-cost-too-low");
    }

    @Test
    @Covers("POOL.WrongNetworkPOOL")
    void poolRewardAccountOnAnotherNetwork() {
        check("pool-wrong-network");
    }

    @Test
    @Covers("POOL.PoolMedataHashTooBig")
    void poolMetadataHashTooBig() {
        check("pool-metadata-hash-too-big");
    }

    @Test
    @Covers("POOL.VRFKeyHashAlreadyRegistered")
    void poolVrfKeyHashTakenV11() {
        check("pool-vrf-taken-v11");
    }

    @Test
    @Covers("GOVCERT.ConwayDRepAlreadyRegistered")
    void drepAlreadyRegistered() {
        check("drep-already-registered");
    }

    @Test
    @Covers("GOVCERT.ConwayDRepIncorrectDeposit")
    void drepDepositIncorrect() {
        check("drep-deposit-incorrect");
    }

    @Test
    @Covers("GOVCERT.ConwayDRepNotRegistered")
    void drepNotRegistered() {
        check("drep-not-registered");
    }

    @Test
    @Covers("GOVCERT.ConwayDRepIncorrectRefund")
    void drepRefundIncorrect() {
        check("drep-refund-incorrect");
    }

    @Test
    @Covers("GOVCERT.ConwayCommitteeHasPreviouslyResigned")
    void committeeMemberResigned() {
        check("committee-resigned");
    }

    @Test
    @Covers("GOVCERT.ConwayCommitteeIsUnknown")
    void committeeMemberUnknown() {
        check("committee-unknown");
    }

    /** Builds a mutant, checks it is well formed, and has the reference engine confirm the single fault. */
    private static BuiltTx check(String id) {
        Mutation mutation = Mutations.find(id).orElseThrow();
        BuiltTx base = Mutations.buildBase(mutation.base(), mutation.protocolMajor());
        BuiltTx mutant = Mutations.buildMutant(mutation);
        assertThat(mutant.cbor()).as("%s differs from its base", id).isNotEqualTo(base.cbor());
        assertThat(mutant.fee()).as("%s fee", id).isEqualTo(mutant.minFee().add(feeAdjust(mutation)));
        assertSignatures(mutant, !id.equals("invalid-witness"));

        ConformanceCase testCase = Mutations.mutantCase(mutation);
        REFERENCE.ifPresent(amaru -> {
            Observation observation = ConformanceRunner.run(amaru, testCase).observation();
            if (Mutation.AMARU_ACCEPTS.equals(mutation.amaruReports())) {
                assertThat(observation.valid()).as("%s under amaru (recorded divergence: Amaru accepts)", id)
                        .isTrue();
                return;
            }
            assertThat(observation.valid()).as("%s under amaru", id).isFalse();
            List<String> failures = observation.failures().stream().map(Observation.Failure::qualifiedName).toList();
            Set<String> allowed = mutation.amaruReports() != null ? Set.of(mutation.amaruReports())
                    : testCase.acceptedFirst();
            assertThat(failures).as("%s under amaru", id).isNotEmpty();
            assertThat(allowed).as("%s under amaru: only the mutation's fault", id).containsAll(failures);
        });
        if (JAVA_FAMILIES.contains(mutation.covers().split("\\.")[0])) {
            Observation observation = ConformanceRunner.run(JAVA, testCase).observation();
            List<String> failures = observation.failures().stream().map(Observation.Failure::qualifiedName).toList();
            List<String> expected = mutation.haskellFailures().isEmpty() ? List.of(mutation.covers())
                    : mutation.haskellFailures();
            assertThat(failures).as("%s under the java engine: Haskell's failure list", id).isEqualTo(expected);
        }
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
