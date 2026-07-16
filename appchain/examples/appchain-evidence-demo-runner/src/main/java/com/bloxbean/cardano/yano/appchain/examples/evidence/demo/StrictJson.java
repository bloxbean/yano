package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Shared duplicate-rejecting JSON parser for bounded external responses. */
final class StrictJson {
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(40L * 1024 * 1024)
                    .maxTokenCount(100_000)
                    .maxNestingDepth(8)
                    .maxStringLength(32 * 1024 * 1024)
                    .maxNameLength(128)
                    .maxNumberLength(32)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    static final ObjectMapper MAPPER = JsonMapper.builder(JSON_FACTORY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private StrictJson() {
    }

    static JsonNode parse(byte[] bytes) {
        try {
            JsonNode value = MAPPER.readTree(bytes);
            if (value == null) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
            return value;
        } catch (DemoException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }
}
