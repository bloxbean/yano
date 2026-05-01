package com.bloxbean.cardano.yano.wallet.yano;

import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YanoTransactionProcessorTest {
    @Test
    void submitTransactionWrapsYanoTxHashAsSuccessfulCclResult() throws ApiException {
        YanoTransactionProcessor processor = new YanoTransactionProcessor(cbor -> "txhash");

        Result<String> result = processor.submitTransaction(new byte[]{1, 2, 3});

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getResponse()).isEqualTo("txhash");
    }

    @Test
    void submitTransactionConvertsRuntimeFailureToApiException() {
        YanoTransactionProcessor processor = new YanoTransactionProcessor(cbor -> {
            throw new IllegalStateException("validation failed");
        });

        assertThatThrownBy(() -> processor.submitTransaction(new byte[]{1, 2, 3}))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unable to submit transaction through Yano");
    }

    @Test
    void evaluationReturnsUnsupportedResultForMvp() {
        YanoTransactionProcessor processor = new YanoTransactionProcessor(cbor -> "txhash");

        Result<?> result = processor.evaluateTx(new byte[]{1, 2, 3}, java.util.Set.of());

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResponse()).contains("not available");
    }
}
