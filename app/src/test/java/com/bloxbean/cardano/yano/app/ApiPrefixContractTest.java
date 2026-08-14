package com.bloxbean.cardano.yano.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiPrefixContractTest {

    @Test
    void acceptsDefaultCustomAndRootBuildContracts() {
        for (String prefix : new String[]{"/api/v1", "/bf", "/"}) {
            ApiPrefixContract contract = new ApiPrefixContract(
                    prefix, prefix, "/", "/", prefix);

            contract.verify();

            assertEquals(prefix, contract.publicPrefix());
            assertEquals(prefix.equals("/") ? "" : prefix, contract.pathPrefix());
        }
    }

    @Test
    void rejectsEitherRuntimeValueWhenItDiffersFromTheMarker() {
        assertSanitizedMismatch(new ApiPrefixContract(
                "/runtime-secret-one", "/api/v1", "/", "/", "/api/v1"));
        assertSanitizedMismatch(new ApiPrefixContract(
                "/api/v1", "/runtime-secret-two", "/", "/", "/api/v1"));
        assertSanitizedMismatch(new ApiPrefixContract(
                "/api/v1", "/api/v1", "/runtime-secret-three", "/", "/api/v1"));
        assertSanitizedMismatch(new ApiPrefixContract(
                "/api/v1", "/api/v1", "/", "/runtime-secret-four", "/api/v1"));
    }

    @Test
    void rejectsNonCanonicalValuesWithoutRenderingThem() {
        ApiPrefixContract.ApiPrefixContractException failure = assertThrows(
                ApiPrefixContract.ApiPrefixContractException.class,
                () -> new ApiPrefixContract(
                        "/api/v1", "/api/v1", "/", "/", "/bad%2fsecret"));

        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(ApiPrefixContract.FAILURE_MESSAGE, failure.getMessage());

        ApiPrefixContract.ApiPrefixContractException oversized = assertThrows(
                ApiPrefixContract.ApiPrefixContractException.class,
                () -> new ApiPrefixContract("/api/v1", "/api/v1", "/", "/",
                        "/" + "a".repeat(ApiPrefixContract.MAX_PREFIX_LENGTH)));
        assertEquals(ApiPrefixContract.FAILURE_MESSAGE, oversized.getMessage());

        for (String alias : new String[]{"api/v1", "/api/v1/", " /api/v1"}) {
            assertThrows(ApiPrefixContract.ApiPrefixContractException.class,
                    () -> new ApiPrefixContract(
                            alias, "/api/v1", "/", "/", "/api/v1"));
        }
    }

    private static void assertSanitizedMismatch(ApiPrefixContract contract) {
        ApiPrefixContract.ApiPrefixContractException failure = assertThrows(
                ApiPrefixContract.ApiPrefixContractException.class, contract::verify);

        assertEquals(ApiPrefixContract.FAILURE_MESSAGE, failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        org.junit.jupiter.api.Assertions.assertFalse(
                failure.getMessage().contains("runtime-secret"));
    }
}
