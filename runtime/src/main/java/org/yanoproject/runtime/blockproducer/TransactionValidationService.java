package org.yanoproject.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.TransactionValidator;
import org.yanoproject.ledger.rules.ScriptReferenceResolverScope;
import org.yanoproject.ledger.rules.ValidationError;
import org.yanoproject.ledger.rules.ValidationResult;
import org.yanoproject.ledger.rules.conway.tx.CclTransactions;
import org.yanoproject.runtime.validation.EngineAdmission;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Function;

/**
 * Wraps a {@link TransactionValidator} with UTXO resolution from {@link UtxoState}.
 * Validates transactions against Cardano ledger rules (Phase 1 structural + Phase 2 script execution).
 */
@Slf4j
public class TransactionValidationService {

    private final TransactionValidator validator;
    private final UtxoState utxoState;
    private volatile EngineAdmission engineAdmission;

    public TransactionValidationService(TransactionValidator validator, UtxoState utxoState) {
        this.validator = validator;
        this.utxoState = utxoState;
    }

    /**
     * Routes mempool admission through the validation engines (ADR-056 step 1d); {@code null} restores the
     * legacy path. Block selection ({@link #validate(byte[], Function)}) always stays on the legacy validator
     * until the Phase 6 block overlay.
     */
    public void setEngineAdmission(EngineAdmission engineAdmission) {
        this.engineAdmission = engineAdmission;
    }

    /** @return the engine admission path, or {@code null} for the legacy path */
    public EngineAdmission engineAdmission() {
        return engineAdmission;
    }

    /**
     * Mempool admission. With no validation engines configured this is exactly the legacy
     * {@link #validate(byte[], Function)} / {@link #validate(byte[])}; otherwise the configured admission
     * engine decides (ADR-056 §7).
     *
     * @param resolver the admission-scoped mempool resolver, or {@code null}
     */
    public ValidationResult validateAdmission(byte[] txCbor, String txHash, String origin,
                                              Function<Outpoint, org.yanoproject.api.utxo.model.Utxo> resolver) {
        EngineAdmission engines = engineAdmission;
        if (engines == null) {
            return resolver != null ? validate(txCbor, resolver) : validate(txCbor);
        }
        if (engines.engines().legacyAdmission()) {
            // Shadows never change admission (ADR-056 §7): the legacy verdict decides, the shadows compare.
            ValidationResult legacy = resolver != null ? validate(txCbor, resolver) : validate(txCbor);
            try {
                engines.shadowLegacy(txCbor, txHash, origin, resolver, legacy);
            } catch (RuntimeException e) {
                log.debug("Shadow submission failed for tx {}: {}", txHash, e.toString());
            }
            return legacy;
        }
        return engines.validate(txCbor, txHash, origin, resolver);
    }

    /**
     * Mempool admission: resolves UTXOs from persistent UtxoState.
     */
    public ValidationResult validate(byte[] txCbor) {
        return validate(txCbor, this::resolveFromUtxoState);
    }

    /**
     * Block production: custom resolver with spent-tracking overlay.
     */
    public ValidationResult validate(byte[] txCbor,
            Function<Outpoint, org.yanoproject.api.utxo.model.Utxo> resolver) {
        // Deserialize to extract input references for UTXO resolution
        Transaction transaction;
        try {
            transaction = CclTransactions.deserialize(txCbor);
        } catch (Exception e) {
            log.debug("Failed to deserialize transaction CBOR: {}", e.getMessage());
            return ValidationResult.failure(new ValidationError(
                    "CborDeserialization",
                    "Failed to deserialize transaction: " + e.getMessage(),
                    ValidationError.Phase.PHASE_1));
        }

        // Collect all inputs that need resolution: regular + reference + collateral
        Set<Utxo> inputUtxos = new HashSet<>();
        List<org.yanoproject.api.utxo.model.Utxo> resolvedInputUtxos = new ArrayList<>();
        List<TransactionInput> allInputs = new ArrayList<>();

        if (transaction.getBody().getInputs() != null) {
            allInputs.addAll(transaction.getBody().getInputs());
        }
        if (transaction.getBody().getReferenceInputs() != null) {
            allInputs.addAll(transaction.getBody().getReferenceInputs());
        }
        if (transaction.getBody().getCollateral() != null) {
            allInputs.addAll(transaction.getBody().getCollateral());
        }

        for (TransactionInput input : allInputs) {
            Outpoint op = new Outpoint(input.getTransactionId(), input.getIndex());
            var yaciUtxo = resolver.apply(op);
            if (yaciUtxo == null) {
                return ValidationResult.failure(new ValidationError(
                        "UtxoNotFound",
                        "UTXO not found: " + op.txHash() + "#" + op.index(),
                        ValidationError.Phase.PHASE_1));
            }

            resolvedInputUtxos.add(yaciUtxo);
            inputUtxos.add(UtxoMapper.toCclUtxo(yaciUtxo));
        }

        try (var ignored = ScriptReferenceResolverScope.open(resolvedInputUtxos)) {
            return validator.validate(txCbor, inputUtxos);
        } catch (Exception e) {
            log.debug("Validation threw exception: {}", e.getMessage());
            return ValidationResult.failure(new ValidationError(
                    "ValidationException",
                    "Validation error: " + e.getMessage(),
                    ValidationError.Phase.PHASE_1));
        }
    }

    private org.yanoproject.api.utxo.model.Utxo resolveFromUtxoState(Outpoint outpoint) {
        return utxoState.getUtxo(outpoint).orElse(null);
    }
}
