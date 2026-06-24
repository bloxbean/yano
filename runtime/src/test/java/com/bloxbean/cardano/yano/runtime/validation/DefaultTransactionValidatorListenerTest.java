package com.bloxbean.cardano.yano.runtime.validation;

import com.bloxbean.cardano.yano.api.events.TransactionValidateEvent;
import com.bloxbean.cardano.yano.ledgerrules.ValidationError;
import com.bloxbean.cardano.yano.ledgerrules.ValidationResult;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTransactionValidatorListenerTest {

    @Test
    void usesCurrentValidationServiceFromSupplier() {
        AtomicReference<TransactionValidationService> current = new AtomicReference<>(
                service("first", "first failure"));
        DefaultTransactionValidatorListener listener = new DefaultTransactionValidatorListener(current::get);

        TransactionValidateEvent first = new TransactionValidateEvent(new byte[] {1}, "hash1", "test");
        listener.onTransactionValidate(first);
        current.set(service("second", "second failure"));
        TransactionValidateEvent second = new TransactionValidateEvent(new byte[] {2}, "hash2", "test");
        listener.onTransactionValidate(second);

        assertThat(first.rejections()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reason()).isEqualTo("first failure"));
        assertThat(second.rejections()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reason()).isEqualTo("second failure"));
    }

    private static TransactionValidationService service(String rule, String message) {
        return new TransactionValidationService(null, null) {
            @Override
            public ValidationResult validate(byte[] txCbor) {
                return ValidationResult.failure(new ValidationError(
                        rule, message, ValidationError.Phase.PHASE_1));
            }
        };
    }
}
