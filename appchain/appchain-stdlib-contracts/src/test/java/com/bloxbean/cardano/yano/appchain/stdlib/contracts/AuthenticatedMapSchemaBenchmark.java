package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Reproducible single-process Phase-B encoding/schema evaluator microbenchmark. */
public final class AuthenticatedMapSchemaBenchmark {
    private static final int WARMUP_ITERATIONS = 20_000;

    private AuthenticatedMapSchemaBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: REPORT ITERATIONS");
        }
        Path report = Path.of(args[0]);
        int iterations = Integer.parseInt(args[1]);
        if (iterations < 10_000 || iterations > 10_000_000) {
            throw new IllegalArgumentException("iterations must be in [10000, 10000000]");
        }

        AuthenticatedMapSchema.Schema schema = productSchema();
        byte[] accepted = HexFormat.of().parseHex(
                "a3637174791a0001e24063736b7567736b752d30303166616374697665f5");
        byte[] rejected = HexFormat.of().parseHex(
                "a3637174791a000f424163736b7567736b752d30303166616374697665f5");
        for (int index = 0; index < WARMUP_ITERATIONS; index++) {
            encoding(accepted);
            schema.accepts(accepted);
            schema.accepts(rejected);
        }

        long encodingStarted = System.nanoTime();
        int encodingAccepted = 0;
        for (int index = 0; index < iterations; index++) {
            if (encoding(accepted)) encodingAccepted++;
        }
        long encodingNanos = System.nanoTime() - encodingStarted;

        long schemaStarted = System.nanoTime();
        int schemaAccepted = 0;
        for (int index = 0; index < iterations; index++) {
            if (schema.accepts(accepted)) schemaAccepted++;
        }
        long schemaNanos = System.nanoTime() - schemaStarted;

        if (encodingAccepted != iterations || schemaAccepted != iterations
                || schema.accepts(rejected)) {
            throw new IllegalStateException("schema benchmark corpus verdict is invalid");
        }
        double encodingPerValue = encodingNanos / (double) iterations;
        double schemaPerValue = schemaNanos / (double) iterations;
        String json = """
                {
                  "schema": "yano-adr025.1-phase-b-schema-benchmark-v1",
                  "scope": "single-process in-memory encoding/schema evaluator; not production bounds",
                  "iterations": %d,
                  "warmupIterations": %d,
                  "encodingOnlyNanosPerValue": %.3f,
                  "schemaNanosPerValue": %.3f,
                  "schemaToEncodingRatio": %.3f
                }
                """.formatted(iterations, WARMUP_ITERATIONS, encodingPerValue,
                schemaPerValue, schemaPerValue / encodingPerValue);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, json, StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT,
                "ADR-025.1 Phase B benchmark: encoding=%.1f ns/value schema=%.1f "
                        + "ns/value ratio=%.2f report=%s%n",
                encodingPerValue, schemaPerValue, schemaPerValue / encodingPerValue,
                report.toAbsolutePath());
    }

    private static boolean encoding(byte[] value) {
        return AuthenticatedMapContract.valueEncodingAccepts(
                AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR,
                value, AuthenticatedMapContract.MAX_VALUE_BYTES);
    }

    private static AuthenticatedMapSchema.Schema productSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.MapNode(List.of(
                new AuthenticatedMapSchema.MapField("sku", true,
                        new AuthenticatedMapSchema.TextNode(1, 64, null)),
                new AuthenticatedMapSchema.MapField("qty", true,
                        new AuthenticatedMapSchema.IntegerNode(
                                AuthenticatedMapSchema.INTEGER_UINT,
                                BigInteger.ZERO, BigInteger.valueOf(1_000_000))),
                new AuthenticatedMapSchema.MapField("active", false,
                        AuthenticatedMapSchema.BooleanNode.any()))));
    }
}
