package org.yanoproject.runtime.sync.validation;

import org.yanoproject.api.config.UpstreamValidationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderValidatorFactoryTest {
    @Test
    void factoryAppliesCustomizersAfterSelectedProfile() {
        HeaderValidator validator = HeaderValidatorFactory.from(
                UpstreamValidationConfig.builder()
                        .level("none")
                        .build(),
                null,
                HeaderValidationNonceProvider.none(),
                HeaderValidationLedgerViewProvider.none(),
                List.of(builder -> builder.addValidator(
                        "custom-policy",
                        context -> HeaderValidationResult.accepted("custom-policy"))));

        HeaderValidationResult result = validator.validateShelley(null, null);

        assertThat(result.accepted()).isTrue();
        assertThat(result.acceptedStages()).containsExactly("custom-policy");
    }
}
