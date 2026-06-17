package com.bloxbean.cardano.yano.app.api.utils;

import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.model.TxEvaluationResult;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluationResourceTest {

    @Test
    void cborPayloadKeepsRawBytes() {
        byte[] rawCbor = new byte[]{(byte) 0x84, 0x01, 0x02};

        assertArrayEquals(rawCbor, EvaluationResource.normalizeCborPayload(rawCbor));
    }

    @Test
    void cborPayloadAcceptsAsciiHexForCompatibility() {
        assertArrayEquals(new byte[]{(byte) 0x84, 0x01, 0x02},
                EvaluationResource.normalizeCborPayload(" 840102\n".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void evaluateReturnsEvaluatorExUnitsWithoutMutation() {
        EvaluationResource resource = new EvaluationResource();
        resource.txEvaluationGateway = new TxEvaluationGateway() {
            @Override
            public boolean isTransactionEvaluationAvailable() {
                return true;
            }

            @Override
            public List<TxEvaluationResult> evaluateTransaction(byte[] txCbor) {
                return List.of(new TxEvaluationResult("spend", 0, 100, 200));
            }
        };

        Response response = resource.evaluateCbor(new byte[]{(byte) 0x84});

        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        Map<?, ?> result = (Map<?, ?>) body.get("result");
        Map<?, ?> evaluation = (Map<?, ?>) result.get("EvaluationResult");
        Map<?, ?> exUnits = (Map<?, ?>) evaluation.get("spend:0");
        assertEquals(100L, exUnits.get("memory"));
        assertEquals(200L, exUnits.get("steps"));
    }

    @Test
    void failureMessageKeepsPublicMessageStable() {
        IllegalStateException error = new IllegalStateException("Failed to resolve current slot from runtime",
                new IllegalStateException("current slot supplier returned -1"));

        assertEquals("Failed to resolve current slot from runtime",
                EvaluationResource.failureMessage(error));
    }

    @Test
    void failureMessageUnwrapsRuntimeSlotFailureFromEvaluatorWrapper() {
        RuntimeException error = new RuntimeException("Error evaluating transaction",
                new IllegalStateException("Failed to resolve current slot from runtime",
                        new IllegalStateException("current slot supplier returned -1")));

        assertEquals("Failed to resolve current slot from runtime",
                EvaluationResource.failureMessage(error));
    }
}
