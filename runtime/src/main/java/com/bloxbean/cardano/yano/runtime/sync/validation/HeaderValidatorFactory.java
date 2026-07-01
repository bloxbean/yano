package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.config.UpstreamValidationConfig;

import java.util.List;
import java.util.Locale;

/**
 * Builds runtime header validators from upstream validation config.
 */
public final class HeaderValidatorFactory {
    private HeaderValidatorFactory() {
    }

    public static HeaderValidator from(UpstreamValidationConfig config, EpochParamProvider epochParamProvider) {
        return from(config, epochParamProvider, HeaderValidationNonceProvider.none());
    }

    public static HeaderValidator from(UpstreamValidationConfig config,
                                       EpochParamProvider epochParamProvider,
                                       HeaderValidationNonceProvider nonceProvider) {
        return from(config, epochParamProvider, nonceProvider, HeaderValidationLedgerViewProvider.none());
    }

    public static HeaderValidator from(UpstreamValidationConfig config,
                                       EpochParamProvider epochParamProvider,
                                       HeaderValidationNonceProvider nonceProvider,
                                       HeaderValidationLedgerViewProvider ledgerViewProvider) {
        return from(config, epochParamProvider, nonceProvider, ledgerViewProvider, List.of());
    }

    public static HeaderValidator from(UpstreamValidationConfig config,
                                       EpochParamProvider epochParamProvider,
                                       HeaderValidationNonceProvider nonceProvider,
                                       HeaderValidationLedgerViewProvider ledgerViewProvider,
                                       List<HeaderValidationCustomizer> customizers) {
        String level = config != null ? normalize(config.getLevel()) : "none";
        List<HeaderValidationCustomizer> effectiveCustomizers = customizers != null ? customizers : List.of();
        if ("none".equals(level) && effectiveCustomizers.isEmpty()) {
            return HeaderValidator.none();
        }
        long slotsPerKESPeriod = epochParamProvider != null ? epochParamProvider.getSlotsPerKESPeriod() : 129600;
        long maxKESEvolutions = epochParamProvider != null ? epochParamProvider.getMaxKESEvolutions() : 62;
        HeaderValidationPipeline.Builder builder = HeaderValidationPipeline.builder()
                .slotsPerKESPeriod(slotsPerKESPeriod)
                .maxKESEvolutions(maxKESEvolutions)
                .nonceProvider(nonceProvider)
                .ledgerViewProvider(ledgerViewProvider)
                .useProfile(level);
        if (config == null || !config.opCertCounterModeEnabled()) {
            builder.disableValidator(HeaderValidationPipeline.STAGE_OPCERT_STATE);
        }
        for (HeaderValidationCustomizer customizer : effectiveCustomizers) {
            if (customizer != null) {
                customizer.customize(builder);
            }
        }
        return builder.build();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? "none"
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
