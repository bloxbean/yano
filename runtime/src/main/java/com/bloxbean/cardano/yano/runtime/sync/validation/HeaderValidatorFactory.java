package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.config.UpstreamValidationConfig;

import java.util.Locale;

/**
 * Builds runtime header validators from upstream validation config.
 */
public final class HeaderValidatorFactory {
    private HeaderValidatorFactory() {
    }

    public static HeaderValidator from(UpstreamValidationConfig config, EpochParamProvider epochParamProvider) {
        String level = config != null ? normalize(config.getLevel()) : "none";
        if ("none".equals(level)) {
            return HeaderValidator.none();
        }
        long slotsPerKESPeriod = epochParamProvider != null ? epochParamProvider.getSlotsPerKESPeriod() : 129600;
        long maxKESEvolutions = epochParamProvider != null ? epochParamProvider.getMaxKESEvolutions() : 62;
        return new ShelleyHeaderValidator(level, slotsPerKESPeriod, maxKESEvolutions);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? "none"
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
