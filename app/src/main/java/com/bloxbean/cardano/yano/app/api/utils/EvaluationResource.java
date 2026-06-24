package com.bloxbean.cardano.yano.app.api.utils;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.model.TxEvaluationResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blockfrost/Ogmios-compatible Plutus script evaluation endpoint.
 * Returns computed ExUnits per redeemer for a given transaction.
 */
@Path("utils/txs")
@Produces(MediaType.APPLICATION_JSON)
public class EvaluationResource {

    private static final Logger log = LoggerFactory.getLogger(EvaluationResource.class);

    @Inject
    TxEvaluationGateway txEvaluationGateway;

    /**
     * Accepts raw CBOR bytes (application/cbor).
     */
    @POST
    @Path("/evaluate")
    @Consumes("application/cbor")
    public Response evaluateCbor(byte[] txCbor) {
        return doEvaluate(normalizeCborPayload(txCbor));
    }

    /**
     * Accepts hex-encoded CBOR as plain text.
     */
    @POST
    @Path("/evaluate")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response evaluateHex(String txHex) {
        if (txHex == null || txHex.isBlank()) {
            return errorResponse("Transaction CBOR required");
        }

        try {
            byte[] txCbor = HexUtil.decodeHexString(txHex.strip());
            return doEvaluate(txCbor);
        } catch (IllegalArgumentException e) {
            return errorResponse("Invalid hex string");
        }
    }

    private Response doEvaluate(byte[] txCbor) {
        if (txCbor == null || txCbor.length == 0) {
            return errorResponse("Transaction CBOR bytes required");
        }

        if (!txEvaluationGateway.isTransactionEvaluationAvailable()) {
            return errorResponse("Script evaluation not initialized. " +
                    "Ensure tx-evaluation is enabled and protocol parameters are configured.");
        }

        try {
            List<TxEvaluationResult> results = txEvaluationGateway.evaluateTransaction(txCbor);

            // Build Ogmios-compatible response
            Map<String, Object> evaluationResult = new LinkedHashMap<>();
            for (TxEvaluationResult r : results) {
                String key = r.tag() + ":" + r.index();
                Map<String, Long> exUnits = new LinkedHashMap<>();
                exUnits.put("memory", r.memory());
                exUnits.put("steps", r.steps());
                evaluationResult.put(key, exUnits);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("EvaluationResult", evaluationResult);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "jsonwsp/response");
            response.put("version", "1.0");
            response.put("servicename", "ogmios");
            response.put("methodname", "EvaluateTx");
            response.put("result", result);

            return Response.ok(response).build();
        } catch (Exception e) {
            log.warn("Script evaluation failed: {}", detailedFailureMessage(e), e);

            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("message", failureMessage(e));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("EvaluationFailure", failure);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "jsonwsp/response");
            response.put("version", "1.0");
            response.put("servicename", "ogmios");
            response.put("methodname", "EvaluateTx");
            response.put("result", result);

            return Response.ok(response).build();
        }
    }

    private Response errorResponse(String message) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("message", message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("EvaluationFailure", failure);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "jsonwsp/response");
        response.put("version", "1.0");
        response.put("servicename", "ogmios");
        response.put("methodname", "EvaluateTx");
        response.put("result", result);

        return Response.ok(response).build();
    }

    static byte[] normalizeCborPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return payload;
        }

        int start = 0;
        int end = payload.length;
        while (start < end && isAsciiWhitespace(payload[start])) {
            start++;
        }
        while (end > start && isAsciiWhitespace(payload[end - 1])) {
            end--;
        }

        int length = end - start;
        if (length == 0 || (length % 2) != 0) {
            return payload;
        }

        for (int i = start; i < end; i++) {
            if (!isAsciiHex(payload[i])) {
                return payload;
            }
        }

        byte[] trimmedPayload = payload;
        if (start != 0 || end != payload.length) {
            trimmedPayload = new byte[length];
            System.arraycopy(payload, start, trimmedPayload, 0, length);
        }

        return HexUtil.decodeHexString(new String(trimmedPayload, StandardCharsets.US_ASCII));
    }

    private static boolean isAsciiWhitespace(byte value) {
        return value == ' ' || value == '\n' || value == '\r' || value == '\t';
    }

    private static boolean isAsciiHex(byte value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    static String failureMessage(Throwable error) {
        if (error == null) return "Script evaluation failed";

        Throwable runtimeSlotFailure = findCause(error, "Failed to resolve current slot from runtime");
        if (runtimeSlotFailure != null) {
            return runtimeSlotFailure.getMessage();
        }

        String message = error.getMessage();
        return message != null ? message : error.getClass().getSimpleName();
    }

    private static String detailedFailureMessage(Throwable error) {
        if (error == null) return "Script evaluation failed";

        String message = failureMessage(error);
        Throwable cause = error.getCause();
        if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) {
            return message;
        }

        return message + ": " + cause.getMessage();
    }

    private static Throwable findCause(Throwable error, String message) {
        Throwable current = error;
        while (current != null) {
            if (message.equals(current.getMessage())) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }
}
