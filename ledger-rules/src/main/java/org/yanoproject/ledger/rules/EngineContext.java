package org.yanoproject.ledger.rules;

import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;

import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What the node hands a {@link LedgerValidationEngineFactory} (ADR-056 §7, step 1d).
 *
 * <p>Engines read ledger state, including the epoch-effective protocol parameters, from each request's
 * view; the suppliers here are for engine set-up and for engines that still need the node's clock.</p>
 */
public interface EngineContext {

    /**
     * Looks up a configuration value, for example {@code yano.validation.amaru.timeout-ms}.
     *
     * @return the value as configured, or empty when the key is not set
     */
    Optional<String> config(String key);

    /**
     * @return the genesis-derived network facts. The supplier may be called late: on a devnet the system
     *         start can be resolved only after startup. It throws {@link IllegalStateException} while
     *         something it needs is not known yet.
     */
    Supplier<NetworkParameters> network();

    /** @return the node's epoch-effective protocol parameters for a slot (the legacy source) */
    EpochProtocolParamsSupplier protocolParams();

    /** @return slot timing for validity intervals and script contexts */
    SlotConfigSupplier slotConfig();

    /** @return the current tip slot, or a negative value when unknown */
    LongSupplier currentSlot();

    /**
     * @return the node's Scalus phase-2 evaluator (engines {@code java-scalus} and {@code amaru-scalus}), or
     *         {@code null} when none is available; engines that delegate Plutus execution
     *         must then fail closed on transactions that need it
     */
    ScriptPhaseEvaluator scriptPhaseEvaluator();

    /**
     * @return the julc phase-2 evaluator of engine {@code java-julc} (ADR-056 Phase 7c), or {@code null} when the node has
     *         none
     */
    default ScriptPhaseEvaluator julcScriptPhaseEvaluator() {
        return null;
    }

    /** @return how many threads validate transactions concurrently (sizes per-thread engine resources) */
    int validationThreads();

    /** Reads an integer setting, falling back to {@code defaultValue} when unset or blank. */
    default int intConfig(String key, int defaultValue) {
        return config(key).filter(v -> !v.isBlank()).map(v -> {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be an integer: " + v, e);
            }
        }).orElse(defaultValue);
    }

    /** Reads a long setting, falling back to {@code defaultValue} when unset or blank. */
    default long longConfig(String key, long defaultValue) {
        return config(key).filter(v -> !v.isBlank()).map(v -> {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be an integer: " + v, e);
            }
        }).orElse(defaultValue);
    }
}
