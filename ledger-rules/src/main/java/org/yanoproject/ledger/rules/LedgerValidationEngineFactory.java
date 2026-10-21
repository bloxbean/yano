package org.yanoproject.ledger.rules;

/**
 * Creates a {@link LedgerValidationEngine} for {@code yano.validation.engine} or
 * {@code yano.validation.shadow-engines} (ADR-056 §7).
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}
 * ({@code META-INF/services/org.yanoproject.ledger.rules.LedgerValidationEngineFactory}), so an optional
 * engine module ({@code amaru-validator}, ADR-057) is available exactly when it is on the classpath. See
 * {@link LedgerValidationEngines} for selection and the startup errors.</p>
 */
public interface LedgerValidationEngineFactory {

    /** @return the engine name used in configuration ({@code scalus}, {@code amaru}, …), lowercase */
    String name();

    /**
     * Creates the engine. Called once per configured use at startup; a failure stops the node.
     *
     * @throws RuntimeException when the engine cannot be created (bad configuration, missing module
     *                          resources, an incompatible module version)
     */
    LedgerValidationEngine create(EngineContext context);
}
