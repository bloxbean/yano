package com.bloxbean.cardano.yano.runtime.sync.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeaderValidationPipelineTest {
    @Test
    void customValidatorsRunInOrderAndReportAcceptedStages() {
        HeaderValidator validator = HeaderValidationPipeline.builder()
                .addValidator("first", context -> HeaderValidationResult.accepted("first"))
                .addValidator("second", context -> HeaderValidationResult.accepted("second"))
                .build();

        HeaderValidationResult result = validator.validateShelley(null, null);

        assertThat(result.accepted()).isTrue();
        assertThat(result.acceptedStages()).containsExactly("first", "second");
    }

    @Test
    void pipelineStopsAtFirstRejectedCustomValidator() {
        HeaderValidator validator = HeaderValidationPipeline.builder()
                .addValidator("first", context -> HeaderValidationResult.accepted("first"))
                .addValidator("second", context -> HeaderValidationResult.rejected("custom", "policy", "blocked"))
                .addValidator("third", context -> HeaderValidationResult.accepted("third"))
                .build();

        HeaderValidationResult result = validator.validateShelley(null, null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.stage()).isEqualTo("policy");
        assertThat(result.reason()).isEqualTo("blocked");
        assertThat(result.acceptedStages()).containsExactly("first");
    }

    @Test
    void builderCanDisableAndOverrideValidators() {
        HeaderValidator validator = HeaderValidationPipeline.builder()
                .addValidator("first", context -> HeaderValidationResult.accepted("first"))
                .addValidator("second", context -> HeaderValidationResult.rejected("custom", "old", "old"))
                .disableValidator("first")
                .overrideValidator("second", context -> HeaderValidationResult.accepted("second"))
                .build();

        HeaderValidationResult result = validator.validateShelley(null, null);

        assertThat(result.accepted()).isTrue();
        assertThat(result.acceptedStages()).containsExactly("second");
    }

    @Test
    void overrideRequiresExistingValidator() {
        assertThatThrownBy(() -> HeaderValidationPipeline.builder()
                .overrideValidator("missing", context -> HeaderValidationResult.accepted("missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void noneProfileAcceptsWithoutEvidenceStages() {
        HeaderValidator validator = HeaderValidationPipeline.builder()
                .useProfile("none")
                .build();

        HeaderValidationResult result = validator.validateShelley(null, null);

        assertThat(result.accepted()).isTrue();
        assertThat(result.level()).isEqualTo("none");
        assertThat(result.acceptedStages()).isEmpty();
    }
}
