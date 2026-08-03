package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedMapGoldenVectorsTest {
    private static final String RESOURCE =
            "META-INF/yano/contracts/authenticated-map/v1/golden-vectors.properties";

    @Test
    void checkedInVectorsExactlyMatchJavaContractsAndPinnedDependencies() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(input).as(RESOURCE).isNotNull();
            properties.load(input);
        }
        Map<String, String> checkedIn = new LinkedHashMap<>();
        properties.stringPropertyNames().forEach(name ->
                checkedIn.put(name, properties.getProperty(name)));

        assertThat(checkedIn).containsExactlyInAnyOrderEntriesOf(
                AuthenticatedMapGoldenVectorGenerator.vectors());
    }
}
