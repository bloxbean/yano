package org.yanoproject.ledger.rules.conway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * Result of transaction validation against Cardano ledger rules.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleValidationResult {

    private boolean valid;

    @Builder.Default
    private List<RuleValidationError> errors = Collections.emptyList();

    /**
     * Create a successful validation result.
     */
    public static RuleValidationResult success() {
        return RuleValidationResult.builder()
                .valid(true)
                .errors(Collections.emptyList())
                .build();
    }

    /**
     * Create a failed validation result with the given errors.
     */
    public static RuleValidationResult failure(List<RuleValidationError> errors) {
        return RuleValidationResult.builder()
                .valid(false)
                .errors(errors != null ? errors : Collections.emptyList())
                .build();
    }

    /**
     * Create a failed validation result with a single error.
     */
    public static RuleValidationResult failure(RuleValidationError error) {
        return RuleValidationResult.builder()
                .valid(false)
                .errors(List.of(error))
                .build();
    }
}
