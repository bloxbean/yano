package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedMapAuthorizationGoldenVectorsTest {
    @Test
    void checkedInVectorsExactlyMatchFinalV1Contracts() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream(
                "/META-INF/yano/contracts/authenticated-map/v1/"
                        + "authorization-vectors.properties")) {
            properties.load(input);
        }
        Map<String, String> checkedIn = new TreeMap<>();
        properties.forEach((key, value) -> checkedIn.put(key.toString(), value.toString()));
        assertThat(checkedIn)
                .isEqualTo(AuthenticatedMapAuthorizationGoldenVectorGenerator.vectors());
    }
}
