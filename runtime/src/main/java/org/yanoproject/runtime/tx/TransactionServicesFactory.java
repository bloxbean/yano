package org.yanoproject.runtime.tx;

import java.util.Optional;

/**
 * Builds concrete transaction services without installing them into the runtime.
 */
@FunctionalInterface
public interface TransactionServicesFactory {
    Optional<TransactionServices> create(TransactionBootstrapContext context,
                                         TransactionBootstrapOptions options);
}
