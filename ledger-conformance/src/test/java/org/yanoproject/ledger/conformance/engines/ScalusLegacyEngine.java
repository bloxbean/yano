package org.yanoproject.ledger.conformance.engines;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;

import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceEngine;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.ledger.rules.ValidationError;
import org.yanoproject.ledger.rules.ValidationResult;
import org.yanoproject.scalusbridge.ScalusBasedTransactionValidator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The legacy admission path as the node runs it: {@code TransactionValidationService.validate} in front of
 * {@link ScalusBasedTransactionValidator}.
 *
 * <ul>
 *   <li><b>Pre-checks</b> ({@code TransactionValidationService.java:88-96, 113-121}): a transaction CCL cannot
 *       decode is rejected ({@code CborDeserialization}, named {@code ENGINE.DecodingFailure}), and so is one with a
 *       spending, reference or collateral input the UTxO set does not have ({@code UtxoNotFound}, named
 *       {@code UTXO.BadInputsUTxO}), before Scalus runs.</li>
 *   <li><b>Validator</b>: one per network id and supplementary setting, built once with suppliers that read the
 *       current case (protocol parameters, reference scripts, slot timing, slot, epoch, and a
 *       {@code LedgerStateProvider} over the view). With {@code supplementary} the copied Java
 *       {@code CertificateValidationRule} and {@code GovernanceValidationRule} run after Scalus accepts
 *       ({@code yano.validation.supplementary-rules-enabled=true}).</li>
 * </ul>
 *
 * <p>The legacy path reports one failure: a Scalus exception class name, or a Java rule name for the
 * supplementary rules. {@link LegacyFailureNames} names both after Haskell.</p>
 */
public final class ScalusLegacyEngine implements ConformanceEngine {

    private static final List<String> JAVA_RULE_NAMES = List.of("CertificateValidation", "GovernanceValidation");

    /** The case being validated on this thread, read by the cached validator's suppliers. */
    private record Current(ConformanceCase testCase, ProtocolParams params, ResolvedInputs inputs,
                           SlotConfigSupplier timing) {
    }

    private final boolean supplementary;
    private final ThreadLocal<Current> current = new ThreadLocal<>();
    private final Map<Integer, ScalusBasedTransactionValidator> validators = new ConcurrentHashMap<>();

    public ScalusLegacyEngine(boolean supplementary) {
        this.supplementary = supplementary;
    }

    @Override
    public String name() {
        return supplementary ? "scalus-legacy+supplementary" : "scalus-legacy";
    }

    @Override
    public String description() {
        return (supplementary
                ? "ScalusBasedTransactionValidator with the supplementary CCL certificate and governance rules"
                : "ScalusBasedTransactionValidator (the default admission path), no supplementary rules")
                + ", behind TransactionValidationService's decode and UTxO-resolution pre-checks";
    }

    @Override
    public Observation validate(ConformanceCase testCase) {
        byte[] txCbor = testCase.txCbor();
        Transaction tx;
        try {
            tx = Transaction.deserialize(txCbor);
        } catch (Exception e) {
            return reject("ENGINE", "DecodingFailure", "CborDeserialization: Failed to deserialize transaction: "
                    + e.getMessage());
        }
        ResolvedInputs resolved = ResolvedInputs.resolve(tx, testCase.view());
        if (!resolved.unresolved().isEmpty()) {
            TransactionInput missing = resolved.unresolved().getFirst();
            return reject("UTXO", "BadInputsUTxO", "UtxoNotFound: UTXO not found: " + missing.getTransactionId()
                    + "#" + missing.getIndex());
        }
        int networkId = testCase.env().networkId() == NetworkId.MAINNET ? 1 : 0;
        current.set(new Current(testCase, testCase.view().protocolParams().require("protocol parameters"), resolved,
                CaseTiming.slotConfig(testCase)));
        try {
            ValidationResult result = validators.computeIfAbsent(networkId, this::newValidator)
                    .validate(txCbor, resolved.utxos());
            if (result.valid()) {
                return Observation.accepted();
            }
            int major = testCase.env().protocolMajor();
            return Observation.rejected(result.errors().stream().map(error -> name(error, major, testCase)).toList());
        } finally {
            current.remove();
        }
    }

    private ScalusBasedTransactionValidator newValidator(int networkId) {
        ScriptSupplier scripts = new ScriptSupplier() {
            @Override
            public Optional<PlutusScript> getScript(String scriptHash) {
                return current.get().inputs().scriptSupplier().getScript(scriptHash);
            }
        };
        SlotConfigSupplier timing = new SlotConfigSupplier() {
            @Override
            public SlotConfig getSlotConfig() {
                return current.get().timing().getSlotConfig();
            }

            @Override
            public EpochSlotCalc getEpochSlotCalc() {
                return current.get().timing().getEpochSlotCalc();
            }
        };
        return new ScalusBasedTransactionValidator(slot -> current.get().params(), scripts, timing, networkId,
                new ViewStateProvider(() -> current.get().testCase().view(),
                        () -> current.get().testCase().env().currentEpoch()),
                () -> current.get().testCase().env().currentSlot(),
                slot -> Math.toIntExact(current.get().testCase().env().currentEpoch()), supplementary);
    }

    private static Observation reject(String rule, String constructor, String raw) {
        return Observation.rejected(List.of(new Observation.Failure(rule, constructor, raw)));
    }

    private static Observation.Failure name(ValidationError error, int protocolMajor, ConformanceCase testCase) {
        if (JAVA_RULE_NAMES.contains(error.rule())) {
            return LegacyFailureNames.javaRule(error.rule(), error.message(), protocolMajor, testCase.view());
        }
        return LegacyFailureNames.scalus(error.rule(), error.message(), protocolMajor);
    }
}
